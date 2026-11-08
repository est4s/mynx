package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The client against the app's own server session, in memory, as the bridge and the server meet on 127.0.0.1. */
class Rfc2217ClientTest {
    private val line = FakeLine()
    private val received = mutableListOf<Byte>()
    private lateinit var session: Rfc2217Session
    private val client = Rfc2217Client { session.fromNetwork(it) }

    init {
        session = Rfc2217Session(line, LineSettings()) { received += client.fromNetwork(it).toList() }
    }

    private fun data(): ByteArray = received.toByteArray().also { received.clear() }

    @Test
    fun `knows it was greeted, and answered once the line is up`() {
        assertFalse(client.greeted)
        session.greet()
        assertTrue(client.greeted)
        assertFalse(client.answered)
        client.open(LineSettings(baud = 115200), lower = false)
        assertTrue(client.answered)
        assertContentEquals(byteArrayOf(), data(), "negotiation isn't data")
    }

    @Test
    fun `opening sets the line, then raises DTR before RTS`() {
        session.greet()
        client.open(LineSettings(baud = 115200, dataBits = 7, parity = Parity.EVEN, stopBits = StopBits.TWO), lower = false)
        assertEquals(LineSettings(115200, 7, Parity.EVEN, StopBits.TWO), session.settings)
        assertEquals(listOf("dtr true", "rts true"), line.callList().filter { !it.startsWith("configure") })
        assertTrue(client.dtr && client.rts)
    }

    @Test
    fun `lower opens with DTR and RTS down, DTR first`() {
        session.greet()
        client.open(LineSettings(baud = 9600), lower = true)
        assertEquals(listOf("dtr false", "rts false"), line.callList().filter { !it.startsWith("configure") })
        assertFalse(client.dtr || client.rts)
    }

    @Test
    fun `every parity and stop size reaches the server`() {
        session.greet()
        client.open(LineSettings(), lower = false)
        for (parity in Parity.entries) {
            client.configure(client.settings.copy(parity = parity))
            assertEquals(parity, session.settings.parity)
        }
        for (stop in StopBits.entries) {
            client.configure(client.settings.copy(stopBits = stop))
            assertEquals(stop, session.settings.stopBits)
        }
        for (bits in 5..8) {
            client.configure(client.settings.copy(dataBits = bits))
            assertEquals(bits, session.settings.dataBits)
        }
    }

    @Test
    fun `a baud rate with 255 in it is escaped`() {
        session.greet()
        client.open(LineSettings(), lower = false)
        for (baud in listOf(255, 0xff00, 0xffff, 0xff_ffff, 1_000_000, 3_000_000)) {
            client.configure(client.settings.copy(baud = baud))
            assertEquals(baud, session.settings.baud)
        }
    }

    @Test
    fun `configure sends only what changed`() {
        session.greet()
        client.open(LineSettings(baud = 9600), lower = false)
        line.calls.clear()
        client.configure(client.settings.copy(baud = 9600))
        assertEquals(emptyList(), line.callList())
        client.configure(client.settings.copy(baud = 57600))
        assertEquals(listOf("configure ${LineSettings(baud = 57600)}"), line.callList())
    }

    @Test
    fun `lines change RTS down before DTR, and DTR up before RTS`() {
        session.greet()
        client.open(LineSettings(), lower = false)
        line.calls.clear()
        client.setLines(dtr = false, rts = false)
        assertEquals(listOf("rts false", "dtr false"), line.callList())
        line.calls.clear()
        client.setLines(dtr = true, rts = true)
        assertEquals(listOf("dtr true", "rts true"), line.callList())
        line.calls.clear()
        client.setLines(dtr = false, rts = null)
        assertEquals(listOf("dtr false"), line.callList())
        line.calls.clear()
        client.setLines(dtr = false, rts = true)
        assertEquals(emptyList(), line.callList(), "nothing changed")
    }

    @Test
    fun `closing drops RTS before DTR`() {
        session.greet()
        client.open(LineSettings(), lower = false)
        line.calls.clear()
        client.close()
        assertEquals(listOf("rts false", "dtr false"), line.callList())
    }

    @Test
    fun `every byte goes through both ways, IAC escaped`() {
        session.greet()
        client.open(LineSettings(), lower = false)
        val all = ByteArray(256) { it.toByte() }
        client.write(all + all)
        assertContentEquals(all + all, line.writtenBytes())
        session.fromLine(all)
        assertContentEquals(all, data())
    }

    @Test
    fun `an IAC split across reads is still undone`() {
        val ours = Rfc2217Client {}
        ours.fromNetwork(byteArrayOf(1, 0xff.toByte()))
        assertContentEquals(byteArrayOf(0xff.toByte(), 2), ours.fromNetwork(byteArrayOf(0xff.toByte(), 2)))
        // A subnegotiation split, with an escaped IAC in its value.
        assertContentEquals(byteArrayOf(), ours.fromNetwork(byteArrayOf(0xff.toByte(), 250.toByte(), 44, 101, 0xff.toByte())))
        assertFalse(ours.answered)
        assertContentEquals(byteArrayOf(7), ours.fromNetwork(byteArrayOf(0xff.toByte(), 0xff.toByte(), 240.toByte(), 7)))
        assertTrue(ours.answered)
    }

    @Test
    fun `writing nothing sends nothing`() {
        val sent = mutableListOf<ByteArray>()
        val ours = Rfc2217Client { sent += it }
        ours.write(byteArrayOf())
        assertEquals(0, sent.size)
    }
}
