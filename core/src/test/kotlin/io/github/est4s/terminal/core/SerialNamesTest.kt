package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerialNamesTest {
    private val base = createTempDirectory("serial-names").toFile()
    private val dev = File(base, "dev")
    private val sysTty = File(base, "sys-tty")

    private val hostDev = mapOf("null" to HostEntry.FILE, "tty" to HostEntry.FILE, "pts" to HostEntry.DIR, "ttyS0" to HostEntry.FILE)

    private fun prepare() = prepareOwnDev(dev, sysTty) { hostDev[it] }

    private fun link(file: File) = Files.readSymbolicLink(file.toPath()).toString()

    private val c3 = UsbSerialDevice(
        id = "/dev/bus/usb/001/002", vendorId = 0x303a, productId = 0x1001,
        manufacturer = "Espressif", product = "USB JTAG/serial debug unit", serial = "AA:BB:CC",
        driver = "cdc-acm", allowed = true, ports = listOf(SerialPortInfo("ttyACM0", 2217, false)), error = null,
    )
    private val nano = UsbSerialDevice(
        id = "/dev/bus/usb/001/003", vendorId = 0x1a86, productId = 0x7523,
        manufacturer = null, product = "USB Serial", serial = null,
        driver = "ch34x", allowed = true, ports = listOf(SerialPortInfo("ttyUSB0", 2218, false)), error = null,
    )

    @Test
    fun `binds the host's device files Debian needs, those the phone has`() {
        val own = prepare()

        assertEquals(dev.absolutePath, own.dir)
        assertEquals(sysTty.absolutePath, own.sysTty)
        assertEquals(listOf("null", "tty", "pts"), own.devices)
    }

    @Test
    fun `never brings in the phone's own serial ports`() {
        assertFalse("ttyS0" in prepare().devices)
        assertFalse(File(dev, "ttyS0").exists())
    }

    @Test
    fun `lists the bound devices in its dev, with the usual links`() {
        prepare()

        assertTrue(File(dev, "null").isFile)
        assertTrue(File(dev, "pts").isDirectory)
        assertTrue(File(dev, "serial").isDirectory)
        assertEquals("/proc/self/fd", link(File(dev, "fd")))
        assertEquals("/proc/self/fd/0", link(File(dev, "stdin")))
        assertEquals("/proc/self/fd/1", link(File(dev, "stdout")))
        assertEquals("/proc/self/fd/2", link(File(dev, "stderr")))
        assertTrue(sysTty.isDirectory)
    }

    @Test
    fun `preparing again keeps the ports' names`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3))
        prepare()

        assertEquals("serial/ttyACM0", link(File(dev, "ttyACM0")))
    }

    @Test
    fun `each port gets its Linux name in dev, pointing at its dev serial link`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3, nano))

        assertEquals("serial/ttyACM0", link(File(dev, "ttyACM0")))
        assertEquals("serial/ttyUSB0", link(File(dev, "ttyUSB0")))
    }

    @Test
    fun `a port that's gone loses its names`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3, nano))
        writeSerialNames(dev, sysTty, listOf(nano))

        assertFalse(Files.exists(File(dev, "ttyACM0").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(sysTty, "ttyACM0").exists())
        assertEquals("serial/ttyUSB0", link(File(dev, "ttyUSB0")))
        assertTrue(File(sysTty, "ttyUSB0/device").exists())
    }

    @Test
    fun `nothing plugged in leaves no names`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3))
        writeSerialNames(dev, sysTty, emptyList())

        assertEquals(emptyList(), dev.list()!!.filter { it.startsWith("ttyACM") || it.startsWith("ttyUSB") })
        assertEquals(emptyList(), sysTty.list()!!.filter { !it.startsWith(".") })
    }

    @Test
    fun `a device without ports yet gets no names`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3.copy(ports = emptyList())))

        assertFalse(File(sysTty, "ttyACM0").exists())
    }

    // As pyserial's list_ports_linux and go.bug.st/serial's enumerator read it.
    private fun device(name: String) = File(sysTty, "$name/device").canonicalFile

    private fun subsystem(dir: File) = File(dir, "subsystem").canonicalFile.also { assertTrue(it.isDirectory) }.name

    private fun read(dir: File, name: String) = File(dir, name).readText().trim()

    @Test
    fun `a CDC ACM port's sysfs device is its USB interface`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3))

        val iface = device("ttyACM0")
        assertEquals("usb", subsystem(iface))
        val usb = iface.parentFile
        assertEquals("303a", read(usb, "idVendor"))
        assertEquals("1001", read(usb, "idProduct"))
        assertEquals("AA:BB:CC", read(usb, "serial"))
        assertEquals("Espressif", read(usb, "manufacturer"))
        assertEquals("USB JTAG/serial debug unit", read(usb, "product"))
    }

    @Test
    fun `a usb-serial port's sysfs device sits under its interface`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(nano))

        val port = device("ttyUSB0")
        assertEquals("ttyUSB0", port.name)
        assertEquals("usb-serial", subsystem(port))
        val usb = port.parentFile.parentFile
        assertEquals("1a86", read(usb, "idVendor"))
        assertEquals("7523", read(usb, "idProduct"))
        assertEquals("USB Serial", read(usb, "product"))
        // What Android doesn't say is left out, as the kernel does.
        assertFalse(File(usb, "serial").exists())
        assertFalse(File(usb, "manufacturer").exists())
    }

    @Test
    fun `each device is its own USB device in sysfs`() {
        prepare()
        writeSerialNames(dev, sysTty, listOf(c3, nano))

        val acm = device("ttyACM0").parentFile
        val usb = device("ttyUSB0").parentFile.parentFile
        assertTrue(acm != usb)
        assertEquals("1", read(usb, "bNumInterfaces"))
    }

    @Test
    fun `a device's ports share its USB device, one interface each`() {
        val ft = nano.copy(vendorId = 0x0403, productId = 0x6010, driver = "ftdi",
            ports = listOf(SerialPortInfo("ttyUSB0", 2217, false), SerialPortInfo("ttyUSB1", 2218, false)))
        prepare()
        writeSerialNames(dev, sysTty, listOf(ft))

        val a = device("ttyUSB0").parentFile
        val b = device("ttyUSB1").parentFile
        assertTrue(a != b)
        assertEquals(a.parentFile, b.parentFile)
        assertEquals("2", read(a.parentFile, "bNumInterfaces"))
    }
}
