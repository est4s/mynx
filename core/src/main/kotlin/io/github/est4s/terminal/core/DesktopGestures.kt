package io.github.est4s.terminal.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** A touch lasting at most this long, not moving, is a tap (touchpad). */
const val TAP_MS = 250L
/** A finger held still this long is a long press. */
const val LONG_PRESS_MS = 500L
/** A touch this soon after a tap that then moves is a drag (touchpad). */
const val DOUBLE_TAP_MS = 300L
/** Fingers moving this far (X pixels) scroll one wheel notch. */
const val SCROLL_PX_PER_NOTCH = 40f
/** A fling's speed falls to 1/e in this long (ms): it goes on about this many ms' worth of its first speed. */
const val FLING_DECAY_MS = 325f
/** A finger leaving at least this fast (X px a ms) flings (direct). */
const val FLING_MIN_SPEED = 0.5f
/** No fling starts faster than this (X px a ms). */
const val FLING_MAX_SPEED = 8f
/** A fling stops once this slow (X px a ms). */
const val FLING_STOP_SPEED = 0.05f
/** How far back (ms) a finger's speed is measured when it leaves. */
const val FLING_WINDOW_MS = 100L
/** The most our view zooms the X screen (direct). */
const val DESKTOP_MOST_ZOOM = 4f
// A scroll this much more along one axis than the other stays on it.
private const val AXIS_LOCK_RATIO = 2f
// Touchpad acceleration: 1× up to the slow speed, rising to the most at the fast one (X px a ms).
private const val SLOW_SPEED = 0.5f
private const val FAST_SPEED = 2f
private const val MOST_GAIN = 2.5f
private const val LEFT = 1
private const val MIDDLE = 2
private const val RIGHT = 3

/** A finger on the desktop's screen, in X pixels (of the view: see [DesktopZoom]). */
data class Finger(val id: Int, val x: Float, val y: Float)

/**
 * How our view shows the X screen: [scale] times its size, its corner at
 * [x], [y]; all in X pixels of the unzoomed view. The X server knows
 * nothing of it: the view scales its surface.
 */
data class DesktopZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun screenX(viewX: Float) = (viewX - x) / scale
    fun screenY(viewY: Float) = (viewY - y) / scale
    fun viewX(screenX: Float) = x + screenX * scale
    fun viewY(screenY: Float) = y + screenY * scale

    /**
     * Two fingers that were at [a0], [b0] and are at [a1], [b1]: zoomed by
     * how far apart they went, the point under their middle following
     * it, on a [width] × [height] screen, never past its edges.
     */
    fun pinch(a0: Finger, b0: Finger, a1: Finger, b1: Finger, width: Int, height: Int): DesktopZoom {
        val before = hypot(a0.x - b0.x, a0.y - b0.y)
        val after = hypot(a1.x - b1.x, a1.y - b1.y)
        val ratio = if (before < 1f) 1f else after / before
        val s = (scale * ratio).coerceIn(1f, DESKTOP_MOST_ZOOM)
        if (s == 1f) return DesktopZoom()
        val sx = screenX((a0.x + b0.x) / 2)
        val sy = screenY((a0.y + b0.y) / 2)
        val nx = (a1.x + b1.x) / 2 - sx * s
        val ny = (a1.y + b1.y) / 2 - sy * s
        return DesktopZoom(s, nx.coerceIn(width - width * s, 0f), ny.coerceIn(height - height * s, 0f))
    }
}

/**
 * Turns touches on the desktop into the X server's pointer events. Feed
 * it every finger down and up, and moves with all the fingers down, in
 * X pixels of the view; [slop] (X pixels) tells a move from a tap. A long
 * press needs [timeout] called at [timeoutAt], a fling [fling] on every
 * frame while [flinging]. Main thread.
 */
sealed class DesktopGestures(private val slop: Float) {
    /** Where the pointer is, in X pixels: our own, so the view can draw it. */
    var pointerX = 0f
        protected set
    var pointerY = 0f
        protected set
    protected var screenWidth = 1
        private set
    protected var screenHeight = 1
        private set
    private var started = false

    /** How the view shows the X screen; only direct mode zooms. */
    var zoom = DesktopZoom()
        protected set

    /** A long press is being held (the view buzzes when this turns on). */
    open val holding: Boolean get() = false

    /** A fling goes on: call [fling] each frame. */
    open val flinging: Boolean get() = false

