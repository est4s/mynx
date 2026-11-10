package io.github.est4s.terminal.core

import java.io.File

/** Whether the chat can run: [check][ChatHost.check]'s answer, and [CHECKING] while one runs. */
enum class ChatAccess { CHECKING, READY, NOT_INSTALLED, NOT_SIGNED_IN }

/**
 * What the Chat screen draws. [canRestart]: the process ended by itself and
 * a chat can go on with `--resume`. [stopping]: the last process is being
 * waited for, so nothing can be sent. [handOffBlock] says why "To tab"
 * can't go now; null when it can. [busy]: a turn runs or a card waits for
 * an answer, so going to another chat would cut it off: ask first.
 */
data class ChatState(
    val conversation: Conversation,
    val access: ChatAccess,
    val canRestart: Boolean,
    val stopping: Boolean = false,
    val tabHold: ChatTabHold = ChatTabHold.NONE,
    val handOffBlock: String? = null,
    val busy: Boolean = false,
    /** Model…'s choices, [model] the one marked (an [AgentModel.value]). */
    val models: List<AgentModel> = chatModelChoices(conversation.models),
    val model: String = "default",
    /** The permission mode, as Claude names it: Mode… marks it if it's one of [CHAT_MODES]. */
    val mode: String = conversation.mode ?: "default",
    /** The line under the title. */
    val line: ChatStatusLine = chatStatusLine(null, conversation.model, conversation.mode, models),
)

/** A tab may still run this chat's session: [ASKING] whether to take it back, [TAKING_BACK] while its tab closes. */
enum class ChatTabHold { NONE, ASKING, TAKING_BACK }

/**
 * The tab a chat was handed to ([ChatHost.openInTab]). It holds the session
 * only while the claude it was opened with runs: a session resumed by hand
 * in that tab's shell after that claude has exited isn't tracked.
 */
interface ChatTab {
    /** Whether its claude may still be running: false once it has exited, or the tab has closed or never started. */
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

    /** A process has ended and [ChatController.live] is now false: the app may go, if nothing else keeps it. */
    fun ended() {}

    fun transport(launch: AgentLaunch): AgentTransport

    /** A fresh UUID for `--session-id`. */
    fun newSessionId(): String

    /** Runs [work] on a thread of its own: reading transcripts. */
    fun background(work: () -> Unit)

    /** Debian's [path] as the app sees it. */
    fun hostFile(path: String): File

    /** Opens a tab running Claude Code on [sessionId] in [cwd] with the chat's picks, null for none (see [claudeHandOffCommand]), and shows it. */
    fun openInTab(sessionId: String, cwd: String, model: String?, mode: String?): ChatTab

    /** How notices go now ([chatNotice]); read only when one may be due. The default: Chat on screen, so none. */
    fun noticeRules(): ChatNoticeRules = ChatNoticeRules(onScreen = true)

    /** Posts the chat's notification, replacing the last. */
    fun notify(notice: ChatNotice) {}

    /** Removes the chat's notification: the user has opened Chat. */
    fun clearNotice() {}

