package io.github.est4s.terminal.core

import java.io.File

/** What a key bar button sends, in order. */
sealed interface KeyStroke {
    /** A key from [KEY_NAMES], or a single character, with modifiers ([shift]: named keys only). */
    data class Key(val key: String, val ctrl: Boolean = false, val alt: Boolean = false,
                   val shift: Boolean = false) : KeyStroke
    /** Typed as is, without modifiers. */
    data class Text(val text: String) : KeyStroke
    /** Sticky Ctrl: applies to the next key typed. */
    data object CtrlLatch : KeyStroke
}

/** [repeat]: sends again and again while held. */
data class KeyButton(val label: String, val strokes: List<KeyStroke>, val repeat: Boolean = false)

data class ParsedKeyBar(val buttons: List<KeyButton>, val problems: List<String>)

/** A bar ready to show; [source] is the file it came from (or the built-in's name). */
data class LoadedKeyBar(val name: String, val buttons: List<KeyButton>, val problems: List<String>, val source: String)

const val SHELL_KEY_BAR = "shell"

/** Named keys, in their usual spelling. */
val KEY_NAMES: List<String> =
    listOf("Enter", "Esc", "Tab", "Space", "Backspace", "Delete", "Insert", "Up", "Down", "Left", "Right",
        "Home", "End", "PgUp", "PgDn") + (1..12).map { "F$it" }

private val KEY_NAMES_BY_LOWER = KEY_NAMES.associateBy { it.lowercase() }

// Held, these repeat without being asked to, like on a hardware keyboard.
private val REPEATING_KEYS = setOf("Up", "Down", "Left", "Right", "PgUp", "PgDn", "Backspace", "Delete")
private val BAR_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")

/**
 * Reads a key bar file: one button per line, `label = keys`. Like the
 * colours file, bad lines are reported and skipped, never fatal.
 */
fun parseKeyBar(text: String): ParsedKeyBar {
    val buttons = mutableListOf<KeyButton>()
    val problems = mutableListOf<String>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val problem = parseButton(line).fold({ buttons += it; null }, { it.message })
        if (problem != null) problems += "line ${i + 1}: $problem"
    }
    return ParsedKeyBar(buttons, problems)
}

private class BadLine(message: String) : Exception(message)

private fun parseButton(line: String): Result<KeyButton> = runCatching {
    if ('=' !in line) throw BadLine("expected label = keys")
    val label = line.substringBefore('=').trim()
    if (label.isEmpty()) throw BadLine("no label")
    val tokens = tokenize(line.substringAfter('='))
    val repeatAsked = tokens.any { it is Token.Word && it.word.equals("Repeat", ignoreCase = true) }
    val strokes = tokens
        .filterNot { it is Token.Word && it.word.equals("Repeat", ignoreCase = true) }
        .map(::parseStroke)
    if (strokes.isEmpty()) throw BadLine("no keys")
    if (KeyStroke.CtrlLatch in strokes && strokes.size > 1) throw BadLine("Ctrl on its own must be the only key")
    val repeatsAnyway = (strokes.singleOrNull() as? KeyStroke.Key)?.key in REPEATING_KEYS
    KeyButton(label, strokes, repeat = repeatAsked || repeatsAnyway)
}

private sealed interface Token {
    data class Word(val word: String) : Token
    data class Quoted(val text: String) : Token
}

private fun tokenize(value: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c.isWhitespace() -> i++
            c == '"' -> {
                val text = StringBuilder()
                i++
                while (true) {
                    if (i >= value.length) throw BadLine("missing closing quote")
                    val q = value[i++]
                    if (q == '"') break
                    if (q == '\\' && i < value.length) text.append(value[i++]) else text.append(q)
                }
                tokens += Token.Quoted(text.toString())
            }
            else -> {
                val start = i
                while (i < value.length && !value[i].isWhitespace()) i++
                tokens += Token.Word(value.substring(start, i))
            }
        }
    }
    return tokens
}

