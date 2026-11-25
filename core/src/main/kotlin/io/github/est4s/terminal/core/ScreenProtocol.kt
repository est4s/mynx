package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The version of the link between the phones; a Hello with another is refused. */
const val SCREEN_PROTOCOL = 1

/** The longest frame either side reads (a video frame must fit). */
const val MAX_SCREEN_FRAME = 4 * 1024 * 1024

/** A link that broke the protocol: a bad frame, a failed decryption. The connection ends. */
class ScreenProtocolException(message: String) : IOException(message)

/** Which phone: the host (A, which runs everything) or the screen (B, which shows what A sends). */
enum class ScreenRole(val word: String) { HOST("host"), SCREEN("screen") }

/** Where the screen (B) sits next to the host (A). */
enum class ScreenSide(val word: String) { LEFT("left"), RIGHT("right"), ABOVE("above"), BELOW("below") }

/** How the screen lines up along the host's edge: its start (top or left), centre or end. */
enum class ScreenAlign(val word: String) { START("start"), CENTER("center"), END("end") }

/** A [ScreenMessage.Hello]'s purpose: pairing with a code, or a paired phone coming back. */
enum class HelloMode { PAIR, RESUME }

/** Why the host refused a Hello, so the screen can say what to do. */
enum class RefusalReason { VERSION, NOT_PAIRING, NOT_PAIRED, WRONG_CODE, BUSY, OFF }

/**
 * What the phones send each other, version [SCREEN_PROTOCOL].
 * [Hello], [Welcome], [Confirm] and [Refusal] are the handshake, in
 * plain frames ([writePlainFrame]); everything after goes through a
 * [ScreenChannel]. A type a phone doesn't know is [Unknown], skipped,
 * so later versions can add messages. [Video], [Touch] and
 * [TerminalText] are placeholders for what's coming (work log "Next"
 * 0a step 3): their fields may still change.
 */
sealed class ScreenMessage(val type: Int) {
    /** The screen's first message: its version, id and name, and its SPAKE2 share. */
    class Hello(val version: Int, val id: String, val name: String, val mode: HelloMode, val share: ByteArray) :
        ScreenMessage(TYPE_HELLO)

    /** The host's answer: its version, id and name, its share and its confirmation. */
    class Welcome(val version: Int, val id: String, val name: String, val share: ByteArray, val confirm: ByteArray) :
        ScreenMessage(TYPE_WELCOME)

    /** The screen's confirmation: the link is up once the host has checked it. */
    class Confirm(val confirm: ByteArray) : ScreenMessage(TYPE_CONFIRM)

    class Refusal(val reason: RefusalReason, val text: String) : ScreenMessage(TYPE_REFUSAL)

    /** A phone's screen: its size in pixels, density (dpi) and rotation (0-3, quarter turns). */
    data class ScreenInfo(val width: Int, val height: Int, val density: Int, val rotation: Int) : ScreenMessage(TYPE_SCREEN_INFO)

    /** Where the screen sits; the host's word is final. */
    data class Layout(val side: ScreenSide, val align: ScreenAlign) : ScreenMessage(TYPE_LAYOUT)

    data object KeepAlive : ScreenMessage(TYPE_KEEP_ALIVE)

    /** The sender is closing the link (why, for people). */
    data class Bye(val reason: String) : ScreenMessage(TYPE_BYE)

    /** The sender forgot the pair: the other forgets it too. */
    data object Unpaired : ScreenMessage(TYPE_UNPAIRED)

    /** Typing goes to the screen touched last, on [to]. */
    data class Focus(val to: ScreenRole) : ScreenMessage(TYPE_FOCUS)

    /** Placeholder: part of an H.264 stream [stream] ([flags]: 1 a key frame, 2 its codec config). */
    class Video(val stream: Int, val flags: Int, val data: ByteArray) : ScreenMessage(TYPE_VIDEO)

    /** Placeholder: a touch on the screen, in its pixels ([action] as Android's MotionEvent). */
    data class Touch(val action: Int, val pointer: Int, val x: Float, val y: Float) : ScreenMessage(TYPE_TOUCH)

    /** Placeholder: a terminal tab's output (host to screen) or typing (screen to host). */
    class TerminalText(val tab: Int, val data: ByteArray) : ScreenMessage(TYPE_TERMINAL_TEXT)

    class Unknown(type: Int, val payload: ByteArray) : ScreenMessage(type)

    override fun equals(other: Any?): Boolean =
        other is ScreenMessage && other.javaClass == javaClass && encodeScreenMessage(other).contentEquals(encodeScreenMessage(this))

    override fun hashCode(): Int = encodeScreenMessage(this).contentHashCode()

