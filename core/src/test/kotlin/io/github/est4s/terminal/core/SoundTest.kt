package io.github.est4s.terminal.core

import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundTest {
    private class FakeOut : SoundOut {
        val events = mutableListOf<String>()
        override fun play() { events += "play" }
        override fun pause() { events += "pause" }
        override fun write(buffer: ByteArray, length: Int) {
            events += "write " + buffer.take(length).joinToString(",")
        }
    }

    // Hands out one chunk per read, then the end of the stream.
    private class Chunks(vararg chunks: List<Int>) : InputStream() {
        private val left = chunks.toMutableList()
        var beforeRead: () -> Unit = {}
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            beforeRead()
            val chunk = left.removeFirstOrNull() ?: return -1
            chunk.forEachIndexed { i, v -> b[off + i] = v.toByte() }
            return chunk.size
        }
    }

    private var now = 0L
    private val out = FakeOut()
    private val player = PipePlayer(out, now = { now }, idleMs = 500)

    @Test
    fun `plays what the pipe gives in whole frames`() {
        player.play(Chunks(listOf(1, 2, 3, 4, 5, 6), listOf(7, 8)))

        assertEquals(listOf("play", "write 1,2,3,4", "write 5,6,7,8", "pause"), out.events)
    }

    @Test
    fun `drops a broken frame at the end`() {
        player.play(Chunks(listOf(1, 2, 3, 4, 5)))

        assertEquals(listOf("play", "write 1,2,3,4", "pause"), out.events)
    }

    @Test
    fun `starts playing only when sound comes`() {
        player.play(Chunks())

        assertEquals(emptyList(), out.events)
        assertFalse(player.playing)
    }

    @Test
    fun `pauses when the pipe stays quiet and plays again when sound comes`() {
        val input = Chunks(listOf(1, 2, 3, 4), listOf(5, 6, 7, 8))
        var reads = 0
        input.beforeRead = {
            if (reads++ == 1) {
                now = 499
                player.pauseIfIdle()
                assertTrue(player.playing)
                now = 500
                player.pauseIfIdle()
                assertFalse(player.playing)
            }
        }

        player.play(input)

        assertEquals(listOf("play", "write 1,2,3,4", "pause", "play", "write 5,6,7,8", "pause"), out.events)
    }

    @Test
    fun `a pause while idle does nothing`() {
        player.pauseIfIdle()

        assertEquals(emptyList(), out.events)
    }

    @Test
    fun `waits for the pipe and opens it again after it ends`() {
        val opened = mutableListOf<String>()
        val pipes = mutableListOf<InputStream?>(null, Chunks(listOf(1, 2, 3, 4)), null, Chunks(listOf(5, 6, 7, 8)))
        var stopped = false

        player.run(
            open = { pipes.removeFirstOrNull().also { opened += if (it == null) "none" else "pipe" } },
            stopped = { stopped },
            sleep = { ms ->
                opened += "sleep $ms"
                if (pipes.isEmpty()) stopped = true
            },
        )

        assertEquals(listOf("none", "sleep 1000", "pipe", "none", "sleep 1000", "pipe", "none", "sleep 1000"), opened)
        assertEquals(
            listOf("play", "write 1,2,3,4", "pause", "play", "write 5,6,7,8", "pause"),
            out.events,
        )
    }

    @Test
    fun `restarts the sound server sooner after a long run`() {
        val restarts = ServerRestarts()

        assertEquals(1000L, restarts.after(exitCode = 1, ranMs = 100))
        assertEquals(2000L, restarts.after(exitCode = 1, ranMs = 100))
        assertEquals(4000L, restarts.after(exitCode = 1, ranMs = 100))
        assertEquals(1000L, restarts.after(exitCode = 1, ranMs = 60_000))
    }

    @Test
    fun `waits at most a minute between restarts`() {
        val restarts = ServerRestarts()

        repeat(10) { restarts.after(exitCode = 1, ranMs = 0) }

        assertEquals(60_000L, restarts.after(exitCode = 1, ranMs = 0))
    }

    @Test
    fun `doesn't restart a sound server that isn't installed`() {
        assertNull(ServerRestarts().after(exitCode = SOUND_NOT_INSTALLED, ranMs = 0))
    }
}

class SoundRequestTest {
    private val base = createTempDirectory("sound").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private var starts = 0
    private var refusal: String? = null
    private val requests = Pc26Requests(dir, home, startSound = { starts++; refusal })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `starts the sound server`() {
        send("8-1", "sound-start")

        assertEquals(1, starts)
        assertEquals("""{"ok":true}""", File(dir, "8-1.reply").readText())
    }

    @Test
    fun `says why the sound server didn't start`() {
        refusal = "PulseAudio isn't installed"
        send("8-1", "sound-start")

        assertEquals("""{"ok":false,"error":"PulseAudio isn't installed"}""", File(dir, "8-1.reply").readText())
    }

    @Test
    fun `doesn't start the sound server while the setting is off`() {
        File(home, ".config/pc26").mkdirs()
        File(home, ".config/pc26/settings.conf").writeText("sound-device = off\n")
        send("8-1", "sound-start")

        assertEquals(0, starts)
        assertEquals(
            """{"ok":false,"error":"the sound device is off (pc26 set sound-device on)"}""",
            File(dir, "8-1.reply").readText(),
        )
    }

    private val pidFile = File(base, "pid")
    private val proc = File(base, "proc")

    private fun process(pid: Int, comm: String) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/comm").writeText("$comm\n")
    }

    @Test
    fun `finds the running sound server by its pid file`() {
        pidFile.writeText("4242\n")
        process(4242, "pulseaudio")

        assertEquals(4242, soundServerPid(pidFile, proc))
    }

    @Test
    fun `finds no sound server without a pid file or a process`() {
        assertNull(soundServerPid(pidFile, proc))
        pidFile.writeText("4242\n")
        assertNull(soundServerPid(pidFile, proc))
        pidFile.writeText("garbage\n")
        assertNull(soundServerPid(pidFile, proc))
    }

    @Test
    fun `never takes another program that got the pid for the sound server`() {
        pidFile.writeText("4242\n")
        process(4242, "bash")

        assertNull(soundServerPid(pidFile, proc))
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }
}
