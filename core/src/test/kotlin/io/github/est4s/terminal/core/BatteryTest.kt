package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryRequestTest {
    private val base = createTempDirectory("battery").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val asked = mutableListOf<Pair<BatteryQuery, BatteryReport>>()
    private val requests = MynxRequests(
        dir, home,
        later = batteryRequests { query, report -> asked += query to report },
        alive = { true },
    )

    // A Pixel charging over USB: what Android's broadcast, BatteryManager
    // and PowerManager say, as the app passes them on.
    private val charging = BatteryReading(
        level = 82, scale = 100, status = 2, plugged = 2, health = 2,
        temperature = 315, voltage = 4123, technology = "Li-ion",
        current = 1_204_000, currentAverage = 1_150_000, charge = 3_912_000,
        chargeTimeMs = 4_320_000, cycles = 142, powerSaver = false, thermal = 0,
        time = 1_791_177_403_145,
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `one reading of everything the phone says`() {
        send("7-1", "battery")
        assertEquals(BatteryQuery(stream = false, intervalSeconds = null), asked.single().first)
        asked.single().second.reading(charging)
        assertEquals(
            """{"ok":true,"battery":{"percent":82,"status":"charging","plugged":"usb","health":"good",""" +
                """"temperature":31.5,"voltage":4.123,"current":1.204,"current_average":1.15,"charge":3912,""" +
                """"time_to_full":4320,"cycles":142,"technology":"Li-ion","battery_saver":false,""" +
                """"thermal":"none","time":1791177403145}}""",
            file("7-1.reply").readText(),
        )
    }

    @Test
    fun `what the phone doesn't say is null`() {
        send("7-1", "battery")
        asked.single().second.reading(
            BatteryReading(
                level = 40, scale = 100, status = 3, plugged = 0, health = 1,
                temperature = null, voltage = null, technology = null,
                current = Int.MIN_VALUE, currentAverage = null, charge = 0,
                chargeTimeMs = -1, cycles = -1, powerSaver = true, thermal = null,
                time = 1_791_177_403_145,
            ),
        )
        assertEquals(
            """{"ok":true,"battery":{"percent":40,"status":"discharging","plugged":null,"health":"unknown",""" +
                """"temperature":null,"voltage":null,"current":null,"current_average":null,"charge":null,""" +
                """"time_to_full":null,"cycles":null,"technology":null,"battery_saver":true,""" +
                """"thermal":null,"time":1791177403145}}""",
            file("7-1.reply").readText(),
        )
    }

    @Test
    fun `names Android's numbers`() {
        val names = listOf(1 to "unknown", 2 to "charging", 3 to "discharging", 4 to "not-charging", 5 to "full")
        for ((status, name) in names) assertTrue(battery(charging.copy(status = status)).contains(""""status":"$name""""))
        val plugs = listOf(0 to "null", 1 to "\"ac\"", 2 to "\"usb\"", 4 to "\"wireless\"", 8 to "\"dock\"")
        for ((plugged, name) in plugs) assertTrue(battery(charging.copy(plugged = plugged)).contains(""""plugged":$name"""))
        val healths = listOf(
            1 to "unknown", 2 to "good", 3 to "overheat", 4 to "dead", 5 to "over-voltage", 6 to "failure", 7 to "cold",
        )
        for ((health, name) in healths) assertTrue(battery(charging.copy(health = health)).contains(""""health":"$name""""))
        val thermals = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
        thermals.forEachIndexed { level, name ->
            assertTrue(battery(charging.copy(thermal = level)).contains(""""thermal":"$name""""))
        }
    }

    @Test
    fun `the percent follows the scale`() {
        assertTrue(battery(charging.copy(level = 41, scale = 50)).contains(""""percent":82,"""))
        assertTrue(battery(charging.copy(level = -1, scale = 100)).contains(""""percent":null,"""))
        assertTrue(battery(charging.copy(level = 5, scale = 0)).contains(""""percent":null,"""))
    }

    @Test
    fun `current flows out while discharging, whichever sign the phone uses`() {
        val discharging = charging.copy(status = 3, plugged = 0, chargeTimeMs = -1)
        assertTrue(battery(discharging.copy(current = -450_000, currentAverage = 380_000)).contains(
            """"current":-0.45,"current_average":-0.38,""",
        ))
        // Charging under a heavy load can still drain the battery: kept as the phone says.
        assertTrue(battery(charging.copy(current = -120_000)).contains(""""current":-0.12,"""))
    }

    @Test
    fun `voltage in volts, also from phones that report volts`() {
        assertTrue(battery(charging.copy(voltage = 4)).contains(""""voltage":4,"""))
        assertTrue(battery(charging.copy(voltage = 0)).contains(""""voltage":null,"""))
    }

    @Test
    fun `a stream sends a line when something worth knowing changes`() {
        send("7-1", "battery-stream")
        assertEquals("stream", file("7-1.wait").readText())
        val (query, report) = asked.single()
        assertEquals(BatteryQuery(stream = true, intervalSeconds = null), query)
        report.reading(charging)
        // Voltage and current alone change all the time.
        report.reading(charging.copy(voltage = 4130, current = 1_100_000, time = charging.time + 10_000))
        report.reading(charging.copy(level = 83, time = charging.time + 60_000))
        report.reading(charging.copy(level = 83, temperature = 318, time = charging.time + 70_000))
        report.reading(charging.copy(level = 83, temperature = 318, powerSaver = true, time = charging.time + 80_000))
        report.reading(charging.copy(level = 83, temperature = 318, powerSaver = true, thermal = 1, time = charging.time + 90_000))
        assertEquals(
            listOf(0L, 60_000L, 70_000L, 80_000L, 90_000L).map { charging.time + it },
            lines("7-1").map { (parseJson(it) as Map<*, *>)["time"] as Long },
        )
        assertFalse(file("7-1.reply").exists())
    }

    @Test
    fun `with an interval, a line at least that often`() {
        send("7-1", "battery-stream", "interval=30")
        val (query, report) = asked.single()
        assertEquals(BatteryQuery(stream = true, intervalSeconds = 30), query)
        for (seconds in listOf(0, 10, 29, 31, 40, 61)) {
            report.reading(charging.copy(current = 1_000_000 + seconds, time = charging.time + seconds * 1000L))
        }
        // The app reads at the interval, so a reading may come a little early.
        assertEquals(3, lines("7-1").size)
    }

    @Test
    fun `refuses options it doesn't know and intervals out of range`() {
        send("7-1", "battery", "interval=30")
        send("7-2", "battery-stream", "interval=0")
        send("7-3", "battery-stream", "interval=3601")
        send("7-4", "battery-stream", "fast")
        assertEquals(
            listOf(
                """{"ok":false,"error":"unknown battery option 'interval=30'"}""",
                """{"ok":false,"error":"the interval is 1 to 3600 seconds"}""",
                """{"ok":false,"error":"the interval is 1 to 3600 seconds"}""",
                """{"ok":false,"error":"unknown battery option 'fast'"}""",
            ),
            (1..4).map { file("7-$it.reply").readText() },
        )
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `says why it couldn't read`() {
        send("7-1", "battery")
        asked.single().second.fail("the phone didn't say")
        assertEquals("""{"ok":false,"error":"the phone didn't say"}""", file("7-1.reply").readText())
    }

    private fun battery(reading: BatteryReading): String = batteryJson(reading)

    private fun send(id: String, vararg lines: String) {
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(lines.joinToString("\n", postfix = "\n"))
        tmp.renameTo(File(dir, "$id.req"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)

    private fun lines(id: String) = file("$id.stream").readLines()
}
