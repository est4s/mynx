package io.github.est4s.terminal.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    /** What to run, from [agentBinary]; else `~/.local/bin/claude`. */
    val binary: String? = null,
)

private val UUID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private val PLAIN_VALUE = Regex("[A-Za-z0-9._:\\[\\]-]+")

private fun requireUuid(flag: String, value: String) = require(UUID.matches(value)) { "$flag: $value isn't a UUID" }

private fun requirePlain(flag: String, value: String) =
    require(PLAIN_VALUE.matches(value) && !value.startsWith("-")) { "$flag: bad value $value" }

/**
 * The command for a chat: Claude's stream-json mode, for [ClaudeSession].
 * The binary [agentBinary] found, not the terminal's wrapper: the messaging
 * socket doesn't matter headless.
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
        options.binary ?: claudeBinary(options.home), "-p",
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

// A tab's or a service's setup (~/.local/bin, /etc/profile.d, ~/.profile). exec:
// the agent stays proot's direct child, which stopAgentProot sends TERM to.
private val LOGIN_EXEC = listOf("/bin/bash", "--login", "-c", "exec \"\$@\"", "bash")

/** The command whose output [parseClaudeAuthStatus] reads. */
fun claudeAuthStatusCommand(binary: String = claudeBinary("/root")): List<String> = LOGIN_EXEC + listOf(binary, "auth", "status", "--json")

enum class SignInState { SIGNED_IN, NOT_SIGNED_IN, UNKNOWN }

/**
 * Reads `claude auth status --json` (exit 1 when not signed in).
 * [SignInState.UNKNOWN] means the check says nothing: no such binary,
 * unexpected output. Don't send people to sign in for that.
 */
fun parseClaudeAuthStatus(exitCode: Int?, output: String): SignInState {
    if (exitCode == null) return SignInState.UNKNOWN
    // It runs under a login shell, whose profile may print anything around it.
    val lines = output.lines()
    for (start in lines.indices.filter { lines[it].trimStart().startsWith("{") }) {
        for (end in start until lines.size) {
            if (!lines[end].trimEnd().endsWith("}")) continue
            when (authStatusField(lines.subList(start, end + 1).joinToString("\n"))) {
                true -> return SignInState.SIGNED_IN
                false -> return SignInState.NOT_SIGNED_IN
                else -> Unit
            }
        }
    }
    return SignInState.UNKNOWN
}

private fun authStatusField(text: String): Any? = try {
    (parseJson(text) as? Map<*, *>)?.get("loggedIn")
} catch (e: JsonException) {
    null
}

/** A `result` saying sign-in is needed: "Not logged in · Please run /login", the one message known. */
fun isNotSignedInResult(isError: Boolean, text: String?): Boolean =
    isError && text != null && text.trimStart().startsWith("Not logged in")

/** A `result` from `--resume` of a conversation that isn't there. */
fun isNoConversationResult(text: String?): Boolean = text?.startsWith("No conversation found") == true

/** What to run in a tab when [AgentEvent.NotSignedIn] comes: the terminal's own sign-in. */
const val CLAUDE_SIGN_IN_COMMAND = "claude auth login"

/** What to run in a tab when Claude Code's binary is missing. */
const val CLAUDE_INSTALL_COMMAND = "mynx agent install claude"

/** How long the sign-in check may take before the chat goes ahead without an answer. */
const val CLAUDE_CHECK_TIMEOUT_MS = 30_000L

/**
 * Runs `claude auth status` through [transport], [done] getting the answer
 * once, from the transport's thread. A status that says nothing goes
 * ahead, and so does one that's stuck past [schedule]'s timeout, closed.
 */
fun claudeAccessCheck(transport: AgentTransport, schedule: (Long, () -> Unit) -> Unit, done: (ChatAccess) -> Unit) {
    val answered = AtomicBoolean(false)
    val output = StringBuilder()
    transport.start(object : AgentTransport.Listener {
        override fun onLine(line: String) {
            synchronized(output) { output.append(line).append('\n') }
        }

        override fun onExit(exitCode: Int?, detail: String) {
            if (!answered.compareAndSet(false, true)) return
            val status = parseClaudeAuthStatus(exitCode, synchronized(output) { output.toString() })
            done(if (status == SignInState.NOT_SIGNED_IN) ChatAccess.NOT_SIGNED_IN else ChatAccess.READY)
        }
    })
    schedule(CLAUDE_CHECK_TIMEOUT_MS) {
        if (answered.compareAndSet(false, true)) {
            transport.close()
            done(ChatAccess.READY)
        }
    }
}

/** A [prootLaunch] command for [launch], in a login shell: its env goes in with the rest of `env -i`'s. */
fun agentProotCommand(launch: AgentLaunch): List<String> = launch.env.map { (key, value) -> "$key=$value" } + LOGIN_EXEC + launch.argv

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
