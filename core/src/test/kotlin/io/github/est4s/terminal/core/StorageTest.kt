package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTest {
    @Test
    fun `Android 11 and later ask on a settings page, older ones in a dialog`() {
        assertEquals(StorageAsk.SETTINGS, storageAsk(30))
        assertEquals(StorageAsk.SETTINGS, storageAsk(36))
        assertEquals(StorageAsk.DIALOG, storageAsk(29))
        assertEquals(StorageAsk.DIALOG, storageAsk(26))
    }

    @Test
    fun `the bar shows in the phone's storage while the app can't see all of it`() {
        assertTrue(storageBarShown("/storage/emulated/0", granted = false))
        assertTrue(storageBarShown("/storage/emulated/0/DCIM/Camera", granted = false))
        assertTrue(storageBarShown("/storage/emulated/0/", granted = false))
        assertFalse(storageBarShown("/storage/emulated/0", granted = true))
        assertFalse(storageBarShown("/root", granted = false))
        assertFalse(storageBarShown("/", granted = false))
        assertFalse(storageBarShown("/storagefoo", granted = false))
    }
}

class StorageRequestTest {
    private val base = createTempDirectory("storage").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }

    private inner class Access : StorageAccess {
        override var granted = false
        override var how = StorageAsk.SETTINGS
        var asks = 0
        var refusal: String? = null
        override fun ask(): String? {
            asks++
            return refusal
        }
    }

    private val access = Access()
    private val requests = MynxRequests(dir, home, storage = access)

    @AfterTest
    fun cleanup() {
        deleteTree(base)
    }

    @Test
    fun `status says whether the app sees all of the phone's storage`() {
        send("1", "storage")
        send("2", "storage", "status")
        access.granted = true
        send("3", "storage", "status")
        assertEquals("""{"ok":true,"access":"some"}""", file("1.reply").readText())
        assertEquals("""{"ok":true,"access":"some"}""", file("2.reply").readText())
        assertEquals("""{"ok":true,"access":"all"}""", file("3.reply").readText())
        assertEquals(0, access.asks)
    }

    @Test
    fun `allow asks Android, and says how`() {
        send("1", "storage", "allow")
        access.how = StorageAsk.DIALOG
        send("2", "storage", "allow")
        assertEquals(2, access.asks)
        assertEquals("""{"ok":true,"access":"some","asked":"settings"}""", file("1.reply").readText())
        assertEquals("""{"ok":true,"access":"some","asked":"dialog"}""", file("2.reply").readText())
    }

    @Test
    fun `allow doesn't ask again once it's allowed`() {
        access.granted = true
        send("1", "storage", "allow")
        assertEquals(0, access.asks)
        assertEquals("""{"ok":true,"access":"all"}""", file("1.reply").readText())
    }

    @Test
    fun `allow says why it couldn't ask, and refuses what it doesn't know`() {
        access.refusal = "the app must be on screen to ask"
        send("1", "storage", "allow")
        send("2", "storage", "grant")
        send("3", "storage", "allow", "now")
        assertEquals("""{"ok":false,"error":"the app must be on screen to ask"}""", file("1.reply").readText())
        val usage = """{"ok":false,"error":"storage [status] | allow"}"""
        assertEquals(usage, file("2.reply").readText())
        assertEquals(usage, file("3.reply").readText())
    }

    @Test
    fun `without the app's storage it refuses`() {
        MynxRequests(dir, home).also {
            File(dir, "1.req").writeText("storage\n")
            it.processPending()
        }
        assertEquals("""{"ok":false,"error":"the phone's storage isn't available here"}""", file("1.reply").readText())
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
}
