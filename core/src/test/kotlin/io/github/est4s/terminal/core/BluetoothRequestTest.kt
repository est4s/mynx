package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeBluetoothHost : BluetoothHost {
    override var available = true
    override var allowed = true
    override var enabled = true
    var devices = listOf<BtDevice>()
    var served = mapOf<String, BtPortState>()
    val asks = mutableListOf<(String?) -> Unit>()
    var settingsError: String? = null
    var settingsOpened = 0
    var changes = 0

    override fun bonded() = devices.also { check(allowed && enabled) { "bonded() needs the permission and Bluetooth on" } }
    override fun ports() = served
    override fun askPermission(done: (String?) -> Unit) { asks += done }
    override fun openSettings(): String? = settingsError.also { if (it == null) settingsOpened++ }
    override fun bindingsChanged() { changes++ }
}

class BluetoothRequestTest {
    private val base = createTempDirectory("bt").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val conf = File(home, "$CONFIG_DIR/$BLUETOOTH_CONF")
    private val host = FakeBluetoothHost()
    private val requests = MynxRequests(dir, home, later = bluetoothRequests(home, host), alive = { true })

    private val cam = BtDevice("mynx-spp", "D4:E9:F4:A2:4A:02", BtType.CLASSIC)
    private val buds = BtDevice("Buds", "AA:BB:CC:DD:EE:01", BtType.DUAL)

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun reply(id: String) = File(dir, "$id.reply").readText()

    private fun ask(vararg lines: String): String {
        send("1-1", *lines)
        return reply("1-1").also { File(dir, "1-1.reply").delete() }
    }

    private fun refusal(message: String) = """{"ok":false,"error":${json(message)}}"""

    @Test
    fun `lists the paired devices and the ports, with their state`() {
        host.devices = listOf(cam, buds.copy(name = null, type = BtType.LE))
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm0 = D4:E9:F4:A2:4A:02\nrfcomm1 = 11:22:33:44:55:66\n")
        host.served = mapOf(
            "rfcomm0" to BtPortState(tcpPort = 2218, linked = true, inUse = true, waiting = false, error = null),
            "rfcomm1" to BtPortState(tcpPort = 2219, linked = false, inUse = false, waiting = false, error = "host is down"),
        )
        assertEquals(
            """{"ok":true,"enabled":true,"devices":[""" +
                """{"name":"mynx-spp","address":"D4:E9:F4:A2:4A:02","type":"classic","port":"rfcomm0"},""" +
                """{"name":null,"address":"AA:BB:CC:DD:EE:01","type":"le","port":null}],"ports":[""" +
                """{"name":"rfcomm0","address":"D4:E9:F4:A2:4A:02","device":"mynx-spp","path":"/dev/serial/rfcomm0",""" +
                """"url":"rfc2217://localhost:2218","tcp_port":2218,"linked":true,"in_use":true,"waiting":false,"error":null},""" +
                """{"name":"rfcomm1","address":"11:22:33:44:55:66","device":null,"path":"/dev/serial/rfcomm1",""" +
                """"url":"rfc2217://localhost:2219","tcp_port":2219,"linked":false,"in_use":false,"waiting":false,"error":"host is down"}]}""",
            ask("bt-list"),
        )
    }

    @Test
    fun `with Bluetooth off, no devices, but the ports from the config, not served`() {
        host.enabled = false
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm0 = D4:E9:F4:A2:4A:02\n")
        assertEquals(
            """{"ok":true,"enabled":false,"devices":[],"ports":[""" +
                """{"name":"rfcomm0","address":"D4:E9:F4:A2:4A:02","device":null,"path":"/dev/serial/rfcomm0",""" +
                """"url":null,"tcp_port":null,"linked":false,"in_use":false,"waiting":false,"error":null}]}""",
            ask("bt-list"),
        )
    }

    @Test
    fun `binds a paired device by name to the lowest free port, and tells the host`() {
        host.devices = listOf(buds, cam)
        conf.apply { parentFile.mkdirs() }.writeText("# mine\nrfcomm0 = AA:BB:CC:DD:EE:01\n")
        val answer = parseJson(ask("bt-bind", "mynx-spp")) as Map<*, *>
        assertEquals(true, answer["ok"])
        val port = answer["port"] as Map<*, *>
        assertEquals("rfcomm1", port["name"])
        assertEquals("D4:E9:F4:A2:4A:02", port["address"])
        assertEquals("mynx-spp", port["device"])
        assertEquals("/dev/serial/rfcomm1", port["path"])
        assertEquals("# mine\nrfcomm0 = AA:BB:CC:DD:EE:01\nrfcomm1 = D4:E9:F4:A2:4A:02\n", conf.readText())
        assertEquals(1, host.changes)
    }

