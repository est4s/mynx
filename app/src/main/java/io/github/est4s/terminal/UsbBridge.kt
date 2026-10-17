package io.github.est4s.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.github.est4s.terminal.core.FlowControl
import io.github.est4s.terminal.core.Launch
import io.github.est4s.terminal.core.LineSettings
import io.github.est4s.terminal.core.Parity
import io.github.est4s.terminal.core.SerialLine
import io.github.est4s.terminal.core.SerialPortInfo
import io.github.est4s.terminal.core.SerialServer
import io.github.est4s.terminal.core.ServerRestarts
import io.github.est4s.terminal.core.StopBits
import io.github.est4s.terminal.core.UsbSerialDevice
import io.github.est4s.terminal.core.killProot
import io.github.est4s.terminal.core.processPid
import io.github.est4s.terminal.core.stopProot
import io.github.est4s.terminal.core.tcpPorts
import io.github.est4s.terminal.core.ttyName
import io.github.est4s.terminal.core.usbDriverName
import io.github.est4s.terminal.core.usbIds
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

private const val ACTION_USB_PERMISSION = "io.github.est4s.terminal.USB_PERMISSION"
private const val WRITE_TIMEOUT_MS = 5000
// Reads kept queued on each port, as Linux's cdc-acm keeps 16.
private const val READ_QUEUE = 16

/**
 * The USB serial bridge: opens the serial devices Android lets the app
 * use (through usb-serial-for-android), serves each port over RFC 2217
 * on localhost ([SerialServer]) and runs its pseudo-terminal helper in
 * Debian ([launchHelper], `serial-port NAME TCPPORT`), whose link lives
 * in [serialDir]. Helpers' output goes to [logDir]. [ready] is told when
 * a device's ports are served, [gone] when a device left. Call from
 * [handler]'s (the main) thread.
 */
