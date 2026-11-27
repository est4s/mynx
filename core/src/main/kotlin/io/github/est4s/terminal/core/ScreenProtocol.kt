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

/**
 * The version of the link between the phones; a Hello with another is
 * refused. 1 was the pairing alone; 2 sends screens (video, touches);
 * 3 sends terminal tabs as text; 4 the desktop's pointer ([ScreenMessage.Pointer]);
 * 5 each screen's own dpi ([ScreenMessage.ScreenInfo]'s xdpi, ydpi) and
 * aligning the desktop across both ([ScreenMessage.Align]).
 */
const val SCREEN_PROTOCOL = 5

/** A [ScreenMessage.Video] part that's a key frame: it decodes alone. */
const val VIDEO_KEY = 1

/** A [ScreenMessage.Video] part that's the codec's config (H.264's SPS and PPS), before the frames. */
const val VIDEO_CONFIG = 2

/** The most fingers a [ScreenMessage.Touch] carries. */
const val MAX_TOUCH_POINTS = 10

/** One finger of a [ScreenMessage.Touch]: its [id] (Android's pointer id), where it is in the stream's pixels. */
data class TouchPoint(val id: Int, val x: Float, val y: Float)

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

/** How aligning the desktop across both phones ends, or starts over: [ScreenMessage.AlignAction]. */
enum class AlignStep { DONE, RESET, CANCEL }

/** A [ScreenMessage.Hello]'s purpose: pairing with a code, or a paired phone coming back. */
enum class HelloMode { PAIR, RESUME }

/** Why the host refused a Hello, so the screen can say what to do. */
enum class RefusalReason { VERSION, NOT_PAIRING, NOT_PAIRED, WRONG_CODE, BUSY, OFF }

