package io.github.est4s.terminal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import kotlin.system.exitProcess

class MainActivity : Activity() {
    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession
    private val crashFile by lazy { File(filesDir, "last-crash.txt") }

    var textSizePx = 0
        set(value) {
            field = value.coerceIn(dp(6), dp(40))
            terminalView.setTextSize(field)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()

        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(ViewClient(this@MainActivity))
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        textSizePx = dp(12)
        setContentView(terminalView)
        applySystemInsets(terminalView)

        session = startShell()
        terminalView.attachSession(session)
        terminalView.requestFocus()

        showLastCrash()
    }

    override fun onDestroy() {
        super.onDestroy()
        session.finishIfRunning()
    }

    fun startShell(): TerminalSession = TerminalSession(
        "/system/bin/sh",
        filesDir.absolutePath,
        arrayOf("sh"),
        arrayOf(
            "TERM=xterm-256color",
            "HOME=${filesDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "PATH=/system/bin",
        ),
        2000,
        SessionClient(this),
    )

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
