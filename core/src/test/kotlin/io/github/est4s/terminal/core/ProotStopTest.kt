package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProotStopTest {
    private val signals = mutableListOf<Pair<Int, Int>>()
    private val later = mutableListOf<Long>()
    private var forced = 0

    private fun stop(pid: Int?) = stopProot(
        pid,
        signal = { p, s -> signals += p to s },
        later = { ms, action -> later += ms; action() },
        force = { forced++ },
    )

    @Test
    fun `a proot gets QUIT, which makes it kill what it runs, and is forced only later`() {
        stop(1234)
        assertEquals(listOf(1234 to 3), signals)
        assertEquals(listOf(PROOT_STOP_GRACE_MS), later)
        assertEquals(1, forced)
    }

    @Test
    fun `a proot that hasn't started or has ended gets no signal, only the force`() {
        stop(0)
        stop(-1)
        stop(null)
        assertEquals(emptyList(), signals)
        assertEquals(3, forced)
    }

    @Test
    fun `a failed signal still forces it later`() {
        stopProot(1234, signal = { _, _ -> error("ESRCH") }, later = { _, action -> action() }, force = { forced++ })
        assertEquals(1, forced)
    }

    // Android's Process has no pid(); its toString() names it.
    @Test
    fun `reads a process's pid from how Android describes it`() {
        assertEquals(9003, processPid("Process[pid=9003, hasExited=false]"))
        assertNull(processPid("java.lang.UNIXProcess@1f2e3d"))
    }

    private val proc = createTempDirectory("proc").toFile()

    private fun process(pid: Int, tracer: Int) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/status").writeText("Name:\tsh\nState:\tt (tracing stop)\nTracerPid:\t$tracer\nPPid:\t1\n")
    }

    @Test
    fun `finds the programs a proot traces, wherever their parent went`() {
        process(9358, tracer = 9003)
        process(9364, tracer = 9361)
        process(9400, tracer = 9003)
        File(proc, "self").mkdirs()

        assertEquals(listOf(9358, 9400), prootTracees(9003, proc).sorted())
    }

    // A hung proot can block QUIT while a program it traces is stopped:
    // killing the program frees it, and killing proot first would leave
    // the program running untraced.
    @Test
    fun `killing a proot kills what it traces first`() {
        process(9358, tracer = 9003)
        val killed = mutableListOf<Int>()

        killProot(9003, proc) { pid -> killed += pid }

        assertEquals(listOf(9358, 9003), killed)
    }
}
