package io.github.est4s.terminal.core

import java.io.File
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** The longest `pocket sensor` waits for one reading. */
const val MAX_SENSOR_TIMEOUT = 60
/** Faster needs Android's HIGH_SAMPLING_RATE_SENSORS permission. */
const val MAX_SENSOR_RATE = 200
private const val DEFAULT_TIMEOUT = 10
private const val DEFAULT_RATE = 10
private const val ACTIVITY = "android.permission.ACTIVITY_RECOGNITION"

/**
 * A kind of sensor `pocket sensor` reads: Android's sensor [type], what
 * its [values] are, their [unit], and a runtime [permission] it needs
 * beyond the app's own. [derived] turns Android's values into this
 * kind's (the compass).
 */
data class SensorKind(
    val name: String,
    val type: Int,
    val values: List<String>,
    val unit: String,
    val permission: String? = null,
    val decimals: Int = 4,
    val derived: ((List<Float>) -> List<Double>)? = null,
)

private val XYZ = listOf("x", "y", "z")
private val QUATERNION = listOf("x", "y", "z", "w")

/** The sensors `pocket sensor` knows, by Android's type number; the derived ones last. */
val SENSOR_KINDS: List<SensorKind> = listOf(
    SensorKind("accelerometer", 1, XYZ, "m/s²"),
    SensorKind("magnetic-field", 2, XYZ, "µT"),
    SensorKind("gyroscope", 4, XYZ, "rad/s"),
    SensorKind("light", 5, listOf("illuminance"), "lx"),
    SensorKind("pressure", 6, listOf("pressure"), "hPa"),
    SensorKind("proximity", 8, listOf("distance"), "cm"),
    SensorKind("gravity", 9, XYZ, "m/s²"),
    SensorKind("linear-acceleration", 10, XYZ, "m/s²"),
    SensorKind("rotation-vector", 11, QUATERNION, ""),
    SensorKind("humidity", 12, listOf("humidity"), "%"),
    SensorKind("ambient-temperature", 13, listOf("temperature"), "°C"),
    SensorKind("magnetic-field-uncalibrated", 14, XYZ + listOf("bias-x", "bias-y", "bias-z"), "µT"),
    SensorKind("game-rotation-vector", 15, QUATERNION, ""),
    SensorKind("gyroscope-uncalibrated", 16, XYZ + listOf("drift-x", "drift-y", "drift-z"), "rad/s"),
    SensorKind("step-detector", 18, listOf("step"), "", permission = ACTIVITY),
    SensorKind("step-counter", 19, listOf("steps"), "steps", permission = ACTIVITY),
    SensorKind("geomagnetic-rotation-vector", 20, QUATERNION, ""),
    SensorKind("accelerometer-uncalibrated", 35, XYZ + listOf("bias-x", "bias-y", "bias-z"), "m/s²"),
    SensorKind("hinge-angle", 36, listOf("angle"), "°"),
    SensorKind("heading", 42, listOf("heading"), "°"),
    SensorKind("compass", 11, listOf("azimuth", "pitch", "roll"), "°", decimals = 1, derived = ::compass),
)

/**
 * What `pocket sensor` asks for: one reading of [kind] within
 * [timeoutSeconds], or a [stream] of at most [rateHz] readings a second.
 */
data class SensorQuery(val kind: SensorKind, val stream: Boolean, val rateHz: Int, val timeoutSeconds: Int) {
    /** How often to ask Android for readings. */
    val periodMicros: Int get() = 1_000_000 / rateHz
}

/** Android's values for one event, its accuracy (SensorManager's -1 to 3) and [time] in ms since 1970. */
data class SensorReading(val values: List<Float>, val accuracy: Int, val time: Long)

/** Where the app sends readings for one [SensorQuery]; any thread may use it. */
interface SensorReport {
    fun reading(reading: SensorReading)
    fun fail(message: String)
    /** Stop listening: `pocket` stopped, or has gone. */
    fun onCancel(block: () -> Unit)
}

/**
 * The `sensor-list`, `sensor` and `sensor-stream` requests, for
 * [PocketRequests]'s `later`. [hasType] says whether the phone has a
 * sensor of an Android type; [read] starts listening and must not block.
 * Request lines: the sensor's name, then `timeout=SECONDS` or
 * `rate=PER_SECOND`.
 */
