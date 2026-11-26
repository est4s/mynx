package io.github.est4s.terminal

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import io.github.est4s.terminal.core.ACTION_DOWN
import io.github.est4s.terminal.core.DesktopGestures
import io.github.est4s.terminal.core.DesktopKeyboard
import io.github.est4s.terminal.core.DesktopOther
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.SentScreen
import io.github.est4s.terminal.core.Settings
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.desktopGestures
import io.github.est4s.terminal.core.desktopScale
import io.github.est4s.terminal.core.desktopTouch

// How often the desktop's Log says what the other phone's encoder gave.
private const val TALLY_MS = 5000L

/** What the desktop is called on the other phone and in Home's lists. */
const val DESKTOP_TITLE = "Linux desktop"

/**
 * The desktop's part on the other phone, on the host (the extended
 * screen; core's [io.github.est4s.terminal.core.DesktopScreens] lays it
 * out): sending it adds that part to the X screen ([DesktopLink.other]:
 * a monitor of its own, or the second half of one, `screen-desktop`,
 * where `screen-place` says), and while the other phone watches, the X
 * server draws it straight into a [ScreenEncoder]'s surface (our patch's
 * second surface), no display of ours between. Its touches go through
 * gestures of their own (`desktop-touch`) on the same pointer as this
 * phone's ([DesktopLink.pointer]), so it crosses between them; where the
 * pointer is on its part goes to [pointerTo] (the X server draws no
 * cursor). A click there while it's typed to and the desktop isn't on
 * this phone's screen opens the typing bar ([typing]); its keys go to X
 * ([type], [press], [key]). Main thread, but [keyFrame].
 */
