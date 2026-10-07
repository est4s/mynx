package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensorRequestTest {
    private val base = createTempDirectory("sensors").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val asked = mutableListOf<Pair<SensorQuery, SensorReport>>()
    private var types = setOf(1, 4, 5, 11, 19)
    private val requests = Pc26Requests(
        dir, home,
        later = sensorRequests(home, { it in types }) { query, report -> asked += query to report },
        alive = { true },
    )
    private val accelerometer = SENSOR_KINDS.first { it.name == "accelerometer" }

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `lists the sensors the phone has, compass when it has a rotation vector`() {
        send("7-1", "sensor-list")
        assertEquals(
            """{"ok":true,"sensors":[""" +
                """{"name":"accelerometer","values":["x","y","z"],"unit":"m/s²"},""" +
                """{"name":"gyroscope","values":["x","y","z"],"unit":"rad/s"},""" +
                """{"name":"light","values":["illuminance"],"unit":"lx"},""" +
                """{"name":"rotation-vector","values":["x","y","z","w"],"unit":""},""" +
                """{"name":"step-counter","values":["steps"],"unit":"steps"},""" +
                """{"name":"compass","values":["azimuth","pitch","roll"],"unit":"°"}]}""",
            file("7-1.reply").readText(),
        )
        types = setOf(5)
        send("7-2", "sensor-list")
        assertEquals(
            """{"ok":true,"sensors":[{"name":"light","values":["illuminance"],"unit":"lx"}]}""",
            file("7-2.reply").readText(),
        )
    }

    @Test
    fun `one reading, within 10 s`() {
        send("7-1", "sensor", "accelerometer")
        assertEquals(SensorQuery(accelerometer, stream = false, rateHz = 10, timeoutSeconds = 10), asked.single().first)
        assertEquals("$MAX_SENSOR_TIMEOUT", file("7-1.wait").readText())

        asked.single().second.reading(SensorReading(listOf(0.1234567f, 9.80665f, -0.5f), accuracy = 3, time = 1_791_177_403_145))
        assertEquals(
            """{"ok":true,"reading":{"sensor":"accelerometer","values":{"x":0.1235,"y":9.8067,"z":-0.5},""" +
                """"unit":"m/s²","accuracy":"high","time":1791177403145}}""",
            file("7-1.reply").readText(),
        )
    }

    @Test
    fun `options are how long to wait, how often`() {
        send("7-1", "sensor", "light", "timeout=60")
        send("7-2", "sensor-stream", "gyroscope", "rate=200")
        assertEquals(
            listOf(
                SensorQuery(SENSOR_KINDS.first { it.name == "light" }, stream = false, rateHz = 10, timeoutSeconds = 60),
                SensorQuery(SENSOR_KINDS.first { it.name == "gyroscope" }, stream = true, rateHz = 200, timeoutSeconds = 10),
            ),
            asked.map { it.first },
        )
    }

    @Test
    fun `refuses sensors and options it doesn't know, values out of range`() {
        send("7-1", "sensor", "smell")
        send("7-2", "sensor", "pressure")
        send("7-3", "sensor", "light", "fast")
        send("7-4", "sensor", "light", "timeout=61")
        send("7-5", "sensor-stream", "light", "rate=201")
        send("7-6", "sensor-stream", "light", "rate=0")
        send("7-7", "sensor")
        assertTrue(asked.isEmpty())
        assertEquals("""{"ok":false,"error":"no sensor called 'smell' (pocket sensor list)"}""", file("7-1.reply").readText())
        assertEquals("""{"ok":false,"error":"the phone has no pressure sensor"}""", file("7-2.reply").readText())
        assertEquals("""{"ok":false,"error":"unknown sensor option 'fast'"}""", file("7-3.reply").readText())
        assertEquals("""{"ok":false,"error":"the timeout is 1 to 60 seconds"}""", file("7-4.reply").readText())
        val rate = """{"ok":false,"error":"the rate is 1 to 200 per second"}"""
        assertEquals(rate, file("7-5.reply").readText())
        assertEquals(rate, file("7-6.reply").readText())
        assertEquals("""{"ok":false,"error":"which sensor? (pocket sensor list)"}""", file("7-7.reply").readText())
    }

    @Test
    fun `a stream sends readings no faster than the rate until it's cancelled`() {
        send("7-1", "sensor-stream", "light", "rate=10")
        assertEquals("stream", file("7-1.wait").readText())
        var stopped = false
        val report = asked.single().second
        report.onCancel { stopped = true }
        // 100 ms apart at 10 per second; Android often sends more than asked.
        listOf(1000L, 1030L, 1080L, 1100L, 1210L).forEach { report.reading(SensorReading(listOf(it / 10f), 2, it)) }
        assertEquals(
            listOf(
                """{"sensor":"light","values":{"illuminance":100},"unit":"lx","accuracy":"medium","time":1000}""",
                """{"sensor":"light","values":{"illuminance":108},"unit":"lx","accuracy":"medium","time":1080}""",
                """{"sensor":"light","values":{"illuminance":121},"unit":"lx","accuracy":"medium","time":1210}""",
            ),
            file("7-1.stream").readLines(),
        )
        assertFalse(file("7-1.reply").exists())

        file("7-1.cancel").writeText("")
        requests.sweep()
        assertTrue(stopped)
    }

    @Test
    fun `a stream keeps the asked rate on average when Android sends a bit more often`() {
        send("7-1", "sensor-stream", "accelerometer", "rate=1")
        val report = asked.single().second
        (0L..4000L step 200).forEach { report.reading(SensorReading(listOf(0f, 0f, 0f), 3, it)) }
        assertEquals(
            listOf(0L, 800L, 1800L, 2800L, 3800L),
            file("7-1.stream").readLines().map { it.substringAfter("\"time\":").trimEnd('}').toLong() },
        )
    }

    @Test
    fun `values Android leaves out are left out, extra ones dropped`() {
        send("7-1", "sensor", "rotation-vector")
        asked.single().second.reading(SensorReading(listOf(0f, 0f, 0.5f), -1, 5))
        send("7-2", "sensor", "light")
        asked.last().second.reading(SensorReading(listOf(3f, 7f, 9f), 0, 5))
        assertEquals(
            """{"ok":true,"reading":{"sensor":"rotation-vector","values":{"x":0,"y":0,"z":0.5},"unit":"",""" +
                """"accuracy":"no-contact","time":5}}""",
            file("7-1.reply").readText(),
        )
        assertEquals(
            """{"ok":true,"reading":{"sensor":"light","values":{"illuminance":3},"unit":"lx","accuracy":"unreliable","time":5}}""",
            file("7-2.reply").readText(),
        )
    }

    @Test
    fun `the compass listens to the rotation vector and gives degrees`() {
        send("7-1", "sensor", "compass")
        val query = asked.single().first
        assertEquals("compass", query.kind.name)
        assertEquals(11, query.kind.type)
        // Turned 90° to the left from facing north: facing west.
        val half = Math.sqrt(0.5).toFloat()
        asked.single().second.reading(SensorReading(listOf(0f, 0f, half, half), 3, 5))
        assertEquals(
            """{"ok":true,"reading":{"sensor":"compass","values":{"azimuth":270,"pitch":0,"roll":0},"unit":"°",""" +
                """"accuracy":"high","time":5}}""",
            file("7-1.reply").readText(),
        )
    }

    @Test
    fun `says why there's no reading`() {
        send("7-1", "sensor", "light")
        asked.single().second.fail("no reading within 10 s")
        assertEquals("""{"ok":false,"error":"no reading within 10 s"}""", file("7-1.reply").readText())
    }

    @Test
    fun `nothing is read while android-sensors is off`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-sensors = off\n")
        send("7-1", "sensor", "light")
        send("7-2", "sensor-stream", "light")
        send("7-3", "sensor-list")
        assertTrue(asked.isEmpty())
        val off = """{"ok":false,"error":"sensors are off (pocket set android-sensors on)"}"""
        listOf("7-1", "7-2", "7-3").forEach { assertEquals(off, file("$it.reply").readText()) }
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
}

