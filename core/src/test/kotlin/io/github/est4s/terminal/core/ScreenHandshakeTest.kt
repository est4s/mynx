package io.github.est4s.terminal.core

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class Pipe {
    val input = PipedInputStream(1 shl 16)
    val output = PipedOutputStream(input)
}

class ScreenHandshakeTest {
    private val host = ScreenMe("aa".repeat(16), "Pixel 10")
    private val screen = ScreenMe("bb".repeat(16), "Nord 2")
    private val toHost = Pipe()
    private val toScreen = Pipe()

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block)

    private fun <T> CompletableFuture<T>.result(): T = try {
        get(10, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause!!
    }

    private fun accept(secretFor: (ScreenMessage.Hello) -> ByteArray) = async {
        try {
            acceptScreen(toHost.input, toScreen.output, host, secretFor)
        } finally {
            toScreen.output.close()
        }
    }

    private fun join(mode: HelloMode, secret: ByteArray, hostId: String? = null, input: InputStream = toScreen.input, output: OutputStream = toHost.output) =
        async {
            try {
                joinHost(input, output, screen, mode, secret, hostId)
            } finally {
                output.close()
            }
        }

    @Test
    fun `pairing with the right code links both phones with the same pair key`() {
        val h = accept { hello -> assertEquals(HelloMode.PAIR, hello.mode); "123456".toByteArray() }
        val s = join(HelloMode.PAIR, "123456".toByteArray())
        val onHost = h.result()
        val onScreen = s.result()
        assertEquals(screen.id, onHost.peerId)
        assertEquals("Nord 2", onHost.peerName)
        assertEquals(host.id, onScreen.peerId)
        assertEquals("Pixel 10", onScreen.peerName)
        assertContentEquals(onHost.pairKey, onScreen.pairKey)
        assertEquals(32, onHost.pairKey.size)
        // The channels work both ways.
        val back = Pipe()
        onHost.channel.write(back.output, ScreenMessage.Layout(ScreenSide.LEFT, ScreenAlign.START))
        assertEquals(ScreenMessage.Layout(ScreenSide.LEFT, ScreenAlign.START), onScreen.channel.read(back.input))
        onScreen.channel.write(back.output, ScreenMessage.KeepAlive)
        assertEquals(ScreenMessage.KeepAlive, onHost.channel.read(back.input))
    }

    @Test
    fun `a wrong code fails on both phones, and says so`() {
        val h = accept { "123456".toByteArray() }
        val s = join(HelloMode.PAIR, "654321".toByteArray())
        assertEquals(RefusalReason.WRONG_CODE, assertFailsWith<ScreenRefused> { s.result() }.reason)
        assertEquals(RefusalReason.WRONG_CODE, assertFailsWith<ScreenRefused> { h.result() }.reason)
    }

    @Test
    fun `a paired phone comes back with its pair key, and a wrong key fails`() {
        val key = ByteArray(32) { 3 }
        val h = accept { hello -> assertEquals(HelloMode.RESUME, hello.mode); key }
        val s = join(HelloMode.RESUME, key, hostId = host.id)
        assertEquals(screen.id, h.result().peerId)
        assertEquals(host.id, s.result().peerId)
    }

    @Test
    fun `a stale pair key says to pair again`() {
        val h = accept { ByteArray(32) { 3 } }
        val s = join(HelloMode.RESUME, ByteArray(32) { 4 }, hostId = host.id)
        assertEquals(RefusalReason.NOT_PAIRED, assertFailsWith<ScreenRefused> { s.result() }.reason)
        assertFailsWith<ScreenRefused> { h.result() }
    }

    @Test
    fun `the host's refusal reaches the screen`() {
        val h = accept { throw ScreenRefused(RefusalReason.NOT_PAIRING, "it isn't pairing") }
        val s = join(HelloMode.PAIR, "123456".toByteArray())
        val refused = assertFailsWith<ScreenRefused> { s.result() }
        assertEquals(RefusalReason.NOT_PAIRING, refused.reason)
        assertEquals("it isn't pairing", refused.message)
        assertFailsWith<ScreenRefused> { h.result() }
    }

    @Test
    fun `another phone answering for the paired host is refused`() {
        val key = ByteArray(32) { 3 }
        val h = accept { key }
        val s = join(HelloMode.RESUME, key, hostId = "cc".repeat(16))
        assertIs<ScreenRefused>(runCatching { s.result() }.exceptionOrNull())
        runCatching { h.result() }
    }

    @Test
    fun `a Hello of another version is refused with what to do`() {
        val h = accept { "123456".toByteArray() }
        writePlainFrame(toHost.output, ScreenMessage.Hello(SCREEN_PROTOCOL + 1, screen.id, screen.name, HelloMode.PAIR, ByteArray(65)))
        assertEquals(RefusalReason.VERSION, assertFailsWith<ScreenRefused> { h.result() }.reason)
        val answer = readPlainFrame(toScreen.input)
        assertIs<ScreenMessage.Refusal>(answer)
        assertEquals(RefusalReason.VERSION, answer.reason)
    }

    @Test
    fun `a name changed on the way fails the confirmation`() {
        // A relay between them that renames the host in its Welcome.
        val relayed = Pipe()
        val h = accept { "123456".toByteArray() }
        val s = join(HelloMode.PAIR, "123456".toByteArray(), input = relayed.input)
        async {
            val welcome = readPlainFrame(toScreen.input) as ScreenMessage.Welcome
            writePlainFrame(
                relayed.output,
                ScreenMessage.Welcome(welcome.version, welcome.id, "Evil", welcome.share, welcome.confirm),
            )
            runCatching { relayed.output.write(toScreen.input.readAllBytes()) }
            relayed.output.close()
        }
        assertEquals(RefusalReason.WRONG_CODE, assertFailsWith<ScreenRefused> { s.result() }.reason)
        assertFailsWith<ScreenRefused> { h.result() }
    }

    @Test
    fun `garbage instead of a Hello is a protocol error`() {
        val h = accept { "123456".toByteArray() }
        toHost.output.write(byteArrayOf(0, 0, 0, 2, 3, 0))
        toHost.output.close()
        assertFailsWith<java.io.IOException> { h.result() }
    }
}
