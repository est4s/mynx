package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeSettingsTest {
    private val top = Files.createTempDirectory("home-settings").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val facts = SystemFacts(
        debian = "13.2", kernel = "6.6.127-android15", arch = "aarch64",
        memoryFreeKb = 3_000_000, memoryTotalKb = 11_800_000,
        storageFreeKb = 52_000_000, storageTotalKb = 230_000_000,
    )

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    private fun menus() = HomeMenus(WebApps(home, rootfs.path, tools), facts = { facts }, now = { 1_000_000_000L })
    private fun items(menu: String) = menus().items(menu)
    private fun item(menu: String, label: String) = items(menu).first { it.label == label }
    private fun act(action: String) = HomeAction.Tab(runThenShell("keybar menu menu --act " + shellQuote(action)))

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun settings(text: String) = write(File(home, ".config/mynx/settings.conf"), text)

    @Test
    fun `settings opens touch lists, with System and Help`() {
        assertEquals(
            listOf(
                HomeItem("Chat", HomeAction.Menu("settings:chat")),
                HomeItem("Terminal", HomeAction.Menu("settings:terminal")),
                HomeItem("Phone access", HomeAction.Menu("settings:phone")),
                HomeItem("Background", HomeAction.Menu("settings:background")),
                HomeItem("Undo", HomeAction.Menu("undo")),
                HomeItem("System", HomeAction.Menu("system")),
                HomeItem("Help", HomeAction.Menu("help")),
            ),
            items("settings"),
        )
    }

    @Test
    fun `every setting is in exactly one list`() {
        val listed = HOME_SETTING_GROUPS.flatMap { it.keys }
        assertEquals(SETTINGS.map { it.key }.sorted(), listed.sorted())
        assertEquals(listed.size, listed.toSet().size)
    }

    @Test
    fun `an on-off setting flips with a tap and shows its value`() {
        settings("tab-swipe = off\n")
        assertEquals(
            HomeItem("Swipe between tabs", HomeAction.Config("set", listOf("tab-swipe", "on")), detail = "Off"),
            item("settings:terminal", "Swipe between tabs"),
        )
        assertEquals(
            HomeItem("Cursor blinks", HomeAction.Config("set", listOf("cursor-blink", "on")), detail = "Off"),
            item("settings:terminal", "Cursor blinks"),
        )
    }

    @Test
    fun `a setting with choices opens them, its description first and the value ticked`() {
        assertEquals(
            HomeItem("Cursor", HomeAction.Menu("setting:cursor-style"), detail = "block"),
            item("settings:terminal", "Cursor"),
        )
        val choices = items("setting:cursor-style")
        assertEquals(HomeItem(SETTINGS.first { it.key == "cursor-style" }.description, HomeAction.None, kind = HomeRow.TEXT), choices[0])
        assertEquals(
            listOf(
                HomeItem("block", HomeAction.Config("set", listOf("cursor-style", "block"), back = true), checked = true),
                HomeItem("underline", HomeAction.Config("set", listOf("cursor-style", "underline"), back = true)),
                HomeItem("bar", HomeAction.Config("set", listOf("cursor-style", "bar"), back = true)),
            ),
            choices.drop(1),
        )
    }

    @Test
    fun `a free value is typed in, checked by the setting itself`() {
        settings("font-size = 14\n")
        val size = item("settings:terminal", "Text size")
        assertEquals("14", size.detail)
        assertEquals(
            HomeAction.Input("Text size", "14", SETTINGS.first { it.key == "font-size" }.description, "set:font-size"),
            size.action,
        )
        assertEquals(HomeAction.Config("set", listOf("font-size", "16"), back = true), homeInputAction("set:font-size", " 16 "))
    }

    @Test
    fun `chat's list has the view, the mode and the model, named as the chat names them`() {
        settings("chat-mode = plan\nchat-model = opus\nview = touch\n")
        val chat = items("settings:chat")
        assertEquals(listOf("View", "Permission mode", "Model for new chats", "Notifications", "Notify after (seconds)"), chat.map { it.label })
        assertEquals("Touch", chat[0].detail)
        assertEquals(chatModeName("plan"), chat[1].detail)
        assertEquals("Opus", chat[2].detail)
        val modes = items("setting:chat-mode").drop(1)
        assertEquals(CHAT_MODES.map { chatModeName(it.value) }, modes.map { it.label })
        assertTrue(modes.single { it.checked }.label == chatModeName("plan"))
    }

    @Test
    fun `the model list says Claude Code decides and takes any other name`() {
        val models = items("setting:chat-model").drop(1)
        assertEquals(listOf("Claude Code decides", "Opus", "Sonnet", "Haiku", "Other model…"), models.map { it.label })
        assertTrue(models[0].checked)
        assertEquals(HomeAction.Config("set", listOf("chat-model", "sonnet"), back = true), models[2].action)
        assertEquals("set:chat-model", (models.last().action as HomeAction.Input).target)
        settings("chat-model = claude-sonnet-5-5\n")
        assertEquals("claude-sonnet-5-5", item("settings:chat", "Model for new chats").detail)
        assertEquals("claude-sonnet-5-5", (items("setting:chat-model").last().action as HomeAction.Input).value)
    }

    @Test
    fun `terminal's list starts with the theme and ends with the key bar and menu editors`() {
        val terminal = items("settings:terminal")
        assertEquals(HomeItem("Theme", HomeAction.Menu("themes"), detail = "neon"), terminal.first())
        assertEquals(HomeItem("Key bars and launcher menu", act("settings")), terminal.last())
    }

    @Test
    fun `themes are listed with their colours, the one in use ticked, the user's too`() {
        write(File(home, ".config/mynx/colors.properties"), colorsFileFor("nord", builtInThemeText("nord")!!))
        write(File(home, "$THEMES_DIR/mine$THEME_SUFFIX"), "background=#000000\nforeground=#ffffff\ncolor1=#ff0000\n")
        val themes = items("themes")
        assertEquals(BUILT_IN_THEMES + "mine", themes.map { it.label })
        assertEquals("nord", themes.single { it.checked }.label)
        assertEquals(HomeAction.Config("theme-set", listOf("mine")), themes.last().action)
        val mine = themes.last().swatch
        assertEquals(0xFF000000.toInt(), mine[0])
        assertEquals(0xFFFFFFFF.toInt(), mine[1])
        assertEquals(0xFFFF0000.toInt(), mine[2])
        assertEquals(8, mine.size)
    }

    @Test
    fun `with no colours file Neon is the one in use`() {
        assertEquals("neon", items("themes").single { it.checked }.label)
    }

    @Test
    fun `system keeps its programs in tabs and shows its info itself`() {
        assertEquals(listOf(HomeItem("Update all", act("update")), HomeItem("System info", HomeAction.Menu("info"))), items("system").take(2))
    }

    @Test
    fun `system info lists the phone's facts`() {
        write(File(tools, ".version"), "6-1794091136167-0.4.0\n")
        assertEquals(
            listOf(
                HomeItem("mynx", HomeAction.None, detail = "0.4.0 (build 6)", kind = HomeRow.INFO),
                HomeItem("Debian", HomeAction.None, detail = "13.2", kind = HomeRow.INFO),
                HomeItem("Kernel", HomeAction.None, detail = "6.6.127-android15", kind = HomeRow.INFO),
                HomeItem("Arch", HomeAction.None, detail = "aarch64", kind = HomeRow.INFO),
                HomeItem("Memory", HomeAction.None, detail = "2.9 GB free of 11.3 GB", kind = HomeRow.INFO),
                HomeItem("Storage", HomeAction.None, detail = "49.6 GB free of 219.3 GB", kind = HomeRow.INFO),
            ),
            items("info"),
        )
    }

    @Test
    fun `help's pages are touch lists, and a bug report is typed in`() {
        val help = items("help")
        assertEquals(
            listOf(
                HomeItem("Getting started", HomeAction.Menu("welcome")),
                HomeItem("Release notes", HomeAction.Menu("notes")),
                HomeItem("Report a bug", HomeAction.Input("Report a bug", "", REPORT_NOTE, "report")),
                HomeItem("About", HomeAction.Menu("about")),
            ),
            help,
        )
        assertEquals(HomeAction.Tab(runThenShell("mynx report " + shellQuote("it broke"))), homeInputAction("report", " it broke "))
        assertTrue(items("welcome").isNotEmpty())
        assertTrue(items("welcome").all { it.kind == HomeRow.TEXT || it.kind == HomeRow.SECTION })
    }

    @Test
    fun `an empty input changes nothing`() {
        assertEquals(HomeAction.None, homeInputAction("report", "  "))
        assertEquals(HomeAction.None, homeInputAction("set:font", ""))
    }

    @Test
    fun `release notes are a section per version, newest first, their Markdown made plain`() {
        write(
            File(tools, "release-notes.json"),
            """[{"version": "0.4.0", "published": "2026-11-01T15:41:15Z", "notes": "## What's Changed\n* **BLE**: mynx ble by @est4s in https://github.com/est4s/mynx/pull/53\n* See [the guide](https://x.y)"},""" +
                """{"version": "0.3.0", "published": "2026-10-20T10:00:00Z", "notes": "Fixes."}]""",
        )
        assertEquals(
            listOf(
                HomeItem("0.4.0 · 2026-11-01", HomeAction.None, kind = HomeRow.SECTION),
                HomeItem("What's Changed", HomeAction.None, kind = HomeRow.TEXT),
                HomeItem("• BLE: mynx ble by @est4s in https://github.com/est4s/mynx/pull/53", HomeAction.None, kind = HomeRow.TEXT),
                HomeItem("• See the guide", HomeAction.None, kind = HomeRow.TEXT),
                HomeItem("0.3.0 · 2026-10-20", HomeAction.None, kind = HomeRow.SECTION),
                HomeItem("Fixes.", HomeAction.None, kind = HomeRow.TEXT),
            ),
            items("notes"),
        )
    }

    @Test
    fun `with no release notes it says so`() {
        assertEquals(listOf(HomeItem("(no release notes here)", HomeAction.None)), items("notes"))
    }

    @Test
    fun `about gives the version, licence and source, and the full list in a tab`() {
        write(File(tools, ".version"), "6-1794091136167-0.4.0\n")
        assertEquals(
            listOf(
                HomeItem("Version", HomeAction.None, detail = "0.4.0 (build 6)", kind = HomeRow.INFO),
                HomeItem("Licence", HomeAction.None, detail = "MIT", kind = HomeRow.INFO),
                HomeItem("Source", HomeAction.Link("https://github.com/est4s/mynx"), detail = "github.com/est4s/mynx"),
                HomeItem("What it's made with", act("about")),
            ),
            items("about"),
        )
    }

    @Test
    fun `undo lists the changes it can take back, newest first`() {
        val config = File(home, CONFIG_DIR)
        val history = ConfigHistory(config, File(home, UNDO_DIR)) { 1_000_000_000L - 120_000 }
        settings("font-size = 12\n")
        history.observe("start", 3)
        settings("font-size = 14\n")
        history.observe("set font-size 14", 3)
        val undo = items("undo")
        assertEquals(HomeItem("Undo last change", HomeAction.Config("undo", emptyList())), undo[0])
        assertEquals(HomeItem("set font-size 14", HomeAction.None, detail = chatRelativeTime(1_000_000_000L, 1_000_000_000L - 120_000), kind = HomeRow.INFO), undo[2])
        assertEquals(HomeItem("Changes kept", HomeAction.Input("Changes kept", "1", SETTINGS.first { it.key == "undo-keep" }.description, "set:undo-keep"), detail = "1"), undo.last())
    }

    @Test
    fun `with nothing to undo it says so`() {
        val undo = items("undo")
        assertEquals(HomeItem("Undo last change", HomeAction.None), undo[0])
        assertEquals(HomeItem("(nothing to undo)", HomeAction.None), undo[1])
    }
}
