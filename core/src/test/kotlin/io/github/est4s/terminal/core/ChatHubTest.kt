package io.github.est4s.terminal.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatHubTest {
    private inner class Host : ChatHost {
        val launches = mutableListOf<AgentLaunch>()
        val transports = mutableListOf<FakeTransport>()
        var checks = 0
        var ids = 0
        var ends = 0
        var rules = ChatNoticeRules(onScreen = true)
        val notices = mutableListOf<ChatNotice>()
        val cleared = mutableListOf<Int>()
        val working = mutableListOf<Boolean>()
        val tabs = mutableListOf<String>()
        val tabHandles = mutableListOf<Tab>()
        val background = ArrayDeque<() -> Unit>()

        override fun check(done: (ChatCheck) -> Unit) {
            checks++
            done(ChatCheck(ChatAccess.READY, "/root/.local/bin/claude"))
        }

        override fun ended() {
            ends++
        }

        override fun transport(launch: AgentLaunch): AgentTransport {
            launches += launch
            return FakeTransport().also { transports += it }
        }

        override fun newSessionId() = "00000000-0000-0000-0000-%012d".format(++ids)

        override fun background(work: () -> Unit) {
            background.addLast(work)
        }

        override fun hostFile(path: String) = File(top, path)

        override fun openInTab(sessionId: String, cwd: String, model: String?, mode: String?): ChatTab {
            tabs += sessionId
            return Tab().also { tabHandles += it }
        }

        override fun noticeRules() = rules

        override fun notify(notice: ChatNotice) {
            notices += notice
        }

        override fun clearNotice(slot: Int) {
            cleared += slot
        }

        override fun workingChanged(working: Boolean) {
            this.working += working
        }

        var defaultMode: String? = null
        val defaultsSet = mutableListOf<String>()
        override fun chatDefaultMode() = defaultMode

        override fun setChatDefaultMode(value: String) {
            defaultsSet += value
            defaultMode = value
        }

        var defaultModel: String? = null
        val defaultModelsSet = mutableListOf<String>()
        override fun chatDefaultModel() = defaultModel

        override fun setChatDefaultModel(value: String) {
            defaultModelsSet += value
            defaultModel = value
        }
    }

    inner class Tab : ChatTab {
        override var open = true
        override fun close() {}
    }

    private val top = kotlin.io.path.createTempDirectory("hub").toFile()

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    private val host = Host()
    private val posted = ArrayDeque<() -> Unit>()
    private var clock = 10_000_000L
    private val hub = ChatHub(host, { posted.addLast(it) }, now = { clock })
    private val told = mutableListOf<Pair<ChatController, ChatState>>()
    private var historyTold = 0

    init {
        hub.listener = { chat, state -> told += chat to state }
        hub.historyListener = { historyTold++ }
    }

    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    private fun work() {
        while (host.background.isNotEmpty() || posted.isNotEmpty()) {
            host.background.removeFirstOrNull()?.invoke()
            drain()
        }
    }

    private fun FakeTransport.line(text: String) {
        say(text)
        drain()
    }

    // Sends [prompt] in [chat], which starts a process; the process says its init: a turn runs.
    private fun start(chat: ChatController, prompt: String = "hello"): FakeTransport {
        hub.opened()
        assertTrue(chat.send(prompt))
        drain()
        val process = host.transports.last()
        val id = chat.sessionId!!
        process.line("""{"type":"system","subtype":"init","session_id":"$id","model":"m","permissionMode":"default"}""")
        return process
    }

    private fun FakeTransport.done() = line("""{"type":"result","is_error":false,"subtype":"success","result":"ok"}""")
    private fun FakeTransport.askToRun() =
        line("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"ls"},"tool_use_id":"t1"}}""")
    private fun FakeTransport.ended(code: Int = 143) {
        exit(code)
        drain()
    }

    private fun transcript(id: String, prompt: String, modified: Long) {
        File(top, claudeTranscriptPath("/root", "/root", id).removePrefix("/")).apply {
            parentFile.mkdirs()
            writeText("""{"type":"user","message":{"role":"user","content":"$prompt"},"uuid":"$id-0"}""" + "\n")
            setLastModified(modified)
        }
    }

    @Test
    fun `a new chat is shown and the screen hears it with its controller`() {
        val chat = hub.newChat()
        assertSame(chat, hub.shown)
        assertSame(chat, told.last().first)
        hub.opened()
        assertEquals(ChatAccess.READY, chat.state.access)
    }

    @Test
    fun `a busy chat left keeps working, beside another`() {
        val first = hub.newChat()
        val one = start(first)
        val second = hub.newChat()
        assertEquals(0, one.closed, "a turn runs: not stopped")
        assertEquals(listOf(first, second), hub.held)
        val two = start(second, "other")
        assertEquals(2, host.launches.size, "two processes, one each")
        assertTrue(first.sessionId != second.sessionId)
        assertTrue(hub.live)
        assertTrue(hub.working)
        two.done()
        assertTrue(hub.working, "the first still works")
        assertEquals(0, two.closed, "shown: kept")
    }

    @Test
    fun `a card waiting keeps a chat left running too`() {
        val first = hub.newChat()
        val one = start(first)
        one.askToRun()
        hub.newChat()
        assertEquals(0, one.closed)
        assertEquals(ChatRowState.WAITING, first.rowState)
    }

    @Test
    fun `an idle chat left is stopped, kept till it exits, then dropped`() {
        val first = hub.newChat()
        val one = start(first)
        one.done()
        val id = first.sessionId!!
        hub.leave()
        assertNull(hub.shown)
        assertEquals(1, one.closed)
        assertEquals(listOf(first), hub.held, "kept while its process exits")
        assertSame(first, hub.open(id), "the one held: never two programs on a session")
        assertFalse(first.send("too early"))
        hub.leave()
        one.ended()
        assertEquals(emptyList(), hub.held)
        assertEquals(1, host.ends, "nothing runs any more")
        val again = hub.open(id)
        assertNotSame(first, again)
        assertTrue(again.send("more"))
        assertEquals(id, host.launches.last().argv.let { it[it.indexOf("--resume") + 1] })
    }

    @Test
    fun `a chat that goes idle in the background is stopped`() {
        val first = hub.newChat()
        val one = start(first)
        hub.newChat()
        one.done()
        assertEquals(1, one.closed)
        one.ended()
        assertEquals(1, hub.held.size)
    }

    @Test
    fun `the app hears an end only when no chat runs`() {
        val one = start(hub.newChat())
        val second = hub.newChat()
        val two = start(second)
        two.done()
        hub.leave()
        two.ended()
        assertEquals(0, host.ends, "the first chat still runs")
        one.done()
        one.ended()
        assertEquals(1, host.ends)
        assertFalse(hub.live)
    }

    @Test
    fun `an empty new chat left is dropped`() {
        hub.newChat()
        hub.leave()
        assertEquals(emptyList(), hub.held)
        hub.newChat()
        hub.newChat()
        assertEquals(1, hub.held.size)
    }

    @Test
    fun `opening the chat shown keeps it`() {
        val chat = hub.newChat()
        start(chat).done()
        assertSame(chat, hub.open(chat.sessionId!!))
        assertEquals(0, host.transports.last().closed)
    }

    @Test
    fun `a session handed to a tab is held whichever controller opens it`() {
        val chat = hub.newChat()
        val one = start(chat)
        one.done()
        one.ended(0)
        val id = chat.sessionId!!
        assertTrue(chat.handOff())
        assertEquals(listOf(id), host.tabs)
        hub.leave()
        assertEquals(emptyList(), hub.held)
        val again = hub.open(id)
        hub.opened()
        assertFalse(again.send("back"))
        assertEquals(ChatTabHold.ASKING, again.state.tabHold)
        host.tabHandles.single().open = false
        hub.tabChanged()
        assertTrue(again.send("back"))
    }

    @Test
    fun `each chat's notices are its own, and showing it clears them`() {
        host.rules = ChatNoticeRules(onScreen = true, agentNotify = true, afterSeconds = 30, agentOn = true)
        val first = hub.newChat()
        val one = start(first)
        val second = hub.newChat()
        val two = start(second)
        one.askToRun()
        two.askToRun()
        assertEquals(1, host.notices.size, "the chat on show tells nothing")
        val notice = host.notices.single()
        assertEquals(first.sessionId, notice.sessionId)
        hub.leave()
        two.line("""{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Read","display_name":"Read","input":{},"tool_use_id":"t2"}}""")
        assertEquals(second.sessionId, host.notices.last().sessionId)
        assertTrue(notice.slot != host.notices.last().slot)
        assertSame(first, hub.open(first.sessionId!!))
        assertEquals(listOf(notice.slot), host.cleared)
    }

    @Test
    fun `notice slots stay in their range, the oldest reused`() {
        host.rules = ChatNoticeRules(onScreen = false)
        val chats = (0..CHAT_NOTICE_SLOTS).map { n ->
            hub.newChat().also { chat -> start(chat, "chat $n").askToRun() }
        }
        val slots = host.notices.map { it.slot }
        assertEquals((0 until CHAT_NOTICE_SLOTS).toList(), slots.take(CHAT_NOTICE_SLOTS))
        assertEquals(0, slots.last(), "the oldest's slot")
        assertEquals(chats.last().sessionId, host.notices.last().sessionId)
    }

    @Test
    fun `the host hears working for the whole`() {
        val one = start(hub.newChat())
        val two = start(hub.newChat())
        assertEquals(listOf(true), host.working)
        two.done()
        assertEquals(listOf(true), host.working)
        one.done()
        assertEquals(listOf(true, false), host.working)
    }

    @Test
    fun `shutting down stops every chat`() {
        val one = start(hub.newChat())
        val two = start(hub.newChat())
        hub.shutdown()
        assertEquals(1, one.closed)
        assertEquals(1, two.closed)
        assertFalse(hub.live)
    }

    @Test
    fun `the history merges live chats with past ones, read off the main thread`() {
        transcript("aaaaaaaa-0000-0000-0000-000000000001", "old one", 1_000_000L)
        transcript("aaaaaaaa-0000-0000-0000-000000000002", "older", 500_000L)
        val chat = hub.newChat()
        start(chat, "a new question\nwith more")
        hub.leave()
        var rows: List<ChatHistoryRow>? = null
        hub.history { rows = it }
        assertNull(rows)
        work()
        assertEquals(listOf("a new question", "old one", "older"), rows!!.map { it.title })
        assertEquals(listOf(ChatRowState.WORKING, null, null), rows!!.map { it.state })
        assertEquals(rows, hub.rows())
    }

    @Test
    fun `the history hears when a chat's mark changes`() {
        val chat = hub.newChat()
        val one = start(chat)
        val before = historyTold
        one.line("""{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"words"}]}}""")
        assertEquals(before, historyTold, "the same mark")
        one.askToRun()
        assertEquals(before + 1, historyTold)
    }

    @Test
    fun `a live chat's row takes its transcript's place`() {
        val past = listOf(
            ClaudeSessionSummary("a", "Claude's title", 100),
            ClaudeSessionSummary("b", "other", 200),
        )
        val live = listOf(
            LiveChat("a", "my prompt", 300, ChatRowState.WAITING),
            LiveChat("n", null, 50, ChatRowState.IDLE),
        )
        assertEquals(
            listOf(
                ChatHistoryRow("n", CHAT_UNTITLED, 50, ChatRowState.IDLE),
                ChatHistoryRow("a", "Claude's title", 300, ChatRowState.WAITING),
                ChatHistoryRow("b", "other", 200),
            ),
            chatHistory(live, past),
        )
    }

    private fun flag(name: String, launch: AgentLaunch = host.launches.last()): String? =
        launch.argv.indexOf(name).takeIf { it >= 0 }?.let { launch.argv[it + 1] }

    private fun FakeTransport.taken(id: String) =
        line("""{"type":"control_response","response":{"subtype":"success","request_id":"$id"}}""")

    // A chat whose mode was changed to Accept edits while it ran, left once idle, and dropped.
    private fun pickedAndLeft(): String {
        val first = hub.newChat()
        val one = start(first)
        first.setMode("acceptEdits")
        one.taken("chat-2")
        first.setModel("haiku")
        one.taken("chat-3")
        one.done()
        val id = first.sessionId!!
        hub.leave()
        one.ended()
        assertEquals(emptyList(), hub.held)
        return id
    }

    @Test
    fun `a chat left and opened again keeps its picks`() {
        val id = pickedAndLeft()
        val again = hub.open(id)
        assertEquals("acceptEdits", again.state.mode)
        assertEquals("haiku", again.state.model)
        assertTrue(again.send("more"))
        assertEquals("acceptEdits", flag("--permission-mode"))
        assertEquals("haiku", flag("--model"))
    }

    @Test
    fun `picks are kept in their file, for the next start of the app`() {
        val file = File(top, "state/chat-picks")
        val saving = ChatHub(host, { posted.addLast(it) }, now = { clock }, picksFile = file)
        val first = saving.newChat()
        saving.opened()
        first.setMode("plan")
        assertTrue(first.send("hello"))
        drain()
        val id = first.sessionId!!
        assertTrue(file.isFile)
        val later = ChatHub(host, { posted.addLast(it) }, now = { clock }, picksFile = file)
        assertEquals("plan", later.open(id).state.mode)
    }

    @Test
    fun `a broken picks file is read as none`() {
        val file = File(top, "chat-picks").apply { writeText("not\u0000 picks\n\n\tx\n") }
        val broken = ChatHub(host, { posted.addLast(it) }, now = { clock }, picksFile = file)
        assertEquals("default", broken.open("11111111-1111-1111-1111-111111111111").state.mode)
    }

    @Test
    fun `a new chat starts in the default mode the host gives`() {
        host.defaultMode = "auto"
        val chat = hub.newChat()
        assertEquals("auto", chat.state.mode)
        assertEquals("auto", chat.state.defaultMode)
        start(chat)
        assertEquals("auto", flag("--permission-mode"))
    }

    @Test
    fun `use as default writes the setting through the host`() {
        val chat = hub.newChat()
        hub.setDefaultMode("acceptEdits")
        assertEquals(listOf("acceptEdits"), host.defaultsSet)
        assertEquals("acceptEdits", chat.state.defaultMode)
        hub.setDefaultMode("bypassPermissions")
        assertEquals(listOf("acceptEdits"), host.defaultsSet, "only modes Chat offers")
        assertEquals("acceptEdits", hub.newChat().state.mode)
    }

    @Test
    fun `a new chat starts with the default model the host gives`() {
        host.defaultModel = "opus"
        val chat = hub.newChat()
        assertEquals("opus", chat.state.model)
        assertEquals("opus", chat.state.defaultModel)
        start(chat)
        assertEquals("opus", flag("--model"))
    }

    @Test
    fun `the default model named default passes no flag`() {
        host.defaultModel = "default"
        val chat = hub.newChat()
        assertEquals("default", chat.state.defaultModel)
        start(chat)
        assertEquals(null, flag("--model"))
    }

    @Test
    fun `a past chat keeps the model it ran, not the default`() {
        host.defaultModel = "opus"
        val chat = hub.open("11111111-1111-1111-1111-111111111111")
        assertEquals("default", chat.state.model)
    }

    @Test
    fun `the default model is written through the host`() {
        val chat = hub.newChat()
        hub.setDefaultModel("sonnet")
        assertEquals(listOf("sonnet"), host.defaultModelsSet)
        assertEquals("sonnet", chat.state.defaultModel)
        assertEquals("sonnet", hub.newChat().state.model)
        hub.setDefaultModel(" ")
        assertEquals(listOf("sonnet"), host.defaultModelsSet, "not a blank name")
    }
}
