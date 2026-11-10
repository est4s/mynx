package io.github.est4s.terminal.core

private const val MAX_TOOL_TEXT = 16_000
private const val ASK_TOOL = "AskUserQuestion"
private const val PLAN_TOOL = "ExitPlanMode"
private const val USER_DENIED = "The user denied this."
private const val USER_DECLINED = "The user chose not to answer."
private const val PLAN_REFUSED = "The user refused this plan."

private val HOW_OPTIONS = listOf(
    PermissionOption("allow_once", "Allow", PermissionOptionKind.ALLOW_ONCE),
    PermissionOption("reject_once", "Deny", PermissionOptionKind.REJECT_ONCE),
)

/**
 * [AgentSession] over Claude Code's stream-json protocol, driving
 * `claude -p` (see [claudeLaunch]). The protocol is the SDK's private one
 * and Claude Code updates itself, so anything unknown is ignored.
 *
 * What it does for the screen:
 * - Text is rendered from `stream_event`s and reconciled by message id with
 *   the `assistant` line that follows, so it never doubles.
 * - A turn runs from a prompt (or an `init` the agent starts by itself, when
 *   a background job ends) to its `result`, read by `is_error`: `subtype`
 *   says `success` even when not signed in. A `result` with no turn to end,
 *   or one arriving before a sent prompt's `init`, belongs to something
 *   else and ends nothing. A subagent's tools and permissions go on across a
 *   turn's end; the agent's own are failed or withdrawn by it.
 * - "Allow always" grants only the suggestions' `addRules` (`allow`), for
 *   this session only (they often say `localSettings`, which would write a
 *   file), and its label names every rule. A suggested mode or directory
 *   would widen more than the button says, so they're left out; with no
 *   rule to add, there's no "Allow always".
 * - All writes are JSON built here, so no raw user text reaches stdin; one
 *   line that isn't JSON would kill the process.
 *
 * All calls are thread-safe; see [AgentSession] for the threads of events.
 */
