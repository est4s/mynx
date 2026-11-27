package io.github.est4s.terminal.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A screen, or a part of one (the desktop's view): [width] x [height] pixels at [xdpi] x [ydpi] real pixels an inch. */
data class PhysicalScreen(val width: Int, val height: Int, val xdpi: Float, val ydpi: Float)

/** How far the other phone's picture may move along the seam (mm, either way). */
const val MAX_ALIGN_OFFSET_MM = 50f
const val MIN_ALIGN_SCALE = 0.8f
const val MAX_ALIGN_SCALE = 1.25f

/** The alignment rulers' lines are this far apart (mm), every [RULER_MAJOR_EVERY]th a long one (1 cm). */
const val RULER_STEP_MM = 2f
const val RULER_MAJOR_EVERY = 5

private const val MM_PER_INCH = 25.4
private const val MODE_STEP = 8

private fun modeStep(width: Int) = (width / MODE_STEP * MODE_STEP).coerceAtLeast(MODE_STEP)

/**
 * How the other phone's picture lines up with this one's (`mynx screen
 * align`, the desktop's Align screens), as fine-tuning of what the
 * phones' dpi say, which Android often has a little off: [offset] mm
 * along the shared edge (more: the picture further down or right), and
 * [scale], its size (1: as the dpi say). Kept with the pair.
 */
data class ScreenAlignment(val offset: Float = 0f, val scale: Float = 1f) {
    fun clamped() = ScreenAlignment(offset.coerceIn(-MAX_ALIGN_OFFSET_MM, MAX_ALIGN_OFFSET_MM), scale.coerceIn(MIN_ALIGN_SCALE, MAX_ALIGN_SCALE))

    val isDefault: Boolean get() = this == ScreenAlignment()
}

/** The alignment as people read it: "offset +1.5 mm · scale 1.012". */
fun alignText(a: ScreenAlignment): String {
    val tenths = (a.offset * 10).roundToInt()
    val offset = when {
        tenths == 0 -> "0"
        tenths % 10 == 0 -> (if (tenths > 0) "+" else "-") + abs(tenths / 10)
        else -> String.format(Locale.ROOT, "%+.1f", tenths / 10f)
    }
    return "offset $offset mm · scale ${String.format(Locale.ROOT, "%.3f", a.scale)}"
}

/** One line of a [DesktopRuler]: [at] pixels along it, a long one if [major]. */
data class RulerLine(val at: Float, val major: Boolean)

/**
 * The lines an alignment ruler draws along the seam: one at [origin] +
 * k × [step] pixels for every whole k, every [RULER_MAJOR_EVERY]th (k = 0
 * among them) a long one. Both phones draw the same X pixels' lines, so
 * they meet across the seam when the pictures line up.
 */
data class DesktopRuler(val origin: Float, val step: Float) {
    /** The lines from 0 to [length]. */
    fun lines(length: Float): List<RulerLine> {
        if (step <= 0f || length <= 0f) return emptyList()
        val first = ceil(-origin / step).toInt()
        val last = floor((length - origin) / step).toInt()
        return (first..last).map { k -> RulerLine(origin + k * step, Math.floorMod(k, RULER_MAJOR_EVERY) == 0) }
    }
}

/**
 * Where the desktop goes on both phones when the other has a part
 * ([desktopFit]): this phone's surface in its view ([hostSurface], view
 * pixels: all of it, unless this phone is the bigger along the seam and
 * shows bands), both monitors in X pixels ([monitors]), and the part of
 * the other phone's video its picture is drawn into ([picture], video
 * pixels; the rest is black). The rulers mark the same X lines on each:
 * [hostRuler] in this phone's view pixels, [otherRuler] in the video's,
 * along the seam ([vertical]: it runs up and down).
 */
data class DesktopFit(
    val hostSurface: PixelRect,
    val monitors: DesktopMonitors,
    val picture: PixelRect,
    val hostRuler: DesktopRuler,
    val otherRuler: DesktopRuler,
    val vertical: Boolean,
)

// The other phone's real pixels an inch, or its density's when it didn't say (before version 5).
private fun DesktopOther.dpi(): Pair<Float, Float> {
    val fallback = (if (density > 0) density else 160).toFloat()
    return Pair(xdpi.takeIf { it > 0f } ?: fallback, ydpi.takeIf { it > 0f } ?: fallback)
}

/** The seam: this phone's view's extent along it and the other's (X pixels), and where the other's starts ([d], from this one's start). */
private class Seam(val fa: Double, val fb: Double, offsetX: Double) {
    val d: Double
    val start: Double
    val length: Double

    init {
        // Centred on each other by default; the offset moves the other's picture forward, so its screen back.
        val keep = minOf(fa, fb) / 2
        d = ((fa - fb) / 2 - offsetX).coerceIn(-fb + keep, fa - keep)
        start = maxOf(0.0, d)
        length = minOf(fa, d + fb) - start
    }
}

/** What [desktopFit] works from, along the seam: this phone's px an X pixel ([k]), the other's ([b]), X pixels a mm ([mm]). */
private class SeamScale(view: PhysicalScreen, scale: Int, other: DesktopOther, side: ScreenSide, align: ScreenAlignment) {
    val vertical = side == ScreenSide.LEFT || side == ScreenSide.RIGHT
    val g = DesktopGeometry(view.width, view.height, scale)
    val kx = view.width.toDouble() / g.width
    val ky = view.height.toDouble() / g.height
    private val dpi = other.dpi()
    val bx = kx * dpi.first / view.xdpi * align.scale
    val by = ky * dpi.second / view.ydpi * align.scale
    val k = if (vertical) ky else kx
    val b = if (vertical) by else bx
    val mm = (if (vertical) view.ydpi else view.xdpi) / MM_PER_INCH / k
    val fa = (if (vertical) g.height else g.width).toDouble()
    val fb = (if (vertical) other.height else other.width) / b
    // The video's pixels a pixel of the other phone's screen, along the seam.
    val video = if (vertical) other.streamHeight.toDouble() / other.height else other.streamWidth.toDouble() / other.width

    fun seam(align: ScreenAlignment) = Seam(fa, fb, align.offset * mm)
}

/**
 * The desktop across both phones (the extended screen): this phone's
 * view ([view], its pixels and dpi) at [scale] phone pixels an X pixel,
 * the other phone ([other]) on [side]. **An X pixel is the same size on
 * both**, as each phone's real dpi say (fine-tuned by [align]), so a
 * window keeps its size across the seam; **along the seam both share the
 * shorter extent** (this phone's view loses height to its bars), and the
 * taller phone shows black bands; both start at its start. The other
 * phone's part is the rest of its screen across (to a mode step), its
 * picture against the edge facing this phone. The same sizes serve a
 * monitor of its own ([ScreenDesktopMode.EXTEND]) and one wide monitor
 * over both ([ScreenDesktopMode.SPAN]): its halves meet exactly.
 */
fun desktopFit(
    view: PhysicalScreen,
    scale: Int,
    other: DesktopOther,
    side: ScreenSide,
    mode: ScreenDesktopMode,
    align: ScreenAlignment,
): DesktopFit {
    val a = align.clamped()
    val s = SeamScale(view, scale, other, side, a)
    val seam = s.seam(a)
    val g = s.g
    val host: Pair<Int, Int>
    val screen: Pair<Int, Int>
    val hostSurface: PixelRect
    // The picture on the other phone's screen, in its pixels.
    val pl: Double
    val pt: Double
    val pw: Double
    val ph: Double
    if (s.vertical) {
        val e = floor(seam.length + 1e-6).toInt().coerceAtLeast(1)
        val h = (e * s.ky).roundToInt().coerceAtMost(view.height)
        hostSurface = PixelRect(0, (seam.start * s.ky).roundToInt().coerceIn(0, view.height - h), view.width, h)
        host = Pair(g.width, e)
        val bw = modeStep(floor(other.width / s.bx).toInt())
        screen = Pair(bw, e)
        pw = bw * s.bx
        ph = e * s.by
        pt = (seam.start - seam.d) * s.by
        pl = if (side == ScreenSide.RIGHT) 0.0 else other.width - pw
    } else {
        val e = modeStep(floor(seam.length + 1e-6).toInt())
        val w = (e * s.kx).roundToInt().coerceAtMost(view.width)
        hostSurface = PixelRect((seam.start * s.kx).roundToInt().coerceIn(0, view.width - w), 0, w, view.height)
        host = Pair(e, g.height)
        val bh = floor(other.height / s.by).toInt().coerceAtLeast(1)
        screen = Pair(e, bh)
        pw = e * s.bx
        ph = bh * s.by
        pl = (seam.start - seam.d) * s.bx
        pt = if (side == ScreenSide.BELOW) 0.0 else other.height - ph
    }
    val vx = other.streamWidth.toDouble() / other.width
    val vy = other.streamHeight.toDouble() / other.height
    val picture = PixelRect(
        (pl * vx).roundToInt().coerceIn(0, other.streamWidth),
        (pt * vy).roundToInt().coerceIn(0, other.streamHeight),
        (pw * vx).roundToInt().coerceIn(1, other.streamWidth),
        (ph * vy).roundToInt().coerceIn(1, other.streamHeight),
    )
    val arranged = arrangeScreens(PhoneScreen(host.first, host.second, 1), PhoneScreen(screen.first, screen.second, 1), side)
    val step = RULER_STEP_MM * s.mm
    val hostStart = if (s.vertical) hostSurface.y else hostSurface.x
    val pictureStart = (if (s.vertical) pt * vy else pl * vx)
    return DesktopFit(
        hostSurface = hostSurface,
        monitors = DesktopMonitors(mode, arranged.host, arranged.screen),
        picture = picture,
        hostRuler = DesktopRuler(hostStart.toFloat(), (step * s.k).toFloat()),
        otherRuler = DesktopRuler(pictureStart.toFloat(), (step * s.b * s.video).toFloat()),
        vertical = s.vertical,
    )
}

/**
 * The alignment after a drag or pinch on the other phone ([input], its
 * video's pixels): the picture moves with the finger, and a pinch scales
 * it about the fingers, so the line under them stays.
 */
fun alignAfter(
    view: PhysicalScreen,
    scale: Int,
    other: DesktopOther,
    side: ScreenSide,
    align: ScreenAlignment,
    input: ScreenMessage.AlignInput,
): ScreenAlignment {
    val a = align.clamped()
    val s = SeamScale(view, scale, other, side, a)
    // The shift: the other phone's pixels, then X pixels, then mm.
    val shifted = a.copy(offset = (a.offset + input.shift / s.video / s.b / s.mm).toFloat()).clamped()
    if (input.zoom == 1f || input.zoom <= 0f) return shifted
    val before = SeamScale(view, scale, other, side, shifted)
    val d = before.seam(shifted).d
    val focus = input.focus / before.video
    // The X pixel under the fingers, from this phone's start; it stays there as the other phone's pixels grow.
    val t = d + focus / before.b
    val zoomed = shifted.copy(scale = shifted.scale * input.zoom).clamped()
    val after = SeamScale(view, scale, other, side, zoomed)
    val dAfter = t - focus / after.b
    val offsetX = (after.fa - after.fb) / 2 - dAfter
    return zoomed.copy(offset = (offsetX / after.mm).toFloat()).clamped()
}

/** What a key does while aligning, on the host: moves the picture back or forward along the seam, scales it, or ends. */
enum class DesktopAlignKey { BACK, FORWARD, BIGGER, SMALLER, DONE, RESET, CANCEL }

/** An Android key code while aligning ([vertical]: the seam runs up and down, so Up and Down move along it); null: nothing. */
fun desktopAlignKey(keyCode: Int, vertical: Boolean): DesktopAlignKey? = when (keyCode) {
    19 -> if (vertical) DesktopAlignKey.BACK else null // DPAD_UP
    20 -> if (vertical) DesktopAlignKey.FORWARD else null // DPAD_DOWN
    21 -> if (vertical) null else DesktopAlignKey.BACK // DPAD_LEFT
    22 -> if (vertical) null else DesktopAlignKey.FORWARD // DPAD_RIGHT
    81, 70, 157 -> DesktopAlignKey.BIGGER // PLUS, EQUALS, NUMPAD_ADD
    69, 156 -> DesktopAlignKey.SMALLER // MINUS, NUMPAD_SUBTRACT
    66, 160, 23 -> DesktopAlignKey.DONE // ENTER, NUMPAD_ENTER, DPAD_CENTER
    111, 4 -> DesktopAlignKey.CANCEL // ESCAPE, BACK
    46 -> DesktopAlignKey.RESET // R
    else -> null
}

/** [align] after a key's nudge: a tenth of a millimetre, or a thousandth of its size. */
fun alignNudge(align: ScreenAlignment, key: DesktopAlignKey): ScreenAlignment {
    fun mm(v: Float) = (v * 100).roundToInt() / 100f
    fun size(v: Float) = (v * 10000).roundToInt() / 10000f
    return when (key) {
        DesktopAlignKey.BACK -> align.copy(offset = mm(align.offset - 0.1f))
        DesktopAlignKey.FORWARD -> align.copy(offset = mm(align.offset + 0.1f))
        DesktopAlignKey.BIGGER -> align.copy(scale = size(align.scale + 0.001f))
        DesktopAlignKey.SMALLER -> align.copy(scale = size(align.scale - 0.001f))
        else -> align
    }.clamped()
}

/**
 * The other phone's touches while aligning (its picture, not the
 * desktop): one finger drags along the seam ([vertical]: it runs up and
 * down, so y counts), two pinch about their middle. Each move is an
 * [ScreenMessage.AlignInput] for the host, of the video's pixels (stream
 * 0: the caller's to set); [preview] moves the ruler drawn there at once,
 * before the host's new picture comes.
 */
class AlignGestures(private val vertical: Boolean) {
    private val fingers = LinkedHashMap<Int, Pair<Float, Float>>()

    private fun along(p: Pair<Float, Float>) = if (vertical) p.second else p.first

    fun down(id: Int, x: Float, y: Float): ScreenMessage.AlignInput? {
        fingers[id] = Pair(x, y)
        return null
    }

    fun up(id: Int) {
        fingers.remove(id)
    }

    fun cancel() = fingers.clear()

    fun move(points: List<Finger>): ScreenMessage.AlignInput? {
        val ids = fingers.keys.take(2)
        if (ids.isEmpty()) return null
        val before = ids.map { fingers.getValue(it) }
        val now = ids.map { id -> points.firstOrNull { it.id == id }?.let { Pair(it.x, it.y) } ?: fingers.getValue(id) }
        ids.forEachIndexed { i, id -> fingers[id] = now[i] }
        val centreBefore = before.map(::along).average().toFloat()
        val centre = now.map(::along).average().toFloat()
        val zoom = if (ids.size == 2) {
            val was = hypot(before[0].first - before[1].first, before[0].second - before[1].second)
            val is_ = hypot(now[0].first - now[1].first, now[0].second - now[1].second)
            if (was > 0f && is_ > 0f) is_ / was else 1f
        } else {
            1f
        }
        val shift = centre - centreBefore
        if (shift == 0f && zoom == 1f) return null
        return ScreenMessage.AlignInput(0, shift, zoom, centre)
    }

    /** [ruler] after [input]: moved, then scaled about its focus. */
    fun preview(ruler: DesktopRuler, input: ScreenMessage.AlignInput): DesktopRuler {
        val moved = ruler.origin + input.shift
        return DesktopRuler(input.focus + (moved - input.focus) * input.zoom, ruler.step * input.zoom)
    }
}
