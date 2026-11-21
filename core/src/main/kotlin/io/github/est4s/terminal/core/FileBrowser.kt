package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import kotlin.math.roundToLong

/*
 * Touch mode's file manager (docs/TOUCH-DESIGN.md, "Files"): what it
 * lists and the jobs its changes run. Paths are Debian's (guest paths).
 * Listing reads through [WebApps] (read-only); every change is a [FileJob]
 * the app runs in Debian, never through java.io.File, as proot fakes hard
 * links with `.l2s.*` files that a change from the app's side would break.
 */

/**
 * One row of a folder: [path] is its Debian path under the folder as asked
 * (links along it not resolved). A link to a folder is a [folder] and a
 * [link]; a broken link is a file. [size] (0 for folders) and [modified]
 * (ms) are the link's target's.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val folder: Boolean,
    val link: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * The entries of the Debian folder [folder]: folders first, then files, each
 * by name ignoring case; names starting with `.` only with [showHidden], and
 * proot's `.l2s.*` files never (deleting one breaks a hard link). A folder
 * that's missing, not a folder or can't be read fails with a short reason.
 */
fun listFolder(apps: WebApps, folder: String, showHidden: Boolean): Result<List<FileEntry>> {
    if (!folder.startsWith("/")) return failure("$folder isn't a Debian path")
    val asked = cleanPath(folder)
    val resolved = apps.resolve(asked) ?: return failure("Can't follow the links to $asked")
    val dir = apps.host(resolved) ?: return failure("$asked doesn't exist")
    val names = runCatching {
        when {
            !dir.exists() -> return failure("$asked doesn't exist")
            !dir.isDirectory -> return failure("$asked isn't a folder")
            else -> dir.list()
        }
    }.getOrNull() ?: return failure("Can't read $asked")
    val entries = names
        .filter { !it.startsWith(L2S_PREFIX) && (showHidden || !it.startsWith(".")) }
        .map { name -> entry(apps, File(dir, name), childPath(resolved, name), childPath(asked, name)) }
    return Result.success(entries.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }, { it.name })))
}

private const val L2S_PREFIX = ".l2s."

private fun failure(message: String): Result<List<FileEntry>> = Result.failure(IllegalStateException(message))

private fun entry(apps: WebApps, own: File, resolvedPath: String, path: String): FileEntry {
    val link = Files.isSymbolicLink(own.toPath())
    val target = if (link) apps.resolve(resolvedPath)?.let(apps::host)?.takeIf { it.exists() } else own
    val folder = target?.isDirectory == true
    val modified = target?.lastModified()
        ?: runCatching { Files.getLastModifiedTime(own.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L)
    return FileEntry(own.name, path, folder, link, if (folder || target == null) 0 else target.length(), modified)
}

// [path] without trailing slashes ("/" stays).
private fun cleanPath(path: String): String = path.trimEnd('/').ifEmpty { "/" }

private fun childPath(folder: String, name: String) = if (folder == "/") "/$name" else "$folder/$name"

/**
 * The crumbs of [path], label to path: under [home] (or at it) they start
 * with `~` for home, else with `/` for the root. `/root/src` →
 * (~, /root), (src, /root/src).
 */
fun folderCrumbs(path: String, home: String): List<Pair<String, String>> {
    val clean = cleanPath(path)
    val top = cleanPath(home)
    val (first, rest) = if (top != "/" && (clean == top || clean.startsWith("$top/"))) {
        ("~" to top) to clean.removePrefix(top)
    } else {
        ("/" to "/") to clean
    }
    var at = first.second
    return listOf(first) + rest.split('/').filter { it.isNotEmpty() }.map { name ->
        at = childPath(at, name)
        name to at
    }
}

/** The folder holding [path]; null at `/`. */
fun parentFolder(path: String): String? {
    val clean = cleanPath(path)
    if (clean == "/") return null
    return clean.substringBeforeLast('/').ifEmpty { "/" }
}

/** The places row, label to path. `/storage` is bound from Android ([HOST_BINDS]). */
val FILE_PLACES: List<Pair<String, String>> = listOf(
    "Home" to "/root",
    "Download" to "/storage/emulated/0/Download",
    "Phone" to "/storage/emulated/0",
    "Debian" to "/",
)

private val SIZE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

/** [bytes] in short (powers of 1024): "0 B", "1.5 KB" (one decimal under 10), "12 KB", "3.4 MB". */
fun fileSizeText(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = 0
    while (unit < SIZE_UNITS.lastIndex && (value >= 1024 || (unit > 0 && value.roundToLong() >= 1024))) {
        value /= 1024
        unit++
    }
    return when {
        unit == 0 -> "$bytes B"
        value < 9.95 -> String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS[unit])
        else -> "${value.roundToLong()} ${SIZE_UNITS[unit]}"
    }
}

/** Whether [name] is a picture the app can show: the chat's list ([chatImageFile]). */
fun isPictureName(name: String): Boolean = chatImageFile(name)

