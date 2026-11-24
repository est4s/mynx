package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// The X screen is the view's size divided by this, so programs aren't drawn at the phone's density.
private const val SCALE = 2

/**
 * SPIKE (desktop-spike, throwaway): shows the X server's screen in a
 * SurfaceView the server draws into ([X11Spike]). One finger is the mouse's
 * left button at the finger; the keyboard types. A log over the screen shows
 * what happened (Log hides it). Opened from the launcher icon's long-press
 * menu ("Desktop (spike)"). Android 11+ only (the insets API): fine for a spike.
 */
@SuppressLint("NewApi")
class X11SpikeActivity : Activity(), SurfaceHolder.Callback, X11Spike.Listener {
    private lateinit var root: InputFrame
    private lateinit var surfaceView: SurfaceView
    private lateinit var pointer: PointerView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusView: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = InputFrame(this)
        root.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, e -> touch(e); true }
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pointer = PointerView(this)
        root.addView(pointer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(logView)
        }
        root.addView(logScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(96) })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
        }
        statusView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; maxLines = 2 }
        bar.addView(statusView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun button(label: String, action: () -> Unit) = bar.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        button("Keys") { toggleKeyboard() }
        button("Log") { logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        button("Start") { X11Spike.start(this) }
        button("Stop") { X11Spike.stop() }
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Keep clear of the status bar and camera at the top, and of the keyboard at the bottom.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
        root.requestFocus()

        X11Spike.listener = this
        X11Spike.start(this)
        changed()
    }

    override fun onDestroy() {
        if (X11Spike.listener === this) X11Spike.listener = null
        super.onDestroy()
    }

    override fun changed() {
        statusView.text = "DISPLAY=${X11Spike.DISPLAY}  ${if (X11Spike.connected) "connected" else X11Spike.status}"
        logView.text = X11Spike.logText()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        @Suppress("DEPRECATION") val rate = windowManager.defaultDisplay.refreshRate.toInt()
        X11Spike.log("mynx: surface ${width}x$height")
        X11Spike.surface(holder.surface, width / SCALE, height / SCALE, rate)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        X11Spike.surface(null, 0, 0, 0)
    }

    private fun touch(e: MotionEvent) {
        val x = e.x / SCALE
        val y = e.y / SCALE
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                X11Spike.mouse(x, y, 0, false)
                X11Spike.mouse(x, y, 1, true)
            }
            MotionEvent.ACTION_MOVE -> X11Spike.mouse(x, y, 0, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                X11Spike.mouse(x, y, 0, false)
                X11Spike.mouse(x, y, 1, false)
            }
        }
        pointer.at(e.x, e.y)
    }

    private fun toggleKeyboard() {
        root.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) imm.hideSoftInputFromWindow(root.windowToken, 0)
        else imm.showSoftInput(root, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> return super.dispatchKeyEvent(event)
        }
        if (!X11Spike.connected) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when {
            event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null ->
                typeText(event.characters)
            // A hardware keyboard's scan code is the Linux key code.
            event.scanCode > 0 && event.action != KeyEvent.ACTION_MULTIPLE -> X11Spike.key(event.scanCode, down)
            LINUX_KEYS.containsKey(event.keyCode) -> X11Spike.key(LINUX_KEYS.getValue(event.keyCode), down)
            event.unicodeChar != 0 -> if (down) X11Spike.unicode(event.unicodeChar)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun typeText(text: CharSequence) {
        var i = 0
        while (i < text.length) {
            val c = Character.codePointAt(text, i)
            typeChar(c)
            i += Character.charCount(c)
        }
    }

    private fun typeChar(c: Int) {
        when (c) {
            '\n'.code -> { X11Spike.key(28, true); X11Spike.key(28, false) }
            '\t'.code -> { X11Spike.key(15, true); X11Spike.key(15, false) }
            else -> X11Spike.unicode(c)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Takes the soft keyboard's text: committed text is typed, deletes are Backspace. */
    private inner class InputFrame(context: Context) : FrameLayout(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    typeText(text)
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(maxOf(beforeLength, 1)) { X11Spike.key(14, true); X11Spike.key(14, false) }
                    return true
                }
            }
        }
    }

    /** Where the finger last was: the X server's own cursor isn't shown yet. */
    private class PointerView(context: Context) : View(context) {
        private var px = -1f
        private var py = -1f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 48f); lineTo(13f, 36f); lineTo(22f, 56f); lineTo(30f, 52f); lineTo(21f, 33f); lineTo(38f, 33f); close()
        }

        fun at(x: Float, y: Float) {
            px = x; py = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (px < 0) return
            canvas.save()
            canvas.translate(px, py)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
            canvas.restore()
        }
    }

    private companion object {
        // Android key codes to Linux (evdev) key codes, for keys that type no character.
        val LINUX_KEYS = mapOf(
            KeyEvent.KEYCODE_ENTER to 28, KeyEvent.KEYCODE_DEL to 14, KeyEvent.KEYCODE_FORWARD_DEL to 111,
            KeyEvent.KEYCODE_TAB to 15, KeyEvent.KEYCODE_ESCAPE to 1, KeyEvent.KEYCODE_SPACE to 57,
            KeyEvent.KEYCODE_DPAD_UP to 103, KeyEvent.KEYCODE_DPAD_DOWN to 108,
            KeyEvent.KEYCODE_DPAD_LEFT to 105, KeyEvent.KEYCODE_DPAD_RIGHT to 106,
            KeyEvent.KEYCODE_MOVE_HOME to 102, KeyEvent.KEYCODE_MOVE_END to 107,
            KeyEvent.KEYCODE_PAGE_UP to 104, KeyEvent.KEYCODE_PAGE_DOWN to 109, KeyEvent.KEYCODE_INSERT to 110,
            KeyEvent.KEYCODE_CTRL_LEFT to 29, KeyEvent.KEYCODE_CTRL_RIGHT to 97,
            KeyEvent.KEYCODE_SHIFT_LEFT to 42, KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
            KeyEvent.KEYCODE_ALT_LEFT to 56, KeyEvent.KEYCODE_ALT_RIGHT to 100, KeyEvent.KEYCODE_META_LEFT to 125,
        ) + (0..9).associate { KeyEvent.KEYCODE_F1 + it to 59 + it } +
            mapOf(KeyEvent.KEYCODE_F11 to 87, KeyEvent.KEYCODE_F12 to 88)
    }
}
