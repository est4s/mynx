package io.github.est4s.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.hoho.android.usbserial.driver.FtdiSerialDriver
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
import io.github.est4s.terminal.core.serialWaitingMark
import io.github.est4s.terminal.core.stopProot
import io.github.est4s.terminal.core.tcpPorts
import io.github.est4s.terminal.core.ttyName
import io.github.est4s.terminal.core.usbString
import io.github.est4s.terminal.core.usbDriverName
import io.github.est4s.terminal.core.usbIds
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Android's answer to our permission requests; [UsbFds] listens for it too. */
internal const val ACTION_USB_PERMISSION = "io.github.est4s.terminal.USB_PERMISSION"
private const val WRITE_TIMEOUT_MS = 5000
// Reads kept queued on each port, a packet each, at most. Linux's cdc-acm
// keeps 16, but it queues each again as it completes; here our thread must
// collect it first. A short packet ends a read, and esptool's stub on an
// ESP32-C3's USB-Serial-JTAG flushes after every byte, so each read holds
// one byte. Android stops queueing at some number (~256 here):
// UsbReads keeps what it takes.
private const val READ_QUEUE = 1024
private const val FTDI_STATUS_BYTES = 2
// After an attach, how often and how many times to look again for a device
// Android allowed without a broadcast reaching us (problem 6 of the phone
// tests: a Pro Micro sat allowed with no port until the app came on screen).
private const val RECHECK_MS = 1000L
private const val RECHECKS = 30

