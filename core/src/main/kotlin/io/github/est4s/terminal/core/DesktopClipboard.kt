package io.github.est4s.terminal.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The most text (UTF-8 bytes) sent to X's clipboard: the server reads
 * it with one `read()` from a stream socket, so text that doesn't arrive
 * whole would break the events after it. 64 KiB fits the socket's
 * buffer; longer text is cut, at a character's start.
 */
const val CLIPBOARD_TO_X_MAX = 64 * 1024
/**
 * The most text (UTF-8 bytes) taken from X's clipboard: Android's
 * clipboard goes through a binder call, which fails past about 1 MB
 * (and holds the text as UTF-16). Longer text is skipped.
 */
const val CLIPBOARD_FROM_X_MAX = 256 * 1024

// lorie.h's eventType: what the server sends us.
private const val EVENT_SHARED_SERVER_STATE = 1
private const val EVENT_CLIPBOARD_REQUEST = 13
private const val EVENT_SIZE = 24

/** What the X server sends on its connection. */
sealed interface X11ServerEvent {
    /** X's CLIPBOARD changed: its text (empty when the server couldn't convert it). */
    data class Clipboard(val text: String) : X11ServerEvent

    /** X's CLIPBOARD changed to more than [CLIPBOARD_FROM_X_MAX]: [bytes] long, skipped. */
    data class ClipboardTooBig(val bytes: Long) : X11ServerEvent

    /** An X program pastes what we announced: answer with the text. */
    data object ClipboardRequest : X11ServerEvent

    /** Our patch: what each surface shows, this phone's ([host]) and the other phone's ([screen], null: none). */
    data class MonitorsShown(val host: PixelRect, val screen: PixelRect?) : X11ServerEvent

    /** Anything else (heartbeats, the shared state, sync replies), unread. */
    data class Other(val type: Int) : X11ServerEvent
}

/**
 * Reads the X server's events from its connection: each a 24-byte
 * `lorieEvent`; the clipboard's text follows its event (a count at
 * offset 4, then that many bytes), the shared state's event one byte
 * (sent beside its descriptor, which we don't take). Not thread-safe:
 * one reader.
 */
class X11Events(input: InputStream, private val limit: Int = CLIPBOARD_FROM_X_MAX) {
    private val input = DataInputStream(input)
    private val event = ByteArray(EVENT_SIZE)

    /** The next event, or null once the connection has ended. */
    fun next(): X11ServerEvent? = try {
        input.readFully(event)
        when (val type = event[0].toInt() and 0xFF) {
            EVENT_CLIPBOARD_SEND -> {
                val count = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xFFFFFFFFL
                if (count > limit) {
                    skip(count)
                    X11ServerEvent.ClipboardTooBig(count)
                } else {
                    val text = ByteArray(count.toInt())
                    input.readFully(text)
                    X11ServerEvent.Clipboard(String(text, Charsets.UTF_8))
                }
            }
            EVENT_CLIPBOARD_REQUEST -> X11ServerEvent.ClipboardRequest
            MYNX_EVENT_MONITORS_SHOWN -> {
                val b = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
                fun rect(at: Int) = PixelRect(b.getShort(at).toInt(), b.getShort(at + 2).toInt(), b.getShort(at + 4).toInt(), b.getShort(at + 6).toInt())
                X11ServerEvent.MonitorsShown(rect(2), rect(10).takeIf { it.width > 0 && it.height > 0 })
            }
            EVENT_SHARED_SERVER_STATE -> {
                input.readFully(ByteArray(1))
                X11ServerEvent.Other(type)
            }
            else -> X11ServerEvent.Other(type)
        }
    } catch (_: EOFException) {
        null
    }

    private fun skip(count: Long) {
        val buffer = ByteArray(8192)
        var left = count
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException()
            left -= n
        }
    }
}

/**
 * The clipboard shared between X's CLIPBOARD and Android's, text only.
 * Android's text is announced to the server, which then owns CLIPBOARD
 * for X's programs, and sent at once (the server keeps it for their
 * pastes; a request it still makes is answered with the same text). X's
 * text comes from the server whenever a program there copies. Each side
 * gets only what the other doesn't have, so nothing echoes back. Main
 * thread.
 */
class DesktopClipboard {
    // What X's clipboard holds, as far as we know: what it sent or what we gave it.
    private var xHas: String? = null
    private var announced: String? = null

    /** A new connection: its server has nothing from us, and syncs only once asked. */
    fun connected(): List<X11Input> {
        xHas = null
        announced = null
        return listOf(X11Input.ClipboardEnable(true))
    }

    /** Android's clipboard as text, null when it holds none (or we may not read it): what to send X. */
    fun fromAndroid(text: String?): List<X11Input> {
        if (text.isNullOrEmpty() || text == xHas) return emptyList()
        xHas = text
        announced = text
        return listOf(X11Input.ClipboardAnnounce, X11Input.ClipboardText(text))
    }

    /** X's clipboard changed to [text]: what to put on Android's, or null. */
    fun fromX(text: String): String? {
        if (text.isEmpty() || text == xHas) return null
        xHas = text
        return text
    }

    /** An X program pastes: always answered (it waits for one), empty when we announced nothing. */
    fun request(): List<X11Input> = listOf(X11Input.ClipboardText(announced ?: ""))
}
