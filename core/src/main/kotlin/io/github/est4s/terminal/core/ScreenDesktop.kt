package io.github.est4s.terminal.core

/**
 * How the desktop uses the other phone (`screen-desktop`): a monitor of
 * its own beside this phone's ([EXTEND]: RandR's second output,
 * `mynx-b`), or the second half of one wide monitor ([SPAN]), for
 * programs that should stretch across both.
 */
enum class ScreenDesktopMode(val word: String) { EXTEND("extend"), SPAN("span") }

/** Both phones' parts of the X screen, in its pixels from 0, 0: this phone's ([host]) and the other's ([screen]). */
data class DesktopMonitors(val mode: ScreenDesktopMode, val host: PixelRect, val screen: PixelRect) {
    val width get() = maxOf(host.x + host.width, screen.x + screen.width)
    val height get() = maxOf(host.y + host.height, screen.y + screen.height)
}

/**
 * The other phone, for its part of the desktop: its screen ([width] x
 * [height] pixels, [xdpi] x [ydpi] real pixels an inch; 0 when it didn't
 * say, then its [density] stands in) and the video it's sent at.
 */
data class DesktopOther(
    val width: Int,
    val height: Int,
    val xdpi: Float,
    val ydpi: Float,
    val streamWidth: Int,
    val streamHeight: Int,
    val density: Int = 0,
)

/**
 * Where the desktop is on each phone (the extended screen's desktop, on
 * the host; work log "Next" 0a): this phone's desktop view ([view]: its
 * pixels and dpi), and the other phone's part while the desktop is sent
 * there ([other]), laid out by `screen-place`, `screen-desktop` and the
 * pair's alignment ([align]) as [desktopFit] says. The X server is told
 * the layout ([event]) and says back what each surface shows ([shown]: a
 * program such as XFCE's display settings may move a monitor); that wins
 * until the layout changes. Touches on the other phone's video map onto
 * its part ([fromStream]), and the pointer back ([toStream]). Main thread.
 */
class DesktopScreens {
    private var view: PhysicalScreen? = null
    private var scale = 1
    private var other: DesktopOther? = null
    private var side = ScreenSide.RIGHT
    private var mode = ScreenDesktopMode.EXTEND
    private var said: X11ServerEvent.MonitorsShown? = null

    /** The pair's alignment, as applied now. */
    var alignment = ScreenAlignment()
        private set

    /** The layout while the other phone has a part, else null. */
    var fit: DesktopFit? = null
        private set

    val monitors: DesktopMonitors? get() = fit?.monitors

    /** Where this phone's surface goes in its view, while the other phone has a part (null: all of the view). */
    val hostSurface: PixelRect? get() = fit?.hostSurface

    /** This phone's desktop view is [width] x [height] pixels at [xdpi] x [ydpi], [scale] pixels an X pixel; whether that changed anything. */
    fun view(width: Int, height: Int, xdpi: Float, ydpi: Float, scale: Int): Boolean {
        val next = PhysicalScreen(width, height, xdpi, ydpi)
        if (next == view && scale == this.scale) return false
        view = next
        this.scale = scale
        relayout()
        return true
    }

    /** Whether this phone's view gave its size yet. */
    val hasView: Boolean get() = view != null

    /** The other phone's part ([to], null: none) on its [side], as [mode] says; whether that changed anything. */
    fun other(to: DesktopOther?, side: ScreenSide, mode: ScreenDesktopMode): Boolean {
        if (to == other && (to == null || (side == this.side && mode == this.mode))) return false
        other = to
        this.side = side
        this.mode = mode
        relayout()
        return true
    }

    /** The pair's alignment is [to] from now on; whether that changed anything. */
    fun align(to: ScreenAlignment): Boolean {
        val next = to.clamped()
        if (next == alignment) return false
        alignment = next
        relayout()
        return true
    }

    /** The alignment after the other phone's drag or pinch ([input]); the same when it has no part. */
    fun aligned(input: ScreenMessage.AlignInput): ScreenAlignment {
        val v = view ?: return alignment
        val o = other ?: return alignment
        return alignAfter(v, scale, o, side, alignment, input)
    }

    private fun relayout() {
        said = null
        val v = view
        val o = other
        fit = if (v == null || o == null) null else desktopFit(v, scale, o, side, mode, alignment)
    }

    /** What the X server says each surface shows. */
    fun shown(event: X11ServerEvent.MonitorsShown) {
        said = event
    }

    // This phone's monitor alone: its view's X screen.
    private val alone: Pair<Int, Int>
        get() = view?.let { DesktopGeometry(it.width, it.height, scale) }?.let { Pair(it.width, it.height) } ?: Pair(0, 0)

    /** This phone's part of the X screen. */
    val hostRect: PixelRect
        get() = said?.host?.takeIf { it.width > 0 } ?: monitors?.host ?: PixelRect(0, 0, alone.first, alone.second)

    /** The other phone's part, while it has one. */
    val otherRect: PixelRect?
        get() {
            val layout = monitors ?: return null
            return said?.screen?.takeIf { it.width > 0 } ?: layout.screen
        }

    /** The parts the pointer is kept on. */
    val areas: List<PixelRect> get() = listOfNotNull(hostRect, otherRect)

    /** What the X server is told: the layout, or this phone's monitor alone. */
    fun event(): X11Input.Monitors = monitors?.let { X11Input.Monitors(it) } ?: X11Input.Monitors.alone(alone.first, alone.second)

    /** A point of the other phone's video, in X pixels of its part (from its corner): its picture's part of the video, the bands held at its edge. */
    fun fromStream(x: Float, y: Float): Pair<Float, Float> {
        val p = fit?.picture ?: return Pair(x, y)
        val r = otherRect ?: return Pair(x, y)
        return Pair(
            ((x - p.x) * r.width / p.width).coerceIn(0f, r.width.toFloat()),
            ((y - p.y) * r.height / p.height).coerceIn(0f, r.height.toFloat()),
        )
    }

    /** Where the pointer at [x], [y] (X screen pixels) is on the other phone's video, or null when it isn't there. */
    fun toStream(x: Float, y: Float): Pair<Float, Float>? {
        val p = fit?.picture ?: return null
        val r = otherRect ?: return null
        if (x < r.x || y < r.y || x >= r.x + r.width || y >= r.y + r.height) return null
        return Pair(p.x + (x - r.x) * p.width / r.width, p.y + (y - r.y) * p.height / r.height)
    }
}

/**
 * A touch on the other phone's video, through that phone's [gestures]:
 * [map] takes the video's pixels to the gestures' monitor's. [ms] is now
 * on this phone (the touch's own time is the other phone's).
 */
fun desktopTouch(gestures: DesktopGestures, touch: ScreenMessage.Touch, map: (Float, Float) -> Pair<Float, Float>, ms: Long): List<X11Input> {
    fun finger(p: TouchPoint): Finger = map(p.x, p.y).let { (x, y) -> Finger(p.id, x, y) }
    val it = touch.points.getOrNull(touch.index)
    return when (touch.action) {
        ACTION_DOWN, ACTION_POINTER_DOWN -> it?.let { p -> finger(p).let { f -> gestures.down(f.id, f.x, f.y, ms) } }.orEmpty()
        ACTION_MOVE -> gestures.move(touch.points.map(::finger), ms)
        ACTION_UP, ACTION_POINTER_UP -> it?.let { p -> gestures.up(p.id, ms) }.orEmpty()
        ACTION_CANCEL -> gestures.cancel()
        else -> emptyList()
    }
}
