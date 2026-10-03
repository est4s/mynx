package io.github.est4s.terminal.core

import java.io.File

/** What a key bar button sends, in order. */
sealed interface KeyStroke {
    /** A key from [KEY_NAMES], or a single character, with modifiers. */
    data class Key(val key: String, val ctrl: Boolean = false, val alt: Boolean = false) : KeyStroke
    /** Typed as is, without modifiers. */
    data class Text(val text: String) : KeyStroke
    /** Sticky Ctrl: applies to the next key typed. */
    data object CtrlLatch : KeyStroke
}

data class KeyButton(val label: String, val strokes: List<KeyStroke>)

data class ParsedKeyBar(val buttons: List<KeyButton>, val problems: List<String>)

/** A bar ready to show; [source] is the file it came from (or the built-in's name). */
data class LoadedKeyBar(val name: String, val buttons: List<KeyButton>, val problems: List<String>, val source: String)

const val SHELL_KEY_BAR = "shell"

/** Named keys, in their usual spelling. */
val KEY_NAMES: List<String> =
    listOf("Enter", "Esc", "Tab", "Space", "Backspace", "Delete", "Insert", "Up", "Down", "Left", "Right",
        "Home", "End", "PgUp", "PgDn") + (1..12).map { "F$it" }

private val KEY_NAMES_BY_LOWER = KEY_NAMES.associateBy { it.lowercase() }
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
    val strokes = tokenize(line.substringAfter('=')).map(::parseStroke)
    if (strokes.isEmpty()) throw BadLine("no keys")
    if (KeyStroke.CtrlLatch in strokes && strokes.size > 1) throw BadLine("Ctrl on its own must be the only key")
    KeyButton(label, strokes)
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
    while (true) {
        when {
            rest.length > 5 && rest.startsWith("Ctrl+", ignoreCase = true) -> { ctrl = true; rest = rest.substring(5) }
            rest.length > 4 && rest.startsWith("Alt+", ignoreCase = true) -> { alt = true; rest = rest.substring(4) }
            else -> break
        }
    }
    val key = KEY_NAMES_BY_LOWER[rest.lowercase()]
        ?: rest.takeIf { it.codePointCount(0, it.length) == 1 }
        ?: throw BadLine("unknown key '$word'")
    return KeyStroke.Key(key, ctrl, alt)
}

/** The bar a tab's program asked for (see the `keybar` command); the shell's if none or invalid. */
fun keyBarName(reported: String?): String =
    reported?.trim()?.takeIf { BAR_NAME.matches(it) } ?: SHELL_KEY_BAR

/** A built-in bar's file, as shipped with the app. */
fun builtInKeyBarText(name: String): String? =
    if (BAR_NAME.matches(name)) KeyBarResources::class.java.getResource("keybars/$name.conf")?.readText() else null

private object KeyBarResources

/**
 * The bar [name]: the user's `NAME.conf` in [userDir] if there is one,
 * else the built-in, else the shell's bar.
 */
fun loadKeyBar(name: String, userDir: File): LoadedKeyBar {
    val userFile = File(userDir, "$name.conf").takeIf { BAR_NAME.matches(name) && it.isFile }
    if (userFile != null) {
        val parsed = parseKeyBar(userFile.readText())
        return LoadedKeyBar(name, parsed.buttons, parsed.problems, userFile.path)
    }
    val builtIn = builtInKeyBarText(name)
        ?: return if (name == SHELL_KEY_BAR) LoadedKeyBar(name, emptyList(), emptyList(), "none")
        else loadKeyBar(SHELL_KEY_BAR, userDir)
    val parsed = parseKeyBar(builtIn)
    return LoadedKeyBar(name, parsed.buttons, parsed.problems, "built-in $name")
}
