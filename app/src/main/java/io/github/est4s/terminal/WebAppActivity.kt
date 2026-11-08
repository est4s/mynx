package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import io.github.est4s.terminal.core.AutoReload
import io.github.est4s.terminal.core.BRIDGE_PORT_MESSAGE
import io.github.est4s.terminal.core.Bridge
import io.github.est4s.terminal.core.BridgeSession
import io.github.est4s.terminal.core.Fullscreen
import io.github.est4s.terminal.core.Orientation
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.ToolsInstaller
import io.github.est4s.terminal.core.WEB_APP_DOMAIN
import io.github.est4s.terminal.core.WEB_APP_FROM_SHORTCUT
import io.github.est4s.terminal.core.WEB_APP_LOG_DIR
import io.github.est4s.terminal.core.WEB_APP_TASK_SCHEME
import io.github.est4s.terminal.core.WebApp
import io.github.est4s.terminal.core.WebAppKind
import io.github.est4s.terminal.core.WebAppLog
import io.github.est4s.terminal.core.WebAppLogLevel
import io.github.est4s.terminal.core.WebAppResponse
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.loadColorScheme
import io.github.est4s.terminal.core.stripColors
import io.github.est4s.terminal.core.webAppNameOfHost
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

private const val COLORS_FILE = "root/.config/mynx/colors.properties"
// Apps' notifications (the bridge's notify): one per app, so a new one replaces the last.
private const val APP_NOTICE_ID_BASE = 5000

/**
 * One app's screen (`mynx app open NAME`): a WebView filling the screen
 * between the system bars, with no bar of its own, like an installed PWA.
 * Recents shows its name; back goes back in the page, then closes it.
 * Started with the data URI `mynx-app://NAME`, so Android keeps one task
 * per app (`documentLaunchMode="intoExisting"`). Everything it decides
 * (which file a request gets, when to reload, the log's lines, the
 * bridge) is core's.
 *
 * The bridge: mynx.js asks for a port with a request for
 * `https://mynx.invalid/hello` once it listens; the screen answers 204
 * and posts a new message channel's port to the app's origin, if the
 * view is on it and core's [Bridge.refusal] says go. Each port is a
 * [BridgeSession]; all of a page's stay open until the next page load
 * starts or the screen finishes.
 *
 * From a home screen shortcut ([WEB_APP_FROM_SHORTCUT]) it starts and
 * binds the service itself and runs core's open step, showing progress
 * and errors as text.
 */