class UsbBridge(
    private val context: Context,
    private val handler: Handler,
    private val serialDir: File,
    private val logDir: File,
    private val workDir: File,
    private val launchHelper: (name: String, tcpPort: Int) -> Launch,
    private val ready: (UsbSerialDevice) -> Unit,
    private val gone: (String) -> Unit,
) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()
    private val open = linkedMapOf<String, OpenDevice>()
    private val errors = mutableMapOf<String, String>()
    private val asking = mutableMapOf<String, MutableList<(String?) -> Unit>>()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = deviceOf(intent)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device != null) answerAsking(device.deviceName, granted)
            } else {
                refresh()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        // Links from an earlier run point at terminals that are gone.
        serialDir.deleteRecursively()
        serialDir.mkdirs()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // Attach and detach are the system's broadcasts, which reach a
        // receiver that isn't exported; the permission answer is our own.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        open.keys.toList().forEach(::close)
        asking.keys.toList().forEach { answerAsking(it, false) }
    }

    /** Opens what became allowed, closes what left. Cheap enough to call whenever the app comes on screen. */
    fun refresh() {
        if (!started) return
        val drivers = runCatching { prober.findAllDrivers(usb) }.getOrDefault(emptyList())
        val present = drivers.map { it.device.deviceName }.toSet()
        open.keys.filter { it !in present }.forEach { name ->
            close(name)
            gone(name)
        }
        errors.keys.retainAll(present)
        for (driver in drivers) {
            val name = driver.device.deviceName
            if (name in open || name in errors || !usb.hasPermission(driver.device)) continue
            openDevice(driver)
        }
    }

    /** What Android sees now, for `mynx usb`. */
    fun devices(): List<UsbSerialDevice> {
        val drivers = runCatching { prober.findAllDrivers(usb) }.getOrDefault(emptyList())
        return drivers.map { driver ->
            val device = driver.device
            UsbSerialDevice(
                id = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = runCatching { device.manufacturerName }.getOrNull(),
                product = runCatching { device.productName }.getOrNull(),
                // Android only gives the serial number to an app allowed to use the device.
                serial = if (usb.hasPermission(device)) runCatching { device.serialNumber }.getOrNull() else null,
                driver = usbDriverName(driver.javaClass.simpleName),
                allowed = usb.hasPermission(device),
                ports = open[device.deviceName]?.ports?.map { SerialPortInfo(it.name, it.server.port, it.server.busy) }.orEmpty(),
                error = errors[device.deviceName],
            )
        }
    }

    /** Asks Android for access to device [id] (Android's dialog); [answer] gets null once its ports are served. */
    fun allow(id: String, answer: (String?) -> Unit) {
        val device = usb.deviceList.values.firstOrNull { it.deviceName == id }
            ?: return answer("that USB device is gone")
        if (usb.hasPermission(device)) {
            // Allowed already: try opening it again, in case it failed before.
            errors.remove(id)
            refresh()
            return answer(errors[id])
        }
        val waiting = asking.getOrPut(id) { mutableListOf() }
        waiting += answer
        if (waiting.size > 1) return
        // Mutable: Android adds whether it was allowed. Explicit (our package), as Android 14 requires of mutable ones.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            usb.requestPermission(device, pending)
        } catch (e: RuntimeException) {
            answerAsking(id, false, "Android didn't ask: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun answerAsking(id: String, granted: Boolean, why: String? = null) {
        val answers = asking.remove(id) ?: return
        val error = if (granted) {
            refresh()
            errors[id]
        } else {
            why ?: "access to the USB device was denied"
        }
        answers.forEach { it(error) }
    }

    @Suppress("DEPRECATION") // the typed getParcelableExtra needs API 33
    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun openDevice(driver: UsbSerialDriver) {
        val device = driver.device
        val opened = OpenDevice()
        open[device.deviceName] = opened
        try {
            for (port in driver.ports) {
                val name = ttyName(usbDriverName(driver.javaClass.simpleName), takenNames())
                // A connection per port: requestWait() returns any request of its
                // connection, so ports sharing one would read each other's data.
                // The port's close() closes it.
                val connection = usb.openDevice(device) ?: throw IOException("Android gave no connection")
                port.open(connection)
                try {
                    // Keeps the IN endpoint polled while a read is handed on. An
                    // ESP32-C3's USB-Serial-JTAG under esptool's stub dropped all but
                    // the first packet of each reply with one read at a time, as if it
                    // gives up on a packet the host doesn't collect at once.
                    port.setReadQueue(READ_QUEUE, port.readEndpoint.maxPacketSize.coerceAtLeast(64) * 16)
                    val line = UsbLine(port)
                    line.configure(LineSettings())
                    line.setDtr(false)
                    line.setRts(false)
                    val server = newServer(line)
                    opened.ports += OpenPort(name, port, server).also { it.start() }
                } catch (e: Exception) {
                    // Not in opened.ports yet, so close() won't reach it.
                    runCatching { port.close() }
                    throw e
                }
            }
        } catch (e: Exception) {
            close(device.deviceName)
            errors[device.deviceName] = "couldn't open it: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        devices().firstOrNull { it.id == device.deviceName }?.let(ready)
    }

    private fun takenNames(): Set<String> = open.values.flatMap { d -> d.ports.map { it.name } }.toSet()

    // The lowest free port from 2217; one another app holds is skipped.
    private fun newServer(line: SerialLine): SerialServer {
        val taken = open.values.flatMap { d -> d.ports.map { it.server.port } }.toSet()
        for (port in tcpPorts(taken).take(100)) {
            val server = runCatching { SerialServer(line, port) }.getOrNull()
            if (server != null) return server
        }
        throw IOException("no free TCP port from 2217")
    }

    private fun close(name: String) {
        val device = open.remove(name) ?: return
        device.ports.forEach { it.close() }
    }

    private class OpenDevice {
        val ports = mutableListOf<OpenPort>()
    }

    private inner class OpenPort(val name: String, val port: UsbSerialPort, val server: SerialServer) {
        @Volatile private var closed = false
        @Volatile private var process: Process? = null

        fun start() {
            server.start()
            thread(name = "usb-read-$name", isDaemon = true) { readLoop() }
            thread(name = "serial-port-$name", isDaemon = true) { runHelper() }
        }

        private fun readLoop() {
            // With a read queue, each read must take exactly its buffers' size.
            val buffer = ByteArray(port.readQueueBufferSize)
            while (!closed) {
                val n = try {
                    port.read(buffer, 0)
                } catch (e: Exception) {
                    // Unplugged (or closed): the detach broadcast closes the rest.
                    if (!closed) handler.post { refresh() }
                    break
                }
                if (n > 0) server.fromLine(buffer, n)
            }
        }

        // The pseudo-terminal's helper, started again if it ends while the port is open.
        private fun runHelper() {
            val restarts = ServerRestarts()
            while (!closed) {
                val started = SystemClock.elapsedRealtime()
                val code = runCatching {
                    logDir.mkdirs()
                    val launch = launchHelper(name, server.port)
                    val builder = ProcessBuilder(launch.argv).directory(workDir)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(File(logDir, "$name.log")))
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
                later = { ms, action -> handler.postDelayed(action, ms) },
                force = { if (p.isAlive && pid != null) killProot(pid) { Os.kill(it, OsConstants.SIGKILL) } },
            )
        }

        fun close() {
            closed = true
            process?.let(::stopHelper)
            server.close()
            // Cancels the queued reads, which ends the blocking one, and closes the connection.
            runCatching { port.close() }
            // The helper is killed, so its link stays: take it away here.
            File(serialDir, name).delete()
        }
    }
}

/** A port of usb-serial-for-android as the RFC 2217 server drives it. */
private class UsbLine(private val port: UsbSerialPort) : SerialLine {
    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun configure(settings: LineSettings) {
        val stopBits = when (settings.stopBits) {
            StopBits.ONE -> UsbSerialPort.STOPBITS_1
            StopBits.TWO -> UsbSerialPort.STOPBITS_2
            StopBits.ONE_POINT_FIVE -> UsbSerialPort.STOPBITS_1_5
        }
        val parity = when (settings.parity) {
            Parity.NONE -> UsbSerialPort.PARITY_NONE
            Parity.ODD -> UsbSerialPort.PARITY_ODD
            Parity.EVEN -> UsbSerialPort.PARITY_EVEN
            Parity.MARK -> UsbSerialPort.PARITY_MARK
            Parity.SPACE -> UsbSerialPort.PARITY_SPACE
        }
        // Some chips can't do every setting: keep the connection and the port as it was.
        try {
            port.setParameters(settings.baud, settings.dataBits, stopBits, parity)
        } catch (e: UnsupportedOperationException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    // Drivers that have no such line throw UnsupportedOperationException: there's nothing to set.
    override fun setDtr(on: Boolean) = unsupportedIsFine { port.setDTR(on) }

    override fun setRts(on: Boolean) = unsupportedIsFine { port.setRTS(on) }

    override fun setBreak(on: Boolean) = unsupportedIsFine { port.setBreak(on) }

    override fun setFlowControl(flow: FlowControl): Boolean {
        val wanted = when (flow) {
            FlowControl.NONE -> UsbSerialPort.FlowControl.NONE
            FlowControl.XON_XOFF -> UsbSerialPort.FlowControl.XON_XOFF
            FlowControl.RTS_CTS -> UsbSerialPort.FlowControl.RTS_CTS
        }
        if (wanted !in port.getSupportedFlowControl()) return wanted == UsbSerialPort.FlowControl.NONE
        return try {
            port.setFlowControl(wanted)
            true
        } catch (e: UnsupportedOperationException) {
            false
        }
    }

    override fun purge(input: Boolean, output: Boolean) = unsupportedIsFine { port.purgeHwBuffers(output, input) }

    override fun modemState(): Int = runCatching {
        val lines = port.getControlLines()
        (if (UsbSerialPort.ControlLine.CTS in lines) 0x10 else 0) or
            (if (UsbSerialPort.ControlLine.DSR in lines) 0x20 else 0) or
            (if (UsbSerialPort.ControlLine.RI in lines) 0x40 else 0) or
            (if (UsbSerialPort.ControlLine.CD in lines) 0x80 else 0)
    }.getOrDefault(0)

    private fun unsupportedIsFine(block: () -> Unit) {
        try {
            block()
        } catch (e: UnsupportedOperationException) {
        }
    }
}
