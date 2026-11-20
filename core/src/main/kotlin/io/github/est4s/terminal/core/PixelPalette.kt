package io.github.est4s.terminal.core

import kotlin.math.roundToInt

/**
 * Touch mode's colours (`PixelStyle.kt`): [accent] is the cyan of the main
 * actions, [mark] the pink of shadows and marks, [markText] its readable
 * tint, [scrim] what lies over a screen behind a drawer.
 */
data class PixelPalette(
    val background: Int,
    val card: Int,
    val line: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val mark: Int,
    val markText: Int,
    val scrim: Int,
)

/** The logo's own colours: Neon's touch screens. */
val LOGO_PALETTE = PixelPalette(
    background = 0xFF0D0221.toInt(),
    card = 0xFF170838.toInt(),
    line = 0xFF3D2A70.toInt(),
    text = 0xFFF2ECFF.toInt(),
    dim = 0xFFB3A6DC.toInt(),
    accent = 0xFF00F3FF.toInt(),
    mark = 0xFFFF0BBB.toInt(),
    markText = 0xFFFF7FDC.toInt(),
    scrim = 0xB30D0221.toInt(),
)

/**
 * The touch screens follow the terminal's theme (maintainer, 2026-11-11):
 * [colorsFile] is `colors.properties`' text, null when there's none. Neon
 * (the default) keeps the logo's palette; any other theme gives its
 * background, text, colour 6 (cyan) and 5 (magenta), with cards, lines and
 * dim text mixed between its background and text.
 */
fun pixelPalette(colorsFile: String?): PixelPalette {
    if (colorsFile == null || themeNameOf(colorsFile) == "neon") return LOGO_PALETTE
    val scheme = parseColorScheme(colorsFile, NEON).scheme
    val bg = scheme.background
    val fg = scheme.foreground
    return PixelPalette(
        background = bg,
        card = mix(bg, fg, 0.06),
        line = mix(bg, fg, 0.22),
        text = fg,
        dim = mix(bg, fg, 0.68),
        accent = scheme.palette.getValue(6),
        mark = scheme.palette.getValue(5),
        markText = scheme.palette.getValue(13),
        scrim = (bg and 0x00FFFFFF) or 0xB3000000.toInt(),
    )
}

private fun mix(from: Int, to: Int, amount: Double): Int {
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * amount).roundToInt() shl shift
    }
    return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
}
