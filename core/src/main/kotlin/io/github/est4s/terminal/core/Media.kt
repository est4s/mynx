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

/** How far ⟲10, 10⟳ and a double tap seek. */
const val SEEK_STEP_MS = 10_000L

/** How long the video's controls stay up while it plays. */
const val CONTROLS_HIDE_MS = 3_000L

/**
 * The Debian paths previous and next go through for [debianPath], whose
 * file on the phone is named [hostName], from that file's folder's
 * [hostNames]. A file reached through a link (another name) has its
 * folder elsewhere, so it plays alone.
 */
fun mediaFolderQueue(hostNames: List<String>, hostName: String, debianPath: String): List<String> {
    val name = debianPath.substringAfterLast('/')
    if (name != hostName) return listOf(debianPath)
    val folder = debianPath.substringBeforeLast('/')
    return mediaQueue(hostNames, name).map { "$folder/$it" }
}

/** The track [step] (±1) from [current] in [queue]; null past either end or when [current] isn't in it. */
fun mediaStep(queue: List<String>, current: String, step: Int): String? {
    val i = queue.indexOf(current)
    if (i < 0) return null
    return queue.getOrNull(i + step)
}

/** Previous starts the track again, as players do, once it's past its first 3 s. */
fun previousRestarts(position: Long): Boolean = position > 3_000

/** A double tap at [x] on a video [width] wide: back on the left half, forward on the right. */
fun doubleTapSeek(x: Float, width: Float): Long = if (x < width / 2) -SEEK_STEP_MS else SEEK_STEP_MS

/** The speed button's text: "1×", "1.25×", "0.75×". */
fun speedLabel(speed: Float): String {
    val text = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "$text×"
}

/** The folder shown under the name: [debianPath]'s, home as `~`. */
fun mediaFolder(debianPath: String): String {
    val folder = debianPath.substringBeforeLast('/', "").ifEmpty { "/" }
    return when {
        folder == "/root" -> "~"
        folder.startsWith("/root/") -> "~" + folder.removePrefix("/root")
        else -> folder
    }
}