    @Test
    fun `answers with the port as the host serves it after the change`() {
        host.devices = listOf(cam)
        val served = BtPortState(tcpPort = 2217, linked = false, inUse = false, waiting = false, error = null)
        val host = object : BluetoothHost by host {
            override fun ports() = if (conf.isFile) mapOf("rfcomm0" to served) else emptyMap()
        }
        val requests = MynxRequests(dir, home, later = bluetoothRequests(home, host), alive = { true })
        File(dir, "2-1.req").writeText("bt-bind\nmynx-spp\n")
        requests.processPending()
        val port = (parseJson(reply("2-1")) as Map<*, *>)["port"] as Map<*, *>
        assertEquals("rfc2217://localhost:2217", port["url"])
        assertEquals(2217L, port["tcp_port"])
    }

    @Test
    fun `a name matches exactly first, then in any case`() {
        host.devices = listOf(cam, BtDevice("MYNX-SPP", "11:22:33:44:55:66", BtType.CLASSIC))
        ask("bt-bind", "mynx-spp")
        ask("bt-bind", "Buds")
        host.devices += buds
        ask("bt-bind", "buds")
        assertEquals(
            listOf(BtBinding("rfcomm0", "D4:E9:F4:A2:4A:02"), BtBinding("rfcomm1", "AA:BB:CC:DD:EE:01")),
            loadBluetooth(conf).bindings,
        )
    }

    @Test
    fun `an ambiguous name is refused, listing the matches`() {
        host.devices = listOf(cam, BtDevice("MYNX-SPP", "11:22:33:44:55:66", BtType.CLASSIC))
        assertEquals(
            refusal("2 paired devices are called mynx-Spp: give its address (D4:E9:F4:A2:4A:02 mynx-spp, 11:22:33:44:55:66 MYNX-SPP)"),
            ask("bt-bind", "mynx-Spp"),
        )
        assertFalse(conf.exists())
    }

    @Test
    fun `an unknown name is refused, saying where to pair it`() {
        host.devices = listOf(cam)
        assertEquals(
            refusal("no paired device esp32 (pair it in Android's Bluetooth settings: mynx bt settings)"),
            ask("bt-bind", "esp32"),
        )
    }

    @Test
    fun `an address is taken as it is, in any case, paired or not, Bluetooth on or off`() {
        host.enabled = false
        val port = (parseJson(ask("bt-bind", "d4:e9:f4:a2:4a:02", "rfcomm3")) as Map<*, *>)["port"] as Map<*, *>
        assertEquals("rfcomm3", port["name"])
        assertEquals(null, port["device"])
        assertEquals(listOf(BtBinding("rfcomm3", "D4:E9:F4:A2:4A:02")), loadBluetooth(conf).bindings)
    }

    @Test
    fun `a name needs Bluetooth on`() {
        host.enabled = false
        assertEquals(refusal("Bluetooth is off: turn it on, or give the device's address"), ask("bt-bind", "mynx-spp"))
    }

    @Test
    fun `a device already bound answers with its port, changing nothing`() {
        host.devices = listOf(cam)
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm2 = D4:E9:F4:A2:4A:02\n")
        val port = (parseJson(ask("bt-bind", "mynx-spp")) as Map<*, *>)["port"] as Map<*, *>
        assertEquals("rfcomm2", port["name"])
        assertEquals(0, host.changes)
        assertEquals(refusal("D4:E9:F4:A2:4A:02 is already bound to rfcomm2"), ask("bt-bind", "mynx-spp", "rfcomm0"))
    }

    @Test
    fun `a port that's taken, or isn't a port's name, is refused`() {
        host.devices = listOf(cam, buds)
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm0 = AA:BB:CC:DD:EE:01\n")
        assertEquals(refusal("rfcomm0 is already bound to AA:BB:CC:DD:EE:01"), ask("bt-bind", "mynx-spp", "rfcomm0"))
        assertEquals(refusal(badRfcommName("ttyUSB0")), ask("bt-bind", "mynx-spp", "ttyUSB0"))
        assertEquals(refusal("bt-bind needs a device: its name or address (mynx bt lists them)"), ask("bt-bind"))
    }

