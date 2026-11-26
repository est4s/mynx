package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"

private fun line(text: String, style: Long = TERM_NORMAL, wrapped: Boolean = false) =
    TermLine(text.map { TermCell(it.toString(), 1, style) }, wrapped)

private fun blank(columns: Int) = line(" ".repeat(columns))

private fun ByteArray.text() = String(this, Charsets.UTF_8)

class TermSnapshotTest {
    @Test
    fun `a plain screen is reset, then its lines one under the other, then the cursor`() {
        val screen = TermScreen(10, 3, listOf(line("ls   "), line("a  b"), blank(10)), cursorRow = 2, cursorCol = 0)
        assertEquals("${ESC}c${ESC}[0mls\r\n${ESC}[0ma  b\r\n${ESC}[0m${ESC}[3;1H${ESC}[0m", termSnapshot(screen).text())
    }

    @Test
    fun `scrollback lines come first, so they scroll off the top as on the host`() {
        val screen = TermScreen(4, 2, listOf(line("old"), line("new"), line("$ ")), cursorRow = 1, cursorCol = 2)
        val text = termSnapshot(screen).text()
        assertTrue(text.startsWith("${ESC}c${ESC}[0mold\r\n${ESC}[0mnew\r\n${ESC}[0m$"), text)
        assertTrue(text.endsWith("${ESC}[2;3H${ESC}[0m"), text)
    }

    @Test
    fun `a wrapped line is printed whole and runs on, so it wraps there too`() {
        val screen = TermScreen(4, 2, listOf(line("abcd", wrapped = true), line("ef  ")), cursorRow = 1, cursorCol = 2)
        assertTrue(termSnapshot(screen).text().startsWith("${ESC}c${ESC}[0mabcd${ESC}[0mef${ESC}[0m"))
    }

    @Test
    fun `styles become SGR, a change at a time`() {
        val red = termStyle(1, TERM_COLOR_BACKGROUND, TERM_BOLD)
        val cells = listOf(TermCell("a", 1, red), TermCell("b", 1, red), TermCell("c", 1, TERM_NORMAL))
        val screen = TermScreen(3, 1, listOf(TermLine(cells)))
        assertTrue(termSnapshot(screen).text().startsWith("${ESC}c${ESC}[0;1;31mab${ESC}[0mc"), termSnapshot(screen).text())
    }

    @Test
    fun `each colour kind has its SGR`() {
        assertEquals("${ESC}[0;97;44m", termSgr(termStyle(15, 4, 0)))
        assertEquals("${ESC}[0;38;5;208;48;5;17m", termSgr(termStyle(208, 17, 0)))
        assertEquals("${ESC}[0;38;2;255;128;0;48;2;1;2;3m", termSgr(termStyle(0xffff8000.toInt(), 0xff010203.toInt(), 0)))
        val all = TERM_BOLD or TERM_ITALIC or TERM_UNDERLINE or TERM_BLINK or TERM_INVERSE or TERM_INVISIBLE or TERM_STRIKETHROUGH or TERM_DIM
        assertEquals("${ESC}[0;1;2;3;4;5;7;8;9m", termSgr(termStyle(TERM_COLOR_FOREGROUND, TERM_COLOR_BACKGROUND, all)))
        assertEquals("${ESC}[0m", termSgr(TERM_NORMAL))
    }

    @Test
    fun `termStyle encodes as Termux's TextStyle does`() {
        // TextStyle.NORMAL: the default colours (256, 257) in their places, no effects.
        assertEquals((256L shl 40) or (257L shl 16), TERM_NORMAL)
        // A 24-bit colour sets its flag and keeps the RGB.
        assertEquals((1L shl 9) or (0x123456L shl 40) or (257L shl 16), termStyle(0xff123456.toInt(), TERM_COLOR_BACKGROUND, 0))
    }

    @Test
    fun `coloured blanks at the end of a line stay, plain ones go`() {
        val bar = termStyle(TERM_COLOR_FOREGROUND, 2, 0)
        val cells = listOf(TermCell("x", 1, TERM_NORMAL), TermCell(" ", 1, bar), TermCell(" ", 1, TERM_NORMAL))
        val text = termSnapshot(TermScreen(3, 1, listOf(TermLine(cells)))).text()
        assertTrue(text.startsWith("${ESC}c${ESC}[0mx${ESC}[0;42m ${ESC}[0m${ESC}[1;1H"), text)
    }

