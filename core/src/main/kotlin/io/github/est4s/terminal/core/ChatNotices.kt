package io.github.est4s.terminal.core

/*
 * Phone notifications for the Chat screen, as `mynx hook claude` gives an
 * agent in a tab (a chat runs with MYNX_CHAT=1, which silences the hooks):
 * a turn that took agent-notify-after seconds or more ended, or a card waits
 * for an answer. Never while the Chat screen is on show. [ChatController]
 * asks [chatNotice] and the app posts what it gives: one notification for
 * the chat, replaced by the next, cleared when Chat opens.
 */

/** A notification for the chat. */
data class ChatNotice(val title: String, val text: String)

/** How things stand when a notice may be due, read from the app then. */
data class ChatNoticeRules(
    /** The Chat screen is on show: nothing to tell. */
    val onScreen: Boolean,
    /** The `agent-notify` setting. */
    val agentNotify: Boolean = true,
    /** The `agent-notify-after` setting. */
    val afterSeconds: Int = 30,
    /** Claude Code's own switch, `mynx agent notify claude on|off`: [claudeNotifyHooks]. */
    val agentOn: Boolean = true,
)

const val CHAT_NOTICE_TITLE = "Claude Code"
const val MAX_CHAT_NOTICE_TEXT = 200

/** Whether [event] can ever be a notice: the controller only asks for rules then. */
fun mayNotify(event: AgentEvent): Boolean = when (event) {
    is AgentEvent.TurnEnded, is AgentEvent.PermissionRequest, is AgentEvent.QuestionRequest, is AgentEvent.PlanRequest -> true
    else -> false
}

/**
 * The notice [event] calls for, given the [conversation] it led to and how
 * long the turn took ([tookMs], null when its start wasn't seen); null for none.
 * A turn's end needs agent-notify-after; a card notifies at once.
 */
fun chatNotice(event: AgentEvent, conversation: Conversation, tookMs: Long?, rules: ChatNoticeRules): ChatNotice? {
    if (rules.onScreen || !rules.agentNotify || !rules.agentOn) return null
    val text = when (event) {
        is AgentEvent.TurnEnded -> {
            if (event.stopReason == StopReason.CANCELLED) return null
            if (tookMs == null || tookMs < rules.afterSeconds * 1000L) return null
            if (event.stopReason == StopReason.ERROR) {
                "The turn failed" + (event.message?.let(::firstLine)?.let { ": $it" } ?: "")
            } else {
                "Your turn" + (lastReply(conversation)?.let { ": $it" } ?: "")
            }
        }
        is AgentEvent.PermissionRequest -> if (event.call.name.isBlank()) "Needs your permission" else "Wants to run ${event.call.name}"
        is AgentEvent.QuestionRequest -> event.questions.firstOrNull()?.question?.let(::firstLine)?.let { "Asks: $it" } ?: "Needs your input"
        is AgentEvent.PlanRequest -> "Has a plan for you to approve"
        else -> return null
    }
    return ChatNotice(CHAT_NOTICE_TITLE, text.cut(MAX_CHAT_NOTICE_TEXT))
}

// The first line of this turn's last reply, without Markdown's marks.
private fun lastReply(conversation: Conversation): String? {
    val items = conversation.items
    val turn = items.subList(items.indexOfLast { it is ChatItem.User } + 1, items.size)
    val text = turn.lastOrNull { it is ChatItem.Agent && it.text.isNotBlank() } as? ChatItem.Agent ?: return null
    return firstLine(text.text)
}

private val BLOCK_MARK = Regex("""^(#{1,6}|>|[-*+]|\d+[.)])\s+""")

private fun firstLine(text: String): String? =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.replace(BLOCK_MARK, "")?.replace("**", "")?.replace("`", "")?.trim()?.takeIf { it.isNotEmpty() }

private fun String.cut(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"

private val CLAUDE_HOOK_EVENTS = listOf("UserPromptSubmit", "Stop", "Notification")
private const val CLAUDE_HOOK = "mynx hook claude"

/** Whether Claude's settings.json [text] (null: no file) has mynx's hook on each of its events, as `mynx agent list` reads it. */
fun claudeNotifyHooks(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val config = try {
        parseJson(text)
    } catch (e: JsonException) {
        return false
    }
    val hooks = (config as? Map<*, *>)?.get("hooks") as? Map<*, *> ?: return false
    return CLAUDE_HOOK_EVENTS.all { event ->
        (hooks[event] as? List<*>).orEmpty().any { group ->
            ((group as? Map<*, *>)?.get("hooks") as? List<*>).orEmpty().any { hook ->
                val command = (hook as? Map<*, *>)?.get("command") as? String
                command != null && (command == CLAUDE_HOOK || command.endsWith("/$CLAUDE_HOOK"))
            }
        }
    }
}
