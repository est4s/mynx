package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.est4s.terminal.core.HtmlNavigation
import io.github.est4s.terminal.core.HtmlRoute
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.ToolsInstaller
import io.github.est4s.terminal.core.WebAppResponse
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.hostPath
import io.github.est4s.terminal.core.htmlFileUrl
import io.github.est4s.terminal.core.htmlNavigation
import io.github.est4s.terminal.core.htmlPagePath
import io.github.est4s.terminal.core.htmlRequestRoute
import io.github.est4s.terminal.core.htmlViewFolder
import io.github.est4s.terminal.core.htmlViewTitle
import io.github.est4s.terminal.core.mediaFolder
import io.github.est4s.terminal.core.pixelPalette
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

private const val EXTRA_PATH = "path"
private const val COLORS_FILE = "root/.config/mynx/colors.properties"

/**
 * The page viewer (docs/TOUCH-DESIGN.md "HTML files"): an HTML file
 * anywhere outside the app folders, rendered in a WebView under
 * [HtmlViewerView]'s top bar. The view loads core's [htmlFileUrl] and
 * answers that host from the file's folder ([WebApps.respondFolder], on
 * WebView's own thread: core only reads files), so relative CSS, scripts
 * and pictures work; the web stays reachable; anything else is refused
 * (core's [htmlRequestRoute]). No bridge: a file isn't an app. Links in
 * the folder stay here, web links, `mailto:` and `tel:` go to other apps
 * ([htmlNavigation]). View source, Share and Open with are for the page
 * shown; Share and Open with go through the service, so this is a
 * [FrontActivity], bound to the service if it runs.
 */
