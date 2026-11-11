package io.github.est4s.terminal

import android.app.Application
import android.widget.ImageView
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.ChatRowState
import io.github.est4s.terminal.core.HOME_MAIN
import io.github.est4s.terminal.core.HomeAction
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.chatHome
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

private const val HOME_MINUTE = 60_000L

// Two live chats (working, waiting for you) and three past ones.
private fun homeRows(now: Long) = listOf(
    ChatHistoryRow("n", "Make a tip calculator app for the home screen", now - 5_000, ChatRowState.WORKING),
    ChatHistoryRow("w", "Why does esptool say the port is busy?", now - 3 * HOME_MINUTE, ChatRowState.WAITING),
    ChatHistoryRow("p1", "Rename the photos in Download by date", now - 40 * HOME_MINUTE),
    ChatHistoryRow("p2", "Write a service for my bot, and keep it running when the screen is off", now - 26 * 60 * HOME_MINUTE),
    ChatHistoryRow("p3", "Explain proot's hard links", now - 9 * 24 * 60 * HOME_MINUTE),
)

private val noActions = object : ChatHomeActions {
    override fun send(text: String) = Unit
    override fun addContext(draft: String) = Unit
    override fun dictate(draft: String) = Unit
    override fun openChat(sessionId: String?) = Unit
    override fun allChats() = Unit
    override fun act(action: HomeAction) = Unit
    override fun openList(menu: String, title: String) = Unit
    override fun openServices() = Unit
    override fun showTerminal() = Unit
}

/** Touch view's chat home and its drawer, with an empty Debian: see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: TerminalApp's crash handler isn't wanted in tests.
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ChatHomeScreenshotTest {
    private val top = Files.createTempDirectory("chat-home-shots").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }

    @After
    fun cleanUp() {
        // No links in it, so this can't reach outside it.
        top.deleteRecursively()
    }

    private fun shoot(
        w: Int, h: Int, name: String,
        live: Boolean = true, servicesActive: Int = 2, installed: Boolean = true, drawer: Boolean = false,
    ) {
        val rows = homeRows(System.currentTimeMillis()).filter { live || it.state == null }
        val main = HomeMenus(WebApps(home, rootfs.path, tools), servicesActive = servicesActive).items(HOME_MAIN)
        val model = chatHome(rows, main, servicesActive, installed, "auto", "/root", "/root")
        val view = Screenshots.open(w, h) { activity ->
            ChatHomeView(activity, load = { done -> done(model) }, actions = noActions) { context ->
                ImageView(context).apply {
                    setImageResource(R.drawable.mynx_title)
                    adjustViewBounds = true
                    maxHeight = (40 * context.resources.displayMetrics.density).toInt()
                }
            }
        }
        view.refresh()
        if (drawer) view.openDrawer()
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    @Test
    fun portrait() = shoot(1080, 2400, "chat-home")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "chat-home-360dp")

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "chat-home-landscape")

    @Test
    fun drawer() = shoot(1080, 2400, "chat-home-drawer", drawer = true)

    @Test
    fun empty() = shoot(1080, 2400, "chat-home-empty", live = false, servicesActive = 0)

    @Test
    fun install() = shoot(1080, 2400, "chat-home-install", installed = false)
}
