package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream

// Terminal tabs on the other phone, as text (work log "Next" 0a; docs/ARCHITECTURE.md, "Extended
// screen: terminals on B"). The host's session keeps running there, and its emulator stays the one
// that answers programs; the screen runs an emulator of its own (Termux's, as here), fed a
// [TermState] that draws everything the host's shows, then the same bytes the host's programs
// write ([ScreenMessage.TermOutput]), at the same size: so both show the same, and the screen
// draws it with its own font, colours, scrollback and selection.

// Termux's TextStyle, which the cells' styles are in (64 bits: 11 effect flags, then the
// background at bit 16 and the foreground at 40, each a colour index or 24-bit RGB).
const val TERM_BOLD = 1
const val TERM_ITALIC = 1 shl 1
const val TERM_UNDERLINE = 1 shl 2
const val TERM_BLINK = 1 shl 3
const val TERM_INVERSE = 1 shl 4
const val TERM_INVISIBLE = 1 shl 5
const val TERM_STRIKETHROUGH = 1 shl 6
const val TERM_DIM = 1 shl 8
private const val TERM_TRUECOLOR_FOREGROUND = 1 shl 9
private const val TERM_TRUECOLOR_BACKGROUND = 1 shl 10
const val TERM_COLOR_FOREGROUND = 256
const val TERM_COLOR_BACKGROUND = 257

/** A style as Termux's `TextStyle.encode` makes it: colours are indexes (0-257) or 0xffRRGGBB. */
fun termStyle(fore: Int, back: Int, effect: Int): Long {
    var style = (effect and 0b111111111).toLong()
    style = if (fore.toLong() and 0xff000000L == 0xff000000L) {
        style or TERM_TRUECOLOR_FOREGROUND.toLong() or ((fore.toLong() and 0xffffffL) shl 40)
    } else {
        style or ((fore.toLong() and 0b111111111L) shl 40)
    }
    style = if (back.toLong() and 0xff000000L == 0xff000000L) {
        style or TERM_TRUECOLOR_BACKGROUND.toLong() or ((back.toLong() and 0xffffffL) shl 16)
    } else {
        style or ((back.toLong() and 0b111111111L) shl 16)
    }
    return style
}

/** Termux's `TextStyle.NORMAL`: the default colours, no effects. */
val TERM_NORMAL: Long = termStyle(TERM_COLOR_FOREGROUND, TERM_COLOR_BACKGROUND, 0)

// Termux's DECSET bits (TerminalEmulator's DECSET_BIT_*), and the mode each one is.
const val TERM_MODE_CURSOR_KEYS = 1
const val TERM_MODE_REVERSE = 1 shl 1
const val TERM_MODE_ORIGIN = 1 shl 2
const val TERM_MODE_AUTOWRAP = 1 shl 3
const val TERM_MODE_CURSOR_SHOWN = 1 shl 4
const val TERM_MODE_KEYPAD = 1 shl 5
const val TERM_MODE_MOUSE_CLICKS = 1 shl 6
const val TERM_MODE_MOUSE_BUTTONS = 1 shl 7
const val TERM_MODE_FOCUS = 1 shl 8
const val TERM_MODE_MOUSE_SGR = 1 shl 9
const val TERM_MODE_PASTE = 1 shl 10
const val TERM_MODE_LEFT_RIGHT_MARGINS = 1 shl 11

/** What a reset terminal has: autowrap, and the cursor shown. */
const val TERM_DEFAULT_MODES = TERM_MODE_AUTOWRAP or TERM_MODE_CURSOR_SHOWN

private val DEC_MODES = listOf(
    TERM_MODE_CURSOR_KEYS to 1, TERM_MODE_REVERSE to 5, TERM_MODE_ORIGIN to 6, TERM_MODE_AUTOWRAP to 7,
    TERM_MODE_CURSOR_SHOWN to 25, TERM_MODE_KEYPAD to 66, TERM_MODE_MOUSE_CLICKS to 1000,
    TERM_MODE_MOUSE_BUTTONS to 1002, TERM_MODE_FOCUS to 1004, TERM_MODE_MOUSE_SGR to 1006, TERM_MODE_PASTE to 2004,
    TERM_MODE_LEFT_RIGHT_MARGINS to 69,
)

