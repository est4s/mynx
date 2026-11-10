package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTest {
    private fun fold(vararg events: AgentEvent, from: Conversation = Conversation()) =
        events.fold(from) { state, event -> state.reduce(event) }

    private fun call(id: String = "t1", status: ToolStatus = ToolStatus.PENDING, title: String = "ls", content: String = "") =
        ToolCallInfo(id, "Bash", title, ToolKind.EXECUTE, status, content)

    private val allow = PermissionOption("allow_once", "Allow", PermissionOptionKind.ALLOW_ONCE)

    @Test
    fun `the user's message is a row`() {
        assertEquals(listOf<ChatItem>(ChatItem.User("user-1", "hi")), fold(AgentEvent.UserMessage("user-1", "hi")).items)
    }

    @Test
    fun `chunks build one agent row`() {
        val state = fold(AgentEvent.AgentMessageChunk("m#0", "Hel"), AgentEvent.AgentMessageChunk("m#0", "lo"))
        assertEquals(listOf<ChatItem>(ChatItem.Agent("m#0", "Hello")), state.items)
    }

    @Test
    fun `replace takes over the text`() {
        val state = fold(AgentEvent.AgentMessageChunk("m#0", "Hullo"), AgentEvent.AgentMessageChunk("m#0", "Hello", replace = true))
        assertEquals("Hello", (state.items.single() as ChatItem.Agent).text)
    }

    @Test
    fun `a new block is a new row after what came between`() {
        val state = fold(
            AgentEvent.AgentMessageChunk("m#0", "first"),
            AgentEvent.ToolCall(call()),
            AgentEvent.AgentMessageChunk("m#1", "second"),
        )
        assertEquals(listOf("m#0", "t1", "m#1"), state.items.map { it.id })
    }

    @Test
    fun `a tool's row updates in place`() {
        val state = fold(
            AgentEvent.ToolCall(call(title = "Bash")),
            AgentEvent.AgentMessageChunk("m#0", "x"),
            AgentEvent.ToolCallUpdate(call(status = ToolStatus.COMPLETED, content = "done")),
        )
        assertEquals(listOf("t1", "m#0"), state.items.map { it.id })
        val tool = state.items[0] as ChatItem.Tool
        assertEquals(ToolStatus.COMPLETED, tool.call.status)
        assertEquals("done", tool.call.content)
    }

    @Test
    fun `an update for an unseen call makes its row`() {
        assertEquals("t1", fold(AgentEvent.ToolCallUpdate(call())).items.single().id)
    }

    @Test
    fun `a permission card goes from pending to answered`() {
        val asked = fold(AgentEvent.PermissionRequest("r1", call(), listOf(allow)))
        val card = asked.items.single() as ChatItem.Permission
        assertEquals("r1", card.requestId)
        assertEquals(listOf(allow), card.options)
        assertNull(card.outcome)
        val answered = fold(AgentEvent.RequestResolved("r1", RequestOutcome.Selected(PermissionOptionKind.ALLOW_ONCE)), from = asked)
        assertEquals(RequestOutcome.Selected(PermissionOptionKind.ALLOW_ONCE), (answered.items.single() as ChatItem.Permission).outcome)
        assertEquals(card.id, answered.items.single().id)
    }

    @Test
    fun `a request's id can't collide with a tool's`() {
        val state = fold(AgentEvent.ToolCall(call(id = "x")), AgentEvent.PermissionRequest("x", call(id = "x"), listOf(allow)))
        assertEquals(2, state.items.map { it.id }.toSet().size)
    }

    @Test
    fun `a question card and a plan card`() {
        val q = AgentQuestion("Which?", "H", false, listOf(QuestionOption("A", null)))
        val state = fold(
            AgentEvent.QuestionRequest("q1", "t1", listOf(q)),
            AgentEvent.PlanRequest("p1", "t2", "Do it", "/plan.md"),
            AgentEvent.RequestResolved("q1", RequestOutcome.Answered(mapOf("Which?" to "A"))),
            AgentEvent.RequestResolved("p1", RequestOutcome.Plan(true)),
        )
        val question = state.items[0] as ChatItem.Question
        assertEquals(listOf(q), question.questions)
        assertEquals(RequestOutcome.Answered(mapOf("Which?" to "A")), question.outcome)
        val plan = state.items[1] as ChatItem.Plan
        assertEquals("Do it", plan.plan)
        assertEquals("/plan.md", plan.planFilePath)
        assertEquals(RequestOutcome.Plan(true), plan.outcome)
    }

    @Test
    fun `resolving what's unknown changes nothing`() {
        val state = Conversation()
        assertEquals(state, state.reduce(AgentEvent.RequestResolved("r9", RequestOutcome.Withdrawn)))
    }

    @Test
    fun `the running flag follows the turn`() {
        val state = fold(AgentEvent.TurnStarted)
        assertTrue(state.running)
        assertFalse(fold(AgentEvent.TurnEnded(StopReason.END_TURN), from = state).running)
    }

    @Test
    fun `thinking is a flag, off when the turn ends`() {
        val state = fold(AgentEvent.TurnStarted, AgentEvent.ThoughtIndicator(true))
        assertTrue(state.thinking)
        assertEquals(emptyList(), state.items)
        assertFalse(fold(AgentEvent.TurnEnded(StopReason.END_TURN), from = state).thinking)
    }

    @Test
    fun `session details are remembered`() {
        val state = fold(AgentEvent.SessionStarted("s1", "m1", "plan"), AgentEvent.AvailableModels(listOf(AgentModel("v", "n", null))))
        assertEquals("s1", state.sessionId)
        assertEquals("m1", state.model)
        assertEquals("plan", state.mode)
        assertEquals(listOf(AgentModel("v", "n", null)), state.models)
    }

    @Test
    fun `a changed mode or model replaces the one the session started with`() {
        val state = fold(AgentEvent.SessionStarted("s1", "m1", "plan"), AgentEvent.ModeChanged("default"), AgentEvent.ModelChanged("sonnet"))
        assertEquals("default", state.mode)
        assertEquals("sonnet", state.model)
        assertEquals("s1", state.sessionId)
    }

    private fun notice(state: Conversation) = state.items.last() as ChatItem.Notice

    @Test
    fun `a turn's error is a notice, a clean end or a cancel isn't`() {
        val errored = fold(AgentEvent.TurnEnded(StopReason.ERROR, "Overloaded"))
        assertEquals(NoticeKind.ERROR, notice(errored).kind)
        assertEquals("Overloaded", notice(errored).text)
        assertEquals(emptyList(), fold(AgentEvent.TurnEnded(StopReason.END_TURN)).items)
        assertEquals(emptyList(), fold(AgentEvent.TurnEnded(StopReason.CANCELLED)).items)
        assertEquals(emptyList(), fold(AgentEvent.TurnEnded(StopReason.ERROR, null)).items)
    }

    @Test
    fun `refusal and cut-off get notices`() {
        assertNotNull(fold(AgentEvent.TurnEnded(StopReason.REFUSAL)).items.single() as? ChatItem.Notice)
        assertNotNull(fold(AgentEvent.TurnEnded(StopReason.MAX_TOKENS)).items.single() as? ChatItem.Notice)
    }

    @Test
    fun `not signed in is its own notice`() {
        val state = fold(AgentEvent.NotSignedIn("Not logged in"))
        assertEquals(NoticeKind.SIGN_IN, notice(state).kind)
        assertEquals("Not logged in", notice(state).text)
    }

    @Test
    fun `notices have ids of their own, stable as more arrive`() {
        val state = fold(AgentEvent.Notice("one"), AgentEvent.Notice("two"))
        assertEquals(2, state.items.map { it.id }.toSet().size)
        assertEquals(state.items[0].id, fold(AgentEvent.Notice("three"), from = state).items[0].id)
        assertEquals(NoticeKind.INFO, notice(state).kind)
    }

    @Test
    fun `an unexpected exit is a notice with why, a stop isn't`() {
        val died = fold(AgentEvent.TurnStarted, AgentEvent.ProcessEnded(1, "segfault", stopped = false))
        assertTrue(died.processEnded)
        assertFalse(died.running)
        assertEquals(NoticeKind.ERROR, notice(died).kind)
        assertTrue(notice(died).text.contains("segfault"))
        assertTrue(notice(died).text.contains("1"))
        val stopped = fold(AgentEvent.ProcessEnded(143, "", stopped = true))
        assertTrue(stopped.processEnded)
        assertEquals(emptyList(), stopped.items)
    }

    @Test
    fun `a real run folds into rows with unique ids`() {
        val events = mutableListOf<AgentEvent>()
        val transport = FakeTransport()
        val session = ClaudeSession(transport) { events.add(it) }
        session.start()
        session.sendPrompt("go")
        chatFixture("claude-subagent.txt").forEach(transport::say)
        transport.exit(0)
        val state = events.fold(Conversation()) { s, e -> s.reduce(e) }
        assertEquals(state.items.size, state.items.map { it.id }.toSet().size)
        assertFalse(state.running)
        assertTrue(state.processEnded)
        assertTrue(state.items.first() is ChatItem.User)
        assertTrue(state.items.any { it is ChatItem.Agent && it.text.startsWith("File written successfully") })
        assertTrue(state.items.any { it is ChatItem.Tool && it.call.name == "Agent" })
        assertTrue(state.items.filterIsInstance<ChatItem.Tool>().all { it.call.status != ToolStatus.PENDING }, "no card left spinning")
    }

    @Test
    fun `a permission row keeps what's being approved`() {
        val state = fold(AgentEvent.PermissionRequest("r1", call(), listOf(allow), review = "rm -rf build"))
        assertEquals("rm -rf build", (state.items.single() as ChatItem.Permission).review)
    }
}
