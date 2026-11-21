package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfTest {
    private fun near(expected: Float, actual: Float, what: String) = assertEquals(expected, actual, 0.01f, what)

    // Three pages on a 1000-wide view with 10 gaps: 1400, 500 (landscape), 1400 high.
    private fun column() = PdfColumn(listOf(1.4f, 0.5f, 1.4f), 1000f, 10f)

    @Test
    fun `pages are fitted to the width, one under another with gaps around them`() {
        val column = column()
        near(10f, column.top(0), "first top")
        near(1400f, column.height(0), "first height")
        near(1420f, column.top(1), "second top")
        near(500f, column.height(1), "second height")
        near(1930f, column.top(2), "third top")
        near(10f + 1400 + 10 + 500 + 10 + 1400 + 10, column.height, "column")
        assertEquals(3, column.count)
    }

    @Test
    fun `the pages in a stretch of the column`() {
        val column = column()
        assertEquals(0..0, column.pagesIn(0f, 1000f))
        assertEquals(0..1, column.pagesIn(1000f, 1500f))
        assertEquals(1..2, column.pagesIn(1500f, 2500f))
        // In the gap between two pages: none.
        assertTrue(column.pagesIn(1411f, 1419f).isEmpty())
        assertEquals(2..2, column.pagesIn(3000f, 9000f))
        assertTrue(PdfColumn(emptyList(), 1000f, 10f).pagesIn(0f, 100f).isEmpty())
    }

    @Test
    fun `the page at a point, the nearest one in a gap`() {
        val column = column()
        assertEquals(0, column.pageAt(-50f))
        assertEquals(0, column.pageAt(700f))
        assertEquals(0, column.pageAt(1412f))
        assertEquals(1, column.pageAt(1418f))
        assertEquals(2, column.pageAt(5000f))
    }

    @Test
    fun `the current page is the one in the middle, but the first and last at the ends`() {
        val column = column()
        assertEquals(0, column.current(0f, 1000f))
        assertEquals(1, column.current(1200f, 2200f))
        // At the very end the short page before can sit in the middle: the last page counts.
        assertEquals(2, column.current(column.height - 1000f, column.height))
        // A short view at the top with page 2 in its middle: still the first.
        assertEquals(0, PdfColumn(listOf(0.1f, 0.1f), 1000f, 10f).current(0f, 400f))
    }

    @Test
    fun `a place in the column as a page and how far down it, and back`() {
        val column = column()
        val (page, fraction) = column.anchor(1670f)
        assertEquals(1, page)
        near(0.5f, fraction, "fraction")
        // The same place once the view is half as wide.
        val narrow = PdfColumn(listOf(1.4f, 0.5f, 1.4f), 500f, 10f)
        near(10f + 700 + 10 + 125, narrow.at(page, fraction), "back")
    }

    @Test
    fun `the zoom starts at the column's width, at the top`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        near(1f, zoom.scale, "scale")
        near(0f, zoom.x, "x")
        near(0f, zoom.y, "y")
        near(0f, zoom.top, "top")
        near(2000f, zoom.bottom, "bottom")
    }

    @Test
    fun `a column shorter than the view is centred`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 1000f) }
        near(500f, zoom.y, "y")
    }

    @Test
    fun `scrolling stays inside the column`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        zoom.panBy(300f, -1000f)
        near(0f, zoom.x, "x can't move at the width")
        near(-1000f, zoom.y, "y")
        zoom.panBy(0f, -9000f)
        near(-3000f, zoom.y, "the end")
        zoom.panBy(0f, 9000f)
        near(0f, zoom.y, "the start")
    }

    @Test
    fun `a pinch zooms around its focus, between the width and the most`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        zoom.zoomBy(2f, 500f, 1000f)
        near(2f, zoom.scale, "scale")
        near(-500f, zoom.x, "x")
        near(-1000f, zoom.y, "y")
        near(500f, zoom.top, "top in the column")
        zoom.zoomBy(100f, 500f, 1000f)
        near(PDF_ZOOM_MAX, zoom.scale, "most")
        zoom.zoomBy(0.001f, 500f, 1000f)
        near(1f, zoom.scale, "least")
        near(0f, zoom.x, "x back")
    }

    @Test
    fun `zoomed in, the column pans sideways within its width`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        zoom.zoomBy(2f, 0f, 0f)
        zoom.panBy(-5000f, 0f)
        near(-1000f, zoom.x, "the right edge")
        zoom.panBy(5000f, 0f)
        near(0f, zoom.x, "the left edge")
    }

    @Test
    fun `a double tap zooms in, and another back to the width`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        zoom.toggle(0f, 0f)
        near(PDF_ZOOM_DOUBLE_TAP, zoom.scale, "in")
        zoom.toggle(0f, 0f)
        near(1f, zoom.scale, "out")
    }

    @Test
    fun `scrolling to a place puts it at the view's top`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        zoom.scrollTo(1420f)
        near(-1420f, zoom.y, "y")
        zoom.scrollTo(4900f)
        near(-3000f, zoom.y, "no further than the end")
    }

    @Test
    fun `a new layout keeps the zoom but stays inside`() {
        val zoom = PdfZoom().apply { layout(1000f, 2000f, 5000f) }
        zoom.zoomBy(2f, 1000f, 0f)
        zoom.layout(500f, 1000f, 2500f)
        near(2f, zoom.scale, "scale")
        near(-500f, zoom.x, "x inside the narrower column")
    }

    @Test
    fun `a page renders as wide as the view at the zoom`() {
        assertEquals(PdfRenderSize(1000, 1400), pdfRenderSize(1.4f, 1000f, 1f))
        assertEquals(PdfRenderSize(2000, 2800), pdfRenderSize(1.4f, 1000f, 2f, maxPixels = 10_000_000))
    }

    @Test
    fun `a page's render is capped in pixels and side`() {
        val size = pdfRenderSize(1.4f, 1000f, 5f, maxPixels = 4_000_000, maxSide = 8192)
        assertTrue(size.width * size.height <= 4_000_000, "pixels")
        near(1.4f, size.height.toFloat() / size.width, "keeps its shape")
        val tall = pdfRenderSize(10f, 1000f, 1f, maxPixels = 100_000_000, maxSide = 4096)
        assertEquals(4096, tall.height)
        assertEquals(409, tall.width)
        assertEquals(PdfRenderSize(1, 1), pdfRenderSize(0f, 0f, 1f))
    }

    @Test
    fun `the plan renders pages in sight sharp first, then their neighbours plain`() {
        val column = PdfColumn(List(10) { 1.4f }, 1000f, 10f)
        // Page 4 is in sight, zoomed in twice.
        val top = column.top(4) + 100f
        val plan = pdfPlan(column, top, top + 500f, 2f, emptyMap(), keep = 2, maxPixels = 10_000_000)
        assertEquals(listOf(4, 3, 5, 2, 6), plan.render.map { it.page })
        assertEquals(PdfRenderSize(2000, 2800), plan.render[0].size)
        assertEquals(PdfRenderSize(1000, 1400), plan.render[1].size)
        assertTrue(plan.drop.isEmpty())
    }

    @Test
    fun `the plan keeps renders good enough and drops the far ones`() {
        val column = PdfColumn(List(10) { 1.4f }, 1000f, 10f)
        val top = column.top(4)
        val have = mapOf(0 to 1000, 3 to 1000, 4 to 950, 5 to 400, 9 to 1000)
        val plan = pdfPlan(column, top, top + 1000f, 1f, have, keep = 1)
        // 4 is near enough; 5 is too blurry.
        assertEquals(listOf(5), plan.render.map { it.page })
        assertEquals(listOf(0, 9), plan.drop.sorted())
    }

    @Test
    fun `go to page takes a number and keeps it in the document`() {
        assertEquals(2, pdfGoToPage(" 3 ", 12))
        assertEquals(0, pdfGoToPage("0", 12))
        assertEquals(11, pdfGoToPage("40", 12))
        assertEquals(11, pdfGoToPage("99999999999999", 12))
        assertNull(pdfGoToPage("", 12))
        assertNull(pdfGoToPage("three", 12))
        assertNull(pdfGoToPage("3", 0))
    }

    @Test
    fun `the indicator counts from one`() {
        assertEquals("3 / 12", pdfIndicator(2, 12))
    }
}
