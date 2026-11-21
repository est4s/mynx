package io.github.est4s.terminal

import android.annotation.TargetApi
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.est4s.terminal.core.PdfPoint
import io.github.est4s.terminal.core.PdfRect
import io.github.est4s.terminal.core.PdfRenderSize
import io.github.est4s.terminal.core.PdfSelection
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.pdfSelectionText
import io.github.est4s.terminal.core.pixelPalette
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val EXTRA_FILE = "file"
private const val EXTRA_PATH = "path"
private const val COLORS_FILE = "root/.config/mynx/colors.properties"

/**
 * The PDF viewer (docs/TOUCH-DESIGN.md "PDF"), on Android's
 * [PdfRenderer]. The screen is [PdfView]; this opens the file and
 * renders its pages on one worker thread, since a renderer has one page
 * open at a time and isn't thread-safe: everything that touches it
 * (opening, measuring the pages, rendering, closing) runs there, in
 * order. A PDF with a password, or one it can't read, says so on screen.
 * On Android 15+ the renderer also selects a page's text ([PdfText]),
 * on the same thread.
 * Share and Open with another app go through the service, so this is a
 * [FrontActivity], bound to the service if it runs.
 */
class PdfViewActivity : Activity(), FrontActivity, PdfViewActions {
    private lateinit var view: PdfView
    private lateinit var hostFile: File
    private lateinit var path: String
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { work -> Thread(work, "pdf-view").apply { isDaemon = true } }
    // The worker's alone.
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostFile = File(intent.getStringExtra(EXTRA_FILE) ?: "")
        path = intent.getStringExtra(EXTRA_PATH) ?: hostFile.path
        title = path.substringAfterLast('/')
        // Started on its own (the app's process was gone), Pixel has the default colours yet.
        val colors = runCatching { File(rootfs, COLORS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        Pixel.use(pixelPalette(colors))
        view = PdfView(this, path, this)
        val root = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setBackgroundColor(Pixel.BACKGROUND)
        }
        window.decorView.setBackgroundColor(Pixel.BACKGROUND)
        setContentView(root)
        applySystemInsets(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        bound = runCatching { bindService(Intent(this, TerminalService::class.java), connection, 0) }.getOrDefault(false)
        open()
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        back()
    }

    // Back clears a selection first.
    private fun back() {
        if (!view.clearSelection()) finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = view.onKey(event) || super.dispatchKeyEvent(event)

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onResume() {
        super.onResume()
        FrontActivities.resumed(this)
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        FrontActivities.gone(this)
        asks.clear()
        // After whatever render is queued: the renderer is the worker's.
        runCatching {
            worker.execute {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                renderer = null
                descriptor = null
            }
        }
        worker.shutdown()
        if (bound) runCatching { unbindService(connection) }
        bound = false
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

    // The document

    private fun open() {
        val name = path.substringAfterLast('/')
        worker.execute {
            var aspects: List<Float>? = null
            var widths: List<Float> = emptyList()
            val problem: String? = try {
                when {
                    hostFile.isDirectory -> "$name is a folder."
                    !hostFile.isFile -> "$name isn't there any more."
                    else -> {
                        val fd = ParcelFileDescriptor.open(hostFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        descriptor = fd
                        val opened = PdfRenderer(fd)
                        renderer = opened
                        val pageWidths = ArrayList<Float>()
                        aspects = List(opened.pageCount) { index ->
                            val page = opened.openPage(index)
                            try {
                                pageWidths += page.width.toFloat()
                                if (page.width > 0) page.height.toFloat() / page.width else 1.4142f
                            } finally {
                                page.close()
                            }
                        }
                        widths = pageWidths
                        null
                    }
                }
            } catch (_: SecurityException) {
                "$name is protected by a password: mynx's viewer can't open it."
            } catch (e: IOException) {
                "Can't open $name: it's broken, or not a PDF. (${e.message ?: e.javaClass.simpleName})"
            } catch (e: Exception) {
                "Can't open $name: ${e.message ?: e.javaClass.simpleName}"
            }
            if (problem != null) {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                renderer = null
                descriptor = null
            }
            val pages = aspects
            val pointWidths = widths
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (pages != null) view.showPages(pages, pointWidths) else view.showError(problem ?: "Can't open $name.")
            }
        }
    }

    // PdfViewActions

    override fun close() = finish()

    override fun render(page: Int, size: PdfRenderSize, stillWanted: () -> Boolean, done: (Bitmap?, String?) -> Unit) {
        // Refused once the activity is going: nothing to show it on.
        runCatching {
            worker.execute {
                val current = renderer
                var bitmap: Bitmap? = null
                var error: String? = null
                if (current != null && stillWanted()) {
                    try {
                        val made = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        // Pages are drawn on white: PdfRenderer leaves what a page doesn't paint see-through.
                        made.eraseColor(Color.WHITE)
                        val opened = current.openPage(page)
                        try {
                            opened.render(made, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally {
                            opened.close()
                        }
                        bitmap = made
                    } catch (e: Throwable) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                }
                runOnUiThread { if (!isDestroyed) done(bitmap, error) }
            }
        }
    }

    override val canSelect: Boolean get() = Build.VERSION.SDK_INT >= 35

    override fun select(page: Int, start: PdfPoint, stop: PdfPoint, done: (PdfSelection?) -> Unit) =
        onWorker(done) { current -> if (Build.VERSION.SDK_INT >= 35) PdfText.select(current, page, start, stop) else null }

    override fun selectAll(page: Int, done: (PdfSelection?) -> Unit) =
        onWorker(done) { current -> if (Build.VERSION.SDK_INT >= 35) PdfText.all(current, page) else null }

    // [work] on the worker with the renderer, then [done] with what it gave (null when it threw).
    private fun onWorker(done: (PdfSelection?) -> Unit, work: (PdfRenderer) -> PdfSelection?) {
        val sent = runCatching {
            worker.execute {
                val current = renderer
                val found = if (current == null) null else runCatching { work(current) }.getOrNull()
                runOnUiThread { if (!isDestroyed) done(found) }
            }
        }
        // Refused once the activity is going.
        if (sent.isFailure) done(null)
    }

    override fun share() {
        val running = service ?: return toast("mynx isn't running: open it, then try again.")
        running.shareDebianFile(path)?.let(::toast)
    }

    override fun openWithAnotherApp() {
        val running = service ?: return toast("mynx isn't running: open it, then try again.")
        running.openDebianFile(path)?.let(::toast)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // Since targetSdk 35 the app draws behind the system bars: keep clear of them and the camera hole.
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    /** Android 15's text on a page, in its PDF points; every call on the worker, which owns the renderer. */
    @TargetApi(35)
    private object PdfText {
        fun select(renderer: PdfRenderer, page: Int, start: PdfPoint, stop: PdfPoint): PdfSelection? {
            val opened = renderer.openPage(page)
            try {
                val chosen: PageSelection? = opened.selectContent(SelectionBoundary(point(start)), SelectionBoundary(point(stop)))
                return chosen?.let { selected(page, it.selectedTextContents) }
            } finally {
                opened.close()
            }
        }

        fun all(renderer: PdfRenderer, page: Int): PdfSelection? {
            val opened = renderer.openPage(page)
            try {
                return selected(page, opened.textContents)
            } finally {
                opened.close()
            }
        }

        private fun selected(page: Int, contents: List<PdfPageTextContent>): PdfSelection? {
            val text = pdfSelectionText(contents.map { it.text })
            if (text.isBlank()) return null
            val rects = contents.flatMap { content -> content.bounds.map { PdfRect(it.left, it.top, it.right, it.bottom) } }
            return PdfSelection(page, text, rects)
        }

        private fun point(at: PdfPoint) = Point(at.x.roundToInt(), at.y.roundToInt())
    }

    companion object {
        /** Shows the Debian file [debianPath], which is [hostFile] on the phone. */
        fun intent(context: Context, hostFile: File, debianPath: String): Intent =
            Intent(context, PdfViewActivity::class.java)
                .putExtra(EXTRA_FILE, hostFile.path)
                .putExtra(EXTRA_PATH, debianPath)
    }
}
