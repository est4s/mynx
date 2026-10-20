package io.github.est4s.terminal.core

import java.io.File

/**
 * The kernel's uevent messages for USB serial ports coming and going.
 * Android refuses apps the uevent socket (NETLINK_KOBJECT_UEVENT), which
 * tools such as arduino-cli's `board list` watch; proot's mynx_serial
 * extension gives programs a Unix datagram socket in [ueventSockets]
 * instead, and the app sends these to each.
 */
class SerialUevents {
    private var names = emptySet<String>()
    private var seqnum = 0L

    /** The messages for the ports that went since the last call, then those that came: [now] are the ports' names. */
    fun changed(now: Set<String>): List<ByteArray> {
        val messages = (names - now).map { message("remove", it) } + (now - names).map { message("add", it) }
        names = now
        return messages
    }

    private fun message(action: String, name: String): ByteArray {
        val devpath = "/devices/mynx/tty/$name"
        val number = name.takeLastWhile(Char::isDigit)
        // ttyACM's major is 166, ttyUSB's 188, as on Linux.
        val major = if (name.startsWith("ttyACM")) 166 else 188
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
