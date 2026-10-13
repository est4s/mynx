package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestartPolicyTest {
    @Test
    fun `on-failure restarts after a non-zero exit, not after a clean one`() {
        val policy = RestartPolicy(Restart.ON_FAILURE)
        assertEquals(1000L, policy.after(exitCode = 1, ranMs = 100))
        assertNull(policy.after(exitCode = 0, ranMs = 100))
        assertFalse(policy.gaveUp)
    }

    @Test
    fun `an unknown exit code counts as a failure`() {
        assertEquals(1000L, RestartPolicy(Restart.ON_FAILURE).after(exitCode = null, ranMs = 100))
    }

    @Test
    fun `always restarts after any exit, no never does`() {
        assertEquals(1000L, RestartPolicy(Restart.ALWAYS).after(exitCode = 0, ranMs = 100))
        assertNull(RestartPolicy(Restart.NO).after(exitCode = 1, ranMs = 100))
        assertFalse(RestartPolicy(Restart.NO).apply { after(1, 100) }.gaveUp)
    }

    @Test
    fun `the wait doubles from the first delay, up to a minute`() {
        val policy = RestartPolicy(Restart.ALWAYS, limit = 0, firstDelayMs = 3000)
        assertEquals(listOf(3000L, 6000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L),
            List(7) { policy.after(1, 0) })
    }

    @Test
    fun `a run of 30 seconds or more starts the count over`() {
        val policy = RestartPolicy(Restart.ALWAYS)
        policy.after(1, 0)
        policy.after(1, 0)
        assertEquals(1000L, policy.after(1, 30_000))
    }

    @Test
    fun `gives up after the limit of restarts in a row`() {
        val policy = RestartPolicy(Restart.ON_FAILURE, limit = 2)
        assertEquals(1000L, policy.after(1, 0))
        assertEquals(2000L, policy.after(1, 0))
        assertNull(policy.after(1, 0))
        assertTrue(policy.gaveUp)
    }

    @Test
    fun `a long run before the limit keeps it going`() {
        val policy = RestartPolicy(Restart.ON_FAILURE, limit = 2)
        policy.after(1, 0)
        policy.after(1, 0)
        assertEquals(1000L, policy.after(1, 60_000))
        assertFalse(policy.gaveUp)
    }

    @Test
    fun `reset starts the count over`() {
        val policy = RestartPolicy(Restart.ON_FAILURE, limit = 1)
        policy.after(1, 0)
        assertNull(policy.after(1, 0))
        policy.reset()
        assertFalse(policy.gaveUp)
        assertEquals(1000L, policy.after(1, 0))
    }

    @Test
    fun `from a service's settings`() {
        val policy = RestartPolicy.of(ServiceDef("web", "run", restart = Restart.ALWAYS, restartLimit = 1, restartDelay = 5))
        assertEquals(5000L, policy.after(0, 0))
        assertNull(policy.after(0, 0))
    }
}
