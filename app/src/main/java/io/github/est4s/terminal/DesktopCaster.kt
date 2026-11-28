package io.github.est4s.terminal

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import io.github.est4s.terminal.core.ACTION_DOWN
import io.github.est4s.terminal.core.AlignStep
import io.github.est4s.terminal.core.DesktopAlignKey
import io.github.est4s.terminal.core.DesktopGestures
import io.github.est4s.terminal.core.DesktopKeyboard
import io.github.est4s.terminal.core.DesktopOther
import io.github.est4s.terminal.core.DesktopRuler
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.ScreenAlignment
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ScreenSide
import io.github.est4s.terminal.core.SentScreen
import io.github.est4s.terminal.core.Settings
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.alignNudge
import io.github.est4s.terminal.core.alignText
import io.github.est4s.terminal.core.desktopGestures
import io.github.est4s.terminal.core.desktopScale
import io.github.est4s.terminal.core.desktopTouch

// How often the desktop's Log says what the other phone's encoder gave.
private const val TALLY_MS = 5000L

/** What the desktop is called on the other phone and in Home's lists. */
const val DESKTOP_TITLE = "Linux desktop"

/** Aligning both phones' pictures, as this phone's desktop view shows it: its ruler along the seam ([side]: where the other phone sits), the values. */
data class DesktopAligning(val side: ScreenSide, val ruler: DesktopRuler, val text: String, val other: String)

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
 * ([type], [press], [key]). Its picture there is sized and placed as
 * core's desktopFit says (an X pixel the same size on both phones, bands
 * on the taller), lined up by the pair's alignment ([alignment]), which
 * Align screens fine-tunes ([startAlign]: rulers on both phones, drags
 * and pinches on the other phone, keys here; Done keeps it
 * ([saveAlignment]), Cancel puts it back). Main thread, but [keyFrame].
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
    /** The other phone's screen as it said (its pixels and dpi), while linked. */
    private val peerScreen: () -> ScreenMessage.ScreenInfo?,
    /** The pair's alignment, as kept. */
    private val alignment: () -> ScreenAlignment,
    /** Keeps the pair's alignment (Done); why not, or null. */
    private val saveAlignment: (ScreenAlignment) -> String?,
    /** The ruler (or its end) to the other phone. */
    private val sendAlign: (ScreenMessage.Align) -> Unit,
    /** The other phone's name. */
    private val otherName: () -> String,
) {
    // While aligning: the alignment it started from (Cancel goes back to it).
    private var alignFrom: ScreenAlignment? = null
    // The ruler last sent to the other phone, so each change goes once.
    private var rulerSent: ScreenMessage.Align? = null
    private var screen: SentScreen? = null
    private var density = 0
    @Volatile private var encoder: ScreenEncoder? = null
    // What the X server draws into: the bridge's surface, which draws into the encoder's (EncoderBridge).
    @Volatile private var bridge: EncoderBridge? = null
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
            val drawn = bridge?.frames?.getAndSet(0)
            val path = if (drawn != null) "the bridge got $drawn frames from the X server, " else "straight into the encoder: "
            link.note("the other phone's part: ${path}the encoder gave $n frames, ${b / 1024} KiB in 5 s")
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
        link.align(alignment())
        relayout()
        changed()
        return null
    }

    /**
     * The other phone turned (or its screen changed): its part is laid out
     * again for its new shape, the video [screen]'s new size (its encoder
     * stopped meanwhile and started again by ScreenShare, when watched).
     * Aligning goes on, its rulers redrawn for the new layout.
     */
    fun resize(screen: SentScreen, density: Int) {
        val was = this.screen ?: return
        if (was.id != screen.id) return
        this.screen = screen
        this.density = density
        val info = peerScreen()
        if (was.width != screen.width || was.height != screen.height) {
            link.note("${otherName()} turned: its screen ${info?.width}x${info?.height}, its video ${screen.width}x${screen.height}")
        }
        relayout()
    }

    /** `screen-place` or `screen-desktop` may have changed: the other phone's part moves. */
    fun relayout() {
        val s = settings() ?: last ?: return link.note("the settings can't be read: the other phone's part stays where it is")
        last = s
        val sent = screen
        val info = peerScreen()
        link.other(
            sent?.let {
                // Its screen as it said; the video's size until it has (the video keeps its shape).
                val w = info?.width?.takeIf { v -> v > 0 } ?: it.width
                val h = info?.height?.takeIf { v -> v > 0 } ?: it.height
                DesktopOther(w, h, info?.xdpi ?: 0f, info?.ydpi ?: 0f, it.width, it.height, density)
            },
            s.screenPlace, s.screenDesktop,
        )
        follow()
    }

    /** The pair's alignment was kept anew (`mynx screen align`): applied, unless aligning is under way here. */
    fun alignmentSaved(to: ScreenAlignment) {
        if (alignFrom != null || screen == null) return
        link.align(to)
        follow()
    }

    /** Aligning, as this phone's view shows it; null when it isn't. */
    val aligning: DesktopAligning?
        get() {
            if (alignFrom == null) return null
            val fit = link.screens.fit ?: return null
            val side = last?.screenPlace ?: ScreenSide.RIGHT
            return DesktopAligning(side, fit.hostRuler, alignText(link.screens.alignment), otherName())
        }

    /** Align screens: rulers on both phones along the seam; null, or why not. */
    fun startAlign(): String? {
        if (screen == null) return "the desktop isn't on ${otherName()}"
        if (link.screens.fit == null) return "the desktop isn't laid out on ${otherName()} yet"
        if (alignFrom == null) alignFrom = link.screens.alignment
        link.note("aligning with ${otherName()}: ${alignText(link.screens.alignment)}")
        follow()
        changed()
        return null
    }

    private fun alignTo(to: ScreenAlignment) {
        link.align(to)
        follow()
    }

    /** A key on this phone while aligning. */
    fun alignKey(key: DesktopAlignKey) {
        if (alignFrom == null) return
        when (key) {
            DesktopAlignKey.DONE -> alignStep(AlignStep.DONE)
            DesktopAlignKey.RESET -> alignStep(AlignStep.RESET)
            DesktopAlignKey.CANCEL -> alignStep(AlignStep.CANCEL)
            else -> alignTo(alignNudge(link.screens.alignment, key))
        }
    }

    /** Done (keeps it), Reset (the default, still aligning) or Cancel (back as it was), from either phone. */
    fun alignStep(step: AlignStep) {
        val from = alignFrom ?: return
        when (step) {
            AlignStep.RESET -> return alignTo(ScreenAlignment())
            AlignStep.DONE -> {
                val now = link.screens.alignment
                saveAlignment(now)?.let { why -> link.note("the alignment wasn't kept: $why") }
                    ?: link.note("aligned with ${otherName()}: ${alignText(now)}")
            }
            AlignStep.CANCEL -> link.align(from)
        }
        endAlign()
    }

    private fun endAlign() {
        if (alignFrom == null) return
        alignFrom = null
        follow()
        changed()
    }

    // The layout or the pointer moved: the other phone's gestures stay on its part, its picture where
    // the layout puts it, its pointer drawn where it is, and its ruler (while aligning) where its X lines are.
    private fun follow() {
        val s = screen ?: return
        val rect = link.screens.otherRect
        gestures?.let { g -> if (rect != null && g.monitor != rect) g.monitor(rect) }
        bridge?.picture = link.screens.fit?.picture
        val at = link.screens.toStream(link.pointer.x, link.pointer.y)
        pointerTo(s.id, at?.first ?: 0f, at?.second ?: 0f, at != null)
        sendRuler(s)
    }

    private fun sendRuler(s: SentScreen) {
        val fit = link.screens.fit
        val a = link.screens.alignment
        val side = last?.screenPlace ?: ScreenSide.RIGHT
        val m = if (alignFrom != null && fit != null) {
            ScreenMessage.Align(s.id, true, side, fit.otherRuler.origin, fit.otherRuler.step, a.offset, a.scale)
        } else {
            ScreenMessage.Align(s.id, false, side, 0f, 0f, a.offset, a.scale)
        }
        // Once for each change; an end only after a start.
        if (m == rulerSent || (!m.on && rulerSent?.on != true)) return
        rulerSent = m
        sendAlign(m)
    }

    fun close(screen: SentScreen) {
        if (this.screen?.id != screen.id) return
        // Aligning ends with it, as Cancel.
        alignFrom?.let { from -> link.align(from) }
        alignFrom = null
        rulerSent = null
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
            // The X server's EGL context can't draw into an encoder's surface (EGL_BAD_MATCH): a bridge does.
            val through = try {
                EncoderBridge.start(started.surface, screen.width, screen.height).also {
                    bridge = it
                    link.note("the other phone's part goes through the bridge (a SurfaceTexture of ours, into the encoder)")
                }.input
            } catch (e: Exception) {
                link.note("the bridge didn't start (${e.message}): the X server draws straight into the encoder (the picture fills the video: no bands, so touches there may be off)")
                started.surface
            }
            link.secondSurface(through)
            bridge?.picture = link.screens.fit?.picture
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
        // The X server lets go of the surface before this returns, then the bridge and the encoder can go.
        link.secondSurface(null)
        bridge?.release()
        bridge = null
        e.stop()
    }

    fun keyFrame() {
        encoder?.keyFrame()
        // An idle desktop sends nothing new: the last frame again carries the key frame.
        bridge?.repeat()
    }

    /** A drag or pinch on the other phone while aligning: its picture moves, its ruler with it. */
    fun alignInput(screen: SentScreen, input: ScreenMessage.AlignInput) {
        if (this.screen?.id != screen.id || alignFrom == null) return
        alignTo(link.screens.aligned(input))
    }

    fun touch(screen: SentScreen, touch: ScreenMessage.Touch) {
        if (this.screen?.id != screen.id) return
        // While aligning, the other phone's touches are its picture's (they come as AlignInput).
        if (alignFrom != null) return
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
