package io.github.est4s.terminal.core

/*
 * A turn's changes to a file, for chat's diff view: a line diff (Myers,
 * in linear space) shown as unified-diff hunks with context, and the
 * file's text before and after a turn worked out from Claude Code's
 * transcript ([FileEdit], read by [readFileEdits]): before is the first
 * edit's `originalFile`, after the last edit's result, so several edits
 * read as one change.
 *
 * Lines are split on `\n` only (a `\r` stays in the text). A last line
 * with no newline is [DiffLine.noNewline], and differs from the same text
 * with one, so adding or dropping the final newline shows as a change of
 * that line.
 */

enum class DiffLineKind { SAME, ADDED, REMOVED }

/**
 * A line of a hunk: [oldNumber] / [newNumber] are its 1-based numbers
 * before and after (null on the side it isn't on); [noNewline]: the
 * file's last line, with no newline after it.
 */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int?,
    val newNumber: Int?,
    val noNewline: Boolean = false,
)

/**
 * A unified-diff hunk, `@@ -oldStart,oldLines +newStart,newLines @@`: a
 * side with no lines starts at the line before (0 at the top), as in diff.
 */
data class DiffHunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<DiffLine>)

/**
 * A file's changes: its [hunks], how many lines were [added] and
 * [removed]; [isNew]: the file didn't exist before (then everything is
 * added). [tooBig]: a side was over [DIFF_LIMIT], so nothing was worked
 * out (no hunks, counts 0): the view says so and offers the file.
 */
data class FileDiff(
    val hunks: List<DiffHunk>,
    val added: Int,
    val removed: Int,
    val isNew: Boolean,
    val tooBig: Boolean = false,
)

/** The most characters a side may have to be diffed (about 1 MB). */
const val DIFF_LIMIT = 1024 * 1024

/** Lines of context around changes. */
const val DIFF_CONTEXT = 3

// The text's lines, each with its `\n` if it has one.
private fun splitLines(text: String): List<String> {
    val lines = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val end = text.indexOf('\n', start)
        if (end < 0) {
            lines += text.substring(start)
            break
        }
        lines += text.substring(start, end + 1)
        start = end + 1
    }
    return lines
}

/** The changes from [before] to [after], with [context] lines around each change. */
fun diffLines(before: String, after: String, context: Int = DIFF_CONTEXT): FileDiff {
    if (before.length > DIFF_LIMIT || after.length > DIFF_LIMIT) return FileDiff(emptyList(), 0, 0, isNew = false, tooBig = true)
    val old = splitLines(before)
    val new = splitLines(after)
    val (removed, added) = changedLines(old, new)
    val lines = ArrayList<DiffLine>(maxOf(old.size, new.size))
    var i = 0
    var j = 0
    fun line(kind: DiffLineKind, raw: String, oldNumber: Int?, newNumber: Int?) =
        DiffLine(kind, raw.removeSuffix("\n"), oldNumber, newNumber, noNewline = !raw.endsWith('\n'))
    while (i < old.size || j < new.size) {
        when {
            i < old.size && removed[i] -> lines += line(DiffLineKind.REMOVED, old[i], ++i, null)
            j < new.size && added[j] -> lines += line(DiffLineKind.ADDED, new[j], null, ++j)
            else -> lines += line(DiffLineKind.SAME, old[i], ++i, ++j)
        }
    }
    return FileDiff(hunks(lines, context), added.count { it }, removed.count { it }, isNew = false)
}

