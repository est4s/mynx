package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PocketRequestsTest {
    private val base = createTempDirectory("requests").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val requests = PocketRequests(dir, home)

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `answers a check with the config problems`() {
        File(home, ".config/pocket-terminal").mkdirs()
        File(home, ".config/pocket-terminal/colors.properties").writeText("oops\n")
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertEquals(
            """{"ok":true,"problems":[{"file":"~/.config/pocket-terminal/colors.properties","problems":["line 1: expected key=value"]}]}""",
            File(dir, "a1.reply").readText(),
        )
    }

    @Test
    fun `a check with nothing wrong has no problems`() {
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertEquals("""{"ok":true,"problems":[]}""", File(dir, "a1.reply").readText())
    }

    @Test
    fun `removes the request once answered`() {
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertFalse(File(dir, "a1.req").exists())
    }

    @Test
    fun `says which requests it handled, so the app knows to reload`() {
        File(dir, "a1.req").writeText("check\n")
        File(dir, "b2.req").writeText("check\n")

        assertEquals(listOf("check", "check"), requests.processPending())
        assertEquals(emptyList(), requests.processPending())
    }

    @Test
    fun `answers an unknown request with an error`() {
        File(dir, "a1.req").writeText("make-coffee\n")

        requests.processPending()

        assertEquals("""{"ok":false,"error":"unknown request 'make-coffee'"}""", File(dir, "a1.reply").readText())
    }

    @Test
    fun `ignores files that aren't finished requests`() {
        File(dir, "a1.tmp").writeText("check\n")
        File(dir, "../escape.req").writeText("check\n")
        File(dir, "a1.reply").writeText("old")

        requests.processPending()

        assertTrue(File(dir, "a1.tmp").exists())
        assertEquals("old", File(dir, "a1.reply").readText())
    }

    @Test
    fun `escapes text in replies`() {
        File(home, ".config/pocket-terminal").mkdirs()
        File(home, ".config/pocket-terminal/colors.properties").writeText("\"quoted\\\"\n")
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertTrue(File(dir, "a1.reply").readText().contains("""line 1: expected key=value"""))
        File(dir, "x.req").writeText("say \"hi\"\\\n")
        requests.processPending()
        assertEquals("""{"ok":false,"error":"unknown request 'say \"hi\"\\'"}""", File(dir, "x.reply").readText())
    }
}
