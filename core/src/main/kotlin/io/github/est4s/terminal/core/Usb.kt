package io.github.est4s.terminal.core

/** Where Debian sees the serial ports: a folder the app binds over `/dev/serial`, of links to pseudo-terminals. */
const val SERIAL_MOUNT = "/dev/serial"
/** The serial port helpers' logs, a Debian path. */
const val SERIAL_LOG_DIR = "/tmp/.mynx/serial"
/** The helper that gives a port its pseudo-terminal, run in Debian for each port. */
const val SERIAL_PORT_HELPER = "$TOOLS_MOUNT/lib/serial-port"
// How long `mynx usb allow` waits for the user to answer Android's dialog.
private const val ALLOW_SECONDS = 120

/** A serial port served to Debian: [name] in [SERIAL_MOUNT], and RFC 2217 on localhost [tcpPort]. */
data class SerialPortInfo(val name: String, val tcpPort: Int, val inUse: Boolean)

/**
 * A USB device a serial driver can drive. [id] is Android's name for it
 * (`/dev/bus/usb/001/002`); [driver] is [usbDriverName]'s. Its [ports]
 * are served once it's [allowed]; [error] says why they aren't.
 */
data class UsbSerialDevice(
    val id: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val driver: String,
    val allowed: Boolean,
    val ports: List<SerialPortInfo>,
    val error: String?,
)

/** A driver's short name, from its class in usb-serial-for-android (`Ch34xSerialDriver` → `ch34x`). */
fun usbDriverName(className: String): String = when (val base = className.removeSuffix("SerialDriver")) {
    "CdcAcm" -> "cdc-acm"
    "Prolific" -> "pl2303"
    "GsmModem" -> "gsm-modem"
    "ChromeCcd" -> "chrome-ccd"
    else -> base.lowercase()
}

/** The next port's name, as Linux would give it: `ttyACM` for CDC ACM, else `ttyUSB`, lowest free number. */
fun ttyName(driver: String, taken: Set<String>): String {
    val prefix = if (driver == "cdc-acm") "ttyACM" else "ttyUSB"
    return generateSequence(0) { it + 1 }.map { "$prefix$it" }.first { it !in taken }
}

/** The TCP ports to try for the next serial port, lowest first. */
fun tcpPorts(taken: Set<Int>): Sequence<Int> =
    generateSequence(FIRST_SERIAL_TCP_PORT) { it + 1 }.filter { it !in taken }.takeWhile { it <= 65535 }

/** A device's string (maker, product, serial) without the NULs some boards end it with; null if nothing's left. */
fun usbString(text: String?): String? = text?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }

/** `vvvv:pppp`, as lsusb shows them. */
fun usbIds(vendorId: Int, productId: Int): String = "%04x:%04x".format(vendorId, productId)

fun serialPath(port: SerialPortInfo) = "$SERIAL_MOUNT/${port.name}"

fun serialUrl(port: SerialPortInfo) = "rfc2217://localhost:${port.tcpPort}"

private fun label(device: UsbSerialDevice) = device.product?.takeIf { it.isNotBlank() } ?: usbIds(device.vendorId, device.productId)

/** The "ready" notification's title: `ttyUSB0 is ready: USB Serial`. */
fun usbReadyTitle(device: UsbSerialDevice): String {
    val names = device.ports.joinToString(", ") { it.name }
    return "$names ${if (device.ports.size == 1) "is" else "are"} ready: ${label(device)}"
}

/** The "ready" notification's text: each port's path and URL. */
fun usbReadyText(device: UsbSerialDevice): String =
    device.ports.joinToString("\n") { "${serialPath(it)} · ${serialUrl(it)}" }

fun usbDeviceJson(device: UsbSerialDevice): String = obj(
    "id" to json(device.id),
    "ids" to json(usbIds(device.vendorId, device.productId)),
    "manufacturer" to (device.manufacturer?.let(::json) ?: "null"),
    "product" to (device.product?.let(::json) ?: "null"),
    "serial" to (device.serial?.let(::json) ?: "null"),
    "driver" to json(device.driver),
    "allowed" to device.allowed.toString(),
    "error" to (device.error?.let(::json) ?: "null"),
    "ports" to array(device.ports.map { port ->
        obj(
            "name" to json(port.name),
            "path" to json(serialPath(port)),
            "url" to json(serialUrl(port)),
            "tcp_port" to port.tcpPort.toString(),
            "in_use" to port.inUse.toString(),
        )
    }),
)

/**
 * The `usb-list` and `usb-allow [DEVICE]` requests, for [MynxRequests]'s
 * `later`. [devices] gives what Android sees now; [allow] asks Android
 * for access to a device (by its id) and answers null once allowed and
 * its ports are served, else why not.
 */
fun usbRequests(
    devices: () -> List<UsbSerialDevice>,
    allow: (String, (String?) -> Unit) -> Unit,
): Map<String, Later> {
    val list = Later(10) { args, reply ->
        if (args.any { it.isNotBlank() }) return@Later reply.refuse("usb-list takes no options")
        reply.ok("devices" to array(devices().map(::usbDeviceJson)))
    }
    val allowRequest = Later(ALLOW_SECONDS) { args, reply ->
        val which = args.firstOrNull()?.trim().orEmpty()
        val all = devices()
        val device = if (which.isEmpty()) {
            val waiting = all.filter { !it.allowed }
            when (waiting.size) {
                0 -> return@Later reply.refuse("no USB serial device is waiting for access")
                1 -> waiting.single()
                else -> return@Later reply.refuse("${waiting.size} USB devices are waiting: say which (mynx usb lists them)")
            }
        } else {
            all.firstOrNull { it.id == which || usbIds(it.vendorId, it.productId) == which.lowercase() }
                ?: return@Later reply.refuse("no USB serial device $which (mynx usb lists them)")
        }
        if (device.allowed) return@Later reply.ok("device" to usbDeviceJson(device))
        allow(device.id) { error ->
            if (error != null) return@allow reply.refuse(error)
            val now = devices().firstOrNull { it.id == device.id } ?: device
            reply.ok("device" to usbDeviceJson(now))
        }
    }
    return mapOf("usb-list" to list, "usb-allow" to allowRequest)
}
