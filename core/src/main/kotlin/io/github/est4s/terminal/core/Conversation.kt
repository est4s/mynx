package io.github.est4s.terminal.core

/** One row of the Chat screen; [id] stays the same as the row changes. */
sealed interface ChatItem {
    val id: String

    data class User(override val id: String, val text: String) : ChatItem
    data class Agent(override val id: String, val text: String) : ChatItem
    data class Tool(override val id: String, val call: ToolCallInfo) : ChatItem
    data class Permission(
        override val id: String,
        val requestId: String,
        val call: ToolCallInfo,
        val options: List<PermissionOption>,
        val outcome: RequestOutcome? = null,
        /** What's being approved: [AgentEvent.PermissionRequest.review]. */
        val review: String = "",
    ) : ChatItem
    data class Question(
        override val id: String,
        val requestId: String,
        val questions: List<AgentQuestion>,
        val outcome: RequestOutcome? = null,
    ) : ChatItem
    data class Plan(
        override val id: String,
        val requestId: String,
        val plan: String,
        val planFilePath: String?,
        val outcome: RequestOutcome? = null,
    ) : ChatItem
    data class Notice(override val id: String, val kind: NoticeKind, val text: String) : ChatItem
    /** An app or APK the agent made or changed this turn: [withArtifactCards]. */
    data class Artifact(override val id: String, val artifact: ChatArtifact) : ChatItem
}

enum class NoticeKind { INFO, ERROR, SIGN_IN }

/** What the Chat screen shows: its rows, and [running] for the Stop button. */
data class Conversation(
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val thinking: Boolean = false,
    val sessionId: String? = null,
    val model: String? = null,
    val mode: String? = null,
    val models: List<AgentModel> = emptyList(),
    val processEnded: Boolean = false,
    internal val notices: Int = 0,
)

/**
 * One step of the screen's state: pure, so the screen folds the events it
 * gets, on the main thread, in order. Items keep their [ChatItem.id] for
 * their life, so rows update in place.
 */
fun Conversation.reduce(event: AgentEvent): Conversation = when (event) {
    is AgentEvent.SessionStarted -> copy(sessionId = event.sessionId, model = event.model, mode = event.mode)
    is AgentEvent.AvailableModels -> copy(models = event.models)
    is AgentEvent.UserMessage -> copy(items = items + ChatItem.User(event.id, event.text))
    AgentEvent.TurnStarted -> copy(running = true)
    is AgentEvent.AgentMessageChunk -> agentText(event)
    is AgentEvent.ThoughtIndicator -> copy(thinking = event.active)
    is AgentEvent.ToolCall -> upsert(ChatItem.Tool(event.call.id, event.call))
    is AgentEvent.ToolCallUpdate -> upsert(ChatItem.Tool(event.call.id, event.call))
    is AgentEvent.PermissionRequest ->
        copy(items = items + ChatItem.Permission(requestItem(event.requestId), event.requestId, event.call, event.options, review = event.review))
    is AgentEvent.QuestionRequest ->
        copy(items = items + ChatItem.Question(requestItem(event.requestId), event.requestId, event.questions))
    is AgentEvent.PlanRequest ->
        copy(items = items + ChatItem.Plan(requestItem(event.requestId), event.requestId, event.plan, event.planFilePath))
    is AgentEvent.RequestResolved -> resolved(event)
    is AgentEvent.Notice -> notice(NoticeKind.INFO, event.text)
    is AgentEvent.TurnEnded -> copy(running = false, thinking = false).turnNotice(event)
    is AgentEvent.NotSignedIn -> notice(NoticeKind.SIGN_IN, event.message)
    is AgentEvent.ProcessEnded -> copy(running = false, thinking = false, processEnded = true).processNotice(event)
}

private fun requestItem(requestId: String) = "request:$requestId"

private fun Conversation.upsert(item: ChatItem): Conversation {
    val at = items.indexOfLast { it.id == item.id }
    if (at < 0) return copy(items = items + item)
    return copy(items = items.toMutableList().also { it[at] = item })
}

private fun Conversation.agentText(event: AgentEvent.AgentMessageChunk): Conversation {
    val at = items.indexOfLast { it.id == event.id }
    val before = (items.getOrNull(at) as? ChatItem.Agent)?.text.orEmpty()
    return upsert(ChatItem.Agent(event.id, if (event.replace) event.text else before + event.text))
}

private fun Conversation.resolved(event: AgentEvent.RequestResolved): Conversation {
    val id = requestItem(event.requestId)
    val item = when (val current = items.lastOrNull { it.id == id }) {
        is ChatItem.Permission -> current.copy(outcome = event.outcome)
        is ChatItem.Question -> current.copy(outcome = event.outcome)
        is ChatItem.Plan -> current.copy(outcome = event.outcome)
        else -> return this
    }
    return upsert(item)
}

private fun Conversation.notice(kind: NoticeKind, text: String): Conversation {
    val number = notices + 1
    return copy(items = items + ChatItem.Notice("notice-$number", kind, text), notices = number)
}

private fun Conversation.turnNotice(end: AgentEvent.TurnEnded): Conversation = when (end.stopReason) {
    StopReason.ERROR -> if (end.message != null) notice(NoticeKind.ERROR, end.message) else this
    StopReason.REFUSAL -> notice(NoticeKind.INFO, "The agent declined to continue.")
    StopReason.MAX_TOKENS -> notice(NoticeKind.INFO, "The reply was cut off at the length limit.")
    StopReason.END_TURN, StopReason.CANCELLED -> this
}

private fun Conversation.processNotice(end: AgentEvent.ProcessEnded): Conversation {
    if (end.stopped) return this
    val what = if (end.exitCode == null) "The agent couldn't start." else "The agent's process ended (exit ${end.exitCode})."
    return notice(NoticeKind.ERROR, if (end.detail.isBlank()) what else "$what\n${end.detail.trim()}")
}
