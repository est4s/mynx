package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeProcTest {
    private val dir = File(createTempDirectory("fake-proc").toFile(), "fake-proc")

    @AfterTest
    fun cleanup() {
        dir.parentFile.deleteRecursively()
    }

    @Test
    fun `fakes only the proc files that can't be read`() {
        val fakes = writeFakeProc(dir, cpus = 8) { it != "/proc/stat" && it != "/proc/vmstat" }

        assertEquals(
            mapOf("/proc/stat" to File(dir, "stat").path, "/proc/vmstat" to File(dir, "vmstat").path),
            fakes,
        )
        assertFalse(File(dir, "loadavg").exists())
    }

    @Test
    fun `fake stat has a total line and one line per cpu`() {
        writeFakeProc(dir, cpus = 4) { false }

        val cpuLines = File(dir, "stat").readLines().filter { it.startsWith("cpu") }
        assertEquals(listOf("cpu", "cpu0", "cpu1", "cpu2", "cpu3"), cpuLines.map { it.substringBefore(' ') })
        // user nice system idle iowait irq softirq steal guest guest_nice
        assertTrue(cpuLines.all { it.substringAfter(' ').trim().split(Regex(" +")).size == 10 })
    }

    @Test
    fun `fakes every file tools commonly read`() {
        val fakes = writeFakeProc(dir, cpus = 1) { false }

        assertEquals(
            setOf("/proc/loadavg", "/proc/stat", "/proc/uptime", "/proc/version", "/proc/vmstat"),
            fakes.keys,
        )
        assertTrue(fakes.values.all { File(it).readText().endsWith("\n") })
    }
}
