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

/** CHAR as given: [text], the [service] it names (`SERVICE/CHAR`) and the characteristic's [uuid]. */
private class CharRef(val text: String, val service: String?, val uuid: String)

private class DeviceQuery(
    val device: String,
    val char: CharRef? = null,
    val format: BleFormat? = null,
    val value: ByteArray? = null,
    val noResponse: Boolean = false,
)

private val FORMAT_WORDS = BleFormat.entries.map { it.word }.let { it.dropLast(1).joinToString(", ") + " or " + it.last() }

// DEVICE, then CHAR when [chars], then VALUE when [writes]; the options each takes.
private fun parseDevice(request: String, raw: List<String>, chars: Boolean = false, writes: Boolean = false): DeviceQuery {
    val count = 1 + (if (chars) 1 else 0) + (if (writes) 1 else 0)
    val args = Args(request, raw, count, if (chars) setOf("as") else emptySet(), if (writes) setOf("no-response") else emptySet())
    val device = args.at(0) { "which device? (an address, or a name: mynx ble scan finds them)" }.trim()
    if (!chars) return DeviceQuery(device)
    val text = args.at(1) { "which characteristic? (a UUID or a name: mynx ble services $device lists them)" }.trim()
    val char = if ('/' in text) {
        CharRef(text, uuidOf(text.substringBefore('/')), uuidOf(text.substringAfter('/')))
    } else {
        CharRef(text, null, uuidOf(text))
    }
    val format = args.options["as"]?.let { word -> bleFormat(word) ?: throw BleRefused("'$word' isn't a format: $FORMAT_WORDS") }
    if (!writes) return DeviceQuery(device, char, format)
    val written = args.at(2) { "what to write? (a value: hex unless as= says)" }
    val value = try {
        (format ?: BleFormat.HEX).parse(written)
    } catch (e: IllegalArgumentException) {
        throw BleRefused(e.message!!)
    }
    return DeviceQuery(device, char, format, value, "no-response" in args.options)
}

/** A link `ble-connect` holds ([link] once it's up), and what uses it wants to hear of a drop. */
private class Held {
    var link: BleLink? = null
    val listeners = mutableListOf<(String) -> Unit>()
}

/** A device a request names: [random] as a scan saw its address. */
private class Dev(val address: String, val name: String?, val random: Boolean) {
    val who get() = if (name != null) "$name ($address)" else address
    val json get() = obj("address" to json(address), "name" to (name?.let(::json) ?: "null"))
}

private fun label(uuid: String) = bleName(uuid) ?: uuid

private fun why(e: Throwable) = e.message ?: e.javaClass.simpleName

private fun propsText(char: BleChar) =
    BleProp.entries.filter { it in char.properties }.joinToString(", ") { it.word }.ifEmpty { "none" }

private fun servicesJson(link: BleLink) = array(link.services.map { service ->
    obj(
        "uuid" to json(service.uuid),
        "name" to (bleName(service.uuid)?.let(::json) ?: "null"),
        "characteristics" to array(service.characteristics.map { char ->
            obj(
                "uuid" to json(char.uuid),
                "name" to (bleName(char.uuid)?.let(::json) ?: "null"),
                "properties" to array(BleProp.entries.filter { it in char.properties }.map { json(it.word) }),
                "descriptors" to array(char.descriptors.map(::json)),
            )
        }),
    )
})

