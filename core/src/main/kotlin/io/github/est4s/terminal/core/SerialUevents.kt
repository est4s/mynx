package io.github.est4s.terminal.core

import java.io.File

/** The rfcomm ttys' major number on Linux. */
internal const val RFCOMM_MAJOR = 216

/**
 * The kernel's uevent messages for serial ports (USB, and Bluetooth's
 * `rfcomm` ones) coming and going.
 * Android refuses apps the uevent socket (NETLINK_KOBJECT_UEVENT), which
 * tools such as arduino-cli's `board list` watch; proot's mynx_serial
 * extension gives programs a Unix datagram socket in [ueventSockets]
 * instead, and the app sends these to each.
 */
class SerialUevents {
    private var names = emptySet<String>()
    private var seqnum = 0L

    /**
     * The messages for the ports that went since the last call, then
     * those that came: [ports] are the ports' names. BLE UART ports
     * (`nus0`) get none: Linux has no such tty, so nothing listens for one.
     */
    fun changed(ports: Set<String>): List<ByteArray> {
        val now = ports.filterNot(::isNusName).toSet()
        val messages = (names - now).map { message("remove", it) } + (now - names).map { message("add", it) }
        names = now
        return messages
    }

    private fun message(action: String, name: String): ByteArray {
        val rfcomm = name.startsWith("rfcomm")
        // Bluetooth's are virtual ttys on Linux, with no device behind them.
        val devpath = if (rfcomm) "/devices/virtual/tty/$name" else "/devices/mynx/tty/$name"
        val number = name.takeLastWhile(Char::isDigit)
        // ttyACM's major is 166, ttyUSB's 188, rfcomm's 216, as on Linux.
        val major = when {
            rfcomm -> RFCOMM_MAJOR
            name.startsWith("ttyACM") -> 166
            else -> 188
        }
        val lines = listOf(
            "$action@$devpath", "ACTION=$action", "DEVPATH=$devpath", "SUBSYSTEM=tty",
            "MAJOR=$major", "MINOR=$number", "DEVNAME=$name", "SEQNUM=${++seqnum}",
        )
        return lines.joinToString("") { "$it\u0000" }.toByteArray()
    }
}

/** The programs' stand-in uevent sockets, in [serialDir]'s `.uevent` (where proot binds them). */
fun ueventSockets(serialDir: File): List<File> =
    File(serialDir, ".uevent").listFiles().orEmpty().sortedBy { it.name }
