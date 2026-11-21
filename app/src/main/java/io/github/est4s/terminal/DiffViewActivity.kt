package io.github.est4s.terminal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.est4s.terminal.core.ChangedFileRow
import io.github.est4s.terminal.core.ChatController
import io.github.est4s.terminal.core.DIFFS_DIR
import io.github.est4s.terminal.core.DiffRow
import io.github.est4s.terminal.core.FileDiff
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.claudeTranscriptPath
import io.github.est4s.terminal.core.codeLanguageOf
import io.github.est4s.terminal.core.diffRows
import io.github.est4s.terminal.core.diffSummary
import io.github.est4s.terminal.core.diffTempPath
import io.github.est4s.terminal.core.fileKindOf
import io.github.est4s.terminal.core.hostPath
import io.github.est4s.terminal.core.pixelPalette
import io.github.est4s.terminal.core.readFileEdits
import io.github.est4s.terminal.core.turnDiff
import io.github.est4s.terminal.core.unifiedDiff
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val EXTRA_TRANSCRIPT = "transcript"
private const val EXTRA_TOOLS = "tools"
private const val EXTRA_PATH = "path"
private const val EXTRA_FILE = "file"
private const val COLORS_FILE = "root/.config/mynx/colors.properties"
// Shared diffs older than this are deleted when another is shared.
private const val DIFF_KEEP_MS = 60 * 60 * 1000L

/**
 * The diff view (docs/TOUCH-DESIGN.md "A turn's changes"): what a chat's
 * turn changed in a file, from the Write and Edit calls that wrote it.
 * Off the main thread it reads those calls' results from the session's
 * transcript (core's [readFileEdits]) and works out one diff ([turnDiff],
 * [diffRows]); the screen is [DiffView]. With no diff to show (the
 * results aren't in the transcript) it opens the file in the viewer
 * instead. Share writes core's [unifiedDiff] to a file in [DIFFS_DIR]
 * and shares it through the service, so this is a [FrontActivity], bound
 * to the service if it runs.
 */
class DiffViewActivity : Activity(), FrontActivity, DiffViewActions {
    private lateinit var view: DiffView
    private lateinit var transcript: File
    private lateinit var toolIds: List<String>
    private lateinit var path: String
    private lateinit var hostFile: File
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs }
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
        transcript = File(intent.getStringExtra(EXTRA_TRANSCRIPT) ?: "")
        toolIds = intent.getStringArrayListExtra(EXTRA_TOOLS).orEmpty()
        hostFile = File(intent.getStringExtra(EXTRA_FILE) ?: "")
        path = intent.getStringExtra(EXTRA_PATH) ?: hostFile.path
        title = path.substringAfterLast('/')
        // Started on its own (the app's process was gone), Pixel has the default colours yet.
        val colors = runCatching { File(rootfs, COLORS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        Pixel.use(pixelPalette(colors))
        view = DiffView(this, path, terminalColorScheme(this), this)
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
        load()
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
        if (bound) runCatching { unbindService(connection) }
        bound = false
        super.onDestroy()
    }

    // FrontActivity: the share sheet needs the activity in front.

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
        class Shown(val diff: FileDiff, val rows: List<DiffRow>) : Read
        object TooBig : Read
        object NoDiff : Read
        class Failed(val why: String) : Read
    }

    private fun load() {
        val language = diffLanguage(path)
        thread(name = "diff-view-read", isDaemon = true) {
            val read = try {
                val diff = turnDiff(readFileEdits(transcript, toolIds.toSet()))
                when {
                    diff == null -> Read.NoDiff
                    diff.tooBig -> Read.TooBig
                    else -> Read.Shown(diff, diffRows(diff, language))
                }
            } catch (e: Exception) {
                Read.Failed("Can't read the changes: ${e.message ?: e.javaClass.simpleName}")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (read) {
                    is Read.Shown -> view.showDiff(read.diff, read.rows)
                    Read.TooBig -> view.showTooBig()
                    Read.NoDiff -> {
                        openFile()
                        finish()
                    }
                    is Read.Failed -> view.showError(read.why)
                }
            }
        }
    }

    // DiffViewActions

    override fun close() = finish()

    override fun openFile() {
        startActivity(TextViewActivity.intent(this, hostFile, path, edit = false))
    }

    override fun shareDiff() {
        val diff = view.diff ?: return
        val running = service ?: return toast("mynx isn't running: open it, then try again.")
        val now = System.currentTimeMillis()
        val temp = diffTempPath(path, now)
        val tempHost = hostPath(temp, rootfs.path)?.let(::File) ?: return toast("Can't share the diff: no place for $temp")
        val text = unifiedDiff(path, diff)
        thread(name = "diff-view-share", isDaemon = true) {
            val wrote = runCatching {
                val folder = tempHost.parentFile
                folder?.mkdirs()
                folder?.listFiles()?.filter { it.isFile && it.lastModified() < now - DIFF_KEEP_MS }?.forEach { it.delete() }
                tempHost.writeText(text)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val problem = wrote.exceptionOrNull()
                if (problem != null) {
                    toast("Can't share the diff: ${problem.message ?: problem.javaClass.simpleName}")
                } else {
                    running.shareDebianFile(temp)?.let(::toast)
                }
            }
        }
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
        /**
         * Shows the changes to the Debian file [debianPath] ([hostFile] on
         * the phone) made by the Write and Edit calls [toolIds], whose
         * results are in the Claude Code transcript [transcriptHost] (its
         * path on the phone).
         */
        fun intent(context: Context, transcriptHost: File, toolIds: List<String>, debianPath: String, hostFile: File): Intent =
            Intent(context, DiffViewActivity::class.java)
                .putExtra(EXTRA_TRANSCRIPT, transcriptHost.path)
                .putStringArrayListExtra(EXTRA_TOOLS, ArrayList(toolIds))
                .putExtra(EXTRA_PATH, debianPath)
                .putExtra(EXTRA_FILE, hostFile.path)
    }
}

