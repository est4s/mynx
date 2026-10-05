package io.github.est4s.terminal.core

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/** The longest `pocket location` waits for one fix. */
const val MAX_LOCATION_TIMEOUT = 300
private const val DEFAULT_TIMEOUT = 60
private const val DEFAULT_INTERVAL = 5
private const val MAX_INTERVAL = 3600

/**
 * What `pocket location` asks for. Unless [gpsOnly], the first fix from
 * GPS or the network wins. A [stream] sends a fix about every
 * [intervalSeconds]; one fix gives up after [timeoutSeconds].
 */
data class LocationQuery(val gpsOnly: Boolean, val stream: Boolean, val intervalSeconds: Int, val timeoutSeconds: Int)

/** A position from Android; null for what the provider doesn't know. [time] is in ms since 1970. */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val speed: Float?,
    val bearing: Float?,
    val provider: String,
    val time: Long,
)

/** Where the app reports fixes for one [LocationQuery]; any thread may use it. */
interface LocationReport {
    /** The fix (one request), or one more reading (a stream). */
    fun fix(fix: Fix)
    fun fail(message: String)
    /** Stop listening: `pocket` stopped, or has gone. */
    fun onCancel(block: () -> Unit)
}

/**
 * The `location` and `location-stream` requests, for
 * [PocketRequests]'s `later`. Option lines: `gps`, `interval=SECONDS`,
 * `timeout=SECONDS`. [locate] starts listening and must not block.
 */
fun locationRequests(home: File, locate: (LocationQuery, LocationReport) -> Unit): Map<String, Later> {
    val settings = File(home, "$CONFIG_DIR/settings.conf")
    fun later(stream: Boolean, seconds: Int?) = Later(seconds) { args, reply ->
        val query = parseLocationQuery(args, stream).getOrElse {
            reply.refuse(it.message!!)
            return@Later
        }
        if (!loadSettings(settings).settings.androidLocation) {
            reply.refuse("location is off (pocket set android-location on)")
            return@Later
        }
        locate(query, object : LocationReport {
            override fun fix(fix: Fix) {
                if (stream) reply.line(fixJson(fix)) else reply.ok("location" to fixJson(fix))
            }

            override fun fail(message: String) = reply.refuse(message)

            override fun onCancel(block: () -> Unit) = reply.onCancel(block)
        })
    }
    return mapOf("location" to later(false, MAX_LOCATION_TIMEOUT), "location-stream" to later(true, null))
}

private fun parseLocationQuery(args: List<String>, stream: Boolean): Result<LocationQuery> = runCatching {
    var query = LocationQuery(false, stream, DEFAULT_INTERVAL, DEFAULT_TIMEOUT)
    for (option in args.map { it.trim() }.filter { it.isNotEmpty() }) {
        val value = option.substringAfter('=', "").toIntOrNull()
        query = when {
            option == "gps" -> query.copy(gpsOnly = true)
            option.startsWith("timeout=") -> query.copy(
                timeoutSeconds = value?.takeIf { it in 1..MAX_LOCATION_TIMEOUT }
                    ?: throw IllegalArgumentException("the timeout is 1 to $MAX_LOCATION_TIMEOUT seconds"),
            )
            option.startsWith("interval=") -> query.copy(
                intervalSeconds = value?.takeIf { it in 1..MAX_INTERVAL }
                    ?: throw IllegalArgumentException("the interval is 1 to $MAX_INTERVAL seconds"),
            )
            else -> throw IllegalArgumentException("unknown location option '$option'")
        }
    }
    query
}

/** A [Fix] as JSON: 7 decimals for degrees (about 1 cm), 1 for the rest. */
fun fixJson(fix: Fix): String = obj(
    "latitude" to number(fix.latitude, 7),
    "longitude" to number(fix.longitude, 7),
    "accuracy" to number(fix.accuracy?.toDouble(), 1),
    "altitude" to number(fix.altitude, 1),
    "speed" to number(fix.speed?.toDouble(), 1),
    "bearing" to number(fix.bearing?.toDouble(), 1),
    "provider" to json(fix.provider),
    "time" to fix.time.toString(),
)

private fun number(value: Double?, decimals: Int): String =
    if (value == null || !value.isFinite()) "null"
    else BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/**
 * What Android allows: [precise] and [approximate] are the granted
 * location permissions, [locationOn] the phone's location switch, and
 * [hasGps]/[hasNetwork] whether those providers exist.
 */
data class LocationAccess(
    val precise: Boolean,
    val approximate: Boolean,
    val locationOn: Boolean,
    val hasGps: Boolean,
    val hasNetwork: Boolean,
)

/** Android's location providers to listen to for [query] (`gps`, `network`), or why there are none. */
fun locationProviders(query: LocationQuery, access: LocationAccess): Result<List<String>> {
    fun no(message: String) = Result.failure<List<String>>(IllegalStateException(message))
    return when {
        !access.locationOn -> no("location is off on the phone (turn it on in quick settings)")
        !access.precise && !access.approximate ->
            no("the app isn't allowed to use location (allow it in the app's Android settings)")
        query.gpsOnly && !access.hasGps -> no("the phone has no GPS")
        query.gpsOnly && !access.precise ->
            no("GPS needs precise location, and the app only has approximate (allow precise location in the app's Android settings)")
        query.gpsOnly -> Result.success(listOf("gps"))
        else -> listOfNotNull("gps".takeIf { access.precise && access.hasGps }, "network".takeIf { access.hasNetwork })
            .takeIf { it.isNotEmpty() }?.let { Result.success(it) } ?: no("no location provider on the phone")
    }
}
