package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabsTest {
    private val tabs = Tabs<String>()

    private fun sessions() = tabs.tabs.map { it.session }

    private fun openAll(vararg sessions: String) = sessions.forEach { tabs.open(it) }

    @Test
    fun `starts empty with nothing selected`() {
        assertTrue(tabs.isEmpty)
        assertNull(tabs.selected)
        assertEquals(-1, tabs.selectedIndex)
    }

    @Test
    fun `opens a tab after the current one and selects it`() {
        openAll("a", "b", "c")
        tabs.select(0)

        tabs.open("d")

        assertEquals(listOf("a", "d", "b", "c"), sessions())
        assertEquals("d", tabs.selected?.session)
    }

    @Test
    fun `closing the selected tab selects the one to its right`() {
        openAll("a", "b", "c")
        tabs.select(1)

        tabs.close("b")

        assertEquals(listOf("a", "c"), sessions())
        assertEquals("c", tabs.selected?.session)
    }

    @Test
    fun `closing the selected last tab selects the one to its left`() {
        openAll("a", "b", "c")

        tabs.close("c")

        assertEquals("b", tabs.selected?.session)
    }

    @Test
    fun `closing another tab keeps the selection`() {
        openAll("a", "b", "c")

        tabs.close("a")

        assertEquals("c", tabs.selected?.session)
        assertEquals(1, tabs.selectedIndex)
    }

    @Test
    fun `closing the only tab leaves nothing selected`() {
        tabs.open("a")

        tabs.close("a")

        assertTrue(tabs.isEmpty)
        assertNull(tabs.selected)
    }

    @Test
    fun `closing an unknown session changes nothing`() {
        openAll("a", "b")

        tabs.close("zzz")

        assertEquals(listOf("a", "b"), sessions())
        assertEquals("b", tabs.selected?.session)
    }

    @Test
    fun `next and previous wrap around`() {
        openAll("a", "b", "c")

        tabs.next()
        assertEquals("a", tabs.selected?.session)
        tabs.previous()
        assertEquals("c", tabs.selected?.session)
        tabs.previous()
        assertEquals("b", tabs.selected?.session)
    }

    @Test
    fun `select ignores an index out of range`() {
        openAll("a", "b")

        tabs.select(5)

        assertEquals("b", tabs.selected?.session)
    }

    @Test
    fun `moves the selected tab left and right, stopping at the ends`() {
        openAll("a", "b", "c")
        tabs.select(1)

        tabs.moveLeft()
        assertEquals(listOf("b", "a", "c"), sessions())
        tabs.moveLeft()
        assertEquals(listOf("b", "a", "c"), sessions())
        tabs.moveRight()
        tabs.moveRight()
        tabs.moveRight()
        assertEquals(listOf("a", "c", "b"), sessions())
        assertEquals("b", tabs.selected?.session)
    }

    @Test
    fun `a tab without a title is named by a number that stays put`() {
        openAll("a", "b", "c")
        tabs.select(0)
        tabs.moveRight()

        assertEquals(listOf("Tab 2", "Tab 1", "Tab 3"), tabs.tabs.map { it.title })
    }

    @Test
    fun `a new tab reuses the lowest free number`() {
        openAll("a", "b", "c")
        tabs.close("b")

        tabs.open("d")

        assertEquals("Tab 2", tabs.find("d")?.title)
    }

    @Test
    fun `the shell title names the tab`() {
        tabs.open("a")

        tabs.setShellTitle("a", "vim notes.txt")

        assertEquals("vim notes.txt", tabs.selected?.title)
    }

    @Test
    fun `a blank shell title falls back to the number`() {
        tabs.open("a")
        tabs.setShellTitle("a", "vim")

        tabs.setShellTitle("a", "  ")

        assertEquals("Tab 1", tabs.selected?.title)
    }

    @Test
    fun `a rename overrides the shell title until it is cleared`() {
        tabs.open("a")
        tabs.setShellTitle("a", "bash")

        tabs.rename("a", "build")
        tabs.setShellTitle("a", "make")
        assertEquals("build", tabs.selected?.title)

        tabs.rename("a", "")
        assertEquals("make", tabs.selected?.title)
    }

    @Test
    fun `output and bell mark background tabs only`() {
        openAll("a", "b")

        tabs.onOutput("a")
        tabs.onBell("a")
        tabs.onOutput("b")
        tabs.onBell("b")

        val (a, b) = tabs.tabs
        assertTrue(a.activity)
        assertTrue(a.bell)
        assertFalse(b.activity)
        assertFalse(b.bell)
    }

    @Test
    fun `selecting a tab clears its marks`() {
        openAll("a", "b")
        tabs.onOutput("a")
        tabs.onBell("a")

        tabs.select(0)

        assertFalse(tabs.tabs[0].activity)
        assertFalse(tabs.tabs[0].bell)
    }

    @Test
    fun `replacing a session keeps the tab's place, name and number`() {
        openAll("a", "b")
        tabs.rename("a", "logs")

        tabs.replaceSession("a", "a2")

        assertEquals(listOf("a2", "b"), sessions())
        assertEquals("logs", tabs.tabs[0].title)
        tabs.rename("a2", null)
        assertEquals("Tab 1", tabs.tabs[0].title)
    }

    @Test
    fun `reports every change`() {
        var changes = 0
        val tabs = Tabs<String> { changes++ }

        tabs.open("a")
        tabs.open("b")
        tabs.select(0)
        tabs.moveRight()
        tabs.setShellTitle("a", "x")
        tabs.rename("a", "y")
        tabs.onOutput("b")
        tabs.close("b")

        assertEquals(8, changes)
    }

    @Test
    fun `doesn't report marking a tab that is already marked`() {
        var changes = 0
        val tabs = Tabs<String> { changes++ }
        tabs.open("a")
        tabs.open("b")
        changes = 0

        tabs.onOutput("a")
        tabs.onOutput("a")

        assertEquals(1, changes)
    }

    @Test
    fun `a shell that exits cleanly closes its tab, a failing one stays open`() {
        assertTrue(closesOnExit(0))
        assertFalse(closesOnExit(1))
        assertFalse(closesOnExit(-9))
    }
}
