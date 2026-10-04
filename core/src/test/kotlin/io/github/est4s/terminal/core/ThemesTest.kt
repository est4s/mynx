package io.github.est4s.terminal.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemesTest {
    @Test
    fun `the built-in theme list matches the theme files`() {
        assertEquals(resourceNames("themes", ".colors.properties"), BUILT_IN_THEMES.sorted())
        assertEquals("neon", BUILT_IN_THEMES.first())
    }

    @Test
    fun `every built-in theme sets every basic colour, with no problems`() {
        val keys = listOf("background", "foreground", "cursor") + (0..15).map { "color$it" }
        for (name in BUILT_IN_THEMES) {
            val text = builtInThemeText(name)!!
            assertEquals(emptyList(), parseColorScheme(text, NEON).problems, name)
            val set = text.lines().filter { '=' in it && !it.startsWith("#") }.map { it.substringBefore('=') }
            assertEquals(keys.sorted(), set.sorted(), name)
        }
    }

    @Test
    fun `Neon is the default scheme`() {
        assertEquals(NEON, parseColorScheme(builtInThemeText("neon")!!, NEON).scheme)
    }

    @Test
    fun `the colours file names the theme it was set from`() {
        assertEquals("nord", themeNameOf("# theme: nord\nbackground=#000000\n"))
        assertEquals(null, themeNameOf("background=#000000\n"))
    }

    @Test
    fun `the built-in key bar list matches the bar files`() {
        assertEquals(resourceNames("keybars", ".conf"), BUILT_IN_KEY_BARS.sorted())
    }

    private fun resourceNames(dir: String, suffix: String): List<String> {
        val folder = File(ThemesTest::class.java.getResource("/io/github/est4s/terminal/core/$dir")!!.toURI())
        return folder.list()!!.filter { it.endsWith(suffix) }.map { it.removeSuffix(suffix) }.sorted()
    }
}
