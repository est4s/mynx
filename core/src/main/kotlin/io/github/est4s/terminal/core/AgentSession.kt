package io.github.est4s.terminal.core

/**
 * The Chat screen's seam to an AI agent: events named after the Agent
 * Client Protocol's vocabulary, so backends other than Claude fit behind it.
 *
 * THE PIECES. The app implements [AgentTransport] with a process, and
 * gives it to a backend ([ClaudeSession]) with a listener for
 * [AgentEvent]s, which it folds into a [Conversation] for the screen.
 *
 * THREADS. The session's calls may come from any thread. Each event goes
 * to the listener on whichever thread caused it (the transport's reading
 * thread for most, the caller's for a prompt or an answer), one at a time,
 * in order, while the session holds its lock: the listener must not block
 * and must not call the session itself, so it posts to the main thread and
 * keeps the order. [AgentTransport.writeLine] runs under the same lock.
 *
 * STOPPING. [AgentSession.cancel] ends a turn and leaves the session
 * alive. [AgentSession.stop] ends the process; [AgentEvent.ProcessEnded]
 * is always the last event, once the transport reports the exit.
 */
interface AgentSession {
    /** Starts the transport and says hello to the agent. A second call does nothing. */
    fun start()

    /**
     * Sends [text] as a user message, encoded for the wire. False, and
     * nothing sent, if it's blank or the session isn't open. Fine during a
     * turn: the agent queues it (Claude merges lines queued together).
     */
    fun sendPrompt(text: String): Boolean

    /** The Stop button: ends the running turn and keeps the context. False when no turn runs. */
    fun cancel(): Boolean

    /**
     * Answers [AgentEvent.PermissionRequest] with one of its options;
     * [message] goes to the agent with a rejection. False if the request is
     * no longer pending (answered or withdrawn) or the option isn't one of its.
     */
    fun answerPermission(requestId: String, optionId: String, message: String? = null): Boolean

    /**
     * Answers [AgentEvent.QuestionRequest]: question text to the chosen
     * label (several labels of a multi-select joined with ", ", as the
     * terminal does). False if no longer pending.
     */
    fun answerQuestion(requestId: String, answers: Map<String, String>): Boolean

    /** Refuses a question, [message] telling the agent why. False if no longer pending. */
    fun declineQuestion(requestId: String, message: String? = null): Boolean

    /** Answers [AgentEvent.PlanRequest]; [message] says why with a refusal. False if no longer pending. */
    fun answerPlan(requestId: String, approved: Boolean, message: String? = null): Boolean

    /** Changes the permission mode (Claude's: `default`, `acceptEdits`, `plan`, …), for the turns after this one. */
    fun setMode(mode: String): Boolean

    /** Changes the model ([AgentModel.value]), for the turns after this one. */
    fun setModel(model: String): Boolean

    /** Ends the process (through [AgentTransport.close]). A second call does nothing. */
    fun stop()
}

/**
 * One agent process, as lines. The app implements it; tests fake it.
 *
 * The app's implementation must:
 * - keep calls to [writeLine] in order and write each as one whole line
 *   (one line that isn't JSON kills Claude's process);
 * - not block in [writeLine]: it runs under the session's lock, on the
 *   caller's thread, which may be the main one, so queue to a writer thread;
 * - read stdout on a thread of its own and call [Listener.onLine] one line
 *   at a time, then [Listener.onExit] exactly once after the last line,
 *   also when the process could not start;
 * - not lose what the process said on stderr: its tail is [Listener.onExit]'s
 *   `detail`, the only explanation some failures have.
 */
interface AgentTransport {
    interface Listener {
        fun onLine(line: String)

        /** [exitCode] is null when the process never ran; [detail] is stderr's tail, or why it didn't start. */
        fun onExit(exitCode: Int?, detail: String)
    }

    /** Starts the process, with [listener] for what it says. Must not throw: failures go to [Listener.onExit]. */
    fun start(listener: Listener)

    /** Queues [line] (no line break in it) for the process's stdin. */
    fun writeLine(line: String)

    /** Asks the process to end, and returns at once; [Listener.onExit] follows when it has. */
    fun close()
}

