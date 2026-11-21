package io.github.est4s.terminal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
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
import io.github.est4s.terminal.core.PdfRenderSize
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.pixelPalette
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }
        bound = runCatching { bindService(Intent(this, TerminalService::class.java), connection, 0) }.getOrDefault(false)
        open()
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        finish()
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
            val problem: String? = try {
                when {
                    hostFile.isDirectory -> "$name is a folder."
                    !hostFile.isFile -> "$name isn't there any more."
                    else -> {
                        val fd = ParcelFileDescriptor.open(hostFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        descriptor = fd
                        val opened = PdfRenderer(fd)
                        renderer = opened
                        aspects = List(opened.pageCount) { index ->
                            val page = opened.openPage(index)
                            try {
                                if (page.width > 0) page.height.toFloat() / page.width else 1.4142f
                            } finally {
                                page.close()
                            }
                        }
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
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (pages != null) view.showPages(pages) else view.showError(problem ?: "Can't open $name.")
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

    companion object {
        /** Shows the Debian file [debianPath], which is [hostFile] on the phone. */
        fun intent(context: Context, hostFile: File, debianPath: String): Intent =
            Intent(context, PdfViewActivity::class.java)
                .putExtra(EXTRA_FILE, hostFile.path)
                .putExtra(EXTRA_PATH, debianPath)
    }
}
