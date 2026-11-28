package io.github.est4s.terminal

import android.content.Context
import android.os.Handler
import android.system.Os
import android.system.OsConstants
import io.github.est4s.terminal.core.DesktopHost
import io.github.est4s.terminal.core.Launch
import io.github.est4s.terminal.core.X11Server
import io.github.est4s.terminal.core.killProot
import io.github.est4s.terminal.core.processPid
import io.github.est4s.terminal.core.stopProot
import java.io.File

/**
 * Runs desktop mode's two processes for core's `Desktop`: the X server,
 * outside proot, and the session, in a proot of its own started by
 * [sessionLaunch] (as the sound server's). The server is Termux:X11's,
 * in the APK as `libXlorie.so` and the assets' `x11/termux-x11.jar`; a
 * build without them has none ([server] is null). Call from [handler]'s thread;
 * each start's `ended` is posted to it when that process ends.
 */
class DesktopProcesses(
    private val context: Context,
    private val handler: Handler,
    private val sessionLaunch: (List<String>) -> Launch,
) : DesktopHost {
    @Volatile private var serverProcess: Process? = null
    @Volatile private var sessionProcess: Process? = null
    private val jar = File(context.filesDir, "x11/termux-x11.jar")
    private val library = File(context.applicationInfo.nativeLibraryDir, "libXlorie.so")

    override val server: X11Server?
        get() = X11Server(library.absolutePath, jar.absolutePath, context.packageName)
            .takeIf { library.isFile && hasJarAsset() }

    private fun hasJarAsset(): Boolean =
        runCatching { context.assets.list("x11")?.contains(JAR_ASSET_NAME) == true }.getOrDefault(false)

    override fun startServer(launch: Launch, workDir: File, log: File, ended: () -> Unit): String? = failure {
        copyJar()
        serverProcess = start(launch, workDir, log, ended)
    }

    // A fresh copy each start (the app may have been updated), read-only:
    // Android 14 refuses to load code from a file it can write.
    private fun copyJar() {
        jar.parentFile?.mkdirs()
        jar.delete()
        context.assets.open("x11/$JAR_ASSET_NAME").use { input -> jar.outputStream().use { input.copyTo(it) } }
        jar.setReadOnly()
    }

    override fun serverRunning(): Boolean = serverProcess?.isAlive == true

    // A plain process, not proot: TERM ends it, and it removes its lock.
    override fun stopServer() {
        serverProcess?.destroy()
        serverProcess = null
    }

    override fun startSession(command: List<String>, log: File, ended: () -> Unit): String? = failure {
        sessionProcess = start(sessionLaunch(command), context.filesDir, log, ended)
    }

    override fun sessionRunning(): Boolean = sessionProcess?.isAlive == true

    // destroy() only sends TERM, which proot ignores.
    override fun stopSession() {
        val process = sessionProcess ?: return
        sessionProcess = null
        val pid = processPid(process.toString())
        stopProot(
            pid,
            signal = Os::kill,
            later = { ms, action -> handler.postDelayed(action, ms) },
            force = {
                if (process.isAlive && pid != null) killProot(pid) { Os.kill(it, OsConstants.SIGKILL) }
            },
        )
    }

    private fun start(launch: Launch, workDir: File, log: File, ended: () -> Unit): Process {
        log.parentFile?.mkdirs()
        val builder = ProcessBuilder(launch.argv).directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
        val env = builder.environment()
        launch.unset.forEach { env.remove(it) }
        env.putAll(launch.env)
        return builder.start().also { process ->
            Thread({
                runCatching { process.waitFor() }
                handler.post(ended)
            }, "desktop-watch").apply { isDaemon = true }.start()
        }
    }

    private fun failure(action: () -> Unit): String? = try {
        action()
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val JAR_ASSET_NAME = "termux-x11.jar"
    }
}