/** Why [name] can't be a file's name (empty, `.`, `..`, a `/` or NUL, over 255 bytes), or null when it can. */
fun fileNameProblem(name: String): String? = when {
    name.isBlank() -> "Type a name"
    name == "." || name == ".." -> "'.' and '..' aren't names"
    '/' in name -> "A name can't have a /"
    '\u0000' in name -> "A name can't have a NUL character"
    name.toByteArray(Charsets.UTF_8).size > 255 -> "Too long: 255 bytes at most"
    else -> null
}

/**
 * [name], or when [taken] has it, the first free "stem (N).ext" from 2 on.
 * The extension is what follows the last dot (`x.tar (2).gz`); a name
 * starting with its only dot (`.bashrc`) has none, nor has a folder
 * without a dot: `.bashrc (2)`, `src (2)`.
 */
fun pasteName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    return generateSequence(2) { it + 1 }.map { "$stem ($it)$ext" }.first { it !in taken }
}

/** Copy or cut. */
enum class ClipOp { COPY, MOVE }

/** What Copy or Cut picked, waiting for Paste. */
data class FileClip(val op: ClipOp, val paths: List<String>)

/** A change to run in Debian, or why it won't run. */
sealed interface FileJob {
    /** Run [argv] in Debian (`/bin/sh -c SCRIPT`, which stops at the first failure); [title] says what it does. */
    data class Run(val argv: List<String>, val title: String) : FileJob
    /** Nothing runs: [reason], for the screen. */
    data class Refused(val reason: String) : FileJob
}

private fun shJob(lines: List<String>, title: String): FileJob =
    FileJob.Run(listOf("/bin/sh", "-c", "set -e\n" + lines.joinToString("\n")), title)

private fun itemsTitle(verb: String, paths: List<String>) =
    if (paths.size == 1) "$verb ${paths[0].substringAfterLast('/')}" else "$verb ${paths.size} items"

private fun insideOrAt(path: String, folder: String) = folder == "/" || path == folder || path.startsWith("$folder/")

/**
 * Pastes [clip] into [dest], whose names are [taken]: `cp -a` or `mv` per
 * item, each to a [pasteName] free of [taken] and of the names chosen
 * before it. Moving an item already in [dest] skips it ("Already here"
 * when that's all of them); copying one there makes a numbered copy. A
 * folder can't go into itself or below it.
 */
fun pasteJob(clip: FileClip, dest: String, taken: Set<String>): FileJob {
    if (clip.paths.isEmpty()) return FileJob.Refused("Nothing to paste")
    val target = cleanPath(dest)
    val move = clip.op == ClipOp.MOVE
    val chosen = taken.toMutableSet()
    val sources = mutableListOf<String>()
    val lines = mutableListOf<String>()
    for (path in clip.paths) {
        val source = cleanPath(path)
        if (insideOrAt(target, source)) {
            return FileJob.Refused("Can't ${if (move) "move" else "copy"} a folder into itself")
        }
        if (move && parentFolder(source) == target) continue
        val name = pasteName(source.substringAfterLast('/'), chosen)
        chosen += name
        sources += source
        val command = if (move) "mv --" else "cp -a --"
        lines += "$command ${shellQuote(source)} ${shellQuote(childPath(target, name))}"
    }
    if (lines.isEmpty()) return FileJob.Refused("Already here")
    return shJob(lines, itemsTitle(if (move) "Moving" else "Copying", sources))
}

/** Deletes [paths] for good (`rm -rf`); never `/`. */
fun deleteJob(paths: List<String>): FileJob {
    if (paths.isEmpty()) return FileJob.Refused("Nothing to delete")
    val clean = paths.map(::cleanPath)
    if ("/" in clean) return FileJob.Refused("Can't delete /")
    return shJob(listOf("rm -rf -- " + clean.joinToString(" ", transform = ::shellQuote)), itemsTitle("Deleting", clean))
}

/** Renames [path] to [newName] in its folder, whose names are [taken]. */
fun renameJob(path: String, newName: String, taken: Set<String>): FileJob {
    val clean = cleanPath(path)
    val parent = parentFolder(clean) ?: return FileJob.Refused("Can't rename /")
    val old = clean.substringAfterLast('/')
    fileNameProblem(newName)?.let { return FileJob.Refused(it) }
    if (newName == old) return FileJob.Refused("Same name")
    if (newName in taken) return FileJob.Refused("$newName already exists")
    return shJob(listOf("mv -- ${shellQuote(clean)} ${shellQuote(childPath(parent, newName))}"), "Renaming $old")
}

/** Makes the folder [name] in [parent], whose names are [taken]. */
fun newFolderJob(parent: String, name: String, taken: Set<String>): FileJob {
    fileNameProblem(name)?.let { return FileJob.Refused(it) }
    if (name in taken) return FileJob.Refused("$name already exists")
    return shJob(listOf("mkdir -- ${shellQuote(childPath(cleanPath(parent), name))}"), "New folder $name")
}
