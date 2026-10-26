package io.github.est4s.terminal.core

import java.io.IOException
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BleUartLineTest {
    private val host = FakeBleHost()
    private var clock = 1_000_000L
    private val line = BleUartLine(host, address, randomAddress = true, now = { clock })
    private val got = Collections.synchronizedList(mutableListOf<String>())
    private var drops = 0
    private val threads = mutableListOf<Thread>()

    init {
        line.received = { got += String(it) }
        line.dropped = { drops++ }
    }

    @AfterTest
    fun cleanup() {
        threads.forEach { it.join(5000) }
    }

    private fun nordic(rx: Set<BleProp> = setOf(BleProp.WRITE, BleProp.WRITE_NO_RESPONSE)) = BleService(
        NUS,
        listOf(BleChar(NUS, RX, 1, rx, emptyList()), BleChar(NUS, TX, 2, setOf(BleProp.NOTIFY), emptyList())),
    )

    private fun link(mtu: Int = 23, services: List<BleService> = listOf(nordic())) = FakeBleLink(address, services, mtu)

    // Waits (briefly) for [ok]: the line works on a thread of its own.
    private fun eventually(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            if (runCatching(ok).getOrDefault(false)) return
            Thread.sleep(5)
        }
        fail("never: $what")
    }

    /** Runs [block] on a thread of its own, as the server's client thread; its outcome comes back when asked. */
    private fun background(block: () -> Unit): () -> Result<Unit> {
        var result: Result<Unit>? = null
        val t = thread { result = runCatching(block) }
        threads += t
        return {
            t.join(5000)
            result ?: fail("still running")
        }
    }

    private fun pending(link: FakeBleLink) = synchronized(link) { link.pending.map { it.first } }

    // Brings the link up: connects, hands [link] over, answers the subscription.
    private fun linkUp(link: FakeBleLink = link()): FakeBleLink {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        host.connects.last().ready(Result.success(link))
        eventually("a subscription") { "subscribe $TX" in pending(link) }
        link.subscription(TX).second(Result.success(Unit))
        arrived().getOrThrow()
        return link
    }

    private fun refusedWith(message: String) {
        val e = runCatching { line.clientArrived() }.exceptionOrNull()
        assertTrue(e is IOException, "expected an IOException, got $e")
        assertEquals(message, e.message)
    }

    @Test
    fun `a client links, connecting as the scan saw it, finds the UART and subscribes before going on`() {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        assertEquals(address, host.connects.single().address)
        assertTrue(host.connects.single().randomAddress)
        val link = link()
        host.connects.single().ready(Result.success(link))
        eventually("a subscription") { "subscribe $TX" in pending(link) }
        assertFalse(line.linked)
        link.subscription(TX).second(Result.success(Unit))
        arrived().getOrThrow()
        assertTrue(line.linked)
        assertNull(line.error)
        assertEquals(0x10 or 0x20 or 0x80, line.modemState())
    }

    @Test
    fun `the device's values go to received, in order`() {
        val link = linkUp()
        val values = link.subscription(TX).first
        values("hel".toByteArray(), 1)
        values("lo\n".toByteArray(), 2)
        assertEquals(listOf("hel", "lo\n"), got.toList())
    }

    @Test
    fun `writes go in chunks of the MTU less 3, one after another`() {
        val link = linkUp(link(mtu = 23))
        val data = ByteArray(45) { it.toByte() }
        val written = background { line.write(data) }
        fun hex(from: Int, to: Int) = (from until to).joinToString(" ") { "%02x".format(it) }
        eventually("the first chunk") { "write $RX ${hex(0, 20)} false" in pending(link) }
        Thread.sleep(50)
        assertEquals(listOf("write $RX ${hex(0, 20)} false"), pending(link).filter { it.startsWith("write") })
        link.answer("write $RX ${hex(0, 20)} false", Result.success(Unit))
        eventually("the second chunk") { "write $RX ${hex(20, 40)} false" in pending(link) }
        link.answer("write $RX ${hex(20, 40)} false", Result.success(Unit))
        eventually("the last chunk") { "write $RX ${hex(40, 45)} false" in pending(link) }
        link.answer("write $RX ${hex(40, 45)} false", Result.success(Unit))
        written().getOrThrow()
        assertTrue(line.linked)
    }

    @Test
    fun `an rx without write-no-response is written with a response`() {
        val link = linkUp(link(services = listOf(nordic(rx = setOf(BleProp.WRITE)))))
        val written = background { line.write("hi".toByteArray()) }
        eventually("the write") { "write $RX 68 69 true" in pending(link) }
        link.answer("write $RX 68 69 true", Result.success(Unit))
        written().getOrThrow()
    }

    @Test
    fun `an HM-10 module is written and heard on its one characteristic`() {
        val ffe1 = BleUartKind.HM10.rx
        val hm10 = BleService(
            BleUartKind.HM10.service,
            listOf(BleChar(BleUartKind.HM10.service, ffe1, 3, setOf(BleProp.WRITE_NO_RESPONSE, BleProp.NOTIFY), emptyList())),
        )
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        val link = link(services = listOf(hm10))
        host.connects.single().ready(Result.success(link))
        eventually("a subscription") { "subscribe $ffe1" in pending(link) }
        link.subscription(ffe1).second(Result.success(Unit))
        arrived().getOrThrow()
        val written = background { line.write("x".toByteArray()) }
        eventually("the write") { "write $ffe1 78 false" in pending(link) }
        link.answer("write $ffe1 78 false", Result.success(Unit))
        written().getOrThrow()
    }

    @Test
    fun `a failed write drops the link, once`() {
        val link = linkUp()
        val written = background { line.write("hi".toByteArray()) }
        eventually("the write") { "write $RX 68 69 false" in pending(link) }
        link.answer("write $RX 68 69 false", Result.failure(IOException("link lost")))
        written().getOrThrow()
        assertFalse(line.linked)
        assertTrue(link.closed)
        assertEquals(1, drops)
        assertEquals("couldn't write to $address: link lost", line.error)
        assertEquals(0, line.modemState())
        // Nothing more is written on it.
        line.write("more".toByteArray())
        assertEquals(emptyList(), pending(link).filter { it.startsWith("write") })
    }

    @Test
    fun `a link that drops closes, says why and ends the client once`() {
        val link = linkUp()
        host.connects.single().dropped("link lost (status 8)")
        host.connects.single().dropped("link lost (status 8)")
        assertFalse(line.linked)
        assertTrue(link.closed)
        assertEquals(1, drops)
        assertEquals("the link dropped: link lost (status 8)", line.error)
        link.subscription(TX).first("late".toByteArray(), 3)
        assertEquals(emptyList(), got.toList())
    }

    @Test
    fun `a write waiting when the link drops gives up`() {
        val link = linkUp()
        val written = background { line.write("hi".toByteArray()) }
        eventually("the write") { "write $RX 68 69 false" in pending(link) }
        host.connects.single().dropped("link lost")
        written().getOrThrow()
        assertEquals(1, drops)
    }

    @Test
    fun `the client leaving unsubscribes and closes the link`() {
        val link = linkUp()
        line.clientLeft()
        assertEquals(listOf("unsubscribe $TX"), pending(link).filter { it.startsWith("unsubscribe") })
        assertTrue(link.closed)
        assertFalse(line.linked)
        assertEquals(0, drops)
        assertNull(line.error)
        host.connects.single().dropped("closed")
        assertEquals(0, drops)
    }

    @Test
    fun `a link already up is used again`() {
        linkUp()
        line.clientArrived()
        assertEquals(1, host.connects.size)
    }

    @Test
    fun `a failed link is refused with why, at once for 2 s, doubling up to 30 s`() {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        host.connects.single().ready(Result.failure(IOException("status 133")))
        assertEquals("couldn't link to $address: status 133", arrived().exceptionOrNull()?.message)
        assertEquals("couldn't link to $address: status 133", line.error)
        clock += 1999
        refusedWith("couldn't link to $address: status 133")
        assertEquals(1, host.connects.size)
        var wait = 2000L
        for (expected in listOf(4000L, 8000L, 16000L, 30000L, 30000L)) {
            clock += wait
            val again = background(line::clientArrived)
            eventually("another connect") { host.connects.size > 1 }
            host.connects.removeAt(0)
            host.connects.single().ready(Result.failure(IOException("status 133")))
            assertTrue(again().isFailure)
            clock += expected - 1
            refusedWith("couldn't link to $address: status 133")
            clock += 1
            wait = 0
        }
    }

    @Test
    fun `a link that works resets the wait`() {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        host.connects.removeAt(0).ready(Result.failure(IOException("status 133")))
        assertTrue(arrived().isFailure)
        clock += 2000
        linkUp()
        assertNull(line.error)
        host.connects.single().dropped("link lost")
        clock += 1999
        refusedWith("the link dropped: link lost")
        clock += 1
        host.connects.clear()
        linkUp()
    }

    @Test
    fun `Bluetooth off, or not allowed, is refused without connecting`() {
        host.enabled = false
        refusedWith("Bluetooth is off")
        clock += 2000
        host.enabled = true
        host.allowed = false
        refusedWith("Bluetooth isn't allowed any more (mynx ble asks again)")
        assertEquals(0, host.connects.size)
    }

    @Test
    fun `a device with no UART is refused, and the link closed`() {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        val link = link(services = emptyList())
        host.connects.single().ready(Result.success(link))
        assertEquals("$address has no Nordic UART or HM-10 service", arrived().exceptionOrNull()?.message)
        assertTrue(link.closed)
        assertFalse(line.linked)
    }

    @Test
    fun `a failed subscription is refused, and the link closed`() {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        val link = link()
        host.connects.single().ready(Result.success(link))
        eventually("a subscription") { "subscribe $TX" in pending(link) }
        link.subscription(TX).second(Result.failure(IOException("not permitted")))
        assertEquals("couldn't subscribe to $address's UART: not permitted", arrived().exceptionOrNull()?.message)
        assertTrue(link.closed)
    }

    @Test
    fun `a client that leaves while linking stops it, and a link that comes after is closed`() {
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        line.clientLeft()
        assertTrue(arrived().isFailure)
        val link = link()
        host.connects.single().ready(Result.success(link))
        assertTrue(link.closed)
        assertFalse(line.linked)
        // Leaving isn't a failure: the next client links at once.
        assertNull(line.error)
        val again = background(line::clientArrived)
        eventually("another connect") { host.connects.size == 2 }
        host.connects.last().ready(Result.failure(IOException("x")))
        assertTrue(again().isFailure)
    }

    @Test
    fun `a host that never answers is given up on, and a late link closed`() {
        line.connectWaitMs = 50
        val arrived = background(line::clientArrived)
        assertEquals("couldn't link to $address: timed out", arrived().exceptionOrNull()?.message)
        val link = link()
        host.connects.single().ready(Result.success(link))
        assertTrue(link.closed)
    }

    @Test
    fun `a subscription never answered is given up on`() {
        line.opWaitMs = 50
        val arrived = background(line::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        val link = link()
        host.connects.single().ready(Result.success(link))
        assertEquals("couldn't subscribe to $address's UART: timed out", arrived().exceptionOrNull()?.message)
        assertTrue(link.closed)
    }

    @Test
    fun `a write never answered drops the link`() {
        line.opWaitMs = 50
        val link = linkUp()
        line.write("hi".toByteArray())
        assertEquals(1, drops)
        assertEquals("couldn't write to $address: timed out", line.error)
        assertTrue(link.closed)
    }

    @Test
    fun `a write with no link does nothing`() {
        line.write("hi".toByteArray())
        assertEquals(0, drops)
    }

    @Test
    fun `line settings are taken and change nothing`() {
        line.configure(LineSettings())
        line.setDtr(true)
        line.setRts(false)
        line.setBreak(true)
        line.purge(input = true, output = true)
        assertTrue(line.setFlowControl(FlowControl.NONE))
        assertFalse(line.setFlowControl(FlowControl.RTS_CTS))
        assertEquals(0, line.modemState())
    }

    @Test
    fun `messages name the device when the bridge gives its name`() {
        val named = BleUartLine(host, address, randomAddress = false, now = { clock }, name = "mynx-ble")
        val arrived = background(named::clientArrived)
        eventually("a connect") { host.connects.size == 1 }
        host.connects.single().ready(Result.success(link(services = emptyList())))
        assertEquals("mynx-ble has no Nordic UART or HM-10 service", arrived().exceptionOrNull()?.message)
    }

    private companion object {
        const val address = "D4:E9:F4:A2:4A:06"
        const val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    }
}