class DesktopCaster(
    context: Context,
    private val handler: Handler,
    private val link: DesktopLink,
    /** The settings, or null when they can't be read now (then the last ones stay). */
    private val settings: () -> Settings?,
    private val encoded: (Int, Int, Long, ByteArray) -> Unit,
    private val pointerTo: (Int, Float, Float, Boolean) -> Unit,
    /** This phone's keyboard types into X for the other phone now (the typing bar), or no longer. */
    private val typing: (Boolean) -> Unit,
    /** Whether the desktop's view shows on this phone (it types into X already). */
    private val shownHere: () -> Boolean,
    /** What's sent changed: Home's lists and the desktop's bar follow. */
    private val changed: () -> Unit,
) {
    private var screen: SentScreen? = null
    private var density = 0
    @Volatile private var encoder: ScreenEncoder? = null
    private var gestures: DesktopGestures? = null
    private var touchMode = DesktopTouch.TOUCHPAD
    private var typed = false
    private var last: Settings? = null
    private val keys = DesktopKeyboard()
    // What the encoder gave, for the desktop's Log (the encoder's thread counts, the main thread tells).
    private val frames = AtomicInteger()
    private val bytes = AtomicLong()
    @Volatile private var firstTold = false
    private val tally = object : Runnable {
        override fun run() {
            if (encoder == null) return
            val n = frames.getAndSet(0)
            val b = bytes.getAndSet(0)
            link.note("the other phone's encoder: $n frames, ${b / 1024} KiB in 5 s")
            handler.postDelayed(this, TALLY_MS)
        }
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat() / desktopScale(context.resources.displayMetrics.density)

    private val longPress = Runnable {
        val out = gestures?.timeout(SystemClock.uptimeMillis()).orEmpty()
        if (out.isNotEmpty()) pointerEvents(out)
    }

    init {
        link.otherWatcher = { follow() }
    }

    /** The desktop's stream on the other phone, while it's there. */
    val sent: SentScreen? get() = screen

    fun describe(): Result<String> =
        if (link.connected) Result.success(DESKTOP_TITLE) else Result.failure(Exception("the desktop isn't running (mynx desktop start starts it)"))

    /** Adds the other phone's part, [screen]'s size at [density] (dpi); null, or why not. */
    fun open(screen: SentScreen, density: Int): String? {
        if (!link.connected) return "the desktop isn't running"
        this.screen = screen
        this.density = density
        relayout()
        changed()
        return null
    }

    /** `screen-place` or `screen-desktop` may have changed: the other phone's part moves. */
    fun relayout() {
        val s = settings() ?: last ?: return link.note("the settings can't be read: the other phone's part stays where it is")
        last = s
        val sent = screen
        link.other(sent?.let { DesktopOther(it.width, it.height, density, it.width, it.height) }, s.screenPlace, s.screenDesktop)
        follow()
    }

    // The layout or the pointer moved: the other phone's gestures stay on its part, and its pointer is drawn where it is.
    private fun follow() {
        val s = screen ?: return
        val rect = link.screens.otherRect
        gestures?.let { g -> if (rect != null && g.monitor != rect) g.monitor(rect) }
        val at = link.screens.toStream(link.pointer.x, link.pointer.y)
        pointerTo(s.id, at?.first ?: 0f, at?.second ?: 0f, at != null)
    }

    fun close(screen: SentScreen) {
        if (this.screen?.id != screen.id) return
        stopEncoder()
        handler.removeCallbacks(longPress)
        gestures?.let { link.send(keys.pointer(it.cancel())) }
        link.send(keys.releaseAll())
        gestures = null
        this.screen = null
        typingTo(null)
        relayout()
        changed()
    }

    fun encode(screen: SentScreen, on: Boolean, fps: Int, kbps: Int) {
        if (this.screen?.id != screen.id) return
        if (!on) return stopEncoder()
        if (encoder != null) return
        try {
            firstTold = false
            frames.set(0)
            bytes.set(0)
            val started = ScreenEncoder.start(screen.width, screen.height, fps, kbps) { flags, pts, data ->
                frames.incrementAndGet()
                bytes.addAndGet(data.size.toLong())
                if (!firstTold) {
                    firstTold = true
                    handler.post { link.note("the other phone's encoder gave its first part (${data.size} bytes, flags $flags)") }
                }
                encoded(screen.id, flags, pts, data)
            }
            encoder = started
            link.note("the other phone's encoder started: ${screen.width}x${screen.height}, $fps fps, $kbps kbit/s")
            link.secondSurface(started.surface)
            follow()
            handler.removeCallbacks(tally)
            handler.postDelayed(tally, TALLY_MS)
        } catch (e: Exception) {
            link.note("the other phone's encoder didn't start: ${e.stackTraceToString().lines().take(4).joinToString(" / ")}")
            stopEncoder()
        }
    }

    private fun stopEncoder() {
        val e = encoder ?: return
        encoder = null
        handler.removeCallbacks(tally)
        link.note("the other phone's encoder stopped (it stopped watching, or the desktop went back)")
        // The X server lets go of the surface before this returns, then the encoder can go.
        link.secondSurface(null)
        e.stop()
    }

    fun keyFrame() {
        encoder?.keyFrame()
    }

    fun touch(screen: SentScreen, touch: ScreenMessage.Touch) {
        if (this.screen?.id != screen.id) return
        val g = gesturesFor(touch)
        pointerEvents(desktopTouch(g, touch, link.screens::fromStream, SystemClock.uptimeMillis()))
        handler.removeCallbacks(longPress)
        g.timeoutAt?.let { handler.postDelayed(longPress, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }

    // `desktop-touch` is read as a gesture starts, as the view reads it as it shows.
    private fun gesturesFor(touch: ScreenMessage.Touch): DesktopGestures {
        val now = gestures
        if (now != null && touch.action != ACTION_DOWN) return now
        val mode = (settings() ?: last)?.desktopTouch ?: touchMode
        if (now != null && mode == touchMode) return now
        now?.let { link.send(keys.pointer(it.cancel())) }
        touchMode = mode
        return desktopGestures(mode, slop, link.pointer).also { g ->
            link.screens.otherRect?.let(g::monitor)
            gestures = g
        }
    }

    private fun pointerEvents(events: List<X11Input>) {
        if (events.isEmpty()) return
        link.send(keys.pointer(events))
        // A click on the other phone while it's typed to: this phone's keyboard types there (into X).
        if (typed && !shownHere() && events.any { it is X11Input.Button && it.down }) typing(true)
    }

    /** Typing goes to the desktop on the other phone ([to]) or no longer. */
    fun typingTo(to: SentScreen?) {
        val now = to != null && to.id == screen?.id
        if (now == typed) return
        typed = now
        if (!now) typing(false)
    }

    /** Text typed on this phone's bar, for X. */
    fun type(text: String) = link.send(keys.typed(text))

    /** [code] (an Android key code: Delete, Enter) pressed and let go, for X. */
    fun press(code: Int) {
        link.send(keys.key(code, 0, 0, down = true).orEmpty() + keys.key(code, 0, 0, down = false).orEmpty())
    }

    /** A key event on this phone's bar (an arrow, Esc, Tab), for X. */
    fun key(event: KeyEvent) {
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return
        }
        link.send(keys.key(event.keyCode, event.scanCode, event.unicodeChar, down, event.repeatCount).orEmpty())
    }

    /** Everything goes (the app stops). */
    fun closeAll() {
        screen?.let(::close)
    }
}
