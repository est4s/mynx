package io.github.est4s.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import io.github.est4s.terminal.core.KeyButton
import io.github.est4s.terminal.core.KeyStroke
import io.github.est4s.terminal.core.LoadedKeyBar
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.keyBarName
import io.github.est4s.terminal.core.loadKeyBar
import java.io.File

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
 * The row of buttons between the terminal and the keyboard. Shows the bar
 * the selected tab's program asked for (see the `keybar` command in
 * Debian), else the shell's. Buttons aren't focusable, so typing stays on
 * the terminal.
 */
class KeyBarView(
    context: Context,
    private val terminalView: TerminalView,
    private val font: Typeface,
    private val userDir: File,
) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var bar: LoadedKeyBar? = null
    // What the bar was loaded from: the tab's report file, its timestamp and
    // length (two quick writes can share a timestamp, not a length).
    private var loadedFrom: Triple<String?, Long, Long>? = null
    private var ctrlLatched = false

    var colors: StripColors? = null
        set(value) {
            field = value
            value?.let { setBackgroundColor(it.background) }
            render()
        }

    init {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        addView(row)
    }

    /**
     * Reloads the bar if the tab's report file changed (or [force], e.g.
     * after the user may have edited bar files). Cheap when nothing
     * changed: a stat or two. Returns the bar when it was (re)loaded.
     */
    fun refresh(reportFile: File?, force: Boolean = false): LoadedKeyBar? {
        val stamp = Triple(reportFile?.path, reportFile?.lastModified() ?: 0L, reportFile?.length() ?: 0L)
        if (!force && stamp == loadedFrom) return null
        loadedFrom = stamp
        val name = keyBarName(runCatching { reportFile?.readText() }.getOrNull())
        val loaded = loadKeyBar(name, userDir)
        if (loaded == bar) return null
        bar = loaded
        ctrlLatched = false
        render()
        scrollTo(0, 0)
        return loaded
    }

    /** The sticky Ctrl, if on; turns it off. */
    fun takeCtrlLatch(): Boolean {
        if (!ctrlLatched) return false
        ctrlLatched = false
        render()
        return true
    }

    private fun render() {
        val colors = colors ?: return
        row.removeAllViews()
        bar?.buttons?.forEach { button ->
            val latched = ctrlLatched && button.strokes == listOf(KeyStroke.CtrlLatch)
            row.addView(TextView(context).apply {
                text = button.label
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (latched) colors.accent else colors.text)
                if (latched) setBackgroundColor(colors.selectedBackground)
                gravity = Gravity.CENTER
                minWidth = dp(44)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isFocusable = false
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press(button)
                }
            })
        }
    }

    private fun press(button: KeyButton) {
        // Not started yet (or finished): there's nothing to type into.
        val session = terminalView.currentSession?.takeIf { it.emulator != null } ?: return
        for (stroke in button.strokes) {
            when (stroke) {
                KeyStroke.CtrlLatch -> {
                    ctrlLatched = !ctrlLatched
                    render()
                }
                is KeyStroke.Text -> session.write(stroke.text)
                is KeyStroke.Key -> sendKey(stroke)
            }
        }
    }

    private fun sendKey(key: KeyStroke.Key) {
        val code = KEY_CODES[key.key]
        if (code == null) {
            // A single character; inputCodePoint applies the sticky Ctrl itself.
            terminalView.inputCodePoint(key.key.codePointAt(0), key.ctrl, key.alt)
            return
        }
        val ctrl = key.ctrl || takeCtrlLatch()
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (key.alt) mod = mod or KeyHandler.KEYMOD_ALT
        // The library has no sequence for a plain Space; type it instead.
        if (!terminalView.handleKeyCode(code, mod) && key.key == "Space") {
            terminalView.inputCodePoint(' '.code, ctrl, key.alt)
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
