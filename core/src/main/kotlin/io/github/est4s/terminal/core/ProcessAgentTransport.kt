package io.github.est4s.terminal.core

import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** How much of stderr [ProcessAgentTransport] keeps for [AgentTransport.Listener.onExit]. */
const val AGENT_STDERR_TAIL = 4000
private const val READER_WAIT_MS = 2000L
private const val STDERR_WAIT_MS = 500L
private val WRITER_END = Any()

/**
 * [AgentTransport] over a child process's pipes, as its KDoc asks. The
 * spawning and stopping are the app's: [spawn] runs off the caller's
 * thread and may throw, [stop] runs on a thread of its own, may block and
 * must end the process (see [stopAgentProot]). Everything here is plain
 * threads over a [Process], so it's tested with real shells.
 */
class ProcessAgentTransport(
    private val spawn: () -> Process,
    private val stop: (Process) -> Unit,
) : AgentTransport {
    private val queue = LinkedBlockingQueue<Any>()
    private val stateLock = Any()
    private val callbackLock = Any()
    private val tail = StringBuilder()
    private var process: Process? = null
    private var closing = false
    @Volatile private var ended = false
    private lateinit var listener: AgentTransport.Listener

    override fun start(listener: AgentTransport.Listener) {
        this.listener = listener
        thread(name = "agent-process", isDaemon = true) { run() }
    }

    override val accepting: Boolean
        get() = !ended && synchronized(stateLock) { !closing && process?.isAlive != false }

    override fun writeLine(line: String) {
        if (!ended) queue.offer(line)
    }

    override fun close() {
        val running = synchronized(stateLock) {
            if (closing) return
            closing = true
            process
        }
        queue.offer(WRITER_END)
        running?.let(::stopInBackground)
    }

    private fun stopInBackground(process: Process) {
        thread(name = "agent-stop", isDaemon = true) { runCatching { stop(process) } }
    }

    private fun run() {
        val child = try {
            spawn()
        } catch (e: Exception) {
            finish(null, e.message ?: e.toString())
            return
        }
        val stopNow = synchronized(stateLock) {
            process = child
            closing
        }
        if (stopNow) stopInBackground(child)
        val reader = thread(name = "agent-out", isDaemon = true) { runCatching { readLines(child) } }
        val errors = thread(name = "agent-err", isDaemon = true) { runCatching { drainErrors(child) } }
        thread(name = "agent-in", isDaemon = true) { runCatching { writeLines(child) } }
        val code = runCatching { child.waitFor() }.getOrDefault(-1)
        // A program left behind may hold the pipes open for long: don't wait for it.
        runCatching {
            reader.join(READER_WAIT_MS)
            errors.join(STDERR_WAIT_MS)
        }
        finish(code, synchronized(tail) { tail.toString() })
    }

    private fun readLines(child: Process) {
        child.inputStream.bufferedReader(Charsets.UTF_8).use { lines ->
            while (true) {
                val line = lines.readLine() ?: break
                synchronized(callbackLock) {
                    if (ended) return
                    listener.onLine(line)
                }
            }
        }
    }

    private fun drainErrors(child: Process) {
        child.errorStream.bufferedReader(Charsets.UTF_8).use { errors ->
            val chunk = CharArray(4096)
            while (true) {
                val count = errors.read(chunk)
                if (count < 0) break
                synchronized(tail) {
                    tail.append(chunk, 0, count)
                    if (tail.length > AGENT_STDERR_TAIL) tail.delete(0, tail.length - AGENT_STDERR_TAIL)
                }
            }
        }
    }

    private fun writeLines(child: Process) {
        val out = child.outputStream
        while (true) {
            val item = queue.take()
            if (item === WRITER_END) return
            out.write((item as String + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun finish(code: Int?, detail: String) {
        val first = synchronized(callbackLock) {
            val wasEnded = ended
            ended = true
            !wasEnded
        }
        if (!first) return
        queue.offer(WRITER_END)
        runCatching { listener.onExit(code, detail) }
    }
}
