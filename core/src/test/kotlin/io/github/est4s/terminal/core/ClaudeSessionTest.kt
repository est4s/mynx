package io.github.est4s.terminal.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lines of a real run, from `core/src/test/resources/chat/`: "OUT" from the agent, "IN" written to it. */
internal fun chatFixture(name: String, direction: String = "OUT"): List<String> =
    object {}.javaClass.getResource("/chat/$name")!!.readText().lines()
        .filter { it.startsWith("$direction ") }.map { it.substringAfter(' ') }

internal class FakeTransport : AgentTransport {
    val written = mutableListOf<String>()
    var listener: AgentTransport.Listener? = null
    var closed = 0
    private val inside = AtomicInteger(0)
    var overlapped = false

    override fun start(listener: AgentTransport.Listener) {
        this.listener = listener
    }

    override var accepting = true

    override fun writeLine(line: String) {
        if (inside.incrementAndGet() != 1) overlapped = true
        written.add(line)
        inside.decrementAndGet()
    }

    override fun close() {
        closed++
    }

    fun say(line: String) = listener!!.onLine(line)
    fun exit(code: Int? = 0, detail: String = "") = listener!!.onExit(code, detail)
}

class ClaudeSessionTest {
    private val transport = FakeTransport()
    private val events = mutableListOf<AgentEvent>()
    private val session = ClaudeSession(transport) { events.add(it) }

    private fun started(): ClaudeSession {
        session.start()
        transport.written.clear()
        return session
    }

    private fun replay(name: String) = chatFixture(name).forEach(transport::say)
    private inline fun <reified T : AgentEvent> all() = events.filterIsInstance<T>()
    private fun parsed(line: String) = parseJson(line) as Map<*, *>
    private fun lastWritten() = parsed(transport.written.last())
    private fun chunks() = all<AgentEvent.AgentMessageChunk>()
    private fun answerOf(line: Map<*, *>) = (line["response"] as Map<*, *>)["response"] as Map<*, *>

