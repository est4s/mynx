package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatNoticesTest {
    private val away = ChatNoticeRules(onScreen = false, agentNotify = true, afterSeconds = 30, agentOn = true)
    private val ended = AgentEvent.TurnEnded(StopReason.END_TURN)
    private val call = ToolCallInfo("t1", "Bash", "ls", ToolKind.entries.first(), ToolStatus.entries.first())

    private fun reply(vararg turns: Pair<String, String>) = Conversation(
        items = turns.flatMapIndexed { n, (prompt, answer) ->
            listOf(ChatItem.User("u$n", prompt), ChatItem.Agent("a$n", answer))
        },
    )

    @Test
    fun `a long turn's end says it's your turn, with the first line of this turn's reply`() {
        val conversation = reply("first" to "old reply", "second" to "\n## Done: all **tests** pass\n\nMore detail.")
        assertEquals(
            ChatNotice("Claude Code", "Your turn: Done: all tests pass"),
            chatNotice(ended, conversation, tookMs = 31_000, away),
        )
    }

    @Test
    fun `a turn with no reply text just says your turn`() {
        val conversation = Conversation(items = listOf(ChatItem.User("u", "hi"), ChatItem.Tool("t1", call)))
        assertEquals(ChatNotice("Claude Code", "Your turn"), chatNotice(ended, conversation, 40_000, away))
    }

    @Test
    fun `a long first line is cut`() {
        val text = chatNotice(ended, reply("p" to "x".repeat(500)), 40_000, away)!!.text
        assertEquals(MAX_CHAT_NOTICE_TEXT, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `a short turn, one with no start, or a cancelled one doesn't notify`() {
        val conversation = reply("p" to "ok")
        assertNull(chatNotice(ended, conversation, tookMs = 29_999, away))
        assertNull(chatNotice(ended, conversation, tookMs = null, away))
        assertNull(chatNotice(AgentEvent.TurnEnded(StopReason.CANCELLED), conversation, 60_000, away))
        assertEquals("Your turn: ok", chatNotice(ended, conversation, 0, away.copy(afterSeconds = 0))?.text)
    }

    @Test
    fun `a failed turn says so, with its message`() {
        val failed = AgentEvent.TurnEnded(StopReason.ERROR, "API error: overloaded\nretry later")
        assertEquals("The turn failed: API error: overloaded", chatNotice(failed, reply("p" to "ok"), 60_000, away)?.text)
    }

    @Test
    fun `cards notify at once, however short the turn`() {
        val conversation = reply("p" to "")
        assertEquals(
            ChatNotice("Claude Code", "Wants to run Bash"),
            chatNotice(AgentEvent.PermissionRequest("r1", call, emptyList()), conversation, 1, away),
        )
        val question = AgentEvent.QuestionRequest("r2", null, listOf(AgentQuestion("Which colour?\nPick one", null, false, emptyList())))
        assertEquals("Asks: Which colour?", chatNotice(question, conversation, null, away)?.text)
        assertEquals("Needs your input", chatNotice(AgentEvent.QuestionRequest("r3", null, emptyList()), conversation, null, away)?.text)
        assertEquals("Has a plan for you to approve", chatNotice(AgentEvent.PlanRequest("r4", null, "plan", null), conversation, null, away)?.text)
    }

    @Test
    fun `nothing while Chat is on screen, with agent-notify off, or with Claude's notifications off`() {
        val conversation = reply("p" to "ok")
        val permission = AgentEvent.PermissionRequest("r1", call, emptyList())
        for (rules in listOf(away.copy(onScreen = true), away.copy(agentNotify = false), away.copy(agentOn = false))) {
            assertNull(chatNotice(ended, conversation, 60_000, rules), rules.toString())
            assertNull(chatNotice(permission, conversation, 60_000, rules), rules.toString())
        }
    }

    @Test
    fun `other events never notify`() {
        assertFalse(mayNotify(AgentEvent.TurnStarted))
        assertFalse(mayNotify(AgentEvent.ProcessEnded(1, "", stopped = false)))
        assertTrue(mayNotify(ended))
        assertTrue(mayNotify(AgentEvent.PlanRequest("r", null, "p", null)))
    }

    @Test
    fun `Claude's notifications are on when mynx's hook is on each of its events`() {
        fun hooks(vararg events: String, command: String = "mynx hook claude") =
            """{"model":"x","hooks":{""" + events.joinToString(",") {
                """"$it":[{"hooks":[{"type":"command","command":"$command"}]}]"""
            } + "}}"
        assertTrue(claudeNotifyHooks(hooks("UserPromptSubmit", "Stop", "Notification")))
        assertTrue(claudeNotifyHooks(hooks("UserPromptSubmit", "Stop", "Notification", command = "/opt/mynx/bin/mynx hook claude")))
        assertFalse(claudeNotifyHooks(hooks("UserPromptSubmit", "Stop")), "one missing")
        assertFalse(claudeNotifyHooks(hooks("UserPromptSubmit", "Stop", "Notification", command = "mynx hook codex")))
        assertFalse(claudeNotifyHooks(null), "no settings file")
        assertFalse(claudeNotifyHooks("""{"model":"x"}"""))
        assertFalse(claudeNotifyHooks("{not json"))
        assertFalse(claudeNotifyHooks("""{"hooks":{"Stop":"x"}}"""))
    }
}
