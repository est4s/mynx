package io.github.est4s.terminal.core

private const val MAX_DELAY_MS = 60_000L
// A run this long wasn't a quick failure: the count starts over.
private const val LONG_RUN_MS = 30_000L

/**
 * When to start a program again after it ended: per [restart], after
 * [firstDelayMs], doubling with each quick failure in a row up to a
 * minute. After [limit] restarts in a row that each ended quickly it
 * gives up ([gaveUp]); 0 never does.
 */
class RestartPolicy(
    private val restart: Restart,
    private val limit: Int = 0,
    private val firstDelayMs: Long = 1000,
) {
    private var failures = 0

    /** Whether the last [after] said no because of the [limit]. */
    var gaveUp = false
        private set

    /** The wait before starting again, or null: don't. [exitCode] null is unknown, a failure. */
    fun after(exitCode: Int?, ranMs: Long): Long? {
        gaveUp = false
        val again = when (restart) {
            Restart.NO -> false
            Restart.ON_FAILURE -> exitCode != 0
            Restart.ALWAYS -> true
        }
        if (!again) return null
        if (ranMs >= LONG_RUN_MS) failures = 0
        if (limit > 0 && failures >= limit) {
            gaveUp = true
            return null
        }
        val delay = firstDelayMs shl failures.coerceAtMost(16)
        failures++
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    /** Starts the count over, as when the program is started by hand. */
    fun reset() {
        failures = 0
        gaveUp = false
    }

    companion object {
        fun of(def: ServiceDef) = RestartPolicy(def.restart, def.restartLimit, def.restartDelay * 1000L)
    }
}
