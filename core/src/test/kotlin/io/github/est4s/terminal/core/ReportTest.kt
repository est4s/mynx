package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTest {
    private val base = createTempDirectory("report").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val crashes = Crashes(File(base, "files").apply { mkdirs() })
    private val pixel = Phone(android = "16", sdk = 36, maker = "Google", model = "Pixel 10")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `no crash at first`() {
        assertNull(crashes.last())
        assertNull(crashes.unseen())
    }

    @Test
    fun `a crash stays for reports after it was shown`() {
        crashes.save("java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)")
        assertEquals("java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)", crashes.unseen()?.text)
        crashes.seen()
        assertNull(crashes.unseen())
        assertEquals("java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)", crashes.last()?.text)
    }

    @Test
    fun `a new crash replaces the one before`() {
        crashes.save("first")
        crashes.seen()
        crashes.save("second")
        assertEquals("second", crashes.unseen()?.text)
        assertEquals("second", crashes.last()?.text)
        crashes.seen()
        assertEquals("second", crashes.last()?.text)
    }

    @Test
    fun `a crash has its time`() {
        crashes.save("boom", time = 1_760_000_000_000)
        assertEquals(1_760_000_000_000, crashes.last()?.time)
    }

    @Test
    fun `report-info gives the phone and the last crash`() {
        crashes.save("boom \"here\"", time = 1_760_000_000_000)
        crashes.seen()
        val app = MynxRequests(dir, home, phone = { pixel }, crashes = crashes)
        assertEquals(
            """{"ok":true,"android":"16","sdk":36,"maker":"Google","model":"Pixel 10",""" +
                """"crash":"boom \"here\"","crash_time":1760000000000}""",
            ask(app, "report-info"),
        )
    }

    @Test
    fun `report-info with no crash`() {
        val app = MynxRequests(dir, home, phone = { pixel }, crashes = crashes)
        assertTrue(ask(app, "report-info").endsWith(""""crash":null,"crash_time":null}"""))
    }

    @Test
    fun `report-info cuts a long crash to its start`() {
        crashes.save("x".repeat(MAX_REPORT_CRASH + 100))
        val app = MynxRequests(dir, home, phone = { pixel }, crashes = crashes)
        val reply = ask(app, "report-info")
        assertTrue(reply.contains("\"crash\":\"${"x".repeat(MAX_REPORT_CRASH - 1)}…\""), reply)
    }

    @Test
    fun `report-info outside the app knows no phone`() {
        val reply = ask(MynxRequests(dir, home), "report-info")
        assertEquals(
            """{"ok":true,"android":null,"sdk":null,"maker":null,"model":null,"crash":null,"crash_time":null}""",
            reply,
        )
        assertFalse(reply.contains("error"))
    }

    private fun ask(app: MynxRequests, vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        app.processPending()
        return File(dir, "q.reply").readText()
    }
}
