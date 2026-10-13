package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ServiceTabsTest {
    private var changes = 0
    private val tabs = Tabs<String> { changes++ }

    private fun sessions() = tabs.tabs.map { it.session }

    @Test
    fun `a service opens as a selected tab after the current one, marked with its name`() {
        tabs.open("a")
        tabs.open("b")
        tabs.select(0)

        val tab = tabs.openService("web", "web#1")

        assertEquals(listOf("a", "web#1", "b"), sessions())
        assertSame(tab, tabs.selected)
        assertEquals("web", tab.service)
        assertEquals("● web", tab.title)
        assertNull(tabs.tabs[0].service, "shell tabs aren't services")
    }

    @Test
    fun `opening a service that's open selects its tab`() {
        tabs.openService("web", "web#1")
        tabs.open("a")

        tabs.openService("web", "web#1")

        assertEquals(listOf("web#1", "a"), sessions())
        assertEquals("web#1", tabs.selected?.session)
    }

    @Test
    fun `a service's tab follows its new session`() {
        tabs.openService("web", "web#1")
        tabs.open("a")

        assertEquals("web#1", tabs.followService("web", "web#2"))

        assertEquals(listOf("web#2", "a"), sessions())
        assertNull(tabs.followService("db", "db#1"), "no tab: nothing to follow")
        assertNull(tabs.followService("web", "web#2"), "already shown")
    }

    @Test
    fun `opening a service whose tab shows an old session shows the new one`() {
        tabs.openService("web", "web#1")

        tabs.openService("web", "web#2")

        assertEquals(listOf("web#2"), sessions())
    }

    @Test
    fun `a tab becomes a service where it is`() {
        tabs.open("a")
        tabs.open("b")
        tabs.setShellTitle("b", "~/src")

        tabs.makeService("b", "build")

        assertEquals(listOf("a", "b"), sessions())
        assertEquals("build", tabs.tabs[1].service)
        assertEquals("● build", tabs.tabs[1].title)
    }

    @Test
    fun `the service's state shows in its tab's title`() {
        tabs.openService("web", "web#1")
        tabs.openService("db", "db#1")
        tabs.open("a")
        changes = 0

        tabs.setServiceTitles { name -> if (name == "web") "○ web: exit 1" else null }

        assertEquals(listOf("○ web: exit 1", "● db", "Tab 1"), tabs.tabs.map { it.title })
        assertEquals(1, changes)
        tabs.setServiceTitles { name -> if (name == "web") "○ web: exit 1" else null }
        assertEquals(1, changes, "nothing changed: no redraw")
    }

    @Test
    fun `a shell title or a rename doesn't hide a service's name`() {
        tabs.openService("web", "web#1")
        tabs.setShellTitle("web#1", "python3")
        tabs.rename("web#1", "x")

        assertEquals("● web", tabs.selected?.title)
    }

    @Test
    fun `the tabs of services that went are closed`() {
        tabs.open("a")
        tabs.openService("web", "web#1")
        tabs.openService("db", "db#1")

        tabs.closeServicesExcept(setOf("db"))

        assertEquals(listOf("a", "db#1"), sessions())
    }

    @Test
    fun `service tabs aren't saved, and the selection stays on the tab it was nearest`() {
        tabs.open("a")
        tabs.openService("web", "web#1")
        tabs.open("b")
        tabs.select(1)

        assertEquals(SavedTabs(listOf(SavedTab(), SavedTab()), selected = 0), tabs.snapshot { null })
        tabs.select(2)
        assertEquals(1, tabs.snapshot { null }.selected)
    }

    @Test
    fun `with only service tabs nothing is saved`() {
        tabs.openService("web", "web#1")

        assertEquals(SavedTabs(emptyList(), selected = 0), tabs.snapshot { null })
    }
}
