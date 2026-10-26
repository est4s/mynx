package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsbNamesTest {
    @Test
    fun `drivers are named after their classes, unknown ones in small letters`() {
        assertEquals("cdc-acm", usbDriverName("CdcAcmSerialDriver"))
        assertEquals("ch34x", usbDriverName("Ch34xSerialDriver"))
        assertEquals("cp21xx", usbDriverName("Cp21xxSerialDriver"))
        assertEquals("ftdi", usbDriverName("FtdiSerialDriver"))
        assertEquals("pl2303", usbDriverName("ProlificSerialDriver"))
        assertEquals("somethingnew", usbDriverName("SomethingNewSerialDriver"))
    }

    @Test
    fun `CDC ACM ports are ttyACM, the rest ttyUSB, as Linux names them`() {
        assertEquals("ttyACM0", ttyName("cdc-acm", emptySet()))
        assertEquals("ttyUSB0", ttyName("ch34x", emptySet()))
        assertEquals("ttyUSB0", ttyName("ftdi", setOf("ttyACM0")))
    }

    @Test
    fun `a port takes the lowest free number`() {
        assertEquals("ttyUSB1", ttyName("cp21xx", setOf("ttyUSB0", "ttyUSB2")))
        assertEquals("ttyUSB2", ttyName("cp21xx", setOf("ttyUSB0", "ttyUSB1")))
    }

    @Test
    fun `TCP ports start at 2217 and skip those taken`() {
        assertEquals(listOf(2217, 2218, 2219), tcpPorts(emptySet()).take(3).toList())
        assertEquals(listOf(2218, 2220), tcpPorts(setOf(2217, 2219)).take(2).toList())
        // Bluetooth's range of its own, so the first USB port stays 2217 with devices bound.
        assertEquals(listOf(2317, 2319), tcpPorts(setOf(2318), first = FIRST_BLUETOOTH_TCP_PORT).take(2).toList())
    }

    @Test
    fun `a device's strings lose the NULs some boards end them with`() {
        // An ESP32-C3's USB-Serial-JTAG gives "Espressif\u0000": Android passes it on.
        assertEquals("Espressif", usbString("Espressif\u0000"))
        assertEquals("USB JTAG/serial debug unit", usbString("USB JTAG/serial debug unit\u0000 "))
        assertEquals("USB Serial", usbString("USB Serial"))
        assertNull(usbString("\u0000"))
        assertNull(usbString(null))
    }

    @Test
    fun `IDs are four hex digits`() {
        assertEquals("1a86:7523", usbIds(0x1a86, 0x7523))
        assertEquals("0403:6001", usbIds(0x403, 0x6001))
    }

    @Test
    fun `the notice says what is ready and where`() {
        val device = ch340.copy(ports = listOf(SerialPortInfo("ttyUSB0", 2217, inUse = false)))
        assertEquals("ttyUSB0 is ready: USB Serial", usbReadyTitle(device))
        assertEquals("/dev/serial/ttyUSB0 · rfc2217://localhost:2217", usbReadyText(device))
        val two = device.copy(product = null, ports = device.ports + SerialPortInfo("ttyUSB1", 2218, inUse = false))
        assertEquals("ttyUSB0, ttyUSB1 are ready: 1a86:7523", usbReadyTitle(two))
        assertEquals("/dev/serial/ttyUSB0 · rfc2217://localhost:2217\n/dev/serial/ttyUSB1 · rfc2217://localhost:2218", usbReadyText(two))
    }
}

private val ch340 = UsbSerialDevice(
    id = "/dev/bus/usb/001/002", vendorId = 0x1a86, productId = 0x7523,
    manufacturer = "QinHeng Electronics", product = "USB Serial", serial = null,
    driver = "ch34x", allowed = true, ports = listOf(SerialPortInfo("ttyUSB0", 2217, inUse = false)), error = null,
)

