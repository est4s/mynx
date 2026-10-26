package io.github.est4s.terminal.core

import java.io.File

/** The Bluetooth ports file, in [CONFIG_DIR]: which paired device each `rfcommN` port links to. */
const val BLUETOOTH_CONF = "bluetooth.conf"
// Linux's RFCOMM_MAX_DEV.
private const val MAX_RFCOMM = 255

/** A Bluetooth serial port: [port] (`rfcomm0`) links to the device at [address] (upper case) when it's used. */
data class BtBinding(val port: String, val address: String)

data class ParsedBluetooth(val bindings: List<BtBinding>, val problems: List<String>)

private val RFCOMM_NAME = Regex("rfcomm(0|[1-9][0-9]{0,2})")
private val BT_ADDRESS = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

fun isRfcommName(name: String): Boolean =
    RFCOMM_NAME.matchEntire(name)?.groupValues?.get(1)?.toInt()?.let { it <= MAX_RFCOMM } == true

internal fun badRfcommName(name: String) = "'$name' isn't a Bluetooth port's name: use rfcomm0 to rfcomm$MAX_RFCOMM"

/** [text] as a Bluetooth address in upper case, or null if it isn't one. */
fun btAddress(text: String): String? = text.trim().takeIf { BT_ADDRESS.matches(it) }?.uppercase()

/** The lowest `rfcommN` not in [taken], as `rfcomm bind` numbers them. */
fun rfcommName(taken: Set<String>): String = (0..MAX_RFCOMM).map { "rfcomm$it" }.firstOrNull { it !in taken }
    ?: error("every rfcomm port is taken")

// A line's binding, or why it can't be one; null for comments and blank lines.
private fun lineBinding(raw: String): Result<BtBinding>? {
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return null
    if ('=' !in line) return Result.failure(Exception("expected rfcommN = ADDRESS"))
    val port = line.substringBefore('=').trim()
    val value = line.substringAfter('=').trim()
    if (!isRfcommName(port)) return Result.failure(Exception(badRfcommName(port)))
    val address = btAddress(value)
        ?: return Result.failure(Exception("'$value' isn't a Bluetooth address (like D4:E9:F4:A2:4A:02)"))
    return Result.success(BtBinding(port, address))
}

/** Reads `bluetooth.conf` (`rfcomm0 = D4:E9:F4:A2:4A:02` lines); bad lines are reported and skipped. */
fun parseBluetooth(text: String): ParsedBluetooth {
    val bindings = mutableListOf<BtBinding>()
    val problems = mutableListOf<String>()
    val ports = mutableMapOf<String, Int>()
    val addresses = mutableMapOf<String, Pair<String, Int>>()
    text.lines().forEachIndexed { i, raw ->
        val number = i + 1
        val binding = lineBinding(raw)?.getOrElse {
            problems += "line $number: ${it.message}"
            null
        } ?: return@forEachIndexed
        val portLine = ports[binding.port]
        val earlier = addresses[binding.address]
        when {
            portLine != null -> problems += "line $number: ${binding.port} is already bound on line $portLine"
            earlier != null -> problems += "line $number: ${binding.address} is already bound to ${earlier.first} on line ${earlier.second}"
            else -> {
                ports[binding.port] = number
                addresses[binding.address] = binding.port to number
                bindings += binding
            }
        }
    }
    return ParsedBluetooth(bindings, problems)
}

fun loadBluetooth(file: File): ParsedBluetooth =
    if (file.isFile) parseBluetooth(file.readText()) else ParsedBluetooth(emptyList(), emptyList())

/** What a new `bluetooth.conf` starts with. */
const val BLUETOOTH_HEADER = """# Bluetooth serial ports: one "rfcommN = ADDRESS" per line, a device
# paired in Android's Bluetooth settings. `mynx bt bind` writes them,
# `mynx check` checks edits made by hand (see ~/AGENTS.md).
"""

