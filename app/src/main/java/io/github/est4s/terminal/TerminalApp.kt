package io.github.est4s.terminal

import android.app.Application
import java.io.File
import kotlin.system.exitProcess

class TerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
    }

    // There's no logcat on the dev phone: save crashes (from the activity or the
    // service) and let MainActivity show them on next launch.
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile(this).writeText(e.stackTraceToString()) }
            previous?.uncaughtException(thread, e) ?: exitProcess(1)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last-crash.txt")
    }
}
