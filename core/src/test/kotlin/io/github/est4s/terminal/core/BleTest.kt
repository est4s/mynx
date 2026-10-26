package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        return BleStop { pending += "unsubscribe ${char.uuid}" to Unit }
    }
    override fun close() { closed = true }
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
    fun `answers only the planned requests`() {
        assertTrue(BLE_REQUEST_NAMES.containsAll(bleRequests(home, host).keys))
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
}
