package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.SCREEN_SEND_MENU
import io.github.est4s.terminal.core.ScreenHome
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ScreenRef
import io.github.est4s.terminal.core.ScreenRole
import io.github.est4s.terminal.core.ShownStream
import io.github.est4s.terminal.core.WebApps
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** The extended screen in touch mode: Home's Send list on the host, Home and a stream on the screen. See [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ScreenShareScreenshotTest {
    private val top = Files.createTempDirectory("screen-shots").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }

    @After
    fun cleanUp() {
        // No links in it, so this can't reach outside it.
        top.deleteRecursively()
    }

    private fun write(name: String, text: String) = File(home, name).apply { parentFile.mkdirs() }.writeText(text)

    private fun home(screen: ScreenHome) = Screenshots.open(1080, 2400) { activity ->
        HomeView(activity, menus = { HomeMenus(WebApps(home, rootfs.path, tools), screen = screen) }, act = {}, config = { _, _ -> null })
    }

    private fun shoot(view: HomeView, name: String, menu: String? = null, title: String = "") {
        if (menu != null) view.showMenu(menu, title)
        view.refresh()
        Screenshots.settleThreads()
        Screenshots.save(view, name)
    }

    private val host = ScreenHome(
        "Nord 2", ScreenRole.HOST, sent = listOf(ScreenRef(1, "Pixel Hero"), ScreenRef(2, "htop")), shown = emptyList(),
        tabs = listOf("bash", "~/mynx: vim notes.md"),
    )

    @Test
    fun hostHome() = shoot(home(host), "screen-host-home")

    @Test
    fun sendList() {
        write("apps/pixel-hero/index.html", "<title>Pixel Hero</title>")
        write("apps/sketch/index.html", "<title>Sketch</title>")
        shoot(home(host), "screen-send", SCREEN_SEND_MENU, "Send to Nord 2")
    }

    @Test
    fun screenHome() = shoot(
        home(ScreenHome("Pixel 10", ScreenRole.SCREEN, sent = emptyList(), shown = listOf(ScreenRef(1, "Pixel Hero"), ScreenRef(2, "htop")))),
        "screen-screen-home",
    )

    // No video in Robolectric: the black screen and the line that says the host's typing comes here.
    @Test
    fun streamTypedTo() {
        val view = Screenshots.open(1080, 2400) { activity ->
            ScreenStreamView(activity, object : ScreenStreamView.Host {
                override fun touch(touch: ScreenMessage.Touch) {}
                override fun decoded(stream: Int, pts: Long) {}
                override fun askKeyFrame(stream: Int) {}
            })
        }
        view.show(ShownStream(1, "Pixel Hero", 1080, 2400))
        view.focus(true)
        Screenshots.settle()
        Screenshots.save(view, "screen-stream-typed-to")
    }
}
