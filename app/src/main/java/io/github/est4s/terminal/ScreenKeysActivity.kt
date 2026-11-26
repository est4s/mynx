package io.github.est4s.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On the host: typing for a sent screen's text field (the extended
 * screen; typing goes to the screen touched last). A bar at the bottom
 * with this phone's keyboard up: what's typed goes to the field on the
 * other phone, as key events ([ScreenCaster.type], [ScreenCaster.press]).
 * Touching this phone outside it, Done or Back closes it, and typing is
 * this phone's again.
 */
class ScreenKeysActivity : Activity() {
    /** Where the typing goes; set by the service while a sent screen's field has the focus. */
    interface Target {
        val label: String
        fun type(text: String)
        fun press(code: Int)
        fun key(event: KeyEvent)
        /** The bar was closed here (this phone touched, Done, Back): typing is this phone's again. */
        fun closedHere()
    }

    private var closingForTarget = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = target ?: return finish()
        current = this
        setFinishOnTouchOutside(true)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(MATCH_PARENT, WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(build(this, target))
    }

    private fun build(context: Context, target: Target): LinearLayout {
        val pad = Pixel.dp(context, 12)
        val title = TextView(context).apply {
            text = target.label
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Pixel.CYAN)
        }
        val field = EditText(context).apply {
            hint = "Type here"
            setTextColor(Pixel.TEXT)
            setHintTextColor(Pixel.LINE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_NONE
            // What the keyboard writes here is sent there; the field keeps it only to see.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: Editable) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    repeat(before) { target.press(KeyEvent.KEYCODE_DEL) }
                    val added = s.subSequence(start, start + count).toString()
                    if (added.isNotEmpty()) target.type(added)
                }
            })
            // Delete in an empty field, and keys that write nothing (arrows, Tab, Escape): straight there.
            setOnKeyListener { _, _, event ->
                val passes = event.keyCode == KeyEvent.KEYCODE_DEL && text.isEmpty() ||
                    event.keyCode in PASSED_KEYS
                if (passes) target.key(event)
                passes
            }
        }
        val done = TextView(context).apply {
            text = "Done"
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Pixel.TEXT)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { finish() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(done, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        field.requestFocus()
        field.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pixel.BACKGROUND)
            setPadding(pad, pad / 2, pad, pad)
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    // Finishing on its own (a touch outside the bar is this phone's screen touched, Done, Back):
    // typing comes back here.
    override fun onDestroy() {
        if (current === this) current = null
        if (!closingForTarget && isFinishing) target?.closedHere()
        super.onDestroy()
    }

    companion object {
        private val PASSED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_FORWARD_DEL,
        )

        /** Where typing goes now; the service sets it before opening the bar. Main thread. */
        var target: Target? = null

        private var current: ScreenKeysActivity? = null

        val open: Boolean get() = current != null

        fun intent(context: Context): Intent = Intent(context, ScreenKeysActivity::class.java)

        /** Closes the bar for the target's sake (the field lost the focus, the screen came back): no closedHere. */
        fun close() {
            val bar = current ?: return
            bar.closingForTarget = true
            bar.finish()
        }
    }
}
