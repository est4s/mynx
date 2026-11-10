package io.github.est4s.terminal.core

import java.io.File

/** Whether the chat can run: [check][ChatHost.check]'s answer, and [CHECKING] while one runs. */
enum class ChatAccess { CHECKING, READY, NOT_INSTALLED, NOT_SIGNED_IN }

/** What the Chat screen draws. [canRestart]: the process ended by itself and a chat can go on with `--resume`. */
/**
 * What the Chat screen draws. [canRestart]: the process ended by itself and
 * a chat can go on with `--resume`. [stopping]: the last process is being
 * waited for, so nothing can be sent. [handOffBlock] says why "To tab"
 * can't go now; null when it can.
 */
data class ChatState(
    val conversation: Conversation,
    val access: ChatAccess,
    val canRestart: Boolean,
    val stopping: Boolean = false,
    val tabHold: ChatTabHold = ChatTabHold.NONE,
    val handOffBlock: String? = null,
)

/** A tab may still run this chat's session: [ASKING] whether to take it back, [TAKING_BACK] while its tab closes. */
enum class ChatTabHold { NONE, ASKING, TAKING_BACK }

/** The tab a chat was handed to ([ChatHost.openInTab]). */
interface ChatTab {
    /** Whether its claude may still be running. */
    val open: Boolean

    /** Closes the tab; [ChatController.tabChanged] follows once it has ended. */
    fun close()
}

