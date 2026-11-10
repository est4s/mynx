package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.AgentQuestion
import io.github.est4s.terminal.core.ArtifactKind
import io.github.est4s.terminal.core.ChatAccess
import io.github.est4s.terminal.core.ChatArtifact
import io.github.est4s.terminal.core.ChatItem
import io.github.est4s.terminal.core.ChatState
import io.github.est4s.terminal.core.Conversation
import io.github.est4s.terminal.core.PermissionOption
import io.github.est4s.terminal.core.PermissionOptionKind
import io.github.est4s.terminal.core.QuestionOption
import io.github.est4s.terminal.core.ToolCallInfo
import io.github.est4s.terminal.core.ToolKind
import io.github.est4s.terminal.core.ToolStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val REPLY = """
    ## Tip calculator

    I made a small web app in `~/apps/tip-calc`. It can:

    - **split** the bill between people
    - round *up* to a whole amount
    - remember the last tip you picked

    The sum it does:

    ```js
    function tip(total, percent) {
      return Math.ceil(total * percent) / 100
    }
    ```

    | Bill | 15% | 20% |
    |---:|---:|---:|
    | 40.00 | 6.00 | 8.00 |
    | 85.50 | 12.83 | 17.10 |

    See [Math.ceil on MDN](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Math/ceil).
""".trimIndent()

private const val SERVE = "python3 -m http.server 8765 --bind 127.0.0.1"

/** A chat in the middle of a turn: one of each card. */
private val FULL = listOf(
    ChatItem.User("user-1", "Make me a tip calculator app, and serve it so I can try it."),
    ChatItem.Agent("agent-1", REPLY),
    ChatItem.Tool(
        "tool-1",
        ToolCallInfo("tool-1", "Write", "Write ~/apps/tip-calc/index.html", ToolKind.EDIT, ToolStatus.IN_PROGRESS),
    ),
    ChatItem.Permission(
        "request:perm-1",
        "perm-1",
        ToolCallInfo("tool-2", "Bash", SERVE, ToolKind.EXECUTE, ToolStatus.PENDING),
        listOf(
            PermissionOption("allow_once", "Allow", PermissionOptionKind.ALLOW_ONCE),
            PermissionOption("allow_always", "Always allow this session: Bash(python3 -m http.server:*)", PermissionOptionKind.ALLOW_ALWAYS),
            PermissionOption("reject_once", "Reject", PermissionOptionKind.REJECT_ONCE),
        ),
        review = SERVE,
    ),
    ChatItem.Question(
        "request:question-1",
        "question-1",
        listOf(
            AgentQuestion(
                "Which currency should it show?",
                "Currency",
                multiSelect = false,
                options = listOf(
                    QuestionOption("Euro (€)", "Shown as 12,50 €"),
                    QuestionOption("US dollar", null),
                    QuestionOption("Turkish lira (₺)", null),
                ),
            ),
        ),
    ),
    ChatItem.Artifact(
        "artifact-1",
        ChatArtifact(ArtifactKind.WEB_APP, "/root/apps/tip-calc", "Tip calculator", "/root/apps/tip-calc/index.html", appName = "tip-calc"),
    ),
)

private val SHORT = listOf(
    ChatItem.User("user-1", "What's in my home folder?"),
    ChatItem.Agent("agent-1", "Your home has **apps**, **pixel-hero** and **neon-runner**, and the notes in `AGENTS.md`."),
)

/** Touch mode's Chat, fed [ChatState]s directly (no controller, so its buttons do nothing): see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ChatScreenshotTest {
    private fun shoot(w: Int, h: Int, name: String, items: List<ChatItem>, running: Boolean) {
        val view = Screenshots.open(w, h) { activity ->
            ChatView(
                activity,
                controller = { null },
                back = {},
                showTerminal = {},
                openTab = {},
                openApp = {},
                shareFile = {},
                installApk = {},
            ).apply { colors = Screenshots.colors }
        }
        view.show(ChatState(Conversation(items, running = running), ChatAccess.READY, canRestart = false, busy = running))
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    /** The phone's height: the rows stay at the end, as they do on the phone. */
    @Test
    fun conversation() = shoot(1080, 2400, "chat", FULL, running = true)

    /** Tall enough for every row, to see them all at once. */
    @Test
    fun wholeConversation() = shoot(1080, 7000, "chat-whole", FULL, running = true)

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "chat-360dp", SHORT, running = false)

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "chat-landscape", FULL, running = true)

    /** Landscape with the keyboard up: what chatFit keeps of a short height. */
    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscapeKeyboard() = shoot(2400, 450, "chat-landscape-keyboard", FULL, running = true)
}