fun sensorRequests(
    home: File,
    hasType: (Int) -> Boolean,
    read: (SensorQuery, SensorReport) -> Unit,
): Map<String, Later> {
    val settings = File(home, "$CONFIG_DIR/settings.conf")
    fun on(reply: PendingReply) = loadSettings(settings).settings.androidSensors.also {
        if (!it) reply.refuse("sensors are off (pocket set android-sensors on)")
    }

    val list = Later(MAX_SENSOR_TIMEOUT) { _, reply ->
        if (!on(reply)) return@Later
        reply.ok("sensors" to SENSOR_KINDS.filter { hasType(it.type) }.joinToString(",", "[", "]") {
            obj("name" to json(it.name), "values" to it.values.joinToString(",", "[", "]") { v -> json(v) },
                "unit" to json(it.unit))
        })
    }

    fun later(stream: Boolean, seconds: Int?) = Later(seconds) { args, reply ->
        val query = parseSensorQuery(args, stream, hasType).getOrElse {
            reply.refuse(it.message!!)
            return@Later
        }
        if (!on(reply)) return@Later
        val minGap = 800.0 / query.rateHz
        var last: Long? = null
        read(query, object : SensorReport {
            @Synchronized
            override fun reading(reading: SensorReading) {
                val json = readingJson(query.kind, reading)
                if (!stream) return reply.ok("reading" to json)
                // Android often sends more often than asked.
                if (last.let { it != null && reading.time - it < minGap }) return
                last = reading.time
                reply.line(json)
            }

            override fun fail(message: String) = reply.refuse(message)

            override fun onCancel(block: () -> Unit) = reply.onCancel(block)
        })
    }
    return mapOf("sensor-list" to list, "sensor" to later(false, MAX_SENSOR_TIMEOUT), "sensor-stream" to later(true, null))
}

private fun parseSensorQuery(args: List<String>, stream: Boolean, hasType: (Int) -> Boolean): Result<SensorQuery> =
    runCatching {
        val words = args.map { it.trim() }.filter { it.isNotEmpty() }
        val name = words.firstOrNull() ?: throw IllegalArgumentException("which sensor? (pocket sensor list)")
        val kind = SENSOR_KINDS.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("no sensor called '$name' (pocket sensor list)")
        var query = SensorQuery(kind, stream, DEFAULT_RATE, DEFAULT_TIMEOUT)
        for (option in words.drop(1)) {
            val value = option.substringAfter('=', "").toIntOrNull()
            query = when {
                option.startsWith("timeout=") -> query.copy(
                    timeoutSeconds = value?.takeIf { it in 1..MAX_SENSOR_TIMEOUT }
                        ?: throw IllegalArgumentException("the timeout is 1 to $MAX_SENSOR_TIMEOUT seconds"),
                )
                option.startsWith("rate=") -> query.copy(
                    rateHz = value?.takeIf { it in 1..MAX_SENSOR_RATE }
                        ?: throw IllegalArgumentException("the rate is 1 to $MAX_SENSOR_RATE per second"),
                )
                else -> throw IllegalArgumentException("unknown sensor option '$option'")
            }
        }
        if (!hasType(kind.type)) throw IllegalStateException("the phone has no $name sensor")
        query
    }

private fun readingJson(kind: SensorKind, reading: SensorReading): String {
    val values = kind.derived?.invoke(reading.values) ?: reading.values.map { it.toDouble() }
    return obj(
        "sensor" to json(kind.name),
        "values" to obj(*kind.values.zip(values) { name, value -> name to number(value, kind.decimals) }.toTypedArray()),
        "unit" to json(kind.unit),
        "accuracy" to json(
            when (reading.accuracy) {
                -1 -> "no-contact"
                0 -> "unreliable"
                1 -> "low"
                2 -> "medium"
                3 -> "high"
                else -> "unknown"
            },
        ),
        "time" to reading.time.toString(),
    )
}

/**
 * Azimuth (clockwise from magnetic north, 0 to 360), pitch and roll in
 * degrees from a rotation vector's x, y, z (and w, worked out when
 * missing), as Android's getRotationMatrixFromVector and getOrientation
 * give them.
 */
fun compass(rotation: List<Float>): List<Double> {
    val (x, y, z) = rotation.map { it.toDouble() }
    val w = rotation.getOrNull(3)?.toDouble() ?: sqrt(maxOf(0.0, 1 - x * x - y * y - z * z))
    val r1 = 2 * x * y - 2 * z * w
    val r4 = 1 - 2 * x * x - 2 * z * z
    val r6 = 2 * x * z - 2 * y * w
    val r7 = 2 * y * z + 2 * x * w
    val r8 = 1 - 2 * x * x - 2 * y * y
    val azimuth = Math.toDegrees(atan2(r1, r4)).let { if (it < 0) it + 360 else it }
    return listOf(azimuth, Math.toDegrees(asin(-r7.coerceIn(-1.0, 1.0))), Math.toDegrees(atan2(-r6, r8)))
}
