package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MicTest {
    private val base = createTempDirectory("mic").toFile()
    private val proc = File(base, "proc")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun process(pid: Int, comm: String) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/comm").writeText("$comm\n")
    }

    // As sound-watch writes it: `pactl list short sources`, a blank line,
    // `pactl list source-outputs`.
    private fun list(vararg outputs: String) = """
        |0	phone.monitor	module-pipe-sink.c	s16le 2ch 48000Hz	SUSPENDED
        |1	mic	module-pipe-source.c	s16le 1ch 48000Hz	RUNNING
        |
        |${outputs.joinToString("\n")}
    """.trimMargin()

    private fun output(n: Int, source: Int, binary: String, pid: Int, corked: Boolean = false, name: String = binary) = """
        |Source Output #$n
        |	Driver: protocol-native.c
        |	Source: $source
        |	Sample Specification: s16le 1ch 48000Hz
        |	Corked: ${if (corked) "yes" else "no"}
        |	Mute: no
        |	Properties:
        |		application.name = "$name"
        |		application.process.id = "$pid"
        |		application.process.binary = "$binary"
    """.trimMargin()

    @Test
    fun `nobody records from an empty list or none at all`() {
        assertEquals(emptyList(), micUsers(list(), proc))
        assertEquals(emptyList(), micUsers("", proc))
    }

    @Test
    fun `names the programs recording from the microphone by their process`() {
        process(100, "arecord")
        process(200, "sox")

        assertEquals(
            listOf("arecord", "sox"),
            micUsers(list(output(0, 1, "aplay", 100), output(1, 1, "sox", 200)), proc),
        )
    }

    @Test
    fun `falls back to Pulse's name for a program that has gone`() {
        assertEquals(listOf("pacat"), micUsers(list(output(0, 1, "pacat", 300)), proc))
    }

    @Test
    fun `recording what plays doesn't need the microphone`() {
        assertEquals(emptyList(), micUsers(list(output(0, 0, "pacat", 300)), proc))
    }

    @Test
    fun `a paused recording doesn't need the microphone`() {
        assertEquals(emptyList(), micUsers(list(output(0, 1, "pacat", 300, corked = true)), proc))
    }

    @Test
    fun `names a program once however many streams it has`() {
        assertEquals(listOf("pacat"), micUsers(list(output(0, 1, "pacat", 300), output(1, 1, "pacat", 300)), proc))
    }

    @Test
    fun `no microphone source, nobody records from it`() {
        val list = "0\tphone.monitor\tmodule-pipe-sink.c\n\n" + output(0, 1, "pacat", 300)

        assertEquals(emptyList(), micUsers(list, proc))
    }

    @Test
    fun `the microphone is off while nobody records`() {
        assertEquals(Mic.Off, micState(emptyList(), allowed = true, permitted = true, canStart = true))
        assertEquals(Mic.Off, micState(emptyList(), allowed = false, permitted = false, canStart = false))
    }

    @Test
    fun `the microphone is on while a program records`() {
        assertEquals(Mic.On(listOf("arecord")), micState(listOf("arecord"), allowed = true, permitted = true, canStart = true))
    }

    @Test
    fun `programs get silence when the setting is off`() {
        assertEquals(
            Mic.Silent(listOf("arecord"), "android-microphone is off"),
            micState(listOf("arecord"), allowed = false, permitted = true, canStart = true),
        )
    }

    @Test
    fun `programs get silence when Android hasn't allowed the microphone`() {
        assertEquals(
            Mic.Silent(listOf("arecord"), "the microphone isn't allowed: open the app to allow it"),
            micState(listOf("arecord"), allowed = true, permitted = false, canStart = true),
        )
    }

    @Test
    fun `programs get silence when Android won't let the app start the microphone`() {
        assertEquals(
            Mic.Silent(listOf("arecord"), "open the app to start it (Android only lets the app in use start the microphone)"),
            micState(listOf("arecord"), allowed = true, permitted = true, canStart = false),
        )
    }

    @Test
    fun `the notice names who is listening`() {
        assertNull(micNotice(Mic.Off))
        assertEquals("Microphone: arecord, sox", micNotice(Mic.On(listOf("arecord", "sox"))))
        assertEquals(
            "Microphone blocked for arecord: android-microphone is off",
            micNotice(Mic.Silent(listOf("arecord"), "android-microphone is off")),
        )
    }

    @Test
    fun `silence comes in 20 ms chunks at the microphone's pace`() {
        var now = 1000L
        val slept = mutableListOf<Long>()
        val silence = Silence(now = { now }, sleep = { slept += it; now += it })
        val buffer = ByteArray(MIC_CHUNK) { 7 }

        assertEquals(MIC_CHUNK, silence.read(buffer))
        assertContentEquals(ByteArray(MIC_CHUNK), buffer)
        now += 5
        assertEquals(MIC_CHUNK, silence.read(buffer))
        now += 30
        assertEquals(MIC_CHUNK, silence.read(buffer))

        assertEquals(listOf(15L), slept)
        assertEquals(1920, MIC_CHUNK)
    }
}
