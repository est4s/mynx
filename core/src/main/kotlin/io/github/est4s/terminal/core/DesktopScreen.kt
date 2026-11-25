package io.github.est4s.terminal.core

import kotlin.math.roundToInt

// The X server's modes come in widths of 8 (libxcvt's granularity).
private const val MODE_WIDTH_STEP = 8
// An X pixel is this many dp, about: programs drawn at 96 dpi stay readable.
private const val DP_PER_X_PIXEL = 0.75f

/**
 * How many of the phone's pixels make one X pixel: whole ones (the
 * server stretches without smoothing), about [DP_PER_X_PIXEL] dp, at
 * least 1. [density] is px per dp.
 */
fun desktopScale(density: Float): Int = (density * DP_PER_X_PIXEL).roundToInt().coerceAtLeast(1)

/**
 * The X screen for a surface of [surfaceWidth] × [surfaceHeight] phone
 * pixels at [scale]: its width rounded down to the server's mode step.
 * Our patch makes the server stretch the screen over the whole surface,
 * so touches map by the ratio, not the scale.
 */
data class DesktopGeometry(val surfaceWidth: Int, val surfaceHeight: Int, val scale: Int) {
    val width: Int = (surfaceWidth / scale / MODE_WIDTH_STEP * MODE_WIDTH_STEP).coerceAtLeast(MODE_WIDTH_STEP)
    val height: Int = (surfaceHeight / scale).coerceAtLeast(1)

    fun toX(px: Float): Float = px * width / surfaceWidth
    fun toY(py: Float): Float = py * height / surfaceHeight
    fun fromX(x: Float): Float = x * surfaceWidth / width
    fun fromY(y: Float): Float = y * surfaceHeight / height
}

/**
 * How far up (px) the screen goes while the soft keyboard covers its
 * bottom, so the X screen keeps its size: [full] px tall, [visible] px
 * showing, the pointer at [pointerY] px. It stays at [current] while the
 * pointer is at least [margin] px inside what shows, else moves just
 * enough; never past either end.
 */
fun keyboardPan(full: Int, visible: Int, pointerY: Float, current: Int, margin: Int): Int {
    val most = (full - visible).coerceAtLeast(0)
    if (most == 0) return 0
    val y = pointerY.roundToInt()
    val pan = when {
        y > current + visible - margin -> y - visible + margin
        y < current + margin -> y - margin
        else -> current
    }
    return pan.coerceIn(0, most)
}

/**
 * What the X server is told when the view hands it a surface: the
 * screen's size when it changed. A new surface of the same size gets a
 * whole picture: the server draws only what changed since its last
 * frame (the spike showed a stale screen after reopening), and a mode
 * change, here the frame rate for a moment, makes it a new screen buffer,
 * drawn whole. Main thread.
 */
class DesktopSurface {
    private var sent: X11Input.ScreenSize? = null

    fun handover(width: Int, height: Int, framerate: Int, fresh: Boolean): List<X11Input> {
        val size = X11Input.ScreenSize(width, height, framerate)
        val events = when {
            size != sent -> listOf(size)
            fresh -> listOf(size.copy(framerate = framerate + 1), size)
            else -> emptyList()
        }
        sent = size
        return events
    }

    /** A new connection: the server's size isn't known. */
    fun reconnected() {
        sent = null
    }
}

/** What the desktop's view shows instead of its screen: [canStart] offers Start, [showLog] the logs under it. */
data class DesktopNotice(val text: String, val canStart: Boolean, val showLog: Boolean)

/**
 * The notice over the desktop's screen, null when the X server is
 * [connected] and the screen shows. [status] is null until the app is
 * ready; [starting] while a start runs; [error] why the last start failed.
 */
fun desktopNotice(connected: Boolean, status: DesktopStatus?, starting: Boolean, error: String?): DesktopNotice? = when {
    connected -> null
    status == null -> DesktopNotice("Waiting for the app…", canStart = false, showLog = false)
    error != null -> DesktopNotice("The desktop didn't start: $error", canStart = true, showLog = true)
    starting -> DesktopNotice("Starting the desktop…", canStart = false, showLog = false)
    status.running -> DesktopNotice("Connecting to the X server…", canStart = false, showLog = true)
    status.problem != null -> DesktopNotice(status.problem, canStart = true, showLog = false)
    else -> DesktopNotice("The desktop isn't running.", canStart = true, showLog = false)
}

/** Opening the desktop starts it when nothing runs and nothing it needs is missing. */
fun desktopStartsOnOpen(connected: Boolean, status: DesktopStatus?): Boolean =
    !connected && status != null && !status.running && status.problem == null