class WebAppActivity : Activity(), FrontActivity {
    private lateinit var name: String
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs }
    private val apps by lazy { WebApps(File(rootfs, "root"), rootfs.absolutePath, ToolsInstaller(filesDir).tools) }
    // Read by WebView's own thread (shouldInterceptRequest).
    @Volatile private var app: WebApp? = null
    @Volatile private var log: WebAppLog? = null
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private val main by lazy { Handler(Looper.getMainLooper()) }
    // Log writes leave the main thread, in order.
    private val logWriter = Executors.newSingleThreadExecutor()
    // The auto-reload poller: its own thread, as the fingerprint reads every file.
    private val pollThread = HandlerThread("mynx-app-poll").apply { start() }
    private val poller = Handler(pollThread.looper)
    // Only touched on the poll thread.
    private var autoReload: AutoReload? = null
    private var visible = false
    // Android's dialogs and the camera app's result, for the bridge's calls.
    private val asks = ActivityAsks(this)
    // mynx's own permission dialogs that are showing.
    private val questions = mutableListOf<AlertDialog>()
    // The page's bridge: every port and session offered to this page load (mynx.js keeps the first).
    private val links = mutableListOf<BridgeLink>()
    private var fullscreen = Fullscreen.NO
    private var barsHidden = false
    private var imeShown = false
    // Opened from a shortcut: the service it binds, and core's open step while it runs.
    private var service: TerminalService? = null
    private var bound = false
    private var opening: (() -> Unit)? = null
    private var openStarted = false

    private val poll = object : Runnable {
        override fun run() {
            val current = app?.takeIf { it.kind == WebAppKind.STATIC } ?: return
            val fingerprint = runCatching { apps.fingerprint(current) }.getOrNull()
            val reload = autoReload
            val next = when {
                fingerprint == null -> 1000L
                reload == null -> {
                    autoReload = AutoReload(fingerprint)
                    1000L
                }
                else -> {
                    val step = runCatching { reload.check(fingerprint) }.getOrNull()
                    if (step?.reload == true) main.post { if (visible) webView.reload() }
                    step?.nextMs ?: 1000L
                }
            }
            poller.postDelayed(this, next)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            val s = (binder as TerminalService.LocalBinder).service
            service = s
            if (!openStarted) openFromShortcut(s)
        }

        override fun onServiceDisconnected(component: ComponentName) {
            service = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = nameOf(intent) ?: run {
            finishAndRemoveTask()
            return
        }
        val fromShortcut = intent.getBooleanExtra(WEB_APP_FROM_SHORTCUT, false)
        if (fromShortcut && !RootfsInstaller(filesDir).isInstalled) {
            // Debian isn't set up yet: the terminal's screen does that first.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishAndRemoveTask()
            return
        }
        val colors = runCatching { loadColorScheme(File(rootfs, COLORS_FILE)).scheme.stripColors() }.getOrNull()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = Client()
            webChromeClient = Chrome()
        }
        status = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            colors?.let { setTextColor(it.text) }
        }
        // The root pads for the system bars, the cutout and the keyboard (as fullscreen says); the page fills the rest.
        root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        colors?.let {
            window.decorView.setBackgroundColor(it.background)
            root.setBackgroundColor(it.background)
            status.setBackgroundColor(it.background)
        }
        setContentView(root)
        applySystemInsets(root)
        handleBack()
        screens[name] = this
        if (fromShortcut) startFromShortcut() else load()
    }

    // Opened again (Android's one task per app): the same screen, the app read again.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (nameOf(intent) != name) return
        if (opening != null) return // the shortcut's open step is still at it
        val before = app?.url
        findApp()
        val now = app ?: return
        log?.let { l -> runCatching { logWriter.execute { runCatching { l.opened(now.url) } } } }
        if (now.url != before) {
            status.visibility = View.GONE
            webView.loadUrl(now.url)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        poller.post(poll)
        // The app's notification has been seen.
        if (::name.isInitialized) {
            runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(noticeId(name)) }
        }
    }

    override fun onResume() {
        super.onResume()
        FrontActivities.resumed(this)
    }

    override fun onStop() {
        visible = false
        poller.removeCallbacks(poll)
        super.onStop()
    }

    // Finishing (back at the first page, swiped from recents), not just leaving the screen.
    override fun onDestroy() {
        poller.removeCallbacks(poll)
        pollThread.quitSafely()
        opening?.invoke()
        opening = null
        closeBridge()
        questions.toList().forEach { runCatching { it.dismiss() } }
        asks.clear()
        FrontActivities.gone(this)
        if (bound) runCatching { unbindService(connection) }
        bound = false
        if (::name.isInitialized) {
            if (screens[name] === this) screens.remove(name)
            if (isFinishing && !isChangingConfigurations && screens[name] == null) {
                runCatching { closed?.invoke(name) }
            }
        }
        if (::webView.isInitialized) webView.destroy()
        logWriter.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A rotation can bring the bars back.
        if (::root.isInitialized) root.post { hideBars() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Back from a dialog, the notification shade or another app.
        if (hasFocus && ::root.isInitialized) hideBars()
    }

    // FrontActivity: Android's dialogs and the camera app, for the bridge's calls.
    override val activity: Activity get() = this
    override val onScreen: Boolean get() = visible
    override val displayRotation: Int get() = asks.displayRotation

    override fun askPermissions(permissions: Array<String>, answer: (Boolean) -> Unit) = asks.askPermissions(permissions, answer)

    override fun startForResult(intent: Intent, answer: (Boolean) -> Unit): Boolean = asks.startForResult(intent, answer)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        asks.onActivityResult(requestCode, resultCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asks.onRequestPermissionsResult(requestCode, grantResults)
    }

    /**
     * mynx's question for the bridge ("LABEL wants to use WHAT"): [answer]
     * gets true, false, or null when it closed unanswered; the handle
     * dismisses it without an answer.
     */
    fun ask(label: String, what: String, answer: (Boolean?) -> Unit): () -> Unit {
        var over = false
        fun reply(value: Boolean?) {
            if (over) return
            over = true
            answer(value)
        }
        val dialog = AlertDialog.Builder(this)
            .setMessage(getString(R.string.app_permission_question, label, what))
            .setPositiveButton(R.string.app_permission_allow) { _, _ -> reply(true) }
            .setNegativeButton(R.string.app_permission_deny) { _, _ -> reply(false) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            questions.remove(dialog)
            reply(null)
        }
        questions += dialog
        dialog.show()
        return {
            if (!over) {
                over = true
                runCatching { dialog.dismiss() }
            }
        }
    }

    /** The bridge's `rotation-lock`: this screen only; null frees it. */
    fun applyRotation(lock: Orientation?) {
        requestedOrientation = when (lock) {
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Orientation.CURRENT -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun nameOf(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == WEB_APP_TASK_SCHEME }?.host

    private fun findApp() {
        val found = runCatching { apps.find(name) }.getOrNull()
        app = found
        if (found != null) useApp(found)
    }

    private fun useApp(found: WebApp) {
        app = found
        val file = File(rootfs, "$WEB_APP_LOG_DIR/$name.log")
        runCatching { logWriter.execute { file.parentFile?.mkdirs() } }
        log = WebAppLog(file, found.origin)
        describeTask(found.label)
        applyWindowMode(found)
    }

    private fun load() {
        findApp()
        val found = app
        if (found == null) {
            describeTask(name)
            webView.loadDataWithBaseURL(null, "<p style=\"font-family:sans-serif\">There's no app named $name any more.</p>", "text/html", "utf-8", null)
            return
        }
        log?.let { l -> runCatching { logWriter.execute { runCatching { l.opened(found.url) } } } }
        webView.loadUrl(found.url)
    }

    // A shortcut: mynx may not be running, so start it (from this visible screen) and wait for it.
    private fun startFromShortcut() {
        val label = runCatching { apps.find(name)?.label }.getOrNull() ?: name
        describeTask(label)
        showStatus("Starting $label…")
        val intent = Intent(this, TerminalService::class.java)
        try {
            startForegroundService(intent)
            bound = bindService(intent, connection, BIND_AUTO_CREATE)
            if (!bound) showStatus("couldn't start mynx")
        } catch (e: RuntimeException) {
            showStatus("couldn't start mynx: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun openFromShortcut(s: TerminalService) {
        openStarted = true
        var finished = false
        val cancel = try {
            s.openApp(
                name,
                show = { found ->
                    useApp(found)
                    log?.let { l -> runCatching { logWriter.execute { runCatching { l.opened(found.url) } } } }
                    webView.loadUrl(found.url)
                    null
                },
                done = { result ->
                    finished = true
                    opening = null
                    result.fold(
                        onSuccess = { status.visibility = View.GONE },
                        onFailure = { e ->
                            val why = e.message ?: e.toString()
                            showStatus(why)
                            logEntry(WebAppLogLevel.ERROR, "couldn't open: $why", null, 0)
                        },
                    )
                },
            )
        } catch (e: Throwable) {
            showStatus("couldn't open $name: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (!finished) opening = cancel
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    /** `mynx app reload`: reads the app again, then reloads the page. */
    fun reload() {
        findApp()
        webView.reload()
    }

    @Suppress("DEPRECATION") // TaskDescription.Builder is API 33
    private fun describeTask(label: String) {
        val description = if (Build.VERSION.SDK_INT >= 33) {
            ActivityManager.TaskDescription.Builder().setLabel(label).build()
        } else {
            ActivityManager.TaskDescription(label)
        }
        setTaskDescription(description)
    }

    // Back in the page's history; at its start the screen closes.
    private fun goBack() {
        if (webView.canGoBack()) webView.goBack() else finishAndRemoveTask()
    }

    private fun handleBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        goBack()
    }

    // app.conf's screen-awake and fullscreen (yes: clear of the cutout; edge: under it too).
    private fun applyWindowMode(found: WebApp) {
        if (found.screenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (found.fullscreen == fullscreen) return
        fullscreen = found.fullscreen
        if (Build.VERSION.SDK_INT >= 28) {
            val mode = when {
                fullscreen != Fullscreen.EDGE -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = mode
            window.attributes = attributes
        }
        hideBars()
        root.requestApplyInsets()
    }

    // Hides the status and navigation bars for fullscreen (a swipe shows them for a moment); shows them otherwise.
    @Suppress("DEPRECATION") // systemUiVisibility: the way before API 30
    private fun hideBars() {
        if (fullscreen == Fullscreen.NO) {
            if (!barsHidden) return
            barsHidden = false
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                window.decorView.systemUiVisibility = 0
            }
            return
        }
        barsHidden = true
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    // As MainActivity: the app draws behind the system bars (targetSdk 35+), and the keyboard.
    // Fullscreen pads for the cutout and the keyboard only (yes), or the keyboard only (edge).
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val types = when (fullscreen) {
                    Fullscreen.NO -> WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                    Fullscreen.YES -> WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                    Fullscreen.EDGE -> WindowInsets.Type.ime()
                }
                val pad = insets.getInsets(types)
                v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
                // The keyboard brings the navigation bar with it: hide it again once the keyboard goes.
                val ime = insets.isVisible(WindowInsets.Type.ime())
                if (imeShown && !ime && fullscreen != Fullscreen.NO) v.post { hideBars() }
                imeShown = ime
            } else {
                @Suppress("DEPRECATION")
                var left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                var top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                var right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                var bottom = insets.systemWindowInsetBottom
                if (fullscreen == Fullscreen.EDGE && Build.VERSION.SDK_INT >= 28) {
                    insets.displayCutout?.let { cut ->
                        left = (left - cut.safeInsetLeft).coerceAtLeast(0)
                        top = (top - cut.safeInsetTop).coerceAtLeast(0)
                        right = (right - cut.safeInsetRight).coerceAtLeast(0)
                        bottom = (bottom - cut.safeInsetBottom).coerceAtLeast(0)
                    }
                }
                v.setPadding(left, top, right, bottom)
            }
            insets
        }
    }

    private fun logEntry(level: WebAppLogLevel, message: String, source: String?, line: Int) {
        val l = log ?: return
        runCatching { logWriter.execute { runCatching { l.entry(level, message, source, line) } } }
    }

    // THE BRIDGE

    /** One page load's port and session; [send] may come on any thread. */
    private inner class BridgeLink(val port: WebMessagePort) {
        @Volatile var closed = false
        var session: BridgeSession? = null

        fun send(text: String) {
            main.post {
                if (!closed) runCatching { port.postMessage(WebMessage(text)) }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { session?.close() }
            runCatching { port.close() }
        }
    }

    // The page went (the next load, a reload, finishing): every session of it ends.
    private fun closeBridge() {
        val current = links.toList()
        links.clear()
        current.forEach { it.close() }
    }

    // A new channel for a hello, posted to the app's origin only (Android delivers it only there).
    // Earlier ones of the same page stay open: each script that asked has its own.
    private fun offerBridge() {
        if (isDestroyed) return
        val bridge = Companion.bridge ?: return
        val current = app ?: return
        val origin = originOf(webView.url) ?: return
        val refusal = runCatching { bridge.refusal(current, origin) }.getOrElse { it.message ?: it.javaClass.simpleName }
        if (refusal != null) return
        val ports = webView.createWebMessageChannel()
        val mine = ports[0]
        val theirs = ports[1]
        val next = BridgeLink(mine)
        val session = try {
            bridge.session(current) { text -> next.send(text) }
        } catch (e: Throwable) {
            runCatching { mine.close() }
            runCatching { theirs.close() }
            logEntry(WebAppLogLevel.ERROR, "the bridge failed: ${e.stackTraceToString()}", "bridge", 0)
            return
        }
        next.session = session
        links += next
        mine.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort?, message: WebMessage?) {
                val text = message?.data ?: return
                if (next.closed) return
                // The second fence: the origin the view is on now.
                val now = originOf(webView.url) ?: ""
                try {
                    session.receive(text, now)
                } catch (e: Exception) {
                    logEntry(WebAppLogLevel.ERROR, "the bridge failed: ${e.stackTraceToString()}", "bridge", 0)
                }
            }
        }, main)
        try {
            webView.postWebMessage(WebMessage(BRIDGE_PORT_MESSAGE, arrayOf(theirs)), Uri.parse(current.origin))
        } catch (e: RuntimeException) {
            links.remove(next)
            next.close()
            logEntry(WebAppLogLevel.ERROR, "the bridge's port couldn't be posted: ${e.message}", "bridge", 0)
        }
    }

    private inner class Client : WebViewClient() {
        // WebView's own thread: core only reads files.
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val url = req.url ?: return null
            if (url.scheme != "https") return null
            val host = url.host ?: return null
            return try {
                // mynx.js, for any page: alone it has no power (the bridge's port does).
                if (host.equals(WEB_APP_DOMAIN, ignoreCase = true)) {
                    // mynx.js listens and asks for its port.
                    if (url.encodedPath == "/hello" && url.encodedQuery == null) {
                        main.post { offerBridge() }
                        return WebResourceResponse(
                            "text/plain", null, 204, "No Content", mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return response(apps.bridgeScript(req.method ?: "GET", url.encodedPath ?: "/"))
                }
                val target = webAppNameOfHost(host) ?: return null
                // Usually the screen's own app; another app's host is answered from its own folder.
                val served = (app?.takeIf { it.name == target } ?: apps.find(target))
                    ?.takeIf { it.kind == WebAppKind.STATIC }
                    ?: return plain(404, "Not Found", "no app named $target")
                response(apps.respond(served, req.method ?: "GET", url.encodedPath ?: "/"))
            } catch (e: Throwable) {
                logEntry(WebAppLogLevel.ERROR, "the app couldn't answer: ${e.stackTraceToString()}", url.toString(), 0)
                plain(500, "Internal Server Error", e.toString())
            }
        }

        // A new page load: the last one's bridge ends (navigation, reload, auto-reload).
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            closeBridge()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            logEntry(WebAppLogLevel.ERROR, "load failed: ${error?.description}", request?.url?.toString(), 0)
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            val l = log ?: return
            val status = errorResponse?.statusCode
            val url = request?.url?.toString()
            runCatching { logWriter.execute { runCatching { l.httpError(status, url) } } }
        }
    }

    private fun response(answer: WebAppResponse): WebResourceResponse {
        val body = answer.file?.let { FileInputStream(it) }
            ?: ByteArrayInputStream((answer.body ?: "").toByteArray(Charsets.UTF_8))
        return WebResourceResponse(answer.mime, answer.encoding, answer.status, answer.reason, answer.headers, body)
    }

    private fun plain(status: Int, reason: String, text: String) = WebResourceResponse(
        "text/plain", "utf-8", status, reason, mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
    )

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
            if (message == null) return false
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> WebAppLogLevel.ERROR
                ConsoleMessage.MessageLevel.WARNING -> WebAppLogLevel.WARN
                ConsoleMessage.MessageLevel.LOG -> WebAppLogLevel.LOG
                else -> WebAppLogLevel.DEBUG // TIP, DEBUG
            }
            logEntry(level, message.message(), message.sourceId()?.takeIf { it.isNotEmpty() }, message.lineNumber())
            return true
        }
    }

    companion object {
        // The screens that exist, by app name. Main thread only.
        private val screens = mutableMapOf<String, WebAppActivity>()
        // How many screens are between onStart and onStop.
        private val shown get() = screens.values.count { it.visible }

        /** Set by the service: an app's screen finished (core's `screenClosed`). Main thread. */
        var closed: ((String) -> Unit)? = null

        /** Set by the service: the bridge pages get their port from. Main thread. */
        var bridge: Bridge? = null

        /** Whether an app screen is in front. */
        val anyOnScreen: Boolean get() = shown > 0

        fun isOpen(name: String): Boolean = name in screens

        /** [name]'s screen, if it exists. */
        fun screen(name: String): WebAppActivity? = screens[name]

        /** Reloads [name]'s screen; false when it isn't open. */
        fun reload(name: String): Boolean {
            val screen = screens[name] ?: return false
            screen.reload()
            return true
        }

        /** The intent that opens (or brings back) [name]'s screen. */
        fun openIntent(context: Context, name: String): Intent =
            Intent(context, WebAppActivity::class.java).setData(Uri.parse("$WEB_APP_TASK_SCHEME://$name"))

        /**
         * The intent of [name]'s home screen shortcut (and its notifications):
         * the screen starts mynx itself if it isn't running.
         */
        fun shortcutIntent(context: Context, name: String): Intent =
            openIntent(context, name).setAction(Intent.ACTION_VIEW).putExtra(WEB_APP_FROM_SHORTCUT, true)

        /** The id of [name]'s notification. */
        fun noticeId(name: String): Int = APP_NOTICE_ID_BASE + Math.floorMod(name.hashCode(), 1000)

        /** The origin (scheme://host[:port]) of [url], as core compares them. */
        fun originOf(url: String?): String? {
            val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }
    }
}
