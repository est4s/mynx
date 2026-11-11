package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.HomeService
import io.github.est4s.terminal.core.LOGO_PALETTE
import io.github.est4s.terminal.core.SystemFacts
import io.github.est4s.terminal.core.builtInThemeText
import io.github.est4s.terminal.core.pixelPalette
import io.github.est4s.terminal.core.WebApps
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** Touch mode's Home with an empty Debian and two services: see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: TerminalApp's crash handler isn't wanted in tests.
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class HomeScreenshotTest {
    private val top = Files.createTempDirectory("home-shots").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }
    private val services = listOf(
        HomeService("web", "running", saved = true, autostart = true),
        HomeService("bot", "stopped", saved = true, autostart = false),
    )

    @After
    fun cleanUp() {
        Pixel.use(LOGO_PALETTE)
        // No links in it, so this can't reach outside it.
        top.deleteRecursively()
    }

    private fun home(w: Int, h: Int) = Screenshots.open(w, h) { activity ->
        HomeView(
            activity,
            menus = { HomeMenus(WebApps(home, rootfs.path, tools), servicesActive = 1, services = services, facts = { facts }) },
            act = {},
            config = { _, _ -> null },
        )
    }

    private val facts = SystemFacts("13.2", "6.6.127-android15", "aarch64", 3_000_000, 11_800_000, 52_000_000, 230_000_000)

    private fun write(name: String, text: String) = File(home, name).apply { parentFile.mkdirs() }.writeText(text)

    // Taps the row labelled [label], as a finger would.
    private fun tap(view: View, label: String): Boolean {
        if (view is TextView && view.text.toString() == label) return (view.parent as View).performClick()
        if (view is ViewGroup) for (i in 0 until view.childCount) if (tap(view.getChildAt(i), label)) return true
        return false
    }

    private fun shoot(view: HomeView, name: String, menu: String? = null, title: String = "") {
        if (menu != null) view.showMenu(menu, title)
        view.refresh()
        Screenshots.settleThreads()
        Screenshots.save(view, name)
    }

    @Test
    fun mainList() = shoot(home(1080, 2400), "home-main")

    @Test
    fun services() = shoot(home(1080, 2400), "home-services", "services", "Services")

    @Test
    fun servicePage() = shoot(home(1080, 2400), "home-service-web", "service:web", "web")

    @Test
    fun settings() = shoot(home(1080, 2400), "home-settings", "settings", "Settings")

    @Test
    fun terminalSettings() {
        write(".config/mynx/settings.conf", "font-size = 14\ncursor-blink = on\n")
        shoot(home(1080, 2400), "home-settings-terminal", "settings:terminal", "Terminal")
    }

    @Test
    fun chatSettings() {
        write(".config/mynx/settings.conf", "view = touch\nchat-model = opus\n")
        shoot(home(1080, 2400), "home-settings-chat", "settings:chat", "Chat")
    }

    @Test
    fun themes() {
        write(".config/mynx/colors.properties", "# theme: nord\n")
        shoot(home(1080, 2400), "home-themes", "themes", "Theme")
    }

    @Test
    fun themedSettings() {
        Pixel.use(pixelPalette("# theme: solarized-light\n" + builtInThemeText("solarized-light")))
        write(".config/mynx/colors.properties", "# theme: solarized-light\n")
        shoot(home(1080, 2400), "home-themes-solarized-light", "themes", "Theme")
    }

    @Test
    fun modelChoices() = shoot(home(1080, 2400), "home-setting-model", "setting:chat-model", "Model for new chats")

    @Test
    fun systemInfo() {
        File(tools, ".version").writeText("6-1794091136167-0.4.0\n")
        shoot(home(1080, 2400), "home-system-info", "info", "System info")
    }

    @Test
    fun releaseNotes() {
        File(tools, "release-notes.json").writeText(
            """[{"version": "0.4.0", "published": "2026-11-01T15:41:15Z", "notes": "## What's Changed\n* **BLE**: mynx ble by @est4s\n* Touch view's chat home"},""" +
                """{"version": "0.3.0", "published": "2026-10-20T10:00:00Z", "notes": "* Bluetooth serial ports"}]""",
        )
        shoot(home(1080, 2400), "home-release-notes", "notes", "Release notes")
    }

    @Test
    fun textInput() {
        val view = home(1080, 2400)
        view.showMenu("settings:terminal", "Terminal")
        view.refresh()
        Screenshots.settleThreads()
        check(tap(view, "Text size")) { "no Text size row" }
        view.refresh()
        Screenshots.settleThreads()
        Screenshots.save(view, "home-setting-input")
    }

    @Test
    fun reportForm() {
        val view = home(1080, 2400)
        shoot(view, "home-help", "help", "Help")
        check(tap(view, "Report a bug")) { "no Report a bug row" }
        Screenshots.settleThreads()
        Screenshots.save(view, "home-report")
    }

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun mainNarrow() = shoot(home(945, 2100), "home-main-360dp")
}
