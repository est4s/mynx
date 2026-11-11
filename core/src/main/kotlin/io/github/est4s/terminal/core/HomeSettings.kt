package io.github.est4s.terminal.core

import java.io.File
import java.util.Locale

/**
 * Touch mode's Settings, System and Help as Home's lists (maintainer's
 * decision, 2026-11-11: menus to tap, programs in tabs). Changes go
 * through the `mynx` requests ([HomeAction.Config]), so undo records them
 * and the app applies them as it does `mynx set`.
 */
data class HomeSettingGroup(val menu: String, val title: String, val keys: List<String>)

val HOME_SETTING_GROUPS = listOf(
    HomeSettingGroup("settings:chat", "Chat", listOf("view", "chat-mode", "chat-model", "agent-notify", "agent-notify-after")),
    HomeSettingGroup("settings:terminal", "Terminal", listOf("font-size", "font", "cursor-style", "cursor-blink", "tab-swipe")),
    HomeSettingGroup(
        "settings:phone", "Phone access",
        listOf(
            "android-clipboard", "android-share", "android-location", "android-sensors",
            "android-camera", "android-microphone", "android-bluetooth",
        ),
    ),
    HomeSettingGroup(
        "settings:background", "Background",
        listOf("wakelock", "sound-device", "share-folder", "update-check", "serial-preload", "serial-names", "undo-keep"),
    ),
)

private val SETTING_LABELS = mapOf(
    "view" to "View",
    "chat-mode" to "Permission mode",
    "chat-model" to "Model for new chats",
    "agent-notify" to "Notifications",
    "agent-notify-after" to "Notify after (seconds)",
    "font-size" to "Text size",
    "font" to "Font",
    "cursor-style" to "Cursor",
    "cursor-blink" to "Cursor blinks",
    "tab-swipe" to "Swipe between tabs",
    "android-clipboard" to "Clipboard",
    "android-share" to "Share sheet",
    "android-location" to "Location",
    "android-sensors" to "Sensors",
    "android-camera" to "Camera",
    "android-microphone" to "Microphone",
    "android-bluetooth" to "Bluetooth",
    "wakelock" to "Keep the CPU awake",
    "sound-device" to "Sound device",
    "share-folder" to "Shared files folder",
    "update-check" to "Check for updates",
    "serial-preload" to "Serial library",
    "serial-names" to "Serial port names",
    "undo-keep" to "Changes kept",
)

const val REPORT_NOTE = "What went wrong? Next, a tab shows the whole report (with the app's version, " +
    "the phone's model and the last crash) and asks before it sends anything."
private const val APP_SOURCE_URL = "https://github.com/est4s/mynx"
private const val OTHER_MODEL = "Other model…"
private const val UNDO_NOTE = "Each change to the settings, theme, key bars or menu, however it was made. Newest first."

private val TOUCH_WELCOME = listOf(
    "Chat" to "The home screen is Claude Code's: type to start a chat, or carry on with one under Continue. " +
        "It runs in the Debian on this phone, so it can install packages, write files and build apps.",
    "The drawer" to "☰ opens it: your chats, the Linux side (apps, games, services, files, settings) and Terminal.",
    "Terminal" to "Terminal shows the tabs, each a real bash with apt and thousands of programs. " +
        "What you install stays. Back comes back here.",
    "Settings" to "The chat's defaults, the terminal's theme and font, what programs may use on the phone, " +
        "and what runs in the background. Every setting is also a line in ~/.config/mynx/settings.conf, " +
        "which `mynx set` changes.",
)

/** What a phone and its Debian are: System info's rows. */
data class SystemFacts(
    val debian: String?,
    val kernel: String?,
    val arch: String?,
    val memoryFreeKb: Long?,
    val memoryTotalKb: Long?,
    val storageFreeKb: Long?,
    val storageTotalKb: Long?,
)

/** Reads [SystemFacts] for the Debian at [rootfs]: its version, the kernel, memory and the storage it's on. */
fun readSystemFacts(rootfs: File): SystemFacts {
    val meminfo = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())
    fun mem(key: String) = meminfo.firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    return SystemFacts(
        debian = runCatching { File(rootfs, "etc/debian_version").readText().trim() }.getOrNull(),
        kernel = System.getProperty("os.version"),
        arch = System.getProperty("os.arch"),
        memoryFreeKb = mem("MemAvailable"),
        memoryTotalKb = mem("MemTotal"),
        storageFreeKb = rootfs.usableSpace.takeIf { it > 0 }?.div(1024),
        storageTotalKb = rootfs.totalSpace.takeIf { it > 0 }?.div(1024),
    )
}