// The lines with changes, and [context] lines around them, as hunks; changes closer than twice that share one.
private fun hunks(lines: List<DiffLine>, context: Int): List<DiffHunk> {
    val changes = lines.indices.filter { lines[it].kind != DiffLineKind.SAME }
    if (changes.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = maxOf(0, changes[0] - context)
    var to = minOf(lines.lastIndex, changes[0] + context)
    for (at in changes.drop(1)) {
        if (at - context <= to + 1) {
            to = minOf(lines.lastIndex, at + context)
        } else {
            ranges += from..to
            from = at - context
            to = minOf(lines.lastIndex, at + context)
        }
    }
    ranges += from..to
    // Lines on each side before a line: its start when the hunk has none of that side's.
    var oldBefore = 0
    var newBefore = 0
    var counted = 0
    return ranges.map { range ->
        for (k in counted until range.first) {
            if (lines[k].oldNumber != null) oldBefore++
            if (lines[k].newNumber != null) newBefore++
        }
        val part = lines.subList(range.first, range.last + 1)
        val oldLines = part.count { it.oldNumber != null }
        val newLines = part.count { it.newNumber != null }
        val hunk = DiffHunk(
            if (oldLines == 0) oldBefore else oldBefore + 1, oldLines,
            if (newLines == 0) newBefore else newBefore + 1, newLines,
            part.toList(),
        )
        oldBefore += oldLines
        newBefore += newLines
        counted = range.last + 1
        hunk
    }
}

/*
 * Which lines of [old] were removed and which of [new] added, in a
 * shortest edit. Lines are numbered by text first, and those on only one
 * side are changes for sure: they're set aside before Myers' search
 * (they can't be in a common subsequence), so a file mostly rewritten
 * costs little.
 */
private fun changedLines(old: List<String>, new: List<String>): Pair<BooleanArray, BooleanArray> {
    val ids = HashMap<String, Int>()
    val a = IntArray(old.size) { ids.getOrPut(old[it]) { ids.size } }
    val inOld = BooleanArray(ids.size)
    a.forEach { inOld[it] = true }
    val b = IntArray(new.size) { ids.getOrPut(new[it]) { ids.size } }
    val inNew = BooleanArray(ids.size)
    b.forEach { inNew[it] = true }
    val removed = BooleanArray(a.size) { !inNew[a[it]] }
    val added = BooleanArray(b.size) { !(b[it] < inOld.size && inOld[b[it]]) }
    val aKept = a.indices.filter { !removed[it] }.toIntArray()
    val bKept = b.indices.filter { !added[it] }.toIntArray()
    val myers = Myers(IntArray(aKept.size) { a[aKept[it]] }, IntArray(bKept.size) { b[bKept[it]] })
    myers.compare(0, aKept.size, 0, bKept.size)
    myers.removed.forEachIndexed { k, gone -> if (gone) removed[aKept[k]] = true }
    myers.added.forEachIndexed { k, new -> if (new) added[bKept[k]] = true }
    return removed to added
}

// Myers' O(ND) diff in linear space: split at a middle snake, then each half.
private class Myers(val a: IntArray, val b: IntArray) {
    val removed = BooleanArray(a.size)
    val added = BooleanArray(b.size)

    fun compare(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
        var aLo = aStart
        var aHi = aEnd
        var bLo = bStart
        var bHi = bEnd
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) { aLo++; bLo++ }
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) { aHi--; bHi-- }
        if (aLo == aHi) {
            for (j in bLo until bHi) added[j] = true
            return
        }
        if (bLo == bHi) {
            for (i in aLo until aHi) removed[i] = true
            return
        }
        val split = middle(aLo, aHi, bLo, bHi)
        if (split == null || (split.first == aLo && split.second == bLo) || (split.first == aHi && split.second == bHi)) {
            for (i in aLo until aHi) removed[i] = true
            for (j in bLo until bHi) added[j] = true
            return
        }
        compare(aLo, split.first, bLo, split.second)
        compare(split.first, aHi, split.second, bHi)
    }

    // Where a shortest path through the box crosses its middle diagonal (as in diff-match-patch's bisect); null: nothing in common.
    private fun middle(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Pair<Int, Int>? {
        val n = aHi - aLo
        val m = bHi - bLo
        val maxD = (n + m + 1) / 2
        val offset = maxD
        val size = 2 * maxD + 2
        val v1 = IntArray(size) { -1 }
        val v2 = IntArray(size) { -1 }
        v1[offset + 1] = 0
        v2[offset + 1] = 0
        val delta = n - m
        val front = delta % 2 != 0
        var k1Start = 0
        var k1End = 0
        var k2Start = 0
        var k2End = 0
        for (d in 0 until maxD) {
            var k1 = -d + k1Start
            while (k1 <= d - k1End) {
                val k1Offset = offset + k1
                var x1 = if (k1 == -d || (k1 != d && v1[k1Offset - 1] < v1[k1Offset + 1])) v1[k1Offset + 1] else v1[k1Offset - 1] + 1
                var y1 = x1 - k1
                while (x1 < n && y1 < m && a[aLo + x1] == b[bLo + y1]) { x1++; y1++ }
                v1[k1Offset] = x1
                if (x1 > n) {
                    k1End += 2
                } else if (y1 > m) {
                    k1Start += 2
                } else if (front) {
                    val k2Offset = offset + delta - k1
                    if (k2Offset in 0 until size && v2[k2Offset] != -1 && x1 >= n - v2[k2Offset]) return (aLo + x1) to (bLo + y1)
                }
                k1 += 2
            }
            var k2 = -d + k2Start
            while (k2 <= d - k2End) {
                val k2Offset = offset + k2
                var x2 = if (k2 == -d || (k2 != d && v2[k2Offset - 1] < v2[k2Offset + 1])) v2[k2Offset + 1] else v2[k2Offset - 1] + 1
                var y2 = x2 - k2
                while (x2 < n && y2 < m && a[aHi - x2 - 1] == b[bHi - y2 - 1]) { x2++; y2++ }
                v2[k2Offset] = x2
                if (x2 > n) {
                    k2End += 2
                } else if (y2 > m) {
                    k2Start += 2
                } else if (!front) {
                    val k1Offset = offset + delta - k2
                    if (k1Offset in 0 until size && v1[k1Offset] != -1) {
                        val x1 = v1[k1Offset]
                        val y1 = offset + x1 - k1Offset
                        if (x1 >= n - x2) return (aLo + x1) to (bLo + y1)
                    }
                }
                k2 += 2
            }
        }
        return null
    }
}

