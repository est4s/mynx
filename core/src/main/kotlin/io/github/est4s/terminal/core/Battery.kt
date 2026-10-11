package io.github.est4s.terminal.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** The longest `mynx battery --stream --every` waits between lines. */
const val MAX_BATTERY_INTERVAL = 3600
// The app reads at the interval, so a reading may come a little early.
private const val INTERVAL_SLACK_MS = 1000

/**
 * What `mynx battery` asks for: one reading, or a [stream] of a line
 * whenever something worth knowing changes, and at least every
 * [intervalSeconds] when given (the app then reads that often too).
 */
data class BatteryQuery(val stream: Boolean, val intervalSeconds: Int?)

/**
 * What Android says about the battery, as it says it: [level] of
 * [scale], [status], [plugged] and [health] as BatteryManager's numbers,
 * [temperature] in tenths of °C, [voltage] in mV, [current] and
 * [currentAverage] in µA (into the battery), [charge] in µAh,
 * [chargeTimeMs] until full, [cycles], [powerSaver], and PowerManager's
 * [thermal] status. Null when this Android version doesn't have it; the
 * phone's own "don't know" values (Int.MIN_VALUE, -1, 0) are handled
 * here. [time] is in ms since 1970.
 */
data class BatteryReading(
    val level: Int,
    val scale: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val temperature: Int?,
    val voltage: Int?,
    val technology: String?,
    val current: Int?,
    val currentAverage: Int?,
    val charge: Int?,
    val chargeTimeMs: Long?,
    val cycles: Int?,
    val powerSaver: Boolean,
    val thermal: Int?,
    val time: Long,
)

/** Where the app sends readings for one [BatteryQuery]; any thread may use it. */
interface BatteryReport {
    fun reading(reading: BatteryReading)
    fun fail(message: String)
    /** Stop listening: `mynx` stopped, or has gone. */
    fun onCancel(block: () -> Unit)
}

/**
 * The `battery` and `battery-stream` requests, for [MynxRequests]'s
 * `later`. [read] starts reading and must not block. A stream's
 * request line may be `interval=SECONDS`.
 */
fun batteryRequests(read: (BatteryQuery, BatteryReport) -> Unit): Map<String, Later> {
    fun later(stream: Boolean, seconds: Int?) = Later(seconds) { args, reply ->
        val query = parseBatteryQuery(args, stream).getOrElse {
            reply.refuse(it.message!!)
            return@Later
        }
        var shown: List<Any?>? = null
        var ticked = 0L
        read(query, object : BatteryReport {
            @Synchronized
            override fun reading(reading: BatteryReading) {
                if (!stream) return reply.ok("battery" to batteryJson(reading))
                val now = worthKnowing(reading)
                val interval = query.intervalSeconds?.let { it * 1000L - INTERVAL_SLACK_MS }
                val due = shown == null || interval != null && reading.time - ticked >= interval
                if (now == shown && !due) return
                if (due) ticked = reading.time
                shown = now
                reply.line(batteryJson(reading))
            }

            override fun fail(message: String) = reply.refuse(message)

            override fun onCancel(block: () -> Unit) = reply.onCancel(block)
        })
    }
    return mapOf("battery" to later(false, 10), "battery-stream" to later(true, null))
}

private fun parseBatteryQuery(args: List<String>, stream: Boolean): Result<BatteryQuery> = runCatching {
    var query = BatteryQuery(stream, null)
    for (option in args.map { it.trim() }.filter { it.isNotEmpty() }) {
        if (!stream || !option.startsWith("interval=")) throw IllegalArgumentException("unknown battery option '$option'")
        query = query.copy(
            intervalSeconds = option.substringAfter('=').toIntOrNull()?.takeIf { it in 1..MAX_BATTERY_INTERVAL }
                ?: throw IllegalArgumentException("the interval is 1 to $MAX_BATTERY_INTERVAL seconds"),
        )
    }
    query
}

// Voltage and current change all the time; a stream skips those changes.
private fun worthKnowing(r: BatteryReading): List<Any?> =
    listOf(percent(r), r.status, r.plugged, r.health, r.temperature, r.powerSaver, r.thermal, r.cycles)

private fun percent(r: BatteryReading): Int? =
    if (r.level < 0 || r.scale <= 0) null else (r.level * 100.0 / r.scale).roundToInt()

private val STATUSES = mapOf(2 to "charging", 3 to "discharging", 4 to "not-charging", 5 to "full")
private val PLUGS = mapOf(1 to "ac", 2 to "usb", 4 to "wireless", 8 to "dock")
private val HEALTHS = mapOf(
    2 to "good", 3 to "overheat", 4 to "dead", 5 to "over-voltage", 6 to "failure", 7 to "cold",
)
private val THERMALS = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
private const val DISCHARGING = 3

/** One reading as JSON: SI units, current positive into the battery. */
fun batteryJson(r: BatteryReading): String {
    fun known(value: Int?) = value?.takeIf { it != Int.MIN_VALUE }
    // Phones disagree on the current's sign; discharging, it can only flow out.
    fun amps(microamps: Int?) = known(microamps)?.let {
        (if (r.status == DISCHARGING) -abs(it) else it) / 1_000_000.0
    }
    // Some phones report volts instead of millivolts.
    val volts = known(r.voltage)?.takeIf { it > 0 }?.let { if (it < 100) it.toDouble() else it / 1000.0 }
    return obj(
        "percent" to (percent(r)?.toString() ?: "null"),
        "status" to json(STATUSES[r.status] ?: "unknown"),
        "plugged" to (PLUGS[r.plugged]?.let(::json) ?: "null"),
        "health" to json(HEALTHS[r.health] ?: "unknown"),
        "temperature" to number(known(r.temperature)?.let { it / 10.0 }, 1),
        "voltage" to number(volts, 3),
        "current" to number(amps(r.current), 3),
        "current_average" to number(amps(r.currentAverage), 3),
        "charge" to number(known(r.charge)?.takeIf { it > 0 }?.let { it / 1000.0 }, 0),
        "time_to_full" to (r.chargeTimeMs?.takeIf { it >= 0 }?.let { (it / 1000).toString() } ?: "null"),
        "cycles" to (r.cycles?.takeIf { it >= 0 }?.toString() ?: "null"),
        "technology" to (r.technology?.takeIf { it.isNotBlank() }?.let(::json) ?: "null"),
        "battery_saver" to r.powerSaver.toString(),
        "thermal" to (r.thermal?.let { THERMALS.getOrNull(it) }?.let(::json) ?: "null"),
        "time" to r.time.toString(),
    )
}
