package io.github.est4s.terminal.core

import java.io.File

/** Whether the chat can run: [check][ChatHost.check]'s answer, and [CHECKING] while one runs. */
enum class ChatAccess { CHECKING, READY, NOT_INSTALLED, NOT_SIGNED_IN }

/**
 * What the Chat screen draws. [canRestart]: the process ended by itself and
 * a chat can go on with `--resume`. [stopping]: the last process is being
 * waited for, so nothing can be sent. [handOffBlock] says why "To tab"
 * can't go now; null when it can. [busy]: a turn runs or a card waits for
 * an answer, so [ChatHub] keeps its process when the chat is left.
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
    /** The `chat-mode` setting, the mode new chats start in. */
    val defaultMode: String = "default",
    /** The `chat-model` setting, the model new chats start with (`default`: Claude's own). */
    val defaultModel: String = "default",
    /** The chat's title: its first prompt (as the history row has it), null for a new empty chat. */
    val title: String? = null,
    /** This chat's context, and the plan's usage as any chat last heard it. */
    val usage: ChatUsage = ChatUsage(),
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

    /** Removes the notification in [slot] ([ChatNotice.slot]): the user has opened its chat. */
    fun clearNotice(slot: Int) {}

    /** [ChatController.working] changed: the `wakelock` setting's auto follows it. */
    fun workingChanged(working: Boolean) {}

    /** The `chat-mode` setting: the permission mode new chats launch with; null for no flag. */
    fun chatDefaultMode(): String? = null

    /** Writes the `chat-mode` setting ("Use as default"); [value] is one of [CHAT_MODES]. */
    fun setChatDefaultMode(value: String) {}

    /** The `chat-model` setting: the model new chats launch with; null or `default` for no flag. */
    fun chatDefaultModel(): String? = null

    /** Writes the `chat-model` setting (Model's "Default for new chats"). */
    fun setChatDefaultModel(value: String) {}
}

/** A chat's own picks: the model and permission mode its launches take as flags, null for none. */
data class ChatPicks(val model: String?, val mode: String?)

/** How many sessions' picks are kept: the most recently changed. */
const val CHAT_PICKS_MAX = 300

/**
 * What every chat of a [ChatHub] shares: the sessions handed to a tab, by
 * id, for as long as the tab may run them (so a session handed off is known
 * whichever controller opens it), the models the agent last listed, the
 * last check that found the agent ready (a new chat starts from it rather
 * than checking again; a chat that finds sign-in lost forgets it), each
 * session's [ChatPicks], and the plan's usage as any chat last heard it.
 *
 * The picks outlive a controller (leaving an idle chat drops it, and
 * `--resume` restores the model but not the mode) and, with [picksFile],
 * the app: one line a session, `ID<tab>MODEL<tab>MODE` (`-` for none),
 * written whole to a temp file and renamed into place. Lines that don't
 * read are skipped.
 */
class ChatShared(private val picksFile: File? = null) {
    internal val tabs = HashMap<String, ChatTab>()
    internal var models: List<AgentModel> = emptyList()
    internal var ready: ChatCheck? = null
    /** The `chat-mode` setting as last read; null: no flag. */
    internal var defaultMode: String? = null
    /** The `chat-model` setting as last read; null: no flag. */
    internal var defaultModel: String? = null
    internal var plan: List<PlanWindow> = emptyList()
    internal var planSeenMs: Long? = null
    /** Context windows Claude reported, by model: a resumed chat's ring before its first reply. */
    internal val windows = HashMap<String, Long>()
    // In order of change, the latest last.
    private val picks: LinkedHashMap<String, ChatPicks> by lazy { readPicks() }

    internal fun picks(sessionId: String): ChatPicks? = picks[sessionId]

    internal fun keepPicks(sessionId: String, value: ChatPicks) {
        if (picks[sessionId] == value) return
        picks.remove(sessionId)
        picks[sessionId] = value
        while (picks.size > CHAT_PICKS_MAX) picks.remove(picks.keys.first())
        savePicks()
    }

    private fun readPicks(): LinkedHashMap<String, ChatPicks> {
        val map = LinkedHashMap<String, ChatPicks>()
        val text = picksFile?.let { runCatching { it.takeIf(File::isFile)?.readText() }.getOrNull() } ?: return map
        for (line in text.lines()) {
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0].isBlank() || parts.any { it.isBlank() }) continue
            fun value(part: String) = part.takeUnless { it == "-" }
            map[parts[0]] = ChatPicks(value(parts[1]), value(parts[2])?.takeIf(::chatModeOffered))
        }
        return map
    }

    private fun savePicks() {
        val file = picksFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(picks.entries.joinToString("") { (id, p) -> "$id\t${p.model ?: "-"}\t${p.mode ?: "-"}\n" })
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }
}

/** A chat's row mark in the history ([chatHistory]). */
enum class ChatRowState { WORKING, WAITING, IDLE }

/**
 * One chat of the Chat screen, for life: a new one, or the past session
 * [resumeId] (its transcript shown, resumed with the next prompt). Kept by
 * [ChatHub], in the service, so it outlives the activity: the
 * [ClaudeSession] (one per process), the [Conversation], and whether the
 * chat may run. Main thread only, except what [AgentSession]'s listener
 * posts through [post], in order.
 *
 * The first prompt starts the process, with a new `--session-id`. After
 * that a process that ended is started with `--resume`, so that one chat
 * is never driven by two processes; if no turn ever finished there is
 * nothing to resume, so a new id it is, as after a resume that found no
 * conversation.
 *
 * One session, one program: a process being stopped ([stop], [handOff]) is
 * waited for ([ChatState.stopping]) before anything runs on its behalf, and
 * a session handed to a tab isn't run here again while that tab may still
 * run it ([ChatTabHold], kept in [ChatShared] for all chats). A resumed chat
 * shows its transcript ([readClaudeHistory]): `--resume` doesn't replay it.
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
 * chat starts from the defaults again (its own controller).
 */
