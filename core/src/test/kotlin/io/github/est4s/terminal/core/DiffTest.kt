package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffTest {
    private fun same(text: String, old: Int, new: Int) = DiffLine(DiffLineKind.SAME, text, old, new)
    private fun added(text: String, new: Int, noNewline: Boolean = false) = DiffLine(DiffLineKind.ADDED, text, null, new, noNewline)
    private fun removed(text: String, old: Int, noNewline: Boolean = false) = DiffLine(DiffLineKind.REMOVED, text, old, null, noNewline)

    private fun numbered(n: Int) = (1..n).joinToString("") { "line $it\n" }

    @Test
    fun `no change is no hunks`() {
        assertEquals(FileDiff(emptyList(), 0, 0, isNew = false), diffLines("a\nb\n", "a\nb\n"))
        assertEquals(FileDiff(emptyList(), 0, 0, isNew = false), diffLines("", ""))
    }

    @Test
    fun `a changed line in the middle, with three lines of context`() {
        val before = numbered(10)
        val after = before.replace("line 5\n", "five\n")
        val diff = diffLines(before, after)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(
            listOf(
                DiffHunk(
                    2, 7, 2, 7,
                    listOf(
                        same("line 2", 2, 2), same("line 3", 3, 3), same("line 4", 4, 4),
                        removed("line 5", 5), added("five", 5),
                        same("line 6", 6, 6), same("line 7", 7, 7), same("line 8", 8, 8),
                    ),
                ),
            ),
            diff.hunks,
        )
    }

    @Test
    fun `changes far apart are separate hunks, near ones share one`() {
        val before = numbered(30)
        val far = diffLines(before, before.replace("line 2\n", "two\n").replace("line 25\n", "twenty-five\n"))
        assertEquals(listOf(1 to 5, 22 to 7), far.hunks.map { it.oldStart to it.oldLines })
        val near = diffLines(before, before.replace("line 10\n", "ten\n").replace("line 16\n", "sixteen\n"))
        assertEquals(listOf(7 to 13), near.hunks.map { it.oldStart to it.oldLines })
    }

    @Test
    fun `added and removed lines shift the numbers`() {
        val diff = diffLines("a\nb\nc\n", "a\nnew\nb\n", context = 1)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(
            listOf(
                DiffHunk(1, 3, 1, 3, listOf(same("a", 1, 1), added("new", 2), same("b", 2, 3), removed("c", 3))),
            ),
            diff.hunks,
        )
    }

    @Test
    fun `an empty before is all added, an empty after all removed`() {
        val created = diffLines("", "x\ny\n")
        assertEquals(listOf(DiffHunk(0, 0, 1, 2, listOf(added("x", 1), added("y", 2)))), created.hunks)
        assertEquals(2 to 0, created.added to created.removed)
        val emptied = diffLines("x\ny\n", "")
        assertEquals(listOf(DiffHunk(1, 2, 0, 0, listOf(removed("x", 1), removed("y", 2)))), emptied.hunks)
    }

    @Test
    fun `a pure insertion's old start is the line before it`() {
        val diff = diffLines(numbered(10), numbered(10).replace("line 5\n", "line 5\nnew\n"), context = 0)
        assertEquals(listOf(DiffHunk(5, 0, 6, 1, listOf(added("new", 6)))), diff.hunks)
    }

    @Test
    fun `a missing final newline is a change of the last line`() {
        val diff = diffLines("a\nb", "a\nb\n")
        assertEquals(
            listOf(DiffHunk(1, 2, 1, 2, listOf(same("a", 1, 1), removed("b", 2, noNewline = true), added("b", 2)))),
            diff.hunks,
        )
        assertEquals(
            listOf(same("a", 1, 1), removed("b", 2, noNewline = true), added("b", 2), added("c", 3, noNewline = true)),
            diffLines("a\nb", "a\nb\nc").hunks.single().lines,
        )
        val noFinal = diffLines("x\n", "x\ny")
        assertEquals(listOf(same("x", 1, 1), added("y", 2, noNewline = true)), noFinal.hunks.single().lines)
    }

    @Test
    fun `lines are split on newlines only, a CR stays in the text`() {
        assertEquals(listOf(removed("a\r", 1), added("a", 1)), diffLines("a\r\n", "a\n").hunks.single().lines)
    }

    @Test
    fun `the diff is a shortest one`() {
        val diff = diffLines("a\nb\nc\na\nb\nb\na\n", "c\nb\na\nb\na\nc\n")
        // Myers' example: ABCABBA → CBABAC, 5 edits.
        assertEquals(5, diff.added + diff.removed)
    }

    @Test
    fun `random diffs give back both sides and are shortest`() {
        val random = java.util.Random(7)
        repeat(500) {
            fun text() = (0 until random.nextInt(25)).joinToString("") { "abcde"[random.nextInt(5)] + "\n" } +
                (if (random.nextInt(4) == 0) "z" else "")
            val before = text()
            val after = text()
            val diff = diffLines(before, after, context = 1_000)
            val lines = diff.hunks.flatMap { it.lines }
            fun side(kind: DiffLineKind) = lines.filter { it.kind != kind }.joinToString("") { it.text + if (it.noNewline) "" else "\n" }
            if (before != after) {
                assertEquals(before, side(DiffLineKind.ADDED))
                assertEquals(after, side(DiffLineKind.REMOVED))
            }
            // Lines with their newlines, as the diff compares them.
            fun lines(text: String) = Regex("(?<=\n)").split(text).filter { it.isNotEmpty() }
            val a = lines(before)
            val b = lines(after)
            val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            val edits = a.size + b.size - 2 * lcs[0][0]
            assertEquals(edits, diff.added + diff.removed, "$before → $after")
        }
    }

    @Test
    fun `a big file with a few changes is quick`() {
        val before = numbered(20_000)
        val after = before.replace("line 100\n", "changed\n").replace("line 10000\n", "").replace("line 19999\n", "line 19999\nmore\n")
        val start = System.nanoTime()
        val diff = diffLines(before, after)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(2 to 2, diff.added to diff.removed)
        assertEquals(3, diff.hunks.size)
        assertTrue(ms < 2_000, "took $ms ms")
    }

    @Test
    fun `a big file rewritten completely is quick too`() {
        val before = numbered(20_000)
        val after = (1..20_000).joinToString("") { "other $it\n" }
        val start = System.nanoTime()
        val diff = diffLines(before, after)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(20_000 to 20_000, diff.added to diff.removed)
        assertTrue(ms < 2_000, "took $ms ms")
    }

    @Test
    fun `many shared lines rewritten around them stay quick`() {
        val before = (1..5_000).joinToString("") { "a $it\n}\n\n" }
        val after = (1..5_000).joinToString("") { "b $it\n}\n\n" }
        val start = System.nanoTime()
        val diff = diffLines(before, after)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5_000 to 5_000, diff.added to diff.removed)
        assertTrue(ms < 3_000, "took $ms ms")
    }

    @Test
    fun `either side over the limit is too big to diff`() {
        val big = "x".repeat(DIFF_LIMIT) + "\n"
        assertEquals(FileDiff(emptyList(), 0, 0, isNew = false, tooBig = true), diffLines(big, "x\n"))
        assertEquals(true, diffLines("", big).tooBig)
        assertEquals(false, diffLines("x".repeat(DIFF_LIMIT), "").tooBig)
    }

    // structuredPatch

    private fun hunk(oldStart: Int, oldLines: Int, newStart: Int, newLines: Int, vararg lines: String) =
        PatchHunk(oldStart, oldLines, newStart, newLines, lines.toList())

    @Test
    fun `a patch applies to its original`() {
        val original = numbered(10)
        val patch = listOf(
            hunk(2, 3, 2, 3, " line 2", "-line 3", "+three", " line 4"),
            hunk(7, 3, 7, 4, " line 7", " line 8", "+eight and a half", " line 9"),
        )
        assertEquals(
            original.replace("line 3\n", "three\n").replace("line 8\n", "line 8\neight and a half\n"),
            applyPatch(original, patch),
        )
    }

    @Test
    fun `no patch leaves the file as it was`() {
        assertEquals("a\n", applyPatch("a\n", emptyList()))
    }

    @Test
    fun `a patch that doesn't fit is null`() {
        assertNull(applyPatch("a\nb\n", listOf(hunk(1, 1, 1, 1, "-x", "+y"))))
        assertNull(applyPatch("a\n", listOf(hunk(5, 1, 5, 1, "-a", "+y"))))
        assertNull(applyPatch("a\nb\n", listOf(hunk(2, 1, 2, 1, "-b", "+c"), hunk(1, 1, 1, 1, "-a", "+z"))), "hunks out of order")
    }

    @Test
    fun `a patch with no newline at the end`() {
        assertEquals("two", applyPatch("one", listOf(hunk(1, 1, 1, 1, "-one", "\\ No newline at end of file", "+two", "\\ No newline at end of file"))))
        assertEquals("one\n", applyPatch("one", listOf(hunk(1, 1, 1, 1, "-one", "\\ No newline at end of file", "+one"))))
        assertEquals("a\nb", applyPatch("a\n", listOf(hunk(1, 1, 1, 2, " a", "+b", "\\ No newline at end of file"))))
    }

    @Test
    fun `a pure insertion patch, and one into an empty file`() {
        assertEquals("a\nnew\nb\n", applyPatch("a\nb\n", listOf(hunk(1, 0, 2, 1, "+new"))))
        assertEquals("x\n", applyPatch("", listOf(hunk(0, 0, 1, 1, "+x"))))
    }

    // A turn's edits

    private fun edit(original: String?, content: String? = null, patch: List<PatchHunk> = emptyList(), id: String = "t") =
        FileEdit(id, "/root/a.txt", original, content, patch)

    @Test
    fun `a turn's diff runs from the first edit's original to the last edit's result`() {
        val edits = listOf(
            edit("a\nb\nc\n", content = "a\nB\nc\n", id = "t1"),
            edit("a\nB\nc\n", patch = listOf(hunk(3, 1, 3, 2, " c", "+d")), id = "t2"),
        )
        assertEquals(diffLines("a\nb\nc\n", "a\nB\nc\nd\n"), turnDiff(edits))
    }

    @Test
    fun `a file new in the turn is all added and new`() {
        val diff = turnDiff(listOf(edit(null, content = "x\ny\n"), edit("x\ny\n", patch = listOf(hunk(2, 1, 2, 1, "-y", "+z")))))
        assertEquals(diffLines("", "x\nz\n").copy(isNew = true), diff)
        assertEquals(true, diff?.isNew)
    }

    @Test
    fun `a turn's diff that can't be worked out is null`() {
        assertNull(turnDiff(emptyList()))
        assertNull(turnDiff(listOf(edit("a\n", patch = listOf(hunk(1, 1, 1, 1, "-q", "+r"))))))
        assertNull(turnDiff(listOf(edit("a\n", content = "b\n"), edit(null, patch = listOf(hunk(1, 1, 1, 1, "-b", "+c"))))))
    }

    @Test
    fun `a summary of a diff`() {
        assertEquals("+12 −3", diffSummary(FileDiff(emptyList(), 12, 3, isNew = false)))
        assertEquals("new · 40 lines", diffSummary(FileDiff(emptyList(), 40, 0, isNew = true)))
        assertEquals("new · 1 line", diffSummary(FileDiff(emptyList(), 1, 0, isNew = true)))
        assertEquals("no changes", diffSummary(FileDiff(emptyList(), 0, 0, isNew = false)))
        assertEquals("too big", diffSummary(FileDiff(emptyList(), 0, 0, isNew = false, tooBig = true)))
    }
}
