package io.github.est4s.terminal.core

import java.io.File

/** How many rows of a past chat the Chat screen shows. */
const val CHAT_HISTORY_MAX = 300
const val CHAT_HISTORY_EARLIER = "Earlier messages not shown."

/** How much of a transcript is read for its title: the first prompt came within 3 KB in every real one. */
const val CLAUDE_TITLE_HEAD_BYTES = 16 * 1024
const val CLAUDE_TITLE_MAX = 120

/** How many past chats the list reads. */
const val CLAUDE_SESSIONS_MAX = 50

/** A past chat for the chat history: [title] is its first prompt, on one line. */
data class ClaudeSessionSummary(val sessionId: String, val title: String, val modifiedMs: Long)

// User lines Claude writes itself: slash commands and their output, `!`
// commands, background tasks' news, interruptions.
private val SYSTEM_PREFIXES = listOf(
    "<command-name>", "<command-message>", "<command-args>", "<local-command-", "<task-notification>",
    "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<system-reminder>", "[Request interrupted",
)

private const val HISTORY_ID = "history:"

private fun systemText(text: String): Boolean {
    val start = text.trimStart()
    return SYSTEM_PREFIXES.any { start.startsWith(it) }
}

// Lines that aren't part of the conversation as the user saw it.
private fun aside(line: Map<*, *>): Boolean =
    line["isSidechain"] == true || line["isMeta"] == true || line["isCompactSummary"] == true ||
        line["isVisibleInTranscriptOnly"] == true || line["sourceToolUseID"] != null

/** What the user typed on a `user` line; null for tool results and lines Claude made. */
private fun promptText(line: Map<*, *>): String? {
    if (line["type"] != "user" || aside(line)) return null
    val text = promptContent((line["message"] as? Map<*, *>)?.get("content")) ?: return null
    return text.takeUnless { it.isBlank() || systemText(it) }
}

// A prompt Claude took during a turn is stored as an attachment, not a user line.
private fun queuedPrompt(line: Map<*, *>): String? {
    if (aside(line)) return null
    val attachment = line["attachment"] as? Map<*, *> ?: return null
    if (attachment["type"] != "queued_command" || (attachment["commandMode"] ?: "prompt") != "prompt") return null
    return promptContent(attachment["prompt"])?.takeUnless { it.isBlank() || systemText(it) }
}

private fun parseLine(line: String): Map<*, *>? = try {
    parseJson(line) as? Map<*, *>
} catch (e: JsonException) {
    null
}

/**
 * A Claude transcript (`~/.claude/projects/…/ID.jsonl`, one JSON object a
 * line) as the Chat screen's rows: prompts (also those taken during a
 * turn, `queued_command` attachments), the agent's text, and tool
 * cards ended as completed or failed with their output (one with no
 * result never finished: failed). Thinking, subagents' and Claude's own
 * lines, compaction summaries, and lines of unknown types are left out;
 * so are the past permission answers, which aren't in it. Lines that
 * aren't whole JSON (a last line still being written) are skipped. Only
 * the last [max] rows are kept, after one notice that there were more;
 * the lines are read one at a time, so a big file only costs its rows.
 */
fun readClaudeHistory(lines: Sequence<String>, max: Int = CHAT_HISTORY_MAX): List<ChatItem> =
    readClaudeTranscript(lines, max).items

/**
 * A transcript's rows ([readClaudeHistory]), the [model] its last answer
 * came from (`message.model`), its [title] (the first prompt, on one line,
 * as [claudeSessionTitle] has it) and the [context] its last answer used
 * (input, cache read and cache written), each null when there's none.
 */
data class ClaudeHistory(val items: List<ChatItem>, val model: String?, val title: String? = null, val context: Long? = null)

