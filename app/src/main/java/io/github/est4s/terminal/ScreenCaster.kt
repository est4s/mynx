package io.github.est4s.terminal

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.est4s.terminal.core.ACTION_DOWN
import io.github.est4s.terminal.core.ACTION_POINTER_DOWN
import io.github.est4s.terminal.core.ACTION_POINTER_UP
import io.github.est4s.terminal.core.ACTION_UP
import io.github.est4s.terminal.core.AlignStep
import io.github.est4s.terminal.core.ScreenCasting
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ScreenTarget
import io.github.est4s.terminal.core.SentScreen
import io.github.est4s.terminal.core.WebApp
import io.github.est4s.terminal.core.WebAppOpened
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.encoderSizes
import java.util.concurrent.ConcurrentHashMap

/**
 * The host's side of a sent screen on Android (core's [ScreenCasting];
 * spike #117 found what works): a private VirtualDisplay
 * (PRESENTATION | OWN_CONTENT_ONLY, no MediaProjection needed) with the
 * app's [SentAppScreen] on it, and a [ScreenEncoder] feeding on the
 * display while the other phone watches. The desktop goes to [desktop]
 * instead: the X server draws its part into the encoder itself. Frames
 * go to [encoded] from the encoder's thread. Main thread, but [keyFrame].
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
    /** The desktop's part on the other phone. */
    private val desktop: DesktopCaster,
) : ScreenCasting {
    private class Cast(val screen: SentScreen, val display: VirtualDisplay, val view: SentAppScreen) {
        var cancelOpen: (() -> Unit)? = null
        @Volatile var encoder: ScreenEncoder? = null
        var downTime = 0L
    }

    private val casts = ConcurrentHashMap<Int, Cast>()
    private var typedTo: Int? = null
    private val keys by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /** Whether app [name] is on the other phone now. */
    fun isSent(name: String): Boolean = casts.values.any { it.screen.target.name == name }

    override fun describe(target: ScreenTarget): Result<String> {
        if (target == ScreenTarget.Desktop) return desktop.describe()
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
        if (screen.target == ScreenTarget.Desktop) return desktop.open(screen, density)
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
        if (screen.target == ScreenTarget.Desktop) return desktop.close(screen)
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
        if (screen.target == ScreenTarget.Desktop) return desktop.encode(screen, on, fps, kbps)
        val cast = casts[screen.id] ?: return
        if (!on) return stopEncoder(cast)
        if (cast.encoder != null) return
        try {
            val encoder = ScreenEncoder.start(screen.width, screen.height, fps, kbps) { flags, pts, data ->
                encoded(screen.id, flags, pts, data)
            }
            cast.encoder = encoder
            cast.display.surface = encoder.surface
        } catch (e: Exception) {
            stopEncoder(cast)
            cast.view.showStatus("The encoder didn't start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopEncoder(cast: Cast) {
        val encoder = cast.encoder ?: return
        cast.encoder = null
        runCatching { cast.display.surface = null }
        encoder.stop()
    }

    override fun keyFrame(screen: SentScreen) {
        if (screen.target == ScreenTarget.Desktop) return desktop.keyFrame()
        casts[screen.id]?.encoder?.keyFrame()
    }

    // Aligning is the desktop's alone (its picture's place on the other phone).
    override fun alignInput(screen: SentScreen, input: ScreenMessage.AlignInput) {
        if (screen.target == ScreenTarget.Desktop) desktop.alignInput(screen, input)
    }

    override fun alignAction(screen: SentScreen, step: AlignStep) {
        if (screen.target == ScreenTarget.Desktop) desktop.alignStep(step)
    }

    override fun touch(screen: SentScreen, touch: ScreenMessage.Touch) {
        if (screen.target == ScreenTarget.Desktop) return desktop.touch(screen, touch)
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
        val toDesktop = screen != null && screen.target == ScreenTarget.Desktop
        typedTo = if (screen != null && !toDesktop) screen.id else null
        // The app's bar closes first: the desktop may open the same bar for itself.
        if (typedTo == null) typing(null, false)
        desktop.typingTo(if (toDesktop) screen else null)
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
        desktop.closeAll()
    }
}
