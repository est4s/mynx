package io.github.est4s.terminal.core

import java.io.File

/** How to run an agent: [argv] and [cwd] as Debian sees them, [env] on top of a tab's. */
data class AgentLaunch(val argv: List<String>, val env: Map<String, String>, val cwd: String)

/** [resume] and [sessionId] are UUIDs, and only one of them may be given. */
data class ClaudeLaunchOptions(
    val cwd: String,
    val home: String = "/root",
    val resume: String? = null,
    val sessionId: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
)

private val UUID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private val PLAIN_VALUE = Regex("[A-Za-z0-9._:\\[\\]-]+")

private fun requireUuid(flag: String, value: String) = require(UUID.matches(value)) { "$flag: $value isn't a UUID" }

private fun requirePlain(flag: String, value: String) =
    require(PLAIN_VALUE.matches(value) && !value.startsWith("-")) { "$flag: bad value $value" }

/**
 * The command for a chat: Claude's stream-json mode, for [ClaudeSession].
 * The real binary, not the terminal's wrapper: the messaging socket
 * doesn't matter headless.
 *
 * A chat keeps the user's settings, as their terminal does. `MYNX_CHAT`
 * tells `mynx hook` to stay quiet: the Chat screen shows what it would say.
 *
 * Don't drive one session from two programs: nothing stops it and the
 * transcript forks silently. Before resuming a chat in a tab, or a tab's
 * session in a chat, wait for the other's process to exit.
 */
fun claudeLaunch(options: ClaudeLaunchOptions): AgentLaunch {
    require(options.resume == null || options.sessionId == null) { "resume or a new session id, not both" }
    options.resume?.let { requireUuid("--resume", it) }
    options.sessionId?.let { requireUuid("--session-id", it) }
    options.model?.let { requirePlain("--model", it) }
    options.permissionMode?.let { requirePlain("--permission-mode", it) }
    val argv = mutableListOf(
        claudeBinary(options.home), "-p",
        "--output-format", "stream-json",
        "--verbose",
        "--input-format", "stream-json",
        "--include-partial-messages",
        "--permission-prompt-tool", "stdio",
    )
    options.resume?.let { argv += listOf("--resume", it) }
    options.sessionId?.let { argv += listOf("--session-id", it) }
    options.model?.let { argv += listOf("--model", it) }
    options.permissionMode?.let { argv += listOf("--permission-mode", it) }
    return AgentLaunch(argv, mapOf("HOME" to options.home, "MYNX_CHAT" to "1"), options.cwd)
}

private fun claudeBinary(home: String) = "$home/.local/bin/claude"

/** The command whose output [parseClaudeAuthStatus] reads. */
fun claudeAuthStatusCommand(home: String = "/root"): List<String> = listOf(claudeBinary(home), "auth", "status", "--json")

enum class SignInState { SIGNED_IN, NOT_SIGNED_IN, UNKNOWN }

/**
 * Reads `claude auth status --json` (exit 1 when not signed in).
 * [SignInState.UNKNOWN] means the check says nothing: no such binary,
 * unexpected output. Don't send people to sign in for that.
 */
fun parseClaudeAuthStatus(exitCode: Int?, output: String): SignInState {
    val start = output.indexOf('{')
    val end = output.lastIndexOf('}')
    if (exitCode == null || start < 0 || end < start) return SignInState.UNKNOWN
    val status = try {
        parseJson(output.substring(start, end + 1)) as? Map<*, *>
    } catch (e: JsonException) {
        null
    }
    return when (status?.get("loggedIn")) {
        true -> SignInState.SIGNED_IN
        false -> SignInState.NOT_SIGNED_IN
        else -> SignInState.UNKNOWN
    }
}

/** A `result` saying sign-in is needed: "Not logged in · Please run /login" and its like. */
fun isNotSignedInResult(isError: Boolean, text: String?): Boolean =
    isError && text != null && (text.contains("not logged in", ignoreCase = true) || text.contains("/login"))

/** What to run in a tab when [AgentEvent.NotSignedIn] comes: the terminal's own sign-in. */
const val CLAUDE_SIGN_IN_COMMAND = "claude auth login"

/**
 * Where Claude keeps a folder's sessions. [home] and [cwd] are Debian's
 * paths; `/` becoming `-` is all the spike saw of the rule (no folder with
 * other punctuation was tried).
 */
fun claudeTranscriptDir(home: String, cwd: String): String =
    "$home/.claude/projects/${cwd.trimEnd('/').replace('/', '-')}"

fun claudeTranscriptPath(home: String, cwd: String, sessionId: String): String =
    "${claudeTranscriptDir(home, cwd)}/$sessionId.jsonl"

data class ClaudeTranscript(val sessionId: String, val modifiedMs: Long)

/** The sessions in [dir] (a host path to [claudeTranscriptDir]), newest first. */
fun listClaudeTranscripts(dir: File): List<ClaudeTranscript> =
    dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".jsonl") && UUID.matches(it.name.removeSuffix(".jsonl")) }
        .map { ClaudeTranscript(it.name.removeSuffix(".jsonl"), it.lastModified()) }
        .sortedByDescending { it.modifiedMs }
