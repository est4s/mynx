package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.HomeService
import io.github.est4s.terminal.core.WebApps
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
        // No links in it, so this can't reach outside it.
        top.deleteRecursively()
    }

    private fun home(w: Int, h: Int) = Screenshots.open(w, h) { activity ->
        HomeView(
            activity,
            menus = { HomeMenus(WebApps(home, rootfs.path, tools), servicesActive = 1, services = services) },
            act = {},
        )
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
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun mainNarrow() = shoot(home(945, 2100), "home-main-360dp")
}
