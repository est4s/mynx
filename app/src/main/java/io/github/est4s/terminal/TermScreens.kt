package io.github.est4s.terminal

import com.termux.terminal.MynxTerminals
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import io.github.est4s.terminal.core.TermCell
import io.github.est4s.terminal.core.TermLine
import io.github.est4s.terminal.core.TermScreen
import io.github.est4s.terminal.core.termStyle

/**
 * What a terminal shows, read off Termux's emulator for core's
 * `termSnapshot` (the extended screen): the main buffer's scrollback and
 * screen, the alternate screen while a program has it, the cursor, modes,
 * margins and style. A session that hasn't started has nothing on it yet.
 */
fun termScreenOf(emulator: TerminalEmulator?, columns: Int, rows: Int): TermScreen {
    emulator ?: return TermScreen(columns, rows, emptyList())
    val cols = emulator.mColumns
    val height = emulator.mRows
    val alt = emulator.isAlternateBufferActive
    val main = if (alt) MynxTerminals.mainBuffer(emulator) else emulator.screen
    val modes = MynxTerminals.modes(emulator)
    return TermScreen(
        columns = cols,
        rows = height,
        lines = main?.let { linesOf(it, cols, -it.activeTranscriptRows, height) } ?: List(height) { TermLine(emptyList()) },
        alt = if (alt) linesOf(emulator.screen, cols, 0, height) else null,
        cursorRow = emulator.cursorRow,
        cursorCol = emulator.cursorCol,
        modes = modes[0],
        top = modes[1],
        bottom = modes[2],
        left = modes[3],
        right = modes[4],
        style = termStyle(modes[5], modes[6], modes[7]),
    )
}

// Rows [from] (negative: scrollback) up to [until], each as its cells.
private fun linesOf(buffer: TerminalBuffer, columns: Int, from: Int, until: Int): List<TermLine> =
    (from until until).map { row ->
        val line = buffer.allocateFullLineIfNecessary(buffer.externalToInternalRow(row))
        val text = line.mText
        val used = line.spaceUsed
        val cells = ArrayList<TermCell>(columns)
        var column = 0
        var i = 0
        // As Termux's renderer walks a row: each character takes its width in columns, combining ones none.
        while (i < used && column < columns) {
            val codePoint = Character.codePointAt(text, i, used)
            val chars = Character.charCount(codePoint)
            val width = WcWidth.width(codePoint)
            if (width <= 0 && cells.isNotEmpty()) {
                val last = cells.removeAt(cells.size - 1)
                cells.add(last.copy(text = last.text + String(text, i, chars)))
            } else if (width > 0) {
                cells.add(TermCell(String(text, i, chars), width, line.getStyle(column)))
                column += width
            }
            i += chars
        }
        TermLine(cells, wrapped = buffer.getLineWrap(row))
    }
