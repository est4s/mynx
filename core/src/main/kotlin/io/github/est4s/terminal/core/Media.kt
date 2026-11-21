package io.github.est4s.terminal.core

import java.util.Locale

// The media player's arithmetic (docs/TOUCH-DESIGN.md, "The media player").

/** [ms] as the player shows it: "0:07", "1:02", "1:02:03". */
fun mediaTime(ms: Long): String {
    val seconds = maxOf(ms, 0) / 1000
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/**
 * What previous and next go through: the audio files among [names] (a
 * folder's), by name ignoring case, with [current] (the one playing)
 * among them even when its name doesn't say it's audio.
 */
fun mediaQueue(names: List<String>, current: String): List<String> =
    (names.filter { fileKindOf(it) == FileKind.AUDIO } + current).distinct()
        .sortedWith(compareBy({ it.lowercase() }, { it }))

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/** The speed after [current]: 1×, 1.25×, 1.5×, 2×, 0.75×, then 1× again (also from any other). */
fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOf(current)
    return if (i < 0) SPEEDS[0] else SPEEDS[(i + 1) % SPEEDS.size]
}

/** [position] moved by [delta] (ms), kept within 0 and [duration]; a [duration] of 0 or less is unknown. */
fun seekBy(position: Long, delta: Long, duration: Long): Long {
    val to = maxOf(position + delta, 0)
    return if (duration > 0) minOf(to, duration) else to
}