    override fun toString(): String = "${javaClass.simpleName}(${encodeScreenMessage(this).size} bytes)"

    companion object {
        const val TYPE_HELLO = 1
        const val TYPE_WELCOME = 2
        const val TYPE_CONFIRM = 3
        const val TYPE_REFUSAL = 4
        const val TYPE_SCREEN_INFO = 16
        const val TYPE_LAYOUT = 17
        const val TYPE_KEEP_ALIVE = 18
        const val TYPE_BYE = 19
        const val TYPE_UNPAIRED = 20
        const val TYPE_FOCUS = 21
        const val TYPE_VIDEO = 32
        const val TYPE_TOUCH = 33
        const val TYPE_TERMINAL_TEXT = 34
    }
}

private const val MAX_TEXT = 256

/** A message as bytes: its type, then its fields (big-endian; text and bytes with their length first). */
fun encodeScreenMessage(m: ScreenMessage): ByteArray {
    val buffer = ByteArrayOutputStream()
    val out = DataOutputStream(buffer)
    out.writeByte(m.type)
    fun text(s: String) = out.shortBytes(s.toByteArray().also { require(it.size <= MAX_TEXT) { "text too long" } })
    fun bytesField(b: ByteArray) { out.writeInt(b.size); out.write(b) }
    when (m) {
        is ScreenMessage.Hello -> {
            out.writeShort(m.version); text(m.id); text(m.name); out.writeByte(m.mode.ordinal); bytesField(m.share)
        }
        is ScreenMessage.Welcome -> {
            out.writeShort(m.version); text(m.id); text(m.name); bytesField(m.share); bytesField(m.confirm)
        }
        is ScreenMessage.Confirm -> bytesField(m.confirm)
        is ScreenMessage.Refusal -> { out.writeByte(m.reason.ordinal); text(m.text) }
        is ScreenMessage.ScreenInfo -> { out.writeInt(m.width); out.writeInt(m.height); out.writeInt(m.density); out.writeByte(m.rotation) }
        is ScreenMessage.Layout -> { out.writeByte(m.side.ordinal); out.writeByte(m.align.ordinal) }
        ScreenMessage.KeepAlive, ScreenMessage.Unpaired -> {}
        is ScreenMessage.Bye -> text(m.reason)
        is ScreenMessage.Focus -> out.writeByte(m.to.ordinal)
        is ScreenMessage.Video -> { out.writeInt(m.stream); out.writeByte(m.flags); out.write(m.data) }
        is ScreenMessage.Touch -> { out.writeByte(m.action); out.writeByte(m.pointer); out.writeFloat(m.x); out.writeFloat(m.y) }
        is ScreenMessage.TerminalText -> { out.writeInt(m.tab); out.write(m.data) }
        is ScreenMessage.Unknown -> out.write(m.payload)
    }
    out.flush()
    return buffer.toByteArray()
}

private fun DataOutputStream.shortBytes(b: ByteArray) {
    writeShort(b.size)
    write(b)
}

/** A message from its bytes; [ScreenProtocolException] if they aren't one. */
fun decodeScreenMessage(bytes: ByteArray): ScreenMessage {
    if (bytes.isEmpty()) throw ScreenProtocolException("an empty message")
    val type = bytes[0].toInt() and 0xff
    val input = DataInputStream(bytes.inputStream(1, bytes.size - 1))
    fun rest() = input.readAllBytes()
    fun text(): String {
        val size = input.readUnsignedShort()
        if (size > MAX_TEXT) throw ScreenProtocolException("text too long")
        return String(ByteArray(size).also(input::readFully))
    }
    fun bytesField(): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > input.available()) throw ScreenProtocolException("a field longer than its message")
        return ByteArray(size).also(input::readFully)
    }
    fun <T : Enum<T>> pick(values: Array<T>, what: String): T =
        values.getOrNull(input.readUnsignedByte()) ?: throw ScreenProtocolException("unknown $what")
    return try {
        when (type) {
            ScreenMessage.TYPE_HELLO -> ScreenMessage.Hello(
                input.readUnsignedShort(), text(), text(), pick(HelloMode.entries.toTypedArray(), "mode"), bytesField(),
            )
            ScreenMessage.TYPE_WELCOME -> ScreenMessage.Welcome(input.readUnsignedShort(), text(), text(), bytesField(), bytesField())
            ScreenMessage.TYPE_CONFIRM -> ScreenMessage.Confirm(bytesField())
            ScreenMessage.TYPE_REFUSAL -> ScreenMessage.Refusal(pick(RefusalReason.entries.toTypedArray(), "refusal"), text())
            ScreenMessage.TYPE_SCREEN_INFO ->
                ScreenMessage.ScreenInfo(input.readInt(), input.readInt(), input.readInt(), input.readUnsignedByte())
            ScreenMessage.TYPE_LAYOUT ->
                ScreenMessage.Layout(pick(ScreenSide.entries.toTypedArray(), "side"), pick(ScreenAlign.entries.toTypedArray(), "alignment"))
            ScreenMessage.TYPE_KEEP_ALIVE -> ScreenMessage.KeepAlive
            ScreenMessage.TYPE_BYE -> ScreenMessage.Bye(text())
            ScreenMessage.TYPE_UNPAIRED -> ScreenMessage.Unpaired
            ScreenMessage.TYPE_FOCUS -> ScreenMessage.Focus(pick(ScreenRole.entries.toTypedArray(), "screen"))
            ScreenMessage.TYPE_VIDEO -> ScreenMessage.Video(input.readInt(), input.readUnsignedByte(), rest())
            ScreenMessage.TYPE_TOUCH ->
                ScreenMessage.Touch(input.readUnsignedByte(), input.readUnsignedByte(), input.readFloat(), input.readFloat())
            ScreenMessage.TYPE_TERMINAL_TEXT -> ScreenMessage.TerminalText(input.readInt(), rest())
            else -> ScreenMessage.Unknown(type, rest())
        }
    } catch (e: EOFException) {
        throw ScreenProtocolException("a message shorter than its fields")
    }
}

