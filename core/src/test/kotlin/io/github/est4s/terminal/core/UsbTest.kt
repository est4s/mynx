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
    driver = "ch34x", allowed = true, ports = emptyList(), error = null,
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
        devices = listOf(ch340.copy(ports = listOf(SerialPortInfo("ttyUSB0", 2217, inUse = true))), esp)
        send("1-1", "usb-list")
        assertEquals(
            """{"ok":true,"devices":[""" +
                """{"id":"/dev/bus/usb/001/002","ids":"1a86:7523","manufacturer":"QinHeng Electronics","product":"USB Serial",""" +
                """"serial":null,"driver":"ch34x","allowed":true,"error":null,"ports":[""" +
                """{"name":"ttyUSB0","path":"/dev/serial/ttyUSB0","url":"rfc2217://localhost:2217","tcp_port":2217,"in_use":true}]},""" +
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
    fun `allow with nothing waiting says so, and an allowed device is answered at once`() {
        devices = emptyList()
        send("1-1", "usb-allow")
        assertEquals("""{"ok":false,"error":"no USB serial device is waiting for access"}""", reply("1-1"))
        devices = listOf(ch340)
        send("1-2", "usb-allow", "1a86:7523")
        assertEquals(true, (parseJson(reply("1-2")) as Map<*, *>)["ok"])
        assertEquals(emptyList(), asked)
        send("1-3", "usb-allow", "dead:beef")
        assertEquals("""{"ok":false,"error":"no USB serial device dead:beef (mynx usb lists them)"}""", reply("1-3"))
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
