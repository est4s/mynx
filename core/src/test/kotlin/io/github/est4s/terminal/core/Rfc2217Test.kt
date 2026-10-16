package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A serial port that records what the server asks of it. */
class FakeLine(var modem: Int = 0, var flows: Set<FlowControl> = FlowControl.entries.toSet()) : SerialLine {
    val written: MutableList<Byte> = java.util.Collections.synchronizedList(mutableListOf())
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    fun writtenBytes() = synchronized(written) { written.toByteArray() }
    fun callList() = synchronized(calls) { calls.toList() }
    override fun write(data: ByteArray) { written += data.toList() }
    override fun configure(settings: LineSettings) { calls += "configure $settings" }
    override fun setDtr(on: Boolean) { calls += "dtr $on" }
    override fun setRts(on: Boolean) { calls += "rts $on" }
    override fun setBreak(on: Boolean) { calls += "break $on" }
    override fun setFlowControl(flow: FlowControl): Boolean = (flow in flows).also { if (it) calls += "flow $flow" }
    override fun purge(input: Boolean, output: Boolean) { calls += "purge $input $output" }
    override fun modemState(): Int = modem
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private const val IAC = 255
private const val SB = 250
private const val SE = 240
private const val WILL = 251
private const val WONT = 252
private const val DO = 253
private const val DONT = 254
private const val COM_PORT = 44

private fun sub(vararg values: Int) = bytes(IAC, SB, COM_PORT, *values, IAC, SE)

class Rfc2217Test {
    private val line = FakeLine()
    private val sent = mutableListOf<Byte>()
    private val session = Rfc2217Session(line, LineSettings()) { sent += it.toList() }

    private fun receive(vararg values: Int) = session.fromNetwork(bytes(*values))
    private fun receive(data: ByteArray) = session.fromNetwork(data)
    private fun sent(): ByteArray = sent.toByteArray().also { sent.clear() }

    @Test
    fun `greets the way pyserial's server does, without offering to echo`() {
        session.greet()
        assertContentEquals(bytes(IAC, WILL, 3, IAC, DO, 0, IAC, WILL, COM_PORT), sent())
        receive(IAC, DO, 3, IAC, WILL, 0, IAC, DO, COM_PORT)
        assertContentEquals(bytes(), sent(), "answers to what it asked for")
    }

    @Test
    fun `an option turned off is acknowledged`() {
        session.greet()
        sent()
        receive(IAC, DONT, COM_PORT, IAC, WONT, 0)
        assertContentEquals(bytes(IAC, WONT, COM_PORT, IAC, DONT, 0), sent())
        receive(IAC, DONT, COM_PORT)
        assertContentEquals(bytes(), sent())
    }

    @Test
    fun `agrees to the options it knows, once, and refuses the rest`() {
        receive(IAC, WILL, COM_PORT)
        assertContentEquals(bytes(IAC, DO, COM_PORT), sent())
        receive(IAC, DO, 0, IAC, WILL, 0, IAC, DO, 3)
        assertContentEquals(bytes(IAC, WILL, 0, IAC, DO, 0, IAC, WILL, 3), sent())
        receive(IAC, WILL, COM_PORT, IAC, DO, 0)
        assertContentEquals(bytes(), sent(), "already agreed")
        receive(IAC, WILL, 99, IAC, DO, 24)
        assertContentEquals(bytes(IAC, DONT, 99, IAC, WONT, 24), sent())
    }

    @Test
    fun `a client asking us to echo is told no`() {
        session.greet()
        sent()
        receive(IAC, DO, 1)
        assertContentEquals(bytes(IAC, WONT, 1), sent())
    }

    @Test
    fun `sets the baud rate and says so`() {
        receive(sub(1, 0, 1, 0xC2, 0))
        assertEquals(listOf("configure ${LineSettings(baud = 115200)}"), line.calls)
        assertContentEquals(sub(101, 0, 1, 0xC2, 0), sent())
        assertEquals(115200, session.settings.baud)
    }

    @Test
    fun `a baud rate of 0 asks for the current one`() {
        receive(sub(1, 0, 0, 0, 0))
        assertEquals(emptyList(), line.calls)
        assertContentEquals(sub(101, 0, 0, 0x25, 0x80), sent())
    }

    @Test
    fun `an IAC inside a value is doubled both ways`() {
        receive(sub(1, 0, 0, 0, IAC, IAC))
        assertEquals(255, session.settings.baud)
        assertContentEquals(sub(101, 0, 0, 0, IAC, IAC), sent())
    }

