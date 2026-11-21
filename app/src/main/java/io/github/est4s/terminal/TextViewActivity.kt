package io.github.est4s.terminal

import android.app.Activity
import android.app.AlertDialog
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.est4s.terminal.core.FileJob
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.TEXT_LIMIT
import io.github.est4s.terminal.core.TextDoc
import io.github.est4s.terminal.core.TextLoad
import io.github.est4s.terminal.core.fileKindOf
import io.github.est4s.terminal.core.hostPath
import io.github.est4s.terminal.core.loadText
import io.github.est4s.terminal.core.mdLinkPath
import io.github.est4s.terminal.core.pixelPalette
import io.github.est4s.terminal.core.saveJob
import io.github.est4s.terminal.core.saveTempPath
import io.github.est4s.terminal.core.saveText
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val EXTRA_FILE = "file"
private const val EXTRA_PATH = "path"
private const val EXTRA_EDIT = "edit"
private const val COLORS_FILE = "root/.config/mynx/colors.properties"
// A Markdown file's pictures: their long side at most this.
private const val PICTURE_SIDE = 2048

/**
 * The text viewer (docs/TOUCH-DESIGN.md "The text viewer"): text, code
 * and Markdown files, to view and edit. The screen is [TextViewerView];
 * this reads the file off the main thread (core's [loadText]), and saves
 * through Debian as Files' jobs run: the text goes to a file in
 * `/tmp/.mynx/saves`, then core's [saveJob] copies it in place with
 * [TerminalService.runInDebian]. Share and Open with go through the
 * service too, so this is a [FrontActivity]; it binds the service only
 * if it's running (the app started it), and says so when it isn't.
 */
