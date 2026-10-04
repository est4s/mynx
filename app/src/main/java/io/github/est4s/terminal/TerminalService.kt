package io.github.est4s.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.github.est4s.terminal.core.PocketRequests
import io.github.est4s.terminal.core.ProotPaths
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.NEON
import io.github.est4s.terminal.core.ToolsInstaller
import io.github.est4s.terminal.core.parseColorScheme
import io.github.est4s.terminal.core.Tabs
import io.github.est4s.terminal.core.closesOnExit
import io.github.est4s.terminal.core.hostPath
import io.github.est4s.terminal.core.parseSavedTabs
import io.github.est4s.terminal.core.restore
import io.github.est4s.terminal.core.serialize
import io.github.est4s.terminal.core.snapshot
import io.github.est4s.terminal.core.prootLaunch
import io.github.est4s.terminal.core.runningTerminalsText
import io.github.est4s.terminal.core.writeFakeProc
import io.github.est4s.terminal.core.writeToolsProfile
import java.io.File
import java.util.WeakHashMap

private const val CHANNEL_ID = "terminals"
private const val NOTIFICATION_ID = 1
private const val ACTION_EXIT = "io.github.est4s.terminal.EXIT"
private const val CWD_DIR = "/tmp/.pocket-terminal"
private const val REQUEST_DIR = "$CWD_DIR/requests"
private const val TOOLS_ASSET = "tools.tar.xz"
// Requests after which the app applies the config files again.
private val RELOADING_REQUESTS = setOf(
    "check", "set", "reset", "theme-set", "theme-reset", "preview-end", "keybar-edit", "keybar-reset",
)

/**
 * Owns the terminal sessions and keeps them running while the app is in the
 * background. MainActivity binds to it and shows the current session.
 */
class TerminalService : Service() {
    inner class LocalBinder : Binder() {
        val service get() = this@TerminalService
    }

