package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class X11InputTest {
    private fun bytes(input: X11Input): ByteBuffer {
        val encoded = input.encode()
        assertEquals(24, encoded.size, "a lorieEvent is 24 bytes")
        return ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test
    fun `the screen size is event 4 with width, height and frame rate, and no name`() {
        val b = bytes(X11Input.ScreenSize(536, 1100, 120))
        assertEquals(4, b.get(0).toInt())
        assertEquals(536, b.getShort(2).toInt())
        assertEquals(1100, b.getShort(4).toInt())
        assertEquals(120, b.getShort(6).toInt())
        assertEquals(0L, b.getLong(8), "name_size")
        assertEquals(0L, b.getLong(16), "name")
    }

    @Test
    fun `a move is an absolute mouse event at x, y`() {
        val b = bytes(X11Input.Move(12.5f, 300f))
        assertEquals(6, b.get(0).toInt())
        assertEquals(12.5f, b.getFloat(4))
        assertEquals(300f, b.getFloat(8))
        assertEquals(0, b.get(12).toInt(), "detail: no button")
        assertEquals(0, b.get(14).toInt(), "not relative")
    }

    @Test
    fun `a button is pressed or released by its number`() {
        val press = bytes(X11Input.Button(3, down = true))
        assertEquals(6, press.get(0).toInt())
        assertEquals(3, press.get(12).toInt())
        assertEquals(1, press.get(13).toInt())
        assertEquals(0, bytes(X11Input.Button(1, down = false)).get(13).toInt())
    }

    @Test
    fun `a scroll is button 4 with 120 to a notch`() {
        val b = bytes(X11Input.Scroll(dx = 0.5f, dy = -2f))
        assertEquals(6, b.get(0).toInt())
        assertEquals(4, b.get(12).toInt())
        assertEquals(60f, b.getFloat(4))
        assertEquals(-240f, b.getFloat(8))
    }

    @Test
    fun `a key is event 7 with the X key code, the Linux one plus 8`() {
        val b = bytes(X11Input.Key(30, down = true))
        assertEquals(7, b.get(0).toInt())
        assertEquals(38, b.getShort(2).toInt())
        assertEquals(1, b.get(4).toInt())
        assertEquals(0, bytes(X11Input.Key(30, down = false)).get(4).toInt())
    }

    @Test
    fun `a character is event 10 with its code point`() {
        val b = bytes(X11Input.Unicode(0x1F600))
        assertEquals(10, b.get(0).toInt())
        assertEquals(0x1F600, b.getInt(4))
    }

    @Test
    fun `the server's interface calls are numbered in its AIDL's order`() {
        // ICmdEntryInterface.aidl: getXConnection, getLogcatOutput, reportFatalError, windowChanged, cursorWindowChanged.
        assertEquals(1, X11_GET_X_CONNECTION)
        assertEquals(4, X11_WINDOW_CHANGED)
        assertEquals("com.termux.x11.ICmdEntryInterface", X11_INTERFACE)
    }
}
