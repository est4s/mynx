package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateStateTest {
    private val dir = createTempDirectory("updates").toFile()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val t = 1_790_000_000_000L
    private val release = parseRelease(releaseJson())

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `round-trips through its file`() {
        val state = UpdateState(lastAttempt = t + 5, lastSuccess = t, latest = "0.2.0", notified = "0.1.1", error = "HTTP 503\nfrom x")
        val file = File(dir, "sub/update-state")
        writeUpdateState(file, state)
        assertEquals(state.copy(error = "HTTP 503 from x"), readUpdateState(file))
        assertTrue(file.readText().startsWith("#"))
        assertFalse(File(dir, "sub").list()!!.any { it != "update-state" }, "temp file left behind")
    }

    @Test
    fun `the file is key = value lines`() {
        val text = UpdateState(lastAttempt = t, lastSuccess = t, latest = "0.2.0").serialize()
        assertTrue("last-attempt = $t" in text.lines(), text)
        assertTrue("last-success = $t" in text.lines(), text)
        assertTrue("latest = 0.2.0" in text.lines(), text)
        assertFalse("notified" in text, text)
    }

    @Test
    fun `a missing or garbled file is a fresh start`() {
        assertEquals(UpdateState(), readUpdateState(File(dir, "none")))
        assertEquals(UpdateState(latest = "0.2.0"), parseUpdateState("junk\nlast-attempt = soon\nlatest = 0.2.0\nwho = me\n"))
    }

    @Test
    fun `checks at once, then daily after a success`() {
        assertTrue(checkDue(UpdateState(), t))
        val ok = UpdateState().afterCheck(ReleaseCheck.Found(release), t)
        assertEquals(t + day, nextCheckAt(ok))
        assertFalse(checkDue(ok, t + day - 1))
        assertTrue(checkDue(ok, t + day))
    }

    @Test
    fun `retries after an hour when a check fails`() {
        val ok = UpdateState().afterCheck(ReleaseCheck.Found(release), t)
        for (failure in listOf(ReleaseCheck.Failed("offline"), ReleaseCheck.BadData("no APK"))) {
            val failed = ok.afterCheck(failure, t + day)
            assertEquals(t + day + hour, nextCheckAt(failed))
            assertFalse(checkDue(failed, t + day + hour - 1))
            assertTrue(checkDue(failed, t + day + hour))
        }
        assertEquals(t + hour, nextCheckAt(UpdateState().afterCheck(ReleaseCheck.Failed("offline"), t)))
    }

    @Test
    fun `a clock that went back makes a check due`() {
        val ok = UpdateState().afterCheck(ReleaseCheck.Found(release), t)
        assertTrue(checkDue(ok, t - 1))
    }

    @Test
    fun `remembers what checks found`() {
        val found = UpdateState().afterCheck(ReleaseCheck.Found(release), t)
        assertEquals(UpdateState(lastAttempt = t, lastSuccess = t, latest = "0.2.0"), found)
        val failed = found.afterCheck(ReleaseCheck.Failed("offline"), t + day)
        assertEquals(found.copy(lastAttempt = t + day, error = "offline"), failed)
        val none = failed.afterCheck(ReleaseCheck.NoRelease, t + 2 * day)
        assertEquals(UpdateState(lastAttempt = t + 2 * day, lastSuccess = t + 2 * day), none)
    }

    @Test
    fun `an update is a newer version than this one`() {
        val state = UpdateState(latest = "0.2.0")
        assertEquals("0.2.0", updateAvailable("0.1.0", state))
        assertNull(updateAvailable("0.2.0", state))
        assertNull(updateAvailable("0.3.0", state))
        assertNull(updateAvailable("0.1.0", UpdateState()))
        assertNull(updateAvailable("0.0.1-dev", UpdateState(latest = "nonsense")))
    }

    @Test
    fun `notifies once per version`() {
        val state = UpdateState(latest = "0.2.0")
        assertTrue(shouldNotify("0.1.0", state))
        val told = state.notified("0.2.0")
        assertEquals("0.2.0", told.notified)
        assertFalse(shouldNotify("0.1.0", told))
        assertTrue(shouldNotify("0.1.0", told.copy(latest = "0.3.0")))
        assertFalse(shouldNotify("0.2.0", state))
    }
}
