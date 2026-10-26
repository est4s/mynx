package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream

enum class Parity { NONE, ODD, EVEN, MARK, SPACE }

enum class StopBits { ONE, TWO, ONE_POINT_FIVE }

enum class FlowControl { NONE, XON_XOFF, RTS_CTS }

/** How a serial port frames its bytes: 9600 8N1 unless told otherwise, as on Linux. */
data class LineSettings(
    val baud: Int = 9600,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: StopBits = StopBits.ONE,
)

/**
 * A serial port, as the RFC 2217 server drives it. Any call may throw
 * (the device was unplugged); the server then drops the connection.
 */
interface SerialLine {
    fun write(data: ByteArray)
    fun configure(settings: LineSettings)
    fun setDtr(on: Boolean)
    fun setRts(on: Boolean)
    fun setBreak(on: Boolean)
    /** False when the port can't do [flow]. */
    fun setFlowControl(flow: FlowControl): Boolean
    fun purge(input: Boolean, output: Boolean)
    /** CTS, DSR, RI and CD as RFC 2217's modem-state bits (0x10, 0x20, 0x40, 0x80); 0 when it can't tell. */
    fun modemState(): Int
    /**
     * A client has the port ([SerialServer], after its greeting, before
     * any of its data): a line that links on demand (Bluetooth) opens
     * the link here, and throws an IOException when it can't, which
     * closes that client. USB ports are always open.
     */
    fun clientArrived() {}
    /** The client has gone, after its RTS and DTR were dropped: an on-demand link closes. */
    fun clientLeft() {}
}

private const val IAC = 255
private const val DONT = 254
private const val DO = 253
private const val WONT = 252
private const val WILL = 251
private const val SB = 250
private const val SE = 240
private const val BINARY = 0
private const val SGA = 3
private const val COM_PORT = 44

// Client-to-server COM-PORT-OPTION commands; the server answers with each plus 100.
private const val SET_BAUDRATE = 1
private const val SET_DATASIZE = 2
private const val SET_PARITY = 3
private const val SET_STOPSIZE = 4
private const val SET_CONTROL = 5
private const val NOTIFY_LINESTATE = 6
private const val NOTIFY_MODEMSTATE = 7
private const val SET_LINESTATE_MASK = 10
private const val SET_MODEMSTATE_MASK = 11
private const val PURGE_DATA = 12
private const val SERVER = 100

private val PARITIES = mapOf(1 to Parity.NONE, 2 to Parity.ODD, 3 to Parity.EVEN, 4 to Parity.MARK, 5 to Parity.SPACE)
private val STOP_BITS = mapOf(1 to StopBits.ONE, 2 to StopBits.TWO, 3 to StopBits.ONE_POINT_FIVE)
private val FLOWS = mapOf(1 to FlowControl.NONE, 2 to FlowControl.XON_XOFF, 3 to FlowControl.RTS_CTS)
private val OUR_OPTIONS = setOf(BINARY, SGA, COM_PORT)

private enum class Mode { DATA, IAC_SEEN, OPTION, SUB, SUB_IAC }

/**
 * One RFC 2217 (Telnet COM port control) connection: the protocol
 * pyserial's `rfc2217://` speaks, so esptool and PlatformIO can set the
 * baud rate and toggle DTR and RTS to reset a board. Bytes from the
 * network go to [fromNetwork]; the port's data to [fromLine]; [send]
 * writes to the network. Not thread-safe: the server serialises calls.
 */
