package io.github.est4s.terminal.core

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** A phone's id on the link: 128 random bits in hex, made once. */
val SCREEN_ID = Regex("[0-9a-f]{32}")

/**
 * A phone this one paired with: its [id] and [name], its [role] (the
 * other phone's: a host we show for, or a screen we send to), the
 * long-term [key] from pairing, where it was last reached ([address],
 * `HOST:PORT`, for a screen to come back), when it paired (ms), and on
 * the host how the desktop's pictures line up across both ([align]: it
 * depends on the pair, so it's kept with it and goes with it).
 */
class ScreenPeer(
    val id: String,
    val name: String,
    val role: ScreenRole,
    val key: ByteArray,
    val address: String?,
    val paired: Long,
    val align: ScreenAlignment = ScreenAlignment(),
)

/**
 * The phones this one paired with, in [dir] (the app's private files,
 * never Debian: the keys must not be readable by programs there): one
 * `peers/ID.conf` each, `key = value` lines; and this phone's own id,
 * in `self`.
 */
class ScreenPairs(private val dir: File, private val rng: SecureRandom = SecureRandom()) {
    private val peers = File(dir, "peers")

    val selfId: String by lazy {
        val file = File(dir, "self")
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { SCREEN_ID.matches(it) }
            ?: ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) }.also { write(file, "$it\n") }
    }

    fun all(): List<ScreenPeer> =
        peers.listFiles { f -> f.name.endsWith(".conf") }.orEmpty()
            .mapNotNull { get(it.name.removeSuffix(".conf")) }
            .sortedBy { it.name.lowercase() }

    fun get(id: String): ScreenPeer? {
        if (!SCREEN_ID.matches(id)) return null
        val lines = runCatching { File(peers, "$id.conf").readLines() }.getOrNull() ?: return null
        val values = lines.filter { '=' in it && !it.trimStart().startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        return runCatching {
            ScreenPeer(
                id, values.getValue("name"),
                ScreenRole.entries.first { it.word == values["role"] },
                Base64.getDecoder().decode(values.getValue("key")).also { require(it.size == 32) },
                values["address"]?.takeIf { it.isNotEmpty() },
                values["paired"]?.toLongOrNull() ?: 0L,
                ScreenAlignment(
                    values["align-offset"]?.toFloatOrNull() ?: 0f,
                    values["align-scale"]?.toFloatOrNull() ?: 1f,
                ).clamped(),
            )
        }.getOrNull()
    }

    /** A pair by its id, else by its name (ignoring case). */
    fun find(nameOrId: String): ScreenPeer? =
        get(nameOrId) ?: all().firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }

    fun save(peer: ScreenPeer) {
        require(SCREEN_ID.matches(peer.id)) { "not a phone's id: ${peer.id}" }
        val text = buildString {
            append("# A phone paired for the extended screen (mynx screen).\n")
            append("name = ${peer.name.replace('\n', ' ')}\n")
            append("role = ${peer.role.word}\n")
            append("key = ${Base64.getEncoder().encodeToString(peer.key)}\n")
            peer.address?.let { append("address = $it\n") }
            append("paired = ${peer.paired}\n")
            if (!peer.align.isDefault) {
                append("align-offset = ${peer.align.offset}\n")
                append("align-scale = ${peer.align.scale}\n")
            }
        }
        write(File(peers, "${peer.id}.conf"), text)
    }

    /** Pair [id]'s alignment is [align] from now on; false if there's no such pair. */
    fun align(id: String, align: ScreenAlignment): Boolean {
        val peer = get(id) ?: return false
        save(ScreenPeer(peer.id, peer.name, peer.role, peer.key, peer.address, peer.paired, align.clamped()))
        return true
    }

    /** Forgets a pair; false if there was none. */
    fun forget(id: String): Boolean = SCREEN_ID.matches(id) && File(peers, "$id.conf").delete()

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) error("couldn't write ${file.path}")
    }
}

/**
 * A code for pairing: six digits, as Bluetooth's passkeys. SPAKE2 gives
 * someone listening nothing to guess at offline, so each try at the
 * code is one guess, and the host takes one try per code.
 */
fun newPairingCode(rng: SecureRandom): String = "%06d".format(rng.nextInt(1_000_000))

/** A typed code without its spaces and dashes; null if it isn't six digits. */
fun pairingCode(typed: String): String? =
    typed.filterNot { it == ' ' || it == '-' }.takeIf { Regex("[0-9]{6}").matches(it) }

fun showPairingCode(code: String) = "${code.take(3)} ${code.drop(3)}"

/** The extended screen's mDNS (Android NSD) service type. */
const val SCREEN_SERVICE_TYPE = "_mynx-screen._tcp"

/** The default TCP port the host listens on (the `screen-port` setting). */
const val SCREEN_PORT = 7391

/** What the host advertises: a service [name] and its TXT record. */
data class ScreenAd(val name: String, val txt: Map<String, String>)

fun screenAd(id: String, name: String, pairing: Boolean): ScreenAd {
    var service = "mynx $name"
    // mDNS labels are at most 63 bytes.
    while (service.toByteArray().size > 63) service = service.dropLast(1)
    return ScreenAd(service, mapOf("v" to "$SCREEN_PROTOCOL", "id" to id, "pairing" to if (pairing) "1" else "0"))
}

/** A host found on the network. */
data class FoundScreenHost(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val pairing: Boolean,
    val version: Int = SCREEN_PROTOCOL,
)

/** A found service as a host; null if it isn't one (no id, no version). */
fun foundScreenHost(serviceName: String, txt: Map<String, String?>, address: String, port: Int): FoundScreenHost? {
    val id = txt["id"]?.takeIf { SCREEN_ID.matches(it) } ?: return null
    val version = txt["v"]?.toIntOrNull() ?: return null
    return FoundScreenHost(id, serviceName.removePrefix("mynx "), address, port, txt["pairing"] == "1", version)
}

/** A typed address, `HOST`, `HOST:PORT` or `[IPv6]:PORT`, as host and port; null if it isn't one. */
fun screenAddress(typed: String, defaultPort: Int): Pair<String, Int>? {
    val text = typed.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' }) return null
    val (host, port) = when {
        text.startsWith("[") -> {
            val end = text.indexOf(']').takeIf { it > 1 } ?: return null
            val rest = text.substring(end + 1)
            text.substring(1, end) to if (rest.isEmpty()) "$defaultPort" else rest.removePrefix(":")
        }
        text.count { it == ':' } == 1 -> text.substringBefore(':') to text.substringAfter(':')
        else -> text to "$defaultPort"
    }
    val number = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return if (host.isEmpty()) null else host to number
}
