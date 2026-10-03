package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabStateTest {
    private val saved = SavedTabs(
        tabs = listOf(SavedTab(), SavedTab(name = "build logs", cwd = "/root/src"), SavedTab(cwd = "/tmp")),
        selected = 1,
    )

    @Test
    fun `writes readable text`() {
        val text = saved.serialize()

        assertTrue(text.startsWith("#"))
        assertTrue("version = 1" in text)
        assertTrue("selected = 2" in text)
        assertTrue("[tab]\nname = build logs\ncwd = /root/src\n" in text)
    }

    @Test
    fun `reads back what it writes`() {
        assertEquals(saved, parseSavedTabs(saved.serialize()))
    }

    @Test
    fun `ignores comments, blank lines, spacing and unknown keys`() {
        val text = """
            # hello
            version=1
              selected   =   1
            colour = red

            [tab]
            name =   a b  
            future = thing
        """.trimIndent()

        assertEquals(SavedTabs(listOf(SavedTab(name = "a b")), selected = 0), parseSavedTabs(text))
    }

    @Test
    fun `selects the first tab when the selection is missing or out of range`() {
        assertEquals(0, parseSavedTabs("version = 1\n[tab]\n[tab]\n")?.selected)
        assertEquals(0, parseSavedTabs("version = 1\nselected = 9\n[tab]\n[tab]\n")?.selected)
        assertEquals(0, parseSavedTabs("version = 1\nselected = x\n[tab]\n")?.selected)
    }

    @Test
    fun `gives up on files it can't use`() {
        assertNull(parseSavedTabs(""))
        assertNull(parseSavedTabs("version = 1\n"))
        assertNull(parseSavedTabs("version = 2\n[tab]\n"))
        assertNull(parseSavedTabs("[tab]\n"))
        assertNull(parseSavedTabs("\u0000\u0001garbage"))
    }

    @Test
    fun `keeps names on one line`() {
        val text = SavedTabs(listOf(SavedTab(name = "a\nselected = 5")), 0).serialize()

        assertEquals("a selected = 5", parseSavedTabs(text)?.tabs?.single()?.name)
    }

    @Test
    fun `snapshots the tabs with renames and folders`() {
        val tabs = Tabs<String>()
        listOf("a", "b").forEach { tabs.open(it) }
        tabs.setShellTitle("a", "shell title")
        tabs.rename("b", "logs")
        tabs.select(0)

        val snapshot = tabs.snapshot { if (it == "b") "/var/log" else null }

        assertEquals(SavedTabs(listOf(SavedTab(), SavedTab(name = "logs", cwd = "/var/log")), selected = 0), snapshot)
    }

    @Test
    fun `restores tabs in order with their names and selection`() {
        val tabs = Tabs<String>()
        val started = mutableListOf<String?>()

        tabs.restore(saved) { cwd -> started += cwd; "s${started.size}" }

        assertEquals(listOf(null, "/root/src", "/tmp"), started)
        assertEquals(listOf("s1", "s2", "s3"), tabs.tabs.map { it.session })
        assertEquals(listOf("Tab 1", "build logs", "Tab 3"), tabs.tabs.map { it.title })
        assertEquals("s2", tabs.selected?.session)
    }
}
