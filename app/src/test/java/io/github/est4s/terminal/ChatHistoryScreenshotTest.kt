package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.ChatRowState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MINUTE = 60_000L

// One row in each state: working, waiting for you, idle, and past chats with no controller.
private fun sampleRows(now: Long) = listOf(
    ChatHistoryRow("n", "Make a tip calculator app for the home screen", now - 5_000, ChatRowState.WORKING),
    ChatHistoryRow("w", "Why does esptool say the port is busy?", now - 3 * MINUTE, ChatRowState.WAITING),
    ChatHistoryRow("i", "Rename the photos in Download by date", now - 40 * MINUTE, ChatRowState.IDLE),
    ChatHistoryRow("p1", "Write a systemd-free service for my bot, and keep it running when the screen is off", now - 26 * 60 * MINUTE),
    ChatHistoryRow("p2", "Explain proot's hard links", now - 9 * 24 * 60 * MINUTE),
)

/** Touch mode's chat history, fed rows directly (no hub): see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ChatHistoryScreenshotTest {
    private fun shoot(w: Int, h: Int, name: String, rows: List<ChatHistoryRow>? = sampleRows(System.currentTimeMillis())) {
        val view = Screenshots.open(w, h) { activity ->
            ChatHistoryView(activity, hub = { null }, back = {}, showTerminal = {}, openChat = {})
        }
        rows?.let { view.show(it) }
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    @Test
    fun history() = shoot(1080, 2400, "chat-history")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "chat-history-360dp")

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "chat-history-landscape")

    @Test
    fun empty() = shoot(1080, 2400, "chat-history-empty", emptyList())
}
