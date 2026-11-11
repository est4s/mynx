package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSettingsTest {
    private val reported = listOf(
        AgentModel("default", "Default (recommended)", "Opus 5.5 · Best for everyday, complex tasks"),
        AgentModel("opus", "Opus 5.5", "For complex work and everyday tasks"),
    )

    @Test
    fun `the modes offered leave out bypassing permissions`() {
        assertEquals(listOf("auto", "default", "plan", "acceptEdits"), CHAT_MODES.map { it.value })
        assertFalse(chatModeOffered("bypassPermissions"))
        assertTrue(chatModeOffered("plan"))
    }

    @Test
    fun `before the agent says, Default and the aliases are offered`() {
        assertEquals(listOf("default", "opus", "sonnet", "haiku"), chatModelChoices(emptyList()).map { it.value })
        assertEquals(reported, chatModelChoices(reported))
    }

    @Test
    fun `the default for new chats calls the default Claude Code's own pick`() {
        val models = chatDefaultModelChoices(reported)
        val first = models.first()
        assertEquals("default", first.value)
        assertEquals("Claude Code decides", first.name)
        assertEquals("No model is set; Claude Code picks, and its pick can change with its updates.", first.description)
        assertEquals(reported.drop(1), models.drop(1))
        assertEquals(listOf("default", "opus", "sonnet", "haiku"), chatDefaultModelChoices(emptyList()).map { it.value })
        assertEquals("Claude Code decides", chatDefaultModelChoices(emptyList()).first().name)
    }

    @Test
    fun `the default for new chats offers Claude Code's pick even when the agent lists none`() {
        val noDefault = reported.filter { it.value != "default" }
        assertEquals(listOf("default") + noDefault.map { it.value }, chatDefaultModelChoices(noDefault).map { it.value })
    }

    @Test
    fun `nothing chosen or known is the default model, asking first`() {
        assertEquals(ChatStatusLine("Default model", "Asks first", highlighted = false), chatStatusLine(null, null, null, emptyList()))
        assertEquals("Default model · Asks first", chatStatusLine(null, null, "default", emptyList()).text)
        assertEquals(ChatStatusLine("Default model", "Auto", highlighted = true), chatStatusLine(null, null, "auto", emptyList()))
    }

    @Test
    fun `the model the agent runs is named from its id`() {
        assertEquals("Haiku 5.5", chatStatusLine(null, "claude-haiku-5-5", "default", reported).model)
        assertEquals("Opus 4.1", chatStatusLine(null, "claude-opus-4-1-20250805", null, reported).model)
        assertEquals("Sonnet 4", chatStatusLine(null, "claude-sonnet-4-20250514", null, reported).model)
        assertEquals("Sonnet 4.5 (1M)", chatStatusLine(null, "claude-sonnet-4-5[1m]", null, reported).model)
        assertEquals("something-else", chatStatusLine(null, "something-else", null, reported).model)
    }

    @Test
    fun `a chosen model wins, named as the agent lists it`() {
        assertEquals("Opus 5.5", chatStatusLine("opus", "claude-haiku-5-5", null, reported).model)
        assertEquals("Sonnet", chatStatusLine("sonnet", null, null, chatModelChoices(emptyList())).model)
        assertEquals("Sonnet", chatStatusLine("sonnet", null, null, reported).model)
    }

    @Test
    fun `choosing Default shows what the agent runs, else the default`() {
        assertEquals("Haiku 5.5", chatStatusLine("default", "claude-haiku-5-5", null, reported).model)
        assertEquals("Default model", chatStatusLine("default", "default", null, reported).model)
    }

    @Test
    fun `a mode other than the default stands out`() {
        assertEquals(ChatStatusLine("Sonnet", "Plan", highlighted = true), chatStatusLine("sonnet", null, "plan", emptyList()))
        assertEquals("Accept edits", chatStatusLine(null, null, "acceptEdits", emptyList()).mode)
        val bypass = chatStatusLine(null, null, "bypassPermissions", emptyList())
        assertEquals("Bypass permissions", bypass.mode)
        assertTrue(bypass.highlighted)
        assertEquals("dontAsk", chatStatusLine(null, null, "dontAsk", emptyList()).mode)
    }

    @Test
    fun `with nothing picked, Model… ticks the model the agent reports, by id or alias`() {
        val fallback = chatModelChoices(emptyList())
        assertEquals(ChatModelMenu(fallback, "haiku"), chatModelMenu(null, "claude-haiku-5-5", fallback))
        assertEquals("opus", chatModelMenu(null, "opus", reported).ticked)
        assertEquals("opus", chatModelMenu(null, "claude-opus-5-5-20261001", reported).ticked)
        assertEquals("default", chatModelMenu(null, null, reported).ticked)
        assertEquals("default", chatModelMenu(null, "default", reported).ticked)
    }

    @Test
    fun `a reported model not offered is an extra ticked entry`() {
        val menu = chatModelMenu(null, "claude-sonnet-4-5[1m]", reported)
        assertEquals(reported + AgentModel("claude-sonnet-4-5[1m]", "Sonnet 4.5 (1M)", null), menu.models)
        assertEquals("claude-sonnet-4-5[1m]", menu.ticked)
    }

    @Test
    fun `a pick wins over what the agent reports`() {
        assertEquals(ChatModelMenu(reported, "default"), chatModelMenu("default", "claude-opus-5-5", reported))
        assertEquals("opus", chatModelMenu("opus", "claude-haiku-5-5", reported).ticked)
    }

    @Test
    fun `a past chat whose model isn't known yet shows only the mode`() {
        val line = chatStatusLine(null, null, "plan", reported, pastChat = true)
        assertEquals(ChatStatusLine("", "Plan", highlighted = true), line)
        assertEquals("Plan", line.text)
        assertEquals("Haiku 5.5", chatStatusLine(null, "claude-haiku-5-5", null, reported, pastChat = true).model)
        assertEquals("Opus 5.5", chatStatusLine("opus", null, null, reported, pastChat = true).model)
    }
}