/**
 * [text] (`bluetooth.conf`, null if there's none) with [binding]'s line
 * added at the end, everything else kept. Fails with a message for
 * people if the name is wrong, or the port or address is taken.
 */
fun addBluetoothBinding(text: String?, binding: BtBinding): Result<String> = runCatching {
    if (!isRfcommName(binding.port)) error(badRfcommName(binding.port))
    val address = btAddress(binding.address) ?: error("'${binding.address}' isn't a Bluetooth address")
    val bindings = text?.let { parseBluetooth(it).bindings }.orEmpty()
    bindings.firstOrNull { it.port == binding.port }?.let { error("${it.port} is already bound to ${it.address}") }
    bindings.firstOrNull { it.address == address }?.let { error("${it.address} is already bound to ${it.port}") }
    val lines = (text ?: BLUETOOTH_HEADER).lines().dropLastWhile { it.isBlank() }
    (lines + "${binding.port} = $address").joinToString("\n", postfix = "\n")
}

/** A file's text after [removeBluetoothBindings], and the ports it took out, in file order. */
data class Unbound(val text: String, val ports: List<String>)

/**
 * [text] (`bluetooth.conf`) without the lines whose binding [matches],
 * duplicates the parser skips included; comments and the rest are kept.
 */
fun removeBluetoothBindings(text: String, matches: (BtBinding) -> Boolean): Unbound {
    val removed = mutableListOf<String>()
    val kept = text.lines().filter { line ->
        val binding = lineBinding(line)?.getOrNull()
        if (binding != null && matches(binding)) false.also { removed += binding.port } else true
    }
    if (removed.isEmpty()) return Unbound(text, emptyList())
    val lines = kept.dropLastWhile { it.isBlank() }
    return Unbound(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"), removed.distinct())
}

// How long `mynx bt` waits for the permission dialog, as `mynx usb allow` does.
private const val BT_ASK_SECONDS = 120
private const val BT_QUICK_SECONDS = 10

/** What Android says a paired device is: Classic (SPP works), LE only, both, or it doesn't know. */
enum class BtType(val word: String) { CLASSIC("classic"), LE("le"), DUAL("dual"), UNKNOWN("unknown") }

/** A device paired in Android's Bluetooth settings; [address] in upper case. */
data class BtDevice(val name: String?, val address: String, val type: BtType)

/**
 * A served Bluetooth port, as the app has it: RFC 2217 on localhost
 * [tcpPort] (null while it has none), [linked] while the RFCOMM link is
 * up (a client has it), [inUse] while a client is connected, [waiting]
 * as for USB ports ([SerialPortInfo.waiting]), and [error], why the last
 * link failed or dropped (null once one works).
 */
data class BtPortState(val tcpPort: Int?, val linked: Boolean, val inUse: Boolean, val waiting: Boolean, val error: String?)

/**
 * What the `bt-*` requests need from the app. Any of it may be called on
 * the thread that answers requests, or on the one [askPermission]
 * answers on.
 */
interface BluetoothHost {
    /** Whether the phone has Bluetooth at all. */
    val available: Boolean
    /** Whether the app may use it: BLUETOOTH_CONNECT granted (Android 12+; always below). */
    val allowed: Boolean
    /** Whether Bluetooth is on; only asked once [allowed]. */
    val enabled: Boolean
    /** The paired devices; only called when [allowed] and [enabled]. */
    fun bonded(): List<BtDevice>
    /** The served ports' states, by port name (`rfcomm0`); a port the app doesn't serve (Bluetooth off) is left out. */
    fun ports(): Map<String, BtPortState>
    /**
     * Asks for the permission with the activity's dialog, without
     * blocking: [done] gets null once granted, else why not, saying how
     * to allow it (the app not on screen, the dialog refused); answered
     * once, within about two minutes.
     */
    fun askPermission(done: (String?) -> Unit)
    /** Opens Android's Bluetooth settings: null, or why it couldn't (the app isn't on screen). */
    fun openSettings(): String?
    /** `bluetooth.conf` was changed by a bind or unbind (already written): serve what it says now. */
    fun bindingsChanged()
}

private class BtRefused(message: String) : Exception(message)

/**
 * The `bt-list`, `bt-bind DEVICE [PORT]`, `bt-unbind PORT|DEVICE` and
 * `bt-settings` requests, for [MynxRequests]'s `later`. All are refused
 * while `android-bluetooth` is off or the phone has no Bluetooth; all
 * but `bt-settings` ask for the permission first when the app hasn't
 * got it (so they wait up to two minutes).
 *
 * Answers: `bt-list` gives `enabled`, `devices` (`name`, `address`,
 * `type`, `port`: the one bound to it or null) and `ports` ([btPortJson]);
 * with Bluetooth off, no devices, and the ports from the config.
 * `bt-bind` gives the `port`, `bt-unbind` the ports `removed`. DEVICE is
 * an address (any case, paired or not) or a paired device's name: exact,
 * else in any case.
 */
fun bluetoothRequests(home: File, host: BluetoothHost): Map<String, Later> {
    val settings = File(home, "$CONFIG_DIR/settings.conf")
    val conf = File(home, "$CONFIG_DIR/$BLUETOOTH_CONF")

    fun refusedFirst(): String? = when {
        !loadSettings(settings).settings.androidBluetooth -> "android-bluetooth is off (mynx set android-bluetooth on)"
        !host.available -> "this phone has no Bluetooth"
        else -> null
    }

    // Answers once the app may use Bluetooth, asking first if it must.
    fun later(seconds: Int, change: ((List<String>) -> String)?, permission: Boolean, answer: (List<String>) -> String) =
        Later(seconds, change) { raw, reply ->
            val args = raw.map { it.trim() }.dropLastWhile { it.isEmpty() }
            fun run() = try {
                reply.answer(answer(args))
            } catch (e: BtRefused) {
                reply.refuse(e.message!!)
            } catch (e: Exception) {
                reply.refuse("Bluetooth failed: ${e.message ?: e.javaClass.simpleName}")
            }
            refusedFirst()?.let { return@Later reply.refuse(it) }
            if (!permission || host.allowed) return@Later run()
            host.askPermission { why -> if (why == null) run() else reply.refuse("Bluetooth isn't allowed: $why") }
        }

    fun bonded() = if (host.enabled) host.bonded() else emptyList()

    fun portJson(binding: BtBinding, devices: List<BtDevice>, served: Map<String, BtPortState>) =
        btPortJson(binding, devices.firstOrNull { it.address == binding.address }?.name, served[binding.port])

    fun list(): String {
        val enabled = host.enabled
        val devices = bonded()
        val bindings = loadBluetooth(conf).bindings
        val served = host.ports()
        return ok(
            "enabled" to enabled.toString(),
            "devices" to array(devices.map { device ->
                obj(
                    "name" to (device.name?.let(::json) ?: "null"),
                    "address" to json(device.address),
                    "type" to json(device.type.word),
                    "port" to (bindings.firstOrNull { it.address == device.address }?.port?.let(::json) ?: "null"),
                )
            }),
            "ports" to array(bindings.map { portJson(it, devices, served) }),
        )
    }

    // The address DEVICE names: as given, or a paired device's.
    fun address(device: String): String {
        btAddress(device)?.let { return it }
        if (!host.enabled) throw BtRefused("Bluetooth is off: turn it on, or give the device's address")
        val devices = host.bonded()
        val matches = devices.filter { it.name == device }.ifEmpty { devices.filter { it.name.equals(device, ignoreCase = true) } }
        return when (matches.size) {
            0 -> throw BtRefused("no paired device $device (pair it in Android's Bluetooth settings: mynx bt settings)")
            1 -> matches.single().address
            else -> throw BtRefused(
                "${matches.size} paired devices are called $device: give its address (" +
                    matches.joinToString(", ") { "${it.address} ${it.name}" } + ")",
            )
        }
    }

    fun bind(args: List<String>): String {
        val device = args.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: throw BtRefused("bt-bind needs a device: its name or address (mynx bt lists them)")
        val port = args.getOrNull(1)?.takeIf { it.isNotEmpty() }
        if (port != null && !isRfcommName(port)) throw BtRefused(badRfcommName(port))
        val address = address(device)
        val text = conf.takeIf { it.isFile }?.readText()
        val bindings = text?.let { parseBluetooth(it).bindings }.orEmpty()
        val devices = bonded()
        bindings.firstOrNull { it.address == address }?.let { bound ->
            if (port != null && port != bound.port) throw BtRefused("$address is already bound to ${bound.port}")
            return ok("port" to portJson(bound, devices, host.ports()))
        }
        // Lines the parser skips count too, so a name isn't given twice.
        val taken = text?.lines().orEmpty().map { it.substringBefore('=').trim() }.filter(::isRfcommName).toSet()
        val binding = BtBinding(port ?: rfcommName(taken), address)
        val written = addBluetoothBinding(text, binding).getOrElse { throw BtRefused(it.message!!) }
        conf.parentFile.mkdirs()
        conf.writeText(written)
        host.bindingsChanged()
        return ok("port" to portJson(binding, devices, host.ports()))
    }

    fun unbind(args: List<String>): String {
        val which = args.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: throw BtRefused("bt-unbind needs a port or a device (mynx bt lists them)")
        val text = conf.takeIf { it.isFile }?.readText().orEmpty()
        val unbound = if (isRfcommName(which)) {
            removeBluetoothBindings(text) { it.port == which }
                .also { if (it.ports.isEmpty()) throw BtRefused("no Bluetooth port $which (mynx bt lists them)") }
        } else {
            val named = bonded().filter { it.name.equals(which, ignoreCase = true) }
            val address = btAddress(which) ?: (named.firstOrNull { it.name == which } ?: named.singleOrNull())?.address
            removeBluetoothBindings(text) { it.address == address }
                .also { if (it.ports.isEmpty()) throw BtRefused("no Bluetooth port is bound to $which (mynx bt lists them)") }
        }
        conf.writeText(unbound.text)
        host.bindingsChanged()
        return ok("removed" to array(unbound.ports.map(::json)))
    }

    fun first(args: List<String>) = args.firstOrNull()?.trim().orEmpty()
    return mapOf(
        "bt-list" to later(BT_ASK_SECONDS, null, permission = true) { list() },
        "bt-bind" to later(BT_ASK_SECONDS, { "bt bind ${first(it)}" }, permission = true, ::bind),
        "bt-unbind" to later(BT_ASK_SECONDS, { "bt unbind ${first(it)}" }, permission = true, ::unbind),
        "bt-settings" to later(BT_QUICK_SECONDS, null, permission = false) {
            host.openSettings()?.let { throw BtRefused(it) }
            ok()
        },
    )
}

/**
 * A bound port as JSON: `name`, `address`, `device` (the paired
 * device's name: null when it isn't paired, has none, or Bluetooth is
 * off), `path` (always), `url` and `tcp_port` (null while the app
 * doesn't serve it, as with Bluetooth off), `linked`, `in_use`,
 * `waiting` and `error` ([BtPortState]; false and null when not served).
 */
fun btPortJson(binding: BtBinding, device: String?, state: BtPortState?): String = obj(
    "name" to json(binding.port),
    "address" to json(binding.address),
    "device" to (device?.let(::json) ?: "null"),
    "path" to json("$SERIAL_MOUNT/${binding.port}"),
    "url" to (state?.tcpPort?.let { json("rfc2217://localhost:$it") } ?: "null"),
    "tcp_port" to (state?.tcpPort?.toString() ?: "null"),
    "linked" to (state?.linked == true).toString(),
    "in_use" to (state?.inUse == true).toString(),
    "waiting" to (state?.waiting == true).toString(),
    "error" to (state?.error?.let(::json) ?: "null"),
)
