package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A [BleHost] the tests drive by hand: they see what was asked and answer it. */
class FakeBleHost : BleHost {
    override var available = true
    override var allowed = true
    override var enabled = true
    override var inFront = true
    var devices = listOf<BtDevice>()
    val asks = mutableListOf<(String?) -> Unit>()

    class Scan(val filter: BleScanFilter, val found: (BleAdvert) -> Unit, val failed: (String) -> Unit) {
        var stopped = false
    }
    val scans = mutableListOf<Scan>()

    class Connect(
        val address: String,
        val randomAddress: Boolean,
        val ready: (Result<BleLink>) -> Unit,
        val dropped: (String) -> Unit,
    )
    val connects = mutableListOf<Connect>()

    override fun bonded() = devices.also { check(allowed && enabled) { "bonded() needs the permission and Bluetooth on" } }
    override fun askPermission(done: (String?) -> Unit) { asks += done }
    override fun scan(filter: BleScanFilter, found: (BleAdvert) -> Unit, failed: (String) -> Unit): BleStop {
        val scan = Scan(filter, found, failed).also { scans += it }
        return BleStop { scan.stopped = true }
    }
    override fun connect(address: String, randomAddress: Boolean, ready: (Result<BleLink>) -> Unit, dropped: (String) -> Unit) {
        connects += Connect(address, randomAddress, ready, dropped)
    }
}

/** A [BleLink] with [services]; operations wait in [pending] until a test answers them. */
class FakeBleLink(override val address: String, override val services: List<BleService>, override var mtu: Int = 23) : BleLink {
    val pending = mutableListOf<Pair<String, Any>>()
    var closed = false

    override fun read(char: BleChar, done: (Result<ByteArray>) -> Unit) { pending += "read ${char.uuid}" to done }
    override fun write(char: BleChar, value: ByteArray, withResponse: Boolean, done: (Result<Unit>) -> Unit) {
        pending += "write ${char.uuid} ${value.joinToString(" ") { "%02x".format(it) }} $withResponse" to done
    }
    override fun subscribe(char: BleChar, value: (ByteArray, Long) -> Unit, done: (Result<Unit>) -> Unit): BleStop {
        pending += "subscribe ${char.uuid}" to (value to done)
        return BleStop { pending += "unsubscribe ${char.uuid}${if (closed) " after closing" else ""}" to Unit }
    }
    override fun close() { closed = true }

    /** Answers the waiting operation [op] (as [pending] names it) with [result]. */
    @Suppress("UNCHECKED_CAST")
    fun answer(op: String, result: Result<Any>) {
        val i = pending.indexOfFirst { it.first == op }
        check(i >= 0) { "no $op waiting, only ${pending.map { it.first }}" }
        (pending.removeAt(i).second as (Result<Any>) -> Unit)(result)
    }

    /** The waiting subscription to [uuid]: where its values go, and its done. */
    @Suppress("UNCHECKED_CAST")
    fun subscription(uuid: String): Pair<(ByteArray, Long) -> Unit, (Result<Unit>) -> Unit> =
        pending.last { it.first == "subscribe $uuid" }.second as Pair<(ByteArray, Long) -> Unit, (Result<Unit>) -> Unit>
}

class BleTest {
    private val base = createTempDirectory("ble").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val host = FakeBleHost()
    private var clock = 1_000_000L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val requests = MynxRequests(
        dir, home,
        later = bleRequests(home, host, now = { clock }, schedule = { ms, run -> timers += ms to run }),
        alive = { true },
    )

    private val c3 = "D4:E9:F4:A2:4A:02"
    private val battery = "0000180f-0000-1000-8000-00805f9b34fb"
    private val level = "00002a19-0000-1000-8000-00805f9b34fb"

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
    private fun reply(id: String) = file("$id.reply").takeIf { it.exists() }?.readText()
    private fun lines(id: String) = file("$id.stream").takeIf { it.exists() }?.readLines().orEmpty()
    private fun refusal(message: String) = """{"ok":false,"error":${json(message)}}"""

    private fun cancel(id: String) {
        file("$id.cancel").writeText("")
        requests.sweep()
    }

