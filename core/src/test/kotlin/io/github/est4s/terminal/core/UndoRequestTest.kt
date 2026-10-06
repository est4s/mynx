package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UndoRequestTest {
    private val base = createTempDirectory("undo").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val requests = Pc26Requests(dir, home, now = { 60_000L })
    private val settings = File(home, "$CONFIG_DIR/settings.conf")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `undoes a setting`() {
        requests.start()
        ask("set", "font-size", "16")
        assertEquals("""{"ok":true,"undone":"set font-size 16"}""", ask("undo"))
        assertEquals(false, settings.exists())
    }

    @Test
    fun `nothing to undo`() {
        requests.start()
        assertEquals("""{"ok":true,"undone":null}""", ask("undo"))
    }

    @Test
    fun `lists the steps, newest first`() {
        requests.start()
        ask("set", "undo-keep", "3")
        ask("theme-set", "nord")
        assertEquals(
            """{"ok":true,"keep":3,"steps":[{"reason":"theme set nord","time":60000},{"reason":"set undo-keep 3","time":60000}]}""",
            ask("undo-list"),
        )
    }

    @Test
    fun `a refused change leaves no step`() {
        requests.start()
        ask("set", "font-size", "99")
        assertEquals("""{"ok":true,"undone":null}""", ask("undo"))
    }

    @Test
    fun `a check records edits by hand, under the reason given`() {
        requests.start()
        settings.apply { parentFile.mkdirs() }.writeText("font-size = 20\n")
        ask("check", "key bar htop edited")
        assertEquals("""{"ok":true,"undone":"key bar htop edited"}""", ask("undo"))
        settings.writeText("font-size = 20\n")
        ask("check")
        assertEquals("""{"ok":true,"undone":"edits by hand"}""", ask("undo"))
    }

    @Test
    fun `starting remembers edits made while the app was closed`() {
        settings.apply { parentFile.mkdirs() }.writeText("font-size = 20\n")
        requests.start()
        settings.writeText("font-size = 22\n")
        requests.start()
        ask("undo")
        assertEquals("font-size = 20\n", settings.readText())
    }

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").readText()
    }
}
