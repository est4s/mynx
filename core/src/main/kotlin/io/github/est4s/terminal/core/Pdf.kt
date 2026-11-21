package io.github.est4s.terminal.core

import kotlin.math.abs
import kotlin.math.sqrt

/** The most the PDF viewer zooms, times the screen's width. */
const val PDF_ZOOM_MAX = 5f
/** A double tap's zoom, times the screen's width. */
const val PDF_ZOOM_DOUBLE_TAP = 2.5f
/** A page's render at most this many pixels (4 bytes each). */
const val PDF_MAX_PIXELS = 6_000_000
/** And at most this long a side (what a canvas draws comfortably). */
const val PDF_MAX_SIDE = 4096
/** Pages kept rendered beyond those in sight, each way. */
const val PDF_KEEP_PAGES = 3

/**
 * The PDF viewer's pages one under another (docs/TOUCH-DESIGN.md "PDF"),
 * each fitted to [width], so as high as its aspect (from [aspects],
 * height / width) times it, with [gap] above, between and below them.
 * Places are in the column's own units: screen pixels at zoom 1.
 */
class PdfColumn(private val aspects: List<Float>, val width: Float, gap: Float) {
    private val heights = FloatArray(aspects.size) { maxOf(0f, aspects[it]) * width }
    private val tops = FloatArray(aspects.size)

    val count: Int get() = heights.size

    /** The whole column, the gaps included. */
    val height: Float

    init {
        var at = gap
        for (i in heights.indices) {
            tops[i] = at
            at += heights[i] + gap
        }
        height = at
    }

    fun top(page: Int): Float = tops[page]

    fun height(page: Int): Float = heights[page]

    /** [page]'s height / width. */
    fun aspect(page: Int): Float = maxOf(0f, aspects[page])

    private fun bottom(page: Int) = tops[page] + heights[page]

    /** The pages any part of which is between [from] and [to]. */
    fun pagesIn(from: Float, to: Float): IntRange {
        // The first page ending below [from].
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (bottom(mid) > from) hi = mid else lo = mid + 1
        }
        var last = lo - 1
        while (last + 1 < count && tops[last + 1] < to) last++
        return if (last < lo) IntRange.EMPTY else lo..last
    }

    /** The page at [y]; in a gap or past the ends, the nearest one. */
    fun pageAt(y: Float): Int {
        if (count == 0) return 0
        // The first page ending below [y].
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (bottom(mid) > y) hi = mid else lo = mid + 1
        }
        if (lo == count) return count - 1
        if (lo == 0 || y >= tops[lo]) return lo
        // In the gap above [lo]: whichever page's edge is closer.
        return if (abs(y - bottom(lo - 1)) < abs(tops[lo] - y)) lo - 1 else lo
    }

    /**
     * The page the indicator names for a view showing [top] to [bottom]:
     * the one in its middle, but the first and last at the column's ends.
     */
    fun current(top: Float, bottom: Float): Int = when {
        count == 0 -> 0
        top <= 0.5f -> 0
        bottom >= height - 0.5f -> count - 1
        else -> pageAt((top + bottom) / 2)
    }

    /** [y] as a page and how far down it (0 its top, 1 its bottom), to find it again in another column of the same pages ([at]). */
    fun anchor(y: Float): Pair<Int, Float> {
        if (count == 0) return 0 to 0f
        val page = pageAt(y)
        val high = heights[page]
        return page to if (high > 0f) (y - tops[page]) / high else 0f
    }

    /** The place [fraction] of the way down [page] (an [anchor]). */
    fun at(page: Int, fraction: Float): Float {
        if (count == 0) return 0f
        val p = page.coerceIn(0, count - 1)
        return tops[p] + fraction * heights[p]
    }
}

/**
 * The PDF viewer's zoom over its whole column: [scale] (1 is the column
 * at the view's width, up to [PDF_ZOOM_MAX]) and where the column's
 * top-left corner is on screen ([x], [y]). It stays on screen: a side
 * larger than the view can't leave a gap, a smaller one is centred.
 */
class PdfZoom {
    private var viewW = 0f
    private var viewH = 0f
    private var columnH = 0f

    var scale = 1f
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    /** The column's place at the view's top. */
    val top: Float get() = -y / scale

    /** The column's place at the view's bottom. */
    val bottom: Float get() = (viewH - y) / scale

    /** Whether it's at the view's width, as it started. */
    val fitted get() = scale <= 1.001f

    /** A new view size or column (the column is [viewWidth] wide at scale 1): the zoom stays, kept inside. */
    fun layout(viewWidth: Float, viewHeight: Float, columnHeight: Float) {
        viewW = viewWidth
        viewH = viewHeight
        columnH = columnHeight
        keepInside()
    }

