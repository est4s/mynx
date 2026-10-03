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
import io.github.est4s.terminal.core.prootLaunch
import io.github.est4s.terminal.core.runningTerminalsText
import io.github.est4s.terminal.core.writeFakeProc
import java.io.File

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
    private val sessions = mutableListOf<TerminalSession>()
    private val client by lazy { SessionClient(this) }

    /** The activity showing the sessions, if any. Sessions must never hold it otherwise. */
    var activity: MainActivity? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exit()
            return START_NOT_STICKY
        }
        showNotification()
        // Android restarting a killed service can't bring the shells back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        super.onDestroy()
    }

    /** The session to show, starting one if none is running. */
    fun currentSession(): TerminalSession = sessions.lastOrNull() ?: newSession()

    fun newSession(): TerminalSession = startShell().also {
        sessions += it
        showNotification()
    }

    /** Replaces a finished session with a fresh shell in the same place. */
    fun restart(old: TerminalSession): TerminalSession {
        val fresh = startShell()
        val i = sessions.indexOf(old)
        if (i >= 0) sessions[i] = fresh else sessions += fresh
        return fresh
    }

    /** Kills every session, stops the service and closes the app. */
    fun exit() {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        activity?.finishAndRemoveTask()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // TerminalSession delivers its output on the Looper of the thread that
    // created it, so this must run on the main thread.
    private fun startShell(): TerminalSession {
        val libDir = applicationInfo.nativeLibraryDir
        val launch = prootLaunch(ProotPaths(
            proot = "$libDir/libproot.so",
            loader = "$libDir/libproot-loader.so",
            rootfs = RootfsInstaller(filesDir).rootfs.absolutePath,
            tmpDir = File(cacheDir, "proot").apply { mkdirs() }.absolutePath,
        ), fakeProc = writeFakeProc(File(filesDir, "fake-proc"), Runtime.getRuntime().availableProcessors()) { path ->
            runCatching { File(path).inputStream().use { it.read() } }.isSuccess
        })
        return TerminalSession(
            launch.argv.first(),
            filesDir.absolutePath,
            launch.argv.toTypedArray(),
            launch.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            2000,
            client,
        )
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Running terminals", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val exit = PendingIntent.getService(this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(runningTerminalsText(sessions.size))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Exit", exit).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
