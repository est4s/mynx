package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ble-bind` and `ble-unbind`: BLE UART ports (`nusN`) in `bluetooth.conf`. */
class BleBindTest {
    private val base = createTempDirectory("ble-bind").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val conf = File(home, "$CONFIG_DIR/$BLUETOOTH_CONF")
    private val host = FakeBleHost()
    private val bt = FakeBluetoothHost()
    private val timers = mutableListOf<() -> Unit>()
    private val requests = MynxRequests(
        dir, home,
        later = bleRequests(home, host, bt, now = { 1_000_000L }, schedule = { _, run -> timers += run }),
        alive = { true },
    )

    private val c3 = "D4:E9:F4:A2:4A:06"
    private val nus = BleUartKind.NORDIC
    private val hm10 = BleUartKind.HM10

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun reply(id: String) = File(dir, "$id.reply").takeIf { it.exists() }?.readText()
    private fun refusal(message: String) = """{"ok":false,"error":${json(message)}}"""

    private fun ask(vararg lines: String): String? {
        send("9-9", *lines)
        return reply("9-9").also { File(dir, "9-9.reply").delete() }
    }

    private fun write(text: String) = conf.apply { parentFile.mkdirs() }.writeText(text)

    private fun nordicLink() = FakeBleLink(
        c3,
        listOf(
            BleService(
                nus.service,
                listOf(
                    BleChar(nus.service, nus.rx, 1, setOf(BleProp.WRITE, BleProp.WRITE_NO_RESPONSE), emptyList()),
                    BleChar(nus.service, nus.tx, 2, setOf(BleProp.NOTIFY), emptyList()),
                ),
            ),
        ),
    )

    private fun hm10Link() = FakeBleLink(
        c3,
        listOf(BleService(hm10.service, listOf(BleChar(hm10.service, hm10.rx, 1, setOf(BleProp.WRITE, BleProp.NOTIFY), emptyList())))),
    )

    // A scan that saw the C3 as [name], its address random or not, so names and address types are known.
    private fun scanned(name: String = "mynx-ble", random: Boolean = true) {
        send("0-1", "ble-scan", "seconds=1")
        host.scans.last().found(BleAdvert(c3, random, name, -50, emptyList(), emptyMap(), connectable = true, time = 1))
        timers.removeAt(0)()
    }

    private fun port(answer: String?): Map<*, *> = (parseJson(answer!!) as Map<*, *>)["port"] as Map<*, *>

    @Test
    fun `bind and unbind are ble requests`() {
        assertTrue(BLE_REQUEST_NAMES.containsAll(listOf("ble-bind", "ble-unbind")))
    }

    @Test
    fun `binds a scanned device by name, random address and all, after checking its UART`() {
        scanned()
        send("1-1", "ble-bind", "mynx-ble")
        val connect = host.connects.single()
        assertEquals(c3, connect.address)
        assertTrue(connect.randomAddress)
        assertFalse(conf.exists())
        val link = nordicLink()
        connect.ready(Result.success(link))
        assertEquals(
            """{"ok":true,"port":{"name":"nus0","address":"$c3","device":"mynx-ble","path":"/dev/serial/nus0",""" +
                """"url":null,"tcp_port":null,"linked":false,"in_use":false,"waiting":false,"error":null},"kind":"nordic-uart"}""",
            reply("1-1"),
        )
        assertEquals(listOf(BtBinding("nus0", c3, random = true)), loadBluetooth(conf).bindings)
        assertTrue(conf.readText().startsWith("# Bluetooth serial ports"))
        assertEquals(1, bt.changes)
        assertTrue(link.closed)
    }

    @Test
    fun `answers with the port as the app serves it once bound`() {
        bt.served = mapOf("nus0" to BtPortState(tcpPort = 2417, linked = false, inUse = false, waiting = false, error = null))
        send("1-1", "ble-bind", c3.lowercase())
        host.connects.single().ready(Result.success(nordicLink()))
        val port = port(reply("1-1"))
        assertEquals("rfc2217://localhost:2417", port["url"])
        assertEquals(null, port["device"])
        assertEquals(listOf(BtBinding("nus0", c3)), loadBluetooth(conf).bindings)
    }

    @Test
    fun `an HM-10 takes the lowest nus name free, counting lines the parser skips`() {
        write("rfcomm0 = AA:BB:CC:DD:EE:01\nnus0 = AA:BB:CC:DD:EE:02\nnus1 = nonsense\n")
        send("1-1", "ble-bind", c3)
        host.connects.single().ready(Result.success(hm10Link()))
        val answer = parseJson(reply("1-1")!!) as Map<*, *>
        assertEquals("hm-10", answer["kind"])
        assertEquals("nus2", port(reply("1-1"))["name"])
        assertTrue(conf.readText().endsWith("nus1 = nonsense\nnus2 = $c3\n"))
    }

    @Test
    fun `binds to the name asked`() {
        send("1-1", "ble-bind", c3, "nus7")
        host.connects.single().ready(Result.success(nordicLink()))
        assertEquals("nus7", port(reply("1-1"))["name"])
    }

    @Test
    fun `a device with no UART is refused, changing nothing`() {
        scanned(random = false)
        send("1-1", "ble-bind", "mynx-ble")
        assertFalse(host.connects.single().randomAddress)
        val link = FakeBleLink(c3, listOf(BleService(nus.service, emptyList())))
        host.connects.single().ready(Result.success(link))
        assertEquals(refusal("mynx-ble ($c3) has no Nordic UART or HM-10 service"), reply("1-1"))
        assertFalse(conf.exists())
        assertEquals(0, bt.changes)
        assertTrue(link.closed)
    }

    @Test
    fun `a failed connection is refused with why`() {
        send("1-1", "ble-bind", c3)
        host.connects.single().ready(Result.failure(Exception("status 133")))
        assertEquals(refusal("couldn't connect to $c3: status 133"), reply("1-1"))
        assertFalse(conf.exists())
    }

    @Test
    fun `a bad port name is refused before anything is asked`() {
        host.allowed = false
        assertEquals(refusal("'rfcomm0' isn't a BLE UART port's name: use nus0 to nus255 (rfcomm ports: mynx bt bind)"), ask("ble-bind", c3, "rfcomm0"))
        assertEquals(refusal("'nus256' isn't a BLE UART port's name: use nus0 to nus255"), ask("ble-bind", c3, "nus256"))
        assertEquals(refusal("which device? (an address, or a name: mynx ble scan finds them)"), ask("ble-bind"))
        assertEquals(refusal("which port or device? (mynx bt lists them)"), ask("ble-unbind"))
        assertTrue(host.asks.isEmpty())
    }

    @Test
    fun `a port taken by another device is refused before connecting`() {
        write("nus0 = AA:BB:CC:DD:EE:02\n")
        assertEquals(refusal("nus0 is already bound to AA:BB:CC:DD:EE:02"), ask("ble-bind", c3, "nus0"))
        assertTrue(host.connects.isEmpty())
    }

    @Test
    fun `a device already bound is checked again, and answers with its port and kind`() {
        write("nus3 = $c3 random\n")
        send("1-1", "ble-bind", c3)
        val connect = host.connects.single()
        assertTrue(connect.randomAddress)
        val link = hm10Link()
        connect.ready(Result.success(link))
        val answer = parseJson(reply("1-1")!!) as Map<*, *>
        assertEquals("nus3", (answer["port"] as Map<*, *>)["name"])
        assertEquals("hm-10", answer["kind"])
        assertEquals("nus3 = $c3 random\n", conf.readText())
        assertEquals(0, bt.changes)
        assertTrue(link.closed)
    }

    @Test
    fun `a device already bound to another nus port is refused before connecting`() {
        write("nus3 = $c3 random\n")
        assertEquals(refusal("$c3 is already bound to nus3"), ask("ble-bind", c3, "nus0"))
        assertTrue(host.connects.isEmpty())
    }

    @Test
    fun `a device already bound that has no UART now is refused, its line kept`() {
        // The same board running another program: its address doesn't change.
        write("nus3 = $c3\n")
        send("1-1", "ble-bind", c3)
        host.connects.single().ready(Result.success(FakeBleLink(c3, listOf(BleService(nus.service, emptyList())))))
        assertEquals(refusal("$c3 has no Nordic UART or HM-10 service (it's bound to nus3: mynx ble unbind nus3)"), reply("1-1"))
        assertEquals("nus3 = $c3\n", conf.readText())
        assertEquals(0, bt.changes)
    }

    @Test
    fun `a device bound to an rfcomm port is refused`() {
        write("rfcomm0 = $c3\n")
        assertEquals(refusal("$c3 is already bound to rfcomm0 (mynx bt unbind rfcomm0 first)"), ask("ble-bind", c3))
        assertTrue(host.connects.isEmpty())
    }

    @Test
    fun `a link mynx ble connect holds is used, and left open`() {
        send("2-1", "ble-connect", c3)
        val link = nordicLink()
        host.connects.single().ready(Result.success(link))
        send("2-2", "ble-bind", c3)
        assertEquals(1, host.connects.size)
        assertEquals("nus0", port(reply("2-2"))["name"])
        assertFalse(link.closed)
    }

    @Test
    fun `refused as the other ble requests are`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-bluetooth = off\n")
        assertEquals(refusal("android-bluetooth is off (mynx set android-bluetooth on)"), ask("ble-bind", c3))
        assertEquals(refusal("android-bluetooth is off (mynx set android-bluetooth on)"), ask("ble-unbind", "nus0"))
        File(home, "$CONFIG_DIR/settings.conf").delete()
        host.available = false
        assertEquals(refusal("this phone has no Bluetooth LE"), ask("ble-bind", c3))
        host.available = true
        host.enabled = false
        assertEquals(refusal("Bluetooth is off: turn it on in Android's settings (mynx bt settings)"), ask("ble-bind", c3))
        host.enabled = true
        host.allowed = false
        send("3-1", "ble-bind", c3)
        assertEquals(null, reply("3-1"))
        host.asks.single()("the dialog was refused")
        assertEquals(refusal("Bluetooth isn't allowed: the dialog was refused"), reply("3-1"))
        assertTrue(host.connects.isEmpty())
    }

    @Test
    fun `unbinds a nus port by name, address or device name`() {
        val lines = "rfcomm0 = $c3\nnus0 = AA:BB:CC:DD:EE:02\nnus1 = $c3 random\n"
        write(lines)
        assertEquals("""{"ok":true,"removed":["nus0"]}""", ask("ble-unbind", "nus0"))
        assertEquals(1, bt.changes)
        write(lines)
        assertEquals("""{"ok":true,"removed":["nus1"]}""", ask("ble-unbind", c3.lowercase()))
        assertEquals("rfcomm0 = $c3\nnus0 = AA:BB:CC:DD:EE:02\n", conf.readText())
        write(lines)
        scanned()
        assertEquals("""{"ok":true,"removed":["nus1"]}""", ask("ble-unbind", "MYNX-BLE"))
        host.devices = listOf(BtDevice("Buds", "AA:BB:CC:DD:EE:02", BtType.LE))
        assertEquals("""{"ok":true,"removed":["nus0"]}""", ask("ble-unbind", "Buds"))
        assertEquals("rfcomm0 = $c3\n", conf.readText())
    }

    @Test
    fun `unbinding nothing, or an rfcomm port, is refused`() {
        write("rfcomm0 = $c3\n")
        assertEquals(refusal("rfcomm0 is a Bluetooth serial port: use mynx bt unbind"), ask("ble-unbind", "rfcomm0"))
        assertEquals(refusal("no BLE UART port nus4 (mynx bt lists them)"), ask("ble-unbind", "nus4"))
        assertEquals(refusal("no BLE UART port is bound to $c3 (mynx bt lists them)"), ask("ble-unbind", c3))
        assertEquals(refusal("no BLE UART port is bound to esp32 (mynx bt lists them)"), ask("ble-unbind", "esp32"))
        assertEquals(0, bt.changes)
        assertEquals("rfcomm0 = $c3\n", conf.readText())
    }

    @Test
    fun `unbinding by port works with Bluetooth off`() {
        write("nus0 = $c3\n")
        host.enabled = false
        assertEquals("""{"ok":true,"removed":["nus0"]}""", ask("ble-unbind", "nus0"))
    }

    @Test
    fun `bind and unbind name their undo steps`() {
        requests.start()
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("undo-keep = 3\n")
        send("4-1", "ble-bind", c3)
        host.connects.single().ready(Result.success(nordicLink()))
        assertEquals("nus0", port(reply("4-1"))["name"])
        ask("ble-unbind", "nus0")
        assertEquals("""{"ok":true,"undone":"ble unbind nus0"}""", ask("undo"))
        assertEquals("""{"ok":true,"undone":"ble bind $c3"}""", ask("undo"))
    }
}
