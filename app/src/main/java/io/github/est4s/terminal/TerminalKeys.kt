package io.github.est4s.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import io.github.est4s.terminal.core.KeyButton
import io.github.est4s.terminal.core.KeyStroke

private val KEY_CODES: Map<String, Int> = mapOf(
    "Enter" to KeyEvent.KEYCODE_ENTER,
    "Esc" to KeyEvent.KEYCODE_ESCAPE,
    "Tab" to KeyEvent.KEYCODE_TAB,
    "Space" to KeyEvent.KEYCODE_SPACE,
    "Backspace" to KeyEvent.KEYCODE_DEL,
    "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
    "Insert" to KeyEvent.KEYCODE_INSERT,
    "Up" to KeyEvent.KEYCODE_DPAD_UP,
    "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
    "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "Home" to KeyEvent.KEYCODE_MOVE_HOME,
    "End" to KeyEvent.KEYCODE_MOVE_END,
    "PgUp" to KeyEvent.KEYCODE_PAGE_UP,
    "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
) + (1..12).associate { "F$it" to KeyEvent.KEYCODE_F1 + it - 1 }

/**
 * The key bar's keys for a terminal tab: written to its session as the
 * terminal library writes those keys. The sticky Ctrl and Alt hold for
 * the next key, from the bar or the keyboard (the library asks through
 * [takeCtrlLatch] and [takeAltLatch]). A terminal has no Super: its
 * sticky button does nothing, and Super+ chords send the key alone.
 */
class TerminalKeys(private val terminalView: TerminalView) : KeyBarTarget {
    private var ctrlLatched = false
    private var altLatched = false
    override var onLatchChange: (() -> Unit)? = null

    override fun latched(button: KeyButton): Boolean = when (button.strokes) {
        listOf(KeyStroke.CtrlLatch) -> ctrlLatched
        listOf(KeyStroke.AltLatch) -> altLatched
        else -> false
    }

    override fun reset() {
        ctrlLatched = false
        altLatched = false
    }

    /** The sticky Ctrl, if on; turns it off. */
    fun takeCtrlLatch(): Boolean {
        if (!ctrlLatched) return false
        ctrlLatched = false
        onLatchChange?.invoke()
        return true
    }

    /** The sticky Alt, likewise. */
    fun takeAltLatch(): Boolean {
        if (!altLatched) return false
        altLatched = false
        onLatchChange?.invoke()
        return true
    }

    override fun press(button: KeyButton) {
        // Not started yet (or finished): there's nothing to type into.
        val session = terminalView.currentSession?.takeIf { it.emulator != null } ?: return
        for (stroke in button.strokes) {
            when (stroke) {
                KeyStroke.CtrlLatch -> {
                    ctrlLatched = !ctrlLatched
                    onLatchChange?.invoke()
                }
                KeyStroke.AltLatch -> {
                    altLatched = !altLatched
                    onLatchChange?.invoke()
                }
                KeyStroke.SuperLatch -> {}
                is KeyStroke.Text -> session.write(stroke.text)
                is KeyStroke.Key -> sendKey(stroke)
            }
        }
    }

    private fun sendKey(key: KeyStroke.Key) {
        val code = KEY_CODES[key.key]
        if (code == null) {
            // A single character; inputCodePoint applies the sticky Ctrl and Alt itself.
            terminalView.inputCodePoint(key.key.codePointAt(0), key.ctrl, key.alt)
            return
        }
        val ctrl = key.ctrl || takeCtrlLatch()
        val alt = key.alt || takeAltLatch()
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        if (key.shift) mod = mod or KeyHandler.KEYMOD_SHIFT
        // The library has no sequence for a plain Space; type it instead.
        if (!terminalView.handleKeyCode(code, mod) && key.key == "Space") {
            terminalView.inputCodePoint(' '.code, ctrl, alt)
        }
    }
}
