package io.github.est4s.terminal.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeHistoryTest {
    private val fixture = object {}.javaClass.getResource("/chat/transcript.jsonl")!!.readText()
    private val top = kotlin.io.path.createTempDirectory("history").toFile()

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    private fun user(n: Int, text: String) =
        """{"type":"user","message":{"role":"user","content":${jsonText(text)}},"uuid":"u$n"}"""

    private fun agent(n: Int, text: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":${jsonText(text)}}]},"uuid":"a$n"}"""

    private fun jsonText(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // A prompt taken during a turn (Claude 2.1.321): an attachment, not a user line; `rendered` cut short.
    private val queuedLine =
        """{"parentUuid":"4eb21736-a416-4876-94e3-127252cad610","isSidechain":false,"attachment":{"type":"queued_command","prompt":"Also say the word BANANA.","source_uuid":"472074a1-2603-4f4e-a9de-1524c68e05ec","delivery_id":"e1a42079-284e-46e1-a2f9-33cb7ce63d8f","commandMode":"prompt","timestamp":"2026-11-10T08:38:48.322Z"},"type":"attachment","uuid":"1c0be48a-c886-4291-8095-a1679464290c","timestamp":"2026-11-10T08:38:48.322Z","rendered":[{"content":"<system-reminder>\nThe user sent a new message while you were working:\nAlso say the word BANANA.\n</system-reminder>"}],"renderedRole":"system","userType":"external","entrypoint":"sdk-cli","sessionId":"778cae7f-ee53-4a3b-96c2-f5059025088a","version":"2.1.321"}"""

    @Test
    fun `a prompt queued during a turn reads back as the user's, where Claude took it`() {
        val items = readClaudeHistory(sequenceOf(user(1, "Run sleep"), queuedLine, agent(2, "DONE")))
        assertEquals(
            listOf<ChatItem>(
                ChatItem.User("history:u1", "Run sleep"),
                ChatItem.User("history:1c0be48a-c886-4291-8095-a1679464290c", "Also say the word BANANA."),
                ChatItem.Agent("history:a2:0", "DONE"),
            ),
            items,
        )
    }

    @Test
    fun `queued commands other than prompts are left out`() {
        val other = queuedLine.replace("\"commandMode\":\"prompt\"", "\"commandMode\":\"task-notification\"")
        assertEquals(emptyList(), readClaudeHistory(sequenceOf(other)))
    }

    @Test
    fun `a transcript becomes the screen's rows`() {
        val items = readClaudeHistory(fixture.lineSequence())
        assertEquals(
            listOf(
                "user:Fix the\n**build**  please",
                "agent:Looking **now**.",
                "tool:ls",
                "tool:/root/nope",
                "user:Second prompt",
                "agent:# The plan",
                "agent:Part one.",
                "agent:Part two.",
                "tool:x",
            ),
            items.map {
                when (it) {
                    is ChatItem.User -> "user:${it.text}"
                    is ChatItem.Agent -> "agent:${it.text}"
                    is ChatItem.Tool -> "tool:${it.call.title}"
                    else -> "other:$it"
                }
            },
        )
    }

    @Test
    fun `tools end completed or failed, with their output`() {
        val tools = readClaudeHistory(fixture.lineSequence()).filterIsInstance<ChatItem.Tool>().map { it.call }
        assertEquals(ToolStatus.COMPLETED, tools[0].status)
        assertEquals("file.txt", tools[0].content)
        assertEquals("List files", tools[0].detail)
        assertEquals(ToolKind.EXECUTE, tools[0].kind)
        assertEquals(ToolStatus.FAILED, tools[1].status)
        assertEquals("no such file", tools[1].content)
    }

    @Test
    fun `a tool with no result never shows as running`() {
        val tool = readClaudeHistory(fixture.lineSequence()).filterIsInstance<ChatItem.Tool>().last().call
        assertEquals("x", tool.title)
        assertEquals(ToolStatus.FAILED, tool.status)
    }

    @Test
    fun `every row has its own id`() {
        val ids = readClaudeHistory(fixture.lineSequence()).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `only the last rows are kept, after one notice`() {
        val lines = (1..10).flatMap { listOf(user(it, "p$it"), agent(it, "r$it")) }
        val items = readClaudeHistory(lines.asSequence(), max = 4)
        assertEquals(5, items.size)
        assertEquals(ChatItem.Notice("history-earlier", NoticeKind.INFO, CHAT_HISTORY_EARLIER), items[0])
        assertEquals(listOf("p9", "r9", "p10", "r10"), items.drop(1).map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Agent).text })
    }

    @Test
    fun `exactly the cap has no notice`() {
        val lines = (1..2).flatMap { listOf(user(it, "p$it"), agent(it, "r$it")) }
        assertTrue(readClaudeHistory(lines.asSequence(), max = 4).none { it is ChatItem.Notice })
    }

    @Test
    fun `a result for a row already cut off is ignored`() {
        val tool = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]},"uuid":"a0"}"""
        val result = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"late"}]},"uuid":"u0"}"""
        val items = readClaudeHistory(sequenceOf(tool, user(1, "a"), user(2, "b"), result), max = 2)
        assertEquals(listOf("a", "b"), items.drop(1).map { (it as ChatItem.User).text })
    }

    @Test
    fun `long tool output is cut`() {
        val tool = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]},"uuid":"a0"}"""
        val result = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"${"x".repeat(100_000)}"}]},"uuid":"u0"}"""
        val call = (readClaudeHistory(sequenceOf(tool, result)).single() as ChatItem.Tool).call
        assertTrue(call.content.length < 20_000)
    }

    @Test
    fun `a file is read, a truncated last line and all`() {
        val file = File(top, "t.jsonl").apply { writeText(fixture) }
        assertEquals(readClaudeHistory(fixture.lineSequence()), readClaudeHistory(file))
    }

    @Test
    fun `the title is the first real prompt, on one line`() {
        val file = File(top, "t.jsonl").apply { writeText(fixture) }
        assertEquals("Fix the **build** please", claudeSessionTitle(file))
    }

    @Test
    fun `a session with no prompt has no title`() {
        val file = File(top, "t.jsonl").apply {
            writeText("""{"type":"mode","mode":"normal"}""" + "\n" + """{"type":"user","message":{"role":"user","content":"<command-name>/x</command-name>"},"uuid":"u"}""" + "\n")
        }
        assertNull(claudeSessionTitle(file))
    }

    @Test
    fun `only the head of a file is read for its title`() {
        val file = File(top, "t.jsonl").apply {
            writeText("""{"type":"x","pad":"${"p".repeat(CLAUDE_TITLE_HEAD_BYTES)}"}""" + "\n" + user(1, "too far") + "\n")
        }
        assertNull(claudeSessionTitle(file))
    }

    @Test
    fun `a first prompt longer than the head still gives a title`() {
        val file = File(top, "t.jsonl").apply {
            writeText(user(1, "A \"long\"\nprompt " + "word ".repeat(CLAUDE_TITLE_HEAD_BYTES)) + "\n")
        }
        val title = claudeSessionTitle(file)!!
        assertTrue(title.startsWith("A \"long\" prompt word"), title)
        assertTrue(title.length <= CLAUDE_TITLE_MAX)
    }

    @Test
    fun `a meta or system first line cut by the head is no title`() {
        val pad = "x".repeat(CLAUDE_TITLE_HEAD_BYTES)
        val meta = File(top, "m.jsonl").apply {
            writeText("""{"isMeta":true,"type":"user","message":{"role":"user","content":"$pad"},"uuid":"u"}""" + "\n")
        }
        val tag = File(top, "s.jsonl").apply {
            writeText("""{"type":"user","message":{"role":"user","content":"<task-notification>$pad"},"uuid":"u"}""" + "\n")
        }
        assertNull(claudeSessionTitle(meta))
        assertNull(claudeSessionTitle(tag))
    }

    @Test
    fun `past chats are newest first, only those with a prompt`() {
        val a = "aaaaaaaa-1111-1111-1111-111111111111"
        val b = "bbbbbbbb-1111-1111-1111-111111111111"
        val c = "cccccccc-1111-1111-1111-111111111111"
        File(top, "$a.jsonl").apply { writeText(user(1, "older") + "\n"); setLastModified(1000_000) }
        File(top, "$b.jsonl").apply { writeText(user(1, "newer") + "\n"); setLastModified(3000_000) }
        File(top, "$c.jsonl").apply { writeText("""{"type":"mode"}""" + "\n"); setLastModified(2000_000) }
        assertEquals(
            listOf(ClaudeSessionSummary(b, "newer", 3000_000), ClaudeSessionSummary(a, "older", 1000_000)),
            listClaudeSessions(top),
        )
    }

    @Test
    fun `the list is capped to the most recent`() {
        for (n in 1..5) {
            File(top, "0000000$n-1111-1111-1111-111111111111.jsonl").apply { writeText(user(n, "p$n") + "\n"); setLastModified(n * 1000_000L) }
        }
        assertEquals(listOf("p5", "p4"), listClaudeSessions(top, max = 2).map { it.title })
    }

    @Test
    fun `an unreadable or missing folder lists nothing`() {
        assertEquals(emptyList(), listClaudeSessions(File(top, "absent")))
    }

    @Test
    fun `relative times`() {
        val now = 100L * 24 * 3_600_000
        fun ago(ms: Long) = chatRelativeTime(now, now - ms)
        assertEquals("just now", ago(30_000))
        assertEquals("just now", ago(-5_000))
        assertEquals("1 min ago", ago(61_000))
        assertEquals("59 min ago", ago(59 * 60_000L))
        assertEquals("1 h ago", ago(3_600_000))
        assertEquals("23 h ago", ago(23 * 3_600_000L))
        assertEquals("yesterday", ago(24 * 3_600_000L))
        assertEquals("2 days ago", ago(48 * 3_600_000L))
        assertEquals("29 days ago", ago(29 * 86_400_000L))
        assertEquals("1 month ago", ago(30 * 86_400_000L))
        assertEquals("3 months ago", ago(95 * 86_400_000L))
    }

    @Test
    fun `the model is the last one the agent itself answered with`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"a"}]},"uuid":"a1"}""",
            """{"type":"assistant","message":{"role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"b"}]},"uuid":"a2"}""",
            """{"type":"assistant","message":{"role":"assistant","model":"claude-sonnet-5","content":[{"type":"text","text":"sub"}]},"isSidechain":true,"uuid":"a3"}""",
            """{"type":"assistant","message":{"role":"assistant","model":"<synthetic>","content":[{"type":"text","text":"API Error"}]},"isApiErrorMessage":true,"uuid":"a4"}""",
        )
        val transcript = readClaudeTranscript(lines.asSequence())
        assertEquals("claude-haiku-5-5", transcript.model)
        assertEquals(readClaudeHistory(lines.asSequence()), transcript.items)
        assertEquals("m", readClaudeTranscript(fixture.lineSequence()).model)
        assertNull(readClaudeTranscript(sequenceOf(user(1, "hi"))).model)
    }
}