/** One cell of a terminal line: its [text] (a character, with any combining ones), [width] columns, [style] ([termStyle]). */
data class TermCell(val text: String, val width: Int, val style: Long)

/** A terminal line's cells (one per character, a wide one taking two columns), and whether it [wrapped] onto the next. */
data class TermLine(val cells: List<TermCell>, val wrapped: Boolean = false)

/**
 * What a terminal shows, read off the host's emulator for [termSnapshot]:
 * [lines] are the main buffer's, scrollback first and the screen's [rows]
 * last; [alt], the alternate screen's rows while a program has it. The
 * cursor, the [modes] (`TERM_MODE_*`), the scrolling margins ([bottom] and
 * [right] exclusive) and the [style] programs write in now.
 */
data class TermScreen(
    val columns: Int,
    val rows: Int,
    val lines: List<TermLine>,
    val alt: List<TermLine>? = null,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val modes: Int = TERM_DEFAULT_MODES,
    val top: Int = 0,
    val bottom: Int = rows,
    val left: Int = 0,
    val right: Int = columns,
    val style: Long = TERM_NORMAL,
)

/** The most a [TermState]'s bytes take: its oldest scrollback goes first. */
const val TERM_STATE_MAX = 1024 * 1024

/** The SGR sequence that sets [style] from scratch. */
fun termSgr(style: Long): String {
    val effect = (style and 0b11111111111).toInt()
    val parts = mutableListOf("0")
    for ((bit, code) in listOf(
        TERM_BOLD to 1, TERM_DIM to 2, TERM_ITALIC to 3, TERM_UNDERLINE to 4, TERM_BLINK to 5,
        TERM_INVERSE to 7, TERM_INVISIBLE to 8, TERM_STRIKETHROUGH to 9,
    )) {
        if (effect and bit != 0) parts += "$code"
    }
    fun colour(truecolor: Boolean, value: Long, base: Int, bright: Int, extended: Int, default: Int) {
        if (truecolor) {
            parts += "$extended;2;${(value shr 16) and 0xff};${(value shr 8) and 0xff};${value and 0xff}"
            return
        }
        val index = (value and 0b111111111).toInt()
        when {
            index == default || index > 255 -> {}
            index < 8 -> parts += "${base + index}"
            index < 16 -> parts += "${bright + index - 8}"
            else -> parts += "$extended;5;$index"
        }
    }
    colour(effect and TERM_TRUECOLOR_FOREGROUND != 0, (style ushr 40) and 0xffffff, 30, 90, 38, TERM_COLOR_FOREGROUND)
    colour(effect and TERM_TRUECOLOR_BACKGROUND != 0, (style ushr 16) and 0xffffff, 40, 100, 48, TERM_COLOR_BACKGROUND)
    return "\u001b[${parts.joinToString(";")}m"
}

// A line as the bytes that print it from its first column: each starts its own style, so lines
// can be left out. A line that doesn't wrap loses its plain blanks at the end.
private fun lineText(line: TermLine): String {
    var cells = line.cells
    if (!line.wrapped) cells = cells.dropLastWhile { it.text == " " && it.style == TERM_NORMAL }
    val text = StringBuilder()
    var style: Long? = null
    for (cell in cells) {
        if (cell.style != style) {
            text.append(termSgr(cell.style))
            style = cell.style
        }
        text.append(cell.text)
    }
    return text.toString()
}

private fun linesText(lines: List<TermLine>): String {
    val text = StringBuilder()
    lines.forEachIndexed { i, line ->
        text.append(lineText(line))
        // A wrapped line fills its row: the next one's first character wraps there, as it did here.
        if (i < lines.size - 1 && !line.wrapped) text.append("\r\n")
    }
    return text.toString()
}