class TextViewActivity : Activity(), FrontActivity, TextViewerActions {
    private lateinit var viewer: TextViewerView
    private lateinit var hostFile: File
    private lateinit var path: String
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs }
    private val pictures = Executors.newFixedThreadPool(2)
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
        val edit = intent.getBooleanExtra(EXTRA_EDIT, false)
        title = path.substringAfterLast('/')
        // Started on its own (the app's process was gone), Pixel has the default colours yet.
        val colors = runCatching { File(rootfs, COLORS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        Pixel.use(pixelPalette(colors))
        val kind = fileKindOf(path)?.takeIf { it == FileKind.MARKDOWN || it == FileKind.CODE } ?: FileKind.TEXT
        viewer = TextViewerView(this, path, kind, terminalColorScheme(this), this)
        val root = FrameLayout(this).apply {
            addView(viewer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setBackgroundColor(Pixel.BACKGROUND)
        }
        window.decorView.setBackgroundColor(Pixel.BACKGROUND)
        setContentView(root)
        applySystemInsets(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { viewer.back() }
        }
        bound = runCatching { bindService(Intent(this, TerminalService::class.java), connection, 0) }.getOrDefault(false)
        load(edit)
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        viewer.back()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = viewer.onKey(event) || super.dispatchKeyEvent(event)

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
        pictures.shutdownNow()
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

    // Reading

    private sealed interface Read {
        class Loaded(val doc: TextDoc) : Read
        class NotText(val reason: String) : Read
        class Failed(val why: String) : Read
    }

    private fun load(edit: Boolean) {
        thread(name = "text-view-read", isDaemon = true) {
            val read = read()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (read) {
                    is Read.Loaded -> viewer.showDoc(read.doc, edit)
                    is Read.NotText -> viewer.showNotText(read.reason)
                    is Read.Failed -> viewer.showError(read.why)
                }
            }
        }
    }

    private fun read(): Read {
        val name = path.substringAfterLast('/')
        if (hostFile.isDirectory) return Read.Failed("$name is a folder.")
        if (!hostFile.isFile) return Read.Failed("$name isn't there any more.")
        return try {
            val modified = hostFile.lastModified()
            val size = hostFile.length()
            // A little past the limit, so a big file is seen as big.
            val bytes = readStart(hostFile, (minOf(size, TEXT_LIMIT.toLong()) + 4).toInt())
            when (val load = loadText(bytes, size)) {
                is TextLoad.Text -> Read.Loaded(TextDoc(load, modified))
                is TextLoad.NotText -> Read.NotText(load.reason)
            }
        } catch (e: Exception) {
            Read.Failed("Can't read $name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // InputStream.readNBytes needs API 33.
    private fun readStart(file: File, most: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(most)
        var got = 0
        while (got < most) {
            val n = input.read(buffer, got, most - got)
            if (n < 0) break
            got += n
        }
        if (got == most) buffer else buffer.copyOf(got)
    }

    // TextViewerActions

    override fun close() = finish()

    override fun save(doc: TextDoc) {
        val running = service
            ?: return viewer.saveFailed("Can't save: mynx isn't running. Open mynx, then save again.")
        if (hostFile.exists() && doc.changedOnDisk(hostFile.lastModified())) {
            AlertDialog.Builder(this)
                .setTitle("${path.substringAfterLast('/')} changed")
                .setMessage("It was changed on disk since it was opened here. Overwrite it with your text, or reload it (your changes are lost)?")
                .setPositiveButton("Overwrite") { _, _ -> write(running, doc) }
                .setNegativeButton("Reload") { _, _ ->
                    viewer.saveCancelled()
                    load(viewer.editing)
                }
                .setNeutralButton("Cancel") { _, _ -> viewer.saveCancelled() }
                .setOnCancelListener { viewer.saveCancelled() }
                .show()
            return
        }
        write(running, doc)
    }

    // The text to a temp file the app can write, then Debian copies it into the file.
    private fun write(running: TerminalService, doc: TextDoc) {
        val name = path.substringAfterLast('/')
        val temp = saveTempPath(path, System.currentTimeMillis())
        val argv = when (val job = saveJob(temp, path)) {
            is FileJob.Refused -> return viewer.saveFailed(job.reason)
            is FileJob.Run -> job.argv
        }
        val tempHost = hostPath(temp, rootfs.path)?.let { File(it) }
            ?: return viewer.saveFailed("Can't save $name: no place for $temp")
        val bytes = saveText(doc)
        thread(name = "text-view-save", isDaemon = true) {
            val wrote = runCatching {
                tempHost.parentFile?.mkdirs()
                tempHost.writeBytes(bytes)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val problem = wrote.exceptionOrNull()
                if (problem != null) {
                    viewer.saveFailed("Can't save $name: ${problem.message ?: problem.javaClass.simpleName}")
                    return@runOnUiThread
                }
                running.runInDebian(argv) { code, output ->
                    if (isDestroyed) return@runInDebian
                    if (code == 0) {
                        viewer.saved(doc.text, hostFile.lastModified())
                    } else {
                        tempHost.delete()
                        viewer.saveFailed("Couldn't save $name (exit $code):\n${output.trim()}")
                    }
                }
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

    override fun openLink(url: String) {
        val lower = url.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                startActivity(view)
            } catch (e: ActivityNotFoundException) {
                toast("No app can open that link.")
            }
            return
        }
        // A link within the file (#section) has no path: nothing to open.
        val target = mdLinkPath(path, url) ?: return
        val name = target.substringAfterLast('/')
        val file = hostPath(target, rootfs.path)?.let { File(it) }
        when {
            file == null || !file.exists() -> toast("$name isn't there.")
            file.isDirectory -> toast("$name is a folder.")
            else -> when (fileKindOf(target)) {
                FileKind.PICTURE -> startActivity(ImageViewActivity.intent(this, file, name))
                FileKind.MARKDOWN, FileKind.CODE, FileKind.TEXT -> startActivity(intent(this, file, target, edit = false))
                else -> {
                    val running = service ?: return toast("mynx can't open $name.")
                    running.openDebianFileWith(target)?.let(::toast)
                }
            }
        }
    }

    override fun loadImage(url: String, done: (Bitmap?) -> Unit) {
        // Pictures on the web aren't fetched: the alt text stays, a link to them.
        val target = mdLinkPath(path, url) ?: return done(null)
        val file = hostPath(target, rootfs.path)?.let { File(it) } ?: return done(null)
        val longSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        runCatching {
            pictures.execute {
                val bitmap = runCatching { if (file.isFile) decodeUpright(file, minOf(PICTURE_SIDE, longSide)) else null }.getOrNull()
                runOnUiThread { if (!isDestroyed) done(bitmap) }
            }
        }.onFailure { done(null) }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // Since targetSdk 35 the app draws behind the system bars: keep clear of them,
    // the camera hole and the keyboard (so the editor and Find stay above it).
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
        /**
         * Shows the Debian file [debianPath], which is [hostFile] on the
         * phone: to view, or ready to edit if [edit] (and it can be).
         */
        fun intent(context: Context, hostFile: File, debianPath: String, edit: Boolean): Intent =
            Intent(context, TextViewActivity::class.java)
                .putExtra(EXTRA_FILE, hostFile.path)
                .putExtra(EXTRA_PATH, debianPath)
                .putExtra(EXTRA_EDIT, edit)
    }
}
