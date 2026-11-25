package io.github.est4s.terminal.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// What the X server sends: a 24-byte lorieEvent, then what some of them carry.
private fun serverEvent(type: Int, count: Int = 0, then: ByteArray = ByteArray(0)): ByteArray {
    val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    b.put(0, type.toByte()).putInt(4, count)
    return b.array() + then
}

class X11ClipboardEventsTest {
    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `clipboard sync is turned on with event 11`() {
        val b = le(X11Input.ClipboardEnable(true).encode())
        assertEquals(24, b.capacity())
        assertEquals(11, b.get(0).toInt())
        assertEquals(1, b.get(1).toInt())
        assertEquals(0, le(X11Input.ClipboardEnable(false).encode()).get(1).toInt())
    }

    @Test
    fun `an announce is event 12 alone`() {
        val b = X11Input.ClipboardAnnounce.encode()
        assertContentEquals(ByteArray(24).also { it[0] = 12 }, b)
    }

    @Test
    fun `text is event 14 with its UTF-8 length, then the bytes, no NUL`() {
        val encoded = X11Input.ClipboardText("héllo").encode()
        val b = le(encoded)
        assertEquals(14, b.get(0).toInt())
        assertEquals(6, b.getInt(4))
        assertEquals(30, encoded.size)
        assertEquals("héllo", String(encoded, 24, 6, Charsets.UTF_8))
    }

    @Test
    fun `text past the limit is cut at a character's start`() {
        val text = "ab" + "é".repeat(10)
        val encoded = X11Input.ClipboardText(text, limit = 7).encode()
        // a b é é + half an é: the half goes.
        assertEquals(6, le(encoded).getInt(4))
        assertEquals("abéé", String(encoded, 24, 6, Charsets.UTF_8))
    }

    @Test
    fun `the limit is what the server takes in one read`() {
        val big = "x".repeat(CLIPBOARD_TO_X_MAX + 10)
        assertEquals(CLIPBOARD_TO_X_MAX, le(X11Input.ClipboardText(big).encode()).getInt(4))
    }
}

class X11ServerEventsTest {
    private fun reader(vararg events: ByteArray): X11Events {
        val out = ByteArrayOutputStream()
        events.forEach { out.write(it) }
        return X11Events(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `the clipboard's text, a request, and the rest`() {
        val text = "copied in X ✓".toByteArray()
        val events = reader(
            serverEvent(19), // a heartbeat
            serverEvent(14, text.size, text),
            serverEvent(13),
            serverEvent(3), // shown changed
        )
        assertEquals(X11ServerEvent.Other(19), events.next())
        assertEquals(X11ServerEvent.Clipboard("copied in X ✓"), events.next())
        assertEquals(X11ServerEvent.ClipboardRequest, events.next())
        assertEquals(X11ServerEvent.Other(3), events.next())
        assertNull(events.next(), "the end of the stream")
    }

    @Test
    fun `the shared state's event carries one more byte, beside its descriptor`() {
        val events = reader(serverEvent(1, then = byteArrayOf('!'.code.toByte())), serverEvent(13))
        assertEquals(X11ServerEvent.Other(1), events.next())
        assertEquals(X11ServerEvent.ClipboardRequest, events.next())
    }

    @Test
    fun `text arriving in pieces is read whole`() {
        val text = "a".repeat(5000).toByteArray()
        val whole = serverEvent(14, text.size, text) + serverEvent(13)
        // An input stream that gives at most 7 bytes a read, as a socket may.
        val stingy = object : java.io.InputStream() {
            var at = 0
            override fun read(): Int = if (at < whole.size) whole[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= whole.size) return -1
                val n = minOf(len, 7, whole.size - at)
                System.arraycopy(whole, at, b, off, n)
                at += n
                return n
            }
        }
        val events = X11Events(stingy)
        assertEquals(X11ServerEvent.Clipboard("a".repeat(5000)), events.next())
        assertEquals(X11ServerEvent.ClipboardRequest, events.next())
    }

    @Test
    fun `text too big for Android's clipboard is skipped, and what follows still reads`() {
        val text = ByteArray(100) { 'z'.code.toByte() }
        val events = X11Events(ByteArrayInputStream(serverEvent(14, 100, text) + serverEvent(13)), limit = 50)
        assertEquals(X11ServerEvent.ClipboardTooBig(100), events.next())
        assertEquals(X11ServerEvent.ClipboardRequest, events.next())
    }

    @Test
    fun `a stream cut short ends`() {
        assertNull(X11Events(ByteArrayInputStream(serverEvent(14, 10, "abc".toByteArray()))).next())
        assertNull(X11Events(ByteArrayInputStream(ByteArray(10))).next())
    }
}

class DesktopClipboardTest {
    private val clip = DesktopClipboard()

    @Test
    fun `a connection turns sync on`() {
        assertEquals(listOf(X11Input.ClipboardEnable(true)), clip.connected())
    }

    @Test
    fun `Android's text is announced and sent, once`() {
        clip.connected()
        assertEquals(listOf(X11Input.ClipboardAnnounce, X11Input.ClipboardText("hello")), clip.fromAndroid("hello"))
        assertEquals(emptyList(), clip.fromAndroid("hello"), "shown again, unchanged")
        assertEquals(listOf(X11Input.ClipboardText("hello")), clip.request())
    }

    @Test
    fun `nothing on Android's clipboard, or nothing readable, isn't sent`() {
        assertEquals(emptyList(), clip.fromAndroid(null))
        assertEquals(emptyList(), clip.fromAndroid(""))
    }

    @Test
    fun `a request always gets an answer, empty when there's nothing`() {
        assertEquals(listOf(X11Input.ClipboardText("")), clip.request())
    }

    @Test
    fun `X's text goes to Android, and doesn't come back`() {
        clip.connected()
        assertEquals("from X", clip.fromX("from X"))
        // Android tells us its clipboard changed (to what we put there).
        assertEquals(emptyList(), clip.fromAndroid("from X"))
        assertNull(clip.fromX("from X"), "X again with the same: Android has it")
    }

    @Test
    fun `X's empty answers (a failed conversion) leave Android's clipboard alone`() {
        assertNull(clip.fromX(""))
    }

    @Test
    fun `what Android sent doesn't go back to it`() {
        clip.fromAndroid("mine")
        assertNull(clip.fromX("mine"))
        assertEquals("new in X", clip.fromX("new in X"))
        assertEquals(2, clip.fromAndroid("mine").size, "Android's text again, after X had another")
    }

    @Test
    fun `a new connection's server has nothing, so Android's text goes again`() {
        clip.connected()
        clip.fromAndroid("kept")
        clip.connected()
        assertEquals(2, clip.fromAndroid("kept").size)
    }
}
