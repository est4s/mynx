package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdfSelectionTest {
    private fun near(expected: Float, actual: Float, what: String) = assertEquals(expected, actual, 0.01f, what)

    private fun near(expected: PdfPoint, actual: PdfPoint, what: String) {
        near(expected.x, actual.x, "$what x")
        near(expected.y, actual.y, "$what y")
    }

    private fun near(expected: PdfRect, actual: PdfRect, what: String) {
        near(expected.left, actual.left, "$what left")
        near(expected.top, actual.top, "$what top")
        near(expected.right, actual.right, "$what right")
        near(expected.bottom, actual.bottom, "$what bottom")
    }

    // Two pages on a 1000-wide view with 10 gaps: Letter (612 × 792 points, so 1294 high) and a 500-point-wide landscape one.
    private val aspects = listOf(792f / 612f, 0.5f)
    private fun column() = PdfColumn(aspects, 1000f, 10f)
    private fun zoom() = PdfZoom().apply { layout(1000f, 1000f, column().height) }
    private fun space(zoom: PdfZoom = zoom()) = PdfPageSpace(column(), zoom, listOf(612f, 500f))

    @Test
    fun `a page's size in points and screen pixels per point`() {
        val space = space()
        near(612f, space.pointWidth(0), "width")
        near(792f, space.pointHeight(0), "height")
        near(1000f / 612f, space.scale(0), "scale")
        near(2f, space.scale(1), "landscape")
    }

    @Test
    fun `a page the document gave no width is a Letter page`() {
        val space = PdfPageSpace(column(), zoom(), emptyList())
        near(PDF_DEFAULT_POINTS, space.pointWidth(1), "width")
        near(PDF_DEFAULT_POINTS * 0.5f, space.pointHeight(1), "height")
    }

    @Test
    fun `the page under a screen point, none in a gap or beside the pages`() {
        val zoom = zoom()
        val space = space(zoom)
        assertEquals(0, space.pageAt(500f, 500f))
        assertEquals(1, space.pageAt(500f, 1320f))
        assertNull(space.pageAt(500f, 5f), "the gap above")
        assertNull(space.pageAt(500f, 1308f), "the gap between")
        // A column narrower than the view (a pinch can't, but a layout can): beside it is nothing.
        val narrow = PdfPageSpace(PdfColumn(aspects, 500f, 10f), PdfZoom().apply { layout(1000f, 2000f, 1000f) }, listOf(612f, 500f))
        assertNull(narrow.pageAt(700f, 500f))
    }

    @Test
    fun `screen points to page points and back, zoomed and scrolled`() {
        val zoom = zoom()
        zoom.zoomBy(2f, 0f, 0f)
        zoom.panBy(-100f, -300f)
        val space = space(zoom)
        // Page 0's top-left corner is at (-100, -300 + 10 × 2).
        near(PdfPoint(0f, 0f), space.toPage(0, -100f, -280f), "corner")
        val s = 2000f / 612f
        near(PdfPoint(306f, 100f), space.toPage(0, -100f + 306f * s, -280f + 100f * s), "inside")
        near(PdfPoint(-100f + 306f * s, -280f + 100f * s), space.toScreen(0, PdfPoint(306f, 100f)), "back")
        // Page 1 starts below page 0 and its gap: 10 + 1294.1 + 10 at zoom 1.
        val top1 = -300f + (10f + 1000f * 792f / 612f + 10f) * 2f
        near(PdfPoint(-100f, top1), space.toScreen(1, PdfPoint(0f, 0f)), "page 1")
    }

    @Test
    fun `page rects to screen rects`() {
        val space = space()
        near(PdfRect(200f, 10f + 100f, 400f, 10f + 120f), space.toScreen(1, PdfRect(100f, 50f, 200f, 60f)).let {
            // Page 1 is at 10 + 1294.1 + 10 on screen.
            PdfRect(it.left, it.top - (1000f * 792f / 612f + 10f), it.right, it.bottom - (1000f * 792f / 612f + 10f))
        }, "rect")
    }

    @Test
    fun `a dragged handle is kept inside its page`() {
        val space = space()
        near(PdfPoint(0f, 0f), space.toPageInside(0, -50f, -50f), "above left")
        near(PdfPoint(612f, 792f), space.toPageInside(0, 5000f, 1400f), "below right, in the next page")
        near(PdfPoint(250f, 0f), space.toPageInside(1, 500f, 20f), "page 1 from above")
    }

    @Test
    fun `the ends of a selection are inside its first and last lines`() {
        val rects = listOf(PdfRect(100f, 10f, 300f, 20f), PdfRect(50f, 22f, 200f, 32f))
        val (start, stop) = pdfSelectionEnds(rects)!!
        near(PdfPoint(100.5f, 15f), start, "start")
        near(PdfPoint(199.5f, 27f), stop, "stop")
        // A sliver of a line: a quarter of its width in.
        val (thinStart, thinStop) = pdfSelectionEnds(listOf(PdfRect(10f, 0f, 11f, 10f)))!!
        near(10.25f, thinStart.x, "thin start")
        near(10.75f, thinStop.x, "thin stop")
        assertNull(pdfSelectionEnds(emptyList()))
    }

    @Test
    fun `the handles hang from the first line's start and the last line's end`() {
        val rects = listOf(PdfRect(100f, 10f, 300f, 20f), PdfRect(50f, 22f, 200f, 32f))
        val (start, stop) = pdfHandles(rects)!!
        near(PdfPoint(100f, 20f), start, "start")
        near(PdfPoint(200f, 32f), stop, "stop")
        assertNull(pdfHandles(emptyList()))
    }

    @Test
    fun `a touch grabs the nearer handle within the slop`() {
        val start = PdfPoint(100f, 100f)
        val stop = PdfPoint(300f, 100f)
        assertEquals(PdfHandle.START, pdfGrab(110f, 110f, start, stop, 30f))
        assertEquals(PdfHandle.STOP, pdfGrab(290f, 95f, start, stop, 30f))
        assertNull(pdfGrab(200f, 100f, start, stop, 30f))
        // Both in reach (a one-letter selection): the nearer; a tie, the end.
        val close = PdfPoint(110f, 100f)
        assertEquals(PdfHandle.START, pdfGrab(101f, 100f, start, close, 30f))
        assertEquals(PdfHandle.STOP, pdfGrab(105f, 100f, start, close, 30f))
    }

    @Test
    fun `the selection's box`() {
        near(PdfRect(50f, 10f, 300f, 32f), pdfBox(listOf(PdfRect(100f, 10f, 300f, 20f), PdfRect(50f, 22f, 200f, 32f)))!!, "box")
        assertNull(pdfBox(emptyList()))
    }

    @Test
    fun `the selected text joins the blocks with line breaks`() {
        assertEquals("Hello world", pdfSelectionText(listOf("Hello world")))
        assertEquals("One\nTwo", pdfSelectionText(listOf("One", "Two")))
        // Blocks that end their own line don't get another; pdfium's CR LF becomes a line break.
        assertEquals("One\nTwo\nThree", pdfSelectionText(listOf("One\r\n", "Two\r\nThree")))
        assertEquals("One\nTwo", pdfSelectionText(listOf("One", "", "Two")))
        assertEquals("", pdfSelectionText(emptyList()))
    }

    @Test
    fun `the bar goes above the selection, below it without room, at the top without either`() {
        // A 300 × 50 bar on a 1000 × 2000 view, 10 from things, 40 for the handles below.
        val above = pdfBarPlace(PdfRect(400f, 500f, 600f, 600f), 300f, 50f, 1000f, 2000f, 10f, 40f)
        near(PdfPoint(350f, 440f), above, "above, centred")
        val below = pdfBarPlace(PdfRect(400f, 30f, 600f, 100f), 300f, 50f, 1000f, 2000f, 10f, 40f)
        near(PdfPoint(350f, 150f), below, "below the handles")
        val top = pdfBarPlace(PdfRect(400f, 30f, 600f, 1990f), 300f, 50f, 1000f, 2000f, 10f, 40f)
        near(PdfPoint(350f, 10f), top, "the top")
        val edge = pdfBarPlace(PdfRect(0f, 500f, 20f, 600f), 300f, 50f, 1000f, 2000f, 10f, 40f)
        near(10f, edge.x, "kept on screen")
        val right = pdfBarPlace(PdfRect(980f, 500f, 1000f, 600f), 300f, 50f, 1000f, 2000f, 10f, 40f)
        near(690f, right.x, "kept on screen, right")
    }

    @Test
    fun `one request in flight, the latest one waiting`() {
        val latest = PdfLatest<Int>()
        assertEquals(1, latest.offer(1), "sent at once")
        assertNull(latest.offer(2), "waits")
        assertNull(latest.offer(3), "replaces the one waiting")
        assertEquals(3, latest.done(), "sent when the first is done")
        assertNull(latest.done(), "nothing waiting")
        assertEquals(4, latest.offer(4), "free again")
        latest.clear()
        assertEquals(5, latest.offer(5), "cleared")
    }
}