private fun findChar(link: BleLink, dev: Dev, ref: CharRef): BleChar {
    val found = link.services.flatMap { it.characteristics }
        .filter { it.uuid == ref.uuid && (ref.service == null || it.service == ref.service) }
    if (found.isEmpty()) throw BleRefused("${dev.who} has no characteristic ${ref.text} (mynx ble services ${dev.address} lists them)")
    val services = found.map { it.service }.distinct()
    if (services.size > 1) {
        throw BleRefused(
            "${ref.text} is in ${services.size} services (${services.joinToString(", ", transform = ::label)}): name one as SERVICE/CHAR",
        )
    }
    return found.first()
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
    // Every scan's latest advertisement of each device, for DEVICE names and random addresses.
    val seen = HashMap<String, BleAdvert>()
    // The links `ble-connect` holds, by address: from its start, so a second one is refused while the first connects.
    val held = HashMap<String, Held>()

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

    fun device(text: String): Dev {
        val bonded = host.bonded()
        val scanned = synchronized(lock) { seen.values.toList() }
        fun random(address: String) = scanned.firstOrNull { it.address == address }?.randomAddress == true
        btAddress(text)?.let { address ->
            val name = bonded.firstOrNull { it.address == address }?.name ?: scanned.firstOrNull { it.address == address }?.name
            return Dev(address, name, random(address))
        }
        val named = (bonded.mapNotNull { d -> d.name?.let { Dev(d.address, it, random(d.address)) } } +
            scanned.mapNotNull { a -> a.name?.let { Dev(a.address, it, a.randomAddress) } }).distinctBy { it.address }
        val matches = named.filter { it.name == text }.ifEmpty { named.filter { it.name.equals(text, ignoreCase = true) } }
        return when (matches.size) {
            0 -> throw BleRefused("no device called $text: scan for it (mynx ble scan), or give its address")
            1 -> matches.single()
            else -> throw BleRefused(
                "${matches.size} devices are called $text: give its address (" +
                    matches.joinToString(", ") { "${it.address} ${it.name}" } + ")",
            )
        }
    }

    // Runs [job] on the link `ble-connect` holds, else on one of [op]'s own, which closes when op ends.
    fun useLink(op: Op, dev: Dev, dropped: (String) -> Unit, job: (BleLink) -> Unit) {
        val hold = synchronized(lock) { held[dev.address]?.takeIf { it.link != null }?.also { it.listeners += dropped } }
        if (hold != null) {
            if (op.onEnd { synchronized(lock) { hold.listeners -= dropped } }) job(hold.link!!)
            return
        }
        host.connect(
            dev.address,
            dev.random,
            ready = { result ->
                op.guard {
                    val link = result.getOrElse { throw BleRefused("couldn't connect to ${dev.who}: ${why(it)}") }
                    if (op.onEnd(link::close)) job(link)
                }
            },
            dropped = dropped,
        )
    }

    fun deviceOp(query: DeviceQuery, op: Op, job: (Dev, BleLink, BleChar?) -> Unit) {
        val dev = device(query.device)
        useLink(op, dev, { why -> op.refuse("the link to ${dev.who} dropped: $why") }) { link ->
            job(dev, link, query.char?.let { findChar(link, dev, it) })
        }
    }

    fun services(query: DeviceQuery, op: Op) = deviceOp(query, op) { dev, link, _ ->
        op.ok("device" to dev.json, "mtu" to link.mtu.toString(), "services" to servicesJson(link))
    }

    fun read(query: DeviceQuery, op: Op) = deviceOp(query, op) { dev, link, char ->
        char!!
        if (BleProp.READ !in char.properties) throw BleRefused("${label(char.uuid)} can't be read (it has: ${propsText(char)})")
        val format = query.format ?: defaultFormat(char.uuid)
        link.read(char) { result ->
            op.guard {
                val bytes = result.getOrElse { throw BleRefused("couldn't read ${label(char.uuid)}: ${why(it)}") }
                op.ok(
                    "device" to dev.json,
                    "char" to json(char.uuid),
                    "name" to (bleName(char.uuid)?.let(::json) ?: "null"),
                    "as" to json(format.word),
                    "value" to format.json(bytes),
                    "hex" to json(bleHex(bytes)),
                )
            }
        }
    }

    fun write(query: DeviceQuery, op: Op) = deviceOp(query, op) { dev, link, char ->
        char!!
        val value = query.value!!
        val name = label(char.uuid)
        val withResponse = BleProp.WRITE in char.properties
        val withoutResponse = BleProp.WRITE_NO_RESPONSE in char.properties
        when {
            !withResponse && !withoutResponse -> throw BleRefused("$name can't be written (it has: ${propsText(char)})")
            query.noResponse && !withoutResponse ->
                throw BleRefused("$name doesn't take writes without a response: leave out no-response")
            !query.noResponse && !withResponse -> throw BleRefused("$name only takes writes without a response: add no-response")
        }
        val limit = link.mtu - 3
        if (value.size > limit) {
            throw BleRefused("${value.size} bytes is too long: a write takes at most $limit here (the MTU is ${link.mtu}, less 3)")
        }
        link.write(char, value, !query.noResponse) { result ->
            op.guard {
                result.getOrElse { throw BleRefused("couldn't write $name: ${why(it)}") }
                op.ok(
                    "device" to dev.json,
                    "char" to json(char.uuid),
                    "name" to (bleName(char.uuid)?.let(::json) ?: "null"),
                    "bytes" to value.size.toString(),
                )
            }
        }
    }

    fun notify(query: DeviceQuery, op: Op) = deviceOp(query, op) { _, link, char ->
        char!!
        val name = label(char.uuid)
        if (BleProp.NOTIFY !in char.properties && BleProp.INDICATE !in char.properties) {
            throw BleRefused("$name can't notify or indicate (it has: ${propsText(char)})")
        }
        val format = query.format ?: defaultFormat(char.uuid)
        val stop = link.subscribe(
            char,
            value = { bytes, time ->
                op.line(
                    obj(
                        "char" to json(char.uuid),
                        "name" to (bleName(char.uuid)?.let(::json) ?: "null"),
                        "as" to json(format.word),
                        "value" to format.json(bytes),
                        "hex" to json(bleHex(bytes)),
                        "time" to time.toString(),
                    ),
                )
            },
            done = { result -> result.onFailure { op.refuse("couldn't subscribe to $name: ${why(it)}") } },
        )
        op.onEnd(stop::stop)
    }

    fun connect(query: DeviceQuery, op: Op) {
        val dev = device(query.device)
        val hold = Held()
        synchronized(lock) {
            if (dev.address in held) throw BleRefused("${dev.address} is already held by another mynx ble connect")
            held[dev.address] = hold
        }
        fun release() = synchronized(lock) { if (held[dev.address] === hold) held.remove(dev.address) }
        op.onEnd(::release)
        host.connect(
            dev.address,
            dev.random,
            ready = { result ->
                op.guard {
                    val link = result.getOrElse { throw BleRefused("couldn't connect to ${dev.who}: ${why(it)}") }
                    val kept = op.onEnd {
                        release()
                        link.close()
                    }
                    if (kept) {
                        synchronized(lock) { hold.link = link }
                        op.line(obj("state" to json("connected"), "device" to dev.json, "mtu" to link.mtu.toString()))
                    }
                }
            },
            dropped = { why ->
                release()
                val listeners = synchronized(lock) { hold.listeners.toList() }
                op.line(obj("state" to json("disconnected"), "device" to dev.json, "error" to json(why)))
                listeners.forEach { runCatching { it(why) } }
                op.refuse("the link to ${dev.who} dropped: $why")
            },
        )
    }

    return mapOf(
        "ble-notify" to later(null, { parseDevice("ble-notify", it, chars = true) }, ::notify),
        "ble-connect" to later(null, { parseDevice("ble-connect", it) }, ::connect),
        "ble-scan" to later(SCAN_SECONDS, { parseScan("ble-scan", it, timed = true) }, ::scan),
        "ble-scan-stream" to later(null, { parseScan("ble-scan-stream", it, timed = false) }, ::scanStream),
        "ble-services" to later(DEVICE_SECONDS, { parseDevice("ble-services", it) }, ::services),
        "ble-read" to later(DEVICE_SECONDS, { parseDevice("ble-read", it, chars = true) }, ::read),
        "ble-write" to later(DEVICE_SECONDS, { parseDevice("ble-write", it, chars = true, writes = true) }, ::write),
    )
}
