package io.github.est4s.terminal.core

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
    }

    private val host = Host()
    private val posted = ArrayDeque<() -> Unit>()
    private val states = mutableListOf<ChatState>()
    private val chat = ChatController(host, { posted.addLast(it) }).also { it.listener = { s -> states += s } }

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
}
