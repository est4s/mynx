package io.github.est4s.terminal.core

import java.io.File
import java.util.Timer
import kotlin.concurrent.schedule

// How long `mynx ble` waits for the permission dialog, as `mynx bt` does.
private const val BLE_ASK_SECONDS = 120
private const val DEFAULT_SCAN_SECONDS = 5
private const val MAX_SCAN_SECONDS = 60
// Android's limit: past 5 scan starts in 30 s an app's scans silently find nothing.
private const val SCANS_ALLOWED = 5
private const val SCAN_WINDOW_MS = 30_000L
private const val STREAM_EVERY_MS = 1000L
// The permission dialog, then the host's connect (30 s) and one operation (10 s).
private const val DEVICE_SECONDS = BLE_ASK_SECONDS + 30 + 10 + 10
private const val SCAN_SECONDS = BLE_ASK_SECONDS + MAX_SCAN_SECONDS + 10

private const val BACKGROUND_SCAN = "with mynx in the background, Android only finds devices a scan filters for: " +
    "give a name or a service, or bring mynx on screen"

private class BleRefused(message: String) : Exception(message)

private val bleTimer by lazy { Timer("mynx-ble", true) }

internal fun bleSchedule(ms: Long, run: () -> Unit) {
    bleTimer.schedule(ms) { runCatching(run) }
}

private class ScanQuery(val seconds: Int, val name: String?, val service: String?, val all: Boolean) {
    val filter get() = BleScanFilter(name, service)

    fun matches(advert: BleAdvert): Boolean {
        val named = advert.name?.takeIf { it.isNotBlank() }
        return (name == null || named?.contains(name, ignoreCase = true) == true) &&
            (service == null || service in advert.services) &&
            (all || named != null || advert.services.isNotEmpty())
    }
}

/** A request's work on a device: what ends it (unsubscribing, closing a link) runs once, newest first, when it's answered or cancelled. */
private class Op(private val reply: PendingReply) {
    private var ended = false
    private val cleanups = mutableListOf<() -> Unit>()

    val active: Boolean @Synchronized get() = !ended

    /** Runs [block] when the request ends, or now (returning false) if it has. */
    fun onEnd(block: () -> Unit): Boolean {
        synchronized(this) {
            if (!ended) {
                cleanups += block
                return true
            }
        }
        runCatching(block)
        return false
    }

    fun end() {
        val list = synchronized(this) {
            if (ended) return
            ended = true
            cleanups.reversed()
        }
        list.forEach { runCatching(it) }
    }

    fun line(json: String) = reply.line(json)

    fun ok(vararg fields: Pair<String, String>) {
        end()
        reply.ok(*fields)
    }

    fun refuse(message: String) {
        end()
        reply.refuse(message)
    }

    fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: BleRefused) {
            refuse(e.message!!)
        } catch (e: Exception) {
            refuse("Bluetooth LE failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// Lines after a request's name: [count] positional ones, then options (`key=value`, or `key` for [bare] ones).
private class Args(
    private val request: String,
    raw: List<String>,
    count: Int,
    valued: Set<String> = emptySet(),
    bare: Set<String> = emptySet(),
) {
    val positional = raw.take(count)
    val options: Map<String, String?>

    init {
        options = raw.drop(count).map { it.trim() }.filter { it.isNotEmpty() }.associate { option ->
            val key = option.substringBefore('=')
            val value = if ('=' in option) option.substringAfter('=') else null
            if (!(value != null && key in valued || value == null && key in bare)) {
                throw BleRefused("unknown option '$option' for $request")
            }
            key to value
        }
    }

    fun at(i: Int, missing: () -> String): String = positional.getOrNull(i)?.takeIf { it.isNotBlank() } ?: throw BleRefused(missing())
}

private fun uuidOf(text: String) = bleUuid(text) ?: throw BleRefused("'$text' isn't a UUID or a known name")

private fun parseScan(request: String, raw: List<String>, timed: Boolean): ScanQuery {
    val args = Args(request, raw, 0, if (timed) setOf("seconds", "name", "service") else setOf("name", "service"), setOf("all"))
    val seconds = args.options["seconds"]?.let { text ->
        text.toIntOrNull()?.takeIf { it in 1..MAX_SCAN_SECONDS } ?: throw BleRefused("seconds is 1 to $MAX_SCAN_SECONDS")
    } ?: DEFAULT_SCAN_SECONDS
    return ScanQuery(
        seconds,
        args.options["name"]?.takeIf { it.isNotEmpty() },
        args.options["service"]?.let(::uuidOf),
        "all" in args.options,
    )
}

private fun advertJson(advert: BleAdvert) = obj(
    "address" to json(advert.address),
    "address_type" to json(if (advert.randomAddress) "random" else "public"),
    "name" to (advert.name?.let(::json) ?: "null"),
    "rssi" to advert.rssi.toString(),
    "services" to array(advert.services.map(::json)),
    "manufacturer" to array(advert.manufacturer.map { (company, data) -> obj("company" to company.toString(), "hex" to json(bleHex(data))) }),
    "connectable" to advert.connectable.toString(),
    "time" to advert.time.toString(),
)

/** [bleRequests] with its clock and timer given, for tests. */
internal fun bleRequests(
    home: File,
    host: BleHost,
    now: () -> Long,
    schedule: (Long, () -> Unit) -> Unit,
): Map<String, Later> {
    val settings = File(home, "$CONFIG_DIR/settings.conf")
    val lock = Any()
    val scanStarts = ArrayDeque<Long>()
    val seen = HashMap<String, BleAdvert>()

    fun <T> later(seconds: Int?, parse: (List<String>) -> T, run: (T, Op) -> Unit) = Later(seconds) { raw, reply ->
        val query = try {
            parse(raw)
        } catch (e: BleRefused) {
            return@Later reply.refuse(e.message!!)
        }
        when {
            !loadSettings(settings).settings.androidBluetooth ->
                return@Later reply.refuse("android-bluetooth is off (mynx set android-bluetooth on)")
            !host.available -> return@Later reply.refuse("this phone has no Bluetooth LE")
        }
        val op = Op(reply)
        reply.onCancel(op::end)
        fun go() = op.guard {
            if (!host.enabled) throw BleRefused("Bluetooth is off: turn it on in Android's settings (mynx bt settings)")
            run(query, op)
        }
        if (host.allowed) go() else host.askPermission { why -> if (why == null) go() else op.refuse("Bluetooth isn't allowed: $why") }
    }

    fun startScan(op: Op, query: ScanQuery, found: (BleAdvert) -> Unit) {
        if (!host.inFront && query.name == null && query.service == null) throw BleRefused(BACKGROUND_SCAN)
        synchronized(lock) {
            val time = now()
            while (scanStarts.isNotEmpty() && time - scanStarts.first() >= SCAN_WINDOW_MS) scanStarts.removeFirst()
            if (scanStarts.size >= SCANS_ALLOWED) {
                val wait = (scanStarts.first() + SCAN_WINDOW_MS - time + 999) / 1000
                throw BleRefused("Android allows $SCANS_ALLOWED scans in ${SCAN_WINDOW_MS / 1000} s and would find nothing: try again in $wait s")
            }
            scanStarts.addLast(time)
        }
        val stop = host.scan(
            query.filter,
            found = { advert ->
                synchronized(lock) { seen[advert.address] = advert }
                if (query.matches(advert)) found(advert)
            },
            failed = { why -> op.refuse("the scan failed: $why") },
        )
        op.onEnd { stop.stop() }
    }

    fun scan(query: ScanQuery, op: Op) {
        val devices = LinkedHashMap<String, BleAdvert>()
        startScan(op, query) { advert -> synchronized(devices) { devices[advert.address] = advert } }
        schedule(query.seconds * 1000L) {
            op.guard {
                val list = synchronized(devices) { devices.values.sortedByDescending { it.rssi } }
                op.ok("devices" to array(list.map(::advertJson)))
            }
        }
    }

    fun scanStream(query: ScanQuery, op: Op) {
        val sent = HashMap<String, Long>()
        startScan(op, query) { advert ->
            val due = synchronized(sent) {
                val last = sent[advert.address]
                (last == null || advert.time - last >= STREAM_EVERY_MS).also { if (it) sent[advert.address] = advert.time }
            }
            if (due) op.line(advertJson(advert))
        }
    }

    return mapOf(
        "ble-scan" to later(SCAN_SECONDS, { parseScan("ble-scan", it, timed = true) }, ::scan),
        "ble-scan-stream" to later(null, { parseScan("ble-scan-stream", it, timed = false) }, ::scanStream),
    )
}
