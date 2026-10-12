package io.github.est4s.terminal.core

/**
 * Text of the background service's notification: [count] terminals,
 * the background [services] that run, and [wakelock]: the app holds one.
 */
fun runningTerminalsText(count: Int, wakelock: Boolean = false, services: Int = 0): String {
    val terminals = if (services == 0) {
        when (count) {
            0 -> "No terminals running"
            1 -> "1 terminal running"
            else -> "$count terminals running"
        }
    } else {
        val tabs = when (count) {
            0 -> "No terminals"
            1 -> "1 terminal"
            else -> "$count terminals"
        }
        "$tabs · $services service" + if (services == 1) "" else "s"
    }
    return terminals + if (wakelock) " · wakelock held" else ""
}

/** The strip's mark next to + while [active] services run (or wait to restart); null: none. */
fun servicesMark(active: Int): String? = if (active > 0) "● $active" else null

/** The notification when [name] ended too often in a row and won't be restarted. */
fun serviceGaveUpText(name: String, exitCode: Int?): String =
    "$name stopped" + (exitCode?.let { " (code $it)" } ?: "") + ", not restarting"
