package io.github.est4s.terminal.core

/** A chat a [ChatHub] holds, as its history row sees it: [title] its first prompt, null before one. */
data class LiveChat(val sessionId: String, val title: String?, val touchedMs: Long, val state: ChatRowState)

/** One row of the chat history: [state] is null for a past chat no controller holds. */
data class ChatHistoryRow(val sessionId: String, val title: String, val modifiedMs: Long, val state: ChatRowState? = null)

const val CHAT_UNTITLED = "New chat"

/**
 * The history screen's rows: the [live] chats merged with the [past]
 * sessions read from their folder ([listClaudeSessions]). A live chat's row
 * replaces its transcript's, keeping the transcript's title (Claude's own
 * pick of the first prompt), and is as new as the later of the two. Live
 * chats not in a transcript yet come first, titled by their first prompt;
 * then the rest, newest first.
 */
fun chatHistory(live: List<LiveChat>, past: List<ClaudeSessionSummary>): List<ChatHistoryRow> {
    val byId = past.associateBy { it.sessionId }
    val liveIds = live.map { it.sessionId }.toSet()
    val (known, fresh) = live.partition { it.sessionId in byId }
    val freshRows = fresh.sortedByDescending { it.touchedMs }
        .map { ChatHistoryRow(it.sessionId, promptTitle(it.title), it.touchedMs, it.state) }
    val knownRows = known.map {
        val transcript = byId.getValue(it.sessionId)
        ChatHistoryRow(it.sessionId, transcript.title, maxOf(transcript.modifiedMs, it.touchedMs), it.state)
    }
    val pastRows = past.filter { it.sessionId !in liveIds }.map { ChatHistoryRow(it.sessionId, it.title, it.modifiedMs) }
    return freshRows + (knownRows + pastRows).sortedByDescending { it.modifiedMs }
}

internal fun promptTitle(prompt: String?): String =
    prompt?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(CLAUDE_TITLE_MAX) ?: CHAT_UNTITLED

/**
 * The chats of the Chat screen, kept by the service: several at once, each a
 * [ChatController] with a process of its own, and at most one [shown].
 * Main thread only, as the controllers.
 *
 * - [newChat] makes a fresh chat and shows it; [open] shows the chat this hub
 *   holds for that session, else a new controller resuming it. So two
 *   controllers never drive one session, and [ChatShared] gives them all the
 *   same hand-off tabs, so a session handed to a tab is known whichever
 *   controller opens it. [leave] shows none: the history.
 * - A chat that isn't shown keeps going while it's busy (a turn runs or a
 *   card waits). Once it isn't, its process is stopped, to spare the phone's
 *   memory, that includes one that goes idle in the background; its next
 *   prompt resumes it, as after any exit. A chat no longer shown that has no
 *   process left (stopped and exited, or a new chat never sent a prompt) is
 *   dropped: the history shows it from its transcript, and [open] makes a new
 *   controller for it. One being stopped is kept until it has exited, so
 *   opening it again can't run its session twice.
 * - [live] and [working] are any chat's. The host hears [ChatHost.ended] only
 *   when no chat runs a process any more, and [ChatHost.workingChanged] for
 *   the whole.
 * - Notices are per chat: "on screen" is that chat shown (and the app's Chat
 *   screen on show, as the host's rules say). Each chat's notice has a slot
 *   of its own ([ChatNotice.slot], [CHAT_NOTICE_SLOTS] of them, the oldest
 *   reused when all are up) and its session id, which the app opens on a
 *   tap; showing a chat clears its notice.
 * - The sign-in check is shared ([ChatShared.ready]): once one chat found the
 *   agent ready, new ones start ready.
 *
 * [listener] hears the shown chat's every change, with its controller (a
 * different one: the screen starts over). [historyListener] hears when a
 * held chat's row may have changed (one appears or goes, a mark changes).
 */
