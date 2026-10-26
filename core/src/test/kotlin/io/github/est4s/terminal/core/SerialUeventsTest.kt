package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SerialUeventsTest {
    private fun text(message: ByteArray) = String(message).split('\u0000').dropLast(1)

    @Test
    fun `a port that comes is the kernel's add message for its tty, SEQNUM last`() {
        assertEquals(
            listOf(
                "add@/devices/mynx/tty/ttyACM0", "ACTION=add", "DEVPATH=/devices/mynx/tty/ttyACM0",
                "SUBSYSTEM=tty", "MAJOR=166", "MINOR=0", "DEVNAME=ttyACM0", "SEQNUM=1",
            ),
            text(SerialUevents().changed(setOf("ttyACM0")).single()),
        )
    }

    @Test
    fun `ports that come and go, each once, numbered in order`() {
        val uevents = SerialUevents()
        uevents.changed(setOf("ttyUSB0"))
        val messages = uevents.changed(setOf("ttyUSB1", "ttyACM2")).map(::text)

        assertEquals(listOf("remove@/devices/mynx/tty/ttyUSB0", "add@/devices/mynx/tty/ttyUSB1", "add@/devices/mynx/tty/ttyACM2"), messages.map { it.first() })
        assertEquals(listOf("SEQNUM=2", "SEQNUM=3", "SEQNUM=4"), messages.map { it.last() })
        assertEquals("MAJOR=188", messages[1][4])
        assertEquals("MINOR=2", messages[2][5])
        assertEquals(emptyList(), uevents.changed(setOf("ttyUSB1", "ttyACM2")))
    }

    @Test
    fun `an rfcomm port is a virtual tty, major 216`() {
        assertEquals(
            listOf(
                "add@/devices/virtual/tty/rfcomm0", "ACTION=add", "DEVPATH=/devices/virtual/tty/rfcomm0",
                "SUBSYSTEM=tty", "MAJOR=216", "MINOR=0", "DEVNAME=rfcomm0", "SEQNUM=1",
            ),
            text(SerialUevents().changed(setOf("rfcomm0")).single()),
        )
    }

    @Test
    fun `the sockets are the files in the ports' folder's uevent folder`() {
        val serial = createTempDirectory("serial").toFile()
        assertEquals(emptyList(), ueventSockets(serial))
        File(serial, ".uevent").mkdirs()
        File(serial, ".uevent/12-1").writeText("")
        File(serial, ".uevent/34-2").writeText("")

        assertEquals(listOf("12-1", "34-2"), ueventSockets(serial).map { it.name })
    }
}
