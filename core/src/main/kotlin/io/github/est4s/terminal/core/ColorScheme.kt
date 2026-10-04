package io.github.est4s.terminal.core

import java.io.File

/** Terminal colours as `0xAARRGGBB`. [palette] maps indexes 0-255 to colours; missing ones keep the terminal's default. */
data class ColorScheme(val foreground: Int, val background: Int, val cursor: Int?, val palette: Map<Int, Int>)

data class ParsedColorScheme(val scheme: ColorScheme, val problems: List<String>)

data class StripColors(val background: Int, val selectedBackground: Int, val accent: Int, val mark: Int, val text: Int)

// Before NEON: top-level vals initialize in order.
private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

/** The built-in theme, in the same format users edit (also shipped into Debian). */
val NEON_COLORS_PROPERTIES: String =
    ColorScheme::class.java.getResource("themes/neon.colors.properties")!!.readText()

val NEON: ColorScheme = parseColorScheme(
    NEON_COLORS_PROPERTIES,
    ColorScheme(foreground = 0, background = 0, cursor = null, palette = emptyMap()),
).scheme

/**
 * Reads Termux's `colors.properties` format on top of [base]. Bad lines are
 * reported in [ParsedColorScheme.problems] and skipped, never fatal: a typo
 * in a theme must not stop the terminal from opening.
 */
fun parseColorScheme(text: String, base: ColorScheme): ParsedColorScheme {
    var scheme = base
    val palette = base.palette.toMutableMap()
    val problems = mutableListOf<String>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachIndexed
        val problem = "line ${i + 1}: "
        if ('=' !in line) {
            problems += problem + "expected key=value"
            return@forEachIndexed
        }
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        val index = key.removePrefix("color").toIntOrNull()?.takeIf { key.startsWith("color") && it in 0..255 }
        if (key !in setOf("foreground", "background", "cursor") && index == null) {
            problems += problem + "unknown key '$key'"
            return@forEachIndexed
        }
        val color = parseHexColor(value)
        if (color == null) {
            problems += problem + "'$value' is not a #rrggbb colour"
            return@forEachIndexed
        }
        when (key) {
            "foreground" -> scheme = scheme.copy(foreground = color)
            "background" -> scheme = scheme.copy(background = color)
            "cursor" -> scheme = scheme.copy(cursor = color)
            else -> palette[index!!] = color
        }
    }
    return ParsedColorScheme(scheme.copy(palette = palette), problems)
}

/** The user's colours file laid over [NEON]; neon alone if there's no file. */
fun loadColorScheme(file: File): ParsedColorScheme =
    if (file.isFile) parseColorScheme(file.readText(), NEON) else ParsedColorScheme(NEON, emptyList())

/**
 * Colours for the tab strip and key bar. The theme's own picks (colour 7
 * for text, colour 0 for the selected tab, the cursor colour as accent)
 * when they're readable; light themes often need others.
 */
fun ColorScheme.stripColors(): StripColors {
    val text = readable(background, 4.5, palette[7], foreground, palette[0], palette[8])
    // Colour 0 is dark: right for a dark theme's selected tab, not a light one's.
    val selected = palette[0]?.takeIf { contrast(it, background) < 3.0 } ?: blend(background, foreground, 0.12)
    val accent = listOfNotNull(cursor, foreground, palette[5], palette[4])
        .firstOrNull { contrast(it, background) >= 3.0 && contrast(it, selected) >= 3.0 } ?: text
    val mark = readable(background, 3.0, palette[6], foreground)
    return StripColors(background, if (palette[0] == null) background else selected, accent, mark, text)
}

/** The first of [candidates] with at least [ratio] contrast on [background], else the best one. */
private fun readable(background: Int, ratio: Double, vararg candidates: Int?): Int {
    val colors = candidates.filterNotNull()
    return colors.firstOrNull { contrast(it, background) >= ratio } ?: colors.maxBy { contrast(it, background) }
}

/** WCAG contrast ratio of two colours, from 1 (same) to 21 (black on white). */
fun contrast(a: Int, b: Int): Double {
    val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (light + 0.05) / (dark + 0.05)
}

private fun luminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val c = (color shr shift and 0xff) / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun blend(from: Int, to: Int, amount: Double): Int {
    fun channel(shift: Int): Int {
        val a = from shr shift and 0xff
        val b = to shr shift and 0xff
        return (a + (b - a) * amount).toInt() shl shift
    }
    return (0xff000000.toInt()) or channel(16) or channel(8) or channel(0)
}

private fun parseHexColor(value: String): Int? =
    if (HEX_COLOR.matches(value)) (0xff000000 or value.substring(1).toLong(16)).toInt() else null
