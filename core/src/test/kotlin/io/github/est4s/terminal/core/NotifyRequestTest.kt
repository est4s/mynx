package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NotifyRequestTest {
    private val base = createTempDirectory("notify").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val posted = mutableListOf<Notice>()
    private var refusal: String? = null
    private val requests = PocketRequests(dir, home, notify = { notice ->
        posted += notice
        refusal
    })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `posts a notification with a title and text`() {
        assertEquals("""{"ok":true,"shown":true}""", ask("notify", "Build done", "All 12 tests pass"))
        assertEquals(listOf(Notice("Build done", "All 12 tests pass", shell = null, ifAway = false)), posted)
    }

    @Test
    fun `the text is optional`() {
        ask("notify", "Hi")
        assertEquals(Notice("Hi", "", shell = null, ifAway = false), posted.single())
    }

    @Test
    fun `knows the tab it came from, and whether to skip it when that tab is on screen`() {
        ask("notify", "Hi", "there", "shell=3", "if-away")
        assertEquals(Notice("Hi", "there", shell = 3, ifAway = true), posted.single())
    }

    @Test
    fun `says why the app didn't show it`() {
        refusal = "you're looking at that tab"
        assertEquals(
            """{"ok":true,"shown":false,"reason":"you're looking at that tab"}""",
            ask("notify", "Hi", "", "if-away"),
        )
    }

    @Test
    fun `needs a title`() {
        assertEquals("""{"ok":false,"error":"notify needs a title"}""", ask("notify", "  "))
        assertEquals("""{"ok":false,"error":"notify needs a title"}""", ask("notify"))
        assertEquals(emptyList(), posted)
    }

    @Test
    fun `refuses options it doesn't know`() {
        assertEquals("""{"ok":false,"error":"unknown notify option 'loud'"}""", ask("notify", "Hi", "", "loud"))
        assertEquals("""{"ok":false,"error":"unknown notify option 'shell=x'"}""", ask("notify", "Hi", "", "shell=x"))
        assertEquals("""{"ok":false,"error":"unknown notify option 'took=-1'"}""", ask("notify", "Hi", "", "took=-1"))
    }

    @Test
    fun `cuts very long titles and texts`() {
        ask("notify", "t".repeat(500), "x".repeat(5000))
        assertEquals("t".repeat(99) + "…", posted.single().title)
        assertEquals("x".repeat(999) + "…", posted.single().text)
    }

    @Test
    fun `agent notifications follow the agent-notify setting`() {
        config("agent-notify = off\n")
        assertEquals(
            """{"ok":true,"shown":false,"reason":"agent-notify is off (pocket set agent-notify on)"}""",
            ask("notify", "Claude Code", "Your turn", "agent"),
        )
        assertEquals(emptyList(), posted)
    }

    @Test
    fun `agent turns shorter than agent-notify-after don't notify`() {
        config("agent-notify-after = 60\n")
        assertEquals(
            """{"ok":true,"shown":false,"reason":"the turn took 59s; agent-notify-after is 60s"}""",
            ask("notify", "Claude Code", "Your turn", "agent", "took=59"),
        )
        assertEquals("""{"ok":true,"shown":true}""", ask("notify", "Claude Code", "Your turn", "agent", "took=60"))
        assertEquals(1, posted.size)
    }

    @Test
    fun `an agent asking for input notifies whatever the turn's length`() {
        assertEquals("""{"ok":true,"shown":true}""", ask("notify", "Claude Code", "Needs permission", "agent"))
    }

    @Test
    fun `without an app to show it, nothing is shown`() {
        val plain = PocketRequests(dir, home)
        File(dir, "q.req").writeText("notify\nHi\n")
        plain.processPending()
        assertEquals("""{"ok":true,"shown":false,"reason":"notifications aren't available"}""", File(dir, "q.reply").readText())
    }

    @Test
    fun `opens web links in the phone's browser`() {
        val opened = mutableListOf<String>()
        val app = PocketRequests(dir, home, openUrl = { opened += it; null })
        File(dir, "q.req").writeText("open-url\nhttps://claude.ai/login\n")
        app.processPending()
        assertEquals("""{"ok":true}""", File(dir, "q.reply").readText())
        assertEquals(listOf("https://claude.ai/login"), opened)

        File(dir, "q.req").writeText("open-url\nfile:///etc/passwd\n")
        app.processPending()
        assertEquals("""{"ok":false,"error":"only http and https links open: file:///etc/passwd"}""", File(dir, "q.reply").readText())
        assertEquals(1, opened.size)
    }

    @Test
    fun `says why a link didn't open`() {
        val app = PocketRequests(dir, home, openUrl = { "no browser" })
        File(dir, "q.req").writeText("open-url\nhttps://x.io\n")
        app.processPending()
        assertEquals("""{"ok":false,"error":"no browser"}""", File(dir, "q.reply").readText())
    }

    private fun config(text: String) =
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText(text)

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").readText()
    }
}