    @Test
    fun `wide characters and combining marks go as they are`() {
        val cells = listOf(TermCell("漢", 2, TERM_NORMAL), TermCell("é", 1, TERM_NORMAL))
        assertTrue(termSnapshot(TermScreen(4, 1, listOf(TermLine(cells)))).text().startsWith("${ESC}c${ESC}[0m漢é"))
    }

    @Test
    fun `a full-screen program's screen goes after the main one, on the alternate screen`() {
        val screen = TermScreen(4, 1, listOf(line("$ ")), alt = listOf(line("vim")), cursorRow = 0, cursorCol = 1)
        val text = termSnapshot(screen).text()
        assertTrue(text.startsWith("${ESC}c${ESC}[0m$${ESC}[0m${ESC}[?1049h${ESC}[H${ESC}[0mvim${ESC}[0m"), text)
    }

    @Test
    fun `modes, margins and the style in use come last, so the next bytes find them`() {
        val modes = TERM_DEFAULT_MODES or TERM_MODE_CURSOR_KEYS or TERM_MODE_MOUSE_BUTTONS or TERM_MODE_MOUSE_SGR or TERM_MODE_PASTE
        val screen = TermScreen(
            10, 5, List(5) { blank(10) }, cursorRow = 3, cursorCol = 4, modes = modes and TERM_MODE_CURSOR_SHOWN.inv(),
            top = 1, bottom = 4, style = termStyle(3, TERM_COLOR_BACKGROUND, 0),
        )
        val tail = termSnapshot(screen).text().substringAfterLast("\r\n")
        assertEquals(
            "${ESC}[0m${ESC}[2;4r${ESC}[?1h${ESC}[?25l${ESC}[?1002h${ESC}[?1006h${ESC}[?2004h${ESC}[4;5H${ESC}[0;33m",
            tail,
        )
    }

    @Test
    fun `in origin mode the cursor is placed within the margins`() {
        val screen = TermScreen(10, 5, List(5) { blank(10) }, cursorRow = 3, cursorCol = 4, modes = TERM_DEFAULT_MODES or TERM_MODE_ORIGIN, top = 1, bottom = 4)
        assertTrue(termSnapshot(screen).text().endsWith("${ESC}[2;4r${ESC}[?6h${ESC}[3;5H${ESC}[0m"))
    }

    @Test
    fun `autowrap off is set after the lines, which need it`() {
        val screen = TermScreen(3, 1, listOf(line("abc")), modes = TERM_DEFAULT_MODES and TERM_MODE_AUTOWRAP.inv())
        assertTrue(termSnapshot(screen).text().endsWith("${ESC}[?7l${ESC}[1;1H${ESC}[0m"))
    }

    @Test
    fun `too much scrollback loses its oldest lines, never the screen`() {
        val lines = List(100) { line("line $it") } + List(3) { line("screen $it") }
        val text = termSnapshot(TermScreen(20, 3, lines), maxBytes = 200).text()
        assertTrue(text.length <= 260, "${text.length}")
        assertTrue("screen 0" in text && "screen 2" in text)
        assertTrue("line 99" in text)
        assertFalse("line 0\r" in text)
    }
}

class TermInputTest {
    @Test
    fun `what the screen's emulator answers stays there, the host's answers`() {
        for (reply in listOf("$ESC[3;4R", "$ESC[?64;1;2;6;9;15;18;21;22c", "$ESC[>41;320;0c", "$ESC]10;rgb:ffff/ffff/ffff$ESC\\", "${ESC}P1\$r0m$ESC\\", "$ESC[8;24;80t")) {
            assertFalse(screenEmulatorSends(reply.toByteArray()), reply)
        }
    }

    @Test
    fun `mouse reports and pastes from the screen go to the host`() {
        for (input in listOf("$ESC[<0;12;3M", "$ESC[<0;12;3m", "$ESC[M !!", "$ESC[200~pasted$ESC[201~", "pasted text")) {
            assertTrue(screenEmulatorSends(input.toByteArray()), input)
        }
        assertFalse(screenEmulatorSends(ByteArray(0)))
    }

