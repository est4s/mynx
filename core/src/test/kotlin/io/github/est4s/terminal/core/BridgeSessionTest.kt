package io.github.est4s.terminal.core

import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeSessionTest {
    private val base = createTempDirectory("bridge").toFile()
    private val rootfs = File(base, "rootfs")
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val tools = File(base, "tools").apply { mkdirs() }
    private val dir = File(base, "requests").apply { mkdirs() }
    private val apps = WebApps(home, rootfs.path, tools)
    private val host = FakeBridgeHost()
    private val permissions = AppPermissions(home, apps)

    /** What the fake handlers were asked: the request and its lines. */
    private val started = mutableListOf<Pair<String, List<String>>>()
    private val replies = mutableMapOf<String, PendingReply>()
    private val stopped = mutableListOf<String>()
    private val vibrations = mutableListOf<Long>()
    private val shares = mutableListOf<Share>()

    private fun waiting(name: String, seconds: Int?) = Later(seconds) { args, reply ->
        started += name to args
        replies[name] = reply
        reply.onCancel { stopped += name }
    }

    private val requests = MynxRequests(
        dir, home,
        vibrate = { ms, _ -> vibrations += ms; null },
        share = { shares += it; null },
        later = mapOf(
            "location" to waiting("location", 60),
            "sensor-stream" to waiting("sensor-stream", null),
            "ble-connect" to waiting("ble-connect", null),
            "picky" to Later(5) { _, reply -> reply.refuse("no") },
            "camera-quick" to Later(QUICK_SHOT_SECONDS) { args, reply ->
                started += "camera-quick" to args
                val target = File(hostPath(args[0], rootfs.path)!!)
                target.writeBytes(photo)
                reply.ok("file" to json(args[0]), "bytes" to photo.size.toString())
            },
            "audio-record" to Later(null) { args, reply ->
                started += "audio-record" to args
                replies["audio-record"] = reply
                reply.line("""{"recording":true}""")
                reply.onCancel {
                    File(hostPath(args[0], rootfs.path)!!).writeBytes(byteArrayOf(1, 2, 3))
                    reply.ok("file" to json(args[0]), "bytes" to "3", "seconds" to "1.5")
                }
            },
            "audio-play" to Later(null) { args, reply ->
                started += "audio-play" to args
                replies["audio-play"] = reply
                played += File(hostPath(args[0], rootfs.path)!!).readBytes()
            },
            "service-list" to Later(10) { _, reply ->
                reply.answer("""{"ok":true,"services":[{"name":"other","state":"running"},{"name":"api-backend","state":"stopped","n":[1,2.5,null,false]}]}""")
            },
            "service-start" to Later(10) { args, reply -> started += "service-start" to args; reply.ok() },
            "service-log" to Later(10) { args, reply -> started += "service-log" to args; reply.ok("log" to json("x")) },
        ),
        alive = { true },
    )
    private val played = mutableListOf<ByteArray>()
    private val photo = ByteArray(1000) { it.toByte() }
    private val bridge = Bridge(requests, apps, permissions, host, rootfs)
    private val sent = mutableListOf<String>()

    init {
        File(home, "apps/weather/sounds").mkdirs()
        File(home, "apps/weather/index.html").writeText("<title>Weather</title>")
        File(home, "apps/weather/sounds/ding.mp3").writeBytes(byteArrayOf(9, 9))
        File(home, "apps/api").mkdirs()
        File(home, "apps/api/app.conf").writeText("url = http://127.0.0.1:8080/\nservice = api-backend\n")
        File(home, "apps/bare").mkdirs()
        File(home, "apps/bare/app.conf").writeText("url = http://localhost:9000/\n")
    }

    private val weather = apps.find("weather")!!
    private val origin = "https://weather.mynx.invalid"
    private var session = bridge.session(weather) { sent += it }

    @AfterTest
    fun cleanUp() {
        deleteTree(base)
    }

    private fun allow(vararg permissions: AppPermission) {
        val file = File(home, "$CONFIG_DIR/$APP_PERMISSIONS_CONF").apply { parentFile.mkdirs() }
        file.writeText("[weather]\n" + permissions.joinToString("") { "${it.word} = allow\n" })
    }

    private fun receive(message: String, from: String = origin) {
        session.receive(message, from)
        host.runScheduled(maxDelayMs = 0)
    }

    private fun call(id: Int, call: String, vararg args: String) =
        receive(obj("id" to id.toString(), "call" to json(call), "args" to array(args.map(::json))))

    private fun messages(): List<Map<*, *>> = sent.map { parseJson(it) as Map<*, *> }

    private fun reply(id: Int): Map<*, *>? =
        messages().lastOrNull { it["id"] == id.toLong() && "reply" in it }?.get("reply") as Map<*, *>?

    private fun lines(id: Int) = messages().filter { it["id"] == id.toLong() && "line" in it }.map { it["line"] }

    private fun refusal(message: String, code: String = "refused") = mapOf("ok" to false, "error" to message, "code" to code)

    @Test
    fun `the calls offered`() {
        assertEquals(BRIDGE_CALLS.map { it.call } - "camera", bridge.offered())
        host.cameraApp = true
        assertEquals(BRIDGE_CALLS.map { it.call }, bridge.offered())
    }

    @Test
    fun `the session says hello first`() {
        assertEquals(
            mapOf("hello" to mapOf("app" to "weather", "label" to "Weather", "calls" to bridge.offered())),
            messages().single(),
        )
    }

    @Test
    fun `only the app's own origin gets a bridge`() {
        assertNull(bridge.refusal(weather, origin))
        assertNull(bridge.refusal(weather, "HTTPS://Weather.MYNX.invalid"))
        val why = bridge.refusal(weather, "https://example.com")
        assertEquals("not weather's origin (https://weather.mynx.invalid)", why)
        assertEquals("weather warn no bridge for https://example.com: $why", host.logged.last())
        assertTrue(bridge.refusal(weather, "https://weather.mynx.invalid:444") != null)
        assertTrue(bridge.refusal(weather, "null") != null)
    }

    @Test
    fun `a served app gets one only while its service runs`() {
        val api = apps.find("api")!!
        assertEquals("api-backend isn't running", bridge.refusal(api, "http://127.0.0.1:8080"))
        host.running += "api-backend"
        assertNull(bridge.refusal(api, "http://127.0.0.1:8080"))
        assertTrue(bridge.refusal(api, "http://127.0.0.1:8081") != null)
        assertEquals("bare has no service (app.conf's service)", bridge.refusal(apps.find("bare")!!, "http://localhost:9000"))
    }

    @Test
    fun `a request runs as from mynx`() {
        call(1, "vibrate", "200")
        assertEquals(mapOf("ok" to true), reply(1))
        assertEquals(listOf(200L), vibrations)
    }

    @Test
    fun `a call not offered is refused and logged`() {
        call(1, "set", "font-size", "40")
        assertEquals(refusal("set isn't offered to apps"), reply(1))
        assertEquals("weather warn set: set isn't offered to apps", host.logged.last())
        call(2, "camera")
        assertEquals(refusal("camera isn't offered to apps"), reply(2))
    }

    @Test
    fun `a handler's refusal is refused, and logged`() {
        call(1, "vibrate", "nope")
        assertEquals(refusal("vibrate takes milliseconds from 1 to 5000"), reply(1))
        assertEquals("weather warn vibrate: vibrate takes milliseconds from 1 to 5000", host.logged.last())
    }

    @Test
    fun `bad messages`() {
        val before = sent.size
        receive("not json")
        receive("[1]")
        receive("""{"call":"vibrate"}""")
        receive("""{"id":0,"call":"vibrate"}""")
        receive("""{"id":1.5,"call":"vibrate"}""")
        receive("x".repeat(MAX_BRIDGE_MESSAGE_CHARS + 1))
        receive("""{"id":9,"cancel":true}""")
        assertEquals(before, sent.size)
        assertEquals(6, host.logged.count { it.startsWith("weather warn bad message: ") })

        receive("""{"id":1}""")
        assertEquals(refusal("a call needs a name"), reply(1))
        receive("""{"id":2,"call":"vibrate","args":[1]}""")
        assertEquals(refusal("args must be a list of strings"), reply(2))
        receive("""{"id":3,"call":"vibrate","args":"200"}""")
        assertEquals(refusal("args must be a list of strings"), reply(3))
        receive("""{"id":4,"call":"vibrate"}""")
        assertEquals(mapOf("ok" to true), reply(4))
    }

    @Test
    fun `an id still open can't be used again`() {
        call(1, "sensor-stream", "light")
        call(1, "vibrate")
        assertEquals(refusal("call 1 is already open"), reply(1))
        assertEquals(listOf("sensor-stream" to listOf("light")), started)
    }

    @Test
    fun `a refusal with no call name is logged without a doubled prefix`() {
        // The host's line is `warn bridge MESSAGE`: the message names no "bridge" itself.
        call(1, "sensor-stream", "light")
        call(1, "vibrate")
        assertEquals("weather warn call 1 is already open", host.logged.last())
        receive("""{"id":2}""")
        assertEquals("weather warn a call needs a name", host.logged.last())
    }

    @Test
    fun `messages from another origin are ignored, logged once`() {
        val before = sent.size
        receive("""{"id":1,"call":"vibrate"}""", "https://example.com")
        receive("""{"id":2,"call":"vibrate"}""", "https://example.com")
        assertEquals(before, sent.size)
        assertEquals(emptyList(), vibrations)
        assertEquals(1, host.logged.count { it == "weather warn message from https://example.com ignored" })
    }

    @Test
    fun `streams send lines, and a cancel stops them`() {
        call(1, "sensor-stream", "light")
        replies.getValue("sensor-stream").line("""{"lux":3}""")
        host.runScheduled(maxDelayMs = 0)
        assertEquals(listOf(mapOf("lux" to 3L)), lines(1))
        assertNull(reply(1))
        receive("""{"id":1,"cancel":true}""")
        assertEquals(listOf("sensor-stream"), stopped)
        assertEquals(mapOf("ok" to true), reply(1))
        assertTrue(host.scheduled.none { !it.cancelled && it.delayMs > 0 })
    }

    @Test
    fun `a call that takes too long is refused and cancelled`() {
        allow(AppPermission.LOCATION)
        call(1, "location")
        assertEquals(listOf(60_000L), host.scheduled.filter { !it.cancelled }.map { it.delayMs })
        host.runScheduled()
        host.runScheduled(maxDelayMs = 0)
        assertEquals(refusal("location timed out after 60 s"), reply(1))
        assertEquals(listOf("location"), stopped)
        replies.getValue("location").ok()
        host.runScheduled()
        assertEquals(1, messages().count { "reply" in it })
    }

    @Test
    fun `an answer in time cancels the timeout`() {
        allow(AppPermission.LOCATION)
        call(1, "location")
        replies.getValue("location").ok("latitude" to "1")
        host.runScheduled(maxDelayMs = 0)
        assertEquals(mapOf("ok" to true, "latitude" to 1L), reply(1))
        assertTrue(host.scheduled.all { it.cancelled })
    }

    @Test
    fun `permissions are checked first`() {
        call(1, "location")
        assertEquals(refusal("Weather must be on screen to ask for your location"), reply(1))
        File(home, "$CONFIG_DIR/$APP_PERMISSIONS_CONF").apply { parentFile.mkdirs() }.writeText("[weather]\nlocation = deny\n")
        call(2, "location")
        assertEquals(
            refusal("Weather isn't allowed to use your location (mynx app permissions weather location allow)", "denied"),
            reply(2),
        )
        assertEquals(emptyList(), started)
    }

    @Test
    fun `a call waits for the dialog, which doesn't count against its time`() {
        host.front += "weather"
        call(1, "location")
        assertEquals(emptyList(), started)
        assertTrue(host.scheduled.none { it.delayMs > 0 })
        host.answer(true)
        host.runScheduled(maxDelayMs = 0)
        assertEquals(listOf("location" to emptyList<String>()), started)
        assertEquals(listOf(60_000L), host.scheduled.filter { !it.cancelled }.map { it.delayMs })
    }

    @Test
    fun `a cancel while it asks refuses it closed`() {
        host.front += "weather"
        call(1, "location")
        receive("""{"id":1,"cancel":true}""")
        assertEquals("closed", reply(1)!!["code"])
        assertEquals(1, host.dismissed)
    }

    @Test
    fun `too many calls at once`() {
        for (id in 1..MAX_BRIDGE_OPEN_CALLS) call(id, "sensor-stream", "light")
        call(MAX_BRIDGE_OPEN_CALLS + 1, "vibrate")
        assertEquals(refusal("too many calls at once"), reply(MAX_BRIDGE_OPEN_CALLS + 1))
    }

    @Test
    fun `closing cancels everything and sends nothing more`() {
        allow(AppPermission.BLUETOOTH)
        call(1, "sensor-stream", "light")
        call(2, "ble-connect", "mynx-ble")
        host.front += "weather"
        call(3, "location")
        call(4, "rotation-lock", "portrait")
        val count = sent.size
        session.close()
        session.close()
        assertTrue(session.closed)
        host.runScheduled()
        assertEquals(setOf("sensor-stream", "ble-connect"), stopped.toSet())
        assertEquals(1, host.dismissed)
        assertEquals("weather" to null, host.rotations.last())
        replies.getValue("sensor-stream").line("{}")
        host.runScheduled()
        receive("""{"id":5,"call":"vibrate"}""")
        assertEquals(count, sent.size)
        assertEquals(emptyList(), vibrations)
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `a session that never locked doesn't unlock`() {
        session.close()
        assertEquals(emptyList(), host.rotations)
    }

    @Test
    fun `notify goes through the host, with the permission`() {
        allow(AppPermission.NOTIFICATIONS)
        call(1, "notify", "Rain", "at five", "if-away")
        assertEquals(mapOf("ok" to true, "shown" to true), reply(1))
        assertEquals(listOf("weather: Rain: at five (if away)"), host.notices)
        call(2, "notify", "T".repeat(150))
        assertEquals("weather: ${"T".repeat(99)}…: ", host.notices.last())
        host.notifyFails = "notifications are off"
        call(3, "notify", "x")
        assertEquals(mapOf("ok" to true, "shown" to false, "reason" to "notifications are off"), reply(3))
        call(4, "notify", "")
        assertEquals(refusal("notify needs a title"), reply(4))
        call(5, "notify", "x", "", "shell=3")
        assertEquals(refusal("unknown notify option 'shell=3'"), reply(5))
    }

    @Test
    fun `rotation holds the app's own screen`() {
        call(1, "rotation-lock")
        assertEquals(mapOf("ok" to true, "locked" to "current"), reply(1))
        call(2, "rotation-lock", "landscape")
        assertEquals(mapOf("ok" to true, "locked" to "landscape"), reply(2))
        call(3, "rotation-lock", "sideways")
        assertEquals(refusal("rotation-lock is portrait, landscape or current"), reply(3))
        call(4, "rotation-unlock")
        assertEquals(mapOf("ok" to true, "locked" to "no"), reply(4))
        assertEquals(listOf("weather" to Orientation.CURRENT, "weather" to Orientation.LANDSCAPE, "weather" to null), host.rotations)
        session.close()
        assertEquals(3, host.rotations.size)
    }

    private fun tempDir() = File(rootfs, "tmp/.mynx/apps/weather")

    @Test
    fun `a quick photo comes back as data, and its file goes`() {
        allow(AppPermission.CAMERA)
        call(1, "camera-quick", "front")
        val (name, args) = started.single()
        assertEquals("camera-quick", name)
        assertTrue(args[0].matches(Regex("/tmp/\\.mynx/apps/weather/bridge-\\d+\\.jpg")), args[0])
        assertEquals("front", args[1])
        assertEquals(
            mapOf("ok" to true, "type" to "image/jpeg", "bytes" to 1000L, "data" to Base64.getEncoder().encodeToString(photo)),
            reply(1),
        )
        assertEquals(emptyList(), tempDir().list()!!.toList())
        call(2, "camera-quick")
        assertEquals("back", started.last().second[1])
        call(3, "camera-quick", "up")
        assertEquals(refusal("the camera is front or back, not 'up'"), reply(3))
    }

    @Test
    fun `a recording comes back as data when it's stopped`() {
        allow(AppPermission.MICROPHONE)
        call(1, "audio-record", "seconds=5", "format=wav")
        val args = started.single().second
        assertTrue(args[0].endsWith(".wav"), args[0])
        assertEquals(listOf("seconds=5"), args.drop(1))
        assertEquals(listOf(mapOf("recording" to true)), lines(1))
        receive("""{"id":1,"cancel":true}""")
        assertEquals(
            mapOf("ok" to true, "type" to "audio/wav", "bytes" to 3L, "seconds" to 1.5, "data" to "AQID"),
            reply(1),
        )
        assertEquals(emptyList(), tempDir().list()!!.toList())
        call(2, "audio-record")
        assertTrue(started.last().second[0].endsWith(".m4a"))
        call(3, "audio-record", "format=mp3")
        assertEquals(refusal("the format is m4a, aac, ogg, opus or wav"), reply(3))
    }

    @Test
    fun `closing while it records deletes the file`() {
        allow(AppPermission.MICROPHONE)
        call(1, "audio-record")
        session.close()
        host.runScheduled()
        assertEquals(emptyList(), tempDir().list()!!.toList())
    }

    @Test
    fun `playing bytes, from a temp file deleted when it ends`() {
        call(1, "audio-play", "data=" + Base64.getEncoder().encodeToString(byteArrayOf(5, 6)), "type=audio/mpeg")
        assertTrue(started.single().second[0].endsWith(".mp3"))
        assertEquals(listOf(5, 6), played.single().toList().map { it.toInt() })
        assertEquals(1, tempDir().list()!!.size)
        replies.getValue("audio-play").ok("file" to json("x"), "seconds" to "0.5")
        host.runScheduled(maxDelayMs = 0)
        assertEquals(mapOf("ok" to true, "seconds" to 0.5), reply(1))
        assertEquals(emptyList(), tempDir().list()!!.toList())
        call(2, "audio-play", "data=***", "type=audio/mpeg")
        assertEquals(refusal("data= isn't base64"), reply(2))
    }

    @Test
    fun `playing a static app's own file`() {
        call(1, "audio-play", "file=/sounds/ding.mp3")
        assertEquals("/root/apps/weather/sounds/ding.mp3", started.single().second[0])
        call(2, "audio-play", "file=/../secret.mp3")
        assertEquals(refusal("no such file in weather: /../secret.mp3"), reply(2))
        call(3, "audio-play")
        assertEquals(refusal("audio-play needs file=PATH or data=BASE64 type=MIME"), reply(3))
    }

    @Test
    fun `a served app plays data, not files`() {
        host.running += "api-backend"
        val api = apps.find("api")!!
        session = bridge.session(api) { sent += it }
        receive("""{"id":1,"call":"audio-play","args":["file=/x.mp3"]}""", "http://127.0.0.1:8080")
        assertEquals(refusal("file= is for static apps: send data="), reply(1))
    }

    @Test
    fun `sharing files writes them for the share request and deletes them later`() {
        call(1, "share-files", "name=a/b.txt", "type=text/plain", "data=aGk=", "name=c.png", "type=image/png", "data=AA==")
        assertEquals(mapOf("ok" to true, "count" to 2L), reply(1))
        val files = shares.single().files
        assertEquals(listOf("b.txt", "c.png"), files.map { it.name })
        assertEquals("hi", files[0].readText())
        assertTrue(files.all { it.path.startsWith(tempDir().path) })
        session.close()
        assertTrue(files.all { it.exists() })
        host.scheduled.filter { it.delayMs == BRIDGE_SHARE_KEEP_MS }.forEach { it.block() }
        assertTrue(files.none { it.exists() })
        assertEquals(emptyList(), tempDir().list()!!.toList())

        session = bridge.session(weather) { sent += it }
        call(2, "share-files", "name=a", "type=text/plain")
        assertEquals(refusal("share-files takes name=, type= and data= for each file"), reply(2))
    }

    @Test
    fun `a static app has no service`() {
        call(1, "service-list")
        assertEquals(refusal("weather has no service (app.conf's service)"), reply(1))
    }

    @Test
    fun `a served app reaches only its own service`() {
        host.running += "api-backend"
        session = bridge.session(apps.find("api")!!) { sent += it }
        val from = "http://127.0.0.1:8080"
        receive("""{"id":1,"call":"service-list","args":[]}""", from)
        assertEquals(
            mapOf("ok" to true, "services" to listOf(mapOf("name" to "api-backend", "state" to "stopped", "n" to listOf(1L, 2.5, null, false)))),
            reply(1),
        )
        receive("""{"id":2,"call":"service-start","args":[]}""", from)
        receive("""{"id":3,"call":"service-start","args":["api-backend"]}""", from)
        receive("""{"id":4,"call":"service-start","args":["other"]}""", from)
        receive("""{"id":5,"call":"service-log","args":["","20"]}""", from)
        assertEquals(mapOf("ok" to true), reply(2))
        assertEquals(refusal("other isn't api's service (app.conf's service)"), reply(4))
        assertEquals(
            listOf("service-start" to listOf("api-backend"), "service-start" to listOf("api-backend"), "service-log" to listOf("api-backend", "20")),
            started,
        )
    }

    @Test
    fun `with no serial calls, serial ports aren't available`() {
        allow(AppPermission.SERIAL)
        call(1, "serial-list")
        assertEquals(refusal("serial ports aren't available here"), reply(1))
    }

    @Test
    fun `start empties the apps' bridge files left from before`() {
        val left = File(tempDir(), "bridge-3.jpg").apply { parentFile.mkdirs(); writeText("x") }
        val folder = File(tempDir(), "bridge-4").apply { mkdirs() }
        File(folder, "a.txt").writeText("x")
        val outside = File(base, "outside").apply { mkdirs() }
        val keep = File(outside, "keep").apply { writeText("x") }
        java.nio.file.Files.createSymbolicLink(File(tempDir(), "bridge-5").toPath(), outside.toPath())
        val other = File(tempDir(), "mine.txt").apply { writeText("x") }
        val log = File(rootfs, "tmp/.mynx/apps/weather.log").apply { writeText("x") }
        bridge.start()
        assertFalse(left.exists())
        assertFalse(folder.exists())
        assertTrue(keep.exists())
        assertTrue(other.exists())
        assertTrue(log.exists())
    }
}
