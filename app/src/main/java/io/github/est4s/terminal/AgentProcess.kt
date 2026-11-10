package io.github.est4s.terminal

import android.system.Os
import io.github.est4s.terminal.core.AgentTransport
import io.github.est4s.terminal.core.Launch
import io.github.est4s.terminal.core.ProcessAgentTransport
import io.github.est4s.terminal.core.processPid
import io.github.est4s.terminal.core.stopAgentProot
import java.io.File

/**
 * An agent in a proot of its own, over pipes ([ProcessAgentTransport] does
 * the work). The proot is [launch]'s; [workDir] is the folder it starts in
 * on Android's side (the app's files, as for the sound server).
 */
class AgentProcess(launch: Launch, workDir: File) : AgentTransport by ProcessAgentTransport(
    spawn = {
        val builder = ProcessBuilder(launch.argv).directory(workDir)
        builder.environment().putAll(launch.env)
        builder.start()
    },
    stop = ::stopAgent,
)

// Off the main thread (the transport runs it): reads /proc and waits. No Android Process.pid().
private fun stopAgent(process: Process) {
    val pid = processPid(process.toString())
    if (pid == null) {
        process.destroyForcibly()
        return
    }
    stopAgentProot(pid, signal = Os::kill, alive = { process.isAlive }, sleep = { ms -> Thread.sleep(ms) })
}
