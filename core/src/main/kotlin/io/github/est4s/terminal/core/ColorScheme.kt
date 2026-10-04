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

fun ColorScheme.stripColors() = StripColors(
    background = background,
    selectedBackground = palette[0] ?: background,
    accent = cursor ?: foreground,
    mark = palette[6] ?: foreground,
    text = palette[7] ?: foreground,
)

private fun parseHexColor(value: String): Int? =
    if (HEX_COLOR.matches(value)) (0xff000000 or value.substring(1).toLong(16)).toInt() else null