    @Test
    fun `sets data bits, parity and stop bits`() {
        receive(sub(2, 7))
        receive(sub(3, 3))
        receive(sub(4, 2))
        assertEquals(LineSettings(dataBits = 7, parity = Parity.EVEN, stopBits = StopBits.TWO), session.settings)
        assertContentEquals(sub(102, 7) + sub(103, 3) + sub(104, 2), sent())
        assertEquals(3, line.calls.size)
    }

    @Test
    fun `a value it can't use is answered with the current one`() {
        receive(sub(2, 9))
        receive(sub(3, 7))
        assertContentEquals(sub(102, 8) + sub(103, 1), sent())
        assertEquals(emptyList(), line.calls)
    }

    @Test
    fun `DTR, RTS and break go to the port`() {
        receive(sub(5, 8))
        receive(sub(5, 9))
        receive(sub(5, 11))
        receive(sub(5, 12))
        receive(sub(5, 5))
        receive(sub(5, 6))
        assertEquals(listOf("dtr true", "dtr false", "rts true", "rts false", "break true", "break false"), line.calls)
        assertContentEquals(sub(105, 8) + sub(105, 9) + sub(105, 11) + sub(105, 12) + sub(105, 5) + sub(105, 6), sent())
    }

    @Test
    fun `asking for DTR or RTS gets their state`() {
        receive(sub(5, 7))
        receive(sub(5, 10))
        assertContentEquals(sub(105, 9) + sub(105, 12), sent())
        receive(sub(5, 8) + sub(5, 11))
        sent()
        receive(sub(5, 7))
        receive(sub(5, 10))
        assertContentEquals(sub(105, 8) + sub(105, 11), sent())
    }

    @Test
    fun `flow control the port can't do is answered with the one in use`() {
        line.flows = setOf(FlowControl.NONE)
        receive(sub(5, 3))
        assertContentEquals(sub(105, 1), sent())
        receive(sub(5, 1))
        assertContentEquals(sub(105, 1), sent())
        assertEquals(listOf("flow NONE"), line.calls)
        line.flows = FlowControl.entries.toSet()
        receive(sub(5, 3))
        assertContentEquals(sub(105, 3), sent())
        receive(sub(5, 0))
        assertContentEquals(sub(105, 3), sent())
    }

    @Test
    fun `purges and answers`() {
        receive(sub(12, 3))
        assertEquals(listOf("purge true true"), line.calls)
        assertContentEquals(sub(112, 3), sent())
    }

    @Test
    fun `reports the modem lines when asked, and the line state as clear`() {
        line.modem = 0x30
        receive(sub(7))
        assertContentEquals(sub(107, 0x30), sent())
        receive(sub(6))
        assertContentEquals(sub(106, 0), sent())
    }

    @Test
    fun `mask and flow control requests are acknowledged`() {
        receive(sub(11, IAC, IAC))
        receive(sub(10, 0))
        assertContentEquals(sub(111, IAC, IAC) + sub(110, 0), sent())
    }

    @Test
    fun `data goes to the port with doubled IACs undone, also when split`() {
        receive(1, 2, IAC)
        receive(IAC, 3)
        assertEquals(listOf<Byte>(1, 2, -1, 3), line.written)
    }

    @Test
    fun `a subnegotiation split over reads still works`() {
        receive(IAC, SB, COM_PORT, 1, 0)
        receive(1, 0xC2, 0, IAC)
        receive(SE, 7)
        assertEquals(115200, session.settings.baud)
        assertEquals(listOf<Byte>(7), line.written)
    }

    @Test
    fun `other telnet commands are skipped`() {
        receive(5, IAC, 241, 6)
        assertEquals(listOf<Byte>(5, 6), line.written)
    }

    @Test
    fun `data from the port has its IACs doubled`() {
        session.fromLine(bytes(1, IAC, 2), 3)
        assertContentEquals(bytes(1, IAC, IAC, 2), sent())
        session.fromLine(bytes(9, 9, 9), 1)
        assertContentEquals(bytes(9), sent())
    }

    @Test
    fun `starts from the settings it is given`() {
        val s = Rfc2217Session(line, LineSettings(baud = 57600)) {}
        assertEquals(57600, s.settings.baud)
        assertTrue(line.calls.isEmpty())
    }
}
