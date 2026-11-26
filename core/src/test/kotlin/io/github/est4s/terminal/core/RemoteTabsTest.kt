package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Terminal tabs the host sent (the extended screen): shown here, running there. */
class RemoteTabsTest {
    private val tabs = Tabs<String>()

    @Test
    fun `a tab from the other phone opens after the selected one, named for that phone`() {
        tabs.open("a")
        tabs.open("b")
        tabs.select(0)
        val tab = tabs.openFrom("r", "Pixel 10", "bash")
        assertEquals(listOf("a", "r", "b"), tabs.tabs.map { it.session })
        assertEquals(tab, tabs.selected)
        assertEquals("Pixel 10", tab.from)
        assertEquals("Pixel 10: bash", tab.title)
        assertNull(tabs.tabs[0].from)
    }

    @Test
    fun `its title follows the host's tab, and a rename here still wins`() {
        tabs.openFrom("r", "Pixel 10", "bash")
        tabs.setShellTitle("r", "htop")
        assertEquals("Pixel 10: htop", tabs.selected!!.title)
        tabs.rename("r", "monitor")
        assertEquals("monitor", tabs.selected!!.title)
    }

    @Test
    fun `it takes no number from this phone's tabs`() {
        tabs.openFrom("r", "Pixel 10", "")
        tabs.open("a")
        assertEquals("Tab 1", tabs.find("a")!!.title)
        assertEquals("Pixel 10: terminal", tabs.find("r")!!.title)
    }

    @Test
    fun `it isn't saved, since it lives on the other phone`() {
        tabs.open("a")
        tabs.openFrom("r", "Pixel 10", "bash")
        tabs.open("b")
        val saved = tabs.snapshot { "/root/$it" }
        assertEquals(listOf("/root/a", "/root/b"), saved.tabs.map { it.cwd })
        assertEquals(1, saved.selected)
    }
}
