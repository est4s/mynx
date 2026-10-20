package io.github.est4s.terminal

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.HandlerThread
import io.github.est4s.terminal.core.SerialUevents
import io.github.est4s.terminal.core.ueventSockets
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tells programs watching for USB serial ports (arduino-cli's `board
 * list`) that ports came or went: core's [SerialUevents] to each stand-in
 * uevent socket proot made in [serialDir]. On a thread of its own, so a
 * program that stops reading can't hold up the app; a socket nobody holds
 * any more is deleted. What it sends, and why it couldn't, goes to [log]
 * (in Debian, beside the port helpers' logs).
 */
class UeventSender(private val serialDir: File, private val log: File) {
    private val uevents = SerialUevents()
    private val handler by lazy { Handler(HandlerThread("uevents").apply { start() }.looper) }

    /** [names] are the ports there are now. */
    fun changed(names: Set<String>) {
        handler.post {
            val messages = uevents.changed(names)
            if (messages.isEmpty()) return@post
            val sockets = ueventSockets(serialDir)
            note("ports ${names.sorted()}: ${messages.size} message(s) to ${sockets.size} socket(s)")
            sockets.forEach { send(it, messages) }
        }
    }

    private fun send(socket: File, messages: List<ByteArray>) {
        try {
            LocalSocket(LocalSocket.SOCKET_DGRAM).use { s ->
                s.soTimeout = 1000
                s.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                messages.forEach { s.outputStream.write(it) }
            }
            note("${socket.name}: sent")
        } catch (e: Exception) {
            // Refused: its program has gone. A full one (timed out) loses these.
            val gone = e is IOException && e.message.orEmpty().contains("refused", ignoreCase = true)
            if (gone) socket.delete()
            note("${socket.name}: ${if (gone) "gone, deleted" else "${e.javaClass.simpleName}: ${e.message}"}")
        }
    }

    private fun note(line: String) {
        runCatching {
            log.parentFile?.mkdirs()
            log.appendText(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + line + "\n")
        }
    }
}
