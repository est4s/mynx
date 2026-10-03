package io.github.est4s.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import io.github.est4s.terminal.core.KeyButton
import io.github.est4s.terminal.core.KeyStroke
import io.github.est4s.terminal.core.LoadedKeyBar
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.keyBarName
import io.github.est4s.terminal.core.keyBarPages
import io.github.est4s.terminal.core.loadKeyBar
import java.io.File
import kotlin.math.abs

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
 * The buttons between the terminal and the keyboard: two rows, always
 * visible. Shows the bar the selected tab's program asked for (see the
 * `keybar` command in Debian), else the shell's. Buttons that don't fit
 * in two rows go on more pages; swipe sideways to switch. Buttons aren't
 * focusable, so typing stays on the terminal.
 */
class KeyBarView(
    context: Context,
    private val terminalView: TerminalView,
    private val font: Typeface,
    private val userDir: File,
) : FrameLayout(context) {
    private var bar: LoadedKeyBar? = null
    // What the bar was loaded from: the tab's report file, its timestamp and
    // length (two quick writes can share a timestamp, not a length).
    private var loadedFrom: Triple<String?, Long, Long>? = null
    private var ctrlLatched = false
    private var pages: List<List<List<Int>>> = emptyList()
    private var page = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var swiping = false
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var colors: StripColors? = null
        set(value) {
            field = value
            value?.let { setBackgroundColor(it.background) }
            render()
        }

    init {
        isFocusable = false
        setWillNotDraw(false)
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
        page = 0
        render()
        return loaded
    }

    /** The sticky Ctrl, if on; turns it off. */
    fun takeCtrlLatch(): Boolean {
        if (!ctrlLatched) return false
        ctrlLatched = false
        render()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) post { render() }
    }

    // As many buttons to a row as the widest label allows.
    private fun perRow(buttons: List<KeyButton>): Int {
        val paint = Paint().apply {
            typeface = font
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        }
        val widest = buttons.maxOfOrNull { paint.measureText(it.label) }?.toInt() ?: 0
        return (width / maxOf(widest + 2 * dp(6), dp(44))).coerceAtLeast(1)
    }

    private fun render() {
        val colors = colors ?: return
        val buttons = bar?.buttons ?: emptyList()
        removeAllViews()
        if (width == 0) return // laid out later; onSizeChanged renders again
        pages = keyBarPages(buttons.size, perRow(buttons))
        page = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val rows = pages.getOrNull(page) ?: listOf(emptyList(), emptyList())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            for (row in rows) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // An empty row keeps its height, so the bar never changes size.
                    if (row.isEmpty()) addView(button(null, colors), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    for (i in row) addView(button(buttons[i], colors), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        invalidate()
    }

    private fun button(button: KeyButton?, colors: StripColors) = TextView(context).apply {
        val latched = ctrlLatched && button?.strokes == listOf(KeyStroke.CtrlLatch)
        text = button?.label ?: " "
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (latched) colors.accent else colors.text)
        if (latched) setBackgroundColor(colors.selectedBackground)
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(dp(2), dp(9), dp(2), dp(9))
        isFocusable = false
        if (button != null) setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            press(button)
        }
    }

    // Page dots, drawn over the bottom edge so they take no space.
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val colors = colors ?: return
        if (pages.size < 2) return
        val r = dp(2).toFloat()
        val gap = dp(8).toFloat()
        val y = height - dp(3).toFloat()
        var x = width / 2f - gap * (pages.size - 1) / 2
        for (i in pages.indices) {
            dotPaint.color = if (i == page) colors.accent else colors.text
            dotPaint.alpha = if (i == page) 255 else 90
            canvas.drawCircle(x, y, r, dotPaint)
            x += gap
        }
    }

    // A sideways swipe switches pages; anything shorter is a tap on a button.
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; swiping = false }
            MotionEvent.ACTION_MOVE -> if (pages.size > 1 && abs(e.x - downX) > touchSlop) swiping = true
        }
        return swiping
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Also here: touches that start between buttons never pass through
        // onInterceptTouchEvent.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; swiping = false }
            MotionEvent.ACTION_MOVE -> if (pages.size > 1 && abs(e.x - downX) > touchSlop) swiping = true
        }
        if (e.actionMasked == MotionEvent.ACTION_UP && swiping) {
            swiping = false
            val next = if (e.x < downX) page + 1 else page - 1
            if (next in pages.indices) {
                page = next
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                render()
            }
        }
        return true
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
