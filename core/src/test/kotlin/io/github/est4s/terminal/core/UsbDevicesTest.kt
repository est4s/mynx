package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbDevicesTest {
    private val base = createTempDirectory("usb-devices").toFile()
    private val sysBus = File(base, "sys-bus")
    // Not "dev": the raw USB spike's proot, which these tests ran under on
    // the phone, took any path ending in dev/bus/usb/BBB/DDD as a device.
    private val dev = File(base, "own-dev")

    // An RP2040 in BOOTSEL: its device descriptor, then its configuration:
    // a mass storage interface and the vendor one picotool uses.
    private val rp2040 = RawUsbDevice(
        name = "/dev/bus/usb/001/004",
        descriptors = bytes(
            0x12, 0x01, 0x10, 0x01, 0x00, 0x00, 0x00, 0x40, 0x8a, 0x2e, 0x03, 0x00, 0x00, 0x01, 0x01, 0x02, 0x03, 0x01,
            0x09, 0x02, 0x37, 0x00, 0x02, 0x01, 0x00, 0x80, 0xfa,
            0x09, 0x04, 0x00, 0x00, 0x02, 0x08, 0x06, 0x50, 0x00,
            0x07, 0x05, 0x81, 0x02, 0x40, 0x00, 0x00, 0x07, 0x05, 0x02, 0x02, 0x40, 0x00, 0x00,
            0x09, 0x04, 0x01, 0x00, 0x02, 0xff, 0x00, 0x00, 0x00,
            0x07, 0x05, 0x83, 0x02, 0x40, 0x00, 0x00, 0x07, 0x05, 0x04, 0x02, 0x40, 0x00, 0x00,
        ),
        manufacturer = "Raspberry Pi", product = "RP2 Boot", serial = "E0C9125B0D9B",
        interfaceNames = mapOf(1 to "Reset"),
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun attr(name: String, device: String = "1-4") = File(sysBus, "usb/devices/$device/$name").readText()

    @Test
    fun `bus and address come from Android's device name`() {
        assertEquals(1 to 4, usbBusAddress("/dev/bus/usb/001/004"))
        assertEquals(2 to 127, usbBusAddress("/dev/bus/usb/002/127"))
        assertNull(usbBusAddress("/dev/bus/usb/001"))
        assertNull(usbBusAddress("/dev/ttyACM0"))
    }

    @Test
    fun `each device gets a sysfs entry libudev and libusb read`() {
        writeUsbDevices(sysBus, dev, listOf(rp2040))

        // No MAJOR/MINOR: libudev's enumerator skips a device with a
        // device number and no udev database entry.
        assertEquals(
            "DEVTYPE=usb_device\nDEVNAME=bus/usb/001/004\nPRODUCT=2e8a/3/100\nTYPE=0/0/0\nBUSNUM=001\nDEVNUM=004\n",
            attr("uevent"),
        )
        assertEquals("1\n", attr("busnum"))
        assertEquals("4\n", attr("devnum"))
        assertContentEquals(rp2040.descriptors, File(sysBus, "usb/devices/1-4/descriptors").readBytes())
        assertEquals("2e8a\n", attr("idVendor"))
        assertEquals("0003\n", attr("idProduct"))
        assertEquals("0100\n", attr("bcdDevice"))
        assertEquals("00\n", attr("bDeviceClass"))
        assertEquals("64\n", attr("bMaxPacketSize0"))
        assertEquals("1\n", attr("bNumConfigurations"))
        assertEquals("1\n", attr("bConfigurationValue"))
        assertEquals(" 1.10\n", attr("version"))
        assertEquals("Raspberry Pi\n", attr("manufacturer"))
        assertEquals("RP2 Boot\n", attr("product"))
        assertEquals("E0C9125B0D9B\n", attr("serial"))
        assertEquals("../../../bus/usb", Files.readSymbolicLink(File(sysBus, "usb/devices/1-4/subsystem").toPath()).toString())
    }

    @Test
    fun `each interface of the first configuration gets a folder, as nusb (probe-rs) reads them`() {
        writeUsbDevices(sysBus, dev, listOf(rp2040))

        assertEquals("00\n", attr("1-4:1.0/bInterfaceNumber"))
        assertEquals("08\n", attr("1-4:1.0/bInterfaceClass"))
        assertEquals("06\n", attr("1-4:1.0/bInterfaceSubClass"))
        assertEquals("50\n", attr("1-4:1.0/bInterfaceProtocol"))
        assertEquals("00\n", attr("1-4:1.0/bAlternateSetting"))
        assertEquals("02\n", attr("1-4:1.0/bNumEndpoints"))
        assertFalse(File(sysBus, "usb/devices/1-4/1-4:1.0/interface").exists())
        assertEquals("01\n", attr("1-4:1.1/bInterfaceNumber"))
        assertEquals("ff\n", attr("1-4:1.1/bInterfaceClass"))
        // probe-rs finds a CMSIS-DAP probe by this string.
        assertEquals("Reset\n", attr("1-4:1.1/interface"))
        assertEquals(" 2\n", attr("bNumInterfaces"))
    }

    @Test
    fun `alternate settings and later configurations add no folders`() {
        val alt = bytes(0x09, 0x04, 0x01, 0x01, 0x00, 0xff, 0x00, 0x00, 0x00)
        val second = bytes(0x09, 0x02, 0x12, 0x00, 0x01, 0x02, 0x00, 0x80, 0xfa, 0x09, 0x04, 0x05, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00)
        writeUsbDevices(sysBus, dev, listOf(rp2040.copy(descriptors = rp2040.descriptors + alt + second)))

        val folders = File(sysBus, "usb/devices/1-4").list()!!.filter { ':' in it }.sorted()
        assertEquals(listOf("1-4:1.0", "1-4:1.1"), folders)
    }

    @Test
    fun `a truncated configuration is read as far as it goes`() {
        writeUsbDevices(sysBus, dev, listOf(rp2040.copy(descriptors = rp2040.descriptors.copyOf(18 + 9 + 9 + 3))))

        assertEquals("08\n", attr("1-4:1.0/bInterfaceClass"))
        assertFalse(File(sysBus, "usb/devices/1-4/1-4:1.1").exists())
    }

    @Test
    fun `strings Android doesn't give are left out`() {
        writeUsbDevices(sysBus, dev, listOf(rp2040.copy(manufacturer = null, serial = null)))

        assertFalse(File(sysBus, "usb/devices/1-4/manufacturer").exists())
        assertFalse(File(sysBus, "usb/devices/1-4/serial").exists())
        assertTrue(File(sysBus, "usb/devices/1-4/product").exists())
    }

    @Test
    fun `each device gets a node in Debian's own dev`() {
        writeUsbDevices(sysBus, dev, listOf(rp2040))

        assertTrue(File(dev, "bus/usb/001/004").isFile)
    }

    @Test
    fun `devices that left lose their entries and nodes`() {
        val other = rp2040.copy(name = "/dev/bus/usb/002/009")
        writeUsbDevices(sysBus, dev, listOf(rp2040, other))
        writeUsbDevices(sysBus, dev, listOf(other))

        assertFalse(File(sysBus, "usb/devices/1-4").exists())
        assertFalse(File(dev, "bus/usb/001").exists())
        assertTrue(File(sysBus, "usb/devices/2-9/uevent").exists())
        assertTrue(File(dev, "bus/usb/002/009").isFile)
    }

    @Test
    fun `the app's socket in dev's bus folder stays`() {
        File(dev, "bus/usb").mkdirs()
        File(dev, "bus/usb/.ctl").writeText("")
        writeUsbDevices(sysBus, dev, emptyList())

        assertTrue(File(dev, "bus/usb/.ctl").exists())
    }

    @Test
    fun `a device with too short a descriptor is skipped`() {
        writeUsbDevices(sysBus, dev, listOf(rp2040.copy(descriptors = bytes(0x12, 0x01))))

        assertFalse(File(sysBus, "usb/devices/1-4").exists())
    }

    @Test
    fun `requests name a device by bus and address`() {
        assertEquals("/dev/bus/usb/001/004", usbFdRequest("001/004\n"))
        assertNull(usbFdRequest("001/004"))
        assertNull(usbFdRequest("../../x\n"))
        assertNull(usbFdRequest("1/4\n"))
    }

    @Test
    fun `replies say ok or why not, as an errno`() {
        assertEquals("ok\n", usbFdReply(present = true, allowed = true))
        assertEquals("err 19\n", usbFdReply(present = false, allowed = false))
        assertEquals("err 13\n", usbFdReply(present = true, allowed = false))
    }
}
