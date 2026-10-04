package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun `no file means the defaults`() {
        assertEquals(ParsedSettings(Settings(), emptyList()), parseSettings(""))
        assertEquals(Settings(fontSize = 12, font = "default", cursorStyle = "block", cursorBlink = false), Settings())
    }

    @Test
    fun `reads key = value lines, ignoring comments and blank lines`() {
        val parsed = parseSettings(
            """
            # my settings
            font-size = 16
            cursor-style=bar
              cursor-blink = on
            font = /usr/share/fonts/truetype/hack/Hack-Regular.ttf
            """.trimIndent()
        )

        assertEquals(emptyList(), parsed.problems)
        assertEquals(Settings(16, "/usr/share/fonts/truetype/hack/Hack-Regular.ttf", "bar", true), parsed.settings)
    }

    @Test
    fun `bad lines are reported and keep the default`() {
        val parsed = parseSettings(
            """
            font-size = huge
            font-size = 99
            cursor-style = blob
            cursor-blink = maybe
            colour = red
            nonsense
            font = Hack.ttf
            """.trimIndent()
        )

        assertEquals(Settings(), parsed.settings)
        assertEquals(
            listOf(
                "line 1: font-size must be a whole number from 6 to 40",
                "line 2: font-size must be a whole number from 6 to 40",
                "line 3: cursor-style must be one of: block, underline, bar",
                "line 4: cursor-blink must be one of: on, off",
                "line 5: unknown setting 'colour'",
                "line 6: expected key = value",
                "line 7: font must be 'default' or the full path of a .ttf or .otf file",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `every setting is described, with its default`() {
        assertEquals(listOf("font-size", "font", "cursor-style", "cursor-blink"), SETTINGS.map { it.key })
        SETTINGS.forEach { assertTrue(it.description.isNotBlank(), it.key) }
        assertEquals(listOf("block", "underline", "bar"), SETTINGS.single { it.key == "cursor-style" }.choices)
        assertEquals("12", SETTINGS.single { it.key == "font-size" }.default)
    }

    @Test
    fun `values reads each setting as text`() {
        assertEquals(
            mapOf("font-size" to "14", "font" to "default", "cursor-style" to "block", "cursor-blink" to "on"),
            Settings(fontSize = 14, cursorBlink = true).values(),
        )
    }

    @Test
    fun `setting a value replaces its line and keeps everything else`() {
        val text = "# mine\nfont-size = 12  \ncursor-style = bar\n"

        assertEquals("# mine\nfont-size = 16\ncursor-style = bar\n", setSetting(text, "font-size", "16").getOrThrow())
    }

    @Test
    fun `setting a value missing from the file adds it at the end`() {
        assertEquals("# mine\ncursor-blink = on\n", setSetting("# mine", "cursor-blink", "on").getOrThrow())
    }

    @Test
    fun `setting a value in a new file starts it with a header`() {
        val text = setSetting(null, "font-size", "14").getOrThrow()

        assertTrue(text.startsWith("# "), text)
        assertTrue(text.endsWith("\nfont-size = 14\n"), text)
        assertEquals(Settings(fontSize = 14), parseSettings(text).settings)
    }

    @Test
    fun `setting a duplicated key keeps only the new value`() {
        assertEquals("font-size = 8\n", setSetting("font-size = 12\nfont-size = 20\n", "font-size", "8").getOrThrow())
    }

    @Test
    fun `refuses unknown keys and bad values, with the reason`() {
        assertEquals("unknown setting 'colour' (pocket settings lists them)",
            setSetting("", "colour", "red").exceptionOrNull()?.message)
        assertEquals("font-size must be a whole number from 6 to 40",
            setSetting("", "font-size", "100").exceptionOrNull()?.message)
    }
}