/**
 * What the phones send each other, version [SCREEN_PROTOCOL].
 * [Hello], [Welcome], [Confirm] and [Refusal] are the handshake, in
 * plain frames ([writePlainFrame]); everything after goes through a
 * [ScreenChannel]. A type a phone doesn't know is [Unknown], skipped,
 * so later versions can add messages. A screen the host sends is a
 * stream: [StreamStart] and [StreamEnd] from the host; while the screen
 * [Watch]es it, [Video] parts, which it acks ([VideoAck]) and asks a
 * [KeyFrame] of when it must start over; its [Touch]es go back, and
 * [TakeBack] asks the host to have it back. A terminal tab the host
 * sends travels as text ([ScreenTerms]): [TermStart], then a [TermState]
 * (everything on it, as the bytes that draw it) and its [TermOutput]s
 * (what its programs write), which the screen acks ([TermAck]); the
 * screen asks for its size ([TermSize]), and sends what's typed there
 * ([TermInput]) and that it was touched ([TermTouched]). Ids of streams
 * and terminals are one series, so [TakeBack] takes either.
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

    /**
     * A phone's screen: its size in pixels, density (dpi, Android's
     * bucket) and rotation (0-3, quarter turns), and its real pixels an
     * inch across and down ([xdpi], [ydpi]; 0: not said, before version 5).
     */
    data class ScreenInfo(
        val width: Int,
        val height: Int,
        val density: Int,
        val rotation: Int,
        val xdpi: Float = 0f,
        val ydpi: Float = 0f,
    ) : ScreenMessage(TYPE_SCREEN_INFO)

    /** Where the screen sits; the host's word is final. */
    data class Layout(val side: ScreenSide, val align: ScreenAlign) : ScreenMessage(TYPE_LAYOUT)

    data object KeepAlive : ScreenMessage(TYPE_KEEP_ALIVE)

    /** The sender is closing the link (why, for people). */
    data class Bye(val reason: String) : ScreenMessage(TYPE_BYE)

    /** The sender forgot the pair: the other forgets it too. */
    data object Unpaired : ScreenMessage(TYPE_UNPAIRED)

    /** Typing goes to the screen touched last, on [to]. */
    data class Focus(val to: ScreenRole) : ScreenMessage(TYPE_FOCUS)

    /**
     * Part of stream [stream]'s H.264: one encoded frame (or the codec's
     * config) as MediaCodec gave it, [flags] [VIDEO_KEY] and [VIDEO_CONFIG],
     * [pts] its time on the host (µs), which the screen acks.
     */
    class Video(val stream: Int, val flags: Int, val pts: Long, val data: ByteArray) : ScreenMessage(TYPE_VIDEO) {
        val key: Boolean get() = flags and VIDEO_KEY != 0
        val config: Boolean get() = flags and VIDEO_CONFIG != 0
    }

    /**
     * A touch on stream [stream], in its pixels: [action] as Android's
     * MotionEvent's masked action, [index] the finger it's about (for a
     * second finger's down and up), and every finger down.
     */
    data class Touch(val stream: Int, val action: Int, val index: Int, val points: List<TouchPoint>) : ScreenMessage(TYPE_TOUCH)

    /** The host sends a screen, [title], drawn at [width] x [height] pixels. */
    data class StreamStart(val stream: Int, val title: String, val width: Int, val height: Int) : ScreenMessage(TYPE_STREAM_START)

    /** The host has the screen back (or it closed): the screen stops showing it. */
    data class StreamEnd(val stream: Int) : ScreenMessage(TYPE_STREAM_END)

    /** The screen shows stream [stream] ([on]) or stopped showing it: the host sends video only while it does. */
    data class Watch(val stream: Int, val on: Boolean) : ScreenMessage(TYPE_WATCH)

    /** The screen got stream [stream]'s part [pts] (and those before): how far behind it is. */
    data class VideoAck(val stream: Int, val pts: Long) : ScreenMessage(TYPE_VIDEO_ACK)

    /** The screen asks for a key frame of [stream] (its decoder starts over). */
    data class KeyFrame(val stream: Int) : ScreenMessage(TYPE_KEY_FRAME)

    /** The screen asks the host to take [stream] back. */
    data class TakeBack(val stream: Int) : ScreenMessage(TYPE_TAKE_BACK)

    /** The host sends terminal tab [term], [title], [columns] x [rows] for now. */
    data class TermStart(val term: Int, val title: String, val columns: Int, val rows: Int) : ScreenMessage(TYPE_TERM_START)

    /**
     * Everything on terminal [term] at [columns] x [rows]: [data] draws it
     * on a terminal reset to that size. It starts [epoch]: the
     * [TermOutput]s after it carry the same number.
     */
    class TermState(val term: Int, val epoch: Int, val columns: Int, val rows: Int, val data: ByteArray) : ScreenMessage(TYPE_TERM_STATE)

    /** What terminal [term]'s programs wrote, after [epoch]'s [TermState]. */
    class TermOutput(val term: Int, val epoch: Int, val data: ByteArray) : ScreenMessage(TYPE_TERM_OUTPUT)

    /** Terminal [term]'s tab is called [title] now. */
    data class TermTitle(val term: Int, val title: String) : ScreenMessage(TYPE_TERM_TITLE)

    /** The host has terminal [term] back (or it closed): the screen's tab goes. */
    data class TermEnd(val term: Int) : ScreenMessage(TYPE_TERM_END)

    /** The screen fits terminal [term] in [columns] x [rows] cells of [cellWidth] x [cellHeight] pixels: its size from now on. */
    data class TermSize(val term: Int, val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int) :
        ScreenMessage(TYPE_TERM_SIZE)

    /** Typed on the screen for terminal [term] (keys, mouse, paste): for its programs. */
    class TermInput(val term: Int, val data: ByteArray) : ScreenMessage(TYPE_TERM_INPUT)

    /** The screen got [bytes] of terminal [term]'s output since [epoch]'s state: how far behind it is. */
    data class TermAck(val term: Int, val epoch: Int, val bytes: Long) : ScreenMessage(TYPE_TERM_ACK)

    /** Terminal [term] was touched on the screen: typing goes to it. */
    data class TermTouched(val term: Int) : ScreenMessage(TYPE_TERM_TOUCHED)

    /**
     * The desktop's pointer on stream [stream] (the X server draws no
     * cursor there, so the screen draws one), at [x], [y] of its pixels;
     * not [shown] while it's on the host's part of the desktop.
     */
    data class Pointer(val stream: Int, val x: Float, val y: Float, val shown: Boolean) : ScreenMessage(TYPE_POINTER)

    /**
     * Aligning the desktop across both phones ([on]; off: it ended), on
     * stream [stream]: the screen draws a ruler along its edge facing
     * the host (it sits on the host's [side]), a line at [origin] + k ×
     * [step] of the video's pixels along that edge (every fifth longer),
     * and says the alignment, [offset] mm and [scale].
     */
    data class Align(
        val stream: Int,
        val on: Boolean,
        val side: ScreenSide,
        val origin: Float,
        val step: Float,
        val offset: Float,
        val scale: Float,
    ) : ScreenMessage(TYPE_ALIGN)

    /**
     * A drag or pinch on the screen while aligning: its picture moves
     * [shift] of the video's pixels along the shared edge, and grows
     * [zoom] times about [focus] (along the edge, the video's pixels).
     */
    data class AlignInput(val stream: Int, val shift: Float, val zoom: Float, val focus: Float) : ScreenMessage(TYPE_ALIGN_INPUT)

    /** The screen's Done, Reset or Cancel while aligning. */
    data class AlignAction(val stream: Int, val step: AlignStep) : ScreenMessage(TYPE_ALIGN_ACTION)

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
        const val TYPE_TERM_OUTPUT = 34
        const val TYPE_STREAM_START = 35
        const val TYPE_STREAM_END = 36
        const val TYPE_WATCH = 37
        const val TYPE_VIDEO_ACK = 38
        const val TYPE_KEY_FRAME = 39
        const val TYPE_TAKE_BACK = 40
        const val TYPE_TERM_START = 41
        const val TYPE_TERM_STATE = 42
        const val TYPE_TERM_TITLE = 43
        const val TYPE_TERM_END = 44
        const val TYPE_TERM_SIZE = 45
        const val TYPE_TERM_INPUT = 46
        const val TYPE_TERM_ACK = 47
        const val TYPE_TERM_TOUCHED = 48
        // Version 4.
        const val TYPE_POINTER = 49
        // Version 5.
        const val TYPE_ALIGN = 50
        const val TYPE_ALIGN_INPUT = 51
        const val TYPE_ALIGN_ACTION = 52
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
        is ScreenMessage.ScreenInfo -> {
            out.writeInt(m.width); out.writeInt(m.height); out.writeInt(m.density); out.writeByte(m.rotation)
            out.writeFloat(m.xdpi); out.writeFloat(m.ydpi)
        }
        is ScreenMessage.Layout -> { out.writeByte(m.side.ordinal); out.writeByte(m.align.ordinal) }
        ScreenMessage.KeepAlive, ScreenMessage.Unpaired -> {}
        is ScreenMessage.Bye -> text(m.reason)
        is ScreenMessage.Focus -> out.writeByte(m.to.ordinal)
        is ScreenMessage.Video -> { out.writeInt(m.stream); out.writeByte(m.flags); out.writeLong(m.pts); out.write(m.data) }
        is ScreenMessage.Touch -> {
            require(m.points.size <= MAX_TOUCH_POINTS) { "a touch of ${m.points.size} fingers" }
            out.writeInt(m.stream); out.writeByte(m.action); out.writeByte(m.index); out.writeByte(m.points.size)
            for (p in m.points) { out.writeByte(p.id); out.writeFloat(p.x); out.writeFloat(p.y) }
        }
        is ScreenMessage.StreamStart -> { out.writeInt(m.stream); text(m.title); out.writeInt(m.width); out.writeInt(m.height) }
        is ScreenMessage.StreamEnd -> out.writeInt(m.stream)
        is ScreenMessage.Watch -> { out.writeInt(m.stream); out.writeByte(if (m.on) 1 else 0) }
        is ScreenMessage.VideoAck -> { out.writeInt(m.stream); out.writeLong(m.pts) }
        is ScreenMessage.KeyFrame -> out.writeInt(m.stream)
        is ScreenMessage.TakeBack -> out.writeInt(m.stream)
        is ScreenMessage.TermStart -> { out.writeInt(m.term); text(m.title); out.writeShort(m.columns); out.writeShort(m.rows) }
        is ScreenMessage.TermState -> {
            out.writeInt(m.term); out.writeInt(m.epoch); out.writeShort(m.columns); out.writeShort(m.rows); out.write(m.data)
        }
        is ScreenMessage.TermOutput -> { out.writeInt(m.term); out.writeInt(m.epoch); out.write(m.data) }
        is ScreenMessage.TermTitle -> { out.writeInt(m.term); text(m.title) }
        is ScreenMessage.TermEnd -> out.writeInt(m.term)
        is ScreenMessage.TermSize -> {
            out.writeInt(m.term); out.writeShort(m.columns); out.writeShort(m.rows); out.writeShort(m.cellWidth); out.writeShort(m.cellHeight)
        }
        is ScreenMessage.TermInput -> { out.writeInt(m.term); out.write(m.data) }
        is ScreenMessage.TermAck -> { out.writeInt(m.term); out.writeInt(m.epoch); out.writeLong(m.bytes) }
        is ScreenMessage.TermTouched -> out.writeInt(m.term)
        is ScreenMessage.Pointer -> { out.writeInt(m.stream); out.writeFloat(m.x); out.writeFloat(m.y); out.writeByte(if (m.shown) 1 else 0) }
        is ScreenMessage.Align -> {
            out.writeInt(m.stream); out.writeByte(if (m.on) 1 else 0); out.writeByte(m.side.ordinal)
            out.writeFloat(m.origin); out.writeFloat(m.step); out.writeFloat(m.offset); out.writeFloat(m.scale)
        }
        is ScreenMessage.AlignInput -> { out.writeInt(m.stream); out.writeFloat(m.shift); out.writeFloat(m.zoom); out.writeFloat(m.focus) }
        is ScreenMessage.AlignAction -> { out.writeInt(m.stream); out.writeByte(m.step.ordinal) }
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
            ScreenMessage.TYPE_SCREEN_INFO -> {
                val width = input.readInt()
                val height = input.readInt()
                val density = input.readInt()
                val rotation = input.readUnsignedByte()
                // Version 4's ended here.
                if (input.available() >= 8) {
                    ScreenMessage.ScreenInfo(width, height, density, rotation, input.readFloat(), input.readFloat())
                } else {
                    ScreenMessage.ScreenInfo(width, height, density, rotation)
                }
            }
            ScreenMessage.TYPE_LAYOUT ->
                ScreenMessage.Layout(pick(ScreenSide.entries.toTypedArray(), "side"), pick(ScreenAlign.entries.toTypedArray(), "alignment"))
            ScreenMessage.TYPE_KEEP_ALIVE -> ScreenMessage.KeepAlive
            ScreenMessage.TYPE_BYE -> ScreenMessage.Bye(text())
            ScreenMessage.TYPE_UNPAIRED -> ScreenMessage.Unpaired
            ScreenMessage.TYPE_FOCUS -> ScreenMessage.Focus(pick(ScreenRole.entries.toTypedArray(), "screen"))
            ScreenMessage.TYPE_VIDEO -> ScreenMessage.Video(input.readInt(), input.readUnsignedByte(), input.readLong(), rest())
            ScreenMessage.TYPE_TOUCH -> {
                val stream = input.readInt()
                val action = input.readUnsignedByte()
                val index = input.readUnsignedByte()
                val count = input.readUnsignedByte()
                if (count > MAX_TOUCH_POINTS) throw ScreenProtocolException("a touch of $count fingers")
                ScreenMessage.Touch(stream, action, index, List(count) { TouchPoint(input.readUnsignedByte(), input.readFloat(), input.readFloat()) })
            }
            ScreenMessage.TYPE_STREAM_START -> ScreenMessage.StreamStart(input.readInt(), text(), input.readInt(), input.readInt())
            ScreenMessage.TYPE_STREAM_END -> ScreenMessage.StreamEnd(input.readInt())
            ScreenMessage.TYPE_WATCH -> ScreenMessage.Watch(input.readInt(), input.readUnsignedByte() != 0)
            ScreenMessage.TYPE_VIDEO_ACK -> ScreenMessage.VideoAck(input.readInt(), input.readLong())
            ScreenMessage.TYPE_KEY_FRAME -> ScreenMessage.KeyFrame(input.readInt())
            ScreenMessage.TYPE_TAKE_BACK -> ScreenMessage.TakeBack(input.readInt())
            ScreenMessage.TYPE_TERM_START ->
                ScreenMessage.TermStart(input.readInt(), text(), input.readUnsignedShort(), input.readUnsignedShort())
            ScreenMessage.TYPE_TERM_STATE -> ScreenMessage.TermState(
                input.readInt(), input.readInt(), input.readUnsignedShort(), input.readUnsignedShort(), rest(),
            )
            ScreenMessage.TYPE_TERM_OUTPUT -> ScreenMessage.TermOutput(input.readInt(), input.readInt(), rest())
            ScreenMessage.TYPE_TERM_TITLE -> ScreenMessage.TermTitle(input.readInt(), text())
            ScreenMessage.TYPE_TERM_END -> ScreenMessage.TermEnd(input.readInt())
            ScreenMessage.TYPE_TERM_SIZE -> ScreenMessage.TermSize(
                input.readInt(), input.readUnsignedShort(), input.readUnsignedShort(), input.readUnsignedShort(), input.readUnsignedShort(),
            )
            ScreenMessage.TYPE_TERM_INPUT -> ScreenMessage.TermInput(input.readInt(), rest())
            ScreenMessage.TYPE_TERM_ACK -> ScreenMessage.TermAck(input.readInt(), input.readInt(), input.readLong())
            ScreenMessage.TYPE_TERM_TOUCHED -> ScreenMessage.TermTouched(input.readInt())
            ScreenMessage.TYPE_POINTER ->
                ScreenMessage.Pointer(input.readInt(), input.readFloat(), input.readFloat(), input.readUnsignedByte() != 0)
            ScreenMessage.TYPE_ALIGN -> ScreenMessage.Align(
                input.readInt(), input.readUnsignedByte() != 0, pick(ScreenSide.entries.toTypedArray(), "side"),
                input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
            )
            ScreenMessage.TYPE_ALIGN_INPUT -> ScreenMessage.AlignInput(input.readInt(), input.readFloat(), input.readFloat(), input.readFloat())
            ScreenMessage.TYPE_ALIGN_ACTION -> ScreenMessage.AlignAction(input.readInt(), pick(AlignStep.entries.toTypedArray(), "alignment step"))
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