class ChatController(
    private val host: ChatHost,
    private val post: (() -> Unit) -> Unit,
    private val home: String = "/root",
    private val cwd: String = "/root",
    /** Finds the apps and APKs a turn made, for artifact cards; none without it. */
    private val artifacts: ArtifactLookup? = null,
    private val now: () -> Long = System::currentTimeMillis,
    resumeId: String? = null,
    private val shared: ChatShared = ChatShared(),
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
    private val heldByTabs get() = shared.tabs
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
    private var knownModels: List<AgentModel>
        get() = shared.models
        set(value) {
            shared.models = value
        }
    // A past chat resumed: it runs the model it ran before, which isn't the default.
    private val pastChat = resumeId != null
    private var firstPrompt: String? = null
    // The transcript's first prompt, for a resumed chat.
    private var historyTitle: String? = null
    private var context: ContextUse? = null

    /** When this chat last did anything: made, a prompt, an event. */
    var touchedMs: Long = now()
        private set

    init {
        shared.ready?.let {
            access = ChatAccess.READY
            binary = it.binary
        }
        if (resumeId != null) {
            conversation = Conversation(sessionId = resumeId)
            chatId = resumeId
            resumable = true
            loadHistory(resumeId)
        }
        shared.defaultMode = host.chatDefaultMode()?.takeIf(::chatModeOffered)
        shared.defaultModel = host.chatDefaultModel()?.takeIf { it.isNotBlank() && it != "default" }
        val kept = resumeId?.let(shared::picks)
        if (kept != null) {
            model = kept.model
            mode = kept.mode
        } else {
            mode = shared.defaultMode
            // A past chat resumes with the model it ran: `--resume` restores it.
            if (resumeId == null) model = shared.defaultModel
        }
    }

    /** The session this chat runs, once it has one: the one it resumes, or the id its first launch got. */
    val sessionId: String? get() = chatId

    /**
     * The chat's title, as its history row has it: the transcript's, else
     * the first prompt sent here (its text, else its first file's name), on
     * one line; null before either.
     */
    val title: String? get() = historyTitle ?: firstPrompt?.let { promptTitle(promptTitleText(it)) }

    /** A turn runs or a card waits: [ChatState.busy]. */
    val busy: Boolean get() = busy()

    /** A process runs, not counting one being stopped: [stop] has something to stop. */
    val running: Boolean get() = session != null

    /** The history's mark for it. */
    val rowState: ChatRowState
        get() = when {
            cardWaiting() -> ChatRowState.WAITING
            session != null && conversation.running -> ChatRowState.WORKING
            else -> ChatRowState.IDLE
        }

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
                defaultMode = shared.defaultMode ?: "default",
                defaultModel = shared.defaultModel ?: "default",
                title = title,
                usage = ChatUsage(context, shared.plan, shared.planSeenMs),
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
        shared.ready = check.takeIf { result == ChatAccess.READY }
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
    fun send(text: String, attachments: List<ChatAttachment> = emptyList()): Boolean {
        if (access != ChatAccess.READY || (text.isBlank() && attachments.isEmpty()) || exiting != null) return false
        if (session == null && heldByTab()) {
            ask()
            return false
        }
        val prompt = promptWithAttachments(text, attachments)
        val sent = (session ?: launch()).sendPrompt(prompt)
        if (sent) {
            if (firstPrompt == null) firstPrompt = prompt
            touchedMs = now()
        }
        return sent
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
                    history.title?.let { historyTitle = it }
                    if (context == null) history.context?.let { context = ContextUse(it, history.model?.let(shared.windows::get)) }
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

    /** "Use as default": the `chat-mode` setting, for new chats; this chat's own pick stays. Only [CHAT_MODES]. */
    fun setDefaultMode(value: String) {
        if (!chatModeOffered(value)) return
        host.setChatDefaultMode(value)
        shared.defaultMode = value
        changed()
    }

    /** The model's "Default for new chats": the `chat-model` setting; this chat's own pick stays. */
    fun setDefaultModel(value: String) {
        if (value.isBlank()) return
        host.setChatDefaultModel(value)
        shared.defaultModel = value.takeIf { it != "default" }
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
     * Stops the process, if one runs, keeping the chat: what it still says is
     * ignored, it stays [live] until it has exited, and the next prompt
     * resumes the session then.
     */
    fun stop() {
        if (session == null) return
        stopThen(null)
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
        touchedMs = now()
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
            is AgentEvent.ContextUsed -> {
                context = ContextUse(event.usedTokens, event.windowTokens)
                val model = conversation.model
                if (event.windowTokens != null && model != null) shared.windows[model] = event.windowTokens
            }
            is AgentEvent.PlanLimits -> {
                shared.plan = event.windows
                shared.planSeenMs = now()
            }
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
                shared.ready = null
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
        chatId?.let { shared.keepPicks(it, ChatPicks(model, mode)) }
        listener?.invoke(state)
        if (working != toldWorking) {
            toldWorking = working
            host.workingChanged(toldWorking)
        }
    }
}
