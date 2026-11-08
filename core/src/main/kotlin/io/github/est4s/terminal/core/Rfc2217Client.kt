package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream

private const val IAC = 255
private const val DONT = 254
private const val DO = 253
private const val WONT = 252
private const val WILL = 251
private const val SB = 250
private const val SE = 240
private const val COM_PORT = 44
private const val SET_BAUDRATE = 1
private const val SET_DATASIZE = 2
private const val SET_PARITY = 3
private const val SET_STOPSIZE = 4
private const val SET_CONTROL = 5
private const val DTR_ON = 8
private const val DTR_OFF = 9
private const val RTS_ON = 11
private const val RTS_OFF = 12

private val PARITY_VALUES = mapOf(Parity.NONE to 1, Parity.ODD to 2, Parity.EVEN to 3, Parity.MARK to 4, Parity.SPACE to 5)
private val STOP_VALUES = mapOf(StopBits.ONE to 1, StopBits.TWO to 2, StopBits.ONE_POINT_FIVE to 3)

private enum class In { DATA, IAC_SEEN, OPTION, SUB, SUB_IAC }

/**
 * The client side of RFC 2217, as `tools/lib/serial-port` speaks it to
 * the app's [SerialServer]: the bridge's serial ports (part D). [send]
 * writes to the socket; what the socket gives goes to [fromNetwork],
 * which returns the port's data. Not thread-safe: one thread (the
 * bridge's main thread) drives it.
 *
 * The server greets a client it takes at once ([greeted]) and closes one
 * it refuses (the port has another user) without a word; it reads
 * nothing of a client until the port's line is up (a Bluetooth link
 * takes seconds), so the first answer to our requests ([answered]) says
 * the port is open. DTR and RTS change in the safe order
 * (`serial-port`'s `control_changes()`): RTS drops before DTR, DTR rises
 * before RTS; with DTR off and RTS on, an ESP32's auto-reset circuit
 * resets it.
 */
class Rfc2217Client(private val send: (ByteArray) -> Unit) {
    var settings = LineSettings()
        private set
    var dtr = false
        private set
    var rts = false
        private set
    /** Whether the server has said anything: it took us. */
    var greeted = false
        private set
    /** Whether it answered one of our requests: the port's line is up. */
    var answered = false
        private set
    private var mode = In.DATA

    /**
     * Sets the port up: [settings], then DTR and RTS both raised, DTR
     * first; [lower]: both down, DTR first (as the serial monitor's
     * `--lower`, for boards those hold in reset).
     */
    fun open(settings: LineSettings, lower: Boolean) {
        this.settings = settings
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(IAC.toByte(), WILL.toByte(), COM_PORT.toByte()))
        out.write(setup(settings, null))
        val on = !lower
        out.write(control(if (on) DTR_ON else DTR_OFF))
        out.write(control(if (on) RTS_ON else RTS_OFF))
        dtr = on
        rts = on
        send(out.toByteArray())
    }

    /** Sends what differs from the settings sent before. */
    fun configure(next: LineSettings) {
        val bytes = setup(next, settings)
        settings = next
        if (bytes.isNotEmpty()) send(bytes)
    }

    /** Sets DTR and RTS (null: as it is), in the safe order; only what changes is sent. */
    fun setLines(dtr: Boolean?, rts: Boolean?) {
        val wantDtr = dtr ?: this.dtr
        val wantRts = rts ?: this.rts
        val steps = listOf(
            Triple(true, false, RTS_OFF), Triple(false, true, DTR_ON),
            Triple(false, false, DTR_OFF), Triple(true, true, RTS_ON),
        )
        val values = steps.filter { (isRts, on, _) ->
            if (isRts) wantRts == on && this.rts != on else wantDtr == on && this.dtr != on
        }.map { it.third }
        this.dtr = wantDtr
        this.rts = wantRts
        if (values.isNotEmpty()) send(values.fold(ByteArray(0)) { acc, v -> acc + control(v) })
    }

    /** Drops RTS, then DTR, before the socket closes (the server does too, in that order). */
    fun close() {
        send(control(RTS_OFF) + control(DTR_OFF))
        rts = false
        dtr = false
    }

    /** The port's data, IACs doubled. */
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        send(escape(data))
    }

    /** What came from the server, less Telnet's commands and the answers to ours: the port's data. */
    fun fromNetwork(data: ByteArray): ByteArray {
        if (data.isNotEmpty()) greeted = true
        val out = ByteArrayOutputStream(data.size)
        for (byte in data) {
            val b = byte.toInt() and 0xff
            mode = when (mode) {
                In.DATA -> if (b == IAC) In.IAC_SEEN else { out.write(b); In.DATA }
                In.IAC_SEEN -> when (b) {
                    IAC -> { out.write(IAC); In.DATA }
                    SB -> In.SUB
                    DO, DONT, WILL, WONT -> In.OPTION
                    else -> In.DATA
                }
                In.OPTION -> In.DATA
                In.SUB -> if (b == IAC) In.SUB_IAC else In.SUB
                In.SUB_IAC -> when (b) {
                    SE -> { answered = true; In.DATA }
                    else -> In.SUB
                }
            }
        }
        return out.toByteArray()
    }

    private fun setup(next: LineSettings, before: LineSettings?): ByteArray {
        val out = ByteArrayOutputStream()
        if (before?.baud != next.baud) out.write(sub(SET_BAUDRATE, int32(next.baud)))
        if (before?.dataBits != next.dataBits) out.write(sub(SET_DATASIZE, byteArrayOf(next.dataBits.toByte())))
        if (before?.parity != next.parity) out.write(sub(SET_PARITY, byteArrayOf(PARITY_VALUES.getValue(next.parity).toByte())))
        if (before?.stopBits != next.stopBits) out.write(sub(SET_STOPSIZE, byteArrayOf(STOP_VALUES.getValue(next.stopBits).toByte())))
        return out.toByteArray()
    }

    private fun control(value: Int) = sub(SET_CONTROL, byteArrayOf(value.toByte()))

    private fun sub(what: Int, value: ByteArray) =
        byteArrayOf(IAC.toByte(), SB.toByte(), COM_PORT.toByte(), what.toByte()) + escape(value) + byteArrayOf(IAC.toByte(), SE.toByte())

    private fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        for (b in data) {
            out.write(b.toInt())
            if (b.toInt() and 0xff == IAC) out.write(IAC)
        }
        return out.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