/** ACP's tool kinds. Claude's backend never produces [DELETE] or [MOVE]: those go through Bash. */
enum class ToolKind { READ, EDIT, DELETE, MOVE, SEARCH, EXECUTE, THINK, FETCH, OTHER }

enum class ToolStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

/** Why a turn ended, as ACP names them ([ERROR] is ours). */
enum class StopReason { END_TURN, CANCELLED, REFUSAL, MAX_TOKENS, ERROR }

enum class PermissionOptionKind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS }

/** One choice on a permission card; pick by [kind], not [id] or [name]. */
data class PermissionOption(val id: String, val name: String, val kind: PermissionOptionKind)

/**
 * A tool call as it stands; each event carries the whole of it.
 *
 * A permission card must show [title]: it comes from the tool's real input
 * (the command, the path). [detail] may be the model's own description of
 * the call, so it can't stand in for [title].
 */
data class ToolCallInfo(
    val id: String,
    val name: String,
    val title: String,
    val kind: ToolKind,
    val status: ToolStatus,
    val content: String = "",
    val detail: String? = null,
    /** The tool call of the subagent that made this one; null for the agent's own. */
    val parentId: String? = null,
)

data class QuestionOption(val label: String, val description: String?)

data class AgentQuestion(val question: String, val header: String?, val multiSelect: Boolean, val options: List<QuestionOption>)

/** A model the agent offers: [value] is what [AgentSession.setModel] takes. */
data class AgentModel(val value: String, val name: String, val description: String?)

/** How a permission, question or plan request ended. */
sealed interface RequestOutcome {
    data class Selected(val kind: PermissionOptionKind) : RequestOutcome
    data class Answered(val answers: Map<String, String>) : RequestOutcome
    data object Declined : RequestOutcome
    data class Plan(val approved: Boolean) : RequestOutcome

    /** The agent took it back, or the turn or process ended first. */
    data object Withdrawn : RequestOutcome
}

sealed interface AgentEvent {
    /** The agent's session and model; repeats when one of them or the mode changes. Comes with the first turn. */
    data class SessionStarted(val sessionId: String, val model: String?, val mode: String?) : AgentEvent

    data class AvailableModels(val models: List<AgentModel>) : AgentEvent

    /** The user's own message, once sent. */
    data class UserMessage(val id: String, val text: String) : AgentEvent

    /** A turn began: a prompt was sent, or the agent started one by itself (a background job finished). */
    data object TurnStarted : AgentEvent

    /**
     * More text of the agent message [id]: append it, or with [replace]
     * make it the whole text (the agent's final copy disagreed with what
     * had streamed).
     */
    data class AgentMessageChunk(val id: String, val text: String, val replace: Boolean = false) : AgentEvent

    /** The agent started or stopped thinking; its thoughts aren't given. */
    data class ThoughtIndicator(val active: Boolean) : AgentEvent

    data class ToolCall(val call: ToolCallInfo) : AgentEvent

    data class ToolCallUpdate(val call: ToolCallInfo) : AgentEvent

    data class PermissionRequest(val requestId: String, val call: ToolCallInfo, val options: List<PermissionOption>) : AgentEvent

    /** The agent asks the user (Claude's `AskUserQuestion`). */
    data class QuestionRequest(val requestId: String, val toolCallId: String?, val questions: List<AgentQuestion>) : AgentEvent

    /** The agent shows its plan for approval (Claude's `ExitPlanMode`). */
    data class PlanRequest(val requestId: String, val toolCallId: String?, val plan: String, val planFilePath: String?) : AgentEvent

    /** A request above was answered or taken back. */
    data class RequestResolved(val requestId: String, val outcome: RequestOutcome) : AgentEvent

    /** Something the user should see that belongs to no turn. */
    data class Notice(val text: String) : AgentEvent

    /** [message] says what went wrong with [StopReason.ERROR]. [costUsd] is what the agent reports. */
    data class TurnEnded(val stopReason: StopReason, val message: String? = null, val costUsd: Double? = null) : AgentEvent

    /** Sign-in is needed: the app opens a tab for it. Followed by [TurnEnded] with no message. */
    data class NotSignedIn(val message: String) : AgentEvent

    /** The process has gone; nothing follows. [stopped]: because of [AgentSession.stop]. */
    data class ProcessEnded(val exitCode: Int?, val detail: String, val stopped: Boolean) : AgentEvent
}
