package io.github.est4s.terminal.core

import java.io.File

/** The app's settings, from `~/.config/pocket-terminal/settings.conf`. [fontSize] is in dp. */
data class Settings(
    val fontSize: Int = 12,
    val font: String = "default",
    val cursorStyle: String = "block",
    val cursorBlink: Boolean = false,
    val agentNotify: Boolean = true,
    /** Seconds an agent's turn must take before its end notifies. */
    val agentNotifyAfter: Int = 30,
    /** How many config changes `pocket undo` can take back. */
    val undoKeep: Int = 1,
    /** Whether programs in Debian may read and change the phone's clipboard. */
    val androidClipboard: Boolean = true,
    /** Whether programs in Debian may open Android's share sheet. */
    val androidShare: Boolean = true,
    /** Whether programs in Debian may ask for the phone's location. */
    val androidLocation: Boolean = true,
    /** Whether programs in Debian may read the phone's sensors. */
    val androidSensors: Boolean = true,
    /** Where files shared to the app from other apps go, in Debian. */
    val shareFolder: String = "~/Shared",
) {
    fun values(): Map<String, String> = mapOf(
        "font-size" to fontSize.toString(),
        "font" to font,
        "cursor-style" to cursorStyle,
        "cursor-blink" to if (cursorBlink) "on" else "off",
        "agent-notify" to if (agentNotify) "on" else "off",
        "agent-notify-after" to agentNotifyAfter.toString(),
        "undo-keep" to undoKeep.toString(),
        "android-clipboard" to if (androidClipboard) "on" else "off",
        "android-share" to if (androidShare) "on" else "off",
        "android-location" to if (androidLocation) "on" else "off",
        "android-sensors" to if (androidSensors) "on" else "off",
        "share-folder" to shareFolder,
    )
}

data class ParsedSettings(val settings: Settings, val problems: List<String>)

/** One setting: what `pocket settings` and the editors show. [choices] is null for free values. */
data class SettingDef(
    val key: String,
    val description: String,
    val default: String,
    val choices: List<String>?,
    internal val check: (String) -> String?,
    internal val apply: Settings.(String) -> Settings,
)

const val MIN_FONT_SIZE = 6
const val MAX_FONT_SIZE = 40
const val MAX_AGENT_NOTIFY_AFTER = 3600
const val MAX_UNDO_KEEP = 20

private fun oneOf(key: String, choices: List<String>): (String) -> String? =
    { if (it in choices) null else "$key must be one of: ${choices.joinToString(", ")}" }

val SETTINGS: List<SettingDef> = listOf(
    SettingDef(
        "font-size", "Text size ($MIN_FONT_SIZE-$MAX_FONT_SIZE). Pinching the terminal changes it too.", "12", null,
        { if (it.toIntOrNull() in MIN_FONT_SIZE..MAX_FONT_SIZE) null else "font-size must be a whole number from $MIN_FONT_SIZE to $MAX_FONT_SIZE" },
        { copy(fontSize = it.toInt()) },
    ),
    SettingDef(
        "font", "'default' (JetBrains Mono Nerd Font) or the full path of a .ttf or .otf file in Debian.", "default", null,
        {
            val path = it.lowercase()
            if (it == "default" || (it.startsWith("/") && (path.endsWith(".ttf") || path.endsWith(".otf")))) null
            else "font must be 'default' or the full path of a .ttf or .otf file"
        },
        { copy(font = it) },
    ),
    SettingDef(
        "cursor-style", "Cursor shape. Programs can change it while they run.", "block", listOf("block", "underline", "bar"),
        oneOf("cursor-style", listOf("block", "underline", "bar")),
        { copy(cursorStyle = it) },
    ),
    SettingDef(
        "cursor-blink", "Whether the cursor blinks.", "off", listOf("on", "off"),
        oneOf("cursor-blink", listOf("on", "off")),
        { copy(cursorBlink = it == "on") },
    ),
    SettingDef(
        "agent-notify", "Phone notifications from AI agents (Claude Code, …) when they finish or need you.",
        "on", listOf("on", "off"),
        oneOf("agent-notify", listOf("on", "off")),
        { copy(agentNotify = it == "on") },
    ),
    SettingDef(
        "agent-notify-after", "Only notify about a finished agent turn that took at least this many seconds (0-$MAX_AGENT_NOTIFY_AFTER).",
        "30", null,
        {
            if (it.toIntOrNull() in 0..MAX_AGENT_NOTIFY_AFTER) null
            else "agent-notify-after must be a whole number of seconds from 0 to $MAX_AGENT_NOTIFY_AFTER"
        },
        { copy(agentNotifyAfter = it.toInt()) },
    ),
    SettingDef(
        "undo-keep", "How many config changes pocket undo can take back (0-$MAX_UNDO_KEEP; 0 turns undo off).",
        "1", null,
        { if (it.toIntOrNull() in 0..MAX_UNDO_KEEP) null else "undo-keep must be a whole number from 0 to $MAX_UNDO_KEEP" },
        { copy(undoKeep = it.toInt()) },
    ),
    SettingDef(
        "android-clipboard", "Whether programs in Debian can read and change the phone's clipboard (pocket clipboard).",
        "on", listOf("on", "off"),
        oneOf("android-clipboard", listOf("on", "off")),
        { copy(androidClipboard = it == "on") },
    ),
    SettingDef(
        "android-share", "Whether programs in Debian can share files and text with other apps (pocket share).",
        "on", listOf("on", "off"),
        oneOf("android-share", listOf("on", "off")),
        { copy(androidShare = it == "on") },
    ),
    SettingDef(
        "android-location", "Whether programs in Debian can ask for the phone's location (pocket location).",
        "on", listOf("on", "off"),
        oneOf("android-location", listOf("on", "off")),
        { copy(androidLocation = it == "on") },
    ),
    SettingDef(
        "android-sensors", "Whether programs in Debian can read the phone's sensors (pocket sensor).",
        "on", listOf("on", "off"),
        oneOf("android-sensors", listOf("on", "off")),
        { copy(androidSensors = it == "on") },
    ),
    SettingDef(
        "share-folder", "Where files shared to the app from other apps are saved: a full path or one starting with ~/.",
        "~/Shared", null,
        { if (it.startsWith("/") || (it.startsWith("~/") && it.length > 2)) null else "share-folder must be a full path or start with ~/" },
        { copy(shareFolder = it) },
    ),
)

