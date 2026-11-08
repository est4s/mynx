package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import io.github.est4s.terminal.core.AutoReload
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.ToolsInstaller
import io.github.est4s.terminal.core.WEB_APP_LOG_DIR
import io.github.est4s.terminal.core.WEB_APP_TASK_SCHEME
import io.github.est4s.terminal.core.WebApp
import io.github.est4s.terminal.core.WebAppKind
import io.github.est4s.terminal.core.WebAppLog
import io.github.est4s.terminal.core.WebAppLogLevel
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.loadColorScheme
import io.github.est4s.terminal.core.stripColors
import io.github.est4s.terminal.core.webAppNameOfHost
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

private const val COLORS_FILE = "root/.config/mynx/colors.properties"

/**
 * One app's screen (`mynx app open NAME`): a WebView filling the screen
 * between the system bars, with no bar of its own, like an installed PWA.
 * Recents shows its name; back goes back in the page, then closes it.
 * Started with the data URI `mynx-app://NAME`, so Android keeps one task
 * per app (`documentLaunchMode="intoExisting"`). Everything it decides
 * (which file a request gets, when to reload, the log's lines) is core's.
 */
class WebAppActivity : Activity() {
    private lateinit var name: String
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs }
    private val apps by lazy { WebApps(File(rootfs, "root"), rootfs.absolutePath, ToolsInstaller(filesDir).tools) }
    // Read by WebView's own thread (shouldInterceptRequest).
    @Volatile private var app: WebApp? = null
    @Volatile private var log: WebAppLog? = null
    private lateinit var webView: WebView
    private val main by lazy { Handler(Looper.getMainLooper()) }
    // Log writes leave the main thread, in order.
    private val logWriter = Executors.newSingleThreadExecutor()
    // The auto-reload poller: its own thread, as the fingerprint reads every file.
    private val pollThread = HandlerThread("mynx-app-poll").apply { start() }
    private val poller = Handler(pollThread.looper)
    // Only touched on the poll thread.
    private var autoReload: AutoReload? = null
    private var visible = false

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = nameOf(intent) ?: run {
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
        // The root pads for the system bars, the cutout and the keyboard; the page fills the rest.
        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        colors?.let {
            window.decorView.setBackgroundColor(it.background)
            root.setBackgroundColor(it.background)
        }
        setContentView(root)
        applySystemInsets(root)
        handleBack()
        screens[name] = this
        load()
    }

    // Opened again (Android's one task per app): the same screen, the app read again.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (nameOf(intent) != name) return
        val before = app?.url
        findApp()
        val now = app ?: return
        log?.let { l -> runCatching { logWriter.execute { runCatching { l.opened(now.url) } } } }
        if (now.url != before) webView.loadUrl(now.url)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        poller.post(poll)
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
        logWriter.shutdown()
        if (::name.isInitialized) {
            if (screens[name] === this) screens.remove(name)
            if (isFinishing && !isChangingConfigurations && screens[name] == null) {
                runCatching { closed?.invoke(name) }
            }
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun nameOf(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == WEB_APP_TASK_SCHEME }?.host

    private fun findApp() {
        val found = runCatching { apps.find(name) }.getOrNull()
        app = found
        if (found != null) {
            val file = File(rootfs, "$WEB_APP_LOG_DIR/$name.log")
            runCatching { logWriter.execute { file.parentFile?.mkdirs() } }
            log = WebAppLog(file, found.origin)
            describeTask(found.label)
        }
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

    // As MainActivity: the app draws behind the system bars (targetSdk 35+), and the keyboard.
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun logEntry(level: WebAppLogLevel, message: String, source: String?, line: Int) {
        val l = log ?: return
        runCatching { logWriter.execute { runCatching { l.entry(level, message, source, line) } } }
    }

    private inner class Client : WebViewClient() {
        // WebView's own thread: core only reads files.
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val url = req.url ?: return null
            if (url.scheme != "https") return null
            val host = url.host ?: return null
            val target = webAppNameOfHost(host) ?: return null
            return try {
                // Usually the screen's own app; another app's host is answered from its own folder.
                val served = (app?.takeIf { it.name == target } ?: apps.find(target))
                    ?.takeIf { it.kind == WebAppKind.STATIC }
                    ?: return plain(404, "Not Found", "no app named $target")
                val answer = apps.respond(served, req.method ?: "GET", url.encodedPath ?: "/")
                val body = answer.file?.let { FileInputStream(it) }
                    ?: ByteArrayInputStream((answer.body ?: "").toByteArray(Charsets.UTF_8))
                WebResourceResponse(answer.mime, answer.encoding, answer.status, answer.reason, answer.headers, body)
            } catch (e: Exception) {
                logEntry(WebAppLogLevel.ERROR, "the app couldn't answer: ${e.stackTraceToString()}", url.toString(), 0)
                plain(500, "Internal Server Error", e.toString())
            }
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

        /** Whether an app screen is in front. */
        val anyOnScreen: Boolean get() = shown > 0

        fun isOpen(name: String): Boolean = name in screens

        /** Reloads [name]'s screen; false when it isn't open. */
        fun reload(name: String): Boolean {
            val screen = screens[name] ?: return false
            screen.reload()
            return true
        }

        /** The intent that opens (or brings back) [name]'s screen. */
        fun openIntent(context: android.content.Context, name: String): Intent =
            Intent(context, WebAppActivity::class.java).setData(Uri.parse("$WEB_APP_TASK_SCHEME://$name"))
    }
}
