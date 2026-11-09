package io.github.est4s.terminal.core

/** Whether the chat can run: [check][ChatHost.check]'s answer, and [CHECKING] while one runs. */
enum class ChatAccess { CHECKING, READY, NOT_INSTALLED, NOT_SIGNED_IN }

/** What the Chat screen draws. [canRestart]: the process ended by itself and a chat can go on with `--resume`. */
data class ChatState(val conversation: Conversation, val access: ChatAccess, val canRestart: Boolean)

/** [check][ChatHost.check]'s answer: [binary] is the path to run when [access] is ready, as Debian sees it. */
data class ChatCheck(val access: ChatAccess, val binary: String? = null)

/** What [ChatController] needs from the app. */
interface ChatHost {
    /** Whether the agent can run (off the main thread, in the app); [done] is called on the main thread. */
    fun check(done: (ChatCheck) -> Unit)

    /** The process ended by itself and [ChatController.live] is now false. */
    fun ended() {}

    fun transport(launch: AgentLaunch): AgentTransport

    /** A fresh UUID for `--session-id`. */
    fun newSessionId(): String
}

/**
 * The one chat of the Chat screen, kept by the service so it outlives the
 * activity: the [ClaudeSession] (one per process), the [Conversation], and
 * whether the chat may run. Main thread only, except what [AgentSession]'s
 * listener posts through [post], in order.
 *
 * The first prompt starts the process, with a new `--session-id`. After
 * that a process that ended is started with `--resume`, so that one chat
 * is never driven by two processes; if no turn ever finished there is
 * nothing to resume, so a new id it is, as after a resume that found no
 * conversation.
 */
class ChatController(
    private val host: ChatHost,
    private val post: (() -> Unit) -> Unit,
    private val home: String = "/root",
    private val cwd: String = "/root",
) {
    private var conversation = Conversation()
    private var access = ChatAccess.CHECKING
    private var checking = false
    private var session: AgentSession? = null
    private var generation = 0
    private var chatId: String? = null
    private var resumable = false
    private var initSeen = false
    private var binary: String? = null
    private var canRestart = false
    private var signInLost = false
    private var waitingForExit = false

    val state: ChatState get() = ChatState(conversation, access, canRestart)

    /** A process is running, so the app mustn't exit by itself. */
    val live: Boolean get() = session != null

    /** Told of every change. Setting it tells it at once how things stand. */
    var listener: ((ChatState) -> Unit)? = null
        set(value) {
            field = value
            changed()
        }

    /** The screen is on show: checks whether the chat can run, unless that's known or being found out. */
    fun opened() {
        if (access == ChatAccess.READY || checking || waitingForExit) return
        access = ChatAccess.CHECKING
        checking = true
        changed()
        host.check(::checked)
    }

    private fun checked(check: ChatCheck) {
        val result = check.access
        checking = false
        check.binary?.let { binary = it }
        val current = session
        if (result == ChatAccess.READY && signInLost && current != null) {
            // Signed in afresh: don't count on a running process noticing.
            signInLost = false
            waitingForExit = true
            current.stop()
        } else {
            if (result == ChatAccess.READY) signInLost = false
            access = result
        }
        changed()
    }

    /** Sends a prompt, starting the process if there is none; false, and nothing done, if it can't. */
    fun send(text: String): Boolean {
        if (access != ChatAccess.READY || text.isBlank()) return false
        return (session ?: launch()).sendPrompt(text)
    }

    /** The Restart button: goes on with the same chat. */
    fun restart() {
        if (access != ChatAccess.READY || session != null) return
        launch()
    }

    fun cancel(): Boolean = session?.cancel() ?: false

    fun answerPermission(requestId: String, optionId: String): Boolean = session?.answerPermission(requestId, optionId) ?: false

    fun answerQuestion(requestId: String, answers: Map<String, String>): Boolean = session?.answerQuestion(requestId, answers) ?: false

    fun answerPlan(requestId: String, approved: Boolean): Boolean = session?.answerPlan(requestId, approved) ?: false

    /** Ends this chat and starts an empty one; what the old process still says is ignored. */
    fun newChat() {
        val old = session
        generation++
        session = null
        old?.stop()
        conversation = Conversation()
        chatId = null
        resumable = false
        canRestart = false
        signInLost = false
        if (waitingForExit) {
            waitingForExit = false
            access = ChatAccess.READY
        }
        changed()
    }

    /** The app is exiting. */
    fun shutdown() {
        val old = session
        generation++
        session = null
        old?.stop()
        conversation = conversation.copy(running = false, thinking = false)
        changed()
    }

    private fun launch(): AgentSession {
        val resume = chatId.takeIf { resumable }
        val fresh = if (resume == null) host.newSessionId().also { chatId = it } else null
        val options = ClaudeLaunchOptions(cwd = cwd, home = home, resume = resume, sessionId = fresh, binary = binary)
        canRestart = false
        initSeen = false
        conversation = conversation.copy(processEnded = false)
        generation++
        val own = generation
        val created = ClaudeSession(host.transport(claudeLaunch(options))) { event ->
            post { onEvent(own, event) }
        }
        session = created
        created.start()
        changed()
        return created
    }

    private fun onEvent(own: Int, event: AgentEvent) {
        if (own != generation) return
        conversation = conversation.reduce(event)
        when (event) {
            is AgentEvent.SessionStarted -> {
                chatId = event.sessionId
                initSeen = true
            }
            is AgentEvent.TurnEnded -> when {
                isNoConversationResult(event.message) -> resumable = false
                // Without an init, this is a Stop that beat the process: there's no conversation yet.
                initSeen && event.stopReason != StopReason.ERROR -> resumable = true
            }
            is AgentEvent.Notice -> if (isNoConversationResult(event.text)) resumable = false
            is AgentEvent.NotSignedIn -> {
                signInLost = true
                access = ChatAccess.NOT_SIGNED_IN
            }
            is AgentEvent.ProcessEnded -> {
                session = null
                if (waitingForExit) {
                    waitingForExit = false
                    access = ChatAccess.READY
                } else {
                    canRestart = !event.stopped
                }
                changed()
                host.ended()
                return
            }
            else -> Unit
        }
        changed()
    }

    private fun changed() {
        listener?.invoke(state)
    }
}