const val CHAT_HAND_OFF_NOTHING = "Nothing to open in a tab yet: let a turn finish first."
const val CHAT_HAND_OFF_RUNNING = "Stop the running turn first."
const val CHAT_HAND_OFF_CARD = "Answer the open card, or Stop, first."
const val CHAT_HAND_OFF_STOPPING = "The chat is still stopping."
const val CHAT_HAND_OFF_HELD = "This chat is already open in a tab."

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

    /** Runs [work] on a thread of its own: reading transcripts. */
    fun background(work: () -> Unit)

    /** Debian's [path] as the app sees it. */
    fun hostFile(path: String): File

    /** Opens a tab running Claude Code on [sessionId] in [cwd] (see [claudeHandOffCommand]) and shows it. */
    fun openInTab(sessionId: String, cwd: String): ChatTab
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
 *
 * One session, one program: a process being replaced ([resume], [handOff])
 * is stopped and waited for ([ChatState.stopping]) before anything runs
 * on its behalf, and a session handed to a tab isn't run here again while
 * that tab may still run it ([ChatTabHold]). A resumed chat shows its
 * transcript ([readClaudeHistory]): `--resume` doesn't replay it.
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
    // The generation of a process stopped to make way, and what runs once it has exited.
    private var exiting: Int? = null
    private var afterExit: (() -> Unit)? = null
    private var historyLoad = 0
    // Sessions handed to a tab, by id, for as long as the tab may run them.
    private val heldByTabs = HashMap<String, ChatTab>()
    private var tabHold = ChatTabHold.NONE

    val state: ChatState get() = ChatState(conversation, access, canRestart, exiting != null, tabHold, handOffBlock())

    /** A process is running, so the app mustn't exit by itself. */
    val live: Boolean get() = session != null || exiting != null

    /** Told of every change. Setting it tells it at once how things stand. */
    var listener: ((ChatState) -> Unit)? = null
        set(value) {
            field = value
            changed()
        }

    /** The screen is on show: checks whether the chat can run, unless that's known or being found out. */
    fun opened() {
        heldByTab()
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
        if (access != ChatAccess.READY || text.isBlank() || exiting != null) return false
        if (session == null && heldByTab()) {
            ask()
            return false
        }
        return (session ?: launch()).sendPrompt(text)
    }

    /** The Restart button: goes on with the same chat. */
    fun restart() {
        if (access != ChatAccess.READY || session != null || exiting != null) return
        if (heldByTab()) return ask()
        launch()
    }

    private fun ask() {
        if (tabHold != ChatTabHold.NONE) return
        tabHold = ChatTabHold.ASKING
        changed()
    }

    /** The past chats of this folder, newest first, read off the main thread; [done] on the main thread. */
    fun pastChats(done: (List<ClaudeSessionSummary>) -> Unit) {
        val dir = host.hostFile(claudeTranscriptDir(home, cwd))
        host.background {
            val list = runCatching { listClaudeSessions(dir) }.getOrDefault(emptyList())
            post { done(list) }
        }
    }

    /**
     * Goes on with the past chat [id]: stops the running process (the next
     * prompt waits for it to exit), shows the transcript and resumes [id]
     * with the next prompt. The chat on screen already: nothing.
     */
    fun resume(id: String) {
        if (id == chatId) return
        stopThen(null)
        conversation = Conversation(sessionId = id)
        chatId = id
        resumable = true
        canRestart = false
        signInLost = false
        tabHold = ChatTabHold.NONE
        if (waitingForExit) {
            waitingForExit = false
            access = ChatAccess.READY
        }
        loadHistory(id)
        changed()
    }

    /**
     * "To tab": stops the chat's process, waits for it to exit, then has the
     * host open a tab on the session. False, with nothing done, when
     * [ChatState.handOffBlock] says why not.
     */
    fun handOff(): Boolean {
        heldByTab()
        if (handOffBlock() != null) return false
        val id = chatId ?: return false
        canRestart = false
        stopThen { heldByTabs[id] = host.openInTab(id, cwd) }
        changed()
        return true
    }

    /** Cancel on "still open in a tab": the prompt waits in the box. */
    fun keepInTab() {
        if (tabHold != ChatTabHold.ASKING) return
        tabHold = ChatTabHold.NONE
        changed()
    }

    /** Take back: closes the tab holding this chat's session; [tabChanged] says when it's gone. */
    fun takeBack() {
        if (!heldByTab()) return
        val tab = heldByTabs[chatId] ?: return
        tabHold = ChatTabHold.TAKING_BACK
        tab.close()
        changed()
    }

    /** A tab has ended, maybe one holding a session. */
    fun tabChanged() {
        heldByTab()
    }

    // Whether a tab may still run this chat's session. One that has let go is
    // forgotten, and the chat reads what the tab added to the transcript.
    private fun heldByTab(): Boolean {
        val id = chatId
        val held = id?.let { heldByTabs[it] }
        heldByTabs.values.removeAll { !it.open }
        if (held == null) return false
        if (id in heldByTabs) return true
        tabHold = ChatTabHold.NONE
        loadHistory(id!!)
        changed()
        return false
    }

    private fun handOffBlock(): String? = when {
        conversation.running -> CHAT_HAND_OFF_RUNNING
        session != null && conversation.items.any(::unanswered) -> CHAT_HAND_OFF_CARD
        exiting != null -> CHAT_HAND_OFF_STOPPING
        chatId?.let { it in heldByTabs } == true -> CHAT_HAND_OFF_HELD
        chatId == null || !resumable -> CHAT_HAND_OFF_NOTHING
        else -> null
    }

    private fun unanswered(item: ChatItem): Boolean = when (item) {
        is ChatItem.Permission -> item.outcome == null
        is ChatItem.Question -> item.outcome == null
        is ChatItem.Plan -> item.outcome == null
        else -> false
    }

    // Stops the process, if any, and runs [then] once it has exited; what it still says is ignored.
    private fun stopThen(then: (() -> Unit)?) {
        val old = session
        if (old == null) {
            then?.invoke()
            return
        }
        exiting = generation
        afterExit = then
        generation++
        session = null
        old.stop()
    }

    // Puts the transcript before what's been added since this started.
    private fun loadHistory(id: String) {
        val own = ++historyLoad
        val before = conversation.items.size
        val file = host.hostFile(claudeTranscriptPath(home, cwd, id))
        host.background {
            val items = runCatching { readClaudeHistory(file) }.getOrDefault(emptyList())
            post {
                if (own == historyLoad && chatId == id && items.isNotEmpty()) {
                    conversation = conversation.copy(items = items + conversation.items.drop(before))
                    changed()
                }
            }
        }
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
        tabHold = ChatTabHold.NONE
        historyLoad++
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
        exiting = null
        afterExit = null
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
        if (own == exiting && event is AgentEvent.ProcessEnded) {
            exiting = null
            val then = afterExit
            afterExit = null
            then?.invoke()
            changed()
            return
        }
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