    private val fingers = LinkedHashMap<Int, Finger>()
    private val starts = HashMap<Int, Finger>()
    protected var startMs = 0L
        private set
    /** The most fingers down at once in this gesture. */
    protected var most = 0
        private set
    /** Whether a finger has gone past the slop in this gesture. */
    protected var moved = false
        private set
    /** When a long press fires, if one is due. */
    var timeoutAt: Long? = null
        protected set

    /** The X screen's size; the pointer is kept on it, and a new size shows it whole. */
    fun screen(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w != screenWidth || h != screenHeight) zoom = DesktopZoom()
        screenWidth = w
        screenHeight = h
        if (!started) {
            started = true
            pointerX = (screenWidth / 2).toFloat()
            pointerY = (screenHeight / 2).toFloat()
        }
        pointerX = clampX(pointerX)
        pointerY = clampY(pointerY)
    }

    /** A mouse pointing at [x], [y] on the X screen: the pointer goes there (kept on the screen). */
    fun moveTo(x: Float, y: Float): X11Input.Move {
        pointerX = clampX(x)
        pointerY = clampY(y)
        return X11Input.Move(pointerX, pointerY)
    }

    protected fun clampX(x: Float) = x.coerceIn(0f, (screenWidth - 1).toFloat())
    protected fun clampY(y: Float) = y.coerceIn(0f, (screenHeight - 1).toFloat())

    fun down(id: Int, x: Float, y: Float, ms: Long): List<X11Input> {
        val finger = Finger(id, x, y)
        val first = fingers.isEmpty()
        fingers[id] = finger
        starts[id] = finger
        if (first) {
            startMs = ms
            most = 1
            moved = false
            timeoutAt = ms + LONG_PRESS_MS
            return firstDown(finger, ms)
        }
        most = maxOf(most, fingers.size)
        timeoutAt = null
        return emptyList()
    }

    fun move(now: List<Finger>, ms: Long): List<X11Input> {
        val before = fingers.toMap()
        for (f in now) if (f.id in fingers) fingers[f.id] = f
        if (!moved && fingers.values.any { f -> starts[f.id]?.let { hypot(f.x - it.x, f.y - it.y) > slop } == true }) {
            moved = true
            timeoutAt = null
        }
        if (most == 1 && fingers.size == 1) {
            val f = fingers.values.first()
            val last = before[f.id] ?: return emptyList()
            return if (moved) oneFinger(f, f.x - last.x, f.y - last.y, ms) else emptyList()
        }
        if (fingers.size != 2 || !moved) return emptyList()
        val (a, b) = fingers.values.take(2)
        return twoFingers(before[a.id] ?: a, before[b.id] ?: b, a, b)
    }

    fun up(id: Int, ms: Long): List<X11Input> {
        val finger = fingers.remove(id) ?: return emptyList()
        starts.remove(id)
        if (fingers.isNotEmpty()) return emptyList()
        timeoutAt = null
        val tap = !moved && ms - startMs <= TAP_MS
        return when {
            most == 1 -> lastUp(finger, tap, ms)
            !tap -> letGo()
            most == 2 -> letGo() + click(RIGHT)
            else -> letGo() + click(MIDDLE)
        }
    }

    /** Android took the touch away, or the view goes: let go of anything held, stop a fling. */
    fun cancel(): List<X11Input> {
        fingers.clear()
        starts.clear()
        timeoutAt = null
        return letGo()
    }

    /** The long press, if it's due at [ms]. */
    fun timeout(ms: Long): List<X11Input> {
        val due = timeoutAt ?: return emptyList()
        if (ms < due || fingers.size != 1 || moved) return emptyList()
        timeoutAt = null
        return longPress()
    }

    /** The fling's next step at [ms] (a frame). */
    open fun fling(ms: Long): List<X11Input> = emptyList()

    protected fun click(button: Int) = listOf(X11Input.Button(button, true), X11Input.Button(button, false))

    /** Content follows the fingers, as on the phone: fingers going up scroll down. */
    protected fun scroll(dx: Float, dy: Float) = X11Input.Scroll(-dx / SCROLL_PX_PER_NOTCH, -dy / SCROLL_PX_PER_NOTCH)

    protected abstract fun firstDown(finger: Finger, ms: Long): List<X11Input>
    protected abstract fun oneFinger(finger: Finger, dx: Float, dy: Float, ms: Long): List<X11Input>
    /** Two fingers that were at [a0], [b0] moved to [a1], [b1]. */
    protected abstract fun twoFingers(a0: Finger, b0: Finger, a1: Finger, b1: Finger): List<X11Input>
    protected abstract fun lastUp(finger: Finger, tap: Boolean, ms: Long): List<X11Input>
    protected abstract fun longPress(): List<X11Input>
    protected abstract fun letGo(): List<X11Input>
}