    private val init = """{"type":"system","subtype":"init","session_id":"s1","model":"m1","permissionMode":"default"}"""
    private fun result(extra: String = "") =
        """{"type":"result","subtype":"success","is_error":false,"stop_reason":"end_turn","session_id":"s1","result":"ok"$extra}"""
    private fun delta(id: String, index: Int, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${json(text)}}},"api_message_id":"$id","parent_tool_use_id":null}"""
    private fun textStart(id: String, index: Int) =
        """{"type":"stream_event","event":{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}},"api_message_id":"$id","parent_tool_use_id":null}"""
    private fun assistantText(id: String, text: String) =
        """{"type":"assistant","message":{"id":"$id","content":[{"type":"text","text":${json(text)}}]},"parent_tool_use_id":null}"""
    private fun toolStart(id: String, name: String) =
        """{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}},"api_message_id":"m","parent_tool_use_id":null}"""
    private fun assistantTool(id: String, name: String, input: String, parent: String = "null") =
        """{"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]},"parent_tool_use_id":${if (parent == "null") "null" else "\"$parent\""}}"""
    private fun toolResult(id: String, content: String, isError: Boolean = false) =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":${json(content)},"is_error":$isError}]},"parent_tool_use_id":null}"""
    private fun canUse(rid: String, tool: String, toolUse: String, input: String, more: String = "") =
        """{"type":"control_request","request_id":"$rid","request":{"subtype":"can_use_tool","tool_name":"$tool","display_name":"$tool","input":$input,"tool_use_id":"$toolUse"$more}}"""
    private val suggestions = """,
        "permission_suggestions":[
            {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch *"}],"behavior":"allow","destination":"localSettings"},
            {"type":"addDirectories","directories":["/w"],"destination":"session"}]""".replace("\n", " ")

    @Test
    fun `start says hello, once`() {
        session.start()
        session.start()
        assertEquals(1, transport.written.size)
        val hello = lastWritten()
        assertEquals("control_request", hello["type"])
        assertEquals(mapOf("subtype" to "initialize", "hooks" to null), hello["request"])
    }

    @Test
    fun `a prompt goes out as one line of JSON, whatever it holds`() {
        started()
        val text = "line one\nsaid \"hi\"\\ \r\u0000 tab\t  para  end ü"
        assertTrue(session.sendPrompt(text))
        val line = transport.written.single()
        assertFalse(line.any { it == '\n' || it == '\r' || it == ' ' || it == ' ' })
        val message = parsed(line)
        assertEquals("user", message["type"])
        assertEquals(mapOf("role" to "user", "content" to text), message["message"])
    }

    @Test
    fun `a prompt echoes as the user's message and starts the turn`() {
        started()
        session.sendPrompt("hello")
        assertEquals(AgentEvent.UserMessage("user-1", "hello"), events[0])
        assertEquals(AgentEvent.TurnStarted, events[1])
        session.sendPrompt("more")
        assertEquals(AgentEvent.PromptQueued("user-2", "more"), events[2])
        assertEquals(3, events.size, "no second TurnStarted during a turn")
    }

    private fun replayed(text: String) =
        """{"type":"user","message":{"role":"user","content":${json(text)}},"session_id":"s1","parent_tool_use_id":null,"isReplay":true}"""

    @Test
    fun `a prompt sent during a turn waits for its replay, and lands where Claude took it`() {
        started()
        session.sendPrompt("Run the shell command `sleep 12` with the Bash tool, then say DONE.")
        session.sendPrompt("Also say the word APPLE.")
        session.sendPrompt("Also say the word CHERRY.")
        assertEquals(listOf("user-2", "user-3"), all<AgentEvent.PromptQueued>().map { it.id })
        assertEquals(listOf("user-1"), all<AgentEvent.UserMessage>().map { it.id })
        replay("claude-queue-replay.txt")
        assertEquals(listOf("user-1", "user-2", "user-3"), all<AgentEvent.UserMessage>().map { it.id }, "the first one's replay is swallowed")
        val apple = events.indexOf(AgentEvent.UserMessage("user-2", "Also say the word APPLE."))
        val tool = events.indexOfFirst { it is AgentEvent.ToolCallUpdate && it.call.status == ToolStatus.COMPLETED }
        assertTrue(tool in 0 until apple, "after the tool's result: $events")
        assertEquals(1, all<AgentEvent.TurnStarted>().size)
        assertEquals(1, all<AgentEvent.TurnEnded>().size)
    }

    @Test
    fun `a replay of prompts merged together takes each of them, in order`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        session.sendPrompt("a")
        session.sendPrompt("b")
        session.sendPrompt("c")
        transport.say(replayed("go"))
        transport.say(result())
        transport.say(init)
        transport.say(replayed("a\nb"))
        assertEquals(listOf("user-1", "user-2", "user-3"), all<AgentEvent.UserMessage>().map { it.id })
        assertEquals(2, all<AgentEvent.TurnStarted>().size, "the queued ones are a turn of their own")
        transport.say(replayed("c"))
        assertEquals(listOf("user-1", "user-2", "user-3", "user-4"), all<AgentEvent.UserMessage>().map { it.id })
    }

    @Test
    fun `a replay that skips a queued prompt sends that one too, and one matching nothing does nothing`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        session.sendPrompt("a")
        session.sendPrompt("b")
        transport.say(replayed("/compact"))
        assertEquals(listOf("user-1"), all<AgentEvent.UserMessage>().map { it.id })
        transport.say(replayed("b"))
        assertEquals(listOf("user-1", "user-2", "user-3"), all<AgentEvent.UserMessage>().map { it.id })
    }

    @Test
    fun `a prompt sent while others still wait queues behind them, and starts the turn`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        session.sendPrompt("a")
        transport.say(result())
        session.sendPrompt("b")
        assertEquals(listOf("user-2", "user-3"), all<AgentEvent.PromptQueued>().map { it.id })
        assertEquals(2, all<AgentEvent.TurnStarted>().size)
        transport.say(init)
        transport.say(replayed("a\nb"))
        assertEquals(listOf("user-1", "user-2", "user-3"), all<AgentEvent.UserMessage>().map { it.id })
    }

    @Test
    fun `a replay with text blocks counts as its text`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        session.sendPrompt("a")
        transport.say("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"a"}]},"isReplay":true}""")
        assertEquals(AgentEvent.UserMessage("user-2", "a"), all<AgentEvent.UserMessage>().last())
    }

    @Test
    fun `prompts queued before a real interrupt run as the next turn`() {
        started()
        session.sendPrompt("Run the shell command `sleep 12` with the Bash tool, then say DONE.")
        session.sendPrompt("Also say the word APPLE.")
        session.sendPrompt("Also say the word CHERRY.")
        val lines = chatFixture("claude-queue-interrupt.txt")
        val firstResult = lines.indexOfFirst { parsed(it)["type"] == "result" }
        lines.take(firstResult + 1).forEach(transport::say)
        assertEquals(StopReason.CANCELLED, all<AgentEvent.TurnEnded>().single().stopReason)
        assertEquals(listOf("user-1"), all<AgentEvent.UserMessage>().map { it.id }, "still queued after the stop")
        val second = lines.indexOfFirst { it.contains("\"result\":\"APPLE") }
        lines.subList(firstResult + 1, second + 1).forEach(transport::say)
        assertEquals(listOf("user-1", "user-2", "user-3"), all<AgentEvent.UserMessage>().map { it.id })
        assertEquals(2, all<AgentEvent.TurnStarted>().size)
        assertEquals(2, all<AgentEvent.TurnEnded>().size)
        session.sendPrompt("Say the word LAST.")
        lines.drop(second + 1).forEach(transport::say)
        assertEquals(AgentEvent.UserMessage("user-4", "Say the word LAST."), all<AgentEvent.UserMessage>().last())
        assertEquals(3, all<AgentEvent.TurnEnded>().size)
    }

    @Test
    fun `no prompts before the start, blank or after the end`() {
        assertFalse(session.sendPrompt("early"))
        session.start()
        assertFalse(session.sendPrompt("   \n"))
        transport.exit(0)
        assertFalse(session.sendPrompt("late"))
        assertEquals(1, transport.written.size, "only the hello")
    }

    @Test
    fun `writes never overlap`() {
        started()
        val go = CountDownLatch(1)
        val writers = (1..8).map { n ->
            thread {
                go.await()
                repeat(200) { session.sendPrompt("t$n-$it") }
            }
        }
        go.countDown()
        writers.forEach { it.join() }
        assertEquals(1600, transport.written.size)
        assertFalse(transport.overlapped)
    }

    @Test
    fun `lines that aren't JSON and unknown types are ignored`() {
        started()
        transport.say("not json at all")
        transport.say("[1,2]")
        transport.say("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}""")
        transport.say("""{"type":"brand_new_thing"}""")
        transport.say("""{"no":"type"}""")
        assertEquals(emptyList(), events)
    }

    @Test
    fun `an unknown request from the agent gets an error answer, so it doesn't wait`() {
        started()
        transport.say("""{"type":"control_request","request_id":"x1","request":{"subtype":"hook_callback"}}""")
        val response = lastWritten()["response"] as Map<*, *>
        assertEquals("error", response["subtype"])
        assertEquals("x1", response["request_id"])
        assertTrue(response["error"] is String)
    }

    @Test
    fun `the initialize answer lists the models`() {
        started()
        val initialize = parsed(chatFixture("claude-question.txt").first())
        transport.say(toJson(initialize + ("response" to (initialize["response"] as Map<*, *> + ("request_id" to "chat-1")))))
        val models = all<AgentEvent.AvailableModels>().single().models
        assertEquals("default", models.first().value)
        assertEquals("Default (recommended)", models.first().name)
    }

    @Test
    fun `init repeats each turn, the session event only when something changed`() {
        started()
        transport.say(init)
        transport.say(result())
        transport.say(init)
        transport.say(result())
        assertEquals(listOf(AgentEvent.SessionStarted("s1", "m1", "default")), all<AgentEvent.SessionStarted>())
        transport.say(init.replace("m1", "m2"))
        assertEquals(AgentEvent.SessionStarted("s1", "m2", "default"), all<AgentEvent.SessionStarted>().last())
    }

    @Test
    fun `text streams, and the final copy doesn't double it`() {
        started()
        transport.say(textStart("msg", 0))
        transport.say(delta("msg", 0, "Hel"))
        transport.say(delta("msg", 0, "lo"))
        transport.say(assistantText("msg", "Hello"))
        assertEquals(
            listOf(AgentEvent.AgentMessageChunk("msg#0", "Hel"), AgentEvent.AgentMessageChunk("msg#0", "lo")),
            chunks(),
        )
    }

    @Test
    fun `a real run's text comes through once`() {
        started()
        replay("claude-question.txt")
        assertEquals("Red", chunks().filter { it.id.startsWith("msg_011CgrCfmSKgY31FjeJcEDTs") }.joinToString("") { it.text })
    }

    @Test
    fun `a message with no stream, like a slash command's, comes whole`() {
        started()
        transport.say(assistantText("m9", "## Context"))
        assertEquals(listOf(AgentEvent.AgentMessageChunk("m9#0", "## Context")), chunks())
    }

    @Test
    fun `missing text is filled in from the final copy`() {
        started()
        transport.say(textStart("m", 0))
        transport.say(delta("m", 0, "Hel"))
        transport.say(assistantText("m", "Hello"))
        assertEquals(AgentEvent.AgentMessageChunk("m#0", "lo"), chunks().last())
    }

    @Test
    fun `a final copy that disagrees replaces the text`() {
        started()
        transport.say(textStart("m", 0))
        transport.say(delta("m", 0, "Hullo"))
        transport.say(assistantText("m", "Hello"))
        assertEquals(AgentEvent.AgentMessageChunk("m#0", "Hello", replace = true), chunks().last())
    }

    @Test
    fun `deltas after the final copy are dropped`() {
        started()
        transport.say(textStart("m", 0))
        transport.say(assistantText("m", "Hello"))
        transport.say(delta("m", 0, "Hello"))
        assertEquals(listOf(AgentEvent.AgentMessageChunk("m#0", "Hello")), chunks())
    }

    @Test
    fun `each text block of a message is its own row`() {
        started()
        transport.say(textStart("m", 0))
        transport.say(delta("m", 0, "a"))
        transport.say(assistantText("m", "a"))
        transport.say(textStart("m", 2))
        transport.say(delta("m", 2, "b"))
        transport.say(assistantText("m", "b"))
        assertEquals(listOf("m#0", "m#1"), chunks().map { it.id })
    }

    @Test
    fun `thinking shows only an indicator`() {
        started()
        val start = """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}},"api_message_id":"m"}"""
        val stop = """{"type":"stream_event","event":{"type":"content_block_stop","index":0},"api_message_id":"m"}"""
        transport.say(start)
        transport.say(stop)
        assertEquals(listOf(AgentEvent.ThoughtIndicator(true), AgentEvent.ThoughtIndicator(false)), all<AgentEvent.ThoughtIndicator>())
    }

    @Test
    fun `a tool call appears, fills in and completes`() {
        started()
        transport.say(toolStart("t1", "Bash"))
        val first = all<AgentEvent.ToolCall>().single().call
        assertEquals(ToolStatus.PENDING, first.status)
        assertEquals(ToolKind.EXECUTE, first.kind)
        assertEquals("Bash", first.title)
        transport.say(assistantTool("t1", "Bash", """{"command":"ls -l","description":"List"}"""))
        var call = all<AgentEvent.ToolCallUpdate>().last().call
        assertEquals("ls -l", call.title)
        assertEquals("List", call.detail)
        assertEquals(ToolStatus.IN_PROGRESS, call.status)
        transport.say(toolResult("t1", "total 0"))
        call = all<AgentEvent.ToolCallUpdate>().last().call
        assertEquals(ToolStatus.COMPLETED, call.status)
        assertEquals("total 0", call.content)
        assertEquals(1, all<AgentEvent.ToolCall>().size)
    }

    @Test
    fun `a tool result of blocks is joined, an error fails the call`() {
        started()
        transport.say(assistantTool("t1", "Read", """{"file_path":"/a"}"""))
        transport.say(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":[{"type":"text","text":"no"},{"type":"tool_reference","tool_name":"X"},{"type":"text","text":"such"}]}]}}""",
        )
        val call = all<AgentEvent.ToolCallUpdate>().last().call
        assertEquals(ToolStatus.FAILED, call.status)
        assertEquals("no\nsuch", call.content)
        assertEquals("/a", call.title)
        assertEquals(ToolKind.READ, call.kind)
    }

    @Test
    fun `huge tool output is cut`() {
        started()
        transport.say(assistantTool("t1", "Read", """{"file_path":"/a"}"""))
        transport.say(toolResult("t1", "x".repeat(200_000)))
        assertTrue(all<AgentEvent.ToolCallUpdate>().last().call.content.length < 40_000)
    }

    @Test
    fun `tools get ACP's kinds`() {
        started()
        val kinds = mapOf(
            "Read" to ToolKind.READ, "Write" to ToolKind.EDIT, "Edit" to ToolKind.EDIT, "NotebookEdit" to ToolKind.EDIT,
            "Grep" to ToolKind.SEARCH, "Glob" to ToolKind.SEARCH, "ToolSearch" to ToolKind.SEARCH,
            "Bash" to ToolKind.EXECUTE, "WebFetch" to ToolKind.FETCH, "WebSearch" to ToolKind.FETCH,
            "Agent" to ToolKind.THINK, "Task" to ToolKind.THINK, "TodoWrite" to ToolKind.THINK,
            "mcp__x__y" to ToolKind.OTHER,
        )
        for ((name, kind) in kinds) {
            transport.say(assistantTool("id-$name", name, "{}"))
            assertEquals(kind, all<AgentEvent.ToolCall>().last().call.kind, name)
        }
    }

    @Test
    fun `an edit's title is the file and its detail the change`() {
        started()
        transport.say(assistantTool("t", "Edit", """{"file_path":"/a.txt","old_string":"one","new_string":"two"}"""))
        val call = all<AgentEvent.ToolCall>().single().call
        assertEquals("/a.txt", call.title)
        assertEquals("-one\n+two", call.detail)
    }

    @Test
    fun `a question or a plan is no tool card`() {
        started()
        replay("claude-question.txt")
        replay("claude-plan.txt")
        assertFalse(all<AgentEvent.ToolCall>().any { it.call.name == "AskUserQuestion" || it.call.name == "ExitPlanMode" })
    }

    @Test
    fun `permission brings the tool call and the options`() {
        started()
        transport.say(assistantTool("t1", "Bash", """{"command":"ls"}"""))
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}""", suggestions))
        val request = all<AgentEvent.PermissionRequest>().single()
        assertEquals("r1", request.requestId)
        assertEquals("ls", request.call.title)
        assertEquals(ToolStatus.PENDING, request.call.status)
        assertEquals(
            listOf(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.ALLOW_ALWAYS, PermissionOptionKind.REJECT_ONCE),
            request.options.map { it.kind },
        )
        assertEquals(ToolStatus.PENDING, all<AgentEvent.ToolCallUpdate>().last().call.status)
    }

    @Test
    fun `permission for a call not seen yet creates it`() {
        started()
        transport.say(canUse("r1", "Bash", "t9", """{"command":"ls"}"""))
        assertEquals("ls", all<AgentEvent.ToolCall>().single().call.title)
        transport.say(assistantTool("t9", "Bash", """{"command":"ls"}"""))
        assertEquals(1, all<AgentEvent.ToolCall>().size)
    }

    @Test
    fun `no always option without a rule to save`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        assertEquals(
            listOf(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.REJECT_ONCE),
            all<AgentEvent.PermissionRequest>().single().options.map { it.kind },
        )
    }

    @Test
    fun `allow always saves the rule for the session only`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"touch a"}""", suggestions))
        val option = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.ALLOW_ALWAYS }
        assertTrue(session.answerPermission("r1", option.id))
        val answer = answerOf(lastWritten())
        assertEquals("allow", answer["behavior"])
        assertEquals(mapOf("command" to "touch a"), answer["updatedInput"])
        assertEquals(
            listOf(mapOf("type" to "addRules", "rules" to listOf(mapOf("toolName" to "Bash", "ruleContent" to "touch *")), "behavior" to "allow", "destination" to "session")),
            answer["updatedPermissions"],
        )
        assertFalse(transport.written.last().contains("localSettings"))
    }

    @Test
    fun `allow always grants only the rule when the mode and directories are suggested too`() {
        started()
        // As a real can_use_tool for mkdir suggested them, all together.
        val all = """, "permission_suggestions":[
            {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"mkdir *"}],"behavior":"allow","destination":"session"},
            {"type":"addDirectories","directories":["/w"],"destination":"session"},
            {"type":"setMode","mode":"acceptEdits","destination":"session"}]""".replace("\n", " ")
        transport.say(canUse("r1", "Bash", "t1", """{"command":"mkdir a"}""", all))
        val option = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.ALLOW_ALWAYS }
        assertEquals("Always allow this session: Bash(mkdir *)", option.name)
        session.answerPermission("r1", option.id)
        assertEquals(
            listOf(mapOf("type" to "addRules", "rules" to listOf(mapOf("toolName" to "Bash", "ruleContent" to "mkdir *")), "behavior" to "allow", "destination" to "session")),
            answerOf(lastWritten())["updatedPermissions"],
        )
    }

    @Test
    fun `no always option for a suggestion with no rule to add`() {
        val noRule = listOf(
            """{"type":"setMode","mode":"acceptEdits","destination":"session"}""",
            """{"type":"addDirectories","directories":["/w"],"destination":"session"}""",
            """{"type":"addRules","rules":[],"behavior":"allow","destination":"session"}""",
            """{"type":"addRules","rules":[{"ruleContent":"no tool named"}],"behavior":"allow","destination":"session"}""",
            """{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":7}],"behavior":"allow","destination":"session"}""",
            """{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"rm *"}],"behavior":"deny","destination":"session"}""",
        )
        started()
        noRule.forEachIndexed { i, suggestion ->
            transport.say(canUse("r$i", "Write", "t$i", """{"file_path":"/a","content":"x"}""", """, "permission_suggestions":[$suggestion]"""))
            assertEquals(
                listOf(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.REJECT_ONCE),
                all<AgentEvent.PermissionRequest>().last().options.map { it.kind },
                suggestion,
            )
        }
    }

    @Test
    fun `the always label names every rule, and a rule for a whole tool says so`() {
        started()
        val rules = """, "permission_suggestions":[
            {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"mkdir *"},{"toolName":"Bash","ruleContent":"touch *"}],"behavior":"allow","destination":"localSettings"},
            {"type":"addRules","rules":[{"toolName":"WebFetch"}],"behavior":"allow","destination":"userSettings"}]""".replace("\n", " ")
        transport.say(canUse("r1", "Bash", "t1", """{"command":"mkdir a && touch b"}""", rules))
        val option = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.ALLOW_ALWAYS }
        assertEquals("Always allow this session: Bash(mkdir *), Bash(touch *), all WebFetch", option.name)
        session.answerPermission("r1", option.id)
        assertEquals(
            listOf(
                mapOf("type" to "addRules", "rules" to listOf(mapOf("toolName" to "Bash", "ruleContent" to "mkdir *"), mapOf("toolName" to "Bash", "ruleContent" to "touch *")), "behavior" to "allow", "destination" to "session"),
                mapOf("type" to "addRules", "rules" to listOf(mapOf("toolName" to "WebFetch")), "behavior" to "allow", "destination" to "session"),
            ),
            answerOf(lastWritten())["updatedPermissions"],
        )
    }

    @Test
    fun `allow once matches what the real run sent`() {
        started()
        val lines = chatFixture("claude-question.txt")
        lines.takeWhile { !(it.contains("can_use_tool") && it.contains("\"Bash\"")) }.forEach(transport::say)
        transport.say(lines.first { it.contains("can_use_tool") && it.contains("\"Bash\"") })
        val request = all<AgentEvent.PermissionRequest>().single()
        assertTrue(session.answerPermission(request.requestId, request.options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }.id))
        val answer = answerOf(lastWritten())
        val sent = chatFixture("claude-question.txt", "IN").map(::parsed).first { it["type"] == "control_response" && (it["response"] as Map<*, *>)["request_id"] == request.requestId }
        assertEquals(answerOf(sent)["updatedInput"], answer["updatedInput"])
        assertEquals("allow", answer["behavior"])
        assertNull(answer["updatedPermissions"])
    }

    @Test
    fun `a denial's message passes through`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        val reject = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.REJECT_ONCE }
        assertTrue(session.answerPermission("r1", reject.id, "Not that folder"))
        assertEquals(mapOf("behavior" to "deny", "message" to "Not that folder"), answerOf(lastWritten()))
        assertEquals(RequestOutcome.Selected(PermissionOptionKind.REJECT_ONCE), all<AgentEvent.RequestResolved>().single().outcome)
    }

    @Test
    fun `a denial has a message even when none is given`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        val reject = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.REJECT_ONCE }
        session.answerPermission("r1", reject.id)
        assertTrue(answerOf(lastWritten())["message"] is String)
    }

    @Test
    fun `an answer counts once, and only for what's pending`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        val allow = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
        assertFalse(session.answerPermission("r1", "no-such-option"), "not an option")
        assertFalse(session.answerPermission("r0", allow.id), "not a request")
        assertTrue(session.answerPermission("r1", allow.id))
        assertFalse(session.answerPermission("r1", allow.id))
        assertEquals(1, transport.written.size)
    }

    @Test
    fun `allowing runs the tool`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        val allow = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
        session.answerPermission("r1", allow.id)
        assertEquals(ToolStatus.IN_PROGRESS, all<AgentEvent.ToolCallUpdate>().last().call.status)
    }

    @Test
    fun `the agent can withdraw a permission`() {
        started()
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        transport.say("""{"type":"control_cancel_request","request_id":"r1"}""")
        assertEquals(AgentEvent.RequestResolved("r1", RequestOutcome.Withdrawn), events.last())
        val allow = all<AgentEvent.PermissionRequest>().single().options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
        assertFalse(session.answerPermission("r1", allow.id))
        assertEquals(emptyList(), transport.written)
    }

    @Test
    fun `withdrawing what isn't pending changes nothing`() {
        started()
        transport.say("""{"type":"control_cancel_request","request_id":"r9"}""")
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a real run's question is answered in the shape it needs`() {
        started()
        val lines = chatFixture("claude-question.txt")
        lines.take(lines.indexOfFirst { it.contains("can_use_tool") } + 1).forEach(transport::say)
        val question = all<AgentEvent.QuestionRequest>().single()
        assertEquals("Which colour do you prefer?", question.questions.single().question)
        assertEquals(listOf("Red", "Blue"), question.questions.single().options.map { it.label })
        assertEquals("Colour", question.questions.single().header)
        assertFalse(question.questions.single().multiSelect)
        assertTrue(session.answerQuestion(question.requestId, mapOf("Which colour do you prefer?" to "Red")))
        val sent = chatFixture("claude-question.txt", "IN").map(::parsed).first { it["type"] == "control_response" }
        assertEquals(answerOf(sent), answerOf(lastWritten()))
        assertEquals(question.requestId, (lastWritten()["response"] as Map<*, *>)["request_id"])
        assertEquals(RequestOutcome.Answered(mapOf("Which colour do you prefer?" to "Red")), all<AgentEvent.RequestResolved>().single().outcome)
    }

    @Test
    fun `a question can be declined`() {
        started()
        transport.say(canUse("q1", "AskUserQuestion", "t1", """{"questions":[]}"""))
        assertTrue(session.declineQuestion("q1", "Not now"))
        assertEquals(mapOf("behavior" to "deny", "message" to "Not now"), answerOf(lastWritten()))
        assertEquals(RequestOutcome.Declined, all<AgentEvent.RequestResolved>().single().outcome)
    }

    @Test
    fun `a real run's plan can be refused and approved`() {
        started()
        val lines = chatFixture("claude-plan.txt")
        val first = lines.indexOfFirst { it.contains("can_use_tool") && it.contains("ExitPlanMode") }
        lines.take(first + 1).forEach(transport::say)
        val plan = all<AgentEvent.PlanRequest>().single()
        assertTrue(plan.plan.startsWith("Run `printf 'hi'"))
        assertEquals("\$HOME/.claude/plans/plan-how-to-create-peppy-widget.md", plan.planFilePath)
        assertTrue(session.answerPlan(plan.requestId, false, "The user tapped Deny."))
        assertEquals(mapOf("behavior" to "deny", "message" to "The user tapped Deny."), answerOf(lastWritten()))
        assertEquals(RequestOutcome.Plan(false), all<AgentEvent.RequestResolved>().single().outcome)
        val second = (first + 1 until lines.size).first { lines[it].contains("can_use_tool") && lines[it].contains("ExitPlanMode") }
        lines.subList(first + 1, second + 1).forEach(transport::say)
        val again = all<AgentEvent.PlanRequest>().last()
        assertTrue(session.answerPlan(again.requestId, true))
        val answer = answerOf(lastWritten())
        assertEquals("allow", answer["behavior"])
        assertEquals(again.plan, (answer["updatedInput"] as Map<*, *>)["plan"])
    }

    @Test
    fun `stop ends a pending request's card`() {
        started()
        session.sendPrompt("hi")
        transport.say(init)
        transport.say(canUse("r1", "Bash", "t1", """{"command":"ls"}"""))
        transport.exit(143)
        assertEquals(AgentEvent.RequestResolved("r1", RequestOutcome.Withdrawn), all<AgentEvent.RequestResolved>().single())
    }

    @Test
    fun `interrupt is the stop button`() {
        started()
        assertFalse(session.cancel(), "nothing runs")
        assertEquals(emptyList(), transport.written)
        session.sendPrompt("go")
        assertTrue(session.cancel())
        val request = parsed(transport.written[1])
        assertEquals("control_request", request["type"])
        assertEquals(mapOf("subtype" to "interrupt"), request["request"])
        session.cancel()
        assertTrue(parsed(transport.written[1])["request_id"] != parsed(transport.written[2])["request_id"])
    }

    @Test
    fun `a real interrupt ends the turn as cancelled, withdraws and fails what was open`() {
        started()
        session.sendPrompt("go")
        replay("claude-interrupt.txt")
        assertEquals(StopReason.CANCELLED, all<AgentEvent.TurnEnded>().single().stopReason)
        val request = all<AgentEvent.PermissionRequest>().single()
        assertEquals(AgentEvent.RequestResolved(request.requestId, RequestOutcome.Withdrawn), all<AgentEvent.RequestResolved>().single())
        assertNull(all<AgentEvent.TurnEnded>().single().message, "the diagnostics aren't for people")
    }

    @Test
    fun `interrupt before the turn began ends it when the agent answers`() {
        started()
        session.sendPrompt("go")
        session.cancel()
        val id = parsed(transport.written[1])["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":{"still_queued":[]}}}""")
        assertEquals(StopReason.CANCELLED, all<AgentEvent.TurnEnded>().single().stopReason)
    }

    @Test
    fun `an interrupt answered during a turn doesn't end it`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        session.cancel()
        val id = parsed(transport.written[1])["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":{"still_queued":[]}}}""")
        assertEquals(emptyList(), all<AgentEvent.TurnEnded>())
    }

    @Test
    fun `an interrupted stream's open tools fail, a turn's thinking stops`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        transport.say(toolStart("t1", "Bash"))
        transport.say("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}},"api_message_id":"m"}""")
        transport.say("""{"type":"result","subtype":"error_during_execution","is_error":true,"terminal_reason":"aborted_streaming","errors":["[ede_diagnostic] x"]}""")
        assertEquals(ToolStatus.FAILED, all<AgentEvent.ToolCallUpdate>().last().call.status)
        assertEquals(AgentEvent.ThoughtIndicator(false), all<AgentEvent.ThoughtIndicator>().last())
    }

    @Test
    fun `result is read by is_error, not subtype`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        transport.say("""{"type":"result","subtype":"success","is_error":true,"stop_reason":"stop_sequence","result":"Overloaded"}""")
        val end = all<AgentEvent.TurnEnded>().single()
        assertEquals(StopReason.ERROR, end.stopReason)
        assertEquals("Overloaded", end.message)
    }

    @Test
    fun `stop reasons`() {
        started()
        fun end(body: String): StopReason {
            transport.say(init)
            transport.say(body)
            return all<AgentEvent.TurnEnded>().last().stopReason
        }
        assertEquals(StopReason.END_TURN, end(result()))
        assertEquals(StopReason.MAX_TOKENS, end("""{"type":"result","is_error":false,"stop_reason":"max_tokens"}"""))
        assertEquals(StopReason.REFUSAL, end("""{"type":"result","is_error":false,"stop_reason":"refusal"}"""))
        assertEquals(StopReason.ERROR, end("""{"type":"result","is_error":true,"terminal_reason":"something"}"""))
    }

    @Test
    fun `cost comes through`() {
        started()
        transport.say(init)
        transport.say(result(""","total_cost_usd":0.25"""))
        assertEquals(0.25, all<AgentEvent.TurnEnded>().last().costUsd)
    }

    @Test
    fun `not signed in, from a real run`() {
        started()
        session.sendPrompt("hello")
        replay("claude-not-signed-in.txt")
        assertEquals(2, all<AgentEvent.NotSignedIn>().size)
        assertEquals("Not logged in · Please run /login", all<AgentEvent.NotSignedIn>().first().message)
        assertEquals(emptyList(), chunks(), "the refusal is no agent message")
        val end = all<AgentEvent.TurnEnded>().first()
        assertEquals(StopReason.ERROR, end.stopReason)
        assertNull(end.message)
    }

    @Test
    fun `an api error's text ends the turn when the result has none`() {
        started()
        session.sendPrompt("hello")
        transport.say(init)
        transport.say("""{"type":"assistant","message":{"id":"x","model":"<synthetic>","content":[{"type":"text","text":"API Error: 500"}]},"error":"server_error","is_api_error_message":true}""")
        transport.say("""{"type":"result","subtype":"success","is_error":true,"result":""}""")
        assertEquals(emptyList(), chunks())
        assertEquals("API Error: 500", all<AgentEvent.TurnEnded>().single().message)
    }

    @Test
    fun `a failed resume says why`() {
        started()
        session.sendPrompt("hello")
        replay("claude-bad-session.txt")
        val end = all<AgentEvent.TurnEnded>().single()
        assertEquals(StopReason.ERROR, end.stopReason)
        assertEquals("No conversation found with session ID: 00000000-0000-4000-8000-000000000000", end.message)
    }

    @Test
    fun `a background agent's extra turn starts and ends by itself, from a real run`() {
        started()
        replay("claude-subagent.txt")
        assertEquals(3, all<AgentEvent.TurnEnded>().size)
        assertEquals(3, all<AgentEvent.TurnStarted>().size)
        assertTrue(all<AgentEvent.TurnEnded>().all { it.stopReason == StopReason.END_TURN })
        assertTrue(events.indexOfLast { it is AgentEvent.TurnEnded } > events.indexOfLast { it is AgentEvent.TurnStarted })
    }

    @Test
    fun `a subagent's text stays out of the chat, its tools show under it`() {
        started()
        replay("claude-subagent.txt")
        assertFalse(chunks().any { it.text.contains("card_sub") })
        val nested = all<AgentEvent.ToolCall>().filter { it.call.parentId != null }
        assertTrue(nested.isNotEmpty())
        assertTrue(nested.all { it.call.name == "Bash" })
    }

    @Test
    fun `an unprompted result while idle ends nothing`() {
        started()
        transport.say(result())
        assertEquals(emptyList(), all<AgentEvent.TurnEnded>())
    }

    @Test
    fun `an unprompted result doesn't end a prompt whose turn hasn't begun`() {
        started()
        session.sendPrompt("go")
        transport.say(result())
        assertEquals(emptyList(), all<AgentEvent.TurnEnded>())
        transport.say(init)
        transport.say(result())
        assertEquals(1, all<AgentEvent.TurnEnded>().size)
    }

    @Test
    fun `a turn the agent starts by itself gets a turn start`() {
        started()
        transport.say(init)
        assertEquals(AgentEvent.TurnStarted, events.last { it !is AgentEvent.SessionStarted })
    }

    @Test
    fun `a subagent's question outlives the turn's end, the agent's own doesn't`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        transport.say(canUse("own", "Bash", "t1", """{"command":"ls"}"""))
        transport.say(canUse("sub", "Bash", "t2", """{"command":"ls"}""", ""","agent_id":"a1""""))
        transport.say(result())
        assertEquals(listOf("own"), all<AgentEvent.RequestResolved>().map { it.requestId })
        val allow = all<AgentEvent.PermissionRequest>().last().options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
        assertTrue(session.answerPermission("sub", allow.id))
    }

    @Test
    fun `a background subagent's tools outlive the turn's end`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        transport.say(assistantTool("t-sub", "Bash", """{"command":"ls"}""", parent = "t-agent"))
        transport.say(result())
        assertTrue(all<AgentEvent.ToolCallUpdate>().none { it.call.id == "t-sub" && it.call.status == ToolStatus.FAILED })
    }

    @Test
    fun `queued lines end as two turns in a real run, and the flag ends off`() {
        started()
        session.sendPrompt("a")
        session.sendPrompt("b")
        session.sendPrompt("c")
        replay("claude-queued.txt")
        assertEquals(2, all<AgentEvent.TurnEnded>().size)
        assertEquals(AgentEvent.TurnEnded::class, events.last()::class)
    }

    @Test
    fun `the agent's mode and model can change`() {
        started()
        assertTrue(session.setMode("acceptEdits"))
        assertEquals(mapOf("subtype" to "set_permission_mode", "mode" to "acceptEdits"), lastWritten()["request"])
        assertTrue(session.setModel("claude-haiku-5-5"))
        assertEquals(mapOf("subtype" to "set_model", "model" to "claude-haiku-5-5"), lastWritten()["request"])
    }

    @Test
    fun `an accepted mode or model change is told`() {
        started()
        session.setMode("plan")
        val mode = lastWritten()["request_id"]
        session.setModel("sonnet")
        val model = lastWritten()["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"success","request_id":"$model","response":{}}}""")
        transport.say("""{"type":"control_response","response":{"subtype":"success","request_id":"$mode"}}""")
        assertEquals(listOf(AgentEvent.ModelChanged("sonnet"), AgentEvent.ModeChanged("plan")), events)
    }

    @Test
    fun `a refused mode change isn't told as a change`() {
        started()
        session.setMode("plan")
        val id = lastWritten()["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"No"}}""")
        assertEquals(listOf(AgentEvent.Notice("No"), AgentEvent.ModeRefused("plan")), events)
    }

    @Test
    fun `a refused model change is told as refused`() {
        started()
        session.setModel("nope")
        val id = lastWritten()["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"error","request_id":"$id"}}""")
        assertEquals(listOf<AgentEvent>(AgentEvent.ModelRefused("nope")), events)
    }

    @Test
    fun `an accepted change to the mode it's in is told too`() {
        started()
        transport.say(init)
        events.clear()
        session.setMode("default")
        val id = lastWritten()["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"success","request_id":"$id"}}""")
        assertEquals(listOf<AgentEvent>(AgentEvent.ModeChanged("default")), events)
    }

    @Test
    fun `the initialize answer says the mode it starts in`() {
        started()
        val lines = chatFixture("claude-plan.txt")
        val initialize = parsed(lines.first())
        transport.say(toJson(initialize + ("response" to (initialize["response"] as Map<*, *> + ("request_id" to "chat-1")))))
        assertEquals(listOf(AgentEvent.ModeChanged("plan")), all<AgentEvent.ModeChanged>())
        // The turns' inits say the same: no second change.
        lines.drop(1).forEach(transport::say)
        assertEquals(1, all<AgentEvent.ModeChanged>().size)
        assertEquals("plan", all<AgentEvent.SessionStarted>().last().mode)
    }

    @Test
    fun `a status line with a mode is followed, once`() {
        started()
        transport.say(init)
        val status = """{"type":"system","subtype":"status","status":null,"permissionMode":"acceptEdits","session_id":"s1"}"""
        transport.say(status)
        transport.say(status)
        transport.say("""{"type":"system","subtype":"status","status":"requesting","session_id":"s1"}""")
        assertEquals(listOf(AgentEvent.ModeChanged("acceptEdits")), all<AgentEvent.ModeChanged>())
    }

    @Test
    fun `a refused request becomes a notice`() {
        started()
        session.setModel("nope")
        val id = lastWritten()["request_id"]
        transport.say("""{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"No such model"}}""")
        assertEquals(listOf(AgentEvent.Notice("No such model"), AgentEvent.ModelRefused("nope")), events.takeLast(2))
    }

    @Test
    fun `the process ending closes everything, in order, once`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        transport.say(toolStart("t1", "Bash"))
        transport.exit(1, "boom")
        val tail = events.takeLast(3)
        assertEquals(ToolStatus.FAILED, (tail[0] as AgentEvent.ToolCallUpdate).call.status)
        assertEquals(StopReason.ERROR, (tail[1] as AgentEvent.TurnEnded).stopReason)
        assertEquals(AgentEvent.ProcessEnded(1, "boom", false), tail[2])
        transport.exit(1, "again")
        transport.say(init)
        assertEquals(1, all<AgentEvent.ProcessEnded>().size)
        assertEquals(tail[2], events.last())
    }

    @Test
    fun `a stop is a clean end`() {
        started()
        session.sendPrompt("go")
        transport.say(init)
        session.stop()
        session.stop()
        assertEquals(1, transport.closed)
        assertFalse(session.sendPrompt("too late"))
        transport.exit(143)
        assertEquals(StopReason.CANCELLED, all<AgentEvent.TurnEnded>().single().stopReason)
        assertEquals(AgentEvent.ProcessEnded(143, "", true), events.last())
    }

    @Test
    fun `an idle exit has no turn to end`() {
        started()
        transport.exit(0)
        assertEquals(listOf<AgentEvent>(AgentEvent.ProcessEnded(0, "", false)), events)
    }

    @Test
    fun `a transport that throws doesn't take the session down`() {
        val broken = object : AgentTransport {
            lateinit var l: AgentTransport.Listener
            override fun start(listener: AgentTransport.Listener) { l = listener }
            override fun writeLine(line: String) = throw java.io.IOException("Broken pipe")
            override fun close() {}
        }
        val seen = mutableListOf<AgentEvent>()
        val own = ClaudeSession(broken) { seen.add(it) }
        own.start()
        own.sendPrompt("hi")
        assertTrue(seen.any { it is AgentEvent.Notice && it.text.contains("Broken pipe") })
    }

    @Test
    fun `a prompt to a dead process is refused, not swallowed`() {
        started()
        transport.accepting = false
        assertFalse(session.sendPrompt("hello"))
        assertTrue(transport.written.isEmpty())
        assertTrue(all<AgentEvent.UserMessage>().isEmpty())
    }

    private fun reviewOf(tool: String, input: String): String {
        val n = all<AgentEvent.PermissionRequest>().size
        transport.say(canUse("r$n", tool, "t$n", input))
        return all<AgentEvent.PermissionRequest>().last().review
    }

    @Test
    fun `a permission's review is Bash's command, not the model's description`() {
        started()
        assertEquals("rm -rf build", reviewOf("Bash", """{"command":"rm -rf build","description":"Tidy up"}"""))
    }

    @Test
    fun `a Bash review keeps any other input it was given`() {
        started()
        val review = reviewOf("Bash", """{"command":"ls","description":"List","run_in_background":true}""")
        assertTrue(review.startsWith("ls\n\n"), review)
        assertTrue(""""run_in_background": true""" in review, review)
        assertFalse("List" in review, review)
    }

    @Test
    fun `a Write review has the path and the whole content`() {
        started()
        val content = "x".repeat(20_000)
        assertEquals("/a/b.txt\n\n$content", reviewOf("Write", """{"file_path":"/a/b.txt","content":"$content"}"""))
    }

    @Test
    fun `an Edit review has the path and both texts`() {
        started()
        assertEquals("/a.kt\n\nold:\nfoo\n\nnew:\nbar", reviewOf("Edit", """{"file_path":"/a.kt","old_string":"foo","new_string":"bar","replace_all":false}"""))
        assertEquals("/a.kt\n\nold (every occurrence):\nfoo\n\nnew:\nbar", reviewOf("Edit", """{"file_path":"/a.kt","old_string":"foo","new_string":"bar","replace_all":true}"""))
    }

    @Test
    fun `other tools' reviews are their input, pretty-printed`() {
        started()
        val expected = """
            {
              "a": 1,
              "b": {
                "c": [
                  true,
                  "d"
                ],
                "e": []
              }
            }
        """.trimIndent()
        assertEquals(expected, reviewOf("mcp__server__do", """{"a":1,"b":{"c":[true,"d"],"e":[]}}"""))
    }

    @Test
    fun `an Agent review shows the prompt, not only the model's description`() {
        started()
        val review = reviewOf("Agent", """{"description":"Small helper","prompt":"delete everything"}""")
        assertTrue("delete everything" in review, review)
    }

    @Test
    fun `a Bash review without a command falls back to the whole input`() {
        started()
        assertEquals("{\n  \"cmd\": \"ls\"\n}", reviewOf("Bash", """{"cmd":"ls"}"""))
    }
}
