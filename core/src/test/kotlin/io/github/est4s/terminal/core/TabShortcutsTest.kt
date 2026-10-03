package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabShortcutsTest {
    @Test
    fun `ctrl shift T opens and ctrl shift W closes a tab`() {
        assertEquals(TabAction.New, tabShortcut(KeyPress("T", ctrl = true, shift = true)))
        assertEquals(TabAction.Close, tabShortcut(KeyPress("W", ctrl = true, shift = true)))
    }

    @Test
    fun `ctrl shift R renames the tab`() {
        assertEquals(TabAction.Rename, tabShortcut(KeyPress("R", ctrl = true, shift = true)))
        assertNull(tabShortcut(KeyPress("R", ctrl = true)))
    }

    @Test
    fun `ctrl tab and ctrl shift tab switch tabs`() {
        assertEquals(TabAction.Next, tabShortcut(KeyPress("TAB", ctrl = true)))
        assertEquals(TabAction.Previous, tabShortcut(KeyPress("TAB", ctrl = true, shift = true)))
    }

    @Test
    fun `ctrl alt and a digit jump to that tab`() {
        assertEquals(TabAction.GoTo(0), tabShortcut(KeyPress("1", ctrl = true, alt = true)))
        assertEquals(TabAction.GoTo(8), tabShortcut(KeyPress("9", ctrl = true, alt = true)))
    }

    @Test
    fun `ctrl alt 0 is not a tab`() {
        assertNull(tabShortcut(KeyPress("0", ctrl = true, alt = true)))
    }

    @Test
    fun `ctrl shift page up and down move the tab`() {
        assertEquals(TabAction.MoveLeft, tabShortcut(KeyPress("PAGE_UP", ctrl = true, shift = true)))
        assertEquals(TabAction.MoveRight, tabShortcut(KeyPress("PAGE_DOWN", ctrl = true, shift = true)))
    }

    @Test
    fun `keys without the exact modifiers go to the terminal`() {
        assertNull(tabShortcut(KeyPress("T", ctrl = true)))
        assertNull(tabShortcut(KeyPress("T", ctrl = true, shift = true, alt = true)))
        assertNull(tabShortcut(KeyPress("TAB")))
        assertNull(tabShortcut(KeyPress("1", ctrl = true)))
        assertNull(tabShortcut(KeyPress("PAGE_UP", shift = true)))
    }

    @Test
    fun `applies actions to the tabs`() {
        val tabs = Tabs<String>()
        listOf("a", "b", "c").forEach { tabs.open(it) }

        TabAction.GoTo(0).applyTo(tabs)
        assertEquals("a", tabs.selected?.session)
        TabAction.Next.applyTo(tabs)
        assertEquals("b", tabs.selected?.session)
        TabAction.Previous.applyTo(tabs)
        assertEquals("a", tabs.selected?.session)
        TabAction.MoveRight.applyTo(tabs)
        assertEquals(listOf("b", "a", "c"), tabs.tabs.map { it.session })
        TabAction.MoveLeft.applyTo(tabs)
        assertEquals(listOf("a", "b", "c"), tabs.tabs.map { it.session })
    }
}
