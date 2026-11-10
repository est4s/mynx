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
    private val uuid = "737a8011-89d5-4e7f-a7ce-41bcea8fe1ef"
    private val base = listOf(
        "/root/.local/bin/claude", "-p",
        "--output-format", "stream-json",
        "--verbose",
        "--input-format", "stream-json",
        "--include-partial-messages",
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
    fun `auth status runs the real binary`() {
        assertEquals(listOf("/root/.local/bin/claude", "auth", "status", "--json"), claudeAuthStatusCommand())
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
        assertTrue(isNotSignedInResult(true, "Invalid API key · Please run /login"))
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
}
