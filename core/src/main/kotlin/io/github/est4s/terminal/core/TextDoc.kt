package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * The text viewer's model (docs/TOUCH-DESIGN.md, "Viewers"): a file's bytes
 * as text to show and edit, and back to bytes as the file had them (its line
 * endings, BOM and final newline). The text shown has `\n` line ends only
 * and no final newline; [saveText] puts them back. Saving runs in Debian
 * ([saveJob]), as Files' jobs do.
 */

/** Files bigger than this show their start, read-only. */
const val TEXT_LIMIT = 1024 * 1024

/** Where the app writes the text to save before Debian copies it into the file. */
const val SAVES_DIR = "/tmp/.mynx/saves"

enum class LineEnding(val chars: String) { LF("\n"), CRLF("\r\n") }

sealed interface TextLoad {
    /**
     * [text] as shown; [lineEnding] the file's (the more common one when
     * mixed); [finalNewline] whether it ended with one; [bom] whether it
     * started with a UTF-8 BOM; [readOnlyReason] why it can't be edited.
     */
    data class Text(
        val text: String,
        val lineEnding: LineEnding,
        val finalNewline: Boolean,
        val bom: Boolean,
        val truncated: Boolean,
        val readOnlyReason: String?,
    ) : TextLoad {
        val readOnly: Boolean get() = readOnlyReason != null
    }

    /** Not UTF-8 text (or has NUL bytes): another app should open it. */
    data class NotText(val reason: String) : TextLoad
}

private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private const val NOT_TEXT = "This file isn't text (UTF-8): open it with another app."
private const val TRUNCATED = "This file is over 1 MB: only its first 1 MB is shown, and it can't be edited here."

/** [bytes]: the file's start (at least [TEXT_LIMIT] bytes when it's bigger); [size]: the whole file's. */
fun loadText(bytes: ByteArray, size: Long): TextLoad {
    val truncated = size > TEXT_LIMIT || bytes.size > TEXT_LIMIT
    val bom = bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]
    val start = if (bom) 3 else 0
    val end = if (truncated) truncatedEnd(bytes, start) else bytes.size
    for (i in start until end) if (bytes[i] == 0.toByte()) return TextLoad.NotText(NOT_TEXT)
    val raw = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, end - start))
            .toString()
    } catch (_: CharacterCodingException) {
        return TextLoad.NotText(NOT_TEXT)
    }
    var crlf = 0
    var lf = 0
    for (i in raw.indices) {
        if (raw[i] != '\n') continue
        if (i > 0 && raw[i - 1] == '\r') crlf++ else lf++
    }
    val text = if (crlf > 0) raw.replace("\r\n", "\n") else raw
    val finalNewline = !truncated && text.endsWith('\n')
    return TextLoad.Text(
        text = if (finalNewline) text.dropLast(1) else text,
        lineEnding = if (crlf > lf) LineEnding.CRLF else LineEnding.LF,
        finalNewline = finalNewline,
        bom = bom,
        truncated = truncated,
        readOnlyReason = if (truncated) TRUNCATED else null,
    )
}

// Where the shown start of a big file ends: before its last newline in the
// first [TEXT_LIMIT] bytes, else after its last whole UTF-8 character.
private fun truncatedEnd(bytes: ByteArray, start: Int): Int {
    val end = minOf(TEXT_LIMIT, bytes.size)
    for (i in end - 1 downTo start) if (bytes[i] == '\n'.code.toByte()) return i
    var lead = end - 1
    while (lead > start && bytes[lead].toInt() and 0xC0 == 0x80) lead--
    if (lead < start) return start
    val b = bytes[lead].toInt() and 0xFF
    val length = when {
        b < 0x80 -> 1
        b >= 0xF0 -> 4
        b >= 0xE0 -> 3
        b >= 0xC0 -> 2
        else -> 1
    }
    return if (lead + length > end) lead else end
}

/** The bytes to save for [text] (as shown, or with pasted `\r\n`) in a file read as [load]. */
fun saveText(load: TextLoad.Text, text: String): ByteArray {
    val lines = text.replace("\r\n", "\n")
    val full = if (load.finalNewline) "$lines\n" else lines
    val ended = if (load.lineEnding == LineEnding.CRLF) full.replace("\n", "\r\n") else full
    val body = ended.toByteArray(Charsets.UTF_8)
    return if (load.bom) BOM + body else body
}

