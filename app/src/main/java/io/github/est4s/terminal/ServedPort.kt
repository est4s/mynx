package io.github.est4s.terminal

import android.os.Handler
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.est4s.terminal.core.Launch
import io.github.est4s.terminal.core.SerialPortInfo
import io.github.est4s.terminal.core.SerialServer
import io.github.est4s.terminal.core.ServerRestarts
import io.github.est4s.terminal.core.killProot
import io.github.est4s.terminal.core.processPid
import io.github.est4s.terminal.core.serialWaitingMark
import io.github.est4s.terminal.core.stopProot
import java.io.File
import kotlin.concurrent.thread

/**
 * What every served port needs, whatever drives it (USB, Bluetooth): the
 * ports folder [serialDir] the helpers link into, their logs in [logDir],
 * and [launchHelper] for `serial-port NAME TCPPORT`.
 */
class PortHelpers(
    val handler: Handler,
    val serialDir: File,
    val logDir: File,
    val workDir: File,
    val launchHelper: (name: String, tcpPort: Int) -> Launch,
)

/**
 * A port served to Debian as [name]: [server] over RFC 2217, and its
 * pseudo-terminal helper, started again if it ends while the port is
 * open. The bridge that made it feeds [server] the line's data and
 * closes its line after [close].
 */
class ServedPort(val name: String, val server: SerialServer, private val helpers: PortHelpers) {
    @Volatile var closed = false
        private set
    @Volatile private var process: Process? = null

    fun start() {
        server.start()
        thread(name = "serial-port-$name", isDaemon = true) { runHelper() }
    }

    fun info() = SerialPortInfo(name, server.port, server.busy, File(helpers.serialDir, serialWaitingMark(name)).exists())

    private fun runHelper() {
        val restarts = ServerRestarts()
        while (!closed) {
            val started = SystemClock.elapsedRealtime()
            val code = runCatching {
                helpers.logDir.mkdirs()
                val launch = helpers.launchHelper(name, server.port)
                val builder = ProcessBuilder(launch.argv).directory(helpers.workDir)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(helpers.logDir, "$name.log")))
                builder.environment().putAll(launch.env)
                val p = builder.start()
                process = p
                if (closed) stopHelper(p)
                p.waitFor()
            }.getOrDefault(-1)
            if (closed) break
            val delay = restarts.after(code, SystemClock.elapsedRealtime() - started) ?: break
            Thread.sleep(delay)
        }
    }

    // destroyForcibly() only sends TERM, which proot ignores.
    private fun stopHelper(p: Process) {
        val pid = processPid(p.toString())
        stopProot(
            pid,
            signal = Os::kill,
            later = { ms, action -> helpers.handler.postDelayed(action, ms) },
            force = { if (p.isAlive && pid != null) killProot(pid) { Os.kill(it, OsConstants.SIGKILL) } },
        )
    }

    fun close() {
        closed = true
        process?.let(::stopHelper)
        server.close()
        // The helper is killed, so its link stays: take it away here.
        File(helpers.serialDir, name).delete()
    }
}
