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