class CompassTest {
    private fun rotation(axis: String, degrees: Double): List<Float> {
        val s = Math.sin(Math.toRadians(degrees / 2)).toFloat()
        val c = Math.cos(Math.toRadians(degrees / 2)).toFloat()
        return when (axis) {
            "x" -> listOf(s, 0f, 0f, c)
            "y" -> listOf(0f, s, 0f, c)
            else -> listOf(0f, 0f, s, c)
        }
    }

    private fun near(expected: List<Double>, actual: List<Double>) =
        assertTrue(expected.zip(actual).all { (e, a) -> Math.abs(e - a) < 0.01 }, "$expected != $actual")

    @Test
    fun `flat facing north is all zero`() {
        near(listOf(0.0, 0.0, 0.0), compass(listOf(0f, 0f, 0f, 1f)))
    }

    @Test
    fun `azimuth goes clockwise from north, 0 to 360`() {
        near(listOf(90.0, 0.0, 0.0), compass(rotation("z", -90.0)))
        near(listOf(270.0, 0.0, 0.0), compass(rotation("z", 90.0)))
    }

    @Test
    fun `pitch and roll as Android's getOrientation gives them`() {
        near(listOf(0.0, -30.0, 0.0), compass(rotation("x", 30.0)))
        near(listOf(0.0, 0.0, 20.0), compass(rotation("y", 20.0)))
    }

    @Test
    fun `works out w when Android leaves it out`() {
        near(compass(rotation("z", 90.0)), compass(rotation("z", 90.0).take(3)))
    }
}