    /** [ChatController.working] changed: the `wakelock` setting's auto follows it. */
    fun workingChanged(working: Boolean) {}
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
 * One session, one program: a process being replaced ([resume], [handOff],
 * [newChat]) is stopped and waited for ([ChatState.stopping]) before anything runs
 * on its behalf, and a session handed to a tab isn't run here again while
 * that tab may still run it ([ChatTabHold]). A resumed chat shows its
 * transcript ([readClaudeHistory]): `--resume` doesn't replay it.
 *
 * The model and permission mode picked ([setModel], [setMode]) go to the
 * running process and count once Claude takes them (a refused one is
 * dropped; with no process, a pick waits for the launch). They are the
 * flags of every launch of this chat, and of its hand-off tab: `--resume`
 * restores the session's model but not its mode. With no pick, Model…
 * ticks the model Claude last reported (its init, or before a resumed
 * process runs, the transcript's last answer), but only a pick is ever a
 * `--model` flag. Only a mode the user picked from [CHAT_MODES] is ever a
 * flag: what Claude reports (its init, a status line, the user's own
 * settings) is only shown, and once it reports another mode than the pick,
 * or a plan is approved (which leaves plan mode without saying so), the
 * pick is dropped and the next launch has no mode flag. A new or another
 * chat starts from the defaults again.
 */
class ChatController(
    private val host: ChatHost,
    private val post: (() -> Unit) -> Unit,
    private val home: String = "/root",
    private val cwd: String = "/root",
    /** Finds the apps and APKs a turn made, for artifact cards; none without it. */
    private val artifacts: ArtifactLookup? = null,
    private val now: () -> Long = System::currentTimeMillis,
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
    // Artifact cards: when this turn started, the checks asked and applied, the cards made, and
    // a count bumped when the conversation is replaced, so a late check is dropped.
    private var turnStart: Long? = null
    private var artifactChecks = 0
    private var artifactsApplied = 0
    private var cards = 0
    private var conversations = 0
    private var toldWorking = false
    // Picked in this chat and taken, or waiting for the launch; null: no flag. The pending ones
    // were sent to the process and wait for its answer.
    private var model: String? = null
    private var mode: String? = null
    private var pendingModel: String? = null
    private var pendingMode: String? = null
    // The last list the agent gave, kept for chats before their process says.
    private var knownModels: List<AgentModel> = emptyList()
    // A chat picked from Chats: it runs the model it ran before, which isn't the default.
    private var pastChat = false

    val state: ChatState
        get() {
            val models = chatModelChoices(knownModels)
            val menu = chatModelMenu(model, conversation.model, models)
            val mode = mode ?: conversation.mode
            return ChatState(
                conversation, access, canRestart, exiting != null, tabHold, handOffBlock(), busy(),
                models = menu.models,
                model = menu.ticked,
                mode = mode ?: "default",
                line = chatStatusLine(model, conversation.model, mode, models, pastChat),
            )
        }

    /** A turn runs and no card waits for the user: the app keeps the CPU awake for it ([Wakelock]). */
    val working: Boolean get() = session != null && conversation.running && !cardWaiting()

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
        host.clearNotice()
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
        conversations++
        model = null
        mode = null
        pendingModel = null
        pendingMode = null
        turnStart = null
        chatId = id
        pastChat = true
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
        stopThen { heldByTabs[id] = host.openInTab(id, cwd, model, mode) }
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

    private fun cardWaiting(): Boolean = session != null && conversation.items.any(::unanswered)

    private fun busy(): Boolean = conversation.running || cardWaiting()

    private fun handOffBlock(): String? = when {
        conversation.running -> CHAT_HAND_OFF_RUNNING
        cardWaiting() -> CHAT_HAND_OFF_CARD
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
            val history = runCatching { readClaudeTranscript(file) }.getOrDefault(ClaudeHistory(emptyList(), null))
            val items = history.items
                .let { rows -> artifacts?.let { withHistoryArtifacts(rows, it, cwd, home) } ?: rows }
            post {
                if (own == historyLoad && chatId == id && items.isNotEmpty()) {
                    conversation = conversation.copy(items = items + conversation.items.drop(before))
                    // What `--resume` will run, till a process says; a running one knows better.
                    if (session == null && history.model != null) conversation = conversation.copy(model = history.model)
                    changed()
                }
            }
        }
    }

    fun cancel(): Boolean = session?.cancel() ?: false

    /** Model…: to the running process, if any, kept once it takes it; with none, the flag of the next launch. */
    fun setModel(value: String) {
        if (session?.setModel(value) == true) pendingModel = value else model = value
        changed()
    }

    /** Mode…: as [setModel]; only [CHAT_MODES]. */
    fun setMode(value: String) {
        if (!chatModeOffered(value)) return
        if (session?.setMode(value) == true) pendingMode = value else mode = value
        changed()
    }

    // A mode Claude says it's in: the pick it took, or one that ends the pick.
    private fun modeReported(value: String) {
        if (value == pendingMode) {
            pendingMode = null
            mode = value
        } else if (mode != null && value != mode) {
            mode = null
        }
    }

    fun answerPermission(requestId: String, optionId: String): Boolean = session?.answerPermission(requestId, optionId) ?: false

    fun answerQuestion(requestId: String, answers: Map<String, String>): Boolean = session?.answerQuestion(requestId, answers) ?: false

    /** An approved plan leaves plan mode, which Claude doesn't report until its next init: not known till then. */
    fun answerPlan(requestId: String, approved: Boolean): Boolean {
        if (session?.answerPlan(requestId, approved) != true) return false
        if (approved) {
            if (mode == "plan") mode = null
            if (pendingMode == "plan") pendingMode = null
            if (conversation.mode == "plan") conversation = conversation.copy(mode = null)
            changed()
        }
        return true
    }

    /**
     * Ends this chat and starts an empty one; what the old process still says
     * is ignored, and nothing runs until it has exited (it may be picked
     * again from Chats at once).
     */
    fun newChat() {
        stopThen(null)
        conversation = Conversation()
        conversations++
        model = null
        mode = null
        pendingModel = null
        pendingMode = null
        turnStart = null
        chatId = null
        pastChat = false
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
        val options = ClaudeLaunchOptions(
            cwd = cwd, home = home, resume = resume, sessionId = fresh, binary = binary, model = model, permissionMode = mode,
        )
        canRestart = false
        initSeen = false
        pendingModel = null
        pendingMode = null
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
            if (!live) host.ended()
            return
        }
        if (own != generation) return
        if (event == AgentEvent.TurnStarted && !conversation.running) turnStart = now()
        conversation = conversation.reduce(event)
        if (mayNotify(event)) {
            val took = if (event is AgentEvent.TurnEnded) turnStart?.let { now() - it } else null
            chatNotice(event, conversation, took, host.noticeRules())?.let(host::notify)
        }
        when (event) {
            is AgentEvent.ToolCall -> if (event.call.status == ToolStatus.COMPLETED) checkArtifacts(event.call)
            is AgentEvent.ToolCallUpdate -> if (event.call.status == ToolStatus.COMPLETED) checkArtifacts(event.call)
            else -> Unit
        }
        when (event) {
            is AgentEvent.SessionStarted -> {
                chatId = event.sessionId
                initSeen = true
                event.mode?.let(::modeReported)
            }
            is AgentEvent.ModeChanged -> modeReported(event.mode)
            is AgentEvent.ModeRefused -> if (event.mode == pendingMode) pendingMode = null
            is AgentEvent.ModelChanged -> {
                if (event.model == pendingModel) pendingModel = null
                model = event.model
            }
            is AgentEvent.ModelRefused -> if (event.model == pendingModel) pendingModel = null
            is AgentEvent.AvailableModels -> knownModels = event.models
            is AgentEvent.TurnEnded -> {
                checkArtifacts(null)
                when {
                    isNoConversationResult(event.message) -> resumable = false
                    // Without an init, this is a Stop that beat the process: there's no conversation yet.
                    initSeen && event.stopReason != StopReason.ERROR -> resumable = true
                }
            }
            is AgentEvent.Notice -> if (isNoConversationResult(event.text)) resumable = false
            is AgentEvent.NotSignedIn -> {
                signInLost = true
                access = ChatAccess.NOT_SIGNED_IN
            }
            is AgentEvent.ProcessEnded -> {
                session = null
                // Never answered: not taken.
                pendingModel = null
                pendingMode = null
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

    // Looks on disk, off the main thread, for what this turn's tool calls made: after a
    // [completed] call that may have touched an app folder or an APK, and at the turn's
    // end (null) for all of them, links included.
    private fun checkArtifacts(completed: ToolCallInfo?) {
        val lookup = artifacts ?: return
        if (completed != null && !artifactTool(completed)) return
        val items = conversation.items
        val start = items.indexOfLast { it is ChatItem.User } + 1
        val calls = items.subList(start, items.size).mapNotNull { (it as? ChatItem.Tool)?.call }
        val hints = artifactHints(calls, cwd, home)
        if (hints.isEmpty()) return
        if (completed != null && hints.none { it.direct(home) }) return
        val since = turnStart?.let { it - ARTIFACT_CLOCK_SLACK_MS }
        val check = ++artifactChecks
        val chat = conversations
        val turn = items.getOrNull(start - 1)?.id
        host.background {
            val found = runCatching { lookup.find(hints, since) }.getOrDefault(emptyList())
            post {
                val sameTurn = conversation.items.lastOrNull { it is ChatItem.User }?.id == turn
                if (chat == conversations && sameTurn && check > artifactsApplied) {
                    artifactsApplied = check
                    val items = withArtifactCards(conversation.items, found) { "artifact-${++cards}" }
                    if (items !== conversation.items) {
                        conversation = conversation.copy(items = items)
                        changed()
                    }
                }
            }
        }
    }

    private fun changed() {
        listener?.invoke(state)
        if (working != toldWorking) {
            toldWorking = working
            host.workingChanged(toldWorking)
        }
    }
}
