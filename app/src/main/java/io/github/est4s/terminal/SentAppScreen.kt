package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import io.github.est4s.terminal.core.WEB_APP_DOMAIN
import io.github.est4s.terminal.core.WebApp
import io.github.est4s.terminal.core.WebAppKind
import io.github.est4s.terminal.core.WebAppResponse
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.webAppNameOfHost
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * A web app's screen sent to the other phone (the extended screen,
 * [ScreenCaster]): a WebView on a private display of its own, which is
 * encoded and streamed. Its files are served as on [WebAppActivity]'s
 * screen; the bridge (mynx.js's port) isn't offered here yet, so a page's
 * phone calls wait. Touches and keys come from the other phone and this
 * one's keyboard, dispatched to its window.
 */
class SentAppScreen(outer: Context, display: Display, private val apps: WebApps) : Presentation(outer, display) {
    // Read by WebView's own thread (shouldInterceptRequest).
    @Volatile private var app: WebApp? = null
    private var webView: WebView? = null
    private var status: TextView? = null
    private var pending: WebApp? = null
    private var pendingStatus: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = FocusedWebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = Client()
        }
        val text = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(text, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)
        webView = web
        status = text
        pending?.let(::load)
        pendingStatus?.let(::showStatus)
    }

    /** Loads [found]'s page (once the screen exists). */
    fun load(found: WebApp) {
        val web = webView ?: run { pending = found; return }
        pending = null
        app = found
        status?.visibility = View.GONE
        web.loadUrl(found.url)
    }

    /** Shows [text] over the page (the app couldn't open). */
    fun showStatus(text: String) {
        val view = status ?: run { pendingStatus = text; return }
        view.text = text
        view.visibility = View.VISIBLE
    }

    /** A touch from the other phone. */
    fun touch(event: MotionEvent) {
        window?.decorView?.dispatchTouchEvent(event)
    }

    /** A key typed for it on this phone. */
    fun key(event: KeyEvent) {
        window?.decorView?.dispatchKeyEvent(event)
    }

    /** Whether what has the focus takes text (a field in the page): typing should go to it. */
    fun editing(): Boolean = window?.decorView?.findFocus()?.onCheckIsTextEditor() == true

    override fun onStop() {
        webView?.destroy()
        webView = null
        super.onStop()
    }

    private inner class Client : WebViewClient() {
        // WebView's own thread: core only reads files.
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val url = req.url ?: return null
            if (url.scheme != "https") return null
            val host = url.host ?: return null
            return try {
                if (host.equals(WEB_APP_DOMAIN, ignoreCase = true)) {
                    // No bridge here yet: mynx.js's hello gets no port.
                    if (url.encodedPath == "/hello" && url.encodedQuery == null) return plain(204, "No Content", "")
                    return response(apps.bridgeScript(req.method ?: "GET", url.encodedPath ?: "/"))
                }
                val target = webAppNameOfHost(host) ?: return null
                val served = (app?.takeIf { it.name == target } ?: apps.find(target))
                    ?.takeIf { it.kind == WebAppKind.STATIC }
                    ?: return plain(404, "Not Found", "no app named $target")
                response(apps.respond(served, req.method ?: "GET", url.encodedPath ?: "/"))
            } catch (e: Throwable) {
                plain(500, "Internal Server Error", e.toString())
            }
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

    /**
     * Its window is on a display nobody touches, so Android never gives it
     * the focus; the page must think it has it, or fields take no typing.
     */
    private class FocusedWebView(context: Context) : WebView(context) {
        override fun hasWindowFocus(): Boolean = true

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post { onWindowFocusChanged(true) }
        }
    }
}