    @Test
    fun `Ctrl turns letters and some signs into control characters, as the terminal view does`() {
        assertEquals(3, terminalCodePoint('c'.code, ctrl = true))
        assertEquals(3, terminalCodePoint('C'.code, ctrl = true))
        assertEquals(0, terminalCodePoint(' '.code, ctrl = true))
        assertEquals(27, terminalCodePoint('['.code, ctrl = true))
        assertEquals(31, terminalCodePoint('/'.code, ctrl = true))
        assertEquals(127, terminalCodePoint('8'.code, ctrl = true))
        assertEquals('c'.code, terminalCodePoint('c'.code, ctrl = false))
        // Bluetooth keyboards' odd tilde, as plain ASCII.
        assertEquals('~'.code, terminalCodePoint(0x02DC, ctrl = false))
    }

    @Test
    fun `a code point as UTF-8, Alt putting Escape first`() {
        assertEquals("a", terminalBytes('a'.code, alt = false).text())
        assertEquals("${ESC}b", terminalBytes('b'.code, alt = true).text())
        assertEquals("é", terminalBytes('é'.code, alt = false).text())
        assertEquals("😀", terminalBytes(0x1F600, alt = false).text())
    }
}

class TermPacerTest {
    @Test
    fun `output goes until too much is unacked, then the screen needs the whole state again`() {
        val pacer = TermPacer(limit = 100)
        assertEquals(1, pacer.epoch)
        assertTrue(pacer.output(60))
        assertTrue(pacer.output(40))
        assertFalse(pacer.output(1))
        // Stale: nothing more goes, even small, until a resync.
        assertFalse(pacer.output(1))
        assertFalse(pacer.acked(1, 60))
        assertTrue(pacer.acked(1, 100))
        assertEquals(2, pacer.resync())
        assertTrue(pacer.output(100))
    }

    @Test
    fun `acks of an old epoch count for nothing`() {
        val pacer = TermPacer(limit = 100)
        pacer.output(100)
        pacer.resync()
        assertFalse(pacer.acked(1, 100))
        assertTrue(pacer.output(100))
        assertFalse(pacer.output(1))
        assertTrue(pacer.acked(2, 101))
    }
}

class ShownTermTest {
    @Test
    fun `output applies after its epoch's state, and is acked as it comes`() {
        val term = ShownTerm(4, "bash", 80, 24)
        assertFalse(term.output(1, 10).apply)
        assertEquals(TermStep(apply = true), term.state(1, 80, 24))
        assertEquals(TermStep(apply = true, ack = ScreenMessage.TermAck(4, 1, 10)), term.output(1, 10))
        assertEquals(TermStep(apply = true, ack = ScreenMessage.TermAck(4, 1, 15)), term.output(1, 5))
        // A late part of the epoch before.
        assertFalse(term.output(0, 5).apply)
    }

    @Test
    fun `a new size is asked once, and output waits for the state at it`() {
        val term = ShownTerm(4, "bash", 80, 24)
        term.state(1, 80, 24)
        assertEquals(ScreenMessage.TermSize(4, 56, 40, 20, 40), term.fit(56, 40, 20, 40))
        assertNull(term.fit(56, 40, 20, 40))
        // Output drawn at the old size would land wrong on the resized one: it's dropped, still acked.
        val step = term.output(1, 7)
        assertFalse(step.apply)
        assertEquals(ScreenMessage.TermAck(4, 1, 7), step.ack)
        assertEquals(TermStep(apply = true), term.state(2, 56, 40))
        assertTrue(term.output(2, 3).apply)
        assertEquals(Pair(56, 40), Pair(term.columns, term.rows))
    }

    @Test
    fun `the size it has already is not asked`() {
        val term = ShownTerm(4, "bash", 80, 24)
        term.state(1, 80, 24)
        assertNull(term.fit(80, 24, 20, 40))
        assertTrue(term.output(1, 3).apply)
    }

    @Test
    fun `a state at another size than the one asked means the answer is still coming`() {
        val term = ShownTerm(4, "bash", 80, 24)
        term.state(1, 80, 24)
        term.fit(56, 40, 20, 40)
        term.fit(40, 30, 20, 40)
        term.state(2, 56, 40)
        assertFalse(term.output(2, 3).apply)
        term.state(3, 40, 30)
        assertTrue(term.output(3, 3).apply)
    }

    @Test
    fun `a state that undoes the size this phone fits asks again`() {
        val term = ShownTerm(4, "bash", 80, 24)
        term.state(1, 80, 24)
        term.fit(56, 40, 20, 40)
        term.state(2, 56, 40)
        // The host went back (another resync at the old size): ask for this phone's again.
        assertEquals(TermStep(apply = true, ask = ScreenMessage.TermSize(4, 56, 40, 20, 40)), term.state(3, 80, 24))
    }
}
