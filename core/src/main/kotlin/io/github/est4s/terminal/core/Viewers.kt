package io.github.est4s.terminal.core

import java.io.File

/*
 * Which of mynx's viewers opens a file (docs/TOUCH-DESIGN.md, "Viewers"):
 * the app has one place that opens Debian's files, for Files, chat and
 * `mynx open`, and asks here.
 */

/** mynx's viewers: pictures; text, code and Markdown; audio and video. */
enum class Viewer { IMAGE, TEXT, MEDIA }

/** The viewer of mynx's that opens a file of [kind] with [with]; null: another app opens it. */
fun viewerFor(kind: FileKind?, with: OpenWith): Viewer? = when {
    kind == null || with == OpenWith.ANDROID -> null
    kind == FileKind.PICTURE -> Viewer.IMAGE
    kind == FileKind.AUDIO || kind == FileKind.VIDEO -> Viewer.MEDIA
    else -> Viewer.TEXT
}

/** The viewer for [kind] as people see it named ("mynx's code viewer"), as `mynx open` asks. */
fun viewerName(kind: FileKind): String = when (kind) {
    FileKind.PICTURE -> "mynx's picture viewer"
    FileKind.MARKDOWN -> "mynx's Markdown viewer"
    FileKind.CODE -> "mynx's code viewer"
    FileKind.TEXT -> "mynx's text viewer"
    FileKind.AUDIO -> "mynx's audio player"
    FileKind.VIDEO -> "mynx's video player"
}

/**
 * The kind of the Debian file [path]: by its name, else by its link's
 * [target]'s (the path with links followed), else text when [file] (its
 * host file) starts as text; null for none.
 */
fun fileKindOfFile(path: String, target: String?, file: File): FileKind? =
    fileKindOf(path) ?: target?.let(::fileKindOf)
        ?: FileKind.TEXT.takeIf { runCatching { looksLikeText(fileHead(file)) }.getOrDefault(false) }

// How much of a file tells whether it's text.
private const val TEXT_HEAD = 8192

private fun fileHead(file: File): ByteArray = file.inputStream().use { input ->
    val bytes = ByteArray(TEXT_HEAD)
    var n = 0
    while (n < bytes.size) {
        val read = input.read(bytes, n, bytes.size - n)
        if (read < 0) break
        n += read
    }
    bytes.copyOf(n)
}