class Rfc2217Session(
    private val line: SerialLine,
    initial: LineSettings,
    private val send: (ByteArray) -> Unit,
) {
    var settings: LineSettings = initial
        private set
    private var flow = FlowControl.NONE
    private var dtr = false
    private var rts = false
    private var mode = Mode.DATA
    private var command = 0
    private val sub = ByteArrayOutputStream()
    private val ours = mutableSetOf<Int>()
    private val theirs = mutableSetOf<Int>()

    /** Our side of the option negotiation, sent first. */
    fun greet() {
        ours += SGA
        theirs += BINARY
        ours += COM_PORT
        send(bytes(IAC, WILL, SGA, IAC, DO, BINARY, IAC, WILL, COM_PORT))
    }

    fun fromNetwork(data: ByteArray, length: Int = data.size) {
        val out = ByteArrayOutputStream()
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xff
            when (mode) {
                Mode.DATA -> if (b == IAC) mode = Mode.IAC_SEEN else out.write(b)
                Mode.IAC_SEEN -> mode = when (b) {
                    IAC -> { out.write(IAC); Mode.DATA }
                    SB -> { sub.reset(); Mode.SUB }
                    DO, DONT, WILL, WONT -> { command = b; Mode.OPTION }
                    else -> Mode.DATA
                }
                Mode.OPTION -> {
                    // Data before the answer reaches the port first, in order.
                    flush(out)
                    negotiate(command, b)
                    mode = Mode.DATA
                }
                Mode.SUB -> if (b == IAC) mode = Mode.SUB_IAC else sub.write(b)
                Mode.SUB_IAC -> mode = when (b) {
                    IAC -> { sub.write(IAC); Mode.SUB }
                    SE -> {
                        flush(out)
                        subnegotiation(sub.toByteArray())
                        Mode.DATA
                    }
                    else -> Mode.SUB
                }
            }
        }
        flush(out)
    }

    fun fromLine(data: ByteArray, length: Int = data.size) {
        if (length == 0) return
        val out = ByteArrayOutputStream(length + 8)
        for (i in 0 until length) {
            out.write(data[i].toInt())
            if (data[i].toInt() and 0xff == IAC) out.write(IAC)
        }
        send(out.toByteArray())
    }

    private fun flush(out: ByteArrayOutputStream) {
        if (out.size() == 0) return
        line.write(out.toByteArray())
        out.reset()
    }

    private fun negotiate(command: Int, option: Int) {
        val known = option in OUR_OPTIONS
        when (command) {
            DO -> if (!known) reply(WONT, option) else if (ours.add(option)) reply(WILL, option)
            WILL -> if (!known) reply(DONT, option) else if (theirs.add(option)) reply(DO, option)
            DONT -> if (ours.remove(option)) reply(WONT, option)
            WONT -> if (theirs.remove(option)) reply(DONT, option)
        }
    }

    private fun reply(command: Int, option: Int) = send(bytes(IAC, command, option))

    private fun subnegotiation(body: ByteArray) {
        if (body.size < 2 || body[0].toInt() != COM_PORT) return
        val what = body[1].toInt() and 0xff
        val value = body.copyOfRange(2, body.size)
        when (what) {
            SET_BAUDRATE -> {
                if (value.size >= 4) {
                    val baud = value.take(4).fold(0L) { acc, b -> acc shl 8 or (b.toLong() and 0xff) }
                    if (baud in 1..Int.MAX_VALUE) apply(settings.copy(baud = baud.toInt()))
                }
                answer(what, int32(settings.baud))
            }
            SET_DATASIZE -> {
                value.firstOrNull()?.toInt()?.takeIf { it in 5..8 }?.let { apply(settings.copy(dataBits = it)) }
                answer(what, byteArrayOf(settings.dataBits.toByte()))
            }
            SET_PARITY -> {
                value.firstOrNull()?.let { PARITIES[it.toInt()] }?.let { apply(settings.copy(parity = it)) }
                answer(what, byteArrayOf(PARITIES.entries.first { it.value == settings.parity }.key.toByte()))
            }
            SET_STOPSIZE -> {
                value.firstOrNull()?.let { STOP_BITS[it.toInt()] }?.let { apply(settings.copy(stopBits = it)) }
                answer(what, byteArrayOf(STOP_BITS.entries.first { it.value == settings.stopBits }.key.toByte()))
            }
            SET_CONTROL -> value.firstOrNull()?.let { control(it.toInt() and 0xff) }
            NOTIFY_LINESTATE -> answer(what, byteArrayOf(0))
            NOTIFY_MODEMSTATE -> answer(what, byteArrayOf(line.modemState().toByte()))
            SET_LINESTATE_MASK, SET_MODEMSTATE_MASK -> answer(what, value.take(1).toByteArray())
            PURGE_DATA -> value.firstOrNull()?.toInt()?.takeIf { it in 1..3 }?.let {
                line.purge(input = it and 1 != 0, output = it and 2 != 0)
                answer(what, byteArrayOf(it.toByte()))
            }
        }
    }

    // Values of SET-CONTROL: 0 and 1-3 outbound flow control, 4-6 break, 7-9 DTR, 10-12 RTS.
    private fun control(value: Int) {
        when (value) {
            0 -> Unit
            in 1..3 -> {
                val wanted = FLOWS.getValue(value)
                if (line.setFlowControl(wanted)) flow = wanted
            }
            5 -> line.setBreak(true)
            6 -> line.setBreak(false)
            7 -> return answer(SET_CONTROL, byteArrayOf(if (dtr) 8 else 9))
            8, 9 -> { dtr = value == 8; line.setDtr(dtr) }
            10 -> return answer(SET_CONTROL, byteArrayOf(if (rts) 11 else 12))
            11, 12 -> { rts = value == 11; line.setRts(rts) }
            else -> return
        }
        val said = if (value in 0..3) FLOWS.entries.first { it.value == flow }.key else value
        answer(SET_CONTROL, byteArrayOf(said.toByte()))
    }

    private fun apply(next: LineSettings) {
        if (next == settings) return
        line.configure(next)
        settings = next
    }

    private fun answer(what: Int, value: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(IAC.toByte(), SB.toByte(), COM_PORT.toByte(), (what + SERVER).toByte()))
        for (b in value) {
            out.write(b.toInt())
            if (b.toInt() and 0xff == IAC) out.write(IAC)
        }
        out.write(IAC)
        out.write(SE)
        send(out.toByteArray())
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
