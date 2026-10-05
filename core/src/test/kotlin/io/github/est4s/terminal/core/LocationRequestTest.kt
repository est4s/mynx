package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationRequestTest {
    private val base = createTempDirectory("location").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val asked = mutableListOf<Pair<LocationQuery, LocationReport>>()
    private val requests = PocketRequests(
        dir, home,
        later = locationRequests(home) { query, report -> asked += query to report },
        alive = { true },
    )
    private val fix = Fix(
        latitude = 60.1695213, longitude = 24.9354471, accuracy = 12.34f, altitude = 21.0, speed = null,
        bearing = null, provider = "network", time = 1_791_177_403_145,
    )
    private val fixJson = """{"latitude":60.1695213,"longitude":24.9354471,"accuracy":12.3,"altitude":21,""" +
        """"speed":null,"bearing":null,"provider":"network","time":1791177403145}"""

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `one fix, the first from GPS or the network, within 60 s`() {
        send("7-1", "location")
        assertEquals(LocationQuery(gpsOnly = false, stream = false, intervalSeconds = 5, timeoutSeconds = 60), asked.single().first)
        assertEquals("$MAX_LOCATION_TIMEOUT", file("7-1.wait").readText())

        asked.single().second.fix(fix)
        assertEquals("""{"ok":true,"location":$fixJson}""", file("7-1.reply").readText())
    }

    @Test
    fun `options are GPS only, how often, how long to wait`() {
        send("7-1", "location", "gps", "timeout=300")
        send("7-2", "location-stream", "interval=1")
        assertEquals(
            listOf(
                LocationQuery(gpsOnly = true, stream = false, intervalSeconds = 5, timeoutSeconds = 300),
                LocationQuery(gpsOnly = false, stream = true, intervalSeconds = 1, timeoutSeconds = 60),
            ),
            asked.map { it.first },
        )
    }

    @Test
    fun `refuses options it doesn't know or values out of range`() {
        send("7-1", "location", "fast")
        send("7-2", "location", "timeout=0")
        send("7-3", "location", "timeout=301")
        send("7-4", "location-stream", "interval=3601")
        send("7-5", "location-stream", "interval=x")
        assertTrue(asked.isEmpty())
        assertEquals("""{"ok":false,"error":"unknown location option 'fast'"}""", file("7-1.reply").readText())
        val timeout = """{"ok":false,"error":"the timeout is 1 to $MAX_LOCATION_TIMEOUT seconds"}"""
        assertEquals(timeout, file("7-2.reply").readText())
        assertEquals(timeout, file("7-3.reply").readText())
        val interval = """{"ok":false,"error":"the interval is 1 to 3600 seconds"}"""
        assertEquals(interval, file("7-4.reply").readText())
        assertEquals(interval, file("7-5.reply").readText())
    }

    @Test
    fun `a stream sends each fix as a line until it's cancelled`() {
        send("7-1", "location-stream")
        assertEquals("stream", file("7-1.wait").readText())
        var stopped = false
        val report = asked.single().second
        report.onCancel { stopped = true }
        report.fix(fix)
        report.fix(fix.copy(provider = "gps"))
        assertEquals(listOf(fixJson, fixJson.replace("network", "gps")), file("7-1.stream").readLines())
        assertFalse(file("7-1.reply").exists())

        file("7-1.cancel").writeText("")
        requests.sweep()
        assertTrue(stopped)
        assertEquals("""{"ok":true}""", file("7-1.reply").readText())
    }

    @Test
    fun `says why there's no fix`() {
        send("7-1", "location")
        asked.single().second.fail("no fix within 60 s")
        assertEquals("""{"ok":false,"error":"no fix within 60 s"}""", file("7-1.reply").readText())
    }

    @Test
    fun `nothing is located while android-location is off`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-location = off\n")
        send("7-1", "location")
        send("7-2", "location-stream")
        assertTrue(asked.isEmpty())
        val off = """{"ok":false,"error":"location is off (pocket set android-location on)"}"""
        assertEquals(off, file("7-1.reply").readText())
        assertEquals(off, file("7-2.reply").readText())
    }

    @Test
    fun `fields Android doesn't know are null, numbers are short`() {
        val bare = Fix(1.0, -2.5, null, null, 0.25f, 359.94f, "gps", 5)
        assertEquals(
            """{"latitude":1,"longitude":-2.5,"accuracy":null,"altitude":null,"speed":0.3,"bearing":359.9,"provider":"gps","time":5}""",
            fixJson(bare),
        )
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
}

class LocationProvidersTest {
    private val any = LocationQuery(gpsOnly = false, stream = false, intervalSeconds = 5, timeoutSeconds = 60)
    private val gps = any.copy(gpsOnly = true)
    private val all = LocationAccess(precise = true, approximate = true, locationOn = true, hasGps = true, hasNetwork = true)

    @Test
    fun `listens to GPS and the network, the first fix wins`() {
        assertEquals(listOf("gps", "network"), locationProviders(any, all).getOrThrow())
        assertEquals(listOf("gps"), locationProviders(gps, all).getOrThrow())
        assertEquals(listOf("network"), locationProviders(any, all.copy(hasGps = false)).getOrThrow())
    }

    @Test
    fun `approximate location only gets the network`() {
        val approximate = all.copy(precise = false)
        assertEquals(listOf("network"), locationProviders(any, approximate).getOrThrow())
        assertEquals(
            "GPS needs precise location, and the app only has approximate (allow precise location in the app's Android settings)",
            locationProviders(gps, approximate).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `says why there's nothing to listen to`() {
        fun why(query: LocationQuery, access: LocationAccess) = locationProviders(query, access).exceptionOrNull()?.message
        assertEquals("location is off on the phone (turn it on in quick settings)", why(any, all.copy(locationOn = false)))
        assertEquals(
            "the app isn't allowed to use location (allow it in the app's Android settings)",
            why(any, all.copy(precise = false, approximate = false)),
        )
        assertEquals("the phone has no GPS", why(gps, all.copy(hasGps = false)))
        assertEquals("no location provider on the phone", why(any, all.copy(hasGps = false, hasNetwork = false)))
    }
}
