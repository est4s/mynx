package io.github.est4s.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.github.est4s.terminal.core.RootfsInstaller
import kotlin.concurrent.thread

private const val ROOTFS_ASSET = "debian-rootfs.tar.xz"

class MainActivity : Activity() {
    private lateinit var terminalView: TerminalView
    private var service: TerminalService? = null
    private var bound = false
    private val crashFile by lazy { TerminalApp.crashFile(application) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as TerminalService.LocalBinder).service
            service = s
            s.activity = this@MainActivity
            showTerminal(s.currentSession())
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    var textSizePx = 0
        set(value) {
            field = value.coerceIn(dp(6), dp(40))
            terminalView.setTextSize(field)
        }

    private lateinit var root: FrameLayout
    private val installer by lazy { RootfsInstaller(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leaveOnBack()
        askForNotifications()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applySystemInsets(root)

        if (installer.isInstalled) connectService() else installDebian()
        showLastCrash()
    }

    // Sessions live in the service and keep running after the activity is gone.
    override fun onDestroy() {
        service?.let { if (it.activity === this) it.activity = null }
        service = null
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    // Started from the visible activity: Android 12+ forbids starting a
    // foreground service from the background.
    private fun connectService() {
        val intent = Intent(this, TerminalService::class.java)
        startForegroundService(intent)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    // Like Termux, Back leaves the app without closing the terminals.
    private fun leaveOnBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT) { moveTaskToBack(true) }
        }
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // Only for the "terminals running" notification; everything works without it.
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun showTerminal(session: TerminalSession) {
        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(ViewClient(this@MainActivity))
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        textSizePx = dp(12)
        // TerminalView ignores its own padding, so it sits inside the inset root.
        root.removeAllViews()
        root.addView(terminalView)

        terminalView.attachSession(session)
        terminalView.requestFocus()
    }

    // First launch: unpack the Debian rootfs from the APK. An interrupted
    // install is simply redone next time (see RootfsInstaller).
    private fun installDebian() {
        val status = TextView(this).apply { text = "Setting up Debian…"; styleText() }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(status)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        thread(name = "rootfs-install") {
            try {
                val size = assets.openFd(ROOTFS_ASSET).use { it.length }
                assets.open(ROOTFS_ASSET).use { archive ->
                    installer.install(archive, size) { percent ->
                        runOnUiThread {
                            bar.progress = percent
                            status.text = "Setting up Debian… $percent%"
                        }
                    }
                }
                runOnUiThread { connectService() }
            } catch (e: Exception) {
                runOnUiThread { showInstallError(e) }
            }
        }
    }

    private fun showInstallError(e: Exception) {
        val retry = Button(this).apply {
            text = "Retry"
            setOnClickListener { installDebian() }
        }
        root.removeAllViews()
        root.addView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(retry)
                addView(TextView(this@MainActivity).apply {
                    text = "Setting up Debian failed.\n\n${e.stackTraceToString()}"
                    styleText()
                    setTextIsSelectable(true)
                })
            })
        })
        retry.requestFocus()
    }

    private fun TextView.styleText() {
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor("#FF2BD6"))
    }

    fun restartShell(old: TerminalSession) {
        service?.restart(old)
    }

    /** Shows the selected tab's session. The tab strip (step 2.3) will hook in here too. */
    fun onTabsChanged() {
        val session = service?.tabs?.selected?.session ?: return
        if (::terminalView.isInitialized && terminalView.currentSession !== session) {
            terminalView.attachSession(session)
        }
    }

    fun onScreenUpdated(session: TerminalSession) {
        if (::terminalView.isInitialized && terminalView.currentSession === session) terminalView.onScreenUpdated()
    }

    fun showKeyboard() {
        terminalView.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
    }

    // Since targetSdk 35 the app draws behind the system bars and adjustResize is
    // ignored, so keep the terminal clear of the bars and the keyboard ourselves.
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun showLastCrash() {
        if (!crashFile.exists()) return
        val trace = crashFile.readText()
        crashFile.delete()
        AlertDialog.Builder(this)
            .setTitle("The app crashed last time")
            .setMessage(trace)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
