package io.github.est4s.terminal.core

/** Text of the background service's notification; [wakelock]: the service holds one. */
fun runningTerminalsText(count: Int, wakelock: Boolean = false): String = when (count) {
    0 -> "No terminals running"
    1 -> "1 terminal running"
    else -> "$count terminals running"
} + if (wakelock) " · wakelock held" else ""
