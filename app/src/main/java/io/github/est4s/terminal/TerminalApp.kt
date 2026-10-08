package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.Crashes
import kotlin.system.exitProcess

class TerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
    }

    // Save crashes (from the activity or the service): MainActivity shows them
    // on the next launch, and `mynx report` puts the last one in a bug report.
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashes(this).save(e.stackTraceToString()) }
            previous?.uncaughtException(thread, e) ?: exitProcess(1)
        }
    }

    companion object {
        fun crashes(app: Application) = Crashes(app.filesDir)
    }
}
