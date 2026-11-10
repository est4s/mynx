package io.github.est4s.terminal

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.github.est4s.terminal.core.AgentQuestion
import io.github.est4s.terminal.core.ArtifactKind
import io.github.est4s.terminal.core.ChatAccess
import io.github.est4s.terminal.core.ChatAttachment
import io.github.est4s.terminal.core.ChatArtifact
import io.github.est4s.terminal.core.ChatItem
import io.github.est4s.terminal.core.ChatState
import io.github.est4s.terminal.core.ChatUsage
import io.github.est4s.terminal.core.ContextUse
import io.github.est4s.terminal.core.Conversation
import io.github.est4s.terminal.core.PermissionOption
import io.github.est4s.terminal.core.PermissionOptionKind
import io.github.est4s.terminal.core.PlanWindow
import io.github.est4s.terminal.core.QuestionOption
import io.github.est4s.terminal.core.RequestOutcome
import io.github.est4s.terminal.core.ToolCallInfo
import io.github.est4s.terminal.core.ToolKind
import io.github.est4s.terminal.core.ToolStatus
import io.github.est4s.terminal.core.promptWithAttachments
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

/** Sent during the turn, not taken yet. */
private val QUEUED = listOf(ChatItem.User("user-2", "Make the buttons bigger too."))

private val SHORT = listOf(
    ChatItem.User("user-1", "What's in my home folder?"),
    ChatItem.Agent("agent-1", "Your home has **apps**, **pixel-hero** and **neon-runner**, and the notes in `AGENTS.md`."),
)

/** The other cards and states: a plan, a multiple choice, answered cards, an APK and a terminal app. */
private val CARDS = listOf(
    ChatItem.User("user-1", "Plan a currency switch, then build it."),
    ChatItem.Permission(
        "request:perm-1",
        "perm-1",
        ToolCallInfo("tool-1", "Bash", SERVE, ToolKind.EXECUTE, ToolStatus.COMPLETED),
        listOf(
            PermissionOption("allow_once", "Allow", PermissionOptionKind.ALLOW_ONCE),
            PermissionOption("reject_once", "Reject", PermissionOptionKind.REJECT_ONCE),
        ),
        outcome = RequestOutcome.Selected(PermissionOptionKind.ALLOW_ONCE),
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
                options = listOf(QuestionOption("Euro (€)", "Shown as 12,50 €"), QuestionOption("US dollar", null)),
            ),
        ),
        outcome = RequestOutcome.Answered(mapOf("Which currency should it show?" to "Euro (€)")),
    ),
    ChatItem.Plan(
        "request:plan-1",
        "plan-1",
        "1. Add a currency picker\n2. Remember the choice\n3. Format sums for it",
        "/root/.claude/plans/currency.md",
    ),
    ChatItem.Question(
        "request:question-2",
        "question-2",
        listOf(
            AgentQuestion(
                "Which rounding should it offer?",
                "Rounding",
                multiSelect = true,
                options = listOf(
                    QuestionOption("Up to a whole amount", "40.00 + 15% → 46"),
                    QuestionOption("To the nearest 0.50", "Common for cash"),
                    QuestionOption("None", null),
                ),
            ),
        ),
    ),
    ChatItem.Artifact(
        "artifact-1",
        ChatArtifact(ArtifactKind.APK, "/root/tip/tip.apk", "Tip calculator", "/root/tip/tip.apk"),
    ),
    ChatItem.Artifact(
        "artifact-2",
        ChatArtifact(ArtifactKind.TERMINAL_APP, "/root/apps/tip", "tip", "/root/apps/tip"),
    ),
)

/** Usage as the maintainer's screenshots had it: context 15%, the session 13% (resets today), the week 40%. */
private val NOW_MS = 1_794_312_000_000L // 2026-11-10 12:00 UTC
private val USAGE = ChatUsage(
    context = ContextUse(148_400, 1_000_000),
    plan = listOf(
        PlanWindow("five_hour", 0.13, NOW_MS / 1000 + 8 * 3600 + 1800, "allowed"),
        PlanWindow("seven_day", 0.40, NOW_MS / 1000 + 3 * 86400 - 3 * 3600, "allowed"),
    ),
    planSeenMs = NOW_MS - 60_000,
)

private val FILES = listOf(
    ChatAttachment("/root/Shared/photo-2026-11-10-120000.jpg", "photo-2026-11-10-120000.jpg", image = true),
    ChatAttachment("/root/Shared/notes.md", "notes.md", image = false),
)

/** A prompt sent with a photo and a file. */
private val WITH_FILES = listOf(
    ChatItem.User("user-1", promptWithAttachments("What's in this photo? Compare it with my notes.", FILES)),
    ChatItem.Agent("agent-1", "The photo shows a **breadboard** with an ESP32-C3; your notes list the same pins."),
)

/** A phone that dictates: listening at once, with the first words heard. */
private class FakeDictation : ChatDictation {
    override val available = true

    override fun start(text: (String, Boolean) -> Unit, listening: (Boolean) -> Unit) {
        listening(true)
        text("round the tip up to", false)
    }

    override fun stop() = Unit
}

