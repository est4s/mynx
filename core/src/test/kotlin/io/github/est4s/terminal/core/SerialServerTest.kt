package io.github.est4s.terminal.core

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerialServerTest {
    private val line = FakeLine()
    private val server = SerialServer(line, port = 0).also { it.start() }

    @AfterTest
    fun close() = server.close()

    private fun connect() = Socket("127.0.0.1", server.port).apply { soTimeout = 3000 }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun Socket.readBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) got += getInputStream().read(buf, got, n - got).also { check(it > 0) }
        return buf
    }

    @Test
    fun `listens on IPv4 localhost only`() {
        // The Debian side connects to 127.0.0.1; Android's loopback address is ::1.
        assertTrue(server.port > 0)
        assertEquals(InetAddress.getByName("127.0.0.1"), server.address)
    }

    @Test
    fun `greets a client, then passes data both ways`() {
        connect().use { s ->
            assertEquals(9, s.readBytes(9).size, "the greeting")
            waitFor("the client") { server.busy }
            s.getOutputStream().write(byteArrayOf(1, 2, 3))
            waitFor("the data") { line.writtenBytes().size == 3 }
            server.fromLine(byteArrayOf(7, 8, -1), 3)
            assertContentEquals(byteArrayOf(7, 8, -1, -1), s.readBytes(4))
        }
    }

    @Test
    fun `refuses a second client while one is connected`() {
        connect().use { first ->
            first.readBytes(9)
            waitFor("the first client") { server.busy }
            connect().use { second -> assertEquals(-1, second.getInputStream().read()) }
            assertTrue(server.busy)
        }
    }

    @Test
    fun `a client leaving drops RTS and then DTR, as closing a port does on Linux, and frees it`() {
        connect().use { it.readBytes(9) }
        waitFor("the drop") { !server.busy }
        // DTR first would pass through RTS alone, which an ESP32's auto-reset
        // (and a C3's USB-Serial-JTAG) take as a reset into the bootloader.
        assertEquals(listOf("rts false", "dtr false"), line.callList())
        connect().use { assertEquals(9, it.readBytes(9).size) }
    }

    @Test
    fun `the port stays taken until a leaving client's lines are down`() {
        // Otherwise a new client could connect and have its lines dropped by the old one.
        val dtrDropped = java.util.concurrent.CountDownLatch(1)
        val slow = FakeLine()
        val slowLine = object : SerialLine by slow {
            override fun setDtr(on: Boolean) { dtrDropped.await(3, TimeUnit.SECONDS); slow.setDtr(on) }
        }
        val server = SerialServer(slowLine, port = 0).also { it.start() }
        try {
            Socket("127.0.0.1", server.port).use { it.soTimeout = 3000; it.readBytes(9) }
            waitFor("RTS down") { "rts false" in slow.callList() }
            assertTrue(server.busy)
            dtrDropped.countDown()
            waitFor("the drop") { !server.busy }
            assertEquals(listOf("rts false", "dtr false"), slow.callList())
        } finally {
            server.close()
        }
    }

    @Test
    fun `settings outlast a client`() {
        connect().use { s ->
            s.readBytes(9)
            s.getOutputStream().write(byteArrayOf(-1, -6, 44, 1, 0, 1, -62, 0, -1, -16))
            s.readBytes(10)
        }
        waitFor("the drop") { !server.busy }
        assertEquals(115200, server.settings.baud)
    }

    @Test
    fun `data from the port with no client is dropped`() {
        server.fromLine(byteArrayOf(1, 2), 2)
        connect().use { s ->
            s.readBytes(9)
            waitFor("the client") { server.busy }
            server.fromLine(byteArrayOf(3), 1)
            assertContentEquals(byteArrayOf(3), s.readBytes(1))
        }
    }

    @Test
    fun `closing stops listening`() {
        server.close()
        assertFalse(runCatching { connect().use { it.getInputStream().read() } }.getOrDefault(-1) >= 0)
    }

    // Against the client esptool and PlatformIO use, where pyserial is installed.
    @Test
    fun `pyserial's rfc2217 client opens the port, sets it up and talks`() {
        val python = ProcessBuilder("python3", "-c", "import serial.rfc2217").redirectErrorStream(true)
        assumeTrue(runCatching { python.start().waitFor() == 0 }.getOrDefault(false), "pyserial isn't installed")
        val script = File.createTempFile("rfc2217", ".py").apply {
            writeText(
                """
                import serial, sys
                s = serial.serial_for_url("rfc2217://localhost:${server.port}", baudrate=115200, timeout=3)
                s.dtr = False
                s.rts = True
                s.write(b"hello")
                print(s.read(3))
                s.close()
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val process = ProcessBuilder("python3", script.path).redirectErrorStream(true).start()
        waitFor("hello") { String(line.writtenBytes()) == "hello" }
        server.fromLine("abc".toByteArray(), 3)
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("b'abc'", output.trim(), output)
        val calls = line.callList()
        assertTrue("configure ${LineSettings(baud = 115200)}" in calls, calls.toString())
        // Opening raises both, as pyserial does; then the script's own.
        assertEquals(listOf("dtr true", "rts true", "dtr false", "rts true"), calls.filter { it.startsWith("dtr") || it.startsWith("rts") }.take(4))
    }
}