private fun readFrameBody(input: InputStream): Pair<ByteArray, ByteArray> {
    val header = ByteArray(4)
    DataInputStream(input).readFully(header)
    val size = ByteBuffer.wrap(header).int
    if (size <= 0 || size > MAX_SCREEN_FRAME) throw ScreenProtocolException("a frame of $size bytes")
    return header to ByteArray(size).also(DataInputStream(input)::readFully)
}

private fun header(size: Int) = ByteBuffer.allocate(4).putInt(size).array()

/** A handshake message, unencrypted: its length (4 bytes), then the message. */
fun writePlainFrame(out: OutputStream, message: ScreenMessage) {
    val body = encodeScreenMessage(message)
    out.write(header(body.size) + body)
    out.flush()
}

fun readPlainFrame(input: InputStream): ScreenMessage = decodeScreenMessage(readFrameBody(input).second)

/** The AES-128-GCM keys of a link, one per direction. */
class SessionKeys internal constructor(val hostToScreen: ByteArray, val screenToHost: ByteArray)

/** A link's keys from SPAKE2's Ke, bound to its [transcript]. */
fun sessionKeys(ke: ByteArray, transcript: ByteArray): SessionKeys {
    val salt = java.security.MessageDigest.getInstance("SHA-256").digest(transcript)
    val keys = hkdf(ke, salt, "mynx-screen session keys".toByteArray(), 32)
    return SessionKeys(keys.copyOfRange(0, 16), keys.copyOfRange(16, 32))
}

/**
 * The encrypted frames of a link, for [role]'s side: its length (4
 * bytes, authenticated too), then AES-128-GCM of the message with a
 * 16-byte tag. Each direction has its own key, and its nonce is the
 * count of frames sent before (never sent: both sides count), so a
 * replayed, reordered, dropped, reflected or changed frame fails to
 * decrypt and ends the link. Writes and reads may run on different
 * threads; each is synchronized.
 */
class ScreenChannel(keys: SessionKeys, role: ScreenRole) {
    private val sendKey = SecretKeySpec(if (role == ScreenRole.HOST) keys.hostToScreen else keys.screenToHost, "AES")
    private val receiveKey = SecretKeySpec(if (role == ScreenRole.HOST) keys.screenToHost else keys.hostToScreen, "AES")
    private var sent = 0L
    private var received = 0L

    private fun nonce(count: Long) = ByteBuffer.allocate(12).putInt(0).putLong(count).array()

    fun write(out: OutputStream, message: ScreenMessage) {
        val body = encodeScreenMessage(message)
        synchronized(this) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sent++)))
            val head = header(body.size + 16)
            cipher.updateAAD(head)
            out.write(head + cipher.doFinal(body))
            out.flush()
        }
    }

    fun read(input: InputStream): ScreenMessage {
        val (head, body) = readFrameBody(input)
        val plain = synchronized(this) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(received)))
            cipher.updateAAD(head)
            try {
                cipher.doFinal(body).also { received++ }
            } catch (e: GeneralSecurityException) {
                throw ScreenProtocolException("a frame that doesn't decrypt (changed, replayed or not ours)")
            }
        }
        return decodeScreenMessage(plain)
    }
}
