package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffViewTest {
    private fun numbered(n: Int) = (1..n).joinToString("") { "line $it\n" }

    @Test
    fun `a hunk's header says where it is, a count of one left out as diff does`() {
        assertEquals("@@ -2,7 +2,8 @@", hunkHeader(DiffHunk(2, 7, 2, 8, emptyList())))
        assertEquals("@@ -0,0 +1 @@", hunkHeader(DiffHunk(0, 0, 1, 1, emptyList())))
        assertEquals("@@ -3 +2,0 @@", hunkHeader(DiffHunk(3, 1, 2, 0, emptyList())))
    }

    @Test
    fun `a unified diff of a change`() {
        val diff = diffLines("a\nb\nc\n", "a\nB\nc\n", context = 1)
        assertEquals(
            "--- /root/x.txt\n+++ /root/x.txt\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n",
            unifiedDiff("/root/x.txt", diff),
        )
    }

    @Test
    fun `a new file's diff is from dev null, and says when the last line has no newline`() {
        val diff = diffLines("", "one\ntwo").copy(isNew = true)
        assertEquals(
            "--- /dev/null\n+++ /root/new.py\n@@ -0,0 +1,2 @@\n+one\n+two\n\\ No newline at end of file\n",
            unifiedDiff("/root/new.py", diff),
        )
    }

    @Test
    fun `rows are each hunk's header and lines, with the lines skipped between hunks folded`() {
        val before = numbered(30)
        val diff = diffLines(before, before.replace("line 2\n", "two\n").replace("line 25\n", "twenty-five\n"))
        val rows = diffRows(diff)
        assertEquals(DiffRow.Header("@@ -1,5 +1,5 @@"), rows[0])
        val fold = rows.indexOfFirst { it is DiffRow.Fold }
        // Hunk one is lines 1-5, hunk two 22-28: 16 lines between.
        assertEquals(DiffRow.Fold(16), rows[fold])
        assertEquals(DiffRow.Header("@@ -22,7 +22,7 @@"), rows[fold + 1])
        assertEquals(1 + 6 + 1 + 1 + 8, rows.size)
        assertEquals(1, rows.count { it is DiffRow.Fold })
        val first = rows[1] as DiffRow.Line
        assertEquals(DiffLine(DiffLineKind.SAME, "line 1", 1, 1), first.line)
    }

    @Test
    fun `a hunk of only additions folds from its line before`() {
        val before = numbered(20)
        val after = before.replace("line 1\n", "line 1\nnew\n").replace("line 20\n", "line 20\nend\n")
        val rows = diffRows(diffLines(before, after, context = 0))
        assertEquals(
            listOf(
                DiffRow.Header("@@ -1,0 +2 @@"),
                DiffRow.Line(DiffLine(DiffLineKind.ADDED, "new", null, 2)),
                DiffRow.Fold(19),
                DiffRow.Header("@@ -20,0 +22 @@"),
                DiffRow.Line(DiffLine(DiffLineKind.ADDED, "end", null, 22)),
            ),
            rows,
        )
    }

    @Test
    fun `a line with no newline after it is followed by a note`() {
        val rows = diffRows(diffLines("a\n", "a\nb"))
        assertEquals(DiffRow.NoNewline, rows.last())
        assertEquals("b", (rows[rows.size - 2] as DiffRow.Line).line.text)
    }

    @Test
    fun `lines are highlighted with their side's text, so a block comment carries over`() {
        val before = "x = 1\n/* start\nend */\ny = 2\n"
        val after = "x = 2\n/* start\nend */\ny = 2\n"
        val rows = diffRows(diffLines(before, after), "c").filterIsInstance<DiffRow.Line>()
        val end = rows.first { it.line.text == "end */" }
        assertEquals(listOf(SyntaxSpan(0, 6, SyntaxKind.COMMENT)), end.spans)
        val added = rows.first { it.line.kind == DiffLineKind.ADDED }
        assertTrue(added.spans.any { it.kind == SyntaxKind.NUMBER && it.start == 4 && it.end == 5 })
        val removed = rows.first { it.line.kind == DiffLineKind.REMOVED }
        assertTrue(removed.spans.any { it.kind == SyntaxKind.NUMBER && it.start == 4 })
    }

    @Test
    fun `tints are the theme's green and red in its background, the text readable on them`() {
        for (name in listOf("neon", "solarized-light", "phosphor", "amber", "nord")) {
            val text = ColorScheme::class.java.getResource("themes/$name.colors.properties")!!.readText()
            val scheme = parseColorScheme(text, NEON).scheme
            val tints = diffTints(scheme)
            // A theme whose own text is under 4.5 (Solarized light's) loses at most a tenth of it.
            val least = minOf(4.5, 0.9 * contrast(scheme.foreground, scheme.background))
            for (tint in listOf(tints.added, tints.removed)) {
                assertTrue(contrast(scheme.foreground, tint) >= least, "$name: ${Integer.toHexString(tint)}")
                assertEquals(0xff, tint ushr 24, name)
            }
            assertTrue(tints.added != scheme.background || tints.removed != scheme.background, name)
        }
        val neon = diffTints(NEON)
        // Greener and redder than the background.
        assertTrue((neon.added shr 8 and 0xff) > (NEON.background shr 8 and 0xff))
        assertTrue((neon.removed shr 16 and 0xff) > (NEON.background shr 16 and 0xff))
    }

    @Test
    fun `a diff's temp file is named after the file`() {
        assertEquals("$DIFFS_DIR/100-main.kt.diff", diffTempPath("/root/src/main.kt", 100))
        assertEquals("$DIFFS_DIR/7-a_b.diff", diffTempPath("/root/a b", 7))
    }
}
