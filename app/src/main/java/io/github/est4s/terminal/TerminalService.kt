package io.github.est4s.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.github.est4s.terminal.core.Notice
import io.github.est4s.terminal.core.locationRequests
import io.github.est4s.terminal.core.PocketRequests
import io.github.est4s.terminal.core.Share
import io.github.est4s.terminal.core.shareType
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
private const val NOTICE_CHANNEL_ID = "notices"
// `pocket notify` notifications: one per tab, so a new one replaces the last.
private const val NOTICE_ID_BASE = 1000
const val EXTRA_SHELL = "io.github.est4s.terminal.SHELL"
private const val ACTION_EXIT = "io.github.est4s.terminal.EXIT"
private const val CWD_DIR = "/tmp/.pocket-terminal"
private const val REQUEST_DIR = "$CWD_DIR/requests"
private const val TOOLS_ASSET = "tools.tar.xz"
// How often to check whether waiting requests' `pocket`s have gone.
private const val SWEEP_MS = 2000L
// Requests after which the app applies the config files again.
private val RELOADING_REQUESTS = setOf(
    "check", "set", "reset", "theme-set", "theme-reset", "preview-end", "keybar-edit", "keybar-reset", "undo",
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
    private val requests by lazy {
        PocketRequests(
            requestDir, File(rootfs, "root"),
            notify = ::showNotice, openUrl = ::openLink, installApk = ::installApk,
            vibrate = ::vibrate, setClipboard = ::setClipboard, readClipboard = ::readClipboard,
            share = ::share,
            later = locationRequests(File(rootfs, "root"), locator::locate),
        )
    }
    private val locator by lazy {
        Locator(
            this, mainHandler,
            onScreen = { activity?.onScreen == true },
            askPermission = { answer -> activity?.takeIf { it.onScreen }?.askForLocation(answer) != null },
            locatingChanged = ::setLocating,
        )
    }
    // Whether the foreground service has the location type: only while
    // something is locating, so a stream keeps going in the background.
    private var locating = false
    private val sweep = Runnable { sweepRequests() }
    // The POCKET_SHELL number of each session, for `pocket notify`.
    private val shellIds = WeakHashMap<TerminalSession, Int>()
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
        requests.start()
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
                when {
                    path?.endsWith(".req") == true -> mainHandler.post { processRequests() }
                    path?.endsWith(".cancel") == true -> mainHandler.post { sweepRequests() }
                }
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
        scheduleSweep()
    }

    // Stops waiting requests that `pocket` cancelled or whose `pocket` has gone.
    private fun sweepRequests() {
        runCatching { requests.sweep() }
        scheduleSweep()
    }

    private fun scheduleSweep() {
        mainHandler.removeCallbacks(sweep)
        if (requests.hasOpen()) mainHandler.postDelayed(sweep, SWEEP_MS)
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
        mainHandler.removeCallbacks(sweep)
        locator.stopAll()
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
        clearNoticeOfShownTab()
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
        ), shellId = shellId, workDir = workDir, cwdFile = "$CWD_DIR/$cwdName", openMenu = openMenu, keyBarFile = "$CWD_DIR/$keyBarFileName", toolsDir = tools.tools.absolutePath, requestDir = REQUEST_DIR, fakeProc = writeFakeProc(File(filesDir, "fake-proc"), Runtime.getRuntime().availableProcessors()) { path ->
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
            shellIds[it] = shellId
            cwdFiles[it] = File(cwdDir, cwdName)
            keyBarFiles[it] = File(cwdDir, keyBarFileName)
        }
    }

    private fun sessionOfShell(id: Int): TerminalSession? =
        tabs.tabs.map { it.session }.firstOrNull { shellIds[it] == id }

    /** Selects the tab of shell [id] (from a tapped notification), if it's still open. */
    fun selectShell(id: Int) {
        val session = sessionOfShell(id) ?: return
        tabs.select(tabs.tabs.indexOfFirst { it.session === session })
    }

    /** Removes the notification of the tab on screen: the user has seen it. */
    fun clearNoticeOfShownTab() {
        if (activity?.onScreen != true) return
        val id = tabs.selected?.session?.let { shellIds[it] } ?: return
        getSystemService(NotificationManager::class.java).cancel(NOTICE_ID_BASE + id)
    }

    /** Opens a web link in the phone's browser: null if it did, else why not. */
    fun openLink(url: String): String? {
        val shown = activity?.takeIf { it.onScreen }
            ?: return "the app must be on screen to open links (the link: $url)"
        return try {
            shown.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            null
        } catch (e: ActivityNotFoundException) {
            "no app on the phone opens links"
        }
    }

    // Answers `pocket vibrate`: null when it vibrated, else why not.
    private fun vibrate(ms: Long): String? {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return "the phone has no vibrator"
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return null
    }

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    // Answers `pocket clipboard set`: null when copied, else why not.
    private fun setClipboard(text: String): String? = try {
        clipboard.setPrimaryClip(ClipData.newPlainText("pocket clipboard", text))
        null
    } catch (e: RuntimeException) {
        "couldn't copy to the clipboard: ${e.message ?: e.javaClass.simpleName}"
    }

    // Answers `pocket clipboard get`. Android only lets the app in front read
    // the clipboard (it gives others nothing), so say that instead.
    private fun readClipboard(): Result<String> {
        if (activity?.onScreen != true) {
            return Result.failure(Exception("the app must be on screen to read the clipboard (Android only lets the app in front read it)"))
        }
        val clip = clipboard.primaryClip
        val text = if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        return Result.success(text)
    }

    // Answers `pocket share`: null when the share sheet opened, else why not.
    private fun share(share: Share): String? {
        val shown = activity?.takeIf { it.onScreen }
            ?: return "the app must be on screen to share (Android only lets the app in front open the share sheet)"
        val send = if (share.text != null) {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share.text)
        } else {
            val uris = ShareProvider.uris(share.files)
            val type = shareType(share.files.map(ShareProvider::typeOf))
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            // ClipData carries the read grant through the chooser to the chosen app.
            val clip = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = clip }
        }
        return try {
            shown.startActivity(Intent.createChooser(send, null))
            null
        } catch (e: ActivityNotFoundException) {
            "no app on the phone takes shares"
        }
    }

    // Answers `pocket install-apk`: null when the installer opened, else why not.
    private fun installApk(apk: File): String? {
        if (!BuildConfig.DEBUG) return "only debug builds of the app can install apps"
        val shown = activity?.takeIf { it.onScreen }
            ?: return "the app must be on screen to open the installer"
        if (!packageManager.canRequestPackageInstalls()) {
            shown.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return "allow the app to install apps (Android's settings are open), then try again"
        }
        ApkProvider.apk = apk
        return try {
            shown.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ApkProvider.uri, ApkProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            null
        } catch (e: ActivityNotFoundException) {
            "no app on the phone installs apps"
        }
    }

    // Answers `pocket notify`: null when shown, else why not.
    private fun showNotice(notice: Notice): String? {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "the app's notifications are off in Android's settings"
        val session = notice.shell?.let { sessionOfShell(it) }
        if (notice.ifAway && activity?.onScreen == true && (session == null || tabs.selected?.session === session)) {
            return if (session == null) "the app is on screen" else "that tab is on screen"
        }
        manager.createNotificationChannel(
            NotificationChannel(NOTICE_CHANNEL_ID, "Notifications from programs", NotificationManager.IMPORTANCE_HIGH))
        val id = NOTICE_ID_BASE + (if (session != null) notice.shell!! else 0)
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (session != null) putExtra(EXTRA_SHELL, notice.shell) }
        val tap = PendingIntent.getActivity(this, id, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id, Notification.Builder(this, NOTICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
        return null
    }

    // Called while the activity is visible: Android 12+ only allows going
    // foreground then.
    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            val location = if (locating) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or location)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Locating starts on screen, where Android allows adding the location
    // type; dropping it may happen in the background. Before API 34 the
    // service has every type in the manifest anyway.
    private fun setLocating(on: Boolean) {
        locating = on
        if (Build.VERSION.SDK_INT >= 34) runCatching { goForeground() }
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
