package io.github.est4s.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import io.github.est4s.terminal.core.ColorScheme
import io.github.est4s.terminal.core.MAX_FONT_SIZE
import io.github.est4s.terminal.core.MIN_FONT_SIZE
import io.github.est4s.terminal.core.Orientation
import io.github.est4s.terminal.core.NEON
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.Tab
import io.github.est4s.terminal.core.TabAction
import io.github.est4s.terminal.core.hostPath
import io.github.est4s.terminal.core.linkAt
import io.github.est4s.terminal.core.loadColorScheme
import io.github.est4s.terminal.core.loadSettings
import io.github.est4s.terminal.core.setSetting
import io.github.est4s.terminal.core.stripColors
import java.io.File
import java.util.Properties
import kotlin.concurrent.thread

private const val ROOTFS_ASSET = "debian-rootfs.tar.xz"
private const val FONT_ASSET = "fonts/JetBrainsMonoNerdFontMono-Regular.ttf"
// Debian path, relative to the rootfs.
private const val COLORS_FILE = "root/.config/pocket-terminal/colors.properties"
private const val KEY_BARS_DIR = "root/.config/pocket-terminal/keybars"
private const val SETTINGS_FILE = "root/.config/pocket-terminal/settings.conf"
private const val CURSOR_BLINK_MS = 500
private const val PERMISSION_REQUEST = 2
private const val RESULT_REQUEST = 3
private const val KEY_BAR_POLL_MS = 250L
private const val LINK_ROWS = 12 // how far up and down a tapped link may go on

