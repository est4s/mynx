package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfSearchTest {
    private fun rect(n: Float) = PdfRect(n, n, n + 10f, n + 5f)
    private fun hits(vararg tops: Float) = tops.map { listOf(rect(it)) }

    @Test
    fun `pages are searched in order, one at a time`() {
        val search = PdfSearch("word", fromPage = 0, pageCount = 3)
        assertEquals(0, search.nextPage)
        search.found(0, emptyList())
        assertEquals(1, search.nextPage)
        search.found(1, emptyList())
        search.found(2, emptyList())
        assertNull(search.nextPage)
        assertTrue(search.done)
    }

    @Test
    fun `the first match at or after the page in view becomes current`() {
        val search = PdfSearch("word", fromPage = 1, pageCount = 3)
        assertFalse(search.found(0, hits(1f, 2f)), "before the page in view: not current yet")
        assertEquals(-1, search.current)
        assertTrue(search.found(1, hits(3f)))
        assertEquals(2, search.current)
        assertEquals(PdfMatch(1, listOf(rect(3f))), search.currentMatch)
        assertFalse(search.found(2, hits(4f)), "current stays")
        assertEquals(2, search.current)
    }

    @Test
    fun `with none from the page in view on, it goes back to the first`() {
        val search = PdfSearch("word", fromPage = 2, pageCount = 3)
        search.found(0, hits(1f))
        search.found(1, emptyList())
        assertEquals(-1, search.current)
        assertTrue(search.found(2, emptyList()))
        assertEquals(0, search.current)
    }

    @Test
    fun `stepping goes round`() {
        val search = PdfSearch("word", fromPage = 0, pageCount = 2)
        search.found(0, hits(1f, 2f))
        search.found(1, hits(3f))
        assertTrue(search.step(1))
        assertEquals(1, search.current)
        search.step(1)
        search.step(1)
        assertEquals(0, search.current)
        search.step(-1)
        assertEquals(2, search.current)
        assertFalse(PdfSearch("word", 0, 1).step(1), "nothing to step to")
    }

    @Test
    fun `an answer for a page out of turn is ignored`() {
        val search = PdfSearch("word", fromPage = 0, pageCount = 2)
        search.found(1, hits(1f))
        assertEquals(0, search.matches.size)
        assertEquals(0, search.nextPage)
    }

    @Test
    fun `the count says how far it got`() {
        assertEquals("", PdfSearch("", 0, 3).label)
        val search = PdfSearch("word", fromPage = 0, pageCount = 3)
        assertEquals("Searching…", search.label)
        search.found(0, hits(1f, 2f))
        assertEquals("1/2…", search.label)
        search.found(1, emptyList())
        search.found(2, emptyList())
        assertEquals("1/2", search.label)
        val none = PdfSearch("word", 0, 1)
        none.found(0, emptyList())
        assertEquals("No matches", none.label)
    }

    @Test
    fun `an empty query searches nothing`() {
        val search = PdfSearch("", 0, 3)
        assertNull(search.nextPage)
        assertTrue(search.done)
    }

    @Test
    fun `revealing a box already in sight doesn't move`() {
        assertEquals(PdfPoint(0f, 0f), pdfRevealBy(PdfRect(10f, 100f, 50f, 120f), 400f, 800f, 16f))
    }

    @Test
    fun `revealing a box below the view brings it a third of the way down`() {
        val by = pdfRevealBy(PdfRect(10f, 1000f, 50f, 1020f), 400f, 900f, 16f)
        assertEquals(0f, by.x)
        assertEquals(300f - 1000f, by.y)
    }

    @Test
    fun `revealing a box off to a side centres it across`() {
        val by = pdfRevealBy(PdfRect(500f, 100f, 540f, 120f), 400f, 800f, 16f)
        assertEquals(200f - 520f, by.x)
        assertEquals(0f, by.y)
    }
}
