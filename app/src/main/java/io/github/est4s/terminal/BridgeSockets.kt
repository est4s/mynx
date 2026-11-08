package io.github.est4s.terminal

import io.github.est4s.terminal.core.BridgeSocket
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val CONNECT_TIMEOUT_MS = 3000
private const val READ_BUFFER = 4096

/**
 * The bridge's sockets to serial ports' RFC 2217 servers on 127.0.0.1
 * (`BridgeHost.connectSerial`): each connects, reads and writes on
 * threads of its own; core speaks RFC 2217 over it.
 */
object BridgeSockets {
    /** Connects to 127.0.0.1:[tcpPort] off the main thread; the callbacks come on its threads. */
    fun connect(
        tcpPort: Int,
        received: (ByteArray) -> Unit,
        closed: (String?) -> Unit,
        ready: (Result<BridgeSocket>) -> Unit,
    ) {
        thread(name = "bridge-serial-$tcpPort", isDaemon = true) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", tcpPort), CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                runCatching { socket.close() }
                ready(Result.failure(IOException("couldn't connect to the port's server (127.0.0.1:$tcpPort): ${e.message ?: e.javaClass.simpleName}", e)))
                return@thread
            }
            val link = SerialSocket(socket, closed)
            ready(Result.success(link))
            link.run(received)
        }
    }
}

// Nothing is called back after close(); bytes queued before it are still written.
private class SerialSocket(private val socket: Socket, private val closed: (String?) -> Unit) : BridgeSocket {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val ended = AtomicBoolean(false)
    private val stop = ByteArray(0)

    override fun send(bytes: ByteArray) {
        if (!ended.get() && bytes.isNotEmpty()) queue.offer(bytes)
    }

    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        queue.offer(stop)
    }

    fun run(received: (ByteArray) -> Unit) {
        thread(name = "bridge-serial-write", isDaemon = true) { write() }
        val buffer = ByteArray(READ_BUFFER)
        val why = try {
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0 && !ended.get()) received(buffer.copyOf(n))
            }
            null
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        }
        end(why)
    }

    private fun write() {
        try {
            val output = socket.getOutputStream()
            while (true) {
                val bytes = queue.take()
                if (bytes === stop) break
                output.write(bytes)
                output.flush()
            }
        } catch (e: IOException) {
            end(e.message ?: e.javaClass.simpleName)
        } catch (e: InterruptedException) {
            // Leaving.
        }
        runCatching { socket.close() }
    }

    // The other side closed or failed: once, unless close() came first.
    private fun end(why: String?) {
        runCatching { socket.close() }
        if (!ended.compareAndSet(false, true)) return
        queue.offer(stop)
        closed(why)
    }
}