class MainActivity : Activity() {
    private lateinit var terminalView: TerminalView
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var stripRow: LinearLayout
    private lateinit var keyBar: KeyBarView
    private var shownKeyBarProblems = emptyList<String>()
    // Output triggers a bar check, but a program that starts quietly (e.g.
    // waiting for input) prints nothing after `keybar` switched the bar.
    // So also check while visible: one stat of a tiny file.
    private val keyBarPoll = object : Runnable {
        override fun run() {
            if (::keyBar.isInitialized) refreshKeyBar()
            // In case a file event was missed: a list of a nearly empty folder.
            service?.processRequests()
            root.postDelayed(this, KEY_BAR_POLL_MS)
        }
    }
    private var service: TerminalService? = null
    private var bound = false
    private val crashFile by lazy { TerminalApp.crashFile(application) }
    // Only CI builds have the font; fall back so other builds still run.
    private val font by lazy {
        runCatching { Typeface.createFromAsset(assets, FONT_ASSET) }.getOrDefault(Typeface.MONOSPACE)
    }
    private var scheme: ColorScheme? = null
    private val strip get() = (scheme ?: NEON).stripColors()
    private var shownColorProblems = emptyList<String>()
    private var shownSettingsProblems = emptyList<String>()
    private var fontSize = 0 // dp
    private var visible = false
    /** Whether the terminal is on screen (between onStart and onStop). */
    val onScreen get() = visible
    // A tapped notification's tab, selected once the service is connected.
    private var shellToShow: Int? = null
    private var appliedFont: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as TerminalService.LocalBinder).service
            service = s
            s.activity = this@MainActivity
            applyRotation(s.rotationLock)
            showTerminal(s.currentSession())
            showShellFromNotification()
            s.toolsError?.let { showToolsError(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    /** `pocket rotation lock`: hold the screen as it is, or turned to a side; null frees it. */
    fun applyRotation(lock: Orientation?) {
        requestedOrientation = when (lock) {
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Orientation.CURRENT -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun setFontSize(size: Int) {
        if (size == fontSize) return
        fontSize = size
        terminalView.setTextSize(dp(size))
    }

    /** Pinch to zoom: one step bigger or smaller, saved as the `font-size` setting. */
    fun zoom(step: Int) {
        val size = (fontSize + step).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (size == fontSize) return
        setFontSize(size)
        val file = File(installer.rootfs, SETTINGS_FILE)
        runCatching {
            file.parentFile!!.mkdirs()
            file.writeText(setSetting(file.takeIf { it.isFile }?.readText(), "font-size", size.toString()).getOrThrow())
        }
    }

    private lateinit var root: FrameLayout
    private val installer by lazy { RootfsInstaller(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leaveOnBack()
        askForNotifications()

        root = FrameLayout(this).apply { setBackgroundColor(NEON.background) }
        setContentView(root)
        applySystemInsets(root)

        shellToShow = shellOf(intent)
        if (installer.isInstalled) connectService() else installDebian()
        showLastCrash()
    }

    // A tapped `pocket notify` notification, while the activity exists.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shellToShow = shellOf(intent)
        showShellFromNotification()
    }

    private fun shellOf(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_SHELL, -1)?.takeIf { it >= 0 }

    private fun showShellFromNotification() {
        val id = shellToShow ?: return
        val service = service ?: return
        shellToShow = null
        service.selectShell(id)
    }

    // Picks up edits to the colours file when the user comes back to the app.
    override fun onStart() {
        super.onStart()
        visible = true
        if (::terminalView.isInitialized) {
            applyColors()
            refreshKeyBar(force = true) // bar files may have been edited
            applySettings()
        }
        root.post(keyBarPoll)
        service?.cameOnScreen()
    }

    // Folders change without tab changes, and leaving the app is the last
    // chance to save before Android may kill it.
    override fun onStop() {
        root.removeCallbacks(keyBarPoll)
        visible = false
        setCursorBlinking(false)
        service?.saveTabs()
        super.onStop()
    }

    // Sessions live in the service and keep running after the activity is gone.
    override fun onDestroy() {
        permissionAsks.toList().also { permissionAsks.clear() }.forEach { it.second(false) }
        service?.let { if (it.activity === this) it.activity = null }
        service = null
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    // Started from the visible activity: Android 12+ forbids starting a
    // foreground service from the background.
    private fun connectService() {
        val intent = Intent(this, TerminalService::class.java)
        startForegroundService(intent)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    // Like Termux, Back leaves the app without closing the terminals.
    private fun leaveOnBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT) { moveTaskToBack(true) }
        }
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // Only for the "terminals running" notification; everything works without it.
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // Asked one dialog at a time, in order.
    private val permissionAsks = mutableListOf<Pair<Array<String>, (Boolean) -> Unit>>()

    /** Shows Android's dialog for [permissions]; [answer] gets whether any of them was allowed. */
    fun askPermissions(permissions: Array<String>, answer: (Boolean) -> Unit) {
        permissionAsks += permissions to answer
        if (permissionAsks.size == 1) requestPermissions(permissions, PERMISSION_REQUEST)
    }

    // One at a time: the activity started is in front until it answers.
    private var resultAnswer: ((Boolean) -> Unit)? = null

    /** Starts [intent] (the camera app); [answer] gets whether it finished OK. False if nothing could start it. */
    @Suppress("DEPRECATION") // the ActivityResult API needs AndroidX
    fun startForResult(intent: Intent, answer: (Boolean) -> Unit): Boolean {
        resultAnswer?.invoke(false)
        return try {
            startActivityForResult(intent, RESULT_REQUEST)
            resultAnswer = answer
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RESULT_REQUEST) return
        val answer = resultAnswer ?: return
        resultAnswer = null
        answer(resultCode == RESULT_OK)
    }

    /** How far the screen is turned, in degrees (Surface's ROTATION_*), for photos taken now. */
    val displayRotation: Int
        get() {
            @Suppress("DEPRECATION") // display needs API 30
            val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation else windowManager.defaultDisplay.rotation
            return when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST || permissionAsks.isEmpty()) return
        val answer = permissionAsks.removeAt(0).second
        permissionAsks.firstOrNull()?.let { requestPermissions(it.first, PERMISSION_REQUEST) }
        answer(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
    }

    private fun showTerminal(session: TerminalSession) {
        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(ViewClient(this@MainActivity))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // Before attaching: the size decides the first rows and columns.
        appliedFont = null
        fontSize = 0
        applySettings()
        stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(stripRow)
        }
        keyBar = KeyBarView(this, terminalView, font, File(installer.rootfs, KEY_BARS_DIR))
        // Before attaching: a session's emulator copies the scheme when it starts.
        applyColors()
        // TerminalView ignores its own padding, so it sits inside the inset root.
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stripScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(terminalView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            // Last, so it sits right above the keyboard (the root is padded by it).
            addView(keyBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        terminalView.attachSession(session)
        terminalView.requestFocus()
        renderStrip()
        refreshKeyBar(force = true)
    }

    // Rebuilt on every change; changes are rare (output only reports when a
    // background tab's mark flips), so this stays cheap.
    private fun renderStrip() {
        val tabs = service?.tabs ?: return
        stripRow.removeAllViews()
        tabs.tabs.forEachIndexed { i, tab ->
            stripRow.addView(tabView(tab, selected = i == tabs.selectedIndex))
        }
        stripRow.addView(stripText("+", strip.accent).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onTabAction(TabAction.New) }
        })
        keepSelectedTabVisible(tabs.selectedIndex)
    }

    private fun tabView(tab: Tab<TerminalSession>, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, 0, 0)
        val colors = strip
        if (selected) setBackgroundColor(colors.selectedBackground)
        val marks = (if (tab.bell) "🔔" else "") + (if (tab.activity) "●" else "")
        if (marks.isNotEmpty()) addView(stripText("$marks ", colors.mark))
        val color = if (selected) colors.accent else colors.text
        addView(stripText(tab.title, color).apply {
            maxWidth = dp(160)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(stripText("×", color).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { service?.closeTab(tab.session); terminalView.requestFocus() }
        })
        setOnClickListener {
            service?.tabs?.let { tabs -> tabs.select(tabs.tabs.indexOf(tab)) }
            terminalView.requestFocus()
        }
        setOnLongClickListener {
            showRename(tab.session)
            true
        }
    }

    private fun showRename(session: TerminalSession) {
        val tabs = service?.tabs ?: return
        val renamed = tabs.find(session)?.rename != null
        val input = EditText(this).apply {
            setText(tabs.renameInput(session))
            setSingleLine(true)
            selectAll()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Rename tab")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton("Rename") { _, _ -> tabs.applyRenameInput(session, input.text.toString()) }
            .setNegativeButton("Cancel", null)
        // Only offered when there's a name to drop; an empty box does the same.
        if (renamed) builder.setNeutralButton("Automatic") { _, _ -> tabs.rename(session, null) }
        val dialog = builder.create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            tabs.applyRenameInput(session, input.text.toString())
            dialog.dismiss()
            true
        }
        dialog.setOnDismissListener { terminalView.requestFocus() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    // Not focusable: tapping the strip must leave keyboard input on the terminal.
    private fun stripText(text: String, color: Int) = TextView(this).apply {
        this.text = text
        typeface = font
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(8), 0, dp(8))
        isFocusable = false
    }

    private fun keepSelectedTabVisible(index: Int) {
        stripScroll.post {
            val tab = stripRow.getChildAt(index) ?: return@post
            val left = stripScroll.scrollX
            val right = left + stripScroll.width
            when {
                tab.left < left -> stripScroll.smoothScrollTo(tab.left, 0)
                tab.right > right -> stripScroll.smoothScrollTo(tab.right - stripScroll.width, 0)
            }
        }
    }

    /**
     * Loads the colours file from Debian (neon if there's none) and, if it
     * changed, applies it to every tab. Unchanged colours are left alone, so
     * colours a program set with escape codes survive switching apps. The
     * views are always repainted: they may be new.
     */
    private fun applyColors(quiet: Boolean = false) {
        val parsed = loadColorScheme(File(installer.rootfs, COLORS_FILE))
        if (!quiet && parsed.problems.isNotEmpty() && parsed.problems != shownColorProblems) {
            AlertDialog.Builder(this)
                .setTitle("Problems in colors.properties")
                .setMessage("~/.config/pocket-terminal/colors.properties\n\n" + parsed.problems.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
        shownColorProblems = parsed.problems
        showScheme(parsed.scheme)
    }

    /** Shows colours that aren't saved (the theme editor's preview) until the next reload. */
    fun previewColors(preview: ColorScheme) {
        if (::terminalView.isInitialized) showScheme(preview)
    }

    private fun showScheme(next: ColorScheme) {
        if (next != scheme) {
            scheme = next
            applyToTerminals(next)
        }

        // Like Termux: the library never paints default-background cells, so
        // everything behind the terminal must be the scheme's background.
        window.decorView.setBackgroundColor(next.background)
        root.setBackgroundColor(next.background)
        terminalView.setBackgroundColor(next.background)
        stripScroll.setBackgroundColor(strip.background)
        keyBar.colors = strip
        renderStrip()
        terminalView.onScreenUpdated()
    }

    private fun applyToTerminals(next: ColorScheme) {
        val library = TerminalColors.COLOR_SCHEME
        library.updateWith(Properties()) // back to the library's defaults
        next.palette.forEach { (index, color) -> library.mDefaultColors[index] = color }
        library.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = next.foreground
        library.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = next.background
        val cursor = next.cursor
        if (cursor != null) {
            library.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = cursor
        } else {
            library.setCursorColorForBackground()
        }
        service?.tabs?.tabs?.forEach { it.session.emulator?.mColors?.reset() }
    }

    /** Runs a tab shortcut, from the keyboard or the strip. */
    fun onTabAction(action: TabAction) {
        val service = service ?: return
        when (action) {
            TabAction.New -> service.newSession()
            TabAction.Close -> service.tabs.selected?.let { service.closeTab(it.session) }
            TabAction.Rename -> service.tabs.selected?.let { showRename(it.session) }
            is TabAction.Navigate -> action.applyTo(service.tabs)
        }
        terminalView.requestFocus()
    }

    // First launch: unpack the Debian rootfs from the APK. An interrupted
    // install is simply redone next time (see RootfsInstaller).
    private fun installDebian() {
        val status = TextView(this).apply { text = "Setting up Debian…"; styleText() }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(status)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        thread(name = "rootfs-install") {
            try {
                val size = assets.openFd(ROOTFS_ASSET).use { it.length }
                assets.open(ROOTFS_ASSET).use { archive ->
                    installer.install(archive, size) { percent ->
                        runOnUiThread {
                            bar.progress = percent
                            status.text = "Setting up Debian… $percent%"
                        }
                    }
                }
                runOnUiThread { connectService() }
            } catch (e: Exception) {
                runOnUiThread { showInstallError(e) }
            }
        }
    }

    private fun showInstallError(e: Exception) {
        val retry = Button(this).apply {
            text = "Retry"
            setOnClickListener { installDebian() }
        }
        root.removeAllViews()
        root.addView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(retry)
                addView(TextView(this@MainActivity).apply {
                    text = "Setting up Debian failed.\n\n${e.stackTraceToString()}"
                    styleText()
                    setTextIsSelectable(true)
                })
            })
        })
        retry.requestFocus()
    }

    private fun TextView.styleText() {
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor("#FF2BD6"))
    }

    fun restartShell(old: TerminalSession) {
        service?.restart(old)
    }

    /** Shows the selected tab's session and redraws the strip. */
    fun onTabsChanged() {
        if (!::terminalView.isInitialized) return
        val session = service?.tabs?.selected?.session ?: return
        if (terminalView.currentSession !== session) terminalView.attachSession(session)
        renderStrip()
        refreshKeyBar()
    }

    fun onScreenUpdated(session: TerminalSession) {
        if (::terminalView.isInitialized && terminalView.currentSession === session) {
            terminalView.onScreenUpdated()
            // A program starting or ending prints something, so this catches bar changes.
            refreshKeyBar()
        }
    }

    /**
     * Applies the config files now: `pocket check` asked. [quiet]: `pocket`
     * reports the problems itself, so no dialogs pop up over the terminal.
     */
    fun reloadConfig(quiet: Boolean) {
        if (!::terminalView.isInitialized) return
        applyColors(quiet)
        refreshKeyBar(force = true, quiet = quiet)
        applySettings(quiet)
    }

    /** Font, font size and cursor from the settings file; bad lines keep their defaults. */
    private fun applySettings(quiet: Boolean = false) {
        val parsed = loadSettings(File(installer.rootfs, SETTINGS_FILE))
        val settings = parsed.settings
        val problems = parsed.problems.toMutableList()
        setFontSize(settings.fontSize)
        if (settings.font != appliedFont) {
            val custom = if (settings.font == "default") null else
                hostPath(settings.font, installer.rootfs.absolutePath)?.let { File(it) }?.takeIf { it.isFile }
                    ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            if (settings.font != "default" && custom == null) problems += "font: can't load ${settings.font}"
            terminalView.setTypeface(custom ?: font)
            appliedFont = settings.font
        }
        service?.setCursorStyle(settings.cursorStyle)
        terminalView.setTerminalCursorBlinkerRate(if (settings.cursorBlink) CURSOR_BLINK_MS else 0)
        setCursorBlinking(true)
        terminalView.onScreenUpdated()
        if (!quiet && problems.isNotEmpty() && problems != shownSettingsProblems) {
            AlertDialog.Builder(this)
                .setTitle("Problems in settings.conf")
                .setMessage("~/.config/pocket-terminal/settings.conf\n\n" + problems.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
        shownSettingsProblems = problems
    }

    /** Shows the bar the selected tab's program asked for; problems in its file show once. */
    private fun refreshKeyBar(force: Boolean = false, quiet: Boolean = false) {
        val session = service?.tabs?.selected?.session ?: return
        val loaded = keyBar.refresh(service?.keyBarFileOf(session), force) ?: return
        if (!quiet && loaded.problems.isNotEmpty() && loaded.problems != shownKeyBarProblems) {
            AlertDialog.Builder(this)
                .setTitle("Problems in a key bar file")
                .setMessage(loaded.source + "\n\n" + loaded.problems.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
        shownKeyBarProblems = loaded.problems
    }

    /** Starts the cursor blinking (if the settings want it) or stops it; never while hidden. */
    fun setCursorBlinking(on: Boolean) {
        if (::terminalView.isInitialized) terminalView.setTerminalCursorBlinkerState(on && visible, true)
    }

    fun takeCtrlLatch() = ::keyBar.isInitialized && keyBar.takeCtrlLatch()

    /** A tap on a link opens it; anywhere else brings up the keyboard. */
    fun onTap(e: MotionEvent) {
        val url = runCatching { linkUnder(e) }.getOrNull()
        if (url == null) {
            showKeyboard()
            return
        }
        service?.openLink(url)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    // Rows around the tap, so a link spread over several rows is found whole.
    private fun linkUnder(e: MotionEvent): String? {
        val emulator = terminalView.mEmulator ?: return null
        val (col, row) = terminalView.getColumnAndRow(e, true)
        val screen = emulator.screen
        val from = maxOf(row - LINK_ROWS, -screen.activeTranscriptRows)
        val to = minOf(row + LINK_ROWS, emulator.mRows - 1)
        if (row !in from..to) return null
        val rows = (from..to).map { screen.getSelectedText(0, it, emulator.mColumns - 1, it) }
        val wraps = (from..to).map { screen.getLineWrap(it) }
        return linkAt(rows, wraps, row - from, col, emulator.mColumns)
    }

    fun showKeyboard() {
        terminalView.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
    }

    // Since targetSdk 35 the app draws behind the system bars and adjustResize is
    // ignored, so keep the terminal clear of the bars and the keyboard ourselves.
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // The terminal works without the tools; only `pocket` and the editors are missing.
    private fun showToolsError(trace: String) {
        AlertDialog.Builder(this)
            .setTitle("Couldn't update the app's tools")
            .setMessage("pocket and the editors may be missing or old.\n\n$trace")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLastCrash() {
        if (!crashFile.exists()) return
        val trace = crashFile.readText()
        crashFile.delete()
        AlertDialog.Builder(this)
            .setTitle("The app crashed last time")
            .setMessage(trace)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