/**
 * The USB serial bridge: opens the serial devices Android lets the app
 * use (through usb-serial-for-android), serves each port over RFC 2217
 * on localhost ([SerialServer]) and runs its pseudo-terminal helper in
 * Debian ([launchHelper], `serial-port NAME TCPPORT`), whose link lives
 * in [serialDir]. Helpers' output goes to [logDir]. [ready] is told when
 * a device's ports are served, [gone] when a device left, [changed]
 * with what Android sees whenever the served ports change. Call from
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
    private val changed: (List<UsbSerialDevice>) -> Unit,
) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()
    private val open = linkedMapOf<String, OpenDevice>()
    private val errors = mutableMapOf<String, String>()
    private val asking = mutableMapOf<String, MutableList<(String?) -> Unit>>()
    private var started = false
    private var rechecks = 0
    private val recheck = Runnable { refresh() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = deviceOf(intent)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device != null) answerAsking(device.deviceName, granted)
            } else {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) rechecks = 0
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
        changed(emptyList())
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
        handler.removeCallbacks(recheck)
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
        handler.removeCallbacks(recheck)
        if (drivers.any { !usb.hasPermission(it.device) } && rechecks < RECHECKS) {
            rechecks++
            handler.postDelayed(recheck, RECHECK_MS)
        }
    }

    /** What Android sees now, for `mynx usb`: every device, those no serial driver drives with none (raw USB). */
    fun devices(): List<UsbSerialDevice> {
        val drivers = runCatching { prober.findAllDrivers(usb) }.getOrDefault(emptyList()).associateBy { it.device.deviceName }
        return usb.deviceList.values.sortedBy { it.deviceName }.map { device ->
            val driver = drivers[device.deviceName]
            UsbSerialDevice(
                id = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = usbString(runCatching { device.manufacturerName }.getOrNull()),
                product = usbString(runCatching { device.productName }.getOrNull()),
                // Android only gives the serial number to an app allowed to use the device.
                serial = if (usb.hasPermission(device)) usbString(runCatching { device.serialNumber }.getOrNull()) else null,
                driver = driver?.let { usbDriverName(it.javaClass.simpleName) },
                allowed = usb.hasPermission(device),
                ports = open[device.deviceName]?.ports?.map {
                    SerialPortInfo(it.name, it.server.port, it.server.busy, File(serialDir, serialWaitingMark(it.name)).exists())
                }.orEmpty(),
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
        if (driver.ports.isEmpty()) {
            errors[device.deviceName] = "its driver found no serial port on it"
            return
        }
        val opened = OpenDevice()
        open[device.deviceName] = opened
        // Named in the error, so a device that refuses one step says which.
        var step = "opening it"
        try {
            for (port in driver.ports) {
                val name = ttyName(usbDriverName(driver.javaClass.simpleName), takenNames())
                // A connection per port: requestWait() returns any request of its
                // connection, so ports sharing one would read each other's data.
                // The port's close() closes it.
                val connection = usb.openDevice(device) ?: throw IOException("Android gave no connection")
                step = "opening its port"
                port.open(connection)
                var queued: UsbReads? = null
                try {
                    // Keeps the IN endpoint polled while a read is handed on. An
                    // ESP32-C3's USB-Serial-JTAG under esptool's stub dropped all but
                    // the first packet of each reply with one read at a time, as if it
                    // gives up on a packet the host doesn't collect at once.
                    step = "queueing reads"
                    val reads = UsbReads(
                        connection, port.readEndpoint, READ_QUEUE,
                        header = if (driver is FtdiSerialDriver) FTDI_STATUS_BYTES else 0,
                    )
                    queued = reads
                    val line = UsbLine(port)
                    step = "setting 9600 8N1"
                    line.configure(LineSettings())
                    step = "dropping DTR"
                    line.setDtr(false)
                    step = "dropping RTS"
                    line.setRts(false)
                    step = "serving it"
                    val server = newServer(line)
                    opened.ports += OpenPort(name, port, reads, server).also { it.start() }
                } catch (e: Exception) {
                    queued?.cancel()
                    // Not in opened.ports yet, so close() won't reach it.
                    runCatching { port.close() }
                    throw e
                }
            }
        } catch (e: Exception) {
            close(device.deviceName)
            errors[device.deviceName] = "couldn't open it ($step): ${e.message ?: e.javaClass.simpleName}"
            return
        }
        val now = devices()
        changed(now)
        now.firstOrNull { it.id == device.deviceName }?.let(ready)
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
        changed(devices())
    }

    private class OpenDevice {
        val ports = mutableListOf<OpenPort>()
    }

    private inner class OpenPort(
        val name: String,
        val port: UsbSerialPort,
        val reads: UsbReads,
        val server: SerialServer,
    ) {
        @Volatile private var closed = false
        @Volatile private var process: Process? = null

        fun start() {
            server.start()
            thread(name = "usb-read-$name", isDaemon = true) { readLoop() }
            thread(name = "serial-port-$name", isDaemon = true) { runHelper() }
        }

        private fun readLoop() {
            val buffer = ByteArray(reads.size)
            // A late collect leaves the device no read to fill.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (!closed) {
                val n = try {
                    reads.read(buffer)
                } catch (e: Exception) {
                    if (closed) break
                    // Unplugged: refresh() closes it. Or still there: keep reading.
                    handler.post { refresh() }
                    Thread.sleep(100)
                    continue
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
            // Ends the blocking wait.
            reads.cancel()
            // Closes the connection.
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
    // An Arduino's Caterina bootloader refuses CDC's line-state request
    // ("controlTransfer failed"); Linux's cdc-acm carries on, and so do we.
    override fun setDtr(on: Boolean) = unsupportedIsFine { refusedIsFine { port.setDTR(on) } }

    override fun setRts(on: Boolean) = unsupportedIsFine { refusedIsFine { port.setRTS(on) } }

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

    private fun refusedIsFine(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
        }
    }

    private fun unsupportedIsFine(block: () -> Unit) {
        try {
            block()
        } catch (e: UnsupportedOperationException) {
        }
    }
}

/**
 * A port's reads, kept queued on its IN endpoint, one packet each. Not the
 * library's read queue: when Android refused to queue a completed read again,
 * its read() threw after taking the packet, so the packet was lost (esptool's
 * replies cut short), and it turned every empty packet into a GET_STATUS of
 * up to 200 ms. Here a read Android refuses waits to be queued again, and an
 * empty packet is just nothing to pass on. Only the read thread calls read().
 */
private class UsbReads(
    private val connection: UsbDeviceConnection,
    endpoint: UsbEndpoint,
    asked: Int,
    // FTDI's status bytes at the start of each packet (its driver's read strips them).
    private val header: Int,
) {
    val size = endpoint.maxPacketSize.coerceAtLeast(64)
    private val requests = mutableListOf<UsbRequest>()
    private val unqueued = ArrayDeque<UsbRequest>()
    val count get() = requests.size

    init {
        for (i in 0 until asked) {
            val request = UsbRequest()
            val buffer = ByteBuffer.allocate(size)
            request.clientData = buffer
            if (!request.initialize(connection, endpoint) || !request.queue(buffer)) {
                request.close()
                break
            }
            requests += request
        }
        if (requests.isEmpty()) throw IOException("Android queued no USB read")
    }

    fun read(dest: ByteArray): Int {
        while (unqueued.isNotEmpty() && queue(unqueued.first())) unqueued.removeFirst()
        // Unplugged, most likely: the caller asks the bridge to look.
        if (unqueued.size == requests.size) throw IOException("Android queues no USB read")
        val request = connection.requestWait() ?: throw IOException("Waiting for USB request failed")
        val buffer = request.clientData as ByteBuffer
        val n = (buffer.position() - header).coerceAtLeast(0)
        if (n > 0) System.arraycopy(buffer.array(), header, dest, 0, n)
        if (!queue(request)) unqueued.addLast(request)
        return n
    }

    private fun queue(request: UsbRequest): Boolean {
        val buffer = request.clientData as ByteBuffer
        buffer.clear()
        return request.queue(buffer)
    }

    // Not close(): one could still be queued. Closing the connection ends them.
    fun cancel() = requests.forEach { runCatching { it.cancel() } }
}
