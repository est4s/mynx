package io.github.est4s.terminal

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import io.github.est4s.terminal.core.ACTION_DOWN
import io.github.est4s.terminal.core.ACTION_POINTER_DOWN
import io.github.est4s.terminal.core.ACTION_POINTER_UP
import io.github.est4s.terminal.core.ACTION_UP
import io.github.est4s.terminal.core.ScreenCasting
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ScreenTarget
import io.github.est4s.terminal.core.SentScreen
import io.github.est4s.terminal.core.VIDEO_CONFIG
import io.github.est4s.terminal.core.VIDEO_KEY
import io.github.est4s.terminal.core.WebApp
import io.github.est4s.terminal.core.WebAppOpened
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.encoderSizes
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The host's side of a sent screen on Android (core's [ScreenCasting];
 * spike #117 found what works): a private VirtualDisplay
 * (PRESENTATION | OWN_CONTENT_ONLY, no MediaProjection needed) with the
 * app's [SentAppScreen] on it, and an H.264 encoder (MediaCodec, the
 * low-latency keys where the encoder takes them) feeding on the display
 * while the other phone watches. Frames go to [encoded] from the
 * encoder's thread. Main thread, but [keyFrame].
 */
class ScreenCaster(
    private val context: Context,
    private val handler: Handler,
    private val apps: () -> WebApps,
    private val openApp: (String, (WebApp) -> String?, (Result<WebAppOpened>) -> Unit) -> () -> Unit,
    /** The app's screen comes back to this phone. */
    private val backHere: (String) -> Unit,
    /** The app's screen is gone for good (its service may stop). */
    private val gone: (String) -> Unit,
    private val encoded: (Int, Int, Long, ByteArray) -> Unit,
    /** Typing on this phone goes to a sent screen's field now ([ScreenKeysActivity]), or no longer (null). */
    private val typing: (SentScreen?, Boolean) -> Unit,
) : ScreenCasting {
    private class Cast(val screen: SentScreen, val display: VirtualDisplay, val view: SentAppScreen) {
        var cancelOpen: (() -> Unit)? = null
        @Volatile var encoder: MediaCodec? = null
        var surface: Surface? = null
        @Volatile var running = false
        var drain: Thread? = null
        var downTime = 0L
    }

    private val casts = ConcurrentHashMap<Int, Cast>()
    private var typedTo: Int? = null
    private val keys by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /** Whether app [name] is on the other phone now. */
    fun isSent(name: String): Boolean = casts.values.any { it.screen.target.name == name }

    override fun describe(target: ScreenTarget): Result<String> {
        val app = runCatching { apps().find(target.name) }.getOrNull()
            ?: return Result.failure(Exception("no app named ${target.name} (mynx app list shows them)"))
        return Result.success(app.label)
    }

    // The first size the AVC encoder takes, from the other phone's own down.
    override fun streamSize(width: Int, height: Int): Pair<Int, Int> {
        val sizes = encoderSizes(width, height)
        val video = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
                ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        }.getOrNull() ?: return sizes.first()
        return sizes.firstOrNull { (w, h) -> video.isSizeSupported(w, h) } ?: sizes.last()
    }

    override fun open(screen: SentScreen, density: Int): String? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return "no display manager"
        // Off this phone's screen first, keeping the app's service (it opens there next).
        WebAppActivity.moveAway(screen.target.name)
        val display = try {
            dm.createVirtualDisplay(
                "mynx-screen-${screen.id}", screen.width, screen.height, density, null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: return "Android gave no display"
        } catch (e: RuntimeException) {
            return "Android gave no display: ${e.message ?: e.javaClass.simpleName}"
        }
        val view = SentAppScreen(context, display.display, apps())
        try {
            view.show()
        } catch (e: RuntimeException) {
            display.release()
            return "its screen wouldn't show: ${e.message ?: e.javaClass.simpleName}"
        }
        val cast = Cast(screen, display, view)
        casts[screen.id] = cast
        cast.cancelOpen = openApp(
            screen.target.name,
            { found ->
                view.load(found)
                null
            },
            { result -> result.exceptionOrNull()?.let { view.showStatus(it.message ?: it.toString()) } },
        )
        return null
    }

    override fun close(screen: SentScreen, back: Boolean) {
        val cast = casts.remove(screen.id) ?: return
        stopEncoder(cast)
        runCatching { cast.cancelOpen?.invoke() }
        runCatching { cast.view.dismiss() }
        runCatching { cast.display.release() }
        if (typedTo == screen.id) {
            typedTo = null
            typing(null, false)
        }
        if (back) backHere(screen.target.name) else gone(screen.target.name)
    }

    override fun encode(screen: SentScreen, on: Boolean, fps: Int, kbps: Int) {
        val cast = casts[screen.id] ?: return
        if (!on) return stopEncoder(cast)
        if (cast.encoder != null) return
        try {
            startEncoder(cast, fps, kbps)
        } catch (e: Exception) {
            stopEncoder(cast)
            cast.view.showStatus("The encoder didn't start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startEncoder(cast: Cast, fps: Int, kbps: Int) {
        val screen = cast.screen
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val cbr = runCatching {
            encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
                .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }.getOrDefault(false)
        var configured = false
        for (lowLatency in listOf(true, false)) {
            try {
                encoder.configure(encoderFormat(screen.width, screen.height, fps, kbps, lowLatency, cbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
                break
            } catch (e: Exception) {
                encoder.reset()
            }
        }
        if (!configured) {
            encoder.release()
            error("it took no format for ${screen.width}x${screen.height}")
        }
        val surface = encoder.createInputSurface()
        encoder.start()
        cast.encoder = encoder
        cast.surface = surface
        cast.running = true
        cast.display.surface = surface
        cast.drain = thread(name = "mynx-screen-encode", isDaemon = true) { drain(cast, encoder) }
    }

    private fun encoderFormat(w: Int, h: Int, fps: Int, kbps: Int, lowLatency: Boolean, cbr: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen still sends 10 frames a second, so a lost one is soon made good.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= 29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            if (lowLatency) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

    private fun drain(cast: Cast, encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (cast.running) {
                val index = encoder.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val data = ByteArray(info.size)
                encoder.getOutputBuffer(index)?.let { buffer ->
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                }
                val flags = info.flags
                val pts = info.presentationTimeUs
                encoder.releaseOutputBuffer(index, false)
                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                var ours = 0
                if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) ours = ours or VIDEO_KEY
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) ours = ours or VIDEO_CONFIG
                if (data.isNotEmpty()) encoded(cast.screen.id, ours, pts, data)
            }
        } catch (e: IllegalStateException) {
            // Stopped under us: the stream is going.
        }
    }

    private fun stopEncoder(cast: Cast) {
        val encoder = cast.encoder ?: return
        cast.running = false
        cast.encoder = null
        runCatching { cast.display.surface = null }
        // Its dequeue waits at most 100 ms: then the encoder can go.
        runCatching { cast.drain?.join(300) }
        cast.drain = null
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { cast.surface?.release() }
        cast.surface = null
    }

    override fun keyFrame(screen: SentScreen) {
        val encoder = casts[screen.id]?.encoder ?: return
        runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    override fun touch(screen: SentScreen, touch: ScreenMessage.Touch) {
        val cast = casts[screen.id] ?: return
        val count = touch.points.size
        if (count == 0) return
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = touch.points[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = touch.points[i].x
                y = touch.points[i].y
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        if (touch.action == ACTION_DOWN) cast.downTime = now
        val action = when (touch.action) {
            ACTION_POINTER_DOWN, ACTION_POINTER_UP -> touch.action or (touch.index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> touch.action
        }
        val event = MotionEvent.obtain(cast.downTime, now, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            cast.view.touch(event)
        } finally {
            event.recycle()
        }
        // A tap that put a field in focus: this phone's keyboard types into it.
        if (touch.action == ACTION_UP && typedTo == screen.id) {
            handler.postDelayed({ if (typedTo == screen.id && casts[screen.id] === cast) typing(screen, cast.view.editing()) }, 150)
        }
    }

    override fun typingTo(screen: SentScreen?) {
        typedTo = screen?.id
        if (screen == null) typing(null, false)
    }

    /** Text typed on this phone for the sent screen typed to. */
    fun type(text: String) {
        val cast = typedTo?.let(casts::get) ?: return
        val events = keys.getEvents(text.toCharArray())
        if (events != null) {
            events.forEach(cast.view::key)
        } else {
            // No key for it (an emoji): as one event with the characters, which fields take.
            @Suppress("DEPRECATION")
            cast.view.key(KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
    }

    /** A key (Delete, Enter, the arrows) pressed on this phone for the sent screen typed to. */
    fun key(event: KeyEvent) {
        val cast = typedTo?.let(casts::get) ?: return
        cast.view.key(event)
    }

    /** Presses and lets go of [code] on the sent screen typed to. */
    fun press(code: Int) {
        val now = SystemClock.uptimeMillis()
        key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        key(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    /** Everything goes (the app stops). */
    fun closeAll() {
        casts.values.toList().forEach { close(it.screen, back = false) }
    }
}
