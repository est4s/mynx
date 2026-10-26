package io.github.est4s.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.github.est4s.terminal.core.RawUsbDevice
import io.github.est4s.terminal.core.usbFdReply
import io.github.est4s.terminal.core.usbFdRequest
import io.github.est4s.terminal.core.usbString
import io.github.est4s.terminal.core.writeUsbDevices
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val REQUEST_TIMEOUT_MS = 2000
private const val RETRY_MS = 1000L

/**
 * Raw USB: Android won't let Debian open `/dev/bus/usb/BBB/DDD`, but the
 * fd from `UsbManager.openDevice()` takes the same usbfs ioctls. Serves
 * one on [socket] per request (`BBB/DDD\n` in, `ok\n` with the fd or
 * `err ERRNO\n` out): proot's mynx_usb extension has a program's open of
 * the node ask for it. Keeps the devices the app may use described in
 * [sysBusDir] and their nodes in [devDir] ([writeUsbDevices]), rewritten
 * when Android says a device came, went or was allowed, and on [refresh].
 * What it does goes to [log].
 */
class UsbFds(
    private val context: Context,
    private val socket: File,
    private val sysBusDir: File,
    private val devDir: File,
    private val log: File,
) {
    private val usb = context.getSystemService(UsbManager::class.java)
    // Describing a device opens it: off the main thread, one at a time.
    private val worker = HandlerThread("usb-devices").apply { start() }
    private val handler = Handler(worker.looper)
    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    // Held: LocalSocket's finalizer would close the listening fd under the server.
    private var bound: LocalSocket? = null
    private var described: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "usb-fds", isDaemon = true) { serve() }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // As UsbBridge: the system's broadcasts reach a receiver that isn't exported.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        refresh()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { server?.close() }
        worker.quitSafely()
    }

    /** Describes the devices again if what Android shows changed. Cheap: call it whenever a device may have been allowed. */
    fun refresh() {
        handler.post { runCatching { describe() }.onFailure { note("describing devices: $it") } }
    }

    private fun serve() {
        val listening = try {
            socket.parentFile?.mkdirs()
            socket.delete()
            val made = LocalSocket(LocalSocket.SOCKET_STREAM)
            made.bind(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
            bound = made
            LocalServerSocket(made.fileDescriptor)
        } catch (e: IOException) {
            note("can't listen on ${socket.path}: $e")
            return
        }
        server = listening
        while (running) {
            val client = try {
                listening.accept()
            } catch (e: IOException) {
                if (running) note("accept: $e")
                Thread.sleep(RETRY_MS)
                continue
            }
            runCatching { answer(client) }.onFailure { note("answering: $it") }
            runCatching { client.close() }
        }
    }

    private fun answer(client: LocalSocket) {
        // Only Debian's programs: they run as the app.
        if (client.peerCredentials.uid != Process.myUid()) return
        client.soTimeout = REQUEST_TIMEOUT_MS
        val line = StringBuilder()
        val input = client.inputStream
        while (line.length < 16 && !line.endsWith("\n")) {
            val c = input.read()
            if (c < 0) break
            line.append(c.toChar())
        }
        val out = client.outputStream
        val name = usbFdRequest(line.toString()) ?: return out.write("err 22\n".toByteArray())
        val device = usb.deviceList.values.firstOrNull { it.deviceName == name }
        val reply = usbFdReply(present = device != null, allowed = device != null && usb.hasPermission(device))
        if (device == null || reply != "ok\n") {
            note("$name: ${reply.trim()}")
            return out.write(reply.toByteArray())
        }
        val connection = usb.openDevice(device)
        if (connection == null) {
            note("$name: Android gave no connection")
            return out.write("err 5\n".toByteArray())
        }
        // The program gets its own copy; ours closes once it's sent.
        try {
            ParcelFileDescriptor.fromFd(connection.fileDescriptor).use { copy ->
                // Android read the descriptors through it, and the copy shares
                // the offset: back to the start, where Linux's open leaves it.
                Os.lseek(copy.fileDescriptor, 0, OsConstants.SEEK_SET)
                client.setFileDescriptorsForSend(arrayOf(copy.fileDescriptor))
                out.write(reply.toByteArray())
                out.flush()
            }
        } finally {
            connection.close()
        }
    }

    private fun describe() {
        val devices = usb.deviceList.values.sortedBy { it.deviceName }
        val state = devices.joinToString(" ") { "${it.deviceName}=${usb.hasPermission(it)}" }
        if (state == described) return
        val raw = devices.filter { usb.hasPermission(it) }.mapNotNull { d ->
            val connection = usb.openDevice(d) ?: return@mapNotNull null
            try {
                RawUsbDevice(
                    name = d.deviceName,
                    descriptors = connection.rawDescriptors ?: return@mapNotNull null,
                    manufacturer = usbString(runCatching { d.manufacturerName }.getOrNull()),
                    product = usbString(runCatching { d.productName }.getOrNull()),
                    serial = usbString(runCatching { d.serialNumber }.getOrNull()),
                    interfaceNames = (0 until d.interfaceCount).map(d::getInterface)
                        .filter { it.alternateSetting == 0 }
                        .mapNotNull { i -> usbString(i.name)?.let { i.id to it } }
                        .toMap(),
                )
            } finally {
                connection.close()
            }
        }
        writeUsbDevices(sysBusDir, devDir, raw)
        described = state
        note("devices: ${state.ifEmpty { "none" }}")
    }

    private fun note(line: String) {
        runCatching {
            log.parentFile?.mkdirs()
            log.appendText(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + line + "\n")
        }
    }
}
