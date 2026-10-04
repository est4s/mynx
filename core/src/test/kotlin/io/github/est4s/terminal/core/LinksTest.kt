package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    private fun tap(rows: List<String>, row: Int, col: Int, width: Int = 20, wraps: List<Boolean>? = null) =
        linkAt(rows, wraps ?: rows.map { false }, row, col, width)

    @Test
    fun `finds the link under a tap`() {
        assertEquals("https://x.io/a", tap(listOf("see https://x.io/a ok"), 0, 8, width = 40))
    }

    @Test
    fun `a tap beside a link finds nothing`() {
        assertNull(tap(listOf("see https://x.io/a ok"), 0, 1, width = 40))
        assertNull(tap(listOf("no links here"), 0, 3))
    }

    @Test
    fun `follows a link the terminal wrapped onto more rows`() {
        val rows = listOf("go https://example.c", "om/oauth?code=1&x=y ", "next")
        val wraps = listOf(true, false, false)
        val url = "https://example.com/oauth?code=1&x=y"
        assertEquals(url, tap(rows, 0, 5, wraps = wraps))
        assertEquals(url, tap(rows, 1, 3, wraps = wraps))
        assertNull(tap(rows, 2, 1, wraps = wraps))
    }

    @Test
    fun `follows a link a program broke at the edge itself`() {
        // Full rows without the wrap mark: the program printed its own line breaks.
        val rows = listOf("https://claude.ai/oa", "uth/authorize?client", "_id=abc", "")
        assertEquals("https://claude.ai/oauth/authorize?client_id=abc", tap(rows, 2, 2))
    }

    @Test
    fun `leaves out punctuation after a link`() {
        assertEquals("https://x.io/a", tap(listOf("(see https://x.io/a)."), 0, 10, width = 40))
        assertEquals("https://x.io/a_(b)", tap(listOf("at https://x.io/a_(b), ok"), 0, 10, width = 40))
    }

    @Test
    fun `only web links open`() {
        assertEquals(true, isWebLink("https://claude.ai/x"))
        assertEquals(true, isWebLink("http://localhost:1455/auth"))
        assertEquals(false, isWebLink("file:///etc/passwd"))
        assertEquals(false, isWebLink("javascript:alert(1)"))
        assertEquals(false, isWebLink("https://"))
    }
}