private fun parseStroke(token: Token): KeyStroke {
    val word = when (token) {
        is Token.Quoted -> return KeyStroke.Text(token.text)
        is Token.Word -> token.word
    }
    if (word.equals("Ctrl", ignoreCase = true)) return KeyStroke.CtrlLatch
    var rest = word
    var ctrl = false
    var alt = false
    var shift = false
    while (true) {
        when {
            rest.length > 6 && rest.startsWith("Shift+", ignoreCase = true) -> { shift = true; rest = rest.substring(6) }
            rest.length > 5 && rest.startsWith("Ctrl+", ignoreCase = true) -> { ctrl = true; rest = rest.substring(5) }
            rest.length > 4 && rest.startsWith("Alt+", ignoreCase = true) -> { alt = true; rest = rest.substring(4) }
            else -> break
        }
    }
    val named = KEY_NAMES_BY_LOWER[rest.lowercase()]
    val key = named
        ?: rest.takeIf { it.codePointCount(0, it.length) == 1 }
        ?: throw BadLine("unknown key '$word'")
    if (shift && named == null) throw BadLine("Shift only goes with named keys; type '${key.uppercase()}' for a capital")
    return KeyStroke.Key(key, ctrl, alt, shift)
}

/**
 * The bars a tab's program asked for, best first (see the `keybar`
 * command: `name,fallback,…`). Invalid names are dropped; the shell's bar
 * if nothing is left.
 */
fun keyBarNames(reported: String?): List<String> =
    reported.orEmpty().split(',').map { it.trim() }.filter { BAR_NAME.matches(it) }
        .ifEmpty { listOf(SHELL_KEY_BAR) }

internal fun isKeyBarName(name: String) = BAR_NAME.matches(name)

/** The bars shipped in the app (resources can't be listed on Android). */
val BUILT_IN_KEY_BARS = listOf("shell", "nnn", "menu", "pocket-edit", "game", "neon-rogue", "neon-drive", "neon-flap", "agent")

/** A built-in bar's file, as shipped with the app. */
fun builtInKeyBarText(name: String): String? =
    if (BAR_NAME.matches(name)) KeyBarResources::class.java.getResource("keybars/$name.conf")?.readText() else null

private object KeyBarResources

/**
 * The first of [names] that exists, as the user's `NAME.conf` in
 * [userDir] or a built-in (the user's wins); else the shell's bar.
 */
fun loadKeyBar(names: List<String>, userDir: File): LoadedKeyBar {
    for (name in names + SHELL_KEY_BAR) {
        if (!BAR_NAME.matches(name)) continue
        val userFile = File(userDir, "$name.conf")
        if (userFile.isFile) {
            val parsed = parseKeyBar(userFile.readText())
            return LoadedKeyBar(name, parsed.buttons, parsed.problems, userFile.path)
        }
        val builtIn = builtInKeyBarText(name) ?: continue
        val parsed = parseKeyBar(builtIn)
        return LoadedKeyBar(name, parsed.buttons, parsed.problems, "built-in $name")
    }
    return LoadedKeyBar(SHELL_KEY_BAR, emptyList(), emptyList(), "none")
}

fun loadKeyBar(name: String, userDir: File): LoadedKeyBar = loadKeyBar(listOf(name), userDir)

/**
 * Lays out [count] buttons as pages of [rows] rows of indexes, at most
 * [perRow] to a row. Two rows unless the screen is squeezed (see
 * [fitAroundTerminal]), so the terminal keeps its size when the bar
 * changes; the first half of a two-row page goes on top.
 */
fun keyBarPages(count: Int, perRow: Int, rows: Int = 2): List<List<List<Int>>> {
    val perRow = perRow.coerceAtLeast(1)
    if (rows < 2) return (0 until count).chunked(perRow).map { listOf(it) }
    return (0 until count).chunked(2 * perRow).map { page ->
        val top = (page.size + 1) / 2
        listOf(page.take(top), page.drop(top))
    }
}

/** How many rows the key bar gets, and whether the tab strip shows. */
data class BarFit(val rows: Int, val strip: Boolean)

/**
 * Makes room for the terminal (e.g. in landscape with the keyboard up),
 * all sizes in pixels: the terminal needs [terminal] of the [available]
 * height. The bar gives up its second row first, then the tab strip goes;
 * the terminal must never get no height, or it loses the keyboard.
 */
fun fitAroundTerminal(available: Int, strip: Int, barRow: Int, terminal: Int): BarFit = when {
    available - strip - 2 * barRow >= terminal -> BarFit(rows = 2, strip = true)
    available - strip - barRow >= terminal -> BarFit(rows = 1, strip = true)
    else -> BarFit(rows = 1, strip = false)
}