/**
 * The bytes that draw [screen] on a terminal of its size (Termux's, or
 * any VT-like one): a reset (RIS, which empties the scrollback too), the
 * lines from the oldest kept (as many as fit in [maxBytes]; the screen's
 * always), the alternate screen, then the margins, modes, cursor and
 * style, so that the bytes programs write next land as they did on the
 * host. Not carried: tab stops, character sets, the saved cursor, colours
 * programs changed (the screen keeps its own theme) and the title (it
 * goes as [ScreenMessage.TermTitle]).
 */
fun termSnapshot(screen: TermScreen, maxBytes: Int = TERM_STATE_MAX): ByteArray {
    val visible = screen.lines.takeLast(screen.rows)
    var kept = visible
    var size = linesText(visible).toByteArray().size
    for (line in screen.lines.dropLast(screen.rows).asReversed()) {
        val more = lineText(line).toByteArray().size + 2
        if (size + more > maxBytes) break
        size += more
        kept = listOf(line) + kept
    }
    val text = StringBuilder("\u001bc")
    text.append(linesText(kept))
    screen.alt?.let { alt ->
        text.append("\u001b[0m\u001b[?1049h\u001b[H")
        text.append(linesText(alt.take(screen.rows)))
    }
    text.append("\u001b[0m")
    if (screen.top != 0 || screen.bottom != screen.rows) text.append("\u001b[${screen.top + 1};${screen.bottom}r")
    for ((bit, mode) in DEC_MODES) {
        val on = screen.modes and bit != 0
        if (on != (TERM_DEFAULT_MODES and bit != 0)) text.append("\u001b[?$mode${if (on) "h" else "l"}")
    }
    if (screen.modes and TERM_MODE_LEFT_RIGHT_MARGINS != 0 && (screen.left != 0 || screen.right != screen.columns)) {
        text.append("\u001b[${screen.left + 1};${screen.right}s")
    }
    val origin = screen.modes and TERM_MODE_ORIGIN != 0
    val row = if (origin) screen.cursorRow - screen.top else screen.cursorRow
    val col = if (origin) screen.cursorCol - screen.left else screen.cursorCol
    text.append("\u001b[${row + 1};${col + 1}H")
    text.append(termSgr(screen.style))
    return text.toString().toByteArray()
}

/**
 * Whether what the screen's own emulator writes goes to the host's
 * programs: its mouse reports and pastes (the screen's touches and
 * clipboard) do; its answers to programs' questions (cursor position,
 * device attributes, colours…) don't, since the host's emulator gets the
 * same questions and answers them.
 */
fun screenEmulatorSends(data: ByteArray): Boolean {
    if (data.isEmpty()) return false
    if (data[0] != 0x1b.toByte()) return true
    val text = String(data, Charsets.ISO_8859_1)
    return text.startsWith("\u001b[200~") || text.startsWith("\u001b[M") ||
        (text.startsWith("\u001b[<") && (text.endsWith("M") || text.endsWith("m")))
}

/** What the terminal view types for [codePoint] with Ctrl held (Termux's `TerminalView.inputCodePoint`). */
fun terminalCodePoint(codePoint: Int, ctrl: Boolean): Int {
    var c = codePoint
    if (ctrl) {
        c = when (c) {
            in 'a'.code..'z'.code -> c - 'a'.code + 1
            in 'A'.code..'Z'.code -> c - 'A'.code + 1
            ' '.code, '2'.code -> 0
            '['.code, '3'.code -> 27
            '\\'.code, '4'.code -> 28
            ']'.code, '5'.code -> 29
            '^'.code, '6'.code -> 30
            '_'.code, '7'.code, '/'.code -> 31
            '8'.code -> 127
            else -> c
        }
    }
    // Bluetooth keyboards' look-alikes, as the ASCII programs expect.
    return when (c) {
        0x02DC -> '~'.code
        0x02CB -> '`'.code
        0x02C6 -> '^'.code
        else -> c
    }
}