// The language a file's lines are highlighted in: Markdown's, or its code's.
private fun diffLanguage(path: String): String? =
    if (fileKindOf(path) == FileKind.MARKDOWN) "markdown" else codeLanguageOf(path.substringAfterLast('/'))

/**
 * What chat's Changed files card asks about its rows (a row with
 * [ChangedFileRow.toolIds]): their `+N −M`, and opening their diff.
 */
interface ChatDiffs {
    /**
     * [row]'s summary ([diffSummary]) if it's known; else null, and
     * [done] gets it on the main thread once worked out (not at all if it
     * can't be).
     */
    fun summary(row: ChangedFileRow, done: (String) -> Unit): String?

    /** Opens [row]'s diff view; false when it can't (no session yet): the card opens the file. */
    fun open(row: ChangedFileRow): Boolean
}

private val DIFF_WORKER: Executor = Executors.newSingleThreadExecutor { work -> Thread(work, "chat-diffs").apply { isDaemon = true } }

/**
 * [ChatDiffs] for the chat [controller] shows: its session's transcript
 * (`claudeTranscriptPath` of its home, folder and session) found in
 * [rootfs]. Summaries are worked out on one worker thread and kept by the
 * row's tool calls, which are a card's and a file's own.
 */
class TranscriptDiffs(
    private val context: Context,
    private val controller: () -> ChatController?,
    private val rootfs: String,
    private val worker: Executor = DIFF_WORKER,
) : ChatDiffs {
    private val main = Handler(Looper.getMainLooper())
    private val summaries = HashMap<List<String>, String>()
    private val waiting = HashMap<List<String>, MutableList<(String) -> Unit>>()

    private fun transcript(): File? {
        val chat = controller() ?: return null
        val session = chat.sessionId ?: return null
        return hostPath(claudeTranscriptPath(chat.home, chat.cwd, session), rootfs)?.let(::File)
    }

    override fun summary(row: ChangedFileRow, done: (String) -> Unit): String? {
        val key = row.toolIds
        summaries[key]?.let { return it }
        waiting[key]?.let {
            it += done
            return null
        }
        val file = transcript() ?: return null
        waiting[key] = mutableListOf(done)
        worker.execute {
            val summary = runCatching { turnDiff(readFileEdits(file, key.toSet()))?.let(::diffSummary) }.getOrNull()
            main.post {
                val callbacks = waiting.remove(key).orEmpty()
                // Not cached when it can't be worked out yet: the transcript may still be catching up.
                if (summary != null) {
                    summaries[key] = summary
                    callbacks.forEach { it(summary) }
                }
            }
        }
        return null
    }

    override fun open(row: ChangedFileRow): Boolean {
        val file = transcript() ?: return false
        val host = hostPath(row.path, rootfs)?.let(::File) ?: File(row.path)
        context.startActivity(DiffViewActivity.intent(context, file, row.toolIds, row.path, host))
        return true
    }
}
