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
import android.os.IBinder
import com.termux.terminal.TerminalSession
import io.github.est4s.terminal.core.ProotPaths
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.Tabs
import io.github.est4s.terminal.core.childPid
import io.github.est4s.terminal.core.closesOnExit
import io.github.est4s.terminal.core.guestPath
import io.github.est4s.terminal.core.hostPath
import io.github.est4s.terminal.core.parentPid
import io.github.est4s.terminal.core.parseSavedTabs
import io.github.est4s.terminal.core.restore
import io.github.est4s.terminal.core.serialize
import io.github.est4s.terminal.core.snapshot
import io.github.est4s.terminal.core.prootLaunch
import io.github.est4s.terminal.core.runningTerminalsText
import io.github.est4s.terminal.core.writeFakeProc
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.WeakHashMap

private const val CHANNEL_ID = "terminals"
private const val NOTIFICATION_ID = 1
private const val ACTION_EXIT = "io.github.est4s.terminal.EXIT"

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
    private val isRootfs = { path: String ->
        runCatching { Files.isSameFile(Paths.get(path), Paths.get(rootfs)) }.getOrDefault(false)
    }
    // TEMPORARY (step 2.5 debugging): how each folder lookup went, readable
    // from Debian at /storage/emulated/0/Android/data/<app id>/files/cwd-debug.txt.
    private val cwdTrace = StringBuilder()
    // Last folder known for each session: where it started, or where its shell
    // was when last asked. Restored tabs only start when first shown, so this
    // keeps their folder until then.
    private val knownCwd = WeakHashMap<TerminalSession, String>()

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
        killAll()
        super.onDestroy()
    }

    /** The session to show: the selected tab, else the saved tabs, else a new tab. */
    fun currentSession(): TerminalSession {
        tabs.selected?.let { return it.session }
        val saved = runCatching { parseSavedTabs(stateFile.readText()) }.getOrNull()
            ?: return newSession()
        tabs.restore(saved) { cwd -> startShell(cwd) }
        return tabs.selected!!.session
    }

    fun newSession(): TerminalSession = startShell().also { tabs.open(it) }

    /** Replaces a finished session with a fresh shell in the same tab and folder. */
    fun restart(old: TerminalSession) {
        tabs.replaceSession(old, startShell(knownCwd[old]))
    }

    fun closeTab(session: TerminalSession) {
        tabs.close(session)
        session.finishIfRunning()
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
            cwdTrace.setLength(0)
            cwdTrace.append("rootfs=$rootfs\n")
            tmp.writeText(tabs.snapshot(::cwdOf).serialize())
            tmp.renameTo(stateFile)
            getExternalFilesDir(null)?.let { File(it, "cwd-debug.txt").writeText(cwdTrace.toString()) }
        }
    }

    // The shell is proot's child; its /proc cwd link is the host path.
    private fun cwdOf(session: TerminalSession): String? {
        // pid is 0 before a session starts (restored tabs not shown yet) and -1 after it exits.
        if (session.pid <= 0) {
            cwdTrace.append("pid=${session.pid} known=${knownCwd[session]}\n")
            return knownCwd[session]
        }
        val current = runCatching {
            val stats = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
                val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                runCatching { pid to File(dir, "stat").readText() }.getOrNull()
            }.toMap()
            cwdTrace.append("pid=${session.pid} stats=${stats.size} children=")
            cwdTrace.append(stats.filterValues { parentPid(it) == session.pid }.values.joinToString(" | ") { it.take(40) })
            val shell = childPid(session.pid, stats) ?: return@runCatching null
            val link = Files.readSymbolicLink(File("/proc/$shell/cwd").toPath()).toString()
            cwdTrace.append(" shell=$shell link=$link")
            guestPath(link, isRootfs)
        }.onFailure { cwdTrace.append(" error=$it") }.getOrNull()
        cwdTrace.append(" -> $current\n")
        if (current != null) knownCwd[session] = current
        return knownCwd[session]
    }

    // A saved folder that's gone (or unreadable) falls back to /root.
    private fun workDirFor(cwd: String?): String =
        cwd?.takeIf { hostPath(it, rootfs)?.let { host -> File(host).isDirectory } == true } ?: "/root"

    // TerminalSession delivers its output on the Looper of the thread that
    // created it, so this must run on the main thread.
    private fun startShell(cwd: String? = null): TerminalSession {
        val workDir = workDirFor(cwd)
        val libDir = applicationInfo.nativeLibraryDir
        val launch = prootLaunch(ProotPaths(
            proot = "$libDir/libproot.so",
            loader = "$libDir/libproot-loader.so",
            rootfs = RootfsInstaller(filesDir).rootfs.absolutePath,
            tmpDir = File(cacheDir, "proot").apply { mkdirs() }.absolutePath,
        ), workDir = workDir, fakeProc = writeFakeProc(File(filesDir, "fake-proc"), Runtime.getRuntime().availableProcessors()) { path ->
            runCatching { File(path).inputStream().use { it.read() } }.isSuccess
        })
        return TerminalSession(
            launch.argv.first(),
            filesDir.absolutePath,
            launch.argv.toTypedArray(),
            launch.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            2000,
            client,
        ).also { knownCwd[it] = workDir }
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
