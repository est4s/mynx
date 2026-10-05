package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationTest {
    private val base = createTempDirectory("rotation").toFile()
    private val proc = File(base, "proc")
    private val locks = RotationLocks(proc)

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    // /proc/PID/stat: the start time is field 22, counted after the
    // name in brackets, which may itself hold spaces and brackets.
    private fun process(pid: Int, started: Long, name: String = "inclinometer") {
        File(proc, "$pid").mkdirs()
        val after = (3..21).joinToString(" ") { if (it == 3) "S" else "0" }
        File(proc, "$pid/stat").writeText("$pid ($name) $after $started 0 0\n")
    }

    private fun gone(pid: Int) = File(proc, "$pid").deleteRecursively()

    @Test
    fun `unlocked to begin with`() {
        assertNull(locks.orientation)
    }

    @Test
    fun `a process locks the rotation as it is, or to an orientation`() {
        process(100, started = 5)
        assertNull(locks.lock(100, Orientation.CURRENT))
        assertEquals(Orientation.CURRENT, locks.orientation)

        assertNull(locks.lock(100, Orientation.LANDSCAPE))
        assertEquals(Orientation.LANDSCAPE, locks.orientation)
    }

    @Test
    fun `the newest lock decides the orientation`() {
        process(100, started = 5)
        process(200, started = 6)
        locks.lock(100, Orientation.PORTRAIT)
        locks.lock(200, Orientation.LANDSCAPE)

        assertEquals(Orientation.LANDSCAPE, locks.orientation)
        gone(200)
        assertTrue(locks.sweep())
        assertEquals(Orientation.PORTRAIT, locks.orientation)
    }

    @Test
    fun `a process that doesn't run can't lock`() {
        assertEquals("no process 100", locks.lock(100, Orientation.CURRENT))
        assertNull(locks.orientation)
    }

    @Test
    fun `the lock ends when its process ends`() {
        process(100, started = 5)
        locks.lock(100, Orientation.CURRENT)

        assertFalse(locks.sweep())
        assertEquals(Orientation.CURRENT, locks.orientation)
        gone(100)
        assertTrue(locks.sweep())
        assertNull(locks.orientation)
    }

    @Test
    fun `a new process that got the pid doesn't keep the lock`() {
        process(100, started = 5)
        locks.lock(100, Orientation.CURRENT)
        process(100, started = 9)

        assertTrue(locks.sweep())
        assertNull(locks.orientation)
    }

    @Test
    fun `reads the start time after a name with spaces and brackets`() {
        process(100, started = 5, name = "my (odd) app")
        locks.lock(100, Orientation.CURRENT)

        assertFalse(locks.sweep())
    }

    @Test
    fun `unlocking ends every lock`() {
        process(100, started = 5)
        process(200, started = 6)
        locks.lock(100, Orientation.CURRENT)
        locks.lock(200, Orientation.CURRENT)

        assertTrue(locks.unlock())
        assertNull(locks.orientation)
        assertFalse(locks.unlock())
    }
}

class RotationRequestTest {
    private val base = createTempDirectory("rotation").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val proc = File(base, "proc")
    private val locks = RotationLocks(proc)
    private val changes = mutableListOf<Orientation?>()
    private val requests = PocketRequests(dir, home, rotation = locks, rotationChanged = { changes += it })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun process(pid: Int) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/stat").writeText("$pid (app) S ${"0 ".repeat(18)}7 0\n")
    }

    private fun send(vararg lines: String): String {
        File(dir, "8-1.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "8-1.reply").readText()
    }

    @Test
    fun `locks for a process and says so`() {
        process(42)

        assertEquals("""{"ok":true,"locked":"current"}""", send("rotation", "lock", "42"))
        assertEquals(listOf<Orientation?>(Orientation.CURRENT), changes)
        assertEquals("""{"ok":true,"locked":"landscape"}""", send("rotation", "lock", "42", "landscape"))
    }

    @Test
    fun `unlocks`() {
        process(42)
        send("rotation", "lock", "42")

        assertEquals("""{"ok":true,"locked":"no"}""", send("rotation", "unlock"))
        assertEquals(listOf(Orientation.CURRENT, null), changes)
    }

    @Test
    fun `says whether it's locked`() {
        assertEquals("""{"ok":true,"locked":"no"}""", send("rotation", "status"))
        assertEquals(emptyList(), changes)
    }

    @Test
    fun `refuses what it doesn't know`() {
        assertEquals("""{"ok":false,"error":"no process 42"}""", send("rotation", "lock", "42"))
        assertEquals(
            """{"ok":false,"error":"rotation lock PID [portrait|landscape] | unlock | status"}""",
            send("rotation", "lock", "42", "sideways"),
        )
        assertEquals(
            """{"ok":false,"error":"rotation lock PID [portrait|landscape] | unlock | status"}""",
            send("rotation", "spin"),
        )
        assertEquals(emptyList(), changes)
    }
}