class ClaudeSession(
    private val transport: AgentTransport,
    private val listener: (AgentEvent) -> Unit,
) : AgentSession {
    private val lock = Any()
    private var started = false
    private var stopping = false
    private var exited = false

    private var running = false
    private var awaitingInit = false
    private var cancelRequested = false
    private var requests = 0
    private var prompts = 0
    private var initializeId: String? = null
    private val interrupts = HashSet<String>()
    private var session: AgentEvent.SessionStarted? = null

    private val messages = HashMap<String, MessageBlocks>()
    private var currentMessage: String? = null
    private var thinking: String? = null
    private var apiErrorText: String? = null
    private var authFailed = false

    private val calls = LinkedHashMap<String, ToolCallInfo>()
    private val ended = HashSet<String>()
    private val pending = LinkedHashMap<String, Pending>()

    private class TextBlock(val id: String) {
        val text = StringBuilder()
        var final = false
    }

    private class MessageBlocks {
        val byIndex = HashMap<Int, TextBlock>()
        val ordered = ArrayList<TextBlock>()
        var finals = 0
    }

    private sealed class Pending(val input: Map<*, *>, val toolId: String?, val agentId: String?) {
        class Permission(input: Map<*, *>, toolId: String, agentId: String?, val options: List<PermissionOption>, val always: List<Map<String, Any?>>) : Pending(input, toolId, agentId)
        class Question(input: Map<*, *>, toolId: String?, agentId: String?) : Pending(input, toolId, agentId)
        class Plan(input: Map<*, *>, toolId: String?, agentId: String?) : Pending(input, toolId, agentId)
    }

    private val isOpen get() = started && !stopping && !exited

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        transport.start(object : AgentTransport.Listener {
            override fun onLine(line: String) = received(line)
            override fun onExit(exitCode: Int?, detail: String) = processExited(exitCode, detail)
        })
        synchronized(lock) {
            if (exited) return
            val id = nextRequest()
            initializeId = id
            sendRequest(id, linkedMapOf("subtype" to "initialize", "hooks" to null))
        }
    }

    override fun sendPrompt(text: String): Boolean = synchronized(lock) {
        if (!isOpen || text.isBlank() || !transport.accepting) return false
        if (!send(linkedMapOf("type" to "user", "message" to linkedMapOf("role" to "user", "content" to text)))) return false
        prompts++
        listener(AgentEvent.UserMessage("user-$prompts", text))
        if (!running) {
            running = true
            awaitingInit = true
            listener(AgentEvent.TurnStarted)
        }
        true
    }

    override fun cancel(): Boolean = synchronized(lock) {
        if (!isOpen || !running) return false
        cancelRequested = true
        val id = nextRequest()
        interrupts.add(id)
        sendRequest(id, linkedMapOf("subtype" to "interrupt"))
        true
    }

    override fun answerPermission(requestId: String, optionId: String, message: String?): Boolean = synchronized(lock) {
        val request = pending[requestId] as? Pending.Permission ?: return false
        val option = request.options.firstOrNull { it.id == optionId } ?: return false
        if (!isOpen) return false
        pending.remove(requestId)
        val allows = option.kind == PermissionOptionKind.ALLOW_ONCE || option.kind == PermissionOptionKind.ALLOW_ALWAYS
        val answer = if (allows) {
            linkedMapOf<String, Any?>("behavior" to "allow", "updatedInput" to request.input).apply {
                if (option.kind == PermissionOptionKind.ALLOW_ALWAYS) put("updatedPermissions", request.always)
            }
        } else {
            denial(message ?: USER_DENIED)
        }
        sendAnswer(requestId, answer)
        listener(AgentEvent.RequestResolved(requestId, RequestOutcome.Selected(option.kind)))
        if (allows) request.toolId?.let { setToolStatus(it, ToolStatus.IN_PROGRESS) }
        true
    }

    override fun answerQuestion(requestId: String, answers: Map<String, String>): Boolean = synchronized(lock) {
        val request = pending[requestId] as? Pending.Question ?: return false
        if (!isOpen) return false
        pending.remove(requestId)
        val updated = LinkedHashMap<String, Any?>()
        for ((key, value) in request.input) updated[key.toString()] = value
        updated["answers"] = answers
        sendAnswer(requestId, linkedMapOf("behavior" to "allow", "updatedInput" to updated))
        listener(AgentEvent.RequestResolved(requestId, RequestOutcome.Answered(answers)))
        true
    }

    override fun declineQuestion(requestId: String, message: String?): Boolean = synchronized(lock) {
        pending[requestId] as? Pending.Question ?: return false
        if (!isOpen) return false
        pending.remove(requestId)
        sendAnswer(requestId, denial(message ?: USER_DECLINED))
        listener(AgentEvent.RequestResolved(requestId, RequestOutcome.Declined))
        true
    }

    override fun answerPlan(requestId: String, approved: Boolean, message: String?): Boolean = synchronized(lock) {
        val request = pending[requestId] as? Pending.Plan ?: return false
        if (!isOpen) return false
        pending.remove(requestId)
        val answer = if (approved) {
            linkedMapOf("behavior" to "allow", "updatedInput" to request.input)
        } else {
            denial(message ?: PLAN_REFUSED)
        }
        sendAnswer(requestId, answer)
        listener(AgentEvent.RequestResolved(requestId, RequestOutcome.Plan(approved)))
        true
    }

    override fun setMode(mode: String) = control(linkedMapOf("subtype" to "set_permission_mode", "mode" to mode))

    override fun setModel(model: String) = control(linkedMapOf("subtype" to "set_model", "model" to model))

    private fun control(request: Map<String, Any?>): Boolean = synchronized(lock) {
        if (!isOpen) return false
        sendRequest(nextRequest(), request)
        true
    }

    override fun stop() {
        synchronized(lock) {
            if (!started || stopping || exited) return
            stopping = true
        }
        transport.close()
    }

    private fun denial(message: String) = linkedMapOf<String, Any?>("behavior" to "deny", "message" to message)

    private fun nextRequest(): String = "chat-${++requests}"

    private fun sendRequest(id: String, request: Map<String, Any?>) {
        send(linkedMapOf("type" to "control_request", "request_id" to id, "request" to request))
    }

    private fun sendAnswer(requestId: String, answer: Map<String, Any?>) {
        send(
            linkedMapOf(
                "type" to "control_response",
                "response" to linkedMapOf("subtype" to "success", "request_id" to requestId, "response" to answer),
            ),
        )
    }

    private fun send(value: Map<String, Any?>): Boolean {
        // A line reader that also splits on these would cut the JSON in two.
        val line = toJson(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return try {
            transport.writeLine(line)
            true
        } catch (e: Exception) {
            listener(AgentEvent.Notice("Couldn't reach the agent: ${e.message}"))
            false
        }
    }

    private fun received(line: String) {
        val message = try {
            parseJson(line) as? Map<*, *>
        } catch (e: JsonException) {
            null
        } ?: return
        synchronized(lock) {
            if (exited) return
            when (message["type"]) {
                "system" -> if (message["subtype"] == "init") onInit(message)
                "stream_event" -> onStream(message)
                "assistant" -> onAssistant(message)
                "user" -> onToolResults(message)
                "result" -> onResult(message)
                "control_request" -> onControlRequest(message)
                "control_cancel_request" -> onControlCancel(message)
                "control_response" -> onControlResponse(message)
            }
        }
    }

    private fun onInit(message: Map<*, *>) {
        val id = message["session_id"] as? String ?: return
        val info = AgentEvent.SessionStarted(id, message["model"] as? String, message["permissionMode"] as? String)
        if (info != session) {
            session = info
            listener(info)
        }
        awaitingInit = false
        if (!running) {
            running = true
            listener(AgentEvent.TurnStarted)
        }
    }

    private fun onStream(message: Map<*, *>) {
        // Subagents' messages come whole, as `assistant` lines.
        if (message["parent_tool_use_id"] != null) return
        val event = message["event"] as? Map<*, *> ?: return
        val type = event["type"]
        val inner = event["message"] as? Map<*, *>
        val messageId = message["api_message_id"] as? String ?: inner?.get("id") as? String ?: currentMessage
        if (messageId != null) currentMessage = messageId
        val index = (event["index"] as? Long)?.toInt() ?: 0
        val blockKey = "$messageId:$index"
        when (type) {
            "content_block_start" -> {
                val block = event["content_block"] as? Map<*, *> ?: return
                when (block["type"]) {
                    "text" -> if (messageId != null) streamBlock(messageId, index)
                    "thinking" -> {
                        thinking = blockKey
                        listener(AgentEvent.ThoughtIndicator(true))
                    }
                    "tool_use" -> {
                        val id = block["id"] as? String ?: return
                        val name = block["name"] as? String ?: return
                        seeTool(id, name, block["input"] as? Map<*, *>, null, ToolStatus.PENDING)
                    }
                }
            }
            "content_block_delta" -> {
                val delta = event["delta"] as? Map<*, *> ?: return
                if (delta["type"] == "text_delta" && messageId != null) {
                    val text = delta["text"] as? String ?: return
                    val block = streamBlock(messageId, index)
                    if (block.final || text.isEmpty()) return
                    block.text.append(text)
                    listener(AgentEvent.AgentMessageChunk(block.id, text))
                }
            }
            "content_block_stop" -> if (thinking == blockKey) stopThinking()
        }
    }

    private fun streamBlock(messageId: String, index: Int): TextBlock {
        val blocks = messages.getOrPut(messageId) { MessageBlocks() }
        return blocks.byIndex.getOrPut(index) { newBlock(messageId, blocks) }
    }

    private fun newBlock(messageId: String, blocks: MessageBlocks) =
        TextBlock("$messageId#${blocks.ordered.size}").also { blocks.ordered.add(it) }

    private fun stopThinking() {
        if (thinking != null) {
            thinking = null
            listener(AgentEvent.ThoughtIndicator(false))
        }
    }

    private fun onAssistant(message: Map<*, *>) {
        val body = message["message"] as? Map<*, *> ?: return
        val content = body["content"] as? List<*> ?: return
        if (message["is_api_error_message"] == true) {
            apiErrorText = content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
            if (message["error"] == "authentication_failed") authFailed = true
            return
        }
        val parent = message["parent_tool_use_id"] as? String
        val messageId = body["id"] as? String
        for (block in content.filterIsInstance<Map<*, *>>()) {
            when (block["type"]) {
                "text" -> if (parent == null && messageId != null) finalText(messageId, block["text"] as? String ?: "")
                "tool_use" -> {
                    val id = block["id"] as? String ?: continue
                    val name = block["name"] as? String ?: continue
                    val status = if (pending.values.any { it.toolId == id }) ToolStatus.PENDING else ToolStatus.IN_PROGRESS
                    seeTool(id, name, block["input"] as? Map<*, *>, parent, status)
                }
            }
        }
    }

    private fun finalText(messageId: String, text: String) {
        val blocks = messages.getOrPut(messageId) { MessageBlocks() }
        val block = blocks.ordered.getOrNull(blocks.finals) ?: newBlock(messageId, blocks)
        blocks.finals++
        val streamed = block.text.toString()
        block.final = true
        when {
            text == streamed -> return
            text.startsWith(streamed) -> listener(AgentEvent.AgentMessageChunk(block.id, text.substring(streamed.length)))
            else -> listener(AgentEvent.AgentMessageChunk(block.id, text, replace = true))
        }
        block.text.setLength(0)
        block.text.append(text)
    }

    private fun seeTool(id: String, name: String, input: Map<*, *>?, parent: String?, status: ToolStatus) {
        if (name == ASK_TOOL || name == PLAN_TOOL || id in ended) return
        val about = describeTool(name, input)
        val current = calls[id]
        if (current == null) {
            val call = ToolCallInfo(id, name, about.title, about.kind, status, detail = about.detail, parentId = parent)
            calls[id] = call
            listener(AgentEvent.ToolCall(call))
        } else {
            val call = current.copy(title = about.title, detail = about.detail, status = status)
            if (call != current) {
                calls[id] = call
                listener(AgentEvent.ToolCallUpdate(call))
            }
        }
    }

    private fun setToolStatus(id: String, status: ToolStatus) {
        val current = calls[id] ?: return
        if (current.status == status) return
        val call = current.copy(status = status)
        calls[id] = call
        listener(AgentEvent.ToolCallUpdate(call))
    }

    private fun onToolResults(message: Map<*, *>) {
        val body = message["message"] as? Map<*, *> ?: return
        val content = body["content"] as? List<*> ?: return
        for (block in content.filterIsInstance<Map<*, *>>()) {
            if (block["type"] != "tool_result") continue
            val id = block["tool_use_id"] as? String ?: continue
            val call = calls.remove(id) ?: continue
            ended.add(id)
            val status = if (block["is_error"] == true) ToolStatus.FAILED else ToolStatus.COMPLETED
            listener(AgentEvent.ToolCallUpdate(call.copy(status = status, content = cut(resultText(block["content"])))))
        }
    }

    private fun resultText(content: Any?): String = when (content) {
        is String -> content
        is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
        else -> ""
    }

    private fun onResult(message: Map<*, *>) {
        val isError = message["is_error"] == true
        val errors = (message["errors"] as? List<*>).orEmpty().filterIsInstance<String>()
            .filterNot { it.startsWith("[ede_diagnostic]") }.joinToString("\n")
        val text = (message["result"] as? String).takeUnless { it.isNullOrBlank() }
            ?: errors.takeIf { it.isNotBlank() }
            ?: apiErrorText.takeUnless { it.isNullOrBlank() }
        val signedOut = isError && (authFailed || isNotSignedInResult(true, text))
        if (!running) {
            when {
                signedOut -> listener(AgentEvent.NotSignedIn(text ?: "Not signed in"))
                isError && text != null -> listener(AgentEvent.Notice(text))
            }
            return
        }
        if (awaitingInit && !isError) return
        val reason = stopReason(message, isError)
        if (signedOut) listener(AgentEvent.NotSignedIn(text ?: "Not signed in"))
        val shown = if (isError && reason == StopReason.ERROR && !signedOut) text else null
        val cost = (message["total_cost_usd"] as? Number)?.toDouble()
        endTurn(AgentEvent.TurnEnded(reason, shown, cost))
    }

    private fun stopReason(message: Map<*, *>, isError: Boolean): StopReason {
        val reason = message["stop_reason"] as? String
        return when {
            reason == "refusal" -> StopReason.REFUSAL
            reason == "max_tokens" -> StopReason.MAX_TOKENS
            !isError -> StopReason.END_TURN
            cancelRequested || (message["terminal_reason"] as? String)?.startsWith("aborted") == true -> StopReason.CANCELLED
            else -> StopReason.ERROR
        }
    }

    private fun endTurn(end: AgentEvent.TurnEnded) {
        stopThinking()
        closeOpen(ownOnly = true)
        running = false
        awaitingInit = false
        cancelRequested = false
        apiErrorText = null
        authFailed = false
        currentMessage = null
        messages.clear()
        listener(end)
    }

    private fun closeOpen(ownOnly: Boolean) {
        val requests = pending.filterValues { !ownOnly || it.agentId == null }
        for (id in requests.keys) {
            pending.remove(id)
            listener(AgentEvent.RequestResolved(id, RequestOutcome.Withdrawn))
        }
        for (call in calls.values.filter { !ownOnly || it.parentId == null }) {
            calls.remove(call.id)
            ended.add(call.id)
            listener(AgentEvent.ToolCallUpdate(call.copy(status = ToolStatus.FAILED)))
        }
    }

    private fun onControlRequest(message: Map<*, *>) {
        val id = message["request_id"] as? String ?: return
        val request = message["request"] as? Map<*, *> ?: return
        if (request["subtype"] != "can_use_tool") {
            send(
                linkedMapOf(
                    "type" to "control_response",
                    "response" to linkedMapOf("subtype" to "error", "request_id" to id, "error" to "Not supported: ${request["subtype"]}"),
                ),
            )
            return
        }
        val tool = request["tool_name"] as? String ?: return
        val input = request["input"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val toolUseId = request["tool_use_id"] as? String
        val agentId = request["agent_id"] as? String
        when (tool) {
            ASK_TOOL -> {
                pending[id] = Pending.Question(input, toolUseId, agentId)
                listener(AgentEvent.QuestionRequest(id, toolUseId, parseQuestions(input)))
            }
            PLAN_TOOL -> {
                pending[id] = Pending.Plan(input, toolUseId, agentId)
                listener(AgentEvent.PlanRequest(id, toolUseId, input["plan"] as? String ?: "", input["planFilePath"] as? String))
            }
            else -> askPermission(id, tool, input, toolUseId ?: id, agentId, request)
        }
    }

    private fun askPermission(id: String, tool: String, input: Map<*, *>, toolId: String, agentId: String?, request: Map<*, *>) {
        val always = alwaysGrants(request["permission_suggestions"])
        val options = if (always.isEmpty()) {
            HOW_OPTIONS
        } else {
            listOf(HOW_OPTIONS[0], PermissionOption("allow_always", alwaysName(always), PermissionOptionKind.ALLOW_ALWAYS), HOW_OPTIONS[1])
        }
        pending[id] = Pending.Permission(input, toolId, agentId, options, always)
        seeTool(toolId, tool, input, null, ToolStatus.PENDING)
        val call = calls[toolId] ?: ToolCallInfo(toolId, tool, tool, ToolKind.OTHER, ToolStatus.PENDING)
        listener(AgentEvent.PermissionRequest(id, call, options, permissionReview(tool, input)))
    }

    private fun alwaysGrants(suggestions: Any?): List<Map<String, Any?>> =
        (suggestions as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "addRules" && it["behavior"] == "allow" }
            .mapNotNull { suggestion ->
                val rules = (suggestion["rules"] as? List<*>).orEmpty().mapNotNull(::grantRule)
                if (rules.isEmpty()) return@mapNotNull null
                mapOf("type" to "addRules", "rules" to rules, "behavior" to "allow", "destination" to "session")
            }

    // Built anew, so nothing is granted that the label doesn't name.
    private fun grantRule(rule: Any?): Map<String, Any?>? {
        val fields = rule as? Map<*, *> ?: return null
        val tool = fields["toolName"] as? String ?: return null
        if (tool.isBlank()) return null
        return when (val content = fields["ruleContent"]) {
            null -> mapOf("toolName" to tool)
            is String -> if (content.isBlank()) null else mapOf("toolName" to tool, "ruleContent" to content)
            else -> null
        }
    }

    private fun alwaysName(always: List<Map<String, Any?>>): String {
        val granted = always.flatMap { it["rules"] as List<*> }.map { rule ->
            val fields = rule as Map<*, *>
            val content = fields["ruleContent"]
            if (content == null) "all ${fields["toolName"]}" else "${fields["toolName"]}($content)"
        }
        return "Always allow this session: " + granted.joinToString(", ")
    }

    private fun onControlCancel(message: Map<*, *>) {
        val id = message["request_id"] as? String ?: return
        pending.remove(id) ?: return
        listener(AgentEvent.RequestResolved(id, RequestOutcome.Withdrawn))
    }

    private fun onControlResponse(message: Map<*, *>) {
        val response = message["response"] as? Map<*, *> ?: return
        val id = response["request_id"] as? String
        val interrupted = interrupts.remove(id)
        if (response["subtype"] == "error") {
            (response["error"] as? String)?.let { listener(AgentEvent.Notice(it)) }
            return
        }
        if (id == initializeId) {
            val models = ((response["response"] as? Map<*, *>)?.get("models") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { model ->
                val value = model["value"] as? String ?: return@mapNotNull null
                AgentModel(value, model["displayName"] as? String ?: value, model["description"] as? String)
            }
            if (models.isNotEmpty()) listener(AgentEvent.AvailableModels(models))
        }
        // No turn began for a prompt interrupted first, so no `result` will come.
        if (interrupted && running && awaitingInit) endTurn(AgentEvent.TurnEnded(StopReason.CANCELLED))
    }

    private fun processExited(exitCode: Int?, detail: String) {
        synchronized(lock) {
            if (exited) return
            exited = true
            stopThinking()
            closeOpen(ownOnly = false)
            if (running) {
                running = false
                listener(AgentEvent.TurnEnded(if (stopping) StopReason.CANCELLED else StopReason.ERROR))
            }
            listener(AgentEvent.ProcessEnded(exitCode, detail, stopping))
        }
    }
}

private fun cut(text: String) = if (text.length <= MAX_TOOL_TEXT) text else text.take(MAX_TOOL_TEXT) + "\n…"

private class ToolAbout(val title: String, val detail: String?, val kind: ToolKind)

private fun describeTool(name: String, input: Map<*, *>?): ToolAbout {
    fun text(key: String) = (input?.get(key) as? String)?.takeIf { it.isNotEmpty() }
    fun about(title: String?, detail: String? = null, kind: ToolKind) = ToolAbout(title ?: name, detail?.let(::cut), kind)
    return when (name) {
        "Bash" -> about(text("command"), text("description"), ToolKind.EXECUTE)
        "Read" -> about(text("file_path"), null, ToolKind.READ)
        "Write" -> about(text("file_path"), text("content"), ToolKind.EDIT)
        "Edit" -> {
            val change = if (input?.containsKey("old_string") == true) "-${text("old_string") ?: ""}\n+${text("new_string") ?: ""}" else null
            about(text("file_path"), change, ToolKind.EDIT)
        }
        "NotebookEdit" -> about(text("notebook_path"), null, ToolKind.EDIT)
        "Grep", "Glob" -> about(text("pattern"), null, ToolKind.SEARCH)
        "ToolSearch" -> about(text("query"), null, ToolKind.SEARCH)
        "WebSearch" -> about(text("query"), null, ToolKind.FETCH)
        "WebFetch" -> about(text("url"), null, ToolKind.FETCH)
        "Agent", "Task" -> about(text("description"), text("prompt"), ToolKind.THINK)
        "TodoWrite" -> about(null, null, ToolKind.THINK)
        else -> about(null, null, ToolKind.OTHER)
    }
}

/**
 * What a permission card shows: the input as it will run, the same map
 * sent back as `updatedInput`. Shortened only by the card, and never
 * replaced by the model's `description`. Tools without a form of their
 * own, and input that isn't what its tool usually gets, show all of it.
 */
private fun permissionReview(name: String, input: Map<*, *>): String {
    val whole = prettyJson(input)
    fun text(key: String) = input[key] as? String
    fun others(vararg known: String): String {
        val rest = input.filterKeys { it.toString() !in known }
        return if (rest.isEmpty()) "" else "\n\n" + prettyJson(rest)
    }
    return when (name) {
        "Bash" -> {
            val command = text("command") ?: return whole
            command + others("command", "description")
        }
        "Write" -> {
            val path = text("file_path") ?: return whole
            val content = text("content") ?: return whole
            "$path\n\n$content" + others("file_path", "content")
        }
        "Edit" -> {
            val path = text("file_path") ?: return whole
            val old = text("old_string") ?: return whole
            val new = text("new_string") ?: return whole
            val label = if (input["replace_all"] == true) "old (every occurrence)" else "old"
            "$path\n\n$label:\n$old\n\nnew:\n$new" + others("file_path", "old_string", "new_string", "replace_all")
        }
        else -> whole
    }
}

private fun prettyJson(value: Any?, indent: String = ""): String {
    val inner = "$indent  "
    return when {
        value is Map<*, *> && value.isNotEmpty() ->
            value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> inner + toJson(k.toString()) + ": " + prettyJson(v, inner) }
        value is List<*> && value.isNotEmpty() ->
            value.joinToString(",\n", "[\n", "\n$indent]") { inner + prettyJson(it, inner) }
        else -> toJson(value)
    }
}

private fun parseQuestions(input: Map<*, *>): List<AgentQuestion> =
    (input["questions"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { question ->
        val text = question["question"] as? String ?: return@mapNotNull null
        val options = (question["options"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { option ->
            val label = option["label"] as? String ?: return@mapNotNull null
            QuestionOption(label, option["description"] as? String)
        }
        AgentQuestion(text, question["header"] as? String, question["multiSelect"] == true, options)
    }
