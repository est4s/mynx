package io.github.est4s.terminal.core

/*
 * What chat's diff view (DiffViewActivity, docs/TOUCH-DESIGN.md "A
 * turn's changes") draws from a [FileDiff]: its rows, the tints of added
 * and removed lines, and the unified diff it shares.
 */

/** Where the diff view writes a diff to share. */
const val DIFFS_DIR = "/tmp/.mynx/diffs"

private val UNSAFE_DIFF_NAME = Regex("[^A-Za-z0-9._-]")

/** A temp file in [DIFFS_DIR] for the diff of [path], made at [now]. */
fun diffTempPath(path: String, now: Long): String {
    val name = path.trimEnd('/').substringAfterLast('/').replace(UNSAFE_DIFF_NAME, "_").ifEmpty { "file" }
    return "$DIFFS_DIR/$now-$name.diff"
}

/** `@@ -oldStart,oldLines +newStart,newLines @@`, a count of 1 left out as diff does. */
fun hunkHeader(hunk: DiffHunk): String {
    fun range(start: Int, lines: Int) = if (lines == 1) "$start" else "$start,$lines"
    return "@@ -${range(hunk.oldStart, hunk.oldLines)} +${range(hunk.newStart, hunk.newLines)} @@"
}

/** [diff] of the Debian file [path] as a unified diff (`diff -u`), for patch and git apply. */
fun unifiedDiff(path: String, diff: FileDiff): String = buildString {
    append("--- ").append(if (diff.isNew) "/dev/null" else path).append('\n')
    append("+++ ").append(path).append('\n')
    for (hunk in diff.hunks) {
        append(hunkHeader(hunk)).append('\n')
        for (line in hunk.lines) {
            append(
                when (line.kind) {
                    DiffLineKind.SAME -> ' '
                    DiffLineKind.ADDED -> '+'
                    DiffLineKind.REMOVED -> '-'
                },
            )
            append(line.text).append('\n')
            if (line.noNewline) append("\\ No newline at end of file\n")
        }
    }
}

/** A row of the diff view. */
sealed interface DiffRow {
    /** A hunk's `@@ … @@` line. */
    data class Header(val text: String) : DiffRow

    /** A line, and its [spans] for its language (offsets in the line's text). */
    data class Line(val line: DiffLine, val spans: List<SyntaxSpan> = emptyList()) : DiffRow

    /** [lines] unchanged lines between two hunks, not shown. */
    data class Fold(val lines: Int) : DiffRow

    /** "No newline at end of file", about the line before. */
    data object NoNewline : DiffRow
}

/**
 * [diff]'s rows: each hunk's header and lines, the unchanged lines between
 * hunks folded, and the lines highlighted for [language] (as
 * [syntaxLanguage] knows it; null: none). Each side is highlighted as one
 * text per hunk, so a block comment or string over lines reads right.
 */
fun diffRows(diff: FileDiff, language: String? = null): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    var previous: DiffHunk? = null
    for (hunk in diff.hunks) {
        previous?.let { before ->
            // The first old line after a hunk, and a hunk's first: with no old lines, its start is the line before.
            val after = before.oldStart + maxOf(before.oldLines, 1)
            val first = if (hunk.oldLines == 0) hunk.oldStart + 1 else hunk.oldStart
            if (first > after) rows += DiffRow.Fold(first - after)
        }
        rows += DiffRow.Header(hunkHeader(hunk))
        val spans = language?.let { hunkSpans(hunk, it) }
        hunk.lines.forEachIndexed { i, line ->
            rows += DiffRow.Line(line, spans?.get(i).orEmpty())
            if (line.noNewline) rows += DiffRow.NoNewline
        }
        previous = hunk
    }
    return rows
}

// Each line's spans: the old side (unchanged and removed lines) and the new (unchanged and added) highlighted as texts of their own.
private fun hunkSpans(hunk: DiffHunk, language: String): List<List<SyntaxSpan>> {
    val spans = MutableList(hunk.lines.size) { emptyList<SyntaxSpan>() }
    fun side(take: (DiffLine) -> Boolean) {
        val indices = hunk.lines.indices.filter { take(hunk.lines[it]) }
        if (indices.isEmpty()) return
        val starts = IntArray(indices.size)
        val text = StringBuilder()
        indices.forEachIndexed { k, i ->
            if (k > 0) text.append('\n')
            starts[k] = text.length
            text.append(hunk.lines[i].text)
        }
        val found = highlight(text.toString(), language)
        var at = 0
        indices.forEachIndexed { k, i ->
            val start = starts[k]
            val end = start + hunk.lines[i].text.length
            val mine = ArrayList<SyntaxSpan>()
            while (at < found.size && found[at].end <= start) at++
            var j = at
            while (j < found.size && found[j].start < end) {
                val span = found[j]
                val from = maxOf(span.start, start)
                val to = minOf(span.end, end)
                if (from < to) mine += SyntaxSpan(from - start, to - start, span.kind)
                j++
            }
            spans[i] = mine
        }
    }
    side { it.kind != DiffLineKind.ADDED }
    // Unchanged lines keep the new side's spans.
    side { it.kind != DiffLineKind.REMOVED }
    return spans
}

/** The backgrounds of added and removed lines (`0xAARRGGBB`). */
data class DiffTints(val added: Int, val removed: Int)

/**
 * The theme's green and red (palette 2 and 1) mixed into its background,
 * as much as keeps its text readable on them: contrast at least 4.5, or
 * for a theme under that itself (Solarized light), at most a tenth less
 * than its own; the background itself if even a little is too much.
 */
fun diffTints(scheme: ColorScheme): DiffTints {
    val least = minOf(4.5, 0.9 * contrast(scheme.foreground, scheme.background))
    fun tint(color: Int): Int {
        for (amount in listOf(0.24, 0.2, 0.16, 0.12, 0.08, 0.05)) {
            val mixed = blend(scheme.background, color, amount)
            if (contrast(scheme.foreground, mixed) >= least) return mixed
        }
        return scheme.background or (0xff shl 24)
    }
    return DiffTints(tint(scheme.palette[2] ?: 0xff2ecc71.toInt()), tint(scheme.palette[1] ?: 0xffe74c3c.toInt()))
}

// [from] moved [amount] of the way to [to], opaque.
private fun blend(from: Int, to: Int, amount: Double): Int {
    fun channel(shift: Int): Int {
        val a = from shr shift and 0xff
        val b = to shr shift and 0xff
        return Math.round(a + (b - a) * amount).toInt() shl shift
    }
    return (0xff shl 24) or channel(16) or channel(8) or channel(0)
}
