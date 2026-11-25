package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.AppView
import io.github.est4s.terminal.core.DESKTOP_DISPLAY
import io.github.est4s.terminal.core.DesktopGeometry
import io.github.est4s.terminal.core.DesktopGestures
import io.github.est4s.terminal.core.DesktopKeyboard
import io.github.est4s.terminal.core.DesktopStatus
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.Finger
import io.github.est4s.terminal.core.KeyButton
import io.github.est4s.terminal.core.KeyStroke
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.VIEW_SWITCHER
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.desktopGestures
import io.github.est4s.terminal.core.desktopNotice
import io.github.est4s.terminal.core.desktopScale
import io.github.est4s.terminal.core.desktopStartsOnOpen
import io.github.est4s.terminal.core.keyboardPan
import java.io.File
import kotlin.math.roundToInt

/** The built-in key bar the desktop shows (core's keybars/desktop.conf), unless the user has their own. */
const val DESKTOP_KEY_BAR = "desktop"
private const val POLL_MS = 1000L
private const val PAN_MARGIN_DP = 48
private const val DEFAULT_FRAMERATE = 60

/**
 * Desktop mode's view (`view = desktop`, or Ctrl+Shift+D): the X
 * server's screen, which the server draws into our SurfaceView, below a
 * bar of our own (the view switcher, the soft keyboard, the log) and above
 * the desktop's key bar, so nothing of ours covers it. Touches go
 * through core's gestures (`desktop-touch`), keys through its keyboard.
 * While the soft keyboard is up the X screen keeps its size: the screen
 * moves up instead, to keep the pointer in sight. Until there's a
 * picture a notice says why (core's desktopNotice), with Start and the
 * logs, as we can't read logcat.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DesktopView(
    context: Context,
    private val font: Typeface,
    keyBarDir: File,
    private val host: Host,
) : LinearLayout(context) {
    /** What the view needs from the app. */
    interface Host {
        /** The X server's link, null until the app's service is there. */
        val link: DesktopScreenLink?
        /** `mynx desktop status`, null until the app's service is there. */
        fun status(): DesktopStatus?
        /** Starts the desktop; [done] gets null, or why it didn't start. */
        fun start(done: (String?) -> Unit)
        /** The link's log and the ends of the X server's and the session's logs. */
        fun logs(): String
        /** The `desktop-touch` setting. */
        fun touch(): DesktopTouch
        /** The `view` setting: the switcher marks it. */
        fun view(): AppView
        /** The switcher's [view] was picked (a tap, or Ctrl+Shift+1, 2, 3). */
        fun switchTo(view: AppView)
        /** A tab that installs what the desktop needs (`mynx desktop install`), shown. */
        fun install()
    }

    private val scale = desktopScale(resources.displayMetrics.density)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat() / scale
    private var touchMode = DesktopTouch.TOUCHPAD
    private var gestures: DesktopGestures = desktopGestures(touchMode, slop)
    private val keys = DesktopKeyboard()
    private var shownLatches: Set<KeyStroke> = emptySet()
    private var geometry: DesktopGeometry? = null
    // A surface made since the last size: the server must draw it whole.
    private var fresh = false
    private var pan = 0
    private var starting = false
    private var error: String? = null
    private var mouseButtons = 0

    private val barTarget = object : KeyBarTarget {
        override var onLatchChange: (() -> Unit)? = null
        override fun press(button: KeyButton) = send(keys.bar(button))
        override fun latched(button: KeyButton) = button.strokes.size == 1 && button.strokes[0] in keys.latched
        // The sticky keys are the desktop keyboard's, not the bar's.
        override fun reset() {}
    }

    private val statusText = barText("desktop").apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    // The view switcher: the `view` setting's view marked.
    private val switcher = VIEW_SWITCHER.map { (view, label) ->
        view to barText(label).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { host.switchTo(view) }
        }
    }
    private val keyboardButton = barText("⌨").apply { setOnClickListener { toggleKeyboard() } }
    private val logButton = barText("Log").apply { setOnClickListener { toggleLog() } }
    private val topBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((_, button) in switcher) addView(button)
        addView(statusText, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(keyboardButton)
        addView(logButton)
    }

    private val surfaceView = SurfaceView(context)
    // Scaled and moved for direct mode's zoom (the X server's screen stays as it is).
    private val zoomed = FrameLayout(context).apply {
        pivotX = 0f
        pivotY = 0f
        addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    private val pointer = PointerView(context)
    private val layer = FrameLayout(context).apply {
        addView(zoomed, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(pointer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private val noticeText = TextView(context).apply {
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
    }
    private val startButton = barText("Start").apply {
        gravity = Gravity.CENTER
        setOnClickListener { start() }
    }
    private val installButton = barText("Install").apply {
        gravity = Gravity.CENTER
        setOnClickListener { host.install() }
    }
    private val noticeLog = logView()
    private val notice = ScrollView(context).apply {
        isFillViewport = true
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(noticeText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(startButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
            addView(installButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
            addView(noticeLog, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        })
    }
    private val logText = logView()
    private val logPanel = ScrollView(context).apply {
        visibility = GONE
        addView(logText)
    }
    private val frame = ScreenFrame(context).apply {
        addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(notice, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(logPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    val keyBar = KeyBarView(context, barTarget, font, keyBarDir)

    var colors: StripColors? = null
        set(value) {
            field = value ?: return
            topBar.setBackgroundColor(value.background)
            for (b in listOf(keyboardButton, logButton)) b.setTextColor(value.accent)
            markView()
            statusText.setTextColor(value.text)
            for (b in listOf(startButton, installButton)) {
                b.setTextColor(value.accent)
                b.setBackgroundColor(value.selectedBackground)
            }
            notice.setBackgroundColor(value.background)
            noticeText.setTextColor(value.text)
            noticeLog.setTextColor(value.text)
            logPanel.setBackgroundColor((value.background and 0xFFFFFF) or (0xE6 shl 24))
            logText.setTextColor(value.text)
            keyBar.colors = value
        }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, POLL_MS)
        }
    }

    private val longPress = Runnable {
        val out = gestures.timeout(SystemClock.uptimeMillis())
        if (out.isNotEmpty() || gestures.holding) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pointerEvents(out)
        }
    }

    // Direct mode's fling: a step each frame until it stops.
    private val flingFrame = object : Runnable {
        override fun run() {
            pointerEvents(gestures.fling(SystemClock.uptimeMillis()))
            if (gestures.flinging) postOnAnimation(this)
        }
    }

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        addView(topBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(frame, LayoutParams(MATCH_PARENT, 0, 1f))
        addView(keyBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                fresh = true
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val g = DesktopGeometry(width, height, scale)
                geometry = g
                gestures.screen(g.width, g.height)
                host.link?.surface(holder.surface, g.width, g.height, framerate(), fresh)
                fresh = false
                pointerMoved()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                host.link?.surfaceGone()
            }
        })
        layer.setOnTouchListener { _, e -> touch(e) }
        layer.setOnGenericMotionListener { _, e -> e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && mouse(e) }
    }

    /** The view came on screen: picks up setting and bar changes, and starts the desktop if nothing runs. */
    fun shown() {
        val touch = host.touch()
        if (touch != touchMode) {
            send(gestures.cancel())
            val x = gestures.pointerX
            val y = gestures.pointerY
            touchMode = touch
            gestures = desktopGestures(touch, slop)
            geometry?.let { gestures.screen(it.width, it.height) }
            gestures.moveTo(x, y)
            pointerMoved()
        }
        keyBar.refresh(null, force = true, fallback = DESKTOP_KEY_BAR)
        requestFocus()
        if (desktopStartsOnOpen(host.link?.connected == true, host.status())) start()
        removeCallbacks(poll)
        post(poll)
    }

    /** The view is going: let go of every key and button the X server still has down. */
    fun hidden() {
        removeCallbacks(poll)
        removeCallbacks(longPress)
        removeCallbacks(flingFrame)
        send(keys.releaseAll() + gestures.cancel())
        hideKeyboard()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(poll)
        removeCallbacks(longPress)
        removeCallbacks(flingFrame)
        super.onDetachedFromWindow()
    }

    /** Marks the `view` setting's view on the switcher. */
    fun markView() {
        val c = colors ?: return
        val current = host.view()
        for ((view, button) in switcher) {
            val on = view == current
            button.setTextColor(if (on) c.text else c.accent)
            button.setBackgroundColor(if (on) c.selectedBackground else Color.TRANSPARENT)
        }
    }

    /** Shows what's going on: the screen, or a notice saying why there's none. */
    fun refresh() {
        markView()
        val connected = host.link?.connected == true
        val status = host.status()
        val shown = desktopNotice(connected, status, starting, error)
        val session = status?.session?.word ?: "desktop"
        statusText.text = when {
            !connected -> "desktop"
            status?.sessionRunning == false -> "$DESKTOP_DISPLAY · no $session session · ${touchMode.word}"
            else -> "$DESKTOP_DISPLAY · $session · ${touchMode.word}"
        }
        pointer.visibility = if (connected) VISIBLE else GONE
        if (shown == null) {
            notice.visibility = GONE
        } else {
            notice.visibility = VISIBLE
            noticeText.text = shown.text
            startButton.visibility = if (shown.canStart) VISIBLE else GONE
            installButton.visibility = if (shown.canInstall) VISIBLE else GONE
            noticeLog.visibility = if (shown.showLog) VISIBLE else GONE
            if (shown.showLog) noticeLog.text = host.logs()
        }
        if (logPanel.visibility == VISIBLE) logText.text = host.logs()
    }

    private fun start() {
        if (starting) return
        starting = true
        error = null
        refresh()
        host.start { failure ->
            starting = false
            error = failure
            refresh()
        }
    }

    /** The Log button: the logs over the screen, or not. */
    fun toggleLog() {
        logPanel.visibility = if (logPanel.visibility == VISIBLE) GONE else VISIBLE
        refresh()
        if (logPanel.visibility == VISIBLE) logPanel.post { logPanel.fullScroll(FOCUS_DOWN) }
    }

    private fun imeShown(): Boolean =
        Build.VERSION.SDK_INT >= 30 && rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    private fun toggleKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (imeShown()) imm.hideSoftInputFromWindow(windowToken, 0) else imm.showSoftInput(this, 0)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun framerate(): Int = display?.refreshRate?.roundToInt()?.takeIf { it > 0 } ?: DEFAULT_FRAMERATE

    private fun send(events: List<X11Input>) {
        if (events.isNotEmpty()) host.link?.send(events)
        val latches = keys.latched
        if (latches != shownLatches) {
            shownLatches = latches
            barTarget.onLatchChange?.invoke()
        }
    }

    private fun pointerEvents(events: List<X11Input>) {
        send(keys.pointer(events))
        pointerMoved()
    }

    // Touches on the screen, in its own pixels (it moves with the pan): core's gestures say what they do.
    private fun touch(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouse(e)
        val g = geometry ?: return true
        val i = e.actionIndex
        val out = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                gestures.down(e.getPointerId(i), g.toX(e.getX(i)), g.toY(e.getY(i)), e.eventTime)
            MotionEvent.ACTION_MOVE -> gestures.move(
                (0 until e.pointerCount).map { Finger(e.getPointerId(it), g.toX(e.getX(it)), g.toY(e.getY(it))) },
                e.eventTime,
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> gestures.up(e.getPointerId(i), e.eventTime)
            MotionEvent.ACTION_CANCEL -> gestures.cancel()
            else -> emptyList()
        }
        pointerEvents(out)
        removeCallbacks(longPress)
        gestures.timeoutAt?.let { postDelayed(longPress, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
        removeCallbacks(flingFrame)
        if (gestures.flinging) postOnAnimation(flingFrame)
        return true
    }

    // A mouse (USB or Bluetooth) points where it is, its buttons are X's.
    private fun mouse(e: MotionEvent): Boolean {
        val g = geometry ?: return true
        val out = mutableListOf<X11Input>()
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            out += X11Input.Scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), -e.getAxisValue(MotionEvent.AXIS_VSCROLL))
        } else {
            out += gestures.moveTo(gestures.zoom.screenX(g.toX(e.x)), gestures.zoom.screenY(g.toY(e.y)))
        }
        val buttons = e.buttonState
        for ((android, x) in MOUSE_BUTTONS) {
            val now = buttons and android != 0
            if (now != (mouseButtons and android != 0)) out += X11Input.Button(x, now)
        }
        mouseButtons = buttons
        pointerEvents(out)
        return true
    }

    private fun pointerMoved() {
        val g = geometry ?: return
        val zoom = gestures.zoom
        zoomed.scaleX = zoom.scale
        zoomed.scaleY = zoom.scale
        zoomed.translationX = g.fromX(zoom.x)
        zoomed.translationY = g.fromY(zoom.y)
        pointer.at(g.fromX(zoom.viewX(gestures.pointerX)), g.fromY(zoom.viewY(gestures.pointerY)))
        // A finger on the screen is where the pointer is: moving the screen under it would chase it.
        if (touchMode == DesktopTouch.TOUCHPAD) updatePan()
    }

    private fun updatePan() {
        val g = geometry
        val pointerY = if (g == null) 0f else g.fromY(gestures.zoom.viewY(gestures.pointerY))
        pan = keyboardPan(layer.height, frame.height, pointerY, pan, dp(PAN_MARGIN_DP))
        layer.translationY = -pan.toFloat()
    }

    /** A key event while the desktop shows; false when it isn't the desktop's (Back, volume). */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters ?: return false
            send(keys.typed(chars))
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        val out = keys.key(event.keyCode, event.scanCode, event.unicodeChar, down, event.repeatCount) ?: return false
        send(out)
        return true
    }

    /** The phone's volume keys, if the desktop's bar sets them. */
    fun onVolumeKey(event: KeyEvent) = keyBar.onVolumeKey(event)

    // The soft keyboard types into the desktop: committed text is typed, deleting is Backspace.
    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                send(keys.typed(text))
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                send(keys.deleted(beforeLength))
                return true
            }
        }
    }

    private fun barText(text: String) = TextView(context).apply {
        this.text = text
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isFocusable = false
    }

    private fun logView() = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextIsSelectable(true)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    /**
     * The screen's frame: the X screen keeps the height it had without the
     * soft keyboard (so the server's screen isn't resized), the part the
     * keyboard covers hidden below the key bar, and moved up by the pan.
     */
    private inner class ScreenFrame(context: Context) : FrameLayout(context) {
        private var fullWidth = -1
        private var fullHeight = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = measuredWidth
            val h = measuredHeight
            if (!imeShown() || w != fullWidth) {
                fullWidth = w
                fullHeight = h
            }
            layer.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(fullHeight, h), MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // Before the first surface (and in screenshots): the pointer where it will be.
            if (geometry == null && layer.width > 0 && layer.height > 0) {
                val g = DesktopGeometry(layer.width, layer.height, scale)
                gestures.screen(g.width, g.height)
                pointer.at(g.fromX(gestures.pointerX), g.fromY(gestures.pointerY))
            }
            updatePan()
        }
    }

    /** Our own pointer: the X server's cursor needs its shared state, which this side doesn't map. */
    private class PointerView(context: Context) : View(context) {
        private var px = -1f
        private var py = -1f
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Drawn in dp (the canvas is scaled), so the outline is 1.2 dp.
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            strokeJoin = Paint.Join.ROUND
        }
        // The usual arrow, its tip at 0, 0, in dp.
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4.5f, 13f); lineTo(7.5f, 20f)
            lineTo(10.5f, 18.8f); lineTo(7.5f, 12f); lineTo(13f, 12f); close()
        }

        init {
            isClickable = false
            isFocusable = false
        }

        fun at(x: Float, y: Float) {
            px = x
            py = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (px < 0) return
            canvas.save()
            canvas.translate(px, py)
            canvas.scale(density, density)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
            canvas.restore()
        }
    }

    private companion object {
        val MOUSE_BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to 1, MotionEvent.BUTTON_TERTIARY to 2, MotionEvent.BUTTON_SECONDARY to 3,
        )
    }
}
