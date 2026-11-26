package io.github.est4s.terminal.core

import kotlin.math.roundToInt

/**
 * How the desktop uses the other phone (`screen-desktop`): a monitor of
 * its own beside this phone's ([EXTEND]: RandR's second output,
 * `mynx-b`), or the second half of one wide monitor ([SPAN]), for
 * programs that should stretch across both.
 */
enum class ScreenDesktopMode(val word: String) { EXTEND("extend"), SPAN("span") }

// The X server's modes come in widths of 8 (libxcvt's granularity), as DesktopGeometry's.
private const val MODE_STEP = 8

private fun modeWidth(width: Int) = (width / MODE_STEP * MODE_STEP).coerceAtLeast(MODE_STEP)

/**
 * The other phone's monitor, in X pixels: its whole screen ([width] x
 * [height] pixels at [dpi]) at an X pixel of about 0.75 of its dp, as
 * this phone's desktop is ([desktopScale]), so a window is about the
 * same size on both; the width a whole mode step.
 */
fun secondMonitorSize(width: Int, height: Int, dpi: Int): Pair<Int, Int> {
    val scale = desktopScale(dpi / 160f)
    return Pair(modeWidth(width / scale), (height / scale).coerceAtLeast(1))
}

/** Both phones' parts of the X screen, in its pixels from 0, 0: this phone's ([host]) and the other's ([screen]). */
data class DesktopMonitors(val mode: ScreenDesktopMode, val host: PixelRect, val screen: PixelRect) {
    val width get() = maxOf(host.x + host.width, screen.x + screen.width)
    val height get() = maxOf(host.y + host.height, screen.y + screen.height)
}

/**
 * This phone's monitor ([host], X pixels) and the other's ([screen]) on
 * the other's [side], lined up at the start of the shared edge. As the
 * second half ([ScreenDesktopMode.SPAN]) the other's part is scaled to
 * this one's edge, keeping its shape, so both make one rectangle.
 */
fun desktopMonitors(host: Pair<Int, Int>, screen: Pair<Int, Int>, side: ScreenSide, mode: ScreenDesktopMode): DesktopMonitors {
    val (hw, hh) = host
    var (sw, sh) = screen
    if (mode == ScreenDesktopMode.SPAN) {
        when (side) {
            ScreenSide.LEFT, ScreenSide.RIGHT -> {
                sw = modeWidth((sw.toDouble() * hh / sh).toInt())
                sh = hh
            }
            ScreenSide.ABOVE, ScreenSide.BELOW -> {
                sh = (sh.toDouble() * hw / sw).roundToInt().coerceAtLeast(1)
                sw = hw
            }
        }
    }
    // Same density on both: arrangeScreens' scale is 1, so it only places them.
    val a = arrangeScreens(PhoneScreen(hw, hh, 1), PhoneScreen(sw, sh, 1), side)
    return DesktopMonitors(mode, a.host, a.screen)
}

/** The other phone, for its part of the desktop: its screen ([width] x [height] at [dpi]) and the video it's sent at. */
data class DesktopOther(val width: Int, val height: Int, val dpi: Int, val streamWidth: Int, val streamHeight: Int)

/**
 * Where the desktop is on each phone (the extended screen's desktop, on
 * the host; work log "Next" 0a): this phone's monitor, the size its view
 * gives ([host]), and the other phone's part while the desktop is sent
 * there ([other]), laid out by `screen-place` and `screen-desktop`. The X
 * server is told the layout ([event]) and says back what each surface
 * shows ([shown]: a program such as XFCE's display settings may move a
 * monitor); that wins until the layout changes. Touches on the other
 * phone's video map onto its part ([fromStream]), and the pointer back
 * ([toStream]). Main thread.
 */
class DesktopScreens {
    private var hostSize = Pair(0, 0)
    private var other: DesktopOther? = null
    private var side = ScreenSide.RIGHT
    private var mode = ScreenDesktopMode.EXTEND
    private var said: X11ServerEvent.MonitorsShown? = null

    /** The layout while the other phone has a part, else null. */
    var monitors: DesktopMonitors? = null
        private set

    /** This phone's monitor is [width] x [height] X pixels; whether that changed anything. */
    fun host(width: Int, height: Int): Boolean {
        if (hostSize == Pair(width, height)) return false
        hostSize = Pair(width, height)
        relayout()
        return true
    }

    /** The other phone's part ([to], null: none) on its [side], as [mode] says; whether that changed anything. */
    fun other(to: DesktopOther?, side: ScreenSide, mode: ScreenDesktopMode): Boolean {
        if (to == other && (to == null || (side == this.side && mode == this.mode))) return false
        other = to
        this.side = side
        this.mode = mode
        relayout()
        return true
    }

    private fun relayout() {
        said = null
        val o = other
        monitors = if (o == null) {
            null
        } else {
            desktopMonitors(hostSize, secondMonitorSize(o.width, o.height, o.dpi), side, mode)
        }
    }

    /** What the X server says each surface shows. */
    fun shown(event: X11ServerEvent.MonitorsShown) {
        said = event
    }

    /** This phone's part of the X screen. */
    val hostRect: PixelRect
        get() = said?.host?.takeIf { it.width > 0 } ?: monitors?.host ?: PixelRect(0, 0, hostSize.first, hostSize.second)

    /** The other phone's part, while it has one. */
    val otherRect: PixelRect?
        get() {
            val layout = monitors ?: return null
            return said?.screen?.takeIf { it.width > 0 } ?: layout.screen
        }

    /** The parts the pointer is kept on. */
    val areas: List<PixelRect> get() = listOfNotNull(hostRect, otherRect)

    /** What the X server is told: the layout, or this phone's monitor alone. */
    fun event(): X11Input.Monitors = monitors?.let { X11Input.Monitors(it) } ?: X11Input.Monitors.alone(hostSize.first, hostSize.second)

    /** A point of the other phone's video, in X pixels of its part (from its corner). */
    fun fromStream(x: Float, y: Float): Pair<Float, Float> {
        val o = other ?: return Pair(x, y)
        val r = otherRect ?: return Pair(x, y)
        return Pair(x * r.width / o.streamWidth, y * r.height / o.streamHeight)
    }

    /** Where the pointer at [x], [y] (X screen pixels) is on the other phone's video, or null when it isn't there. */
    fun toStream(x: Float, y: Float): Pair<Float, Float>? {
        val o = other ?: return null
        val r = otherRect ?: return null
        if (x < r.x || y < r.y || x >= r.x + r.width || y >= r.y + r.height) return null
        return Pair((x - r.x) * o.streamWidth / r.width, (y - r.y) * o.streamHeight / r.height)
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
