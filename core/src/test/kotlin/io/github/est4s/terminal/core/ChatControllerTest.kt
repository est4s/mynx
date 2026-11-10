package io.github.est4s.terminal.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatControllerTest {
    private inner class Host : ChatHost {
        var access = ChatAccess.READY
        var answering = true
        val checks = mutableListOf<(ChatAccess) -> Unit>()
        val launches = mutableListOf<AgentLaunch>()
        val transports = mutableListOf<FakeTransport>()
        var ids = 0
        var binary: String? = "/root/.local/bin/claude"
        var ends = 0

        override fun check(done: (ChatCheck) -> Unit) {
            val answer: (ChatAccess) -> Unit = { done(ChatCheck(it, binary)) }
            checks += answer
            if (answering) answer(access)
        }

        override fun ended() {
            ends++
        }

        override fun transport(launch: AgentLaunch): AgentTransport {
            launches += launch
            return FakeTransport().also { transports += it }
        }

        override fun newSessionId() = "00000000-0000-0000-0000-00000000000${++ids}"

        val background = ArrayDeque<() -> Unit>()
        override fun background(work: () -> Unit) {
            background.addLast(work)
        }

        override fun hostFile(path: String) = File(top, path)

        var rules = ChatNoticeRules(onScreen = true)
        val notices = mutableListOf<ChatNotice>()
        var clears = 0
        val working = mutableListOf<Boolean>()
        override fun noticeRules() = rules
        override fun notify(notice: ChatNotice) {
            notices += notice
        }
        override fun clearNotice() {
            clears++
        }
        override fun workingChanged(working: Boolean) {
            this.working += working
        }

        val tabs = mutableListOf<Pair<String, String>>()
        val tabPicks = mutableListOf<Pair<String?, String?>>()
        val tabHandles = mutableListOf<Tab>()
        override fun openInTab(sessionId: String, cwd: String, model: String?, mode: String?): ChatTab {
            tabs += sessionId to cwd
            tabPicks += model to mode
            return Tab().also { tabHandles += it }
        }
    }

    inner class Tab : ChatTab {
        override var open = true
        var closes = 0
        override fun close() {
            closes++
        }
    }

    private val top = kotlin.io.path.createTempDirectory("chat").toFile()

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    private fun work() {
        while (host.background.isNotEmpty() || posted.isNotEmpty()) {
            host.background.removeFirstOrNull()?.invoke()
            drain()
        }
    }

    private fun transcript(id: String, vararg prompts: String) {
        File(top, claudeTranscriptPath("/root", "/root", id).removePrefix("/")).apply {
            parentFile.mkdirs()
            writeText(prompts.mapIndexed { n, p -> """{"type":"user","message":{"role":"user","content":"$p"},"uuid":"$id-$n"}""" }.joinToString("\n") + "\n")
        }
    }

    private fun shown() = chat.state.conversation.items.map { (it as? ChatItem.User)?.text ?: it.javaClass.simpleName }

    private val host = Host()
    private val posted = ArrayDeque<() -> Unit>()
    private val states = mutableListOf<ChatState>()
    private var clock = 10_000_000L
    private val finder = ArtifactFinder(WebApps(File(top, "root"), top.path, File(top, "tools")))
    private val chat = ChatController(host, { posted.addLast(it) }, artifacts = finder, now = { clock })
        .also { it.listener = { s -> states += s } }

    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    private val process get() = host.transports.last()
    private fun line(text: String) {
        process.listener!!.onLine(text)
        drain()
    }
    private fun init(id: String = "11111111-1111-1111-1111-111111111111") =
        line("""{"type":"system","subtype":"init","session_id":"$id","model":"m","permissionMode":"default"}""")
    private fun done() = line("""{"type":"result","is_error":false,"subtype":"success","result":"ok"}""")
    private fun notSignedIn() = line("""{"type":"result","is_error":true,"subtype":"success","result":"Not logged in · Please run /login"}""")
    private fun exit(code: Int = 1, detail: String = "boom") {
        process.listener!!.onExit(code, detail)
        drain()
    }

    private fun argFlag(flag: String, launch: AgentLaunch = host.launches.last()): String? =
        launch.argv.indexOf(flag).takeIf { it >= 0 }?.let { launch.argv[it + 1] }

    private fun ready() {
        chat.opened()
    }

    @Test
    fun `a model and mode chosen before the process are its flags`() {
        ready()
        assertEquals(listOf("default", "opus", "sonnet", "haiku"), chat.state.models.map { it.value })
        assertEquals("default", chat.state.model)
        assertEquals("default", chat.state.mode)
        chat.setModel("sonnet")
        chat.setMode("plan")
        assertEquals("sonnet", chat.state.model)
        assertEquals(ChatStatusLine("Sonnet", "Plan", highlighted = true), chat.state.line)
        assertTrue(chat.send("hello"))
        assertEquals("sonnet", argFlag("--model"))
        assertEquals("plan", argFlag("--permission-mode"))
    }

    @Test
    fun `nothing chosen adds no flags`() {
        ready()
        chat.send("hello")
        assertEquals(null, argFlag("--model"))
        assertEquals(null, argFlag("--permission-mode"))
    }

    private fun answered(id: String, ok: Boolean = true) = line(
        if (ok) """{"type":"control_response","response":{"subtype":"success","request_id":"$id"}}"""
        else """{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"No"}}""",
    )

    private fun relaunched(): AgentLaunch {
        done()
        exit()
        chat.restart()
        return host.launches.last()
    }

    @Test
    fun `a choice while the process runs goes to it, and once taken to the next launch`() {
        ready()
        chat.send("hello")
        init()
        chat.setMode("acceptEdits")
        val request = (parseJson(process.written.last()) as Map<*, *>)["request"]
        assertEquals(mapOf("subtype" to "set_permission_mode", "mode" to "acceptEdits"), request)
        assertEquals("default", chat.state.mode, "not until Claude takes it")
        answered("chat-2")
        assertEquals("acceptEdits", chat.state.mode)
        chat.setModel("haiku")
        assertEquals(mapOf("subtype" to "set_model", "model" to "haiku"), (parseJson(process.written.last()) as Map<*, *>)["request"])
        assertEquals("m", chat.state.model, "what Claude reports, not the pick till it takes it")
        answered("chat-3")
        assertEquals("haiku", chat.state.model)
        val next = relaunched()
        assertEquals("acceptEdits", argFlag("--permission-mode", next))
        assertEquals("haiku", argFlag("--model", next))
    }

    @Test
    fun `a refused choice is neither shown nor launched`() {
        ready()
        chat.setMode("plan")
        chat.send("hello")
        line("""{"type":"system","subtype":"init","session_id":"$first","model":"m","permissionMode":"plan"}""")
        chat.setMode("acceptEdits")
        answered("chat-2", ok = false)
        chat.setModel("haiku")
        answered("chat-3", ok = false)
        assertEquals("plan", chat.state.mode)
        assertEquals("m", chat.state.model, "what Claude reports")
        val next = relaunched()
        assertEquals("plan", argFlag("--permission-mode", next), "the last one taken")
        assertEquals(null, argFlag("--model", next))
    }

    @Test
    fun `a mode Claude reports is shown, never launched`() {
        ready()
        chat.send("hello")
        line("""{"type":"control_response","response":{"subtype":"success","request_id":"chat-1","response":{"current_permission_mode":"dontAsk"}}}""")
        line("""{"type":"system","subtype":"init","session_id":"$first","model":"m","permissionMode":"bypassPermissions"}""")
        assertEquals("bypassPermissions", chat.state.mode)
        assertEquals("Bypass permissions", chat.state.line.mode)
        assertEquals(null, argFlag("--permission-mode", relaunched()))
    }

    @Test
    fun `a pick Claude has left is dropped`() {
        ready()
        chat.setMode("plan")
        chat.send("hello")
        line("""{"type":"system","subtype":"init","session_id":"$first","model":"claude-haiku-5-5","permissionMode":"plan"}""")
        line("""{"type":"system","subtype":"status","status":null,"permissionMode":"dontAsk"}""")
        assertEquals("dontAsk", chat.state.mode)
        assertEquals(ChatStatusLine("Haiku 5.5", "dontAsk", highlighted = true), chat.state.line)
        assertEquals(null, argFlag("--permission-mode", relaunched()), "neither the pick nor what Claude said")
    }

    @Test
    fun `an approved plan leaves plan mode`() {
        ready()
        chat.setMode("plan")
        chat.send("hello")
        line("""{"type":"system","subtype":"init","session_id":"$first","model":"m","permissionMode":"plan"}""")
        line("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","input":{"plan":"Do it"},"tool_use_id":"t1"}}""")
        assertTrue(chat.answerPlan("r1", true))
        assertEquals("default", chat.state.mode, "not known until the next init")
        assertEquals(null, argFlag("--permission-mode", relaunched()))
    }

    @Test
    fun `a refused plan stays in plan mode`() {
        ready()
        chat.setMode("plan")
        chat.send("hello")
        line("""{"type":"system","subtype":"init","session_id":"$first","model":"m","permissionMode":"plan"}""")
        line("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","input":{"plan":"Do it"},"tool_use_id":"t1"}}""")
        assertTrue(chat.answerPlan("r1", false))
        assertEquals("plan", chat.state.mode)
        assertEquals("plan", argFlag("--permission-mode", relaunched()))
    }

    @Test
    fun `the models the agent lists are offered, also in the next chat`() {
        ready()
        chat.send("hello")
        line("""{"type":"control_response","response":{"subtype":"success","request_id":"chat-1","response":{"models":[{"value":"default","displayName":"Default (recommended)"},{"value":"opus","displayName":"Opus 5.5"}]}}}""")
        assertEquals(listOf("default", "opus"), chat.state.models.map { it.value })
        chat.newChat()
        assertEquals(listOf("default", "opus"), chat.state.models.map { it.value })
    }

    @Test
    fun `bypassing permissions can't be chosen here`() {
        ready()
        chat.setMode("bypassPermissions")
        assertEquals("default", chat.state.mode)
        chat.send("hello")
        assertEquals(null, argFlag("--permission-mode"))
    }

    @Test
    fun `a new chat starts from the defaults again`() {
        ready()
        chat.setModel("opus")
        chat.setMode("plan")
        chat.newChat()
        assertEquals("default", chat.state.model)
        assertEquals("default", chat.state.mode)
        chat.send("hello")
        assertEquals(null, argFlag("--model"))
    }

    @Test
    fun `opening checks once and a prompt starts the process in root's home`() {
        ready()
        assertEquals(1, host.checks.size)
        assertEquals(ChatAccess.READY, chat.state.access)
        assertTrue(chat.send("hello"))
        assertEquals("/root", host.launches.single().cwd)
        assertEquals("00000000-0000-0000-0000-000000000001", argFlag("--session-id"))
        assertTrue(host.launches.single().argv.first().endsWith("/.local/bin/claude"))
        assertTrue(chat.send("more"))
        assertEquals(1, host.launches.size)
        drain()
        assertEquals(listOf("hello", "more"), chat.state.conversation.items.filterIsInstance<ChatItem.User>().map { it.text })
    }

    @Test
    fun `a blank prompt does nothing, not even start the process`() {
        ready()
        assertFalse(chat.send("   "))
        assertTrue(host.launches.isEmpty())
    }

    @Test
    fun `nothing is sent until the check has passed`() {
        host.answering = false
        chat.opened()
        assertEquals(ChatAccess.CHECKING, chat.state.access)
        assertFalse(chat.send("hello"))
        assertTrue(host.launches.isEmpty())
        chat.opened() // shown again meanwhile: one check is enough
        assertEquals(1, host.checks.size)
        host.checks.single()(ChatAccess.READY)
        assertTrue(chat.send("hello"))
    }

    @Test
    fun `not signed in or not installed blocks, and coming back checks again`() {
        for (blocked in listOf(ChatAccess.NOT_SIGNED_IN, ChatAccess.NOT_INSTALLED)) {
            host.access = blocked
            chat.opened()
            assertEquals(blocked, chat.state.access)
            assertFalse(chat.send("hello"))
        }
        assertEquals(2, host.checks.size)
        host.access = ChatAccess.READY
        chat.opened()
        assertEquals(ChatAccess.READY, chat.state.access)
        chat.opened()
        assertEquals(3, host.checks.size, "ready: no more checks")
    }

    @Test
    fun `events reach the screen on the main thread, in order`() {
        ready()
        chat.send("hello")
        assertFalse(states.last().conversation.running, "only after the post")
        drain()
        assertTrue(states.last().conversation.running)
        init()
        line("""{"type":"result","is_error":false,"subtype":"success","result":"ok"}""")
        assertFalse(states.last().conversation.running)
    }

    @Test
    fun `sign-in needed in a chat restarts it once signed in, with a new id when nothing was said`() {
        ready()
        chat.send("hello")
        drain()
        init()
        notSignedIn()
        assertEquals(ChatAccess.NOT_SIGNED_IN, chat.state.access)
        assertFalse(chat.send("again"))
        val old = process
        chat.opened()
        assertEquals(1, old.closed)
        assertEquals(ChatAccess.CHECKING, chat.state.access)
        assertFalse(chat.send("too early: the old process is still ending"))
        exit(0)
        assertEquals(ChatAccess.READY, chat.state.access)
        assertTrue(chat.send("again"))
        assertEquals(2, host.launches.size)
        assertEquals("00000000-0000-0000-0000-000000000002", argFlag("--session-id"))
        assertEquals(null, argFlag("--resume"))
    }

    @Test
    fun `an unexpected exit offers restart, which resumes the id the agent reported`() {
        ready()
        chat.send("hello")
        drain()
        init("22222222-2222-2222-2222-222222222222")
        done()
        exit(1, "bang")
        assertTrue(chat.state.canRestart)
        assertTrue(chat.state.conversation.processEnded)
        assertTrue(chat.state.conversation.items.any { it is ChatItem.Notice && "bang" in it.text })
        chat.restart()
        assertEquals("22222222-2222-2222-2222-222222222222", argFlag("--resume"))
        assertEquals(null, argFlag("--session-id"))
        assertFalse(chat.state.canRestart)
        assertFalse(chat.state.conversation.processEnded)
        assertTrue(chat.state.conversation.items.any { it is ChatItem.User }, "the rows stay")
    }

    @Test
    fun `a prompt after an exit restarts too`() {
        ready()
        chat.send("hello")
        drain()
        init()
        done()
        exit()
        assertTrue(chat.send("more"))
        assertEquals(2, host.launches.size)
        assertEquals("11111111-1111-1111-1111-111111111111", argFlag("--resume"))
    }

    @Test
    fun `restart does nothing while the process runs`() {
        ready()
        chat.send("hello")
        chat.restart()
        assertEquals(1, host.launches.size)
    }

    @Test
    fun `stopping and answering go to the session`() {
        ready()
        chat.send("hello")
        drain()
        init()
        assertTrue(chat.cancel())
        assertTrue(process.written.last().contains("interrupt"))
        val before = process.written.size
        assertFalse(chat.answerPermission("nope", "allow_once"))
        assertFalse(chat.answerQuestion("nope", emptyMap()))
        assertFalse(chat.answerPlan("nope", true))
        assertEquals(before, process.written.size)
    }

    @Test
    fun `answers do nothing with no process`() {
        ready()
        assertFalse(chat.cancel())
        assertFalse(chat.answerPermission("a", "b"))
    }

    @Test
    fun `New ends the chat, ignores what the old one says and starts fresh`() {
        ready()
        chat.send("hello")
        drain()
        init()
        done()
        val old = process
        chat.newChat()
        assertEquals(1, old.closed)
        assertTrue(chat.state.conversation.items.isEmpty())
        old.listener!!.onLine("""{"type":"system","subtype":"init","session_id":"x","model":"m"}""")
        old.listener!!.onExit(143, "")
        drain()
        assertFalse(chat.state.conversation.processEnded)
        assertTrue(chat.state.conversation.items.isEmpty())
        assertTrue(chat.send("fresh"))
        assertEquals(null, argFlag("--resume"))
        assertEquals("00000000-0000-0000-0000-000000000002", argFlag("--session-id"))
    }

    @Test
    fun `New while waiting to restart after sign-in doesn't stay stuck`() {
        ready()
        chat.send("hello")
        drain()
        init()
        notSignedIn()
        chat.opened()
        assertEquals(ChatAccess.CHECKING, chat.state.access)
        chat.newChat()
        assertEquals(ChatAccess.READY, chat.state.access)
    }

    @Test
    fun `shutting down stops the process`() {
        ready()
        chat.send("hello")
        val old = process
        chat.shutdown()
        assertEquals(1, old.closed)
    }

    @Test
    fun `a new listener is told how things stand`() {
        ready()
        var got: ChatState? = null
        chat.listener = { got = it }
        assertEquals(ChatAccess.READY, got?.access)
    }

    @Test
    fun `a live chat is one with a process, and its end is told once`() {
        ready()
        assertFalse(chat.live)
        chat.send("hello")
        assertTrue(chat.live)
        drain()
        exit()
        assertFalse(chat.live)
        assertEquals(1, host.ends)
    }

    @Test
    fun `shutting down isn't live and leaves nothing running`() {
        ready()
        chat.send("hello")
        drain()
        init()
        line("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}},"api_message_id":"m1","parent_tool_use_id":null}""")
        assertTrue(chat.state.conversation.thinking)
        chat.shutdown()
        assertFalse(chat.live)
        assertFalse(chat.state.conversation.running)
        assertFalse(chat.state.conversation.thinking)
        assertFalse(states.last().conversation.running, "the screen is told")
    }

    @Test
    fun `the path the check found is the one run`() {
        host.binary = "/usr/local/bin/claude"
        ready()
        chat.send("hello")
        assertEquals("/usr/local/bin/claude", host.launches.single().argv.first())
    }

    @Test
    fun `Stop before the first init leaves nothing to resume`() {
        ready()
        chat.send("hello")
        drain()
        assertTrue(chat.cancel())
        val interrupt = (parseJson(process.written.last()) as Map<*, *>)["request_id"]
        line("""{"type":"control_response","response":{"subtype":"success","request_id":"$interrupt"}}""")
        assertFalse(chat.state.conversation.running)
        exit(143, "")
        assertTrue(chat.state.canRestart)
        chat.restart()
        assertEquals(null, argFlag("--resume"))
        assertEquals("00000000-0000-0000-0000-000000000002", argFlag("--session-id"))
    }

    @Test
    fun `a resume that finds no conversation starts afresh next, from a real run`() {
        ready()
        chat.send("hello")
        drain()
        init()
        done()
        exit()
        chat.send("more")
        drain()
        assertEquals("11111111-1111-1111-1111-111111111111", argFlag("--resume"))
        chatFixture("claude-bad-session.txt").forEach { line(it) }
        exit(1, "")
        chat.restart()
        assertEquals(null, argFlag("--resume"))
        assertEquals("00000000-0000-0000-0000-000000000002", argFlag("--session-id"))
    }

    @Test
    fun `a Restart that finds no conversation starts afresh next too`() {
        ready()
        chat.send("hello")
        drain()
        init()
        done()
        exit()
        chat.restart()
        assertEquals("11111111-1111-1111-1111-111111111111", argFlag("--resume"))
        chatFixture("claude-bad-session.txt").forEach { line(it) }
        exit(1, "")
        chat.restart()
        assertEquals(null, argFlag("--resume"))
    }

    @Test
    fun `opening again while the old process is ending keeps the chat closed`() {
        ready()
        chat.send("hello")
        drain()
        init()
        notSignedIn()
        chat.opened()
        assertEquals(ChatAccess.CHECKING, chat.state.access)
        val checks = host.checks.size
        chat.opened()
        assertEquals(ChatAccess.CHECKING, chat.state.access, "not ready while it stops")
        assertEquals(checks, host.checks.size)
        assertFalse(chat.send("too early"))
        exit(0)
        assertEquals(ChatAccess.READY, chat.state.access)
        assertTrue(chat.send("now"))
    }

    private val past = "22222222-2222-2222-2222-222222222222"
    private val first = "11111111-1111-1111-1111-111111111111"

    private fun finishedTurn() {
        ready()
        chat.send("hello")
        drain()
        init()
        done()
    }

    @Test
    fun `a past chat shows its history and the next prompt resumes it`() {
        transcript(past, "old prompt")
        ready()
        chat.resume(past)
        work()
        assertEquals(listOf("old prompt"), shown())
        assertTrue(chat.send("more"))
        assertEquals(past, argFlag("--resume"))
        drain()
        assertEquals(listOf("old prompt", "more"), shown())
    }

    @Test
    fun `resuming stops the running chat and waits for it to exit before running again`() {
        transcript(past, "old prompt")
        finishedTurn()
        chat.resume(past)
        assertEquals(1, process.closed)
        assertTrue(chat.live, "the old process still runs")
        assertTrue(chat.state.stopping)
        work()
        assertEquals(listOf("old prompt"), shown(), "history at once")
        line("""{"type":"assistant","message":{"id":"m9","role":"assistant","content":[{"type":"text","text":"late"}]}}""")
        assertEquals(listOf("old prompt"), shown(), "the old chat's words stay out")
        assertFalse(chat.send("too early"))
        assertEquals(1, host.launches.size)
        exit(0)
        assertFalse(chat.state.stopping)
        assertEquals(1, host.ends, "nothing runs any more, so the app may go")
        assertTrue(chat.send("now"))
        assertEquals(past, argFlag("--resume"))
    }

    @Test
    fun `a prompt sent before the history is read stays after it`() {
        transcript(past, "old prompt")
        ready()
        chat.resume(past)
        assertTrue(chat.send("quick"))
        drain()
        work()
        assertEquals(listOf("old prompt", "quick"), shown())
    }

    @Test
    fun `resuming the chat on screen does nothing`() {
        finishedTurn()
        chat.resume(first)
        assertEquals(0, process.closed)
        assertEquals(listOf("hello"), shown())
    }

    @Test
    fun `a restart keeps what's on screen`() {
        finishedTurn()
        exit()
        val before = shown()
        chat.restart()
        drain()
        assertEquals(before, shown())
    }

    @Test
    fun `past chats are read off the main thread`() {
        transcript(past, "old prompt")
        var got: List<ClaudeSessionSummary>? = null
        chat.pastChats { got = it }
        assertEquals(null, got)
        work()
        assertEquals(listOf(past), got!!.map { it.sessionId })
    }

    @Test
    fun `the hand-off tab gets the chat's own picks`() {
        ready()
        chat.setModel("haiku")
        chat.send("hello")
        init()
        chat.setMode("plan")
        answered("chat-2")
        done()
        assertTrue(chat.handOff())
        exit(0)
        assertEquals(listOf<Pair<String?, String?>>("haiku" to "plan"), host.tabPicks)
    }

    @Test
    fun `a hand-off with nothing picked adds nothing`() {
        ready()
        chat.send("hello")
        line("""{"type":"system","subtype":"init","session_id":"$first","model":"m","permissionMode":"bypassPermissions"}""")
        done()
        assertTrue(chat.handOff())
        exit(0)
        assertEquals(listOf<Pair<String?, String?>>(null to null), host.tabPicks)
    }

    @Test
    fun `nothing to hand off before a turn has finished`() {
        ready()
        assertEquals(CHAT_HAND_OFF_NOTHING, chat.state.handOffBlock)
        assertFalse(chat.handOff())
        chat.send("hello")
        drain()
        init()
        assertEquals(CHAT_HAND_OFF_RUNNING, chat.state.handOffBlock)
        assertFalse(chat.handOff())
        assertTrue(host.tabs.isEmpty())
    }

    @Test
    fun `an unanswered card blocks the hand-off`() {
        finishedTurn()
        line("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"ls"},"tool_use_id":"t1"}}""")
        assertTrue(chat.state.handOffBlock != null)
        assertFalse(chat.handOff())
        assertTrue(host.tabs.isEmpty())
    }

    @Test
    fun `the hand-off opens the tab only once the chat's process has exited`() {
        finishedTurn()
        assertEquals(null, chat.state.handOffBlock)
        assertTrue(chat.handOff())
        assertEquals(1, process.closed)
        assertTrue(host.tabs.isEmpty(), "never two programs on one session")
        assertEquals(CHAT_HAND_OFF_STOPPING, chat.state.handOffBlock)
        exit(0)
        assertEquals(listOf(first to "/root"), host.tabs)
        assertEquals(listOf("hello"), shown(), "the chat stays as it was")
        assertFalse(chat.live)
        assertEquals(CHAT_HAND_OFF_HELD, chat.state.handOffBlock)
    }

    @Test
    fun `with no process the tab opens at once`() {
        finishedTurn()
        exit(0)
        assertTrue(chat.handOff())
        assertEquals(listOf(first to "/root"), host.tabs)
        assertFalse(chat.state.canRestart)
    }

    @Test
    fun `a prompt while the tab may hold the session asks instead of forking it`() {
        finishedTurn()
        exit(0)
        chat.handOff()
        assertFalse(chat.send("here again"))
        assertEquals(1, host.launches.size)
        assertEquals(ChatTabHold.ASKING, chat.state.tabHold)
        chat.keepInTab()
        assertEquals(ChatTabHold.NONE, chat.state.tabHold)
        assertFalse(chat.send("here again"))
        assertEquals(1, host.launches.size)
    }

    @Test
    fun `taking back closes the tab and waits for it, then shows what the tab added`() {
        finishedTurn()
        exit(0)
        chat.handOff()
        chat.send("here again")
        chat.takeBack()
        val tab = host.tabHandles.single()
        assertEquals(1, tab.closes)
        assertEquals(ChatTabHold.TAKING_BACK, chat.state.tabHold)
        assertFalse(chat.send("here again"))
        transcript(first, "hello", "typed in the tab")
        tab.open = false
        chat.tabChanged()
        assertEquals(ChatTabHold.NONE, chat.state.tabHold)
        work()
        assertEquals(listOf("hello", "typed in the tab"), shown())
        assertTrue(chat.send("here again"))
        assertEquals(first, argFlag("--resume"))
    }

    @Test
    fun `coming back after the tab's claude exited goes on without asking`() {
        finishedTurn()
        exit(0)
        chat.handOff()
        host.tabHandles.single().open = false
        chat.opened()
        assertEquals(null, chat.state.handOffBlock)
        assertTrue(chat.send("back"))
        assertEquals(first, argFlag("--resume"))
        assertEquals(ChatTabHold.NONE, chat.state.tabHold)
    }

    @Test
    fun `the tab's hold stays with its session across other chats`() {
        finishedTurn()
        exit(0)
        chat.handOff()
        chat.resume(past)
        assertTrue(chat.send("elsewhere"))
        drain()
        exit(0)
        chat.resume(first)
        assertFalse(chat.send("back"))
        assertEquals(ChatTabHold.ASKING, chat.state.tabHold)
    }

    @Test
    fun `busy while a turn runs or a card waits, so leaving it asks first`() {
        ready()
        assertFalse(chat.state.busy)
        chat.send("hello")
        drain()
        init()
        assertTrue(chat.state.busy, "a turn runs")
        done()
        assertFalse(chat.state.busy)
        line("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"ls"},"tool_use_id":"t1"}}""")
        assertTrue(chat.state.busy, "a card waits")
        exit(0)
        assertFalse(chat.state.busy, "with no process the card can't be answered")
    }

    @Test
    fun `New waits for the old process, so going back to its chat can't run it twice`() {
        finishedTurn()
        val old = process
        chat.newChat()
        assertEquals(1, old.closed)
        assertTrue(chat.live, "the old process still runs")
        assertTrue(chat.state.stopping)
        chat.resume(first)
        work()
        assertFalse(chat.send("back"))
        assertEquals(1, host.launches.size, "never two programs on one session")
        old.listener!!.onExit(143, "")
        drain()
        assertFalse(chat.state.stopping)
        assertTrue(chat.send("back"))
        assertEquals(first, argFlag("--resume"))
    }

    @Test
    fun `New waits for the old process before its chat can go to a tab`() {
        finishedTurn()
        val old = process
        chat.newChat()
        chat.resume(first)
        assertEquals(CHAT_HAND_OFF_STOPPING, chat.state.handOffBlock)
        assertFalse(chat.handOff())
        assertTrue(host.tabs.isEmpty())
        old.listener!!.onExit(143, "")
        drain()
        assertTrue(chat.handOff())
        assertEquals(listOf(first to "/root"), host.tabs)
    }

    @Test
    fun `a stopped process that leaves nothing running is told as an end`() {
        finishedTurn()
        chat.newChat()
        assertEquals(0, host.ends)
        exit(143)
        assertFalse(chat.live)
        assertEquals(1, host.ends)
    }

    private fun file(guest: String, text: String) = File(top, guest.removePrefix("/")).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun tool(id: String, name: String, input: String) {
        line("""{"type":"assistant","message":{"id":"m-$id","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]},"parent_tool_use_id":null}""")
        line("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"ok","is_error":false}]},"parent_tool_use_id":null}""")
    }

    private fun cards() = chat.state.conversation.items.filterIsInstance<ChatItem.Artifact>()

    @Test
    fun `an app the agent writes gets one card a turn, changed in place`() {
        ready()
        chat.send("make an app")
        drain()
        init()
        file("/root/apps/demo/index.html", "<title>Demo</title>")
        tool("t1", "Write", """{"file_path":"~/apps/demo/index.html","content":"x"}""")
        assertTrue(cards().isEmpty(), "found off the main thread")
        work()
        assertEquals(listOf("Demo"), cards().map { it.artifact.label })
        assertEquals("Artifact", shown().last())
        file("/root/apps/demo/index.html", "<title>Demo 2</title>")
        tool("t2", "Edit", """{"file_path":"/root/apps/demo/index.html","old_string":"a","new_string":"b"}""")
        file("/root/notes.txt", "x")
        tool("t3", "Write", """{"file_path":"/root/notes.txt","content":"x"}""")
        done()
        work()
        assertEquals(listOf("Demo 2"), cards().map { it.artifact.label })
        val first = cards().single().id
        chat.send("again")
        drain()
        tool("t4", "Write", """{"file_path":"/root/apps/demo/index.html","content":"x"}""")
        done()
        work()
        assertEquals(2, cards().size, "a new turn, a new card")
        assertEquals(first, cards().first().id)
        assertTrue(cards().last().id != first)
    }

    @Test
    fun `a command's path counts when it changed during the turn`() {
        ready()
        chat.send("build it")
        drain()
        init()
        val apk = file("/root/old.apk", "PK").apply { setLastModified(1_000_000L) }
        tool("t1", "Bash", """{"command":"unzip -l ~/old.apk"}""")
        done()
        work()
        assertTrue(cards().isEmpty(), "read, not written")
        chat.send("now build")
        drain()
        apk.setLastModified(clock + 5_000)
        tool("t2", "Bash", """{"command":"apksigner sign --out ~/old.apk x"}""")
        done()
        work()
        assertEquals(listOf(ArtifactKind.APK), cards().map { it.artifact.kind })
    }

    @Test
    fun `a past chat shows the cards of its turns`() {
        file("/root/apps/demo/index.html", "<title>Demo</title>")
        File(top, claudeTranscriptPath("/root", "/root", past).removePrefix("/")).apply {
            parentFile.mkdirs()
            writeText(
                """{"type":"user","message":{"role":"user","content":"make"},"uuid":"u1"}
                |{"type":"assistant","message":{"model":"m","content":[{"type":"tool_use","id":"w1","name":"Write","input":{"file_path":"/root/apps/demo/index.html"}}]},"uuid":"a1"}
                |{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"w1","content":"ok"}]},"uuid":"r1"}
                |""".trimMargin(),
            )
        }
        ready()
        chat.resume(past)
        work()
        assertEquals(listOf("make", "Tool", "Artifact"), shown())
    }

    private val away = ChatNoticeRules(onScreen = false, agentNotify = true, afterSeconds = 30, agentOn = true)
    private fun say(text: String) =
        line("""{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"$text"}]}}""")
    private fun askToRun() =
        line("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"ls"},"tool_use_id":"t1"}}""")

    @Test
    fun `a long turn's end, with Chat away, is a notice with the reply`() {
        host.rules = away
        ready()
        chat.send("hello")
        drain()
        init()
        clock += 31_000
        say("All done")
        assertTrue(host.notices.isEmpty())
        done()
        assertEquals(listOf(ChatNotice("Claude Code", "Your turn: All done")), host.notices)
    }

    @Test
    fun `a short turn's end isn't, nor anything while Chat is on screen`() {
        host.rules = away
        ready()
        chat.send("hello")
        drain()
        init()
        clock += 29_000
        done()
        assertTrue(host.notices.isEmpty())
        host.rules = away.copy(onScreen = true)
        chat.send("again")
        drain()
        clock += 60_000
        askToRun()
        done()
        assertTrue(host.notices.isEmpty())
    }

    @Test
    fun `a card waiting is a notice at once`() {
        host.rules = away
        ready()
        chat.send("hello")
        drain()
        init()
        askToRun()
        assertEquals(listOf(ChatNotice("Claude Code", "Wants to run Bash")), host.notices)
    }

    @Test
    fun `opening Chat clears its notice`() {
        ready()
        assertEquals(1, host.clears)
    }

    @Test
    fun `the host hears when a turn starts and stops working, not while a card waits`() {
        ready()
        chat.send("hello")
        assertEquals(emptyList(), host.working)
        drain()
        assertTrue(chat.working)
        assertEquals(listOf(true), host.working)
        init()
        say("thinking aloud")
        assertEquals(listOf(true), host.working, "told only on a change")
        askToRun()
        assertFalse(chat.working)
        assertEquals(listOf(true, false), host.working)
        done()
        assertEquals(false, host.working.last(), "the turn's end takes back the card, then ends")
        chat.send("more")
        drain()
        assertEquals(true, host.working.last())
        chat.shutdown()
        assertEquals(false, host.working.last())
    }

    private fun transcriptWithModel(id: String, model: String) {
        File(top, claudeTranscriptPath("/root", "/root", id).removePrefix("/")).apply {
            parentFile.mkdirs()
            writeText(
                """{"type":"user","message":{"role":"user","content":"old prompt"},"uuid":"$id-u"}""" + "\n" +
                    """{"type":"assistant","message":{"role":"assistant","model":"$model","content":[{"type":"text","text":"old answer"}]},"uuid":"$id-a"}""" + "\n",
            )
        }
    }

    @Test
    fun `a resumed chat shows and ticks the model its transcript ran, with no flag`() {
        transcriptWithModel(past, "claude-haiku-5-5")
        ready()
        chat.resume(past)
        assertEquals("", chat.state.line.model, "not known yet: not Default either")
        work()
        assertEquals("Haiku 5.5", chat.state.line.model)
        assertEquals("haiku", chat.state.model)
        assertTrue(chat.send("more"))
        assertEquals(null, argFlag("--model"))
    }

    @Test
    fun `with nothing picked, Model… ticks what the resumed process reports`() {
        transcript(past, "old prompt")
        ready()
        chat.resume(past)
        work()
        assertEquals("", chat.state.line.model)
        chat.send("more")
        line("""{"type":"system","subtype":"init","session_id":"$past","model":"claude-haiku-5-5","permissionMode":"default"}""")
        assertEquals("haiku", chat.state.model)
        assertEquals("Haiku 5.5", chat.state.line.model)
        val next = relaunched()
        assertEquals(null, argFlag("--model", next), "reported, never picked")
    }

    @Test
    fun `a new chat shows the default model again`() {
        transcriptWithModel(past, "claude-haiku-5-5")
        ready()
        chat.resume(past)
        work()
        chat.newChat()
        assertEquals("Default model", chat.state.line.model)
        assertEquals("default", chat.state.model)
    }
}
