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
 * Where the X server's one pointer is, in X screen pixels, shared by the
 * gestures of every monitor (the extended screen: this phone's and the
 * other phone's), so a touchpad on either moves it across both. It's kept
 * on [areas], the monitors; while there are none, on the monitor of the
 * gestures moving it. [listener] hears every move. Main thread.
 */
class DesktopPointer {
    var x = 0f
        private set
    var y = 0f
        private set
    /** Whether it's been put anywhere yet (it starts in the middle of the first monitor it's kept on). */
    var placed = false
        private set
    var listener: (() -> Unit)? = null

    /** The monitors it's kept on; changing them brings it onto them. */
    var areas: List<PixelRect> = emptyList()
        set(value) {
            field = value
            if (value.isEmpty()) return
            if (!placed) centre(value.first()) else place(x, y, value)
        }

    internal fun centre(on: PixelRect) {
        place((on.x + on.width / 2).toFloat(), (on.y + on.height / 2).toFloat(), listOf(on))
    }

    /**
     * Moves it to [px], [py] when that's on a monitor (of the [areas] while
     * there are any, else of [on]); else as far as the monitor it's on
     * goes (crossing only where monitors meet, as X's own pointer does),
     * or onto the nearest one when it's on none.
     */
    fun place(px: Float, py: Float, on: List<PixelRect>) {
        val bounds = areas.ifEmpty { on }
        fun PixelRect.has(px: Float, py: Float) = px >= x && py >= y && px <= x + width - 1 && py <= y + height - 1
        fun PixelRect.clampX(v: Float) = v.coerceIn(x.toFloat(), (x + width - 1).coerceAtLeast(x).toFloat())
        fun PixelRect.clampY(v: Float) = v.coerceIn(y.toFloat(), (y + height - 1).coerceAtLeast(y).toFloat())
        val target = bounds.firstOrNull { it.has(px, py) }
            ?: bounds.firstOrNull { placed && it.has(x, y) }
            ?: bounds.minByOrNull { hypot(it.clampX(px) - px, it.clampY(py) - py) }
        placed = true
        val nx = target?.clampX(px) ?: px
        val ny = target?.clampY(py) ?: py
        val moved = nx != x || ny != y
        x = nx
        y = ny
        if (moved) listener?.invoke()
    }
}

/**
 * Turns touches on the desktop into the X server's pointer events. Feed
 * it every finger down and up, and moves with all the fingers down, in
 * X pixels of its [monitor] (its own surface's part of the X screen);
 * [slop] (X pixels) tells a move from a tap. A long press needs
 * [timeout] called at [timeoutAt]. The [pointer] may be shared with
 * another monitor's gestures. Main thread.
 */
sealed class DesktopGestures(private val slop: Float, val pointer: DesktopPointer = DesktopPointer()) {
    /** Where the pointer is, in X screen pixels: our own, so the view can draw it. */
    val pointerX: Float get() = pointer.x
    val pointerY: Float get() = pointer.y

    /** This surface's part of the X screen: its fingers' 0, 0 is the monitor's corner. */
    var monitor = PixelRect(0, 0, 1, 1)
        private set

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

    /** The X screen's size, when it's this surface's alone; the pointer is kept on it. */
    fun screen(width: Int, height: Int) = monitor(PixelRect(0, 0, width, height))

    /** This surface's monitor on the X screen; the pointer is kept on the monitors. */
    fun monitor(rect: PixelRect) {
        monitor = rect.copy(width = rect.width.coerceAtLeast(1), height = rect.height.coerceAtLeast(1))
        if (!pointer.placed) pointer.centre(monitor) else pointer.place(pointer.x, pointer.y, listOf(monitor))
    }

    /** A mouse pointing at [x], [y] of the monitor: the pointer goes there (kept on it). */
    fun moveTo(x: Float, y: Float): X11Input.Move {
        val m = monitor
        val px = (m.x + x).coerceIn(m.x.toFloat(), (m.x + m.width - 1).toFloat())
        val py = (m.y + y).coerceIn(m.y.toFloat(), (m.y + m.height - 1).toFloat())
        pointer.place(px, py, listOf(m))
        return X11Input.Move(pointerX, pointerY)
    }

    /** Moves the pointer by [dx], [dy], across monitors (kept on them). */
    protected fun moveBy(dx: Float, dy: Float) = pointer.place(pointerX + dx, pointerY + dy, listOf(monitor))

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
class TouchpadGestures(slop: Float, pointer: DesktopPointer = DesktopPointer()) : DesktopGestures(slop, pointer) {
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
        moveBy(dx * gain, dy * gain)
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
class DirectGestures(slop: Float, pointer: DesktopPointer = DesktopPointer()) : DesktopGestures(slop, pointer) {
    private var pressed = false
    private var longPressed = false
    private var startX = 0f
    private var startY = 0f

    private fun at(finger: Finger): X11Input.Move = moveTo(finger.x, finger.y)

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
fun desktopGestures(touch: DesktopTouch, slop: Float, pointer: DesktopPointer = DesktopPointer()): DesktopGestures = when (touch) {
    DesktopTouch.TOUCHPAD -> TouchpadGestures(slop, pointer)
    DesktopTouch.DIRECT -> DirectGestures(slop, pointer)
}
