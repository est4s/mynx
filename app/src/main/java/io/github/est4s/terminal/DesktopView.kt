package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
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
import io.github.est4s.terminal.core.DESKTOP_DISPLAY
import io.github.est4s.terminal.core.DesktopGeometry
import io.github.est4s.terminal.core.DesktopGestures
import io.github.est4s.terminal.core.DesktopKeyboard
import io.github.est4s.terminal.core.DesktopPointer
import io.github.est4s.terminal.core.DesktopMenu
import io.github.est4s.terminal.core.DesktopMenuCommand
import io.github.est4s.terminal.core.DesktopMenuItem
import io.github.est4s.terminal.core.DesktopMenuSection
import io.github.est4s.terminal.core.DesktopMenuState
import io.github.est4s.terminal.core.DesktopStatus
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.Finger
import io.github.est4s.terminal.core.KeyButton
import io.github.est4s.terminal.core.KeyPress
import io.github.est4s.terminal.core.KeyStroke
import io.github.est4s.terminal.core.PixelRect
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.desktopBackLabel
import io.github.est4s.terminal.core.desktopGestures
import io.github.est4s.terminal.core.desktopMenuKey
import io.github.est4s.terminal.core.desktopMenuShortcut
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
 * bar of our own (back, the soft keyboard, the ⋮ menu: core's DesktopMenu) and above
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
        /** Back to the screen the desktop was opened over; the desktop keeps running. */
        fun back()
        /** The back button's label: where [back] goes ([desktopBackLabel]). */
        fun backLabel(): String
        /** Stops the desktop and the X server (`mynx desktop stop`), then goes [back]. */
        fun stop()
        /** Stops the desktop and starts it again (`mynx desktop stop`, then `start`); [done] as [start]'s. */
        fun restart(done: (String?) -> Unit)
        /** Saves [touch] as the `desktop-touch` setting (as `mynx set` does). */
        fun setTouch(touch: DesktopTouch)
        /** A tab that installs what the desktop needs (`mynx desktop install`), shown. */
        fun install()
        /** The other phone (the extended screen) when the desktop can go there or is there: its name, and whether it's there. */
        fun screen(): Pair<String, Boolean>? = null
        /** Sends the desktop to the other phone, or takes it back; null, or why not. */
        fun screenToggle(): String? = null
    }

    private val scale = desktopScale(resources.displayMetrics.density)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat() / scale
    private var touchMode = DesktopTouch.TOUCHPAD
    private var gestures: DesktopGestures = desktopGestures(touchMode, slop, host.link?.pointer ?: DesktopPointer())
    private val keys = DesktopKeyboard()
    private var shownLatches: Set<KeyStroke> = emptySet()
    private var geometry: DesktopGeometry? = null
    // A surface made since the last size: the server must draw it whole.
    private var fresh = false
    private var pan = 0
    private var starting = false
    private var error: String? = null
    private var mouseButtons = 0
    private var wasConnected = false
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    // Android's clipboard changed while the desktop shows (another app over it, or ours).
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener { sendClipboard() }

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
    private val backButton = barText(desktopBackLabel(fromTouch = false)).apply { setOnClickListener { host.back() } }
    private val keyboardButton = barText("⌨").apply { setOnClickListener { toggleKeyboard() } }
    // The extended screen: the desktop to the other phone (its part of the X screen) and back.
    private val screenButton = barText("").apply {
        visibility = GONE
        setOnClickListener {
            host.screenToggle()?.let { why -> statusText.text = why }
            refresh()
        }
    }
    private val menuButton = barText("⋮").apply {
        contentDescription = "Desktop menu"
        setOnClickListener { toggleMenu(byKeyboard = false) }
    }
    private val topBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(backButton)
        addView(statusText, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(screenButton)
        addView(keyboardButton)
        addView(menuButton)
    }

    private val surfaceView = SurfaceView(context)
    private val pointer = PointerArrow(context)
    private val layer = FrameLayout(context).apply {
        addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
    private val copyButton = barText("Copy").apply {
        gravity = Gravity.CENTER
        setOnClickListener {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("mynx desktop log", logText.text.toString()))
        }
    }
    private val logPanel = ScrollView(context).apply {
        visibility = GONE
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(copyButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.END
                setMargins(dp(8), dp(8), dp(8), 0)
            })
            addView(logText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    // The ⋮ menu: core's DesktopMenu says what's in it, the rows are pixelMenu's, dropped from ⋮.
    private val menu = DesktopMenu()
    private var shownMenu: Pair<List<DesktopMenuSection>, Int>? = null
    // Keys whose press the menu took (Ctrl+Shift+M's M too): their release isn't the X server's either.
    private val menuKeysDown = HashSet<Int>()
    private val menuBox = pixelMenuBox(context).apply { isClickable = true }
    private val menuLayer = FrameLayout(context).apply {
        visibility = GONE
        // A tap beside the menu closes it, and doesn't reach the desktop.
        isClickable = true
        setOnClickListener { closeMenu() }
        addView(menuBox, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
    }
    private val menuLapse = Runnable { renderMenu() }

    private val frame = ScreenFrame(context).apply {
        addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(notice, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(logPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(menuLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    val keyBar = KeyBarView(context, barTarget, font, keyBarDir)

    var colors: StripColors? = null
        set(value) {
            field = value ?: return
            topBar.setBackgroundColor(value.background)
            for (b in listOf(backButton, screenButton, keyboardButton, menuButton)) b.setTextColor(value.accent)
            statusText.setTextColor(value.text)
            for (b in listOf(startButton, installButton, copyButton)) {
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
        if (out.isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pointerEvents(out)
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
                host.link?.surface(holder.surface, g.width, g.height, framerate(), fresh)
                followMonitor(g)
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
        useTouch(host.touch())
        host.link?.watch { post { followMonitor(geometry); pointerMoved() } }
        closeMenu()
        keyBar.refresh(null, force = true, fallback = DESKTOP_KEY_BAR)
        backButton.text = host.backLabel()
        requestFocus()
        clipboard.removePrimaryClipChangedListener(clipChanged)
        clipboard.addPrimaryClipChangedListener(clipChanged)
        if (hasWindowFocus()) sendClipboard()
        if (desktopStartsOnOpen(host.link?.connected == true, host.status())) start()
        removeCallbacks(poll)
        post(poll)
    }

    /** Touches work as [touch] from now on (the menu's Touch, the `desktop-touch` setting); the pointer stays. */
    fun useTouch(touch: DesktopTouch) {
        // The link's pointer is shared with the other phone's gestures; a view made before the service has its own.
        val shared = host.link?.pointer
        if (touch == touchMode && (shared == null || shared === gestures.pointer)) return
        send(gestures.cancel())
        removeCallbacks(longPress)
        val x = gestures.pointerX
        val y = gestures.pointerY
        val old = gestures.pointer
        touchMode = touch
        gestures = desktopGestures(touch, slop, shared ?: old)
        geometry?.let(::followMonitor)
        if (shared == null || shared === old) gestures.pointer.place(x, y, listOf(gestures.monitor))
        refresh()
    }

    /** The view is going: let go of every key and button the X server still has down. */
    fun hidden() {
        host.link?.watch(null)
        closeMenu()
        removeCallbacks(poll)
        removeCallbacks(longPress)
        clipboard.removePrimaryClipChangedListener(clipChanged)
        send(keys.releaseAll() + gestures.cancel())
        hideKeyboard()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(menuLapse)
        removeCallbacks(poll)
        removeCallbacks(longPress)
        clipboard.removePrimaryClipChangedListener(clipChanged)
        super.onDetachedFromWindow()
    }

    // Android 10+ lets an app read the clipboard only while it has the focus: back to the app is when.
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && visibility == VISIBLE) sendClipboard()
    }

    /** Android's clipboard to X's, if it's text X doesn't have (core's DesktopClipboard decides). */
    private fun sendClipboard() {
        val link = host.link ?: return
        if (!link.connected) return
        link.androidClipboard(clipText())
    }

    private fun clipText(): String? = runCatching {
        val description = clipboard.primaryClipDescription ?: return null
        val text = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (!text) return null
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }.getOrNull()

    /** Shows what's going on: the screen, or a notice saying why there's none. */
    fun refresh() {
        val connected = host.link?.connected == true
        // A new connection's server has nothing of Android's clipboard yet.
        if (connected && !wasConnected && visibility == VISIBLE && hasWindowFocus()) sendClipboard()
        wasConnected = connected
        val status = host.status()
        val shown = desktopNotice(connected, status, starting, error)
        val session = status?.session?.word ?: "desktop"
        statusText.text = when {
            !connected -> "desktop"
            status?.sessionRunning == false -> "$DESKTOP_DISPLAY · no $session session · ${touchMode.word}"
            else -> "$DESKTOP_DISPLAY · $session · ${touchMode.word}"
        }
        pointer.visibility = if (connected) VISIBLE else GONE
        val other = host.screen()
        screenButton.visibility = if (other == null) GONE else VISIBLE
        if (other != null) screenButton.text = if (other.second) "Take back" else "→ ${other.first}"
        followMonitor(geometry)
        if (shown == null) {
            notice.visibility = GONE
        } else {
            notice.visibility = VISIBLE
            noticeText.text = shown.text
            startButton.visibility = if (shown.canStart) VISIBLE else GONE
            installButton.visibility = if (shown.canInstall) VISIBLE else GONE
            noticeLog.visibility = if (shown.showLog) VISIBLE else GONE
            if (shown.showLog) host.logs().let { if (noticeLog.text.toString() != it) noticeLog.text = it }
        }
        if (logPanel.visibility == VISIBLE) showLogs(host.logs())
        renderMenu()
    }

    // Restart stops it first (`mynx desktop stop`, then `start`); the notice says Starting meanwhile.
    private fun start(restart: Boolean = false) {
        if (starting) return
        starting = true
        error = null
        refresh()
        val done: (String?) -> Unit = { failure ->
            starting = false
            error = failure
            refresh()
        }
        if (restart) host.restart(done) else host.start(done)
    }

    private fun menuState() = DesktopMenuState(
        touch = touchMode,
        running = host.link?.connected == true || host.status()?.running == true,
        logShown = logPanel.visibility == VISIBLE,
    )

    /** The ⋮ menu, opened or closed (⋮, Ctrl+Shift+M); [byKeyboard]: its first row selected. */
    fun toggleMenu(byKeyboard: Boolean) {
        if (menu.isOpen) {
            closeMenu()
            return
        }
        // Keys held for the shortcut (Ctrl, Shift) went to the X server: it lets go of them now.
        send(keys.releaseAll())
        menu.open(byKeyboard)
        renderMenu()
    }

    private fun closeMenu() {
        menu.close()
        renderMenu()
    }

    /** Back while the desktop shows: closes the menu if it's open (true), else it's the app's. */
    fun back(): Boolean {
        if (!menu.isOpen) return false
        closeMenu()
        return true
    }

    /** Draws the menu's rows again if they changed (refreshed every second, so a press isn't redrawn under a finger). */
    private fun renderMenu() {
        removeCallbacks(menuLapse)
        if (!menu.isOpen) {
            shownMenu = null
            menuBox.removeAllViews()
            menuLayer.visibility = GONE
            return
        }
        val now = SystemClock.uptimeMillis()
        val sections = menu.sections(menuState(), now)
        menu.confirmDeadline?.let { postDelayed(menuLapse, (it - now).coerceAtLeast(0)) }
        menuLayer.visibility = VISIBLE
        val next = sections to menu.selected
        if (next == shownMenu) return
        shownMenu = next
        menuBox.removeAllViews()
        var index = 0
        for (section in sections) {
            section.title?.let { title ->
                menuBox.addView(sectionLabel(context, title).apply { setPadding(dp(16), dp(12), dp(16), dp(4)) })
            }
            for (item in section.items) {
                menuBox.addView(menuRow(item, index == menu.selected), LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                index++
            }
        }
    }

    // A row as pixelMenu's; a choice row (Touch) with its choices as the text viewer's View | Edit.
    private fun menuRow(item: DesktopMenuItem, selected: Boolean): View {
        if (item.choices.isNotEmpty()) return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, 0, dp(8), 0)
            if (selected) setBackgroundColor(Pixel.LINE)
            addView(pixelMenuRow(context, item.label).apply {
                isFocusable = false
                background = null
            }, LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pixelSegments(context).apply {
                item.choices.forEachIndexed { i, label ->
                    val segment = pixelSegment(context, label) { act(menu.tap(item.action, i, SystemClock.uptimeMillis())) }
                    stylePixelSegment(segment, i == item.chosen)
                    addView(segment, LayoutParams(WRAP_CONTENT, dp(36)))
                }
            }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        return pixelMenuRow(context, item.label).apply {
            when {
                // Pink as pixelDialog's danger button (it can't be undone), its text the background's as on Chat's pink tiles.
                item.confirming -> {
                    typeface = Pixel.label(context, 600)
                    setTextColor(Pixel.BACKGROUND)
                    background = Pixel.box(context, fill = Pixel.PINK, border = 0)
                }
                selected -> setBackgroundColor(Pixel.LINE)
            }
            setOnClickListener { act(menu.tap(item.action, now = SystemClock.uptimeMillis())) }
        }
    }

    private fun act(command: DesktopMenuCommand?) {
        when (command) {
            null -> Unit
            is DesktopMenuCommand.Touch -> {
                host.setTouch(command.touch)
                useTouch(command.touch)
            }
            DesktopMenuCommand.ToggleLog -> toggleLog()
            DesktopMenuCommand.Restart -> start(restart = true)
            DesktopMenuCommand.ShutDown -> host.stop()
        }
        renderMenu()
    }

    /**
     * Ctrl+Shift+M, and every key while the menu is open (arrows, Tab, Enter,
     * Esc or Back; the others do nothing): true when the menu took [event].
     */
    private fun menuKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && menuKeysDown.remove(code)) return true
        if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_M &&
            desktopMenuShortcut(KeyPress("M", ctrl = event.isCtrlPressed, shift = event.isShiftPressed, alt = event.isAltPressed))
        ) {
            menuKeysDown += code
            if (event.repeatCount == 0) toggleMenu(byKeyboard = true)
            return true
        }
        if (!menu.isOpen) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            menuKeysDown += code
            desktopMenuKey(code, event.isShiftPressed)?.let { act(menu.key(it, menuState(), SystemClock.uptimeMillis())) }
        }
        return true
    }

    /**
     * The logs, refreshed every second: setting the same text again would undo
     * a selection and scroll to the top, so only new text is set, and the panel
     * keeps its place unless it was at the bottom (then it follows new lines).
     */
    private fun showLogs(logs: String) {
        if (logText.text.toString() == logs) return
        val child = logPanel.getChildAt(0)
        val atBottom = child == null || logPanel.scrollY + logPanel.height >= child.height - dp(8)
        val y = logPanel.scrollY
        logText.text = logs
        logPanel.post { if (atBottom) logPanel.fullScroll(FOCUS_DOWN) else logPanel.scrollTo(0, y) }
    }

    /** The menu's Log: the logs over the screen, or not. */
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
        return true
    }

    // A mouse (USB or Bluetooth) points where it is, its buttons are X's.
    private fun mouse(e: MotionEvent): Boolean {
        val g = geometry ?: return true
        val out = mutableListOf<X11Input>()
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            out += X11Input.Scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), -e.getAxisValue(MotionEvent.AXIS_VSCROLL))
        } else {
            out += gestures.moveTo(g.toX(e.x), g.toY(e.y))
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

    // This phone's part of the X screen (all of it, unless the other phone has a part): the gestures' monitor.
    private fun followMonitor(g: DesktopGeometry?) {
        g ?: return
        val rect = host.link?.hostMonitor ?: PixelRect(0, 0, g.width, g.height)
        if (rect != gestures.monitor) gestures.monitor(rect)
    }

    // The pointer is drawn where it is on this phone's part, or not at all while it's on the other phone's.
    private fun pointerMoved() {
        val g = geometry ?: return
        val m = gestures.monitor
        val x = gestures.pointerX - m.x
        val y = gestures.pointerY - m.y
        if (x < 0 || y < 0 || x >= m.width || y >= m.height) {
            pointer.away()
            return
        }
        pointer.at(g.fromX(x), g.fromY(y))
        // A finger on the screen is where the pointer is: moving the screen under it would chase it.
        if (touchMode == DesktopTouch.TOUCHPAD) updatePan()
    }

    private fun updatePan() {
        val g = geometry
        val pointerY = if (g == null) 0f else g.fromY(gestures.pointerY - gestures.monitor.y)
        pan = keyboardPan(layer.height, frame.height, pointerY, pan, dp(PAN_MARGIN_DP))
        layer.translationY = -pan.toFloat()
    }

    /** A key event while the desktop shows; false when it isn't the desktop's (Back, volume). */
    fun onKey(event: KeyEvent): Boolean {
        if (menuKey(event)) return true
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
                pointer.at(g.fromX(gestures.pointerX - gestures.monitor.x), g.fromY(gestures.pointerY - gestures.monitor.y))
            }
            updatePan()
        }
    }

    private companion object {
        val MOUSE_BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to 1, MotionEvent.BUTTON_TERTIARY to 2, MotionEvent.BUTTON_SECONDARY to 3,
        )
    }
}
