package io.github.est4s.terminal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
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
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.github.est4s.terminal.core.RootfsInstaller
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val ROOTFS_ASSET = "debian-rootfs.tar.xz"

class MainActivity : Activity() {
    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession
    private val crashFile by lazy { File(filesDir, "last-crash.txt") }

    var textSizePx = 0
        set(value) {
            field = value.coerceIn(dp(6), dp(40))
            terminalView.setTextSize(field)
        }

    private lateinit var root: FrameLayout
    private val installer by lazy { RootfsInstaller(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applySystemInsets(root)

        if (installer.isInstalled) showTerminal() else installDebian()
        showLastCrash()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized) session.finishIfRunning()
    }

    private fun showTerminal() {
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

        session = startShell()
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
                runOnUiThread { showTerminal() }
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

    // Until Debian starts (step 1.5), proot is reachable from this Android shell
    // for testing: `libproot.so --version`.
    fun startShell(): TerminalSession {
        val libDir = applicationInfo.nativeLibraryDir
        val prootTmp = File(cacheDir, "proot").apply { mkdirs() }
        return TerminalSession(
            "/system/bin/sh",
            filesDir.absolutePath,
            arrayOf("sh"),
            arrayOf(
                "TERM=xterm-256color",
                "HOME=${filesDir.absolutePath}",
                "TMPDIR=${cacheDir.absolutePath}",
                "PATH=/system/bin:$libDir",
                "PROOT_LOADER=$libDir/libproot-loader.so",
                "PROOT_TMP_DIR=${prootTmp.absolutePath}",
            ),
            2000,
            SessionClient(this),
        )
    }

    fun restartShell() {
        session = startShell()
        terminalView.attachSession(session)
    }

    fun onScreenUpdated() = terminalView.onScreenUpdated()

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

    // There's no logcat on the dev phone: save crashes and show them on next launch.
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile.writeText(e.stackTraceToString()) }
            previous?.uncaughtException(thread, e) ?: exitProcess(1)
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
