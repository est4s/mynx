package io.github.est4s.terminal.core

/** Text of the background service's notification. */
fun runningTerminalsText(count: Int): String = when (count) {
    0 -> "No terminals running"
    1 -> "1 terminal running"
    else -> "$count terminals running"
}
