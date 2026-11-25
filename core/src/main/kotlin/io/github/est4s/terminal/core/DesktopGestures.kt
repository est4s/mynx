package io.github.est4s.terminal.core

import kotlin.math.hypot

/** A touch lasting at most this long, not moving, is a tap. */
const val TAP_MS = 250L
/** A finger held still this long is a long press. */
const val LONG_PRESS_MS = 500L
/** A touch this soon after a tap that then moves is a drag (touchpad). */
const val DOUBLE_TAP_MS = 300L
/** Two fingers moving this far (X pixels) scroll one wheel notch. */
const val SCROLL_PX_PER_NOTCH = 40f
// Touchpad acceleration: 1× up to the slow speed, rising to the most at the fast one (X px a ms).
private const val SLOW_SPEED = 0.5f
private const val FAST_SPEED = 2f
private const val MOST_GAIN = 2.5f
private const val LEFT = 1
private const val MIDDLE = 2
private const val RIGHT = 3

/** A finger on the desktop's screen, in X pixels. */
data class Finger(val id: Int, val x: Float, val y: Float)

/**
 * Turns touches on the desktop into the X server's pointer events. Feed
 * it every finger down and up, and moves with all the fingers down, in
 * X pixels; [slop] (X pixels) tells a move from a tap. A long press
 * needs [timeout] called at [timeoutAt]. Main thread.
 */
sealed class DesktopGestures(private val slop: Float) {
    /** Where the pointer is, in X pixels: our own, so the view can draw it. */
    var pointerX = 0f
        protected set
    var pointerY = 0f
        protected set
    private var screenWidth = 1
    private var screenHeight = 1
    private var started = false

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
    private var lastCentreX = 0f
    private var lastCentreY = 0f

    /** The X screen's size; the pointer is kept on it. */
    fun screen(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        if (!started) {
            started = true
            pointerX = (screenWidth / 2).toFloat()
            pointerY = (screenHeight / 2).toFloat()
        }
        pointerX = clampX(pointerX)
        pointerY = clampY(pointerY)
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
        centre().let { (cx, cy) -> lastCentreX = cx; lastCentreY = cy }
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
        if (fingers.size != 2) return emptyList()
        val (cx, cy) = centre()
        val dx = cx - lastCentreX
        val dy = cy - lastCentreY
        lastCentreX = cx
        lastCentreY = cy
        // The content follows the fingers, as on the phone: fingers going up scroll down.
        if (!moved || (dx == 0f && dy == 0f)) return emptyList()
        return listOf(X11Input.Scroll(-dx / SCROLL_PX_PER_NOTCH, -dy / SCROLL_PX_PER_NOTCH))
    }

    fun up(id: Int, ms: Long): List<X11Input> {
        val finger = fingers.remove(id) ?: return emptyList()
        starts.remove(id)
        if (fingers.isNotEmpty()) {
            centre().let { (cx, cy) -> lastCentreX = cx; lastCentreY = cy }
            return emptyList()
        }
        timeoutAt = null
        val tap = !moved && ms - startMs <= TAP_MS
        return when {
            most == 1 -> lastUp(finger, tap, ms)
            !tap -> emptyList()
            most == 2 -> click(RIGHT)
            else -> click(MIDDLE)
        }
    }

    /** Android took the touch away: let go of anything held. */
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

    private fun centre(): Pair<Float, Float> {
        val two = fingers.values.take(2)
        return two.map { it.x }.average().toFloat() to two.map { it.y }.average().toFloat()
    }

    protected fun click(button: Int) = listOf(X11Input.Button(button, true), X11Input.Button(button, false))

    protected abstract fun firstDown(finger: Finger, ms: Long): List<X11Input>
    protected abstract fun oneFinger(finger: Finger, dx: Float, dy: Float, ms: Long): List<X11Input>
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
 * `desktop-touch = direct`: the pointer goes where the finger is; a tap
 * clicks there, a finger that moves drags from where it went down, a long
 * press is a right click; two fingers scroll or, tapped, right click.
 */
class DirectGestures(slop: Float) : DesktopGestures(slop) {
    private var pressed = false
    private var longPressed = false
    private var startX = 0f
    private var startY = 0f

    private fun at(finger: Finger): X11Input.Move {
        pointerX = clampX(finger.x)
        pointerY = clampY(finger.y)
        return X11Input.Move(pointerX, pointerY)
    }

    override fun firstDown(finger: Finger, ms: Long): List<X11Input> {
        pressed = false
        longPressed = false
        val move = at(finger)
        startX = move.x
        startY = move.y
        return listOf(move)
    }

    override fun oneFinger(finger: Finger, dx: Float, dy: Float, ms: Long): List<X11Input> {
        if (longPressed) return emptyList()
        if (pressed) return listOf(at(finger))
        pressed = true
        return listOf(X11Input.Move(startX, startY), X11Input.Button(LEFT, true), at(finger))
    }

    override fun lastUp(finger: Finger, tap: Boolean, ms: Long): List<X11Input> = when {
        pressed -> listOf(at(finger)) + letGo()
        longPressed || moved -> emptyList()
        else -> click(LEFT)
    }

    override fun longPress(): List<X11Input> {
        longPressed = true
        return click(RIGHT)
    }

    override fun letGo(): List<X11Input> {
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
