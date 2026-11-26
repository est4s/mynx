package io.github.est4s.terminal.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScreenMessagesTest {
    private fun roundTrip(message: ScreenMessage): ScreenMessage = decodeScreenMessage(encodeScreenMessage(message))

    @Test
    fun `every message comes back as it went`() {
        val messages = listOf(
            ScreenMessage.Hello(1, "b1", "Nord 2", HelloMode.PAIR, byteArrayOf(4, 5, 6)),
            ScreenMessage.Welcome(1, "a1", "Pixel 10", byteArrayOf(1), byteArrayOf(2, 3)),
            ScreenMessage.Confirm(byteArrayOf(9, 8)),
            ScreenMessage.Refusal(RefusalReason.WRONG_CODE, "wrong code"),
            ScreenMessage.ScreenInfo(1080, 2400, 420, 1),
            ScreenMessage.Layout(ScreenSide.LEFT, ScreenAlign.CENTER),
            ScreenMessage.KeepAlive,
            ScreenMessage.Bye("unpaired"),
            ScreenMessage.Unpaired,
            ScreenMessage.Focus(ScreenRole.SCREEN),
            ScreenMessage.Video(2, VIDEO_KEY or VIDEO_CONFIG, 123_456_789_012L, byteArrayOf(0, 0, 0, 1, 0x67)),
            ScreenMessage.Touch(3, 5, 1, listOf(TouchPoint(0, 12.5f, 800f), TouchPoint(4, 300f, 0.25f))),
            ScreenMessage.StreamStart(2, "Pixel Hero", 1080, 2400),
            ScreenMessage.StreamEnd(2),
            ScreenMessage.Watch(2, true),
            ScreenMessage.Watch(2, false),
            ScreenMessage.VideoAck(2, 123_456_789_012L),
            ScreenMessage.KeyFrame(2),
            ScreenMessage.TakeBack(2),
            ScreenMessage.TermStart(7, "bash", 56, 40),
            ScreenMessage.TermState(7, 3, 48, 30, "\u001bcprompt$ ".toByteArray()),
            ScreenMessage.TermOutput(7, 3, "ls\r\n".toByteArray()),
            ScreenMessage.TermTitle(7, "vim notes.md"),
            ScreenMessage.TermEnd(7),
            ScreenMessage.TermSize(7, 48, 30, 22, 51),
            ScreenMessage.TermInput(7, "q".toByteArray()),
            ScreenMessage.TermAck(7, 3, 123_456L),
            ScreenMessage.TermTouched(7),
        )
        for (m in messages) assertEquals(m, roundTrip(m), m.toString())
    }

    @Test
    fun `a type this version doesn't know comes back as unknown, to be skipped`() {
        val m = decodeScreenMessage(byteArrayOf(99.toByte(), 1, 2, 3))
        assertIs<ScreenMessage.Unknown>(m)
        assertEquals(99, m.type)
    }

    @Test
    fun `short or bad payloads are refused`() {
        assertFailsWith<ScreenProtocolException> { decodeScreenMessage(ByteArray(0)) }
        assertFailsWith<ScreenProtocolException> { decodeScreenMessage(byteArrayOf(ScreenMessage.TYPE_SCREEN_INFO.toByte(), 1)) }
        // A layout side this version doesn't know.
        assertFailsWith<ScreenProtocolException> { decodeScreenMessage(byteArrayOf(ScreenMessage.TYPE_LAYOUT.toByte(), 9, 0)) }
        // A touch saying it has more fingers than it carries.
        assertFailsWith<ScreenProtocolException> {
            decodeScreenMessage(byteArrayOf(ScreenMessage.TYPE_TOUCH.toByte(), 0, 0, 0, 1, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        }
    }

    @Test
    fun `a touch carries at most ten fingers`() {
        val many = (0 until 11).map { TouchPoint(it, 1f, 1f) }
        assertFailsWith<IllegalArgumentException> { encodeScreenMessage(ScreenMessage.Touch(1, 2, 0, many)) }
    }

    @Test
    fun `a video part keeps its bytes and its flags`() {
        val m = decodeScreenMessage(encodeScreenMessage(ScreenMessage.Video(7, VIDEO_KEY, 42L, byteArrayOf(1, 2, 3))))
        assertIs<ScreenMessage.Video>(m)
        assertTrue(m.key)
        assertTrue(!m.config)
        assertEquals(42L, m.pts)
        assertContentEquals(byteArrayOf(1, 2, 3), m.data)
    }

    @Test
    fun `this is version 3 of the link`() {
        // Version 1 was the pairing alone, 2 added screens as video, 3 terminals as text.
        assertEquals(3, SCREEN_PROTOCOL)
    }

    @Test
    fun `a terminal's bytes travel as they are`() {
        val bytes = ByteArray(256) { it.toByte() }
        val m = roundTrip(ScreenMessage.TermOutput(2, 9, bytes))
        assertIs<ScreenMessage.TermOutput>(m)
        assertContentEquals(bytes, m.data)
        val state = roundTrip(ScreenMessage.TermState(2, 9, 80, 24, bytes))
        assertIs<ScreenMessage.TermState>(state)
        assertContentEquals(bytes, state.data)
        assertEquals(Triple(9, 80, 24), Triple(state.epoch, state.columns, state.rows))
    }
}

class ScreenFramesTest {
    @Test
    fun `plain frames are a length, then the message`() {
        val out = ByteArrayOutputStream()
        writePlainFrame(out, ScreenMessage.Confirm(byteArrayOf(1, 2)))
        val bytes = out.toByteArray()
        assertEquals(bytes.size - 4, (bytes[0].toInt() shl 24) or (bytes[1].toInt() shl 16) or (bytes[2].toInt() shl 8) or bytes[3].toInt())
        assertEquals(ScreenMessage.Confirm(byteArrayOf(1, 2)), readPlainFrame(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `a frame longer than the limit is refused before it's read`() {
        val header = byteArrayOf(0x7f, 0, 0, 0)
        assertFailsWith<ScreenProtocolException> { readPlainFrame(ByteArrayInputStream(header)) }
    }

    @Test
    fun `a stream that ends mid-frame is an EOF`() {
        assertFailsWith<EOFException> { readPlainFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 5, 1))) }
    }
}

class ScreenChannelTest {
    private val keys = sessionKeys(ByteArray(16) { it.toByte() }, ByteArray(10) { 7 })
    private val host = ScreenChannel(keys, ScreenRole.HOST)
    private val screen = ScreenChannel(keys, ScreenRole.SCREEN)

    private fun send(from: ScreenChannel, message: ScreenMessage) =
        ByteArrayOutputStream().also { from.write(it, message) }.toByteArray()

    @Test
    fun `messages go both ways, encrypted`() {
        val frame = send(host, ScreenMessage.TermOutput(1, 1, "secret text".toByteArray()))
        assertTrue("secret text" !in String(frame, Charsets.ISO_8859_1))
        assertEquals(ScreenMessage.TermOutput(1, 1, "secret text".toByteArray()), screen.read(ByteArrayInputStream(frame)))
        val back = send(screen, ScreenMessage.KeepAlive)
        assertEquals(ScreenMessage.KeepAlive, host.read(ByteArrayInputStream(back)))
    }

    @Test
    fun `the same message sent twice is two different frames`() {
        val one = send(host, ScreenMessage.KeepAlive)
        val two = send(host, ScreenMessage.KeepAlive)
        assertTrue(!one.contentEquals(two))
    }

    @Test
    fun `a changed byte anywhere fails the frame`() {
        val frame = send(host, ScreenMessage.Layout(ScreenSide.RIGHT, ScreenAlign.START))
        for (i in 4 until frame.size) {
            val tampered = frame.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val fresh = ScreenChannel(keys, ScreenRole.SCREEN)
            assertFailsWith<ScreenProtocolException>("byte $i") { fresh.read(ByteArrayInputStream(tampered)) }
        }
    }

    @Test
    fun `a replayed frame fails, each direction counts its frames`() {
        val frame = send(host, ScreenMessage.Layout(ScreenSide.RIGHT, ScreenAlign.START))
        screen.read(ByteArrayInputStream(frame))
        assertFailsWith<ScreenProtocolException> { screen.read(ByteArrayInputStream(frame)) }
    }

    @Test
    fun `frames in the wrong order or dropped fail`() {
        val first = send(host, ScreenMessage.KeepAlive)
        val second = send(host, ScreenMessage.KeepAlive)
        assertFailsWith<ScreenProtocolException> { screen.read(ByteArrayInputStream(second)) }
        assertContentEquals(first, first)
    }

    @Test
    fun `a frame reflected back to its sender fails`() {
        val frame = send(host, ScreenMessage.KeepAlive)
        assertFailsWith<ScreenProtocolException> { host.read(ByteArrayInputStream(frame)) }
    }

    @Test
    fun `keys from another session can't read the frames`() {
        val other = ScreenChannel(sessionKeys(ByteArray(16) { 1 }, ByteArray(10) { 7 }), ScreenRole.SCREEN)
        assertFailsWith<ScreenProtocolException> { other.read(ByteArrayInputStream(send(host, ScreenMessage.KeepAlive))) }
    }
}