private val SETTINGS_BY_KEY = SETTINGS.associateBy { it.key }

private const val SETTINGS_HEADER = """# App settings: one "key = value" per line. `pocket settings` lists
# them all, `pocket set KEY VALUE` changes one, `pocket check` applies
# edits made by hand.
"""

/** Reads a settings file; bad lines are reported and skipped, like the colours file. */
fun parseSettings(text: String): ParsedSettings {
    var settings = Settings()
    val problems = mutableListOf<String>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val problem = "line ${i + 1}: "
        if ('=' !in line) {
            problems += problem + "expected key = value"
            return@forEachIndexed
        }
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        val def = SETTINGS_BY_KEY[key]
        if (def == null) {
            problems += problem + "unknown setting '$key'"
            return@forEachIndexed
        }
        val error = def.check(value)
        if (error != null) problems += problem + error else settings = def.apply(settings, value)
    }
    return ParsedSettings(settings, problems)
}

fun loadSettings(file: File): ParsedSettings =
    if (file.isFile) parseSettings(file.readText()) else ParsedSettings(Settings(), emptyList())

/**
 * [text] (the settings file, null if there's none) with [key] set to
 * [value]: its line replaced, other lines and comments kept. Fails with a
 * message for people if the key or value is wrong.
 */
fun setSetting(text: String?, key: String, value: String): Result<String> = runCatching {
    val def = SETTINGS_BY_KEY[key] ?: error("unknown setting '$key' (pocket settings lists them)")
    def.check(value)?.let { error(it) }
    val newLine = "$key = $value"
    val lines = (text ?: SETTINGS_HEADER).lines().dropLastWhile { it.isEmpty() }.toMutableList()
    val index = lines.indexOfFirst { isLineFor(it, key) }
    if (index < 0) {
        lines += newLine
    } else {
        lines[index] = newLine
        // A key set twice: the later line would win, so drop it.
        for (i in lines.indices.reversed()) if (i != index && isLineFor(lines[i], key)) lines.removeAt(i)
    }
    lines.joinToString("\n", postfix = "\n")
}

/** [text] (the settings file) without [key]'s lines, so it's back to its default. */
fun unsetSetting(text: String, key: String): Result<String> = runCatching {
    if (key !in SETTINGS_BY_KEY) error("unknown setting '$key' (pocket settings lists them)")
    text.lines().dropLastWhile { it.isEmpty() }.filterNot { isLineFor(it, key) }
        .joinToString("\n", postfix = "\n")
}

private fun isLineFor(line: String, key: String): Boolean {
    val trimmed = line.trim()
    return !trimmed.startsWith("#") && '=' in trimmed && trimmed.substringBefore('=').trim() == key
}