    /** A pinch: [factor] times bigger, keeping the point at ([focusX], [focusY]) under it. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (factor <= 0f) return
        val next = (scale * factor).coerceIn(1f, PDF_ZOOM_MAX)
        val applied = next / scale
        x = focusX - (focusX - x) * applied
        y = focusY - (focusY - y) * applied
        scale = next
        keepInside()
    }

    /** A drag (or a key's scroll) by ([dx], [dy]) screen pixels. */
    fun panBy(dx: Float, dy: Float) {
        x += dx
        y += dy
        keepInside()
    }

    /** A double tap: zoomed in at ([focusX], [focusY]) when at the width, else back to it. */
    fun toggle(focusX: Float, focusY: Float) {
        if (fitted) zoomBy(PDF_ZOOM_DOUBLE_TAP, focusX, focusY) else zoomBy(1f / scale, focusX, focusY)
    }

    /** The column's place [columnY] at the view's top, as near as it goes. */
    fun scrollTo(columnY: Float) {
        y = -columnY * scale
        keepInside()
    }

    private fun keepInside() {
        x = inside(x, viewW * scale, viewW)
        y = inside(y, columnH * scale, viewH)
    }

    private fun inside(at: Float, size: Float, view: Float) =
        if (size <= view) (view - size) / 2 else at.coerceIn(view - size, 0f)
}

/** A page's render in pixels. */
data class PdfRenderSize(val width: Int, val height: Int)

/** Render [page] at [size]. */
data class PdfRender(val page: Int, val size: PdfRenderSize)

/** What to render, most wanted first, and which pages' renders to let go. */
data class PdfPlan(val render: List<PdfRender>, val drop: List<Int>)

/**
 * The render of a page whose height is [aspect] times its width, shown
 * [width] screen pixels wide at [zoom]: as sharp as the screen, but no
 * more than [maxPixels], nor [maxSide] a side, keeping its shape.
 */
fun pdfRenderSize(aspect: Float, width: Float, zoom: Float, maxPixels: Int = PDF_MAX_PIXELS, maxSide: Int = PDF_MAX_SIDE): PdfRenderSize {
    val w = maxOf(0f, width * zoom)
    val h = maxOf(0f, w * aspect)
    var f = 1f
    if (w * h > maxPixels) f = minOf(f, sqrt(maxPixels / (w * h)))
    if (w > maxSide) f = minOf(f, maxSide / w)
    if (h > maxSide) f = minOf(f, maxSide / h)
    return PdfRenderSize(maxOf(1, (w * f).toInt()), maxOf(1, (h * f).toInt()))
}

/**
 * What the viewer renders for a view showing [top] to [bottom] of
 * [column] (its [PdfColumn.width] the screen's) at [zoom], given the
 * widths of the renders it [have]s by page: the pages in sight at the
 * zoom, then [keep] pages each way at zoom 1 (nearest first), each only
 * if what it has is blurrier than that by more than a little. Renders
 * further away are dropped.
 */
fun pdfPlan(
    column: PdfColumn,
    top: Float,
    bottom: Float,
    zoom: Float,
    have: Map<Int, Int>,
    keep: Int = PDF_KEEP_PAGES,
    maxPixels: Int = PDF_MAX_PIXELS,
    maxSide: Int = PDF_MAX_SIDE,
): PdfPlan {
    if (column.count == 0) return PdfPlan(emptyList(), have.keys.toList())
    val sight = column.pagesIn(top, bottom).takeUnless { it.isEmpty() }
        ?: column.pageAt((top + bottom) / 2).let { it..it }
    val first = maxOf(0, sight.first - keep)
    val last = minOf(column.count - 1, sight.last + keep)
    val render = ArrayList<PdfRender>()
    fun want(page: Int, pageZoom: Float) {
        val size = pdfRenderSize(column.aspect(page), column.width, pageZoom, maxPixels, maxSide)
        val got = have[page]
        if (got == null || got < size.width * 0.85f) render += PdfRender(page, size)
    }
    for (page in sight) want(page, maxOf(1f, zoom))
    for (step in 1..keep) {
        if (sight.first - step >= first) want(sight.first - step, 1f)
        if (sight.last + step <= last) want(sight.last + step, 1f)
    }
    return PdfPlan(render, have.keys.filter { it < first || it > last })
}

/** "Go to page": [input] as a page (counted from 0) of a document of [count] pages, kept in it; null when it isn't a number. */
fun pdfGoToPage(input: String, count: Int): Int? {
    val text = input.trim()
    if (count <= 0 || text.isEmpty() || !text.all { it in '0'..'9' }) return null
    val number = text.toIntOrNull() ?: count
    return (number - 1).coerceIn(0, count - 1)
}

/** The page indicator: "3 / 12" for [page] (counted from 0) of [count]. */
fun pdfIndicator(page: Int, count: Int): String = "${page + 1} / $count"
