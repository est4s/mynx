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

    // A tab that never started has pid 0 (and -1 once it ended): every untraced
    // process has TracerPid 0, the app itself included, and kill(0) is its group.
    @Test
    fun `a proot that never started kills nothing`() {
        process(9358, tracer = 0)
        val killed = mutableListOf<Int>()

        killProot(0, proc) { pid -> killed += pid }
        killProot(-1, proc) { pid -> killed += pid }

        assertEquals(emptyList(), killed)
    }

    private fun child(pid: Int, parent: Int, tracer: Int) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/status").writeText("Name:\tclaude\nPPid:\t$parent\nTracerPid:\t$tracer\n")
    }

    @Test
    fun `the agent is what proot started, not the programs the agent runs`() {
        child(9358, parent = 9003, tracer = 9003)
        child(9400, parent = 9358, tracer = 9003)
        assertEquals(listOf(9358), agentProcesses(9003, proc))
    }

    @Test
    fun `with no parent to go by, the tracees count as the agent`() {
        child(9400, parent = 1, tracer = 9003)
        assertEquals(listOf(9400), agentProcesses(9003, proc))
    }

    private val agentSignals = mutableListOf<Pair<Int, Int>>()

    @Test
    fun `an agent gets TERM and nothing more if it ends in its grace`() {
        child(9358, parent = 9003, tracer = 9003)
        val slept = mutableListOf<Long>()
        stopAgentProot(9003, signal = { p, s -> agentSignals += p to s }, alive = { false }, sleep = { slept += it }, proc)
        assertEquals(listOf(9358 to 15), agentSignals)
        assertEquals(listOf(AGENT_STOP_GRACE_MS), slept)
    }

    @Test
    fun `an agent that stays gets QUIT through its proot, then the force`() {
        child(9358, parent = 9003, tracer = 9003)
        val slept = mutableListOf<Long>()
        stopAgentProot(9003, signal = { p, s -> agentSignals += p to s }, alive = { true }, sleep = { slept += it }, proc)
        assertEquals(listOf(9358 to 15, 9003 to 3, 9358 to 9, 9003 to 9), agentSignals)
        assertEquals(listOf(AGENT_STOP_GRACE_MS, PROOT_STOP_GRACE_MS), slept)
    }

    @Test
    fun `a proot that went during QUIT's grace isn't forced`() {
        child(9358, parent = 9003, tracer = 9003)
        var checks = 0
        stopAgentProot(9003, signal = { p, s -> agentSignals += p to s }, alive = { ++checks == 1 }, sleep = {}, proc)
        assertEquals(listOf(9358 to 15, 9003 to 3), agentSignals)
    }
}