    private fun advert(
        address: String = c3,
        name: String? = "mynx-ble",
        rssi: Int = -60,
        services: List<String> = emptyList(),
        time: Long = clock,
        random: Boolean = false,
        manufacturer: Map<Int, ByteArray> = emptyMap(),
    ) = BleAdvert(address, random, name, rssi, services, manufacturer, connectable = true, time = time)

    // Runs the timers set so far (the tests don't wait).
    private fun runTimers() {
        val due = timers.toList()
        timers.clear()
        due.forEach { it.second() }
    }

    @Test
    fun `answers every planned request`() {
        assertEquals(BLE_REQUEST_NAMES.toSet(), bleRequests(home, host).keys)
    }

    @Test
    fun `refused with the setting off, no Bluetooth LE, or Bluetooth off`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-bluetooth = off\n")
        send("1-1", "ble-scan")
        assertEquals(refusal("android-bluetooth is off (mynx set android-bluetooth on)"), reply("1-1"))
        File(home, "$CONFIG_DIR/settings.conf").delete()
        host.available = false
        send("1-2", "ble-scan-stream")
        assertEquals(refusal("this phone has no Bluetooth LE"), reply("1-2"))
        host.available = true
        host.enabled = false
        send("1-3", "ble-scan")
        assertEquals(refusal("Bluetooth is off: turn it on in Android's settings (mynx bt settings)"), reply("1-3"))
        assertTrue(host.scans.isEmpty() && host.connects.isEmpty())
    }

    @Test
    fun `asks for the permission first, and goes on once it's given`() {
        host.allowed = false
        send("1-1", "ble-scan", "seconds=2")
        assertEquals(null, reply("1-1"))
        assertTrue(host.scans.isEmpty())
        host.allowed = true
        host.asks.single()(null)
        assertEquals(BleScanFilter(), host.scans.single().filter)
        assertEquals(2000L, timers.single().first)
    }

    @Test
    fun `a refused permission refuses the request with why`() {
        host.allowed = false
        send("1-1", "ble-scan-stream")
        host.asks.single()("the dialog was refused")
        assertEquals(refusal("Bluetooth isn't allowed: the dialog was refused"), reply("1-1"))
        assertTrue(host.scans.isEmpty())
    }

    @Test
    fun `bad options are refused before anything is asked`() {
        host.allowed = false
        val bad = mapOf(
            listOf("ble-scan", "seconds=0") to "seconds is 1 to 60",
            listOf("ble-scan", "seconds=61") to "seconds is 1 to 60",
            listOf("ble-scan", "seconds=x") to "seconds is 1 to 60",
            listOf("ble-scan", "loud") to "unknown option 'loud' for ble-scan",
            listOf("ble-scan", "service=nope") to "'nope' isn't a UUID or a known name",
            listOf("ble-scan-stream", "seconds=5") to "unknown option 'seconds=5' for ble-scan-stream",
            listOf("ble-scan", "all=yes") to "unknown option 'all=yes' for ble-scan",
        )
        bad.entries.forEachIndexed { i, (lines, error) ->
            send("2-$i", *lines.toTypedArray())
            assertEquals(refusal(error), reply("2-$i"), lines.toString())
        }
        assertTrue(host.asks.isEmpty())
    }

    @Test
    fun `a scan answers each device once, its latest advertisement, strongest first`() {
        send("1-1", "ble-scan")
        assertEquals(5000L, timers.single().first)
        val scan = host.scans.single()
        scan.found(advert(rssi = -80, time = 1))
        scan.found(advert(address = "11:22:33:44:55:66", name = "Buds", rssi = -50, services = listOf(battery), time = 2))
        scan.found(
            advert(rssi = -40, time = 3, random = true, services = listOf(battery), manufacturer = mapOf(0x02e5 to byteArrayOf(1, 0x2a))),
        )
        assertEquals(null, reply("1-1"))
        runTimers()
        assertTrue(scan.stopped)
        assertEquals(
            """{"ok":true,"devices":[""" +
                """{"address":"$c3","address_type":"random","name":"mynx-ble","rssi":-40,"services":["$battery"],""" +
                """"manufacturer":[{"company":741,"hex":"01 2a"}],"connectable":true,"time":3},""" +
                """{"address":"11:22:33:44:55:66","address_type":"public","name":"Buds","rssi":-50,"services":["$battery"],""" +
                """"manufacturer":[],"connectable":true,"time":2}]}""",
            reply("1-1"),
        )
    }

    @Test
    fun `without all, devices with no name and no services are left out`() {
        send("1-1", "ble-scan")
        send("1-2", "ble-scan", "all")
        host.scans.forEach {
            it.found(advert(address = "11:22:33:44:55:66", name = null))
            it.found(advert(name = null, services = listOf(battery)))
        }
        runTimers()
        val some = parseJson(reply("1-1")!!) as Map<*, *>
        val all = parseJson(reply("1-2")!!) as Map<*, *>
        assertEquals(listOf(c3), (some["devices"] as List<*>).map { (it as Map<*, *>)["address"] })
        assertEquals(2, (all["devices"] as List<*>).size)
    }

    @Test
    fun `name and service filters go to the host, and are matched here too`() {
        send("1-1", "ble-scan", "name=BLE", "service=battery-service", "seconds=1")
        val scan = host.scans.single()
        assertEquals(BleScanFilter(name = "BLE", service = battery), scan.filter)
        assertEquals(1000L, timers.single().first)
        scan.found(advert(services = listOf(battery)))
        scan.found(advert(address = "11:22:33:44:55:66", name = "other", services = listOf(battery)))
        scan.found(advert(address = "11:22:33:44:55:77", name = "mynx-ble-2"))
        runTimers()
        val devices = (parseJson(reply("1-1")!!) as Map<*, *>)["devices"] as List<*>
        assertEquals(listOf(c3), devices.map { (it as Map<*, *>)["address"] })
    }

    @Test
    fun `a scan that fails is refused with why, and a cancelled one is stopped`() {
        send("1-1", "ble-scan")
        host.scans[0].failed("Android refused the scan (code 2)")
        assertEquals(refusal("the scan failed: Android refused the scan (code 2)"), reply("1-1"))
        assertTrue(host.scans[0].stopped)
        send("1-2", "ble-scan")
        cancel("1-2")
        assertTrue(host.scans[1].stopped)
    }

    @Test
    fun `a stream sends each device at most once a second until cancelled`() {
        send("1-1", "ble-scan-stream", "name=mynx")
        assertEquals("stream", file("1-1.wait").readText())
        val scan = host.scans.single()
        assertEquals(BleScanFilter(name = "mynx"), scan.filter)
        scan.found(advert(time = 1000))
        scan.found(advert(time = 1500))
        scan.found(advert(address = "11:22:33:44:55:66", name = "mynx-2", time = 1600))
        scan.found(advert(name = "other", address = "11:22:33:44:55:77", time = 1600))
        scan.found(advert(time = 2000))
        assertEquals(
            listOf("$c3 1000", "11:22:33:44:55:66 1600", "$c3 2000"),
            lines("1-1").map { val m = parseJson(it) as Map<*, *>; "${m["address"]} ${m["time"]}" },
        )
        cancel("1-1")
        assertTrue(scan.stopped)
        assertEquals("""{"ok":true}""", reply("1-1"))
    }

    @Test
    fun `a sixth scan within 30 s is refused, as Android would scan nothing`() {
        repeat(5) {
            send("1-$it", "ble-scan-stream")
            clock += 5000
        }
        send("1-5", "ble-scan")
        assertEquals(
            refusal("Android allows 5 scans in 30 s and would find nothing: try again in 5 s"),
            reply("1-5"),
        )
        assertEquals(5, host.scans.size)
        clock += 5000
        send("1-6", "ble-scan")
        assertEquals(6, host.scans.size)
    }

    @Test
    fun `an unfiltered scan in the background is refused, saying how to scan`() {
        host.inFront = false
        send("1-1", "ble-scan", "all")
        assertEquals(
            refusal(
                "with mynx in the background, Android only finds devices a scan filters for: " +
                    "give a name or a service, or bring mynx on screen",
            ),
            reply("1-1"),
        )
        send("1-2", "ble-scan-stream", "service=2a19")
        assertEquals(1, host.scans.size)
    }

    @Test
    fun `bad device arguments are refused before anything is asked`() {
        host.allowed = false
        val bad = mapOf(
            listOf("ble-services") to "which device? (an address, or a name: mynx ble scan finds them)",
            listOf("ble-services", c3, "all") to "unknown option 'all' for ble-services",
            listOf("ble-read", c3) to "which characteristic? (a UUID or a name: mynx ble services $c3 lists them)",
            listOf("ble-read", c3, "2a19", "as=u64") to "'u64' isn't a format: hex, text, u8, u16, u32, i8, i16, i32 or float32",
            listOf("ble-read", c3, "nope") to "'nope' isn't a UUID or a known name",
            listOf("ble-read", c3, "nope/2a19") to "'nope' isn't a UUID or a known name",
            listOf("ble-read", c3, "2a19", "no-response") to "unknown option 'no-response' for ble-read",
            listOf("ble-write", c3, "2a19") to "what to write? (a value: hex unless as= says)",
            listOf("ble-write", c3, "2a19", "0g") to "'0g' isn't hex: two digits a byte, like 0a1b or 0a 1b",
            listOf("ble-write", c3, "2a19", "300", "as=u8") to "u8 is 0 to 255, not '300'",
            listOf("ble-notify", c3) to "which characteristic? (a UUID or a name: mynx ble services $c3 lists them)",
            listOf("ble-notify", c3, "2a19", "no-response") to "unknown option 'no-response' for ble-notify",
            listOf("ble-connect") to "which device? (an address, or a name: mynx ble scan finds them)",
            listOf("ble-connect", c3, "as=u8") to "unknown option 'as=u8' for ble-connect",
        )
        bad.entries.forEachIndexed { i, (lines, error) ->
            send("2-$i", *lines.toTypedArray())
            assertEquals(refusal(error), reply("2-$i"), lines.toString())
        }
        assertTrue(host.asks.isEmpty())
    }

    private val custom = "12345678-1234-5678-1234-56789abcdef0"
    private val box = "12345678-1234-5678-1234-56789abcdef1"
    private val counter = "12345678-1234-5678-1234-56789abcdef2"
    private val nus = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private val rx = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private val tx = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val cccd = "00002902-0000-1000-8000-00805f9b34fb"

    private fun c3Link(mtu: Int = 23) = FakeBleLink(
        c3,
        listOf(
            BleService(battery, listOf(BleChar(battery, level, 1, setOf(BleProp.READ, BleProp.NOTIFY), listOf(cccd)))),
            BleService(
                custom,
                listOf(
                    BleChar(custom, box, 2, setOf(BleProp.READ, BleProp.WRITE), emptyList()),
                    BleChar(custom, counter, 3, setOf(BleProp.INDICATE), listOf(cccd)),
                ),
            ),
            BleService(
                nus,
                listOf(
                    BleChar(nus, rx, 4, setOf(BleProp.WRITE, BleProp.WRITE_NO_RESPONSE), emptyList()),
                    BleChar(nus, tx, 5, setOf(BleProp.NOTIFY), listOf(cccd)),
                ),
            ),
        ),
        mtu,
    )

    // Hands [link] to the request waiting for a connection.
    private fun connected(link: FakeBleLink = c3Link()) = link.also { host.connects.last().ready(Result.success(it)) }

    @Test
    fun `lists the services of a device, connecting for it and disconnecting after`() {
        send("1-1", "ble-services", c3.lowercase())
        val connect = host.connects.single()
        assertEquals(c3, connect.address)
        assertFalse(connect.randomAddress)
        val link = connected(c3Link(mtu = 247))
        assertEquals(
            """{"ok":true,"device":{"address":"$c3","name":null},"mtu":247,"services":[""" +
                """{"uuid":"$battery","name":"battery-service","characteristics":[""" +
                """{"uuid":"$level","name":"battery-level","properties":["read","notify"],"descriptors":["$cccd"]}]},""" +
                """{"uuid":"$custom","name":null,"characteristics":[""" +
                """{"uuid":"$box","name":null,"properties":["read","write"],"descriptors":[]},""" +
                """{"uuid":"$counter","name":null,"properties":["indicate"],"descriptors":["$cccd"]}]},""" +
                """{"uuid":"$nus","name":"nordic-uart","characteristics":[""" +
                """{"uuid":"$rx","name":"nus-rx","properties":["write","write-no-response"],"descriptors":[]},""" +
                """{"uuid":"$tx","name":"nus-tx","properties":["notify"],"descriptors":["$cccd"]}]}]}""",
            reply("1-1"),
        )
        assertTrue(link.closed)
    }

    @Test
    fun `reads a value in its known format, or the one asked`() {
        send("1-1", "ble-read", c3, "battery-level")
        val link = connected()
        link.answer("read $level", Result.success(byteArrayOf(42)))
        assertEquals(
            """{"ok":true,"device":{"address":"$c3","name":null},"char":"$level","name":"battery-level",""" +
                """"as":"u8","value":42,"hex":"2a"}""",
            reply("1-1"),
        )
        assertTrue(link.closed)
        send("1-2", "ble-read", c3, box.uppercase(), "as=text")
        connected().answer("read $box", Result.success("hi".toByteArray()))
        assertEquals(
            """{"ok":true,"device":{"address":"$c3","name":null},"char":"$box","name":null,""" +
                """"as":"text","value":"hi","hex":"68 69"}""",
            reply("1-2"),
        )
        send("1-3", "ble-read", c3, box)
        connected().answer("read $box", Result.success("hi".toByteArray()))
        assertEquals("68 69", (parseJson(reply("1-3")!!) as Map<*, *>)["value"])
    }

    @Test
    fun `a failed read or connection is refused with why, and the link closed`() {
        send("1-1", "ble-read", c3, "2a19")
        val link = connected()
        link.answer("read $level", Result.failure(Exception("not permitted")))
        assertEquals(refusal("couldn't read battery-level: not permitted"), reply("1-1"))
        assertTrue(link.closed)
        send("1-2", "ble-read", c3, "2a19")
        host.connects.last().ready(Result.failure(Exception("timed out")))
        assertEquals(refusal("couldn't connect to $c3: timed out"), reply("1-2"))
    }

    @Test
    fun `a link that drops during a request refuses it with why`() {
        send("1-1", "ble-read", c3, "2a19")
        connected()
        host.connects.single().dropped("link lost")
        assertEquals(refusal("the link to $c3 dropped: link lost"), reply("1-1"))
    }

    @Test
    fun `a request cancelled while it connects closes the link it gets`() {
        send("1-1", "ble-services", c3)
        cancel("1-1")
        val link = connected()
        assertTrue(link.closed)
    }

    @Test
    fun `a device by name, paired, or seen by a scan, exact, else in any case`() {
        host.devices = listOf(BtDevice("Buds", "AA:BB:CC:DD:EE:01", BtType.DUAL), BtDevice("buds", "AA:BB:CC:DD:EE:02", BtType.LE))
        send("1-1", "ble-scan")
        host.scans.single().found(advert(random = true))
        runTimers()
        send("1-2", "ble-read", c3, "2a19")
        assertTrue(host.connects.last().randomAddress)
        send("1-3", "ble-services", "MYNX-BLE")
        assertEquals(c3, host.connects.last().address)
        assertTrue(host.connects.last().randomAddress)
        connected()
        assertEquals("mynx-ble", ((parseJson(reply("1-3")!!) as Map<*, *>)["device"] as Map<*, *>)["name"])
        send("1-4", "ble-services", "buds")
        assertEquals("AA:BB:CC:DD:EE:02", host.connects.last().address)
        assertFalse(host.connects.last().randomAddress)
        send("1-5", "ble-services", "BUDS")
        assertEquals(
            refusal("2 devices are called BUDS: give its address (AA:BB:CC:DD:EE:01 Buds, AA:BB:CC:DD:EE:02 buds)"),
            reply("1-5"),
        )
        send("1-6", "ble-services", "watch")
        assertEquals(refusal("no device called watch: scan for it (mynx ble scan), or give its address"), reply("1-6"))
    }

    @Test
    fun `unknown, missing and ambiguous characteristics are refused`() {
        send("1-1", "ble-read", c3, "ffe1")
        var link = connected()
        assertEquals(refusal("$c3 has no characteristic ffe1 (mynx ble services $c3 lists them)"), reply("1-1"))
        assertTrue(link.closed)
        val twice = FakeBleLink(
            c3,
            listOf(
                BleService(battery, listOf(BleChar(battery, level, 1, setOf(BleProp.READ), emptyList()))),
                BleService(custom, listOf(BleChar(custom, level, 2, setOf(BleProp.READ), emptyList()))),
            ),
        )
        send("1-2", "ble-read", c3, "2a19")
        connected(twice)
        assertEquals(
            refusal("2a19 is in 2 services (battery-service, $custom): name one as SERVICE/CHAR"),
            reply("1-2"),
        )
        send("1-3", "ble-read", c3, "$custom/battery-level")
        link = connected(twice)
        assertEquals(listOf("read $level"), link.pending.map { it.first })
        link.answer("read $level", Result.success(byteArrayOf(7)))
        assertEquals(7L, (parseJson(reply("1-3")!!) as Map<*, *>)["value"])
    }

    @Test
    fun `writes, with a response unless asked not to`() {
        send("1-1", "ble-write", c3, "nus-rx", "hi there", "as=text")
        var link = connected()
        link.answer("write $rx 68 69 20 74 68 65 72 65 true", Result.success(Unit))
        assertEquals(
            """{"ok":true,"device":{"address":"$c3","name":null},"char":"$rx","name":"nus-rx","bytes":8}""",
            reply("1-1"),
        )
        assertTrue(link.closed)
        send("1-2", "ble-write", c3, "nus-rx", "0a 1B", "no-response")
        link = connected()
        link.answer("write $rx 0a 1b false", Result.success(Unit))
        assertEquals(2L, (parseJson(reply("1-2")!!) as Map<*, *>)["bytes"])
        send("1-3", "ble-write", c3, box, "01")
        connected().answer("write $box 01 true", Result.failure(Exception("not permitted")))
        assertEquals(refusal("couldn't write $box: not permitted"), reply("1-3"))
    }

    @Test
    fun `a write longer than the MTU less 3 is refused with the limit`() {
        send("1-1", "ble-write", c3, "nus-rx", "x".repeat(21), "as=text")
        val link = connected()
        assertEquals(refusal("21 bytes is too long: a write takes at most 20 here (the MTU is 23, less 3)"), reply("1-1"))
        assertTrue(link.pending.isEmpty() && link.closed)
        send("1-2", "ble-write", c3, "nus-rx", "x".repeat(20), "as=text")
        assertEquals(1, connected().pending.size)
    }

    @Test
    fun `a property the characteristic hasn't got is refused`() {
        val refused = mapOf(
            listOf("ble-read", c3, "nus-rx") to "nus-rx can't be read (it has: write, write-no-response)",
            listOf("ble-write", c3, "battery-level", "01") to "battery-level can't be written (it has: read, notify)",
            listOf("ble-write", c3, box, "01", "no-response") to "$box doesn't take writes without a response: leave out no-response",
        )
        refused.entries.forEachIndexed { i, (lines, error) ->
            send("3-$i", *lines.toTypedArray())
            val link = connected()
            assertEquals(refusal(error), reply("3-$i"), lines.toString())
            assertTrue(link.pending.isEmpty() && link.closed)
        }
        val writeOnly = FakeBleLink(c3, listOf(BleService(nus, listOf(BleChar(nus, rx, 1, setOf(BleProp.WRITE_NO_RESPONSE), emptyList())))))
        send("3-9", "ble-write", c3, "nus-rx", "01")
        connected(writeOnly)
        assertEquals(refusal("nus-rx only takes writes without a response: add no-response"), reply("3-9"))
    }

    @Test
    fun `notify streams each value until cancelled, then unsubscribes and disconnects`() {
        send("1-1", "ble-notify", c3, "battery-level")
        assertEquals("stream", file("1-1.wait").readText())
        val link = connected()
        val (value, done) = link.subscription(level)
        done(Result.success(Unit))
        value(byteArrayOf(42), 1234)
        value(byteArrayOf(41), 2234)
        assertEquals(
            listOf(
                """{"char":"$level","name":"battery-level","as":"u8","value":42,"hex":"2a","time":1234}""",
                """{"char":"$level","name":"battery-level","as":"u8","value":41,"hex":"29","time":2234}""",
            ),
            lines("1-1"),
        )
        cancel("1-1")
        assertEquals(listOf("subscribe $level", "unsubscribe $level"), link.pending.map { it.first })
        assertTrue(link.closed)
        assertEquals("""{"ok":true}""", reply("1-1"))
    }

    @Test
    fun `notify takes indicate too, in the format asked, and refuses what has neither`() {
        send("1-1", "ble-notify", c3, counter, "as=u16")
        val link = connected()
        link.subscription(counter).first(byteArrayOf(1, 1), 5)
        assertEquals(listOf("""{"char":"$counter","name":null,"as":"u16","value":257,"hex":"01 01","time":5}"""), lines("1-1"))
        send("1-2", "ble-notify", c3, box)
        assertTrue(connected().closed)
        assertEquals(refusal("$box can't notify or indicate (it has: read, write)"), reply("1-2"))
    }

    @Test
    fun `notify ends as a refusal when subscribing fails or the link drops`() {
        send("1-1", "ble-notify", c3, "nus-tx")
        var link = connected()
        link.subscription(tx).second(Result.failure(Exception("not permitted")))
        assertEquals(refusal("couldn't subscribe to nus-tx: not permitted"), reply("1-1"))
        assertTrue(link.closed)
        send("1-2", "ble-notify", c3, "nus-tx")
        link = connected()
        link.subscription(tx).second(Result.success(Unit))
        host.connects.last().dropped("link lost")
        assertEquals(refusal("the link to $c3 dropped: link lost"), reply("1-2"))
    }

    @Test
    fun `connect holds a link that other requests on the device use until it's cancelled`() {
        send("1-1", "ble-connect", c3)
        assertEquals("stream", file("1-1.wait").readText())
        val link = connected(c3Link(mtu = 185))
        assertEquals(listOf("""{"state":"connected","device":{"address":"$c3","name":null},"mtu":185}"""), lines("1-1"))
        send("1-2", "ble-read", c3, "2a19")
        send("1-3", "ble-services", c3)
        assertEquals(1, host.connects.size)
        link.answer("read $level", Result.success(byteArrayOf(9)))
        assertEquals(9L, (parseJson(reply("1-2")!!) as Map<*, *>)["value"])
        assertEquals(185L, (parseJson(reply("1-3")!!) as Map<*, *>)["mtu"])
        send("1-4", "ble-notify", c3, "2a19")
        link.subscription(level).second(Result.success(Unit))
        cancel("1-4")
        assertEquals(listOf("subscribe $level", "unsubscribe $level"), link.pending.map { it.first })
        assertFalse(link.closed)
        cancel("1-1")
        assertTrue(link.closed)
        assertEquals("""{"ok":true}""", reply("1-1"))
        send("1-5", "ble-read", c3, "2a19")
        assertEquals(2, host.connects.size)
    }

    @Test
    fun `a held link that drops says so, ends what uses it, and isn't used again`() {
        send("1-1", "ble-connect", c3)
        val link = connected()
        send("1-2", "ble-notify", c3, "nus-tx")
        link.subscription(tx).second(Result.success(Unit))
        host.connects.single().dropped("link lost")
        assertEquals(
            """{"state":"disconnected","device":{"address":"$c3","name":null},"error":"link lost"}""",
            lines("1-1").last(),
        )
        assertEquals(refusal("the link to $c3 dropped: link lost"), reply("1-1"))
        assertEquals(refusal("the link to $c3 dropped: link lost"), reply("1-2"))
        send("1-3", "ble-connect", c3)
        assertEquals(2, host.connects.size)
    }

    @Test
    fun `one connect per device at a time, and a failed one frees it`() {
        send("1-1", "ble-connect", c3)
        send("1-2", "ble-connect", c3.lowercase())
        assertEquals(refusal("$c3 is already held by another mynx ble connect"), reply("1-2"))
        assertEquals(1, host.connects.size)
        // Until it's connected, other requests connect for themselves.
        send("1-3", "ble-read", c3, "2a19")
        assertEquals(2, host.connects.size)
        host.connects[0].ready(Result.failure(Exception("timed out")))
        assertEquals(refusal("couldn't connect to $c3: timed out"), reply("1-1"))
        send("1-4", "ble-connect", c3)
        assertEquals(3, host.connects.size)
    }
}