class ChatHub(
    private val host: ChatHost,
    private val post: (() -> Unit) -> Unit,
    private val home: String = "/root",
    private val cwd: String = "/root",
    private val artifacts: ArtifactLookup? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where each session's picks are kept for the next start ([ChatShared]); in memory only without it. */
    picksFile: java.io.File? = null,
) {
    private val shared = ChatShared(picksFile)
    private val chats = ArrayList<ChatController>()
    // Session id → notice slot, the most recently posted last.
    private val noticeSlots = LinkedHashMap<String, Int>()
    private var past: List<ClaudeSessionSummary> = emptyList()
    private var toldWorking = false
    private var toldRows: List<LiveChat> = emptyList()

    /** The chat on screen; null on the history. */
    var shown: ChatController? = null
        private set

    /** Every chat held: the shown one and those with a process. */
    val held: List<ChatController> get() = chats.toList()

    var listener: ((ChatController, ChatState) -> Unit)? = null
        set(value) {
            field = value
            shown?.let { value?.invoke(it, it.state) }
        }

    var historyListener: (() -> Unit)? = null

    /** A chat runs a process, so the app mustn't exit by itself. */
    val live: Boolean get() = chats.any { it.live }

    /** A chat's turn runs with no card waiting: the wakelock's auto follows it. */
    val working: Boolean get() = chats.any { it.working }

    /** Makes a fresh chat and shows it. */
    fun newChat(): ChatController = show(make(null))

    /** Shows the chat on [sessionId]: the one held, else a new one resuming it. */
    fun open(sessionId: String): ChatController = show(chats.firstOrNull { it.sessionId == sessionId } ?: make(sessionId))

    /** Back to the history: no chat shown. */
    fun leave() {
        val old = shown ?: return
        shown = null
        settle(old)
        rowsChanged()
    }

    /** The shown chat is on screen: clears its notice and checks whether it can run. */
    fun opened() {
        val chat = shown ?: return
        clearNotice(chat)
        chat.opened()
    }

    /** A tab has ended, maybe one holding a session. */
    fun tabChanged() {
        chats.toList().forEach { it.tabChanged() }
    }

    /** The history's rows, with the past sessions as last read ([history]). */
    fun rows(): List<ChatHistoryRow> = chatHistory(liveChats(), past)

    /** Reads the folder's past chats off the main thread, then gives [rows] on it. */
    fun history(done: (List<ChatHistoryRow>) -> Unit) {
        val dir = host.hostFile(claudeTranscriptDir(home, cwd))
        host.background {
            val list = runCatching { listClaudeSessions(dir) }.getOrDefault(emptyList())
            post {
                past = list
                done(rows())
            }
        }
    }

    /** "Use as default for new chats": the `chat-mode` setting; only [CHAT_MODES]. */
    fun setDefaultMode(value: String) {
        if (!chatModeOffered(value)) return
        val chat = shown
        if (chat != null) chat.setDefaultMode(value) else host.setChatDefaultMode(value)
    }

    /** The model's "Default for new chats": the `chat-model` setting. */
    fun setDefaultModel(value: String) {
        if (value.isBlank()) return
        val chat = shown
        if (chat != null) chat.setDefaultModel(value) else host.setChatDefaultModel(value)
    }

    /** The app is exiting: every chat stops. */
    fun shutdown() {
        chats.toList().forEach { it.shutdown() }
        noticeSlots.clear()
    }

    private fun make(resume: String?): ChatController {
        val slot = SlotHost()
        val chat = ChatController(slot, post, home, cwd, artifacts, now, resume, shared)
        slot.chat = chat
        chats += chat
        return chat
    }

    private fun show(chat: ChatController): ChatController {
        val old = shown
        shown = chat
        // Set again: a listener set is told at once, so the screen gets this chat.
        chat.listener = { state -> changed(chat, state) }
        if (old != null && old !== chat) settle(old)
        clearNotice(chat)
        rowsChanged()
        return chat
    }

    private fun changed(chat: ChatController, state: ChatState) {
        if (chat === shown) listener?.invoke(chat, state) else settle(chat)
        rowsChanged()
    }

    // A chat not shown: stopped once it's idle, dropped once nothing runs.
    private fun settle(chat: ChatController) {
        if (chat === shown) return
        when {
            chat.running && chat.busy -> Unit
            chat.running -> chat.stop()
            !chat.live -> {
                chats.remove(chat)
                chat.listener = null
            }
        }
    }

    private fun liveChats(): List<LiveChat> = chats.mapNotNull { chat ->
        chat.sessionId?.let { LiveChat(it, chat.title, chat.touchedMs, chat.rowState) }
    }

    private fun rowsChanged() {
        val rows = liveChats().map { it.copy(touchedMs = 0) }
        if (rows == toldRows) return
        toldRows = rows
        historyListener?.invoke()
    }

    private fun clearNotice(chat: ChatController) {
        val slot = chat.sessionId?.let { noticeSlots.remove(it) } ?: return
        host.clearNotice(slot)
    }

    private fun noticeSlot(id: String): Int {
        noticeSlots.remove(id)?.let {
            noticeSlots[id] = it
            return it
        }
        val used = noticeSlots.values.toSet()
        val slot = (0 until CHAT_NOTICE_SLOTS).firstOrNull { it !in used }
            ?: noticeSlots.entries.first().let { oldest ->
                noticeSlots.remove(oldest.key)
                oldest.value
            }
        noticeSlots[id] = slot
        return slot
    }

    // What one chat sees of the app: the hub's rules for notices and the app's going.
    private inner class SlotHost : ChatHost {
        lateinit var chat: ChatController

        override fun check(done: (ChatCheck) -> Unit) = host.check(done)

        override fun transport(launch: AgentLaunch): AgentTransport = host.transport(launch)

        override fun newSessionId(): String = host.newSessionId()

        override fun background(work: () -> Unit) = host.background(work)

        override fun hostFile(path: String) = host.hostFile(path)

        override fun openInTab(sessionId: String, cwd: String, model: String?, mode: String?): ChatTab =
            host.openInTab(sessionId, cwd, model, mode)

        override fun noticeRules(): ChatNoticeRules {
            val rules = host.noticeRules()
            return if (chat === shown) rules else rules.copy(onScreen = false)
        }

        override fun notify(notice: ChatNotice) {
            val id = chat.sessionId ?: return
            host.notify(notice.copy(sessionId = id, slot = noticeSlot(id)))
        }

        override fun chatDefaultMode(): String? = host.chatDefaultMode()

        override fun setChatDefaultMode(value: String) = host.setChatDefaultMode(value)

        override fun chatDefaultModel(): String? = host.chatDefaultModel()

        override fun setChatDefaultModel(value: String) = host.setChatDefaultModel(value)

        override fun workingChanged(working: Boolean) {
            val now = this@ChatHub.working
            if (now == toldWorking) return
            toldWorking = now
            host.workingChanged(now)
        }

        override fun ended() {
            settle(chat)
            rowsChanged()
            if (!live) host.ended()
        }
    }
}