/**
 * `desktop-touch = touchpad`: a finger moves the pointer (faster when it
 * moves fast), a tap is a left click, two fingers a right click, three a
 * middle click; two fingers moving scroll; a long press, or a tap and a
 * touch that moves, holds the left button while the finger drags.
 */
class TouchpadGestures(slop: Float) : DesktopGestures(slop) {
    private var dragging = false
    private var lastTapMs: Long? = null
    private var tapThenTouch = false
    private var lastMoveMs = 0L

    override fun firstDown(finger: Finger, ms: Long): List<X11Input> {
        tapThenTouch = lastTapMs?.let { ms - it <= DOUBLE_TAP_MS } == true
        lastMoveMs = ms
        return emptyList()
    }

    override fun oneFinger(finger: Finger, dx: Float, dy: Float, ms: Long): List<X11Input> {
        val out = mutableListOf<X11Input>()
        if (tapThenTouch && !dragging) {
            dragging = true
            out += X11Input.Button(LEFT, true)
        }
        val elapsed = (ms - lastMoveMs).coerceAtLeast(1)
        lastMoveMs = ms
        val gain = gain(hypot(dx, dy) / elapsed)
        pointerX = clampX(pointerX + dx * gain)
        pointerY = clampY(pointerY + dy * gain)
        out += X11Input.Move(pointerX, pointerY)
        return out
    }

    override fun twoFingers(a0: Finger, b0: Finger, a1: Finger, b1: Finger): List<X11Input> {
        val dx = (a1.x + b1.x - a0.x - b0.x) / 2
        val dy = (a1.y + b1.y - a0.y - b0.y) / 2
        return if (dx == 0f && dy == 0f) emptyList() else listOf(scroll(dx, dy))
    }

    private fun gain(speed: Float): Float = when {
        speed <= SLOW_SPEED -> 1f
        speed >= FAST_SPEED -> MOST_GAIN
        else -> 1f + (speed - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED) * (MOST_GAIN - 1f)
    }

    override fun lastUp(finger: Finger, tap: Boolean, ms: Long): List<X11Input> {
        if (dragging) {
            lastTapMs = null
            return letGo()
        }
        if (!tap) return emptyList()
        lastTapMs = ms
        return click(LEFT)
    }

    override fun longPress(): List<X11Input> {
        dragging = true
        return listOf(X11Input.Button(LEFT, true))
    }

    override fun letGo(): List<X11Input> {
        if (!dragging) return emptyList()
        dragging = false
        return listOf(X11Input.Button(LEFT, false))
    }
}

/**
 * `desktop-touch = direct`, as a phone's touchscreen: the pointer goes
 * where the finger is. A tap clicks there; a finger that moves scrolls
 * (the content follows it, smoothly, flinging on after a flick); a long
 * press is a right click, or, moved after, a drag with the left button
 * (to select); two fingers pinch to zoom our view of the screen and pan
 * it while zoomed, or tapped, right-click; three tapped middle-click.
 */
class DirectGestures(slop: Float) : DesktopGestures(slop) {
    private enum class Axis { BOTH, X, Y }

    private var held = false
    private var pressed = false
    // This touch stopped a fling: lifting it isn't a click.
    private var stopper = false
    private var axis: Axis? = null
    private var startX = 0f
    private var startY = 0f
    // The finger's recent positions (ms, view x, view y), for its speed when it leaves.
    private val track = ArrayDeque<Triple<Long, Float, Float>>()
    private var velocityX = 0f
    private var velocityY = 0f
    private var flingMs = 0L
    private var flingOn = false

    override val holding: Boolean get() = held && !pressed
    override val flinging: Boolean get() = flingOn

    private fun at(finger: Finger): X11Input.Move = moveTo(zoom.screenX(finger.x), zoom.screenY(finger.y))

