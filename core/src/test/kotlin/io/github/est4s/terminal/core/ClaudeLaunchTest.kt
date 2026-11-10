package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeLaunchTest {
    private val LOGIN = listOf("/bin/bash", "--login", "-c", "exec \"\$@\"", "bash")
    private val uuid = "737a8011-89d5-4e7f-a7ce-41bcea8fe1ef"
    private val base = listOf(
        "/root/.local/bin/claude", "-p",
        "--output-format", "stream-json",
        "--verbose",
        "--input-format", "stream-json",
        "--include-partial-messages",
        "--replay-user-messages",
        "--permission-prompt-tool", "stdio",
    )

    @Test
    fun `the launch runs the real binary with the streaming flags`() {
        val launch = claudeLaunch(ClaudeLaunchOptions(cwd = "/root/work"))
        assertEquals(base, launch.argv)
        assertEquals("/root/work", launch.cwd)
        assertEquals("/root", launch.env["HOME"])
        assertEquals("1", launch.env["MYNX_CHAT"])
    }

    @Test
    fun `the binary follows the home folder`() {
        assertEquals("/home/a/.local/bin/claude", claudeLaunch(ClaudeLaunchOptions(cwd = "/", home = "/home/a")).argv[0])
    }

    @Test
    fun `the options add their flags`() {
        val argv = claudeLaunch(ClaudeLaunchOptions(cwd = "/", resume = uuid, model = "claude-haiku-5-5", permissionMode = "plan")).argv
        assertEquals(base + listOf("--resume", uuid, "--model", "claude-haiku-5-5", "--permission-mode", "plan"), argv)
        assertEquals(base + listOf("--session-id", uuid), claudeLaunch(ClaudeLaunchOptions(cwd = "/", sessionId = uuid)).argv)
    }

    @Test
    fun `resume and a new id don't mix`() {
        assertFailsWith<IllegalArgumentException> { claudeLaunch(ClaudeLaunchOptions(cwd = "/", resume = uuid, sessionId = uuid)) }
    }

    @Test
    fun `values can't become flags`() {
        assertFailsWith<IllegalArgumentException> { claudeLaunch(ClaudeLaunchOptions(cwd = "/", resume = "--dangerous")) }
        assertFailsWith<IllegalArgumentException> { claudeLaunch(ClaudeLaunchOptions(cwd = "/", sessionId = "nope")) }
        assertFailsWith<IllegalArgumentException> { claudeLaunch(ClaudeLaunchOptions(cwd = "/", model = "--foo")) }
        assertFailsWith<IllegalArgumentException> { claudeLaunch(ClaudeLaunchOptions(cwd = "/", permissionMode = "a b")) }
    }

    @Test
    fun `only the modes Chat offers can be launched`() {
        for (mode in listOf("bypassPermissions", "dontAsk", "auto", "manual")) {
            assertFailsWith<IllegalArgumentException>(mode) { claudeLaunch(ClaudeLaunchOptions(cwd = "/", permissionMode = mode)) }
            assertFailsWith<IllegalArgumentException>(mode) { claudeHandOffCommand(uuid, "/tmp/f", permissionMode = mode) }
        }
        for (mode in CHAT_MODES.map { it.value }) {
            assertTrue(mode in claudeLaunch(ClaudeLaunchOptions(cwd = "/", permissionMode = mode)).argv)
        }
    }

    @Test
    fun `auth status runs the real binary in a login shell`() {
        assertEquals(LOGIN + listOf("/root/.local/bin/claude", "auth", "status", "--json"), claudeAuthStatusCommand())
        assertEquals(LOGIN + listOf("/usr/local/bin/claude", "auth", "status", "--json"), claudeAuthStatusCommand("/usr/local/bin/claude"))
    }

    @Test
    fun `the launch runs the binary it's given`() {
        assertEquals("/usr/local/bin/claude", claudeLaunch(ClaudeLaunchOptions(cwd = "/", binary = "/usr/local/bin/claude")).argv[0])
    }

    @Test
    fun `noise around auth status's JSON doesn't change the answer`() {
        assertEquals(SignInState.SIGNED_IN, parseClaudeAuthStatus(0, "Hello {you}\n{\"loggedIn\":true}\nbye {now}\n"))
        assertEquals(SignInState.NOT_SIGNED_IN, parseClaudeAuthStatus(1, "{not json\n{\n  \"loggedIn\": false\n}\n"))
    }

    @Test
    fun `resuming a conversation that isn't there is recognised`() {
        assertTrue(isNoConversationResult("No conversation found with session ID: 00000000-0000-4000-8000-000000000000"))
        assertFalse(isNoConversationResult("API Error: 500"))
        assertFalse(isNoConversationResult(null))
    }

    @Test
    fun `auth status is read from its JSON`() {
        assertEquals(SignInState.SIGNED_IN, parseClaudeAuthStatus(0, """{"loggedIn": true, "authMethod": "claude.ai"}"""))
        assertEquals(SignInState.NOT_SIGNED_IN, parseClaudeAuthStatus(1, """{"loggedIn": false, "authMethod": "none", "apiProvider": "firstParty"}"""))
        assertEquals(SignInState.SIGNED_IN, parseClaudeAuthStatus(0, "Warning: x\n{\"loggedIn\":true}\n"))
    }

    @Test
    fun `anything else about auth status is unknown`() {
        assertEquals(SignInState.UNKNOWN, parseClaudeAuthStatus(127, "bash: claude: command not found"))
        assertEquals(SignInState.UNKNOWN, parseClaudeAuthStatus(null, ""))
        assertEquals(SignInState.UNKNOWN, parseClaudeAuthStatus(0, "{}"))
        assertEquals(SignInState.UNKNOWN, parseClaudeAuthStatus(1, "{not json"))
    }

    @Test
    fun `a not-signed-in result is recognised`() {
        assertTrue(isNotSignedInResult(true, "Not logged in · Please run /login"))
        assertFalse(isNotSignedInResult(true, "Invalid API key · Please run /login"), "only the known message")
        assertFalse(isNotSignedInResult(true, "The tool said: run /login to continue"))
        assertFalse(isNotSignedInResult(true, "The server said the user is not logged in"))
        assertFalse(isNotSignedInResult(false, "Not logged in · Please run /login"), "the agent may say so itself")
        assertFalse(isNotSignedInResult(true, "API Error: 500"))
        assertFalse(isNotSignedInResult(true, null))
    }

    private val top = Files.createTempDirectory("claude").toFile()

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    @Test
    fun `the transcript path turns slashes to dashes`() {
        assertEquals("/root/.claude/projects/-root-mynx", claudeTranscriptDir("/root", "/root/mynx"))
        assertEquals("/root/.claude/projects/-root-mynx/$uuid.jsonl", claudeTranscriptPath("/root", "/root/mynx", uuid))
        assertEquals("/root/.claude/projects/-root-mynx", claudeTranscriptDir("/root", "/root/mynx/"))
    }

    @Test
    fun `everything but letters and digits becomes a dash in the folder name`() {
        assertEquals("/root/.claude/projects/-tmp-my-proj-v1-2", claudeTranscriptDir("/root", "/tmp/my_proj.v1 2"))
        // As JavaScript sees it: an emoji is two UTF-16 units, so two dashes.
        assertEquals("/root/.claude/projects/-root-caf--", claudeTranscriptDir("/root", "/root/caf\uD83D\uDE00"))
    }

    @Test
    fun `the hand-off tab runs claude with the app's flags, then says it's done`() {
        val command = claudeHandOffCommand(uuid, "/tmp/.mynx/chat-tab-$uuid")
        assertEquals(listOf("/bin/bash", "--login", "-c"), command.take(3))
        val script = command[3]
        assertTrue(script.contains("mynx agent start claude --resume '$uuid';"), script)
        assertTrue(script.contains(": > '/tmp/.mynx/chat-tab-$uuid'"), script)
        assertTrue(script.endsWith("exec /bin/bash --login"), script)
    }

    @Test
    fun `the hand-off tab gets the chat's model and mode`() {
        val script = claudeHandOffCommand(uuid, "/tmp/f", model = "claude-haiku-5-5", permissionMode = "plan")[3]
        assertTrue(script.contains("mynx agent start claude --resume '$uuid' --model 'claude-haiku-5-5' --permission-mode 'plan';"), script)
        assertFailsWith<IllegalArgumentException> { claudeHandOffCommand(uuid, "/tmp/f", model = "x'; rm -rf /") }
    }

    @Test
    fun `the hand-off refuses what isn't a session id`() {
        assertFailsWith<IllegalArgumentException> { claudeHandOffCommand("x; rm -rf /", "/tmp/f") }
    }

    @Test
    fun `transcripts list newest first, only session files`() {
        val a = File(top, "$uuid.jsonl").apply { writeText("x"); setLastModified(1000_000) }
        val other = "aaaaaaaa-89d5-4e7f-a7ce-41bcea8fe1ef"
        File(top, "$other.jsonl").apply { writeText("x"); setLastModified(2000_000) }
        File(top, "notes.jsonl").writeText("x")
        File(top, "$uuid.txt").writeText("x")
        File(top, "memory").mkdir()
        assertEquals(listOf(ClaudeTranscript(other, 2000_000), ClaudeTranscript(uuid, a.lastModified())), listClaudeTranscripts(top))
    }

    @Test
    fun `no transcripts folder means none`() {
        assertEquals(emptyList(), listClaudeTranscripts(File(top, "absent")))
    }

    @Test
    fun `an agent's env goes before its command, for proot's env -i`() {
        val launch = AgentLaunch(listOf("/root/.local/bin/claude", "-p"), mapOf("HOME" to "/root", "MYNX_CHAT" to "1"), "/root")
        assertEquals(listOf("HOME=/root", "MYNX_CHAT=1") + LOGIN + listOf("/root/.local/bin/claude", "-p"), agentProotCommand(launch))
    }

    @Test
    fun `a chat runs through a login shell, as itself, with its env, past the profile's noise`() {
        File(top, ".profile").writeText("export FROM_PROFILE=yes\necho 'Welcome {to} the login'\necho '{not json'\n")
        val line = "echo \"{\\\"type\\\":\\\"system\\\",\\\"subtype\\\":\\\"init\\\",\\\"session_id\\\":\\\"\${FROM_PROFILE}-\${MYNX_CHAT}-\$\$\\\"}\""
        val command = listOf("/usr/bin/env", "-i", "HOME=$top", "PATH=/usr/bin:/bin") +
            agentProotCommand(AgentLaunch(listOf("/bin/sh", "-c", line), mapOf("MYNX_CHAT" to "1"), "/"))
        var pid = 0L
        val transport = ProcessAgentTransport(
            spawn = { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start().also { pid = it.pid() } },
            stop = { it.destroy() },
        )
        val events = java.util.concurrent.CopyOnWriteArrayList<AgentEvent>()
        val exited = java.util.concurrent.CountDownLatch(1)
        ClaudeSession(transport) { event ->
            events.add(event)
            if (event is AgentEvent.ProcessEnded) exited.countDown()
        }.start()
        assertTrue(exited.await(20, java.util.concurrent.TimeUnit.SECONDS), "no exit: $events")
        assertEquals("yes-1-$pid", events.filterIsInstance<AgentEvent.SessionStarted>().single().sessionId)
    }
}