    @Test
    fun `unbinds a port, or every port of a device by address or name`() {
        host.devices = listOf(cam, buds)
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm0 = AA:BB:CC:DD:EE:01\nrfcomm1 = D4:E9:F4:A2:4A:02\n")
        assertEquals("""{"ok":true,"removed":["rfcomm1"]}""", ask("bt-unbind", "mynx-spp"))
        assertEquals(1, host.changes)
        conf.writeText("rfcomm0 = AA:BB:CC:DD:EE:01\nrfcomm1 = D4:E9:F4:A2:4A:02\n")
        assertEquals("""{"ok":true,"removed":["rfcomm0"]}""", ask("bt-unbind", "aa:bb:cc:dd:ee:01"))
        assertEquals("""{"ok":true,"removed":["rfcomm1"]}""", ask("bt-unbind", "rfcomm1"))
        assertEquals("", conf.readText())
    }

    @Test
    fun `unbinding nothing is refused`() {
        host.devices = listOf(cam)
        assertEquals(refusal("no Bluetooth port rfcomm4 (mynx bt lists them)"), ask("bt-unbind", "rfcomm4"))
        assertEquals(refusal("no Bluetooth port is bound to mynx-spp (mynx bt lists them)"), ask("bt-unbind", "mynx-spp"))
        assertEquals(refusal("bt-unbind needs a port or a device (mynx bt lists them)"), ask("bt-unbind"))
        assertEquals(0, host.changes)
    }

    @Test
    fun `bind and unbind name their undo steps`() {
        host.devices = listOf(cam)
        requests.start()
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("undo-keep = 3\n")
        ask("bt-bind", "mynx-spp")
        ask("bt-unbind", "rfcomm0")
        assertEquals("""{"ok":true,"undone":"bt unbind rfcomm0"}""", ask("undo"))
        assertEquals("""{"ok":true,"undone":"bt bind mynx-spp"}""", ask("undo"))
    }