    override fun firstDown(finger: Finger, ms: Long): List<X11Input> {
        stopper = flingOn
        flingOn = false
        held = false
        pressed = false
        axis = null
        track.clear()
        track.addLast(Triple(ms, finger.x, finger.y))
        val move = at(finger)
        startX = move.x
        startY = move.y
        return listOf(move)
    }

    override fun oneFinger(finger: Finger, dx: Float, dy: Float, ms: Long): List<X11Input> {
        if (held) {
            if (pressed) return listOf(at(finger))
            pressed = true
            return listOf(X11Input.Move(startX, startY), X11Input.Button(LEFT, true), at(finger))
        }
        track.addLast(Triple(ms, finger.x, finger.y))
        while (track.size > 2 && ms - track.first().first > FLING_WINDOW_MS) track.removeFirst()
        val lock = axis ?: lockFor(zoom.screenX(finger.x) - startX, zoom.screenY(finger.y) - startY).also { axis = it }
        val (x, y) = locked(lock, dx / zoom.scale, dy / zoom.scale)
        return if (x == 0f && y == 0f) emptyList() else listOf(scroll(x, y))
    }

    private fun lockFor(dx: Float, dy: Float): Axis = when {
        abs(dy) >= abs(dx) * AXIS_LOCK_RATIO -> Axis.Y
        abs(dx) >= abs(dy) * AXIS_LOCK_RATIO -> Axis.X
        else -> Axis.BOTH
    }

    private fun locked(lock: Axis, dx: Float, dy: Float) = when (lock) {
        Axis.X -> dx to 0f
        Axis.Y -> 0f to dy
        Axis.BOTH -> dx to dy
    }

    override fun twoFingers(a0: Finger, b0: Finger, a1: Finger, b1: Finger): List<X11Input> {
        if (pressed) return emptyList()
        zoom = zoom.pinch(a0, b0, a1, b1, screenWidth, screenHeight)
        return emptyList()
    }

    override fun lastUp(finger: Finger, tap: Boolean, ms: Long): List<X11Input> {
        val wasHeld = held
        held = false
        return when {
            pressed -> listOf(at(finger)) + letGo()
            wasHeld -> click(RIGHT)
            moved -> {
                startFling(ms)
                emptyList()
            }
            stopper -> emptyList()
            else -> click(LEFT)
        }
    }

    private fun startFling(ms: Long) {
        val (lastMs, lastX, lastY) = track.lastOrNull() ?: return
        val (firstMs, firstX, firstY) = track.first()
        // A finger that rested before it left doesn't fling.
        if (ms - lastMs > FLING_WINDOW_MS / 2 || lastMs <= firstMs) return
        val elapsed = (lastMs - firstMs).toFloat()
        val (vx, vy) = locked(axis ?: Axis.BOTH, (lastX - firstX) / elapsed / zoom.scale, (lastY - firstY) / elapsed / zoom.scale)
        val speed = hypot(vx, vy)
        if (speed < FLING_MIN_SPEED) return
        val cap = if (speed > FLING_MAX_SPEED) FLING_MAX_SPEED / speed else 1f
        velocityX = vx * cap
        velocityY = vy * cap
        flingMs = ms
        flingOn = true
    }

    // The speed dies away as exp(-t / FLING_DECAY_MS); each frame scrolls by what it covered since the last.
    override fun fling(ms: Long): List<X11Input> {
        if (!flingOn) return emptyList()
        val elapsed = ms - flingMs
        if (elapsed <= 0) return emptyList()
        flingMs = ms
        val left = exp(-elapsed / FLING_DECAY_MS)
        val dx = velocityX * FLING_DECAY_MS * (1 - left)
        val dy = velocityY * FLING_DECAY_MS * (1 - left)
        velocityX *= left
        velocityY *= left
        if (hypot(velocityX, velocityY) < FLING_STOP_SPEED) flingOn = false
        return listOf(scroll(dx, dy))
    }

    override fun longPress(): List<X11Input> {
        held = true
        return emptyList()
    }

    override fun letGo(): List<X11Input> {
        flingOn = false
        held = false
        if (!pressed) return emptyList()
        pressed = false
        return listOf(X11Input.Button(LEFT, false))
    }
}

/** The gestures `desktop-touch` names. */
fun desktopGestures(touch: DesktopTouch, slop: Float): DesktopGestures = when (touch) {
    DesktopTouch.TOUCHPAD -> TouchpadGestures(slop)
    DesktopTouch.DIRECT -> DirectGestures(slop)
}
