package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PixelPaletteTest {
    @Test
    fun `with no colours file, or Neon, the touch screens keep the logo's palette`() {
        assertEquals(LOGO_PALETTE, pixelPalette(null))
        assertEquals(LOGO_PALETTE, pixelPalette(colorsFileFor("neon", builtInThemeText("neon")!!)))
        assertEquals(0xFF0D0221.toInt(), LOGO_PALETTE.background)
        assertEquals(0xFF00F3FF.toInt(), LOGO_PALETTE.accent)
        assertEquals(0xFFFF0BBB.toInt(), LOGO_PALETTE.mark)
    }

    @Test
    fun `another theme gives its background, text, cyan and magenta`() {
        val palette = pixelPalette(colorsFileFor("nord", builtInThemeText("nord")!!))
        val nord = parseColorScheme(builtInThemeText("nord")!!, NEON).scheme
        assertEquals(nord.background, palette.background)
        assertEquals(nord.foreground, palette.text)
        assertEquals(nord.palette.getValue(6), palette.accent)
        assertEquals(nord.palette.getValue(5), palette.mark)
        assertEquals(nord.palette.getValue(13), palette.markText)
        assertEquals((nord.background and 0x00FFFFFF) or 0xB3000000.toInt(), palette.scrim)
    }

    @Test
    fun `cards, lines and dim text lie between the background and the text`() {
        val palette = pixelPalette("background=#000000\nforeground=#ffffff\n")
        assertEquals(0xFF0F0F0F.toInt(), palette.card)
        assertEquals(0xFF383838.toInt(), palette.line)
        assertEquals(0xFFADADAD.toInt(), palette.dim)
    }

    @Test
    fun `colours edited by hand count, even with no theme line`() {
        assertNotEquals(LOGO_PALETTE, pixelPalette("background=#101010\n"))
        assertEquals(0xFF101010.toInt(), pixelPalette("background=#101010\n").background)
    }
}