    @Test
    fun `everything is refused while android-bluetooth is off`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-bluetooth = off\n")
        val off = refusal("android-bluetooth is off (mynx set android-bluetooth on)")
        for (request in listOf("bt-list", "bt-bind", "bt-unbind", "bt-settings")) assertEquals(off, ask(request, "x"))
    }

    @Test
    fun `everything is refused on a phone without Bluetooth`() {
        host.available = false
        for (request in listOf("bt-list", "bt-bind", "bt-unbind", "bt-settings")) {
            assertEquals(refusal("this phone has no Bluetooth"), ask(request, "x"))
        }
    }

    @Test
    fun `without the permission, asks for it, then answers`() {
        host.allowed = false
        host.devices = listOf(cam)
        send("3-1", "bt-list")
        assertEquals("120", File(dir, "3-1.wait").readText())
        assertFalse(File(dir, "3-1.reply").exists())
        host.allowed = true
        host.asks.single()(null)
        assertTrue(reply("3-1").startsWith("""{"ok":true,"enabled":true,"devices":[{"name":"mynx-spp""""))
    }

    // `mynx usb url|path|monitor` merge the rfcomm ports: a USB command mustn't open a Bluetooth dialog.
    @Test
    fun `listing with --no-ask never asks, and without the permission it's refused at once`() {
        host.allowed = false
        assertEquals(refusal("Bluetooth isn't allowed yet (mynx bt asks)"), ask("bt-list", "--no-ask"))
        assertTrue(host.asks.isEmpty())
        host.allowed = true
        host.devices = listOf(cam)
        assertTrue(ask("bt-list", "--no-ask").startsWith("""{"ok":true,"enabled":true"""))
        assertEquals(refusal("bt-list takes no options but --no-ask"), ask("bt-list", "--yes"))
    }

    @Test
    fun `unbinding a device removes every port it has, even lines the parser skips`() {
        host.devices = listOf(cam)
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm0 = D4:E9:F4:A2:4A:02\nrfcomm1 = D4:E9:F4:A2:4A:02\n")
        assertEquals("""{"ok":true,"removed":["rfcomm0","rfcomm1"]}""", ask("bt-unbind", "mynx-spp"))
        assertEquals("", conf.readText())
    }

    @Test
    fun `a bind that waited for the permission still names its undo step`() {
        host.allowed = false
        host.devices = listOf(cam)
        requests.start()
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("undo-keep = 3\n")
        send("3-1", "bt-bind", "mynx-spp")
        host.allowed = true
        host.asks.single()(null)
        assertEquals(listOf(BtBinding("rfcomm0", "D4:E9:F4:A2:4A:02")), loadBluetooth(conf).bindings)
        assertEquals("""{"ok":true,"undone":"bt bind mynx-spp"}""", ask("undo"))
    }

    @Test
    fun `a denied permission is refused with the reason`() {
        host.allowed = false
        send("3-1", "bt-unbind", "rfcomm0")
        host.asks.single()("Nearby devices was denied (allow it in mynx's Android settings: Permissions)")
        assertEquals(
            refusal("Bluetooth isn't allowed: Nearby devices was denied (allow it in mynx's Android settings: Permissions)"),
            reply("3-1"),
        )
    }

    @Test
    fun `settings opens Android's Bluetooth settings, needing no permission`() {
        host.allowed = false
        assertEquals("""{"ok":true}""", ask("bt-settings"))
        assertEquals(1, host.settingsOpened)
        assertTrue(host.asks.isEmpty())
        host.settingsError = "mynx isn't on screen"
        assertEquals(refusal("mynx isn't on screen"), ask("bt-settings"))
    }

    @Test
    fun `bind refuses a nus port, which is mynx ble bind's, and picks rfcomm names past nus lines`() {
        host.devices = listOf(cam, buds)
        assertEquals(refusal("nus0 is a Bluetooth LE UART port: use mynx ble bind"), ask("bt-bind", "mynx-spp", "nus0"))
        conf.apply { parentFile.mkdirs() }.writeText("nus0 = AA:BB:CC:DD:EE:01 random\n")
        val port = (parseJson(ask("bt-bind", "mynx-spp")) as Map<*, *>)["port"] as Map<*, *>
        assertEquals("rfcomm0", port["name"])
    }

    @Test
    fun `bind refuses a device bound to a nus port`() {
        host.devices = listOf(buds)
        conf.apply { parentFile.mkdirs() }.writeText("nus0 = AA:BB:CC:DD:EE:01\n")
        assertEquals(refusal("AA:BB:CC:DD:EE:01 is already bound to nus0 (mynx ble unbind nus0 first)"), ask("bt-bind", "Buds"))
        assertEquals(0, host.changes)
    }

    @Test
    fun `unbind removes nus ports too, by name or device`() {
        host.devices = listOf(cam, buds)
        conf.apply { parentFile.mkdirs() }.writeText("rfcomm0 = D4:E9:F4:A2:4A:02\nnus0 = AA:BB:CC:DD:EE:01 random\n")
        assertEquals("""{"ok":true,"removed":["nus0"]}""", ask("bt-unbind", "nus0"))
        conf.writeText("rfcomm0 = D4:E9:F4:A2:4A:02\nnus0 = AA:BB:CC:DD:EE:01 random\n")
        assertEquals("""{"ok":true,"removed":["nus0"]}""", ask("bt-unbind", "Buds"))
        assertEquals("rfcomm0 = D4:E9:F4:A2:4A:02\n", conf.readText())
    }

    @Test
    fun `lists nus ports with the others`() {
        host.devices = listOf(buds)
        conf.apply { parentFile.mkdirs() }.writeText("nus0 = AA:BB:CC:DD:EE:01 random\n")
        host.served = mapOf("nus0" to BtPortState(tcpPort = 2417, linked = true, inUse = true, waiting = false, error = null))
        val answer = parseJson(ask("bt-list")) as Map<*, *>
        assertEquals("nus0", ((answer["devices"] as List<*>).single() as Map<*, *>)["port"])
        val port = (answer["ports"] as List<*>).single() as Map<*, *>
        assertEquals("nus0", port["name"])
        assertEquals("/dev/serial/nus0", port["path"])
        assertEquals("rfc2217://localhost:2417", port["url"])
        assertEquals(true, port["linked"])
    }
}