/** What an [HomeAction.Input]'s text does: its [target] is `set:KEY` or `report`. Nothing for blank text. */
fun homeInputAction(target: String, text: String): HomeAction {
    val value = text.trim()
    if (value.isEmpty()) return HomeAction.None
    return when {
        target.startsWith("set:") -> HomeAction.Config("set", listOf(target.removePrefix("set:"), value), back = true)
        target == "report" -> HomeAction.Tab(runThenShell("mynx report " + shellQuote(value)))
        else -> HomeAction.None
    }
}

internal class HomeSettings(
    private val apps: WebApps,
    private val facts: () -> SystemFacts,
    private val now: () -> Long,
) {
    private val config get() = File(apps.home, CONFIG_DIR)

    /** The list [menu], or null when it isn't one of these. */
    fun items(menu: String): List<HomeItem>? = when {
        menu == "settings" -> HOME_SETTING_GROUPS.map { HomeItem(it.title, HomeAction.Menu(it.menu)) } + listOf(
            HomeItem("Undo", HomeAction.Menu("undo")),
            HomeItem("System", HomeAction.Menu("system")),
            HomeItem("Help", HomeAction.Menu("help")),
        )
        menu == "settings:terminal" -> listOf(HomeItem("Theme", HomeAction.Menu("themes"), detail = currentTheme())) +
            group(menu) + HomeItem("Key bars and launcher menu", menuActTab("settings"))
        menu.startsWith("settings:") -> HOME_SETTING_GROUPS.firstOrNull { it.menu == menu }?.let { group(menu) }
        menu.startsWith("setting:") -> choices(menu.removePrefix("setting:"))
        menu == "themes" -> themes()
        menu == "undo" -> undo()
        menu == "info" -> info()
        menu == "help" -> listOf(
            HomeItem("Getting started", HomeAction.Menu("welcome")),
            HomeItem("Release notes", HomeAction.Menu("notes")),
            HomeItem("Report a bug", HomeAction.Input("Report a bug", "", REPORT_NOTE, "report")),
            HomeItem("About", HomeAction.Menu("about")),
        )
        menu == "welcome" -> TOUCH_WELCOME.flatMap { (title, text) ->
            listOf(HomeItem(title, HomeAction.None, kind = HomeRow.SECTION), HomeItem(text, HomeAction.None, kind = HomeRow.TEXT))
        }
        menu == "notes" -> notes()
        menu == "about" -> listOfNotNull(
            version()?.let { HomeItem("Version", HomeAction.None, detail = it, kind = HomeRow.INFO) },
            HomeItem("Licence", HomeAction.None, detail = "MIT", kind = HomeRow.INFO),
            HomeItem("Source", HomeAction.Link(APP_SOURCE_URL), detail = APP_SOURCE_URL.removePrefix("https://")),
            HomeItem("What it's made with", menuActTab("about")),
        )
        else -> null
    }

    private fun values(): Map<String, String> = loadSettings(File(config, "settings.conf")).settings.values()

    private fun group(menu: String): List<HomeItem> {
        val values = values()
        return HOME_SETTING_GROUPS.first { it.menu == menu }.keys.map { key -> settingItem(key, values.getValue(key)) }
    }

    private fun settingItem(key: String, value: String): HomeItem {
        val def = SETTINGS.first { it.key == key }
        val label = SETTING_LABELS[key] ?: key
        return when {
            def.choices == listOf("on", "off") ->
                HomeItem(label, HomeAction.Config("set", listOf(key, if (value == "on") "off" else "on")), detail = onOff(value))
            def.choices != null || key == "chat-mode" || key == "chat-model" ->
                HomeItem(label, HomeAction.Menu("setting:$key"), detail = valueName(key, value))
            else -> HomeItem(label, HomeAction.Input(label, value, def.description, "set:$key"), detail = value)
        }
    }

    private fun onOff(value: String) = if (value == "on") "On" else "Off"

    private fun valueName(key: String, value: String): String = when (key) {
        "chat-mode" -> chatModeName(value)
        "chat-model" -> chatDefaultModelChoices(emptyList()).firstOrNull { it.value == value }?.name ?: value
        "view" -> value.replaceFirstChar { it.uppercase() }
        else -> value
    }

    private fun choices(key: String): List<HomeItem>? {
        val def = SETTINGS.firstOrNull { it.key == key } ?: return null
        val value = values().getValue(key)
        val label = SETTING_LABELS[key] ?: key
        fun choice(name: String, choice: String) =
            HomeItem(name, HomeAction.Config("set", listOf(key, choice), back = true), checked = value == choice)
        val list = when (key) {
            "chat-mode" -> CHAT_MODES.map { choice(chatModeName(it.value), it.value) }
            "chat-model" -> {
                val models = chatDefaultModelChoices(emptyList())
                models.map { choice(it.name, it.value) } + HomeItem(
                    OTHER_MODEL,
                    HomeAction.Input(label, if (models.any { it.value == value }) "" else value, def.description, "set:$key"),
                )
            }
            else -> def.choices.orEmpty().map { choice(valueName(key, it), it) }
        }
        return listOf(HomeItem(def.description, HomeAction.None, kind = HomeRow.TEXT)) + list
    }

    private fun currentTheme(): String {
        val colors = File(config, "colors.properties").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            ?: return BUILT_IN_THEMES.first()
        return themeNameOf(colors) ?: "your own colours"
    }

    private fun themes(): List<HomeItem> {
        val user = File(apps.home, THEMES_DIR).listFiles { f -> f.isFile && f.name.endsWith(THEME_SUFFIX) }.orEmpty()
            .associateBy { it.name.removeSuffix(THEME_SUFFIX) }.filterKeys { isKeyBarName(it) }.toSortedMap()
        val current = currentTheme()
        val names = BUILT_IN_THEMES.filter { it !in user } + user.keys
        return names.map { name ->
            val text = user[name]?.let { runCatching { it.readText() }.getOrNull() } ?: builtInThemeText(name).orEmpty()
            HomeItem(name, HomeAction.Config("theme-set", listOf(name)), checked = name == current, swatch = swatch(text))
        }
    }

    // The background, the text, then colours 1 to 6: what a theme looks like at a glance.
    private fun swatch(text: String): List<Int> {
        val scheme = parseColorScheme(text, NEON).scheme
        return listOf(scheme.background, scheme.foreground) + (1..6).map { scheme.palette.getValue(it) }
    }

    private fun undo(): List<HomeItem> {
        val steps = ConfigHistory(config, File(apps.home, UNDO_DIR)).steps()
        val keep = values().getValue("undo-keep")
        val kept = settingItem("undo-keep", keep)
        if (steps.isEmpty()) return listOf(HomeItem("Undo last change", HomeAction.None), HomeItem("(nothing to undo)", HomeAction.None), kept)
        val now = now()
        return listOf(
            HomeItem("Undo last change", HomeAction.Config("undo", emptyList())),
            HomeItem(UNDO_NOTE, HomeAction.None, kind = HomeRow.TEXT),
        ) + steps.map { HomeItem(it.reason, HomeAction.None, detail = chatRelativeTime(now, it.time), kind = HomeRow.INFO) } + kept
    }

    private fun info(): List<HomeItem> {
        val facts = facts()
        fun row(label: String, value: String?) = value?.let { HomeItem(label, HomeAction.None, detail = it, kind = HomeRow.INFO) }
        fun freeOf(free: Long?, total: Long?) = if (free != null && total != null) "${size(free)} free of ${size(total)}" else null
        return listOfNotNull(
            row("mynx", version()),
            row("Debian", facts.debian),
            row("Kernel", facts.kernel),
            row("Arch", facts.arch),
            row("Memory", freeOf(facts.memoryFreeKb, facts.memoryTotalKb)),
            row("Storage", freeOf(facts.storageFreeKb, facts.storageTotalKb)),
        )
    }

    private fun size(kb: Long): String = when {
        kb >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f GB", kb / 1024.0 / 1024.0)
        kb >= 1024 -> String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }

    // The tools' `.version`: BUILD-INSTALLTIME-NAME.
    private fun version(): String? {
        val text = runCatching { File(apps.toolsDir, ".version").readText().trim() }.getOrNull() ?: return null
        val parts = text.split('-', limit = 3)
        return if (parts.size == 3) "${parts[2]} (build ${parts[0]})" else text
    }

    private fun notes(): List<HomeItem> {
        val text = runCatching { File(apps.toolsDir, "release-notes.json").readText() }.getOrNull()
        val releases = text?.let { runCatching { parseJson(it) }.getOrNull() } as? List<*>
        val items = releases.orEmpty().filterIsInstance<Map<*, *>>().flatMap { release ->
            val version = release["version"] as? String ?: return@flatMap emptyList()
            val date = (release["published"] as? String)?.take(10)
            listOf(HomeItem(listOfNotNull(version, date).joinToString(" · "), HomeAction.None, kind = HomeRow.SECTION)) +
                plainMarkdown(release["notes"] as? String ?: "").map { HomeItem(it, HomeAction.None, kind = HomeRow.TEXT) }
        }
        return items.ifEmpty { listOf(HomeItem("(no release notes here)", HomeAction.None)) }
    }
}

private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val MD_MARKS = Regex("\\*\\*|__|`")

// GitHub's generated notes as plain lines: headings bare, bullets as •, links as their text.
private fun plainMarkdown(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
    val bare = when {
        line.startsWith("#") -> line.trimStart('#').trim()
        line.startsWith("* ") || line.startsWith("- ") -> "• " + line.drop(2).trim()
        else -> line
    }
    MD_MARKS.replace(MD_LINK.replace(bare) { it.groupValues[1] }, "")
}