/** [readClaudeHistory], with the model: `--resume` runs the chat on it again. */
fun readClaudeTranscript(lines: Sequence<String>, max: Int = CHAT_HISTORY_MAX): ClaudeHistory {
    var model: String? = null
    var title: String? = null
    var context: Long? = null
    // In order of first appearance; a tool's result replaces its row in place.
    val rows = LinkedHashMap<String, ChatItem>()
    var dropped = false
    fun add(item: ChatItem) {
        rows[item.id] = item
        if (rows.size > max) {
            val first = rows.keys.iterator()
            first.next()
            first.remove()
            dropped = true
        }
    }
    var number = 0
    for (text in lines) {
        number++
        // Claude writes compact JSON: this skips its many big lines of other kinds unparsed.
        if (!text.contains("\"type\":\"user\"") && !text.contains("\"type\":\"assistant\"") && !text.contains("\"type\":\"queued_command\"")) continue
        val line = parseLine(text) ?: continue
        val uuid = line["uuid"] as? String ?: "line$number"
        when (line["type"]) {
            "attachment" -> queuedPrompt(line)?.let { add(ChatItem.User("$HISTORY_ID$uuid", it)) }
            "user" -> {
                promptText(line)?.let {
                    if (title == null) title = oneLine(promptTitleText(it))
                    add(ChatItem.User("$HISTORY_ID$uuid", it))
                }
                if (aside(line)) continue
                val content = (line["message"] as? Map<*, *>)?.get("content") as? List<*> ?: continue
                for (block in content.filterIsInstance<Map<*, *>>()) {
                    if (block["type"] != "tool_result") continue
                    val id = "${HISTORY_ID}tool:${block["tool_use_id"] as? String ?: continue}"
                    val tool = rows[id] as? ChatItem.Tool ?: continue
                    val status = if (block["is_error"] == true) ToolStatus.FAILED else ToolStatus.COMPLETED
                    rows[id] = tool.copy(call = tool.call.copy(status = status, content = cutToolText(resultText(block["content"]))))
                }
            }
            "assistant" -> {
                if (aside(line) || line["isApiErrorMessage"] == true) continue
                val message = line["message"] as? Map<*, *> ?: continue
                if (message["model"] == "<synthetic>") continue
                (message["model"] as? String)?.let { model = it }
                (message["usage"] as? Map<*, *>)?.let { usage -> contextTokens(usage)?.let { context = it } }
                val content = message["content"] as? List<*> ?: continue
                content.forEachIndexed { index, item ->
                    val block = item as? Map<*, *> ?: return@forEachIndexed
                    when (block["type"]) {
                        "text" -> (block["text"] as? String)?.takeIf { it.isNotBlank() }?.let { add(ChatItem.Agent("$HISTORY_ID$uuid:$index", it)) }
                        "tool_use" -> {
                            val id = block["id"] as? String ?: return@forEachIndexed
                            val name = block["name"] as? String ?: return@forEachIndexed
                            val input = block["input"] as? Map<*, *>
                            when (name) {
                                ASK_TOOL -> Unit
                                // The plan the user saw, as text: its card's buttons are long gone.
                                PLAN_TOOL -> (input?.get("plan") as? String)?.takeIf { it.isNotBlank() }
                                    ?.let { add(ChatItem.Agent("$HISTORY_ID$uuid:$index", it)) }
                                else -> {
                                    val about = describeTool(name, input)
                                    val call = ToolCallInfo(id, name, about.title, about.kind, ToolStatus.FAILED, detail = about.detail)
                                    add(ChatItem.Tool("${HISTORY_ID}tool:$id", call))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val items = rows.values.toList()
    val shown = if (dropped) listOf(ChatItem.Notice("history-earlier", NoticeKind.INFO, CHAT_HISTORY_EARLIER)) + items else items
    return ClaudeHistory(shown, model, title, context)
}

/** [readClaudeHistory] of a file; call it off the main thread. */
fun readClaudeHistory(file: File, max: Int = CHAT_HISTORY_MAX): List<ChatItem> =
    file.useLines { readClaudeHistory(it, max) }

/** [readClaudeTranscript] of a file; call it off the main thread. */
fun readClaudeTranscript(file: File, max: Int = CHAT_HISTORY_MAX): ClaudeHistory =
    file.useLines { readClaudeTranscript(it, max) }

internal fun oneLine(text: String): String =
    text.take(CLAUDE_TITLE_MAX * 4).trim().replace(Regex("\\s+"), " ").take(CLAUDE_TITLE_MAX)

/**
 * The first prompt in [file], on one line, read from its first [head]
 * bytes only; null when there's none there.
 */
fun claudeSessionTitle(file: File, head: Int = CLAUDE_TITLE_HEAD_BYTES): String? {
    val buffer = ByteArray(head)
    var size = 0
    file.inputStream().use { input ->
        while (size < head) {
            val read = input.read(buffer, size, head - size)
            if (read < 0) break
            size += read
        }
    }
    val lines = String(buffer, 0, size, Charsets.UTF_8).split('\n')
    val full = size == head
    for (line in if (full) lines.dropLast(1) else lines) {
        val parsed = parseLine(line) ?: continue
        promptText(parsed)?.let { return oneLine(promptTitleText(it)) }
    }
    return if (full) cutPrompt(lines.last()) else null
}

// A first prompt longer than the head: its line is cut, so take the start of its text as it stands.
private fun cutPrompt(line: String): String? {
    if (!line.contains("\"type\":\"user\"") || ASIDE_KEYS.any { line.contains(it) }) return null
    val key = "\"content\":\""
    val at = line.indexOf(key)
    if (at < 0) return null
    val raw = line.substring(at + key.length).take(CLAUDE_TITLE_MAX * 4)
    // The cut may split an escape: back off until it's a whole JSON string.
    for (back in 0..6) {
        val text = runCatching { parseJson("\"" + raw.dropLast(back) + "\"") as? String }.getOrNull() ?: continue
        return text.takeUnless { it.isBlank() || systemText(it) }?.let(::oneLine)
    }
    return null
}

private val ASIDE_KEYS = listOf("\"isSidechain\":true", "\"isMeta\":true", "\"isCompactSummary\":true", "\"sourceToolUseID\"")

/**
 * The past chats in [dir] (a host path to [claudeTranscriptDir]), newest
 * first, with a title each: only the most recent [max] files are read,
 * and only their heads. Sessions with no prompt are left out.
 */
fun listClaudeSessions(dir: File, max: Int = CLAUDE_SESSIONS_MAX): List<ClaudeSessionSummary> =
    listClaudeTranscripts(dir).take(max).mapNotNull { transcript ->
        val title = runCatching { claudeSessionTitle(File(dir, "${transcript.sessionId}.jsonl")) }.getOrNull()
        title?.let { ClaudeSessionSummary(transcript.sessionId, it, transcript.modifiedMs) }
    }

/** How long ago [then] was, for the chat history. */
fun chatRelativeTime(now: Long, then: Long): String {
    val minutes = (now - then) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "$minutes min ago"
        days < 1 -> "$hours h ago"
        days < 2 -> "yesterday"
        days < 30 -> "$days days ago"
        days < 60 -> "1 month ago"
        else -> "${days / 30} months ago"
    }
}
