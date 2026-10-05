package io.github.est4s.terminal.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRequestTest {
    private val base = createTempDirectory("audio").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val rootfs = File(base, "debian")
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val played = mutableListOf<Pair<PlayQuery, PlayReport>>()
    private val recorded = mutableListOf<Pair<RecordQuery, RecordReport>>()
    private val gone = mutableSetOf<Int>()
    private val requests = PocketRequests(
        dir, home,
        later = audioRequests(home, { q, r -> played += q to r }, { q, r -> recorded += q to r }),
        alive = { it !in gone },
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `plays a file until it ends`() {
        File(home, "a.mp3").writeText("mp3")
        send("8-1", "audio-play", "/root/a.mp3")
        assertEquals("stream", file("8-1.wait").readText())
        val (query, report) = played.single()
        assertEquals(PlayQuery(File(home, "a.mp3"), "/root/a.mp3"), query)
        report.done(3.26)
        assertEquals("""{"ok":true,"file":"/root/a.mp3","seconds":3.3}""", file("8-1.reply").readText())
    }

    @Test
    fun `playing stops when pocket stops waiting`() {
        File(home, "a.mp3").writeText("mp3")
        send("8-1", "audio-play", "/root/a.mp3")
        var stopped = false
        played.single().second.onCancel { stopped = true }
        file("8-1.cancel").writeText("")
        requests.sweep()
        assertTrue(stopped)
        assertEquals("""{"ok":true}""", file("8-1.reply").readText())
    }

    @Test
    fun `refuses to play what isn't a file`() {
        File(home, "music").mkdirs()
        send("8-1", "audio-play")
        send("8-2", "audio-play", "a.mp3")
        send("8-3", "audio-play", "/root/music")
        send("8-4", "audio-play", "/root/b.mp3")
        assertTrue(played.isEmpty())
        assertEquals("""{"ok":false,"error":"audio play needs a file to play"}""", file("8-1.reply").readText())
        assertEquals("""{"ok":false,"error":"the path must start with /: a.mp3"}""", file("8-2.reply").readText())
        assertEquals("""{"ok":false,"error":"/root/music is a folder"}""", file("8-3.reply").readText())
        assertEquals("""{"ok":false,"error":"no such file: /root/b.mp3"}""", file("8-4.reply").readText())
    }

    @Test
    fun `records in the format the file name asks for`() {
        send("8-1", "audio-record", "/root/a.m4a")
        send("8-2", "audio-record", "/root/b.WAV", "seconds=5", "rate=16000")
        send("8-3", "audio-record", "/root/c.opus")
        send("8-4", "audio-record", "/root/d.aac")
        assertEquals("stream", file("8-1.wait").readText())
        assertEquals(
            listOf(
                RecordQuery(File(home, "a.m4a"), "/root/a.m4a", AudioFormat.M4A, rate = 44100, seconds = null),
                RecordQuery(File(home, "b.WAV"), "/root/b.WAV", AudioFormat.WAV, rate = 16000, seconds = 5),
                RecordQuery(File(home, "c.opus"), "/root/c.opus", AudioFormat.OGG, rate = 48000, seconds = null),
                RecordQuery(File(home, "d.aac"), "/root/d.aac", AudioFormat.AAC, rate = 44100, seconds = null),
            ),
            recorded.map { it.first },
        )
        assertEquals(File(home, ".a.m4a.part"), recorded.first().first.partial)
    }

    @Test
    fun `says when the microphone is on`() {
        send("8-1", "audio-record", "/root/a.m4a")
        recorded.single().second.started()
        assertEquals("{\"recording\":true}\n", file("8-1.stream").readText())
    }

    @Test
    fun `a finished recording replaces the file`() {
        File(home, "a.m4a").writeText("old")
        send("8-1", "audio-record", "/root/a.m4a", "seconds=5")
        val (query, report) = recorded.single()
        query.partial.writeText("new audio")
        report.recorded(5.0)
        assertEquals("new audio", File(home, "a.m4a").readText())
        assertFalse(query.partial.exists())
        assertEquals("""{"ok":true,"file":"/root/a.m4a","bytes":9,"seconds":5}""", file("8-1.reply").readText())
    }

    @Test
    fun `Ctrl+C ends a recording and says what was saved`() {
        send("8-1", "audio-record", "/root/a.wav")
        val (query, report) = recorded.single()
        report.onStop {
            query.partial.writeText("pcm")
            report.recorded(2.04)
        }
        file("8-1.cancel").writeText("")
        requests.sweep()
        assertEquals("pcm", File(home, "a.wav").readText())
        assertEquals("""{"ok":true,"file":"/root/a.wav","bytes":3,"seconds":2}""", file("8-1.reply").readText())
    }

    @Test
    fun `a recording whose pocket has gone is still saved`() {
        send("77-1", "audio-record", "/root/a.wav")
        val (query, report) = recorded.single()
        report.onStop {
            query.partial.writeText("pcm")
            report.recorded(1.0)
        }
        gone += 77
        requests.sweep()
        assertEquals("pcm", File(home, "a.wav").readText())
    }

    @Test
    fun `a failed recording leaves the file alone`() {
        File(home, "a.m4a").writeText("old")
        send("8-1", "audio-record", "/root/a.m4a")
        val (query, report) = recorded.single()
        query.partial.writeText("half")
        report.fail("the microphone is busy")
        assertEquals("old", File(home, "a.m4a").readText())
        assertFalse(query.partial.exists())
        assertEquals("""{"ok":false,"error":"the microphone is busy"}""", file("8-1.reply").readText())
    }

    @Test
    fun `refuses recordings it can't make`() {
        File(home, "clips").mkdirs()
        send("8-1", "audio-record")
        send("8-2", "audio-record", "/root/clips")
        send("8-3", "audio-record", "/root/nowhere/a.m4a")
        send("8-4", "audio-record", "/root/a.mp3")
        send("8-5", "audio-record", "/root/a")
        send("8-6", "audio-record", "/root/a.m4a", "seconds=0")
        send("8-7", "audio-record", "/root/a.m4a", "rate=4000")
        send("8-8", "audio-record", "/root/a.m4a", "loud")
        assertTrue(recorded.isEmpty())
        assertEquals("""{"ok":false,"error":"audio record needs a file to save to"}""", file("8-1.reply").readText())
        assertEquals("""{"ok":false,"error":"/root/clips is a folder"}""", file("8-2.reply").readText())
        assertEquals("""{"ok":false,"error":"no such folder: /root/nowhere"}""", file("8-3.reply").readText())
        val formats = "the file name must end in .m4a, .aac, .ogg, .opus or .wav"
        assertEquals("""{"ok":false,"error":"$formats"}""", file("8-4.reply").readText())
        assertEquals("""{"ok":false,"error":"$formats"}""", file("8-5.reply").readText())
        assertEquals("""{"ok":false,"error":"the length is 1 to 86400 seconds"}""", file("8-6.reply").readText())
        assertEquals("""{"ok":false,"error":"the rate is 8000 to 48000 Hz"}""", file("8-7.reply").readText())
        assertEquals("""{"ok":false,"error":"unknown record option 'loud'"}""", file("8-8.reply").readText())
    }

    @Test
    fun `no recording while android-microphone is off, but playing works`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-microphone = off\n")
        File(home, "a.mp3").writeText("mp3")
        send("8-1", "audio-record", "/root/a.m4a")
        send("8-2", "audio-play", "/root/a.mp3")
        assertTrue(recorded.isEmpty())
        assertEquals(
            """{"ok":false,"error":"the microphone is off (pocket set android-microphone on)"}""",
            file("8-1.reply").readText(),
        )
        assertEquals(1, played.size)
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
}

class WavTest {
    @Test
    fun `a WAV header describes 16-bit PCM`() {
        val header = ByteBuffer.wrap(wavHeader(rate = 16000, channels = 1, dataBytes = 32000)).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int) = String(header.array(), at, 4, Charsets.US_ASCII)
        assertEquals(44, header.capacity())
        assertEquals("RIFF", text(0))
        assertEquals(36 + 32000, header.getInt(4))
        assertEquals("WAVE", text(8))
        assertEquals("fmt ", text(12))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())      // PCM
        assertEquals(1, header.getShort(22).toInt())      // channels
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))            // bytes per second
        assertEquals(2, header.getShort(32).toInt())      // bytes per frame
        assertEquals(16, header.getShort(34).toInt())     // bits
        assertEquals("data", text(36))
        assertEquals(32000, header.getInt(40))
    }
}
