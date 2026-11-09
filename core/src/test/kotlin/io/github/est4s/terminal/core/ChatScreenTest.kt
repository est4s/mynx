package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatScreenTest {
    private val user = ChatItem.User("u1", "hello")
    private val agent = ChatItem.Agent("a1", "hi")
    private val tool = ChatItem.Tool("t1", ToolCallInfo("t1", "Bash", "ls", ToolKind.EXECUTE, ToolStatus.IN_PROGRESS))

    @Test
    fun `an updated row is the only one touched`() {
        val updated = ChatItem.Agent("a1", "hi there")
        assertEquals(ChatRowChanges(false, listOf(1)), chatRowChanges(listOf(user, agent, tool), listOf(user, updated, tool)))
    }

    @Test
    fun `new rows at the end are changes too`() {
        assertEquals(ChatRowChanges(false, listOf(2)), chatRowChanges(listOf(user, agent), listOf(user, agent, tool)))
        assertEquals(ChatRowChanges(false, listOf(0, 1)), chatRowChanges(emptyList(), listOf(user, agent)))
    }

    @Test
    fun `no change means nothing to do`() {
        assertEquals(ChatRowChanges(false, emptyList()), chatRowChanges(listOf(user, agent), listOf(user, agent)))
    }

    @Test
    fun `a different list rebuilds`() {
        assertTrue(chatRowChanges(listOf(user, agent), listOf(agent)).rebuild)
        assertTrue(chatRowChanges(listOf(user, agent), listOf(ChatItem.User("u9", "x"), agent)).rebuild)
    }

    @Test
    fun `outcomes read as what was chosen`() {
        assertEquals("Allowed", chatOutcomeText(RequestOutcome.Selected(PermissionOptionKind.ALLOW_ONCE)))
        assertEquals("Always allowed", chatOutcomeText(RequestOutcome.Selected(PermissionOptionKind.ALLOW_ALWAYS)))
        assertEquals("Denied", chatOutcomeText(RequestOutcome.Selected(PermissionOptionKind.REJECT_ONCE)))
        assertEquals("Denied", chatOutcomeText(RequestOutcome.Selected(PermissionOptionKind.REJECT_ALWAYS)))
        assertEquals("Answered: red, blue", chatOutcomeText(RequestOutcome.Answered(mapOf("Colour?" to "red, blue"))))
        assertEquals("Declined", chatOutcomeText(RequestOutcome.Declined))
        assertEquals("Approved", chatOutcomeText(RequestOutcome.Plan(true)))
        assertEquals("Kept planning", chatOutcomeText(RequestOutcome.Plan(false)))
        assertEquals("No longer needed", chatOutcomeText(RequestOutcome.Withdrawn))
    }

    @Test
    fun `a tool's line says its kind and status`() {
        assertEquals("command · running", chatToolStatus(ToolCallInfo("t", "Bash", "ls", ToolKind.EXECUTE, ToolStatus.IN_PROGRESS)))
        assertEquals("read · done", chatToolStatus(ToolCallInfo("t", "Read", "a", ToolKind.READ, ToolStatus.COMPLETED)))
        assertEquals("MyTool · failed", chatToolStatus(ToolCallInfo("t", "MyTool", "a", ToolKind.OTHER, ToolStatus.FAILED)))
        assertEquals("edit · waiting", chatToolStatus(ToolCallInfo("t", "Edit", "a", ToolKind.EDIT, ToolStatus.PENDING)))
    }

    @Test
    fun `a tool's body is its detail and output, capped`() {
        val call = ToolCallInfo("t", "Bash", "ls", ToolKind.EXECUTE, ToolStatus.COMPLETED, content = "output", detail = "List files")
        assertEquals("List files\n\noutput", chatToolBody(call))
        assertEquals("output", chatToolBody(call.copy(detail = null)))
        val long = chatToolBody(call.copy(detail = null, content = "x".repeat(50)), max = 10)
        assertEquals("x".repeat(10) + "\n… 40 more characters", long)
        assertEquals("", chatToolBody(call.copy(detail = null, content = "")))
    }

    private fun question(text: String, multi: Boolean = false) =
        AgentQuestion(text, null, multi, listOf(QuestionOption("red", null), QuestionOption("blue", null), QuestionOption("green", null)))

    @Test
    fun `a single choice answers at once and picks one`() {
        val picks = QuestionPicks(listOf(question("Colour?")))
        assertTrue(picks.answersOnTap)
        assertFalse(picks.complete)
        picks.toggle(0, 0)
        picks.toggle(0, 1)
        assertFalse(picks.isPicked(0, 0))
        assertTrue(picks.isPicked(0, 1))
        assertTrue(picks.complete)
        assertEquals(mapOf("Colour?" to "blue"), picks.answers())
    }

    @Test
    fun `a multi-select toggles and joins its labels in option order`() {
        val picks = QuestionPicks(listOf(question("Colour?", multi = true)))
        assertFalse(picks.answersOnTap)
        picks.toggle(0, 2)
        picks.toggle(0, 0)
        picks.toggle(0, 1)
        picks.toggle(0, 1)
        assertEquals(mapOf("Colour?" to "red, green"), picks.answers())
    }

    @Test
    fun `several questions need a pick each before they can be sent`() {
        val picks = QuestionPicks(listOf(question("One?"), question("Two?")))
        assertFalse(picks.answersOnTap)
        picks.toggle(0, 0)
        assertFalse(picks.complete)
        picks.toggle(1, 2)
        assertTrue(picks.complete)
        assertEquals(mapOf("One?" to "red", "Two?" to "green"), picks.answers())
    }
}
