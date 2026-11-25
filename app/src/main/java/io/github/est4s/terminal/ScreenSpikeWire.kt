package io.github.est4s.terminal

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

// SPIKE (throwaway, never merged): streaming a mynx screen to a second phone.
// The wire: [type: byte][length: int][payload], big-endian, over one TCP socket.
// Times are System.nanoTime() / 1000 (CLOCK_MONOTONIC, µs) on the phone that sent them.
internal object SpikeWire {
    const val PORT = 7860

    // B → A
    const val HELLO = 1 // w, h, dpi, fps, kbps, iframeSec
    const val PING = 2 // tB
    const val TOUCH = 3 // seq, tB, actionMasked, actionIndex, count, (id, x, y)*
    const val STATS_B = 4 // text
    const val PAGE = 5 // page index: B asks A to show a page

    // A → B
    const val CONFIG = 10 // w, h, csd bytes
    const val FRAME = 11 // ptsA, sentA, flags, touchSeq, touchTB, data
    const val PONG = 12 // tB, tA
    const val STATS_A = 13 // text
    const val LOG = 14 // text

    fun nowUs(): Long = System.nanoTime() / 1000

    class Message(val type: Int, val payload: ByteArray)

    fun read(input: DataInputStream): Message {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        require(length in 0..(16 shl 20)) { "bad message length $length (type $type)" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Message(type, payload)
    }

    fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.block() }
        return bytes.toByteArray()
    }

    /** Writes one message; callers on several threads share [out], so it's locked. */
    fun write(out: OutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            val header = ByteArray(5)
            header[0] = type.toByte()
            header[1] = (payload.size ushr 24).toByte()
            header[2] = (payload.size ushr 16).toByte()
            header[3] = (payload.size ushr 8).toByte()
            header[4] = payload.size.toByte()
            out.write(header)
            out.write(payload)
            out.flush()
        }
    }

    fun text(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** The phone's IPv4 addresses by interface (wlan0, ap0 for a hotspot, …). */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${nif.name}  ${it.hostAddress}:$PORT" }
        }
    }.getOrElse { listOf("(can't list addresses: $it)") }
}

/** A running average and range over the last second's samples. */
internal class SpikeWindow {
    private val samples = ArrayList<Long>()

    @Synchronized
    fun add(value: Long) {
        samples.add(value)
    }

    /** "avg (min-max)" in ms from µs samples, then cleared. */
    @Synchronized
    fun takeMs(): String {
        if (samples.isEmpty()) return "-"
        val avg = samples.average() / 1000.0
        val text = "%.1f (%.1f-%.1f)".format(avg, samples.min() / 1000.0, samples.max() / 1000.0)
        samples.clear()
        return text
    }
}

/** The last lines logged, shown on screen. */
internal class SpikeLog(private val keep: Int = 60) {
    private val lines = ArrayDeque<String>()
    private val start = System.currentTimeMillis()

    @Synchronized
    fun add(line: String) {
        val t = (System.currentTimeMillis() - start) / 100 / 10.0
        lines.addFirst("[$t s] $line")
        while (lines.size > keep) lines.removeLast()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