/**
 * A hunk of Claude Code's `structuredPatch`: [lines] start with ` `
 * (context), `-`, `+`, or `\` (`\ No newline at end of file`, about the
 * line before).
 */
data class PatchHunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<String>)

/**
 * [original] with [hunks] applied, in order; null when they don't fit it
 * (a context or removed line that isn't there, hunks out of order). A
 * line's final newline is matched loosely: the patch says whether an
 * added line has one.
 */
fun applyPatch(original: String, hunks: List<PatchHunk>): String? {
    val old = splitLines(original)
    val out = StringBuilder(original.length + 64)
    var at = 0
    for (hunk in hunks) {
        // A hunk with no old lines inserts after its start line.
        val start = if (hunk.oldLines == 0) hunk.oldStart else hunk.oldStart - 1
        if (start < at || start > old.size) return null
        for (k in at until start) out.append(old[k])
        at = start
        val lines = hunk.lines
        for ((index, line) in lines.withIndex()) {
            if (line.isEmpty()) return null
            val text = line.substring(1)
            val noNewline = lines.getOrNull(index + 1)?.startsWith('\\') == true
            when (line[0]) {
                ' ', '-' -> {
                    if (at >= old.size || old[at].removeSuffix("\n") != text) return null
                    if (line[0] == ' ') out.append(old[at])
                    at++
                }
                '+' -> {
                    out.append(text)
                    if (!noNewline) out.append('\n')
                }
                '\\' -> Unit
                else -> return null
            }
        }
    }
    for (k in at until old.size) out.append(old[k])
    return out.toString()
}

/**
 * A Write or Edit of [path] by tool call [toolId], from its result in the
 * transcript: [originalFile] (null: the file was new), a Write's
 * [content], and the change as [patch].
 */
data class FileEdit(val toolId: String, val path: String, val originalFile: String?, val content: String?, val patch: List<PatchHunk>)

/**
 * The turn's diff of a file from its [edits] in order (all of one file):
 * from the first edit's original (empty, and [FileDiff.isNew], when it
 * had none) to the last edit's content, else its original with its patch
 * applied. Null when there are none, or that can't be worked out.
 */
fun turnDiff(edits: List<FileEdit>): FileDiff? {
    val first = edits.firstOrNull() ?: return null
    val last = edits.last()
    val after = last.content ?: last.originalFile?.let { applyPatch(it, last.patch) } ?: return null
    return diffLines(first.originalFile ?: "", after).copy(isNew = first.originalFile == null)
}

/** A changed file's row: `+12 −3`, `new · 40 lines`, `no changes` or `too big`. */
fun diffSummary(diff: FileDiff): String = when {
    diff.tooBig -> "too big"
    diff.isNew -> "new · ${diff.added} ${if (diff.added == 1) "line" else "lines"}"
    diff.added == 0 && diff.removed == 0 -> "no changes"
    else -> "+${diff.added} −${diff.removed}"
}