class HtmlViewActivity : Activity(), FrontActivity, HtmlViewActions {
    private lateinit var path: String
    private lateinit var folder: String
    private lateinit var viewer: HtmlViewerView
    private var webView: WebView? = null
    // The address whose load failed last (its error is on screen).
    private var failedUrl: String? = null
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs }
    private val files by lazy { WebApps(File(rootfs, "root"), rootfs.absolutePath, ToolsInstaller(filesDir).tools) }
    private var service: TerminalService? = null
    private var bound = false
    private var visible = false
    private val asks = ActivityAsks(this)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            service = (binder as TerminalService.LocalBinder).service
        }

        override fun onServiceDisconnected(component: ComponentName) {
            service = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val given = intent.getStringExtra(EXTRA_PATH)
        if (given.isNullOrEmpty()) {
            finish()
            return
        }
        path = given
        folder = htmlViewFolder(path)
        title = path.substringAfterLast('/')
        // Started on its own (the app's process was gone), Pixel has the default colours yet.
        val colors = runCatching { File(rootfs, COLORS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        Pixel.use(pixelPalette(colors))
        viewer = HtmlViewerView(this, this)
        viewer.showPage(path.substringAfterLast('/'), mediaFolder(path))
        val root = FrameLayout(this).apply {
            addView(viewer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setBackgroundColor(Pixel.BACKGROUND)
        }
        window.decorView.setBackgroundColor(Pixel.BACKGROUND)
        setContentView(root)
        applySystemInsets(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        bound = runCatching { bindService(Intent(this, TerminalService::class.java), connection, 0) }.getOrDefault(false)
        // A phone whose WebView is missing or broken throws here: say so on screen.
        val web = try {
            WebView(this)
        } catch (e: Exception) {
            viewer.showError("Can't show pages: Android's WebView didn't start.\n\n${e.stackTraceToString()}")
            return
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        // A download is the web's: the browser fetches it.
        web.setDownloadListener { url, _, _, _, _ ->
            if (htmlNavigation(url) == HtmlNavigation.OUTSIDE) openOutside(url)
        }
        webView = web
        viewer.setPage(web)
        web.loadUrl(htmlFileUrl(path))
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        back()
    }

    // Esc closes and F5 or Ctrl+R reload, whatever the page does with keys.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val reload = key == KeyEvent.KEYCODE_F5 || (key == KeyEvent.KEYCODE_R && event.isCtrlPressed)
        if (key != KeyEvent.KEYCODE_ESCAPE && !reload) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (reload) reload() else close()
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onResume() {
        super.onResume()
        FrontActivities.resumed(this)
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        FrontActivities.gone(this)
        asks.clear()
        if (bound) runCatching { unbindService(connection) }
        bound = false
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // FrontActivity: the share sheet and "open with" need the activity in front.

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

    // The Debian path of the page shown: another page of the folder once a link was followed.
    private fun pagePath(): String = htmlPagePath(folder, webView?.url) ?: path

    // HtmlViewActions

    override fun close() = finish()

    // The page's history first, then the screen closes.
    override fun back() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else finish()
    }

    override fun reload() {
        val web = webView ?: return
        failedUrl = null
        viewer.clearError()
        // After a failed first load there's nothing to reload.
        if (web.url == null) web.loadUrl(htmlFileUrl(path)) else web.reload()
    }

    override fun viewSource() {
        val page = pagePath()
        val host = hostPath(page, rootfs.path)?.let(::File)
            ?: return toast("Can't show ${page.substringAfterLast('/')}'s source.")
        startActivity(TextViewActivity.intent(this, host, page, edit = false))
    }

    override fun share() {
        val running = service ?: return toast("mynx isn't running: open it, then try again.")
        running.shareDebianFile(pagePath())?.let(::toast)
    }

    override fun openWithAnotherApp() {
        val running = service ?: return toast("mynx isn't running: open it, then try again.")
        running.openDebianFile(pagePath())?.let(::toast)
    }

    private fun openOutside(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No app can open that link.")
        }
    }

    private fun showTitle(pageTitle: String?) {
        val web = webView ?: return
        val page = pagePath()
        viewer.showPage(htmlViewTitle(pageTitle, web.url, page), mediaFolder(page))
    }

    private inner class Client : WebViewClient() {
        // WebView's own thread: core only reads files.
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val url = req.url ?: return null
            return try {
                when (htmlRequestRoute(url.toString())) {
                    HtmlRoute.NETWORK -> null
                    HtmlRoute.BLOCKED -> plain(403, "Forbidden", "pages can't load that\n")
                    HtmlRoute.FOLDER -> response(files.respondFolder(folder, req.method ?: "GET", url.encodedPath ?: "/"))
                }
            } catch (e: Throwable) {
                plain(500, "Internal Server Error", e.toString())
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return false
            val url = req.url?.toString() ?: return true
            // A frame in the page loads what the page may load; only the page's own navigations leave.
            if (!req.isForMainFrame) return htmlRequestRoute(url) == HtmlRoute.BLOCKED
            return when (htmlNavigation(url)) {
                HtmlNavigation.STAY -> false
                HtmlNavigation.OUTSIDE -> {
                    openOutside(url)
                    true
                }
                HtmlNavigation.BLOCKED -> true
            }
        }

        // A new page clears the last one's error; the failed page itself may start after its error came.
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != failedUrl) {
                failedUrl = null
                viewer.clearError()
            }
            showTitle(null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            showTitle(view?.title)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val req = request?.takeIf { it.isForMainFrame } ?: return
            val why = error?.description?.toString() ?: "error ${error?.errorCode}"
            failed(req.url?.toString()) { name -> "Can't load $name: $why\n\n${req.url}" }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            val req = request?.takeIf { it.isForMainFrame } ?: return
            val status = errorResponse?.statusCode
            val reason = errorResponse?.reasonPhrase.orEmpty()
            failed(req.url?.toString()) { name -> if (status == 404) "$name isn't there." else "Can't load $name: $status $reason" }
        }
    }

    // The main frame's load of [url] failed: [text] (given the page's file name) on screen.
    private fun failed(url: String?, text: (String) -> String) {
        failedUrl = url
        viewer.showError(text((htmlPagePath(folder, url) ?: path).substringAfterLast('/')))
    }

    private inner class Chrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            showTitle(title)
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

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // Since targetSdk 35 the app draws behind the system bars: keep clear of them,
    // the camera hole and the keyboard (so a form's fields stay above it).
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

    companion object {
        /** Shows the HTML file [debianPath] (a Debian path), rendered, with its folder served beside it. */
        fun intent(context: Context, debianPath: String): Intent =
            Intent(context, HtmlViewActivity::class.java).putExtra(EXTRA_PATH, debianPath)
    }
}

/** What the page viewer's screen asks of its activity. */
interface HtmlViewActions {
    /** Esc. */
    fun close()

    /** ← or Back: the page's history first. */
    fun back()

    fun reload()

    /** View source: the file in the code viewer. */
    fun viewSource()

    fun share()

    fun openWithAnotherApp()
}

/**
 * The page viewer's screen: a top bar (←, the page's title over its
 * folder, dim, and ⋮: Reload, View source, Share, Open with another app),
 * then the page ([setPage]: the WebView), or a message over it when it
 * can't load ([showError]). The page keeps its own colours.
 */
@SuppressLint("ViewConstructor")
class HtmlViewerView(context: Context, private val actions: HtmlViewActions) : LinearLayout(context) {
    private val titleText = TextView(context).apply {
        typeface = Pixel.label(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val folderText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Pixel.DIM)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(4), 0, dp(4), 0)
        addView(iconButton(PixelIcon.BACK, Pixel.CYAN, "Back") { actions.back() }, LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(6), dp(8), dp(6))
            addView(titleText)
            addView(folderText)
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(iconButton(PixelIcon.MORE, Pixel.TEXT, "More") { showMore(it) }, LayoutParams(dp(48), dp(52)))
    }
    private val messageText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.DIM)
        gravity = Gravity.CENTER
        setTextIsSelectable(true)
    }
    private val reloadButton = TextView(context).apply {
        text = "Reload"
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Pixel.BACKGROUND)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
        isFocusable = true
        setOnClickListener { actions.reload() }
    }
    private val message = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Pixel.BACKGROUND)
        visibility = View.GONE
        isClickable = true // over the page: taps don't reach it
        addView(messageText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(reloadButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }
    private val content = FrameLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(MATCH_PARENT, dp(2)))
        addView(content, LayoutParams(MATCH_PARENT, 0, 1f))
        content.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** [page] (the WebView) fills the screen under the bar. */
    fun setPage(page: View) {
        content.addView(page, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** The bar's [title] and dim [folder]. */
    fun showPage(title: String, folder: String) {
        titleText.text = title
        folderText.text = folder
    }

    /** [text] instead of the page, with a Reload button. */
    fun showError(text: String) {
        messageText.text = text
        message.visibility = View.VISIBLE
    }

    fun clearError() {
        message.visibility = View.GONE
    }

    private fun showMore(anchor: View) {
        pixelMenu(anchor, listOf(
            PixelMenuEntry("Reload") { actions.reload() },
            PixelMenuEntry("View source") { actions.viewSource() },
            PixelMenuEntry("Share") { actions.share() },
            PixelMenuEntry("Open with another app") { actions.openWithAnotherApp() },
        ))
    }

    private fun iconButton(icon: PixelIcon, color: Int, description: String, onTap: (View) -> Unit) = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, color, 20))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onTap(it) }
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}