/**
 * A file open in the viewer: [text] as it is now, [savedText] as last read
 * or saved, [modified] the file's time (ms) then, to tell whether it changed
 * on disk since.
 */
data class TextDoc(val load: TextLoad.Text, val modified: Long, val text: String = load.text, val savedText: String = load.text) {
    val dirty: Boolean get() = text != savedText
    val readOnly: Boolean get() = load.readOnly

    fun edited(text: String): TextDoc = copy(text = text)

    /** Saved as [text]; [modified]: the file's time after the save. */
    fun saved(modified: Long): TextDoc = copy(savedText = text, modified = modified)

    /** Whether the file on disk, now at [modifiedNow], isn't what was read or saved. */
    fun changedOnDisk(modifiedNow: Long): Boolean = modifiedNow != modified
}

fun saveText(doc: TextDoc): ByteArray = saveText(doc.load, doc.text)

/**
 * Copies [temp] into [path] in Debian and removes it: `cat` changes the file
 * in place, so its links, owner and mode stay. The paths are arguments, not
 * part of the script.
 */
fun saveJob(temp: String, path: String): FileJob {
    if (path.isBlank() || temp.isBlank()) return FileJob.Refused("Nowhere to save")
    return FileJob.Run(
        listOf("/bin/sh", "-c", "cat \"\$1\" > \"\$2\" && rm -f \"\$1\"", "mynx-save", temp, path),
        "Saving ${path.trimEnd('/').substringAfterLast('/')}",
    )
}

private val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")

/** A temp file in [SAVES_DIR] for saving [path], made unique by [now] (ms). */
fun saveTempPath(path: String, now: Long): String {
    val name = path.trimEnd('/').substringAfterLast('/').replace(UNSAFE_NAME, "_").ifEmpty { "file" }
    return "$SAVES_DIR/$now-$name"
}

/** Where [query] is in [text], case ignored, left to right without overlaps. */
fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val matches = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) return matches
        matches += at until at + query.length
        from = at + query.length
    }
}

/** Where each line of [text] starts (the first is 0), for line numbers. */
fun lineStarts(text: String): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val starts = IntArray(count)
    var n = 1
    for (i in text.indices) if (text[i] == '\n') starts[n++] = i + 1
    return starts
}

/**
 * A replace in Find: the [text] after it, the [edit] that made it (for
 * the editor and Undo), how many matches it replaced, the [cursor] after
 * the last replacement, and the match to go to next in the new text's
 * [findMatches] (-1: none).
 */
data class Replaced(val text: String, val edit: TextEdit, val count: Int, val cursor: Int, val next: Int)

/** The index of the first of [matches] starting at or after [offset], else the first (-1 with none). */
fun matchAtOrAfter(matches: List<IntRange>, offset: Int): Int {
    if (matches.isEmpty()) return -1
    val at = matches.indexOfFirst { it.first >= offset }
    return if (at < 0) 0 else at
}

/** Replaces [matches]' [current] one in [text] with [replacement]; the next match is the first after it. */
fun replaceMatch(text: String, matches: List<IntRange>, current: Int, replacement: String): Replaced {
    val match = matches[current]
    val edit = TextEdit(match.first, text.substring(match.first, match.last + 1), replacement)
    val after = edit.applyTo(text)
    val query = edit.removed
    return Replaced(after, edit, 1, edit.end, matchAtOrAfter(findMatches(after, query), edit.end))
}

/** Every match of [query] in [text] (case ignored) replaced with [replacement]; null when there's none. */
fun replaceAll(text: String, query: String, replacement: String): Replaced? {
    val matches = findMatches(text, query)
    if (matches.isEmpty()) return null
    val out = StringBuilder(text.length)
    var from = 0
    for (match in matches) {
        out.append(text, from, match.first).append(replacement)
        from = match.last + 1
    }
    out.append(text, from, text.length)
    val after = out.toString()
    val first = matches.first().first
    val tail = text.length - (matches.last().last + 1)
    val edit = TextEdit(first, text.substring(first, text.length - tail), after.substring(first, after.length - tail))
    return Replaced(after, edit, matches.size, edit.end, -1)
}
