package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Desktop mode's side of Termux:X11's interface (a separate GPL
 * program, so nothing of its code is here: these are the facts of its
 * interface, at the commit `scripts/build-x11.sh` pins). The server
 * broadcasts [X11_START_ACTION] with its binder; the binder's calls are
 * numbered in `ICmdEntryInterface.aidl`'s order; `getXConnection()`
 * returns a socket the view writes [X11Input]s to.
 */
const val X11_START_ACTION = "com.termux.x11.CmdEntryPoint.ACTION_START"
const val X11_INTERFACE = "com.termux.x11.ICmdEntryInterface"
/** Binder transaction codes (FIRST_CALL_TRANSACTION is 1). */
const val X11_GET_X_CONNECTION = 1
const val X11_WINDOW_CHANGED = 4

// lorie.h's eventType, and the size of its lorieEvent union.
private const val EVENT_SCREEN_SIZE = 4
private const val EVENT_MOUSE = 6
private const val EVENT_KEY = 7
private const val EVENT_UNICODE = 10
private const val EVENT_SIZE = 24
private const val MOUSE_SCROLL = 4
private const val SCROLL_UNITS = 120
// X key codes are Linux (evdev) ones plus 8.
private const val X_KEYCODE_OFFSET = 8

/** One event for the X server: a 24-byte `lorieEvent`, little-endian (arm64). */
sealed interface X11Input {
    fun encode(): ByteArray

    /** The X screen's size in its pixels, and the display's refresh rate. */
    data class ScreenSize(val width: Int, val height: Int, val framerate: Int) : X11Input {
        override fun encode() = event(EVENT_SCREEN_SIZE) {
            putShort(2, width.toShort()).putShort(4, height.toShort()).putShort(6, framerate.toShort())
        }
    }

    /** The pointer to [x], [y] (X pixels). */
    data class Move(val x: Float, val y: Float) : X11Input {
        override fun encode() = event(EVENT_MOUSE) { putFloat(4, x).putFloat(8, y) }
    }

    /** [button] 1 left, 2 middle, 3 right, pressed or released. */
    data class Button(val button: Int, val down: Boolean) : X11Input {
        override fun encode() = event(EVENT_MOUSE) { put(12, button.toByte()).put(13, if (down) 1 else 0) }
    }

    /** Scrolling by [dx], [dy] wheel notches (fractions too); positive is right and down. */
    data class Scroll(val dx: Float, val dy: Float) : X11Input {
        override fun encode() = event(EVENT_MOUSE) {
            putFloat(4, dx * SCROLL_UNITS).putFloat(8, dy * SCROLL_UNITS).put(12, MOUSE_SCROLL.toByte())
        }
    }

    /** A key by its Linux (evdev) code, pressed or released. */
    data class Key(val code: Int, val down: Boolean) : X11Input {
        override fun encode() = event(EVENT_KEY) {
            putShort(2, (code + X_KEYCODE_OFFSET).toShort()).put(4, if (down) 1 else 0)
        }
    }

    /** A character typed: the server finds a key for it, presses and releases it. */
    data class Unicode(val codePoint: Int) : X11Input {
        override fun encode() = event(EVENT_UNICODE) { putInt(4, codePoint) }
    }
}

private fun event(type: Int, fill: ByteBuffer.() -> Unit): ByteArray {
    val buffer = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(0, type.toByte())
    buffer.fill()
    return buffer.array()
}
