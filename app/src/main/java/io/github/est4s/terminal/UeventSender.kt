package io.github.est4s.terminal

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.HandlerThread
import io.github.est4s.terminal.core.SerialUevents
import io.github.est4s.terminal.core.ueventSockets
import java.io.File
import java.io.IOException

/**
 * Tells programs watching for USB serial ports (arduino-cli's `board
 * list`) that ports came or went: core's [SerialUevents] to each stand-in
 * uevent socket proot made in [serialDir]. On a thread of its own, so a
 * program that stops reading can't hold up the app; a socket nobody holds
 * any more is deleted.
 */
class UeventSender(private val serialDir: File) {
    private val uevents = SerialUevents()
    private val handler by lazy { Handler(HandlerThread("uevents").apply { start() }.looper) }

    /** [names] are the ports there are now. */
    fun changed(names: Set<String>) {
        handler.post {
            val messages = uevents.changed(names)
            if (messages.isNotEmpty()) ueventSockets(serialDir).forEach { send(it, messages) }
        }
    }

    private fun send(socket: File, messages: List<ByteArray>) {
        try {
            LocalSocket(LocalSocket.SOCKET_DGRAM).use { s ->
                s.soTimeout = 1000
                s.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                messages.forEach { s.outputStream.write(it) }
            }
        } catch (e: IOException) {
            // Refused: its program has gone. A full one (timed out) loses these.
            if (e.message.orEmpty().contains("refused", ignoreCase = true)) socket.delete()
        }
    }
}
