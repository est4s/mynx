package io.github.est4s.terminal.core

import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeSerialTest {
    private val base = createTempDirectory("bridge-serial").toFile()
    private val home = File(base, "root").apply { mkdirs() }
    private val dir = File(base, "requests").apply { mkdirs() }
    private val host = FakeBridgeHost()

    private var usbList = """{"ok":true,"devices":[
        {"id":"1","ids":"303a:1001","manufacturer":"Espressif","product":"USB JTAG/serial debug unit","serial":null,
         "driver":"cdc","allowed":true,"error":null,"ports":[
           {"name":"ttyACM0","path":"/dev/serial/ttyACM0","url":"rfc2217://localhost:2217","tcp_port":2217,"in_use":false,"waiting":false}]},
        {"id":"2","ids":"1a86:7523","manufacturer":null,"product":null,"serial":null,
         "driver":"ch34x","allowed":true,"error":null,"ports":[
           {"name":"ttyUSB0","path":"/dev/serial/ttyUSB0","url":"rfc2217://localhost:2218","tcp_port":2218,"in_use":true,"waiting":false}]}]}"""
    private var btList = """{"ok":true,"enabled":true,"devices":[],"ports":[
        {"name":"rfcomm0","address":"D4:E9:F4:A2:4A:02","device":"mynx-spp","path":"/dev/serial/rfcomm0",
         "url":"rfc2217://localhost:2317","tcp_port":2317,"linked":false,"in_use":false,"waiting":false,"error":null},
        {"name":"nus0","address":"D4:E9:F4:A2:4A:06","device":null,"path":"/dev/serial/nus0",
         "url":null,"tcp_port":null,"linked":false,"in_use":false,"waiting":false,"error":"Bluetooth is off"}]}"""
    private val btArgs = mutableListOf<List<String>>()

    private val requests = MynxRequests(
        dir, home,
        later = mapOf(
            "usb-list" to Later(10) { _, reply -> reply.answer(usbList) },
            "bt-list" to Later(120) { args, reply ->
                btArgs += args
                reply.answer(btList)
            },
        ),
        alive = { true },
    )
    private val app = WebApp(
        "weather", "Weather", "/root/apps/weather", "/root/apps/weather", WebAppKind.STATIC,
        "https://weather.mynx.invalid", "https://weather.mynx.invalid/", null, null, null, emptyList(),
    )
    private val serial = bridgeSerial(app, requests, host)
    private val calls = serial.calls()

    @AfterTest
    fun cleanUp() {
        deleteTree(base)
    }

    // Runs what callbacks posted to the main thread, until nothing more is posted.
    private fun pump() {
        repeat(20) { host.runScheduled(maxDelayMs = 0) }
    }

    private fun run(call: String, vararg args: String): Pair<FakeReplySink, Call> {
        val sink = FakeReplySink()
        val handle = requests.call(calls.getValue(call), args.toList(), sink)
        pump()
        return sink to handle
    }

    private fun answer(sink: FakeReplySink) = parseJson(sink.answers.single()) as Map<*, *>

    private fun refusal(sink: FakeReplySink) = answer(sink).let {
        assertEquals(false, it["ok"], sink.answers.toString())
        it["error"]
    }

    /** A connection to an in-memory server session over a [FakeLine]. */
    private inner class Served(val connect: FakeBridgeHost.SerialConnect) {
        val line = FakeLine()
        val socket = MemorySocket()

        // The server reads nothing of a client until its line is up.
        inner class MemorySocket : BridgeSocket {
            var closed = false
            var up = false
            val pending = mutableListOf<ByteArray>()
            override fun send(bytes: ByteArray) {
                if (closed) return
                if (up) session.fromNetwork(bytes) else pending += bytes
            }
            override fun close() {
                closed = true
            }
        }
        val session: Rfc2217Session = Rfc2217Session(line, LineSettings()) { connect.received(it) }

        /** The server takes it: the socket's ready, it's greeted; [linkUp]: the line opens, so it reads. */
        fun accept(linkUp: Boolean = true) {
            connect.ready(Result.success(socket))
            pump()
            if (linkUp) {
                session.greet()
                up()
            }
            pump()
        }

        /** The line is up: the server reads what the client sent. */
        fun up() {
            socket.up = true
            socket.pending.forEach(session::fromNetwork)
            socket.pending.clear()
        }
    }

    private fun open(vararg args: String): Triple<FakeReplySink, Call, Served> {
        val (sink, call) = run("serial-open", *args)
        val served = Served(host.serialConnects.last())
        served.accept()
        return Triple(sink, call, served)
    }

    private fun lines(sink: FakeReplySink) = sink.lines.map { parseJson(it) as Map<*, *> }

    private fun handleOf(sink: FakeReplySink) = ((lines(sink).first()["open"] as Map<*, *>)["handle"] as Long).toString()

    private fun dataOf(sink: FakeReplySink): ByteArray =
        lines(sink).mapNotNull { it["data"] as String? }.fold(ByteArray(0)) { acc, b -> acc + Base64.getDecoder().decode(b) }

    @Test
    fun `serial-list lists USB and Bluetooth ports, asking Bluetooth without a dialog`() {
        val (sink, _) = run("serial-list")
        val ports = answer(sink)["ports"] as List<*>
        assertEquals(
            listOf(
                mapOf("name" to "ttyACM0", "kind" to "usb", "path" to "/dev/serial/ttyACM0", "url" to "rfc2217://localhost:2217",
                    "in_use" to false, "device" to "USB JTAG/serial debug unit"),
                mapOf("name" to "ttyUSB0", "kind" to "usb", "path" to "/dev/serial/ttyUSB0", "url" to "rfc2217://localhost:2218",
                    "in_use" to true, "device" to "1a86:7523"),
                mapOf("name" to "rfcomm0", "kind" to "bluetooth", "path" to "/dev/serial/rfcomm0", "url" to "rfc2217://localhost:2317",
                    "in_use" to false, "device" to "mynx-spp"),
                mapOf("name" to "nus0", "kind" to "ble-uart", "path" to "/dev/serial/nus0", "url" to null,
                    "in_use" to false, "device" to "D4:E9:F4:A2:4A:06"),
            ),
            ports,
        )
        assertEquals(listOf(listOf("--no-ask")), btArgs)
    }

    @Test
    fun `Bluetooth that can't be asked is left out, not refused`() {
        btList = """{"ok":false,"error":"Bluetooth isn't allowed yet (mynx bt asks)"}"""
        val (sink, _) = run("serial-list")
        assertEquals(listOf("ttyACM0", "ttyUSB0"), (answer(sink)["ports"] as List<*>).map { (it as Map<*, *>)["name"] })
    }

    @Test
    fun `opening a port sets the line and the lines, then sends the open line with a handle`() {
        val (sink, call, served) = open("ttyACM0", "baud=9600", "data=7", "parity=even", "stop=2")
        assertEquals(2217, served.connect.tcpPort)
        assertEquals(LineSettings(9600, 7, Parity.EVEN, StopBits.TWO), served.session.settings)
        assertEquals(listOf("dtr true", "rts true"), served.line.callList().filter { !it.startsWith("configure") })
        assertEquals(
            mapOf("open" to mapOf("port" to "ttyACM0", "handle" to 1L, "baud" to 9600L)),
            lines(sink).single(),
        )
        assertEquals(null, call.seconds, "a stream")
        assertTrue(sink.answers.isEmpty())
    }

    @Test
    fun `115200 8N1 unless given, and lower opens with DTR first, both down`() {
        val (_, _, served) = open("ttyACM0", "lower")
        assertEquals(LineSettings(115200), served.session.settings)
        assertEquals(listOf("dtr false", "rts false"), served.line.callList().filter { !it.startsWith("configure") })
    }

    @Test
    fun `bad options are refused before connecting`() {
        for ((args, why) in listOf(
            listOf<String>() to "serial-open needs a port (mynx.serial.list() names them)",
            listOf("ttyACM0", "baud=fast") to "baud= takes a number from 1",
            listOf("ttyACM0", "data=9") to "data= takes 5, 6, 7 or 8",
            listOf("ttyACM0", "parity=maybe") to "parity= takes none, even, odd, mark or space",
            listOf("ttyACM0", "stop=3") to "stop= takes 1, 1.5 or 2",
            listOf("ttyACM0", "speed=1") to "unknown serial-open option 'speed=1'",
            listOf("ttyS9") to "no port ttyS9 (mynx.serial.list() names them)",
            listOf("nus0") to "nus0 isn't served now: Bluetooth is off",
            listOf("ttyUSB0") to "ttyUSB0 is busy: another program has it",
        )) {
            val (sink, _) = run("serial-open", *args.toTypedArray())
            assertEquals(why, refusal(sink), args.toString())
        }
        assertTrue(host.serialConnects.isEmpty())
    }

    @Test
    fun `every byte both ways, as base64`() {
        val (sink, _, served) = open("ttyACM0")
        val all = ByteArray(256) { it.toByte() }
        served.session.fromLine(all)
        pump()
        assertContentEquals(all, dataOf(sink))

        val (write, _) = run("serial-write", handleOf(sink), Base64.getEncoder().encodeToString(all + all))
        assertEquals(mapOf("ok" to true, "bytes" to 512L), answer(write))
        assertContentEquals(all + all, served.line.writtenBytes())
    }

    @Test
    fun `data that comes before the line is up follows the open line`() {
        val (sink, _) = run("serial-open", "ttyACM0")
        val served = Served(host.serialConnects.last())
        served.connect.ready(Result.success(served.socket))
        pump()
        // The greeting, then a byte the port sent, then the answers to our setup.
        served.connect.received(byteArrayOf(255.toByte(), 251.toByte(), 3, 42))
        pump()
        assertTrue(sink.lines.isEmpty())
        served.up()
        pump()
        assertTrue("open" in lines(sink).first())
        assertContentEquals(byteArrayOf(42), dataOf(sink))
    }

    @Test
    fun `serial-set changes the line, RTS before DTR down`() {
        val (sink, _, served) = open("ttyACM0")
        served.line.calls.clear()
        val (set, _) = run("serial-set", handleOf(sink), "baud=57600", "dtr=0", "rts=0")
        assertEquals(mapOf("ok" to true), answer(set))
        assertEquals(57600, served.session.settings.baud)
        assertEquals(listOf("rts false", "dtr false"), served.line.callList().filter { !it.startsWith("configure") })

        val (bad, _) = run("serial-set", handleOf(sink), "dtr=yes")
        assertEquals("dtr= takes 0 or 1", refusal(bad))
    }

    @Test
    fun `an unknown handle is refused`() {
        val (write, _) = run("serial-write", "7", "aGk=")
        assertEquals("no open port 7", refusal(write))
        val (set, _) = run("serial-set", "x", "dtr=1")
        assertEquals("no open port x", refusal(set))
    }

    @Test
    fun `data that isn't base64 is refused`() {
        val (sink, _, _) = open("ttyACM0")
        val (write, _) = run("serial-write", handleOf(sink), "%%%")
        assertEquals("serial-write's data isn't base64", refusal(write))
    }

    @Test
    fun `stopping closes the port with RTS then DTR down, and the socket`() {
        val (sink, call, served) = open("ttyACM0")
        served.line.calls.clear()
        call.stop()
        pump()
        assertEquals(listOf("rts false", "dtr false"), served.line.callList())
        assertTrue(served.socket.closed)
        assertEquals(mapOf("ok" to true), answer(sink))
        val (write, _) = run("serial-write", handleOf(sink), "aGk=")
        assertEquals("no open port 1", refusal(write))
    }

    @Test
    fun `closing the serial calls lets every port go`() {
        val (_, _, first) = open("ttyACM0")
        val (_, _, second) = open("rfcomm0")
        serial.close()
        assertTrue(first.socket.closed && second.socket.closed)
        assertEquals("rts false", first.line.callList().dropLast(1).last())
        assertEquals("dtr false", first.line.callList().last())
    }

    @Test
    fun `handles are new per port`() {
        val (a, _, _) = open("ttyACM0")
        val (b, _, _) = open("rfcomm0")
        assertEquals("1", handleOf(a))
        assertEquals("2", handleOf(b))
    }

    @Test
    fun `a port another program has is busy, the server closing without a greeting`() {
        val (sink, _) = run("serial-open", "ttyACM0")
        val connect = host.serialConnects.last()
        connect.ready(Result.success(FakeBridgeSocket()))
        connect.closed(null)
        pump()
        assertEquals("ttyACM0 is busy: another program has it", refusal(sink))
    }

    @Test
    fun `a Bluetooth link that won't open says why`() {
        val (sink, _) = run("serial-open", "rfcomm0")
        val served = Served(host.serialConnects.last())
        served.accept(linkUp = false)
        served.connect.received(byteArrayOf(255.toByte(), 251.toByte(), 3))
        btList = btList.replace(""""linked":false,"in_use":false,"waiting":false,"error":null""",
            """"linked":false,"in_use":false,"waiting":false,"error":"read failed, socket might closed or timeout"""")
        served.connect.closed(null)
        pump()
        assertEquals("rfcomm0 didn't open: read failed, socket might closed or timeout", refusal(sink))
    }

    @Test
    fun `a port that goes away is refused with why`() {
        val (sink, _, served) = open("ttyACM0")
        served.connect.closed("Connection reset")
        pump()
        assertEquals("ttyACM0 went away (Connection reset)", refusal(sink))
        val (gone, _, other) = open("ttyACM0")
        other.connect.closed(null)
        pump()
        assertEquals("ttyACM0 went away (unplugged, or its link dropped)", refusal(gone))
    }

    @Test
    fun `a server that isn't there`() {
        val (sink, _) = run("serial-open", "ttyACM0")
        host.serialConnects.last().ready(Result.failure(java.io.IOException("couldn't connect to the port's server (127.0.0.1:2217): refused")))
        pump()
        assertEquals("ttyACM0: couldn't connect to the port's server (127.0.0.1:2217): refused", refusal(sink))
    }

    @Test
    fun `a port that never opens times out`() {
        val (sink, _) = run("serial-open", "rfcomm0")
        val served = Served(host.serialConnects.last())
        served.accept(linkUp = false)
        host.runScheduled()
        pump()
        assertEquals("rfcomm0 didn't open within $SERIAL_OPEN_SECONDS s", refusal(sink))
        assertTrue(served.socket.closed)
    }

    @Test
    fun `stopped while it connects, the socket is closed when it comes`() {
        val (sink, call) = run("serial-open", "ttyACM0")
        call.stop()
        pump()
        val socket = FakeBridgeSocket()
        host.serialConnects.last().ready(Result.success(socket))
        pump()
        assertTrue(socket.closed)
        assertEquals(mapOf("ok" to true), answer(sink))
    }

    @Test
    fun `through a session, with the serial permission`() {
        val rootfs = File(base, "rootfs").apply { mkdirs() }
        val roots = File(rootfs, "root/apps/weather").apply { mkdirs() }
        File(roots, "index.html").writeText("x")
        val apps = WebApps(File(rootfs, "root"), rootfs.path, File(base, "tools").apply { mkdirs() })
        File(rootfs, "root/$CONFIG_DIR").mkdirs()
        File(rootfs, "root/$CONFIG_DIR/$APP_PERMISSIONS_CONF").writeText("[weather]\nserial = allow\n")
        val bridge = Bridge(requests, apps, AppPermissions(File(rootfs, "root"), apps), host, rootfs) { bridgeSerial(it, requests, host) }
        val sent = mutableListOf<String>()
        val session = bridge.session(apps.find("weather")!!) { sent += it }
        session.receive("""{"id":1,"call":"serial-open","args":["ttyACM0"]}""", "https://weather.mynx.invalid")
        pump()
        val served = Served(host.serialConnects.last())
        served.accept()
        assertEquals(
            """{"id":1,"line":{"open":{"port":"ttyACM0","handle":1,"baud":115200}}}""",
            sent.last(),
        )
        session.close()
        assertTrue(served.socket.closed, "the session's ports go with it")
        assertEquals("dtr false", served.line.callList().last())
    }
}
