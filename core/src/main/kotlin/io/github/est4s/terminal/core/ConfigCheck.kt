package io.github.est4s.terminal.core

import java.io.File

/** The user's settings folder, relative to root's home in Debian. */
const val CONFIG_DIR = ".config/pocket-terminal"

/** What's wrong in one config file; [file] is its Debian path, as users see it. */
data class ConfigProblems(val file: String, val problems: List<String>)

/**
 * Checks every config file the app reads under [home] (root's home in
 * Debian). The app skips bad lines when it loads them, so this is for
 * telling the user (or an agent) what was skipped.
 */
fun checkConfig(home: File): List<ConfigProblems> {
    val config = File(home, CONFIG_DIR)
    val found = mutableListOf<ConfigProblems>()
    fun report(file: File, problems: List<String>) {
        if (problems.isNotEmpty()) found += ConfigProblems("~/" + file.relativeTo(home).invariantSeparatorsPath, problems)
    }

    val colors = File(config, "colors.properties")
    if (colors.isFile) report(colors, parseColorScheme(colors.readText(), NEON).problems)

    val settings = File(config, "settings.conf")
    if (settings.isFile) {
        val parsed = parseSettings(settings.readText())
        val font = parsed.settings.font
        val fontMissing = font != "default" &&
            hostPath(font, home.parentFile.path)?.let { File(it).isFile } != true
        report(settings, parsed.problems + if (fontMissing) listOf("font: no such file $font") else emptyList())
    }

    val themes = File(home, THEMES_DIR).listFiles { f -> f.isFile && f.name.endsWith(THEME_SUFFIX) }.orEmpty()
    for (theme in themes.sortedBy { it.name }) report(theme, parseColorScheme(theme.readText(), NEON).problems)

    val bars = File(config, "keybars").listFiles { f -> f.isFile && f.name.endsWith(".conf") }.orEmpty()
    for (bar in bars.sortedBy { it.name }) {
        if (!isKeyBarName(bar.name.removeSuffix(".conf"))) {
            report(bar, listOf("not a usable bar name: use letters, digits, - and _"))
        } else {
            report(bar, parseKeyBar(bar.readText()).problems)
        }
    }
    return found
}
