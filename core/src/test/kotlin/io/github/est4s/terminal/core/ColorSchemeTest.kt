package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorSchemeTest {
    private val base = ColorScheme(
        foreground = 0xffffffff.toInt(),
        background = 0xff000000.toInt(),
        cursor = null,
        palette = mapOf(1 to 0xffcd0000.toInt()),
    )

    @Test
    fun `reads the Termux colors properties format`() {
        val text = """
            # Neon
            background=#14101f
            foreground=#e4eef4
            cursor=#ff7edb

            color0=#241b35
            color15=#ffffff
            color255=#010203
        """.trimIndent()

        val parsed = parseColorScheme(text, base)

        assertEquals(emptyList(), parsed.problems)
        assertEquals(0xff14101f.toInt(), parsed.scheme.background)
        assertEquals(0xffe4eef4.toInt(), parsed.scheme.foreground)
        assertEquals(0xffff7edb.toInt(), parsed.scheme.cursor)
        assertEquals(0xff241b35.toInt(), parsed.scheme.palette[0])
        assertEquals(0xffffffff.toInt(), parsed.scheme.palette[15])
        assertEquals(0xff010203.toInt(), parsed.scheme.palette[255])
    }

    @Test
    fun `keys missing from the file keep the base colours`() {
        val parsed = parseColorScheme("color2=#72f1b8\n", base)

        assertEquals(base.foreground, parsed.scheme.foreground)
        assertEquals(base.background, parsed.scheme.background)
        assertNull(parsed.scheme.cursor)
        assertEquals(mapOf(1 to 0xffcd0000.toInt(), 2 to 0xff72f1b8.toInt()), parsed.scheme.palette)
    }

    @Test
    fun `allows spacing, upper case hex and bang comments`() {
        val parsed = parseColorScheme("  ! comment\n  color1  =  #FE4450  \r\n", base)

        assertEquals(emptyList(), parsed.problems)
        assertEquals(0xfffe4450.toInt(), parsed.scheme.palette[1])
    }

    @Test
    fun `reports bad lines with their line numbers and skips them`() {
        val text = """
            background=#14101f
            colour1=#ffffff
            color256=#ffffff
            color1=red
            color2=#12345
            just some words
            foreground=#e4eef4
        """.trimIndent()

        val parsed = parseColorScheme(text, base)

        assertEquals(
            listOf(
                "line 2: unknown key 'colour1'",
                "line 3: unknown key 'color256'",
                "line 4: 'red' is not a #rrggbb colour",
                "line 5: '#12345' is not a #rrggbb colour",
                "line 6: expected key=value",
            ),
            parsed.problems,
        )
        assertEquals(0xff14101f.toInt(), parsed.scheme.background)
        assertEquals(0xffe4eef4.toInt(), parsed.scheme.foreground)
        assertEquals(base.palette, parsed.scheme.palette)
    }

    @Test
    fun `the built-in neon scheme is complete and clean`() {
        val parsed = parseColorScheme(NEON_COLORS_PROPERTIES, base)

        assertEquals(emptyList(), parsed.problems)
        assertEquals(NEON, parsed.scheme)
        assertEquals((0..15).toSet(), NEON.palette.keys)
        assertEquals(0xff14101f.toInt(), NEON.background)
        assertEquals(0xffff7edb.toInt(), NEON.cursor)
        assertTrue(NEON_COLORS_PROPERTIES.startsWith("#"))
    }

    @Test
    fun `loading a missing file gives neon`() {
        val parsed = loadColorScheme(File(createTempDirectory("colors").toFile(), "nope.properties"))

        assertEquals(NEON, parsed.scheme)
        assertEquals(emptyList(), parsed.problems)
    }

    @Test
    fun `loading a file lays it over neon`() {
        val file = File(createTempDirectory("colors").toFile(), "colors.properties")
        file.writeText("background=#000000\nbogus\n")

        val parsed = loadColorScheme(file)

        assertEquals(0xff000000.toInt(), parsed.scheme.background)
        assertEquals(NEON.foreground, parsed.scheme.foreground)
        assertEquals(listOf("line 2: expected key=value"), parsed.problems)
        file.parentFile.deleteRecursively()
    }

    @Test
    fun `tab strip colours come from the scheme`() {
        val strip = NEON.stripColors()

        assertEquals(NEON.background, strip.background)
        assertEquals(NEON.palette[0], strip.selectedBackground)
        assertEquals(NEON.cursor, strip.accent)
        assertEquals(NEON.palette[6], strip.mark)
        assertEquals(NEON.palette[7], strip.text)
    }

    @Test
    fun `tab strip falls back to the foreground when the scheme lacks colours`() {
        val strip = base.copy(palette = emptyMap()).stripColors()

        assertEquals(base.background, strip.selectedBackground)
        assertEquals(base.foreground, strip.accent)
        assertEquals(base.foreground, strip.mark)
        assertEquals(base.foreground, strip.text)
    }

    @Test
    fun `tab strip and key bar stay readable in every built-in theme`() {
        for (name in BUILT_IN_THEMES) {
            val strip = parseColorScheme(builtInThemeText(name)!!, NEON).scheme.stripColors()
            assertTrue(contrast(strip.text, strip.background) >= 4.5, "$name: text")
            assertTrue(contrast(strip.accent, strip.background) >= 3.0, "$name: accent")
            assertTrue(contrast(strip.accent, strip.selectedBackground) >= 3.0, "$name: accent on the selected tab")
            assertTrue(contrast(strip.mark, strip.background) >= 3.0, "$name: marks")
        }
    }

    @Test
    fun `a light theme's pale colour 7 isn't used for text`() {
        val light = base.copy(background = 0xfffdf6e3.toInt(), foreground = 0xff073642.toInt(),
            palette = mapOf(7 to 0xffeee8d5.toInt(), 0 to 0xff073642.toInt()))

        assertEquals(light.foreground, light.stripColors().text)
    }

    @Test
    fun `contrast is the WCAG ratio`() {
        assertEquals(21.0, contrast(0xff000000.toInt(), 0xffffffff.toInt()), 0.01)
        assertEquals(1.0, contrast(0xff777777.toInt(), 0xff777777.toInt()), 0.01)
    }
}
