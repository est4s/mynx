package io.github.est4s.terminal.core

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The first TCP port a serial port is served on (`rfc2217://localhost:2217`); more ports take the next ones. */
const val FIRST_SERIAL_TCP_PORT = 2217
// What waits for a slow client before the port's data is dropped.
private const val MAX_QUEUED_BYTES = 1 shl 20
private val END = ByteArray(0)

/**
 * Serves [line] over RFC 2217 on localhost [port] (0: any free one), to
 * one client at a time: others are refused while one is connected. A
 * client leaving drops RTS and DTR, as closing a port does on Linux.
 * The port's [settings] outlast clients. The port's data goes to
 * [fromLine]; with no client it's dropped.
 */
class SerialServer(private val line: SerialLine, port: Int) : Closeable {
    // 127.0.0.1, which the Debian side connects to: Android's getLoopbackAddress() is ::1.
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val address: InetAddress get() = server.inetAddress

    @Volatile var settings = LineSettings()
        private set
    @Volatile private var client: Client? = null
    @Volatile private var closed = false

    /** Whether a client is connected. */
    val busy: Boolean get() = client != null

    fun start() {
        thread(name = "serial-server-$port", isDaemon = true) {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(this) {
                    if (client != null || closed) runCatching { socket.close() }
                    else client = Client(socket).also { it.start() }
                }
            }
        }
    }

    fun fromLine(data: ByteArray, length: Int) {
        client?.session?.fromLine(data, length)
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        client?.close()
    }

    private inner class Client(private val socket: Socket) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicInteger()
        // fromLine only escapes and sends, so the port's thread may call it
        // while the reader runs the session; writes go through one thread so
        // neither waits on the network.
        val session = Rfc2217Session(line, settings) { bytes ->
            if (queued.addAndGet(bytes.size) <= MAX_QUEUED_BYTES) queue.put(bytes) else queued.addAndGet(-bytes.size)
        }

        fun start() {
            runCatching { socket.tcpNoDelay = true }
            session.greet()
            thread(name = "serial-write-$port", isDaemon = true) { writeLoop() }
            thread(name = "serial-read-$port", isDaemon = true) { readLoop() }
        }

        private fun writeLoop() {
            runCatching {
                val out = socket.getOutputStream()
                while (true) {
                    val bytes = queue.take()
                    if (bytes === END) break
                    queued.addAndGet(-bytes.size)
                    out.write(bytes)
                }
            }
            close()
        }

        private fun readLoop() {
            runCatching {
                val input = socket.getInputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    session.fromNetwork(buffer, n)
                    settings = session.settings
                }
            }
            close()
        }

        fun close() {
            synchronized(this@SerialServer) {
                if (client !== this) return
                client = null
            }
            queue.put(END)
            runCatching { socket.close() }
            // RTS first: DTR off with RTS on is how an ESP32's auto-reset (and a
            // C3's USB-Serial-JTAG) reset it, into the bootloader after DTR on.
            runCatching { line.setRts(false) }
            runCatching { line.setDtr(false) }
        }
    }
}