/** Touch mode's Chat, fed [ChatState]s directly (no controller, so its buttons do nothing): see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ChatScreenshotTest {
    private fun shoot(
        w: Int,
        h: Int,
        name: String,
        items: List<ChatItem>,
        running: Boolean,
        access: ChatAccess = ChatAccess.READY,
        model: String? = null,
        mode: String? = null,
        queued: List<ChatItem.User> = emptyList(),
        title: String? = "Tip calculator",
        usage: ChatUsage = USAGE,
        attached: List<ChatAttachment> = emptyList(),
        typed: String? = null,
        sheets: List<ChatSheetPage> = emptyList(),
        dictating: Boolean = false,
    ) {
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
                attach = { _, _ -> },
                dictation = FakeDictation(),
                clock = { NOW_MS },
            ).apply { colors = Screenshots.colors }
        }
        val conversation = Conversation(items, queued = queued, running = running, model = model, mode = mode)
        view.show(ChatState(conversation, access, canRestart = false, busy = running, usage = usage, title = title))
        Screenshots.settle()
        view.attachFiles(attached)
        typed?.let { typeInto(view, it) }
        if (dictating) view.toggleDictation()
        sheets.forEach { view.openSheet(it) }
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    private fun typeInto(view: View, text: String) {
        if (view is EditText) {
            view.setText(text)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) typeInto(view.getChildAt(i), text)
    }
    /** The phone's height: the rows stay at the end, as they do on the phone. */
    @Test
    fun conversation() = shoot(1080, 2400, "chat", FULL, running = true, queued = QUEUED)

    /** Tall enough for every row, to see them all at once. */
    @Test
    fun wholeConversation() = shoot(1080, 7000, "chat-whole", FULL, running = true, queued = QUEUED)

    /** Also the line under the title, with a mode that isn't the default. */
    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "chat-360dp", SHORT, running = false, model = "claude-opus-5-5", mode = "acceptEdits")

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "chat-landscape", FULL, running = true)

    /** The other cards, answered ones, and the sign-in gate over the box. */
    @Test
    fun cards() = shoot(1080, 4200, "chat-cards", CARDS, running = false, access = ChatAccess.NOT_SIGNED_IN)

    /** Landscape with the keyboard up: what chatFit keeps of a short height. */
    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscapeKeyboard() = shoot(2400, 450, "chat-landscape-keyboard", FULL, running = true)

    /** A new chat: "New chat", an empty ring, the idle box. */
    @Test
    fun newChat() = shoot(1080, 2400, "chat-new", emptyList(), running = false, title = null, usage = ChatUsage())

    /** Files picked with +, as chips over the box, and a prompt sent with files. */
    @Test
    fun attachments() = shoot(1080, 2400, "chat-attachments", WITH_FILES, running = false, attached = FILES, typed = "And this one?")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun attachmentsNarrow() = shoot(945, 2100, "chat-attachments-360dp", WITH_FILES, running = false, attached = FILES)

    /** While a turn runs, with something typed: Send, not Stop. */
    @Test
    fun runningTyped() = shoot(1080, 2400, "chat-running-typed", SHORT, running = true, typed = "Also list hidden files")

    /** The mic listening: words heard so far after what was typed. */
    @Test
    fun dictating() = shoot(1080, 2400, "chat-dictating", SHORT, running = false, typed = "Please", dictating = true)

    @Test
    fun addSheet() = shoot(1080, 2400, "chat-sheet-add", SHORT, running = false, mode = "auto", sheets = listOf(ChatSheetPage.ADD))

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun addSheetNarrow() = shoot(945, 2100, "chat-sheet-add-360dp", SHORT, running = false, mode = "acceptEdits", sheets = listOf(ChatSheetPage.ADD))

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun addSheetLandscape() = shoot(2400, 1080, "chat-sheet-add-landscape", SHORT, running = false, sheets = listOf(ChatSheetPage.ADD))

    @Test
    fun permissionSheet() =
        shoot(1080, 2400, "chat-sheet-permission", SHORT, running = false, mode = "acceptEdits", sheets = listOf(ChatSheetPage.ADD, ChatSheetPage.PERMISSION))

    @Test
    fun defaultModeSheet() = shoot(
        1080, 2400, "chat-sheet-default-mode", SHORT, running = false,
        sheets = listOf(ChatSheetPage.ADD, ChatSheetPage.PERMISSION, ChatSheetPage.DEFAULT_MODE),
    )

    @Test
    fun modelSheet() = shoot(1080, 2400, "chat-sheet-model", SHORT, running = false, sheets = listOf(ChatSheetPage.MODEL))

    @Test
    fun usageSheet() = shoot(1080, 2400, "chat-sheet-usage", SHORT, running = false, sheets = listOf(ChatSheetPage.USAGE))

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun usageSheetNarrow() = shoot(945, 2100, "chat-sheet-usage-360dp", SHORT, running = false, sheets = listOf(ChatSheetPage.USAGE))

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun usageSheetLandscape() = shoot(2400, 1080, "chat-sheet-usage-landscape", SHORT, running = false, sheets = listOf(ChatSheetPage.USAGE))

    /** Before any reply: Usage says when it fills in. */
    @Test
    fun usageSheetEmpty() =
        shoot(1080, 2400, "chat-sheet-usage-empty", emptyList(), running = false, title = null, usage = ChatUsage(), sheets = listOf(ChatSheetPage.USAGE))
}
