package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

// The Bluetooth base UUID, which 16- and 32-bit UUIDs stand for.
private const val BASE_TAIL = "-0000-1000-8000-00805f9b34fb"
private val FULL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val SHORT_UUID = Regex("(?:0x)?([0-9a-f]{4}|[0-9a-f]{8})")

private fun base(short: String) = short.padStart(8, '0') + BASE_TAIL
private const val NUS = "-b5a3-f393-e0a9-e50e24dcca9e"

/**
 * Names `mynx ble` knows, for services and characteristics alike: the
 * SIG's common ones and Nordic UART. The SIG's own names, in the style
 * of `mynx`'s words.
 */
private val KNOWN_NAMES: Map<String, String> = linkedMapOf(
    base("1800") to "generic-access",
    base("1801") to "generic-attribute",
    base("1805") to "current-time-service",
    base("180a") to "device-information",
    base("180d") to "heart-rate",
    base("180f") to "battery-service",
    base("181a") to "environmental-sensing",
    "6e400001$NUS" to "nordic-uart",
    base("2a00") to "device-name",
    base("2a01") to "appearance",
    base("2a04") to "connection-parameters",
    base("2a05") to "service-changed",
    base("2a19") to "battery-level",
    base("2a23") to "system-id",
    base("2a24") to "model-number",
    base("2a25") to "serial-number",
    base("2a26") to "firmware-revision",
    base("2a27") to "hardware-revision",
    base("2a28") to "software-revision",
    base("2a29") to "manufacturer-name",
    base("2a2b") to "current-time",
    base("2a37") to "heart-rate-measurement",
    base("2a38") to "body-sensor-location",
    base("2a39") to "heart-rate-control-point",
    base("2a50") to "pnp-id",
    base("2a6d") to "pressure",
    base("2a6e") to "temperature",
    base("2a6f") to "humidity",
    "6e400002$NUS" to "nus-rx",
    "6e400003$NUS" to "nus-tx",
)
private val BY_NAME = KNOWN_NAMES.entries.associate { (uuid, name) -> name to uuid }

/** A UUID (full, 16- or 32-bit, `0x` allowed) or a known name, as a full UUID in lower case; null if it's neither. */
fun bleUuid(text: String): String? {
    val t = text.trim().lowercase()
    if (FULL_UUID.matches(t)) return t
    SHORT_UUID.matchEntire(t)?.let { return base(it.groupValues[1]) }
    return BY_NAME[t]
}

/** The known name of a full UUID in lower case, or null. */
fun bleName(uuid: String): String? = KNOWN_NAMES[uuid]

/** How a value's bytes are written for people: GATT numbers are little-endian. */
enum class BleFormat(val word: String, private val size: Int = 0, private val min: Long = 0, private val max: Long = 0) {
    HEX("hex"),
    TEXT("text"),
    U8("u8", 1, 0, 0xff),
    U16("u16", 2, 0, 0xffff),
    U32("u32", 4, 0, 0xffffffffL),
    I8("i8", 1, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()),
    I16("i16", 2, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()),
    I32("i32", 4, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()),
    FLOAT32("float32", 4),
    ;

    /** [bytes] as a JSON value: a string, a number, or null when the size doesn't fit (the hex still says what came). */
    fun json(bytes: ByteArray): String {
        when (this) {
            HEX -> return json(bleHex(bytes))
            TEXT -> return json(String(bytes, Charsets.UTF_8))
            else -> {}
        }
        if (bytes.size != size) return "null"
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (this) {
            U8 -> (buffer.get().toInt() and 0xff).toString()
            I8 -> buffer.get().toString()
            U16 -> (buffer.short.toInt() and 0xffff).toString()
            I16 -> buffer.short.toString()
            U32 -> (buffer.int.toLong() and 0xffffffffL).toString()
            I32 -> buffer.int.toString()
            else -> buffer.float.takeIf { it.isFinite() }?.toString() ?: "null"
        }
    }

    /** [text] as bytes to write, or an IllegalArgumentException saying what would do. */
    fun parse(text: String): ByteArray = when (this) {
        HEX -> {
            val digits = text.filterNot { it.isWhitespace() }
            require(digits.length % 2 == 0 && digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                "'$text' isn't hex: two digits a byte, like 0a1b or 0a 1b"
            }
            ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        TEXT -> text.toByteArray(Charsets.UTF_8)
        FLOAT32 -> {
            val value = text.trim().toFloatOrNull() ?: throw IllegalArgumentException("'$text' isn't a number")
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        }
        else -> {
            val value = text.trim().toLongOrNull()?.takeIf { it in min..max }
                ?: throw IllegalArgumentException("$word is $min to $max, not '$text'")
            ByteArray(size) { (value shr (8 * it)).toByte() }
        }
    }
}

fun bleFormat(word: String): BleFormat? = BleFormat.entries.firstOrNull { it.word == word }

// Known characteristics that read better than hex.
private val DEFAULT_FORMATS: Map<String, BleFormat> = buildMap {
    listOf("battery-level", "body-sensor-location").forEach { put(BY_NAME.getValue(it), BleFormat.U8) }
    listOf(
        "device-name", "model-number", "serial-number", "firmware-revision", "hardware-revision",
        "software-revision", "manufacturer-name", "nus-rx", "nus-tx",
    ).forEach { put(BY_NAME.getValue(it), BleFormat.TEXT) }
    put(BY_NAME.getValue("temperature"), BleFormat.I16)
    put(BY_NAME.getValue("humidity"), BleFormat.U16)
    put(BY_NAME.getValue("pressure"), BleFormat.U32)
}

/** The format a characteristic is read and notified in unless another is asked for. */
fun defaultFormat(uuid: String): BleFormat = DEFAULT_FORMATS[uuid] ?: BleFormat.HEX

/** Bytes as two lower-case hex digits each, spaced. */
fun bleHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
