package io.github.est4s.terminal.core

import kotlin.math.hypot

/** A page's width in PDF points when the document didn't say: US Letter. */
const val PDF_DEFAULT_POINTS = 612f

/** A point: on screen in pixels, or on a page in its PDF points (0, 0 its top-left corner). */
data class PdfPoint(val x: Float, val y: Float)

/** A rectangle, in the same spaces as [PdfPoint]. */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Text selected on [page] (Android 15's PdfRenderer): [text] and its lines' [rects] in the page's PDF points. */
data class PdfSelection(val page: Int, val text: String, val rects: List<PdfRect>)

/** Which end of a selection a handle moves. */
enum class PdfHandle { START, STOP }

/**
 * Screen pixels and a page's own PDF points for [column] at [zoom]
 * (docs/TOUCH-DESIGN.md "PDF"), its pages [pointWidths] points wide (a
 * page without one is [PDF_DEFAULT_POINTS]). It reads the zoom as it is
 * when asked.
 */
class PdfPageSpace(private val column: PdfColumn, private val zoom: PdfZoom, private val pointWidths: List<Float>) {
    fun pointWidth(page: Int): Float = pointWidths.getOrNull(page)?.takeIf { it > 0f } ?: PDF_DEFAULT_POINTS

    fun pointHeight(page: Int): Float = pointWidth(page) * column.aspect(page)

    /** Screen pixels per PDF point on [page]. */
    fun scale(page: Int): Float = column.width * zoom.scale / pointWidth(page)

    private fun left() = zoom.x

    private fun top(page: Int) = zoom.y + column.top(page) * zoom.scale

    /** The page under the screen point ([x], [y]); null in a gap or beside the pages. */
    fun pageAt(x: Float, y: Float): Int? {
        if (column.count == 0) return null
        val columnY = (y - zoom.y) / zoom.scale
        val page = column.pageAt(columnY)
        val inX = x >= left() && x <= left() + column.width * zoom.scale
        val inY = columnY >= column.top(page) && columnY <= column.top(page) + column.height(page)
        return if (inX && inY) page else null
    }

    /** The screen point ([x], [y]) in [page]'s points, wherever it is. */
    fun toPage(page: Int, x: Float, y: Float): PdfPoint {
        val s = scale(page)
        return PdfPoint((x - left()) / s, (y - top(page)) / s)
    }

    /** The same, kept inside the page: a dragged handle's. */
    fun toPageInside(page: Int, x: Float, y: Float): PdfPoint {
        val p = toPage(page, x, y)
        return PdfPoint(p.x.coerceIn(0f, pointWidth(page)), p.y.coerceIn(0f, pointHeight(page)))
    }

    /** [page]'s [point] on screen. */
    fun toScreen(page: Int, point: PdfPoint): PdfPoint {
        val s = scale(page)
        return PdfPoint(left() + point.x * s, top(page) + point.y * s)
    }

    /** [page]'s [rect] on screen. */
    fun toScreen(page: Int, rect: PdfRect): PdfRect {
        val s = scale(page)
        val x = left()
        val y = top(page)
        return PdfRect(x + rect.left * s, y + rect.top * s, x + rect.right * s, y + rect.bottom * s)
    }
}

/**
 * The points (in page points) to select from and to again when one
 * handle moves: just inside the first line's start and the last line's
 * end, half-way down each, so they land on its first and last letters.
 */
fun pdfSelectionEnds(rects: List<PdfRect>): Pair<PdfPoint, PdfPoint>? {
    val first = rects.firstOrNull() ?: return null
    val last = rects.last()
    fun nudge(r: PdfRect) = minOf(0.5f, (r.right - r.left) / 4)
    return PdfPoint(first.left + nudge(first), (first.top + first.bottom) / 2) to
        PdfPoint(last.right - nudge(last), (last.top + last.bottom) / 2)
}

/** Where the handles hang from (in page points): the first line's bottom-left and the last line's bottom-right. */
fun pdfHandles(rects: List<PdfRect>): Pair<PdfPoint, PdfPoint>? {
    val first = rects.firstOrNull() ?: return null
    val last = rects.last()
    return PdfPoint(first.left, first.bottom) to PdfPoint(last.right, last.bottom)
}

/** The handle a touch at ([x], [y]) grabs, by the handles' centres: the nearer within [slop], the end on a tie; null for neither. */
fun pdfGrab(x: Float, y: Float, start: PdfPoint, stop: PdfPoint, slop: Float): PdfHandle? {
    val toStart = hypot(x - start.x, y - start.y)
    val toStop = hypot(x - stop.x, y - stop.y)
    return when {
        toStop <= slop && toStop <= toStart -> PdfHandle.STOP
        toStart <= slop -> PdfHandle.START
        else -> null
    }
}

/** The box around all [rects]. */
fun pdfBox(rects: List<PdfRect>): PdfRect? {
    if (rects.isEmpty()) return null
    return PdfRect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
}

/** The selected text: its blocks in order, a line break between them unless one ends its line already, pdfium's CR LF as LF. */
fun pdfSelectionText(blocks: List<String>): String {
    val out = StringBuilder()
    for (block in blocks) {
        val text = block.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isEmpty()) continue
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        out.append(text)
    }
    return out.toString().trimEnd('\n')
}

/**
 * Where the selection's bar ([barWidth] × [barHeight]) goes in a
 * [viewWidth] × [viewHeight] view: centred over [selection] (its box
 * on screen), [margin] above it; without room there, below it and the
 * handles hanging [handles] under it; without room there either, at the
 * top. Always [margin] inside the view's sides.
 */
fun pdfBarPlace(
    selection: PdfRect,
    barWidth: Float,
    barHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    margin: Float,
    handles: Float,
): PdfPoint {
    val centre = (selection.left + selection.right) / 2
    val x = (centre - barWidth / 2).coerceAtMost(viewWidth - barWidth - margin).coerceAtLeast(margin)
    val above = selection.top - margin - barHeight
    val below = selection.bottom + handles + margin
    val y = when {
        above >= margin && above + barHeight <= viewHeight - margin -> above
        below >= margin && below + barHeight <= viewHeight - margin -> below
        else -> margin
    }
    return PdfPoint(x, y)
}

/**
 * One request in flight, the latest waiting: [offer] gives back what to
 * send now (null: one's in flight, this one waits, replacing any
 * waiting); [done], when the one in flight answers, the next to send.
 */
class PdfLatest<T> {
    private var busy = false
    private var waiting: T? = null

    fun offer(request: T): T? {
        if (busy) {
            waiting = request
            return null
        }
        busy = true
        return request
    }

    fun done(): T? {
        val next = waiting
        waiting = null
        busy = next != null
        return next
    }

    fun clear() {
        busy = false
        waiting = null
    }
}