/** [codePoint] as UTF-8, Escape first with [alt] (Alt+B in readline). */
fun terminalBytes(codePoint: Int, alt: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    if (alt) out.write(0x1b)
    out.write(String(Character.toChars(codePoint)).toByteArray())
    return out.toByteArray()
}

/** How much terminal output may be on its way to the screen, unacked: more, and it's sent the whole state instead once it catches up. */
const val TERM_UNACKED_MAX = 256 * 1024

/**
 * The host's flow control of one terminal's output: [output] says
 * whether a part goes; past [limit] unacked bytes it goes stale and
 * drops everything until the screen has acked all it got ([acked] says
 * so), and then the screen gets the whole state again ([resync], a new
 * epoch). A screen that can't keep up (`yes`, a big `cat`) so gets the
 * screen as it is now, not a queue of what it missed. Any thread.
 */
class TermPacer(private val limit: Int = TERM_UNACKED_MAX) {
    var epoch = 1
        private set
    private var sent = 0L
    private var acked = 0L
    private var stale = false

    /** Whether [bytes] more go now. */
    @Synchronized
    fun output(bytes: Int): Boolean {
        if (stale) return false
        if (sent - acked + bytes > limit) {
            stale = true
            return false
        }
        sent += bytes
        return true
    }

    /** The screen got [upTo] bytes of [epoch]'s output; true: it's caught up, stale, and needs a [resync]. */
    @Synchronized
    fun acked(epoch: Int, upTo: Long): Boolean {
        if (epoch != this.epoch) return false
        acked = maxOf(acked, upTo)
        return stale && acked >= sent
    }

    /** A new state is sent: its epoch. */
    @Synchronized
    fun resync(): Int {
        epoch++
        sent = 0
        acked = 0
        stale = false
        return epoch
    }
}

/** What a [ShownTerm] says to do with a state or output: draw it ([apply]), and messages for the host. */
data class TermStep(val apply: Boolean = false, val ack: ScreenMessage.TermAck? = null, val ask: ScreenMessage.TermSize? = null)

/**
 * On the screen: terminal [id] the host sent, at [columns] x [rows]. Its
 * output draws only after its epoch's state, and not while this phone
 * waits for the state at a size it asked for ([fit]): this phone's
 * emulator has that size already, and output drawn at the old one would
 * land wrong (the state that comes redraws it all).
 */
class ShownTerm(val id: Int, var title: String, columns: Int, rows: Int) {
    var columns = columns
        private set
    var rows = rows
        private set
    private var epoch = 0
    private var received = 0L
    private var asked: Pair<Int, Int>? = null
    private var wanted: ScreenMessage.TermSize? = null

    /** A state of [epoch] at [columns] x [rows] came. */
    fun state(epoch: Int, columns: Int, rows: Int): TermStep {
        this.epoch = epoch
        this.columns = columns
        this.rows = rows
        received = 0
        val size = Pair(columns, rows)
        if (asked == size) asked = null
        val want = wanted
        if (asked == null && want != null && Pair(want.columns, want.rows) != size) {
            asked = Pair(want.columns, want.rows)
            return TermStep(apply = true, ask = want)
        }
        return TermStep(apply = true)
    }

    /** [bytes] of output of [epoch] came: whether to draw them, and the ack. */
    fun output(epoch: Int, bytes: Int): TermStep {
        if (epoch != this.epoch) return TermStep()
        received += bytes
        return TermStep(apply = asked == null, ack = ScreenMessage.TermAck(id, epoch, received))
    }

    /** This phone fits it in [columns] x [rows]: the size to ask for, or null if it has it or asked already. */
    fun fit(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): ScreenMessage.TermSize? {
        val size = Pair(columns, rows)
        wanted = ScreenMessage.TermSize(id, columns, rows, cellWidth, cellHeight)
        if (asked == size || (asked == null && size == Pair(this.columns, this.rows))) return null
        asked = size
        return wanted
    }
}
