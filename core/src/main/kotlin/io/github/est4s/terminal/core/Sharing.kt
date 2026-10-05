package io.github.est4s.terminal.core

import java.io.File
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What `pocket share` sends to other apps: [files] (host files), or [text]. */
data class Share(val files: List<File>, val text: String?)

const val MAX_SHARE_FILES = 100
/** The longest text `pocket share --text` sends (it goes through Binder, like the clipboard). */
const val MAX_SHARE_TEXT = 100_000
const val MAX_SHARED_NAME = 120

/**
 * The files of recent shares, for the app's provider to serve: a share
 * is found by a random token, so other apps only reach the files they
 * were handed. Only the last [keep] shares are kept.
 */
class SharedFiles(private val keep: Int = 20) {
    private val shares = LinkedHashMap<String, List<File>>()
    private val random = SecureRandom()

    @Synchronized
    fun add(files: List<File>): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        shares[token] = files
        while (shares.size > keep) shares.remove(shares.keys.first())
        return token
    }

    @Synchronized
    fun find(token: String, index: Int): File? = shares[token]?.getOrNull(index)
}

/** One MIME type for files of [types]: theirs if they agree, else one for their kind (all images, say), else any. */
fun shareType(types: List<String?>): String {
    val known = types.filterNotNull()
    if (known.isEmpty() || known.size < types.size) return "*/*"
    if (known.distinct().size == 1) return known[0]
    val top = known.map { it.substringBefore('/') }.distinct()
    return if (top.size == 1) "${top[0]}/*" else "*/*"
}

/**
 * Which files of an incoming share to save: its [streams] (EXTRA_STREAM),
 * else the [clip]'s, unless it has [text]. Then the clip only holds a
 * preview for the share sheet (a browser puts the site's icon there) and
 * the text is what was shared. Empty means save the text.
 */
fun <T> filesToSave(streams: List<T>, clip: List<T>, text: String?): List<T> = when {
    streams.isNotEmpty() -> streams
    !text.isNullOrEmpty() -> emptyList()
    else -> clip
}

/** Where files and text shared to the app from other apps are saved (the share-folder setting). */
fun inbox(settings: Settings, home: File, now: () -> LocalDateTime = LocalDateTime::now): Inbox {
    val path = settings.shareFolder
    val folder = if (path.startsWith("~/")) File(home, path.removePrefix("~/")) else File(hostPath(path, home.parentFile.path)!!)
    return Inbox(folder, path, now)
}

/**
 * Saves what other apps share into [folder] ([label] is how Debian
 * spells it). Never overwrites: a taken name gets " (2)" and so on.
 */
class Inbox(val folder: File, val label: String, private val now: () -> LocalDateTime) {
    /**
     * A new, empty file for a shared file called [name] (from the other
     * app, so made safe), or named by the time with [extension].
     */
    fun newFile(name: String?, extension: String?): File =
        reserve(safeName(name) ?: (stamp() + (extension?.let { ".$it" } ?: "")))

    /** Saves shared [text] in a `.txt` file named after its [subject] (a page's title, say). */
    fun saveText(text: String, subject: String?): File {
        val name = safeName(subject?.replace('/', '-'))?.let { cut("$it.txt") } ?: "${stamp()}.txt"
        return reserve(name).apply { writeText(if (text.endsWith("\n")) text else "$text\n") }
    }

    /** The notification's text after saving [saved], when [failed] couldn't be read. */
    fun notice(saved: List<File>, failed: Int): String {
        val what = if (saved.size == 1) saved[0].name else "${saved.size} files"
        return when {
            saved.isEmpty() && failed == 0 -> "Nothing to save"
            saved.isEmpty() -> "Nothing saved: ${if (failed == 1) "1 file" else "$failed files"} couldn't be read"
            failed == 0 -> "Saved $what in $label"
            else -> "Saved $what in $label; $failed couldn't be read"
        }
    }

    private fun stamp() = "shared-" + now().format(STAMP)

    private fun reserve(name: String): File {
        folder.mkdirs()
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while (true) {
            val file = File(folder, if (n == 1) name else "${name.take(dot)} ($n)${name.drop(dot)}")
            if (file.createNewFile()) return file
            n++
        }
    }
}

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

private fun safeName(wanted: String?): String? {
    val name = wanted.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .filter { it >= ' ' && it != '\u007f' }.trim()
    return if (name.isEmpty() || name == "." || name == "..") null else cut(name)
}

// Cut to MAX_SHARED_NAME, keeping a short extension.
private fun cut(name: String): String {
    if (name.length <= MAX_SHARED_NAME) return name
    val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..10 }?.let { ".$it" } ?: ""
    return name.take(MAX_SHARED_NAME - ext.length) + ext
}
