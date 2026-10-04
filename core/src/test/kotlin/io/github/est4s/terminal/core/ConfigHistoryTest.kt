package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigHistoryTest {
    private val base = createTempDirectory("history").toFile()
    private val config = File(base, "config")
    private val history = ConfigHistory(config, File(base, "state"), now = { 1_000L })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(config, path).apply { parentFile.mkdirs() }.writeText(text)
    private fun read(path: String) = File(config, path).takeIf { it.isFile }?.readText()

    @Test
    fun `the first look only remembers the config`() {
        write("settings.conf", "font-size = 14\n")
        history.observe("edits by hand", keep = 1)
        assertEquals(emptyList(), history.steps())
        assertNull(history.undo(keep = 1))
    }

    @Test
    fun `a change can be undone`() {
        write("settings.conf", "font-size = 14\n")
        history.observe("edits by hand", keep = 1)
        write("settings.conf", "font-size = 16\n")
        history.observe("set font-size 16", keep = 1)

        assertEquals(listOf(UndoStep("set font-size 16", 1_000L)), history.steps())
        assertEquals("set font-size 16", history.undo(keep = 1))
        assertEquals("font-size = 14\n", read("settings.conf"))
        assertEquals(emptyList(), history.steps())
    }

    @Test
    fun `nothing changed, nothing to undo`() {
        write("settings.conf", "font-size = 14\n")
        history.observe("edits by hand", keep = 1)
        history.observe("set font-size 14", keep = 1)
        assertEquals(emptyList(), history.steps())
    }

    @Test
    fun `undo puts back deleted files and removes new ones`() {
        write("colors.properties", "background=#000000\n")
        history.observe("edits by hand", keep = 1)
        File(config, "colors.properties").delete()
        write("keybars/htop.conf", "Quit = q\n")
        history.observe("theme reset", keep = 1)

        history.undo(keep = 1)

        assertEquals("background=#000000\n", read("colors.properties"))
        assertFalse(File(config, "keybars/htop.conf").exists())
    }

    @Test
    fun `edits by hand that were never checked are undone first`() {
        write("settings.conf", "font-size = 14\n")
        history.observe("edits by hand", keep = 1)
        write("settings.conf", "font-size = oops\n")

        assertEquals("edits by hand", history.undo(keep = 1))
        assertEquals("font-size = 14\n", read("settings.conf"))
    }

    @Test
    fun `keeps only as many steps as asked, newest first`() {
        write("settings.conf", "a\n")
        history.observe("edits by hand", keep = 2)
        for (value in listOf("b", "c", "d")) {
            write("settings.conf", "$value\n")
            history.observe("set $value", keep = 2)
        }
        assertEquals(listOf("set d", "set c"), history.steps().map { it.reason })

        assertEquals("set d", history.undo(keep = 2))
        assertEquals("set c", history.undo(keep = 2))
        assertEquals("b\n", read("settings.conf"))
        assertNull(history.undo(keep = 2))
    }

    @Test
    fun `lowering keep drops the oldest steps`() {
        write("settings.conf", "a\n")
        history.observe("edits by hand", keep = 3)
        write("settings.conf", "b\n"); history.observe("set b", keep = 3)
        write("settings.conf", "c\n"); history.observe("set c", keep = 3)
        write("settings.conf", "d\n"); history.observe("set d", keep = 1)
        assertEquals(listOf("set d"), history.steps().map { it.reason })
    }

    @Test
    fun `keep 0 turns undo off`() {
        write("settings.conf", "a\n")
        history.observe("edits by hand", keep = 0)
        write("settings.conf", "b\n")
        history.observe("set b", keep = 0)
        assertEquals(emptyList(), history.steps())
        assertNull(history.undo(keep = 0))
        assertEquals("b\n", read("settings.conf"))
    }

    @Test
    fun `fonts, big files and temporary files aren't kept`() {
        history.observe("edits by hand", keep = 1)
        write("fonts/Hack.ttf", "font")
        write("big.conf", "x".repeat(300 * 1024))
        write("settings.conf.tmp", "half")
        history.observe("added fonts", keep = 1)
        assertEquals(emptyList(), history.steps())

        write("settings.conf", "a\n")
        history.observe("set a", keep = 1)
        history.undo(keep = 1)
        assertTrue(File(config, "fonts/Hack.ttf").exists())
        assertNull(read("settings.conf"))
    }
}
