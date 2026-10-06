package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