class UsbRequestTest {
    private val base = createTempDirectory("usb").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private var devices = listOf<UsbSerialDevice>()
    private val asked = mutableListOf<Pair<String, (String?) -> Unit>>()
    private val requests = MynxRequests(
        dir, File(base, "root").apply { mkdirs() },
        later = usbRequests(devices = { devices }, allow = { id, answer -> asked += id to answer }),
        alive = { true },
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private val esp = UsbSerialDevice(
        id = "/dev/bus/usb/001/003", vendorId = 0x303a, productId = 0x1001,
        manufacturer = "Espressif", product = "USB JTAG/serial debug unit", serial = "F4:12:FA:00:11:22",
        driver = "cdc-acm", allowed = false, ports = emptyList(), error = null,
    )

    @Test
    fun `lists the devices with their ports, paths and URLs`() {
        devices = listOf(ch340.copy(ports = listOf(SerialPortInfo("ttyUSB0", 2217, inUse = true, waiting = true))), esp)
        send("1-1", "usb-list")
        assertEquals(
            """{"ok":true,"devices":[""" +
                """{"id":"/dev/bus/usb/001/002","ids":"1a86:7523","manufacturer":"QinHeng Electronics","product":"USB Serial",""" +
                """"serial":null,"driver":"ch34x","allowed":true,"error":null,"ports":[""" +
                """{"name":"ttyUSB0","path":"/dev/serial/ttyUSB0","url":"rfc2217://localhost:2217","tcp_port":2217,"in_use":true,"waiting":true}]},""" +
                """{"id":"/dev/bus/usb/001/003","ids":"303a:1001","manufacturer":"Espressif","product":"USB JTAG/serial debug unit",""" +
                """"serial":"F4:12:FA:00:11:22","driver":"cdc-acm","allowed":false,"error":null,"ports":[]}]}""",
            reply("1-1"),
        )
    }

    @Test
    fun `allow asks for the one device waiting, and answers with it once allowed`() {
        devices = listOf(ch340, esp)
        send("1-1", "usb-allow")
        assertEquals(esp.id, asked.single().first)
        devices = listOf(ch340, esp.copy(allowed = true, ports = listOf(SerialPortInfo("ttyACM0", 2218, inUse = false))))
        asked.single().second(null)
        val answer = parseJson(reply("1-1")) as Map<*, *>
        assertEquals(true, answer["ok"])
        assertEquals("ttyACM0", (((answer["device"] as Map<*, *>)["ports"] as List<*>).single() as Map<*, *>)["name"])
    }

    @Test
    fun `a refusal says so`() {
        devices = listOf(esp)
        send("1-1", "usb-allow")
        asked.single().second("access to 303a:1001 was denied")
        assertEquals("""{"ok":false,"error":"access to 303a:1001 was denied"}""", reply("1-1"))
    }

    @Test
    fun `allow picks a device by its ids or its id`() {
        val other = esp.copy(id = "/dev/bus/usb/001/009", vendorId = 0x10c4, productId = 0xea60)
        devices = listOf(esp, other)
        send("1-1", "usb-allow", "10c4:ea60")
        send("1-2", "usb-allow", "/dev/bus/usb/001/003")
        assertEquals(listOf(other.id, esp.id), asked.map { it.first })
    }

    @Test
    fun `allow with several waiting asks which`() {
        devices = listOf(esp, esp.copy(id = "/dev/bus/usb/001/009"))
        send("1-1", "usb-allow")
        assertEquals(
            """{"ok":false,"error":"2 USB devices are waiting: say which (mynx usb lists them)"}""",
            reply("1-1"),
        )
    }

    @Test
    fun `an allowed device with no port is opened again, picked like one not allowed`() {
        // Allowed, but the bridge never opened it (problem 6: a Pro Micro sat at
        // "no port yet" for 40 minutes, and `mynx usb allow` answered at once).
        val served = ch340
        val stuck = esp.copy(allowed = true)
        devices = listOf(served, stuck)
        send("1-1", "usb-allow")
        assertEquals(stuck.id, asked.single().first)
        devices = listOf(served, stuck.copy(ports = listOf(SerialPortInfo("ttyACM0", 2218, inUse = false))))
        asked.single().second(null)
        assertEquals(true, (parseJson(reply("1-1")) as Map<*, *>)["ok"])
        devices = listOf(served, stuck)
        send("1-2", "usb-allow", "303a:1001")
        assertEquals(listOf(stuck.id, stuck.id), asked.map { it.first })
        send("1-3", "usb-allow", "/dev/bus/usb/001/003")
        assertEquals(3, asked.size)
    }

    @Test
    fun `allow with nothing waiting says so, and a served device is answered at once`() {
        devices = emptyList()
        send("1-1", "usb-allow")
        assertEquals("""{"ok":false,"error":"no USB device is waiting for access"}""", reply("1-1"))
        devices = listOf(ch340)
        send("1-2", "usb-allow")
        assertEquals("""{"ok":false,"error":"no USB device is waiting for access"}""", reply("1-2"))
        send("1-2b", "usb-allow", "1a86:7523")
        assertEquals(true, (parseJson(reply("1-2b")) as Map<*, *>)["ok"])
        assertEquals(emptyList(), asked)
        send("1-3", "usb-allow", "dead:beef")
        assertEquals("""{"ok":false,"error":"no USB device dead:beef (mynx usb lists them)"}""", reply("1-3"))
    }

    // An RP2040 in BOOTSEL: no serial driver, for Debian's tools over raw USB.
    private val boot = UsbSerialDevice(
        id = "/dev/bus/usb/001/004", vendorId = 0x2e8a, productId = 0x0003,
        manufacturer = "Raspberry Pi", product = "RP2 Boot", serial = null,
        driver = null, allowed = false, ports = emptyList(), error = null,
    )

    @Test
    fun `lists devices no serial driver drives, with no driver`() {
        devices = listOf(boot)
        send("1-1", "usb-list")
        assertEquals(
            """{"ok":true,"devices":[""" +
                """{"id":"/dev/bus/usb/001/004","ids":"2e8a:0003","manufacturer":"Raspberry Pi","product":"RP2 Boot",""" +
                """"serial":null,"driver":null,"allowed":false,"error":null,"ports":[]}]}""",
            reply("1-1"),
        )
    }

    @Test
    fun `allow takes a device with no serial driver, which waits only until it's allowed`() {
        devices = listOf(ch340, boot)
        send("1-1", "usb-allow")
        assertEquals(boot.id, asked.single().first)
        devices = listOf(ch340, boot.copy(allowed = true))
        asked.single().second(null)
        assertEquals(true, (parseJson(reply("1-1")) as Map<*, *>)["ok"])
        // Allowed, it has no port to wait for.
        send("1-2", "usb-allow")
        assertEquals("""{"ok":false,"error":"no USB device is waiting for access"}""", reply("1-2"))
        send("1-3", "usb-allow", "2e8a:0003")
        assertEquals(true, (parseJson(reply("1-3")) as Map<*, *>)["ok"])
        assertEquals(1, asked.size)
    }

    @Test
    fun `list takes no options`() {
        send("1-1", "usb-list", "extra")
        assertEquals("""{"ok":false,"error":"usb-list takes no options"}""", reply("1-1"))
        assertNull(asked.firstOrNull())
    }

    private fun send(id: String, vararg lines: String) {
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(lines.joinToString("\n", postfix = "\n"))
        tmp.renameTo(File(dir, "$id.req"))
        requests.processPending()
    }

    private fun reply(id: String) = File(dir, "$id.reply").readText()
}
