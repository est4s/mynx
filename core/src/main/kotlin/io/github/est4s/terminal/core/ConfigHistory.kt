package io.github.est4s.terminal.core

import java.io.File

/** A change `mynx undo` can take back: what it was and when (epoch ms). */
data class UndoStep(val reason: String, val time: Long)

private const val MAX_TRACKED_SIZE = 256 * 1024L
// User fonts can be large, and aren't something to undo.
private val UNTRACKED_DIRS = setOf("fonts")

/**
 * Undo for the config folder [config]. [observe] compares the folder with
 * the copy it saw last (in [state]); if it changed, that copy becomes an
 * undo step named after the change, so every change is recorded however
 * it was made: by a request, an editor or by hand (seen at the next
 * `mynx check`, app start or undo). [keep] is how many steps to keep.
 */
class ConfigHistory(private val config: File, private val state: File, private val now: () -> Long = System::currentTimeMillis) {
    private val last = File(state, "last")
    private val stepsDir = File(state, "steps")

    fun observe(reason: String, keep: Int) {
        val current = read(config)
        if (!last.isDirectory) {
            replace(last, current)
            return
        }
        val seen = read(last)
        if (!same(seen, current) && keep > 0) {
            val seq = (stepDirs().lastOrNull()?.name?.toLong() ?: 0) + 1
            val step = File(stepsDir, "%08d".format(seq))
            replace(File(step, "files"), seen)
            File(step, "about").writeText("$reason\n${now()}\n")
        }
        replace(last, current)
        stepDirs().dropLast(keep.coerceAtLeast(0)).forEach { it.deleteRecursively() }
    }

    /** Newest first. */
    fun steps(): List<UndoStep> = stepDirs().reversed().mapNotNull { dir ->
        val about = runCatching { File(dir, "about").readLines() }.getOrNull() ?: return@mapNotNull null
        UndoStep(about.getOrElse(0) { "" }, about.getOrNull(1)?.toLongOrNull() ?: 0)
    }

    /** Takes back the newest change (edits not seen yet count as one); returns its name, or null. */
    fun undo(keep: Int): String? {
        observe("edits by hand", keep)
        val dir = stepDirs().lastOrNull() ?: return null
        val reason = steps().first().reason
        val files = read(File(dir, "files"))
        for (path in read(config).keys - files.keys) File(config, path).delete()
        for ((path, bytes) in files) File(config, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        replace(last, files)
        dir.deleteRecursively()
        return reason
    }

    private fun stepDirs(): List<File> =
        stepsDir.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty().sortedBy { it.name }

    private fun read(root: File): Map<String, ByteArray> {
        if (!root.isDirectory) return emptyMap()
        return root.walkTopDown()
            .onEnter { it == root || it.relativeTo(root).invariantSeparatorsPath !in UNTRACKED_DIRS }
            .filter { it.isFile && !it.name.endsWith(".tmp") && it.length() <= MAX_TRACKED_SIZE }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    }

    private fun replace(dir: File, files: Map<String, ByteArray>) {
        dir.deleteRecursively()
        dir.mkdirs()
        for ((path, bytes) in files) File(dir, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    private fun same(a: Map<String, ByteArray>, b: Map<String, ByteArray>) =
        a.keys == b.keys && a.all { (path, bytes) -> bytes.contentEquals(b.getValue(path)) }
}
