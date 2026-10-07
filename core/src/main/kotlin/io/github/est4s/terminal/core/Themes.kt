package io.github.est4s.terminal.core

/** The themes shipped in the app, default first (resources can't be listed on Android). */
val BUILT_IN_THEMES = listOf(
    "neon", "amber", "phosphor", "dracula", "nord", "gruvbox-dark",
    "solarized-dark", "solarized-light", "catppuccin-mocha", "tokyo-night",
)

/** A user's themes: `~/.config/mynx/themes/NAME.colors.properties`. */
const val THEMES_DIR = "$CONFIG_DIR/themes"
const val THEME_SUFFIX = ".colors.properties"

private val THEME_LINE = Regex("#\\s*theme:\\s*(\\S+)\\s*")

fun builtInThemeText(name: String): String? =
    if (name in BUILT_IN_THEMES) ColorScheme::class.java.getResource("themes/$name$THEME_SUFFIX")?.readText() else null

/** The theme a colours file was set from (its first line, `# theme: NAME`), if any. */
fun themeNameOf(colorsFile: String): String? =
    THEME_LINE.matchEntire(colorsFile.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)

/** What `mynx theme set` writes into the colours file. */
internal fun colorsFileFor(theme: String, text: String) =
    "# theme: $theme\n# Set by `mynx theme set $theme`; edit freely.\n$text"