    private val binder = LocalBinder()
    private val client by lazy { SessionClient(this) }
    var tabs: Tabs<TerminalSession> = newTabs()
        private set
    private var notifiedCount = -1
    private val stateFile by lazy { File(filesDir, "state/tabs") }
    private val rootfs by lazy { RootfsInstaller(filesDir).rootfs.absolutePath }
    // Each shell writes its folder to a file here after every prompt (see
    // prootLaunch's cwdFile). Inside Debian's /tmp, so the shell can write it.
    private val cwdDir by lazy { File(rootfs, CWD_DIR) }
    private val cwdFiles = WeakHashMap<TerminalSession, File>()
    // Each shell's `keybar` command writes the key bar to show here.
    private val keyBarFiles = WeakHashMap<TerminalSession, File>()
    private var nextShellId = 1
    private val tools by lazy { ToolsInstaller(filesDir) }
    /** Why the app's tools couldn't be updated, for the activity to show. */
    var toolsError: String? = null
        private set
    private val requestDir by lazy { File(rootfs, REQUEST_DIR) }
    private val requests by lazy { PocketRequests(requestDir, File(rootfs, "root")) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    // Kept in a field: a FileObserver stops when it's garbage collected.
    private var requestWatcher: FileObserver? = null
    // Last folder known for each session: where it started, or what its shell
    // last reported. Restored tabs only start when first shown, so this keeps
    // their folder until then.
    private val knownCwd = WeakHashMap<TerminalSession, String>()

    override fun onCreate() {
        super.onCreate()
        // Reports from a previous run would belong to the wrong shells.
        cwdDir.deleteRecursively()
        updateTools()
        watchRequests()
    }

    // Before any shell starts, so none runs old tools while they're replaced.
    private fun updateTools() {
        val version = "${BuildConfig.VERSION_CODE}-${packageManager.getPackageInfo(packageName, 0).lastUpdateTime}"
        toolsError = runCatching {
            tools.update(version) { assets.open(TOOLS_ASSET) }
            writeToolsProfile(File(rootfs))
        }.exceptionOrNull()?.stackTraceToString()
    }

    @Suppress("DEPRECATION") // the File constructor needs API 29
    private fun watchRequests() {
        requestDir.mkdirs()
        requestWatcher = object : FileObserver(requestDir.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.endsWith(".req") == true) mainHandler.post { processRequests() }
            }
        }.also { it.startWatching() }
    }

    /** Answers waiting `pocket` requests, then applies what they changed. */
    fun processRequests() {
        val handled = runCatching { requests.processPending() }.getOrDefault(emptyList())
        for (request in handled) {
            when (request.name) {
                "preview-colors" -> activity?.previewColors(parseColorScheme(request.args.joinToString("\n"), NEON).scheme)
                in RELOADING_REQUESTS -> activity?.reloadConfig(quiet = true)
            }
        }
    }

    /** The settings' cursor style, which every terminal reads when it (re)starts. */
    var cursorStyle: Int? = null
        private set

    fun setCursorStyle(style: String) {
        val next = when (style) {
            "underline" -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            "bar" -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
            else -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }
        if (next == cursorStyle) return
        cursorStyle = next
        tabs.tabs.forEach { it.session.emulator?.setCursorStyle() }
    }

    /** The activity showing the sessions, if any. Sessions must never hold it otherwise. */
    var activity: MainActivity? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exit()
            return START_NOT_STICKY
        }
        goForeground()
        // Android restarting a killed service can't bring the shells back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        requestWatcher?.stopWatching()
        killAll()
        super.onDestroy()
    }

    /**
     * The session to show: the selected tab, else the saved tabs, else a new
     * tab. Only that last case, a fresh start, opens the launcher menu.
     */
    fun currentSession(): TerminalSession {
        tabs.selected?.let { return it.session }
        val saved = runCatching { parseSavedTabs(stateFile.readText()) }.getOrNull()
            ?: return startShell(openMenu = true).also { tabs.open(it) }
        tabs.restore(saved) { cwd -> startShell(cwd) }
        return tabs.selected!!.session
    }

    fun newSession(): TerminalSession = startShell().also { tabs.open(it) }

    /** Replaces a finished session with a fresh shell in the same tab and folder. */
    fun restart(old: TerminalSession) {
        tabs.replaceSession(old, startShell(cwdOf(old)))
        cwdFiles.remove(old)?.delete()
        keyBarFiles.remove(old)?.delete()
    }

    /** Where [session]'s programs report the key bar they want (may not exist yet). */
    fun keyBarFileOf(session: TerminalSession): File? = keyBarFiles[session]

    fun closeTab(session: TerminalSession) {
        tabs.close(session)
        session.finishIfRunning()
        cwdFiles.remove(session)?.delete()
        keyBarFiles.remove(session)?.delete()
        if (tabs.isEmpty) exit()
    }

    fun onSessionFinished(session: TerminalSession) {
        if (tabs.find(session) != null && closesOnExit(session.exitStatus)) closeTab(session)
    }

    /** Kills every session, stops the service and closes the app. The next start is fresh. */
    fun exit() {
        stateFile.delete()
        killAll()
        activity?.finishAndRemoveTask()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Swaps in an empty model first, so the killed sessions' late exit
    // events find no tab and are ignored.
    private fun killAll() {
        val sessions = tabs.tabs.map { it.session }
        tabs = newTabs()
        sessions.forEach { it.finishIfRunning() }
    }

    private fun newTabs(): Tabs<TerminalSession> = Tabs {
        if (!tabs.isEmpty && tabs.tabs.size != notifiedCount) updateNotification()
        saveTabs()
        activity?.onTabsChanged()
    }

    /**
     * Saves the tabs so they come back after Android kills the app. Runs on
     * every tab change and when the app goes to the background (folders
     * change without tab changes). Never fails: losing tabs beats crashing.
     */
    fun saveTabs() {
        if (tabs.isEmpty) return
        runCatching {
            stateFile.parentFile!!.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(tabs.snapshot(::cwdOf).serialize())
            tmp.renameTo(stateFile)
        }
    }

    private fun cwdOf(session: TerminalSession): String? {
        val reported = runCatching { cwdFiles[session]?.readText()?.trim() }.getOrNull()
        if (!reported.isNullOrEmpty()) knownCwd[session] = reported
        return knownCwd[session]
    }

    // A saved folder that's gone (or unreadable) falls back to /root.
    private fun workDirFor(cwd: String?): String =
        cwd?.takeIf { hostPath(it, rootfs)?.let { host -> File(host).isDirectory } == true } ?: "/root"

    // TerminalSession delivers its output on the Looper of the thread that
    // created it, so this must run on the main thread.
    private fun startShell(cwd: String? = null, openMenu: Boolean = false): TerminalSession {
        val workDir = workDirFor(cwd)
        val shellId = nextShellId++
        val cwdName = "cwd-$shellId"
        val keyBarFileName = "keybar-$shellId"
        cwdDir.mkdirs()
        val libDir = applicationInfo.nativeLibraryDir
        val launch = prootLaunch(ProotPaths(
            proot = "$libDir/libproot.so",
            loader = "$libDir/libproot-loader.so",
            rootfs = RootfsInstaller(filesDir).rootfs.absolutePath,
            tmpDir = File(cacheDir, "proot").apply { mkdirs() }.absolutePath,
        ), workDir = workDir, cwdFile = "$CWD_DIR/$cwdName", openMenu = openMenu, keyBarFile = "$CWD_DIR/$keyBarFileName", toolsDir = tools.tools.absolutePath, requestDir = REQUEST_DIR, fakeProc = writeFakeProc(File(filesDir, "fake-proc"), Runtime.getRuntime().availableProcessors()) { path ->
            runCatching { File(path).inputStream().use { it.read() } }.isSuccess
        })
        return TerminalSession(
            launch.argv.first(),
            filesDir.absolutePath,
            launch.argv.toTypedArray(),
            launch.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            2000,
            client,
        ).also {
            knownCwd[it] = workDir
            cwdFiles[it] = File(cwdDir, cwdName)
            keyBarFiles[it] = File(cwdDir, keyBarFileName)
        }
    }

    // Called while the activity is visible: Android 12+ only allows going
    // foreground then.
    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Tab count changes can happen in the background, so update the
    // notification directly instead of calling startForeground() again.
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        notifiedCount = tabs.tabs.size
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Running terminals", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val exit = PendingIntent.getService(this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(runningTerminalsText(tabs.tabs.size))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Exit", exit).build())
            .build()
    }
}
