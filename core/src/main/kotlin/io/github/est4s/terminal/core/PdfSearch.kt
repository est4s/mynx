package io.github.est4s.terminal.core

/** A search match on [page]: its [rects] (a line each) in the page's PDF points. */
data class PdfMatch(val page: Int, val rects: List<PdfRect>)

/**
 * Find in the PDF viewer (docs/TOUCH-DESIGN.md "PDF", Android 15+): the
 * pages are searched one at a time, in order ([nextPage], then [found]
 * with its matches), so the pages keep rendering between them and the
 * count grows as it goes. The current match is the first at or after
 * [fromPage], the page in view when the search began; with none there,
 * the first in the document once every page is searched.
 */
class PdfSearch(val query: String, private val fromPage: Int, private val pageCount: Int) {
    private val found = ArrayList<PdfMatch>()
    private var searched = if (query.isEmpty()) pageCount else 0

    val matches: List<PdfMatch> get() = found

    /** The current match's index, -1 for none (yet). */
    var current = -1
        private set

    val currentMatch: PdfMatch? get() = found.getOrNull(current)

    val done: Boolean get() = searched >= pageCount

    /** The page to search next; null when every page has been. */
    val nextPage: Int? get() = if (done) null else searched

    /** [page]'s matches came (each a list of line rects); true when the current match changed. */
    fun found(page: Int, pageMatches: List<List<PdfRect>>): Boolean {
        if (page != searched || done) return false
        searched++
        val before = found.size
        for (rects in pageMatches) if (rects.isNotEmpty()) found += PdfMatch(page, rects)
        if (current >= 0) return false
        if (page >= fromPage && found.size > before) {
            current = before
            return true
        }
        if (done && found.isNotEmpty()) {
            current = 0
            return true
        }
        return false
    }

    /** ↑ (-1) or ↓ (1), round the ends; false with nothing to go to. */
    fun step(by: Int): Boolean {
        if (found.isEmpty()) return false
        val from = if (current < 0) 0 else current + by
        current = ((from % found.size) + found.size) % found.size
        return true
    }

    /** The bar's count: "3/12", with "…" while it's still searching. */
    val label: String
        get() = when {
            query.isEmpty() -> ""
            found.isEmpty() -> if (done) "No matches" else "Searching…"
            else -> "${maxOf(current, 0) + 1}/${found.size}" + if (done) "" else "…"
        }
}

/**
 * How far to pan the pages (screen pixels) to bring [box] (on screen)
 * into a [viewWidth] × [viewHeight] view: across, centred, when part of it
 * is off a side; down, a third of the way from the top, when it's less
 * than [margin] from the top or bottom. In sight, it doesn't move.
 */
fun pdfRevealBy(box: PdfRect, viewWidth: Float, viewHeight: Float, margin: Float): PdfPoint {
    val inX = box.left >= 0f && box.right <= viewWidth
    val inY = box.top >= margin && box.bottom <= viewHeight - margin
    val dx = if (inX) 0f else viewWidth / 2 - (box.left + box.right) / 2
    val dy = if (inY) 0f else viewHeight / 3 - box.top
    return PdfPoint(dx, dy)
}
