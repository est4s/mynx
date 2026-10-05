package io.github.est4s.terminal.core

import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingTest {
    private val base = createTempDirectory("sharing").toFile()
    private val home = File(base, "root").apply { mkdirs() }
    private val time = LocalDateTime.of(2026, 10, 4, 21, 5, 9)

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `serves the files of a share by token and number`() {
        val files = SharedFiles()
        val a = File(home, "a")
        val b = File(home, "b")
        val token = files.add(listOf(a, b))
        assertEquals(a, files.find(token, 0))
        assertEquals(b, files.find(token, 1))
        assertNull(files.find(token, 2))
        assertNull(files.find("other", 0))
    }

    @Test
    fun `tokens can't be guessed from each other`() {
        val files = SharedFiles()
        val first = files.add(listOf(File("a")))
        val second = files.add(listOf(File("a")))
        assertNotEquals(first, second)
        assertTrue(Regex("[0-9a-f]{32}").matches(first), first)
    }

    @Test
    fun `forgets the oldest shares`() {
        val files = SharedFiles(keep = 2)
        val first = files.add(listOf(File("a")))
        val second = files.add(listOf(File("b")))
        val third = files.add(listOf(File("c")))
        assertNull(files.find(first, 0))
        assertEquals(File("b"), files.find(second, 0))
        assertEquals(File("c"), files.find(third, 0))
    }

    @Test
    fun `one type for a share`() {
        assertEquals("image/png", shareType(listOf("image/png", "image/png")))
        assertEquals("image/*", shareType(listOf("image/png", "image/jpeg")))
        assertEquals("*/*", shareType(listOf("image/png", "text/plain")))
        assertEquals("*/*", shareType(listOf("image/png", null)))
        assertEquals("*/*", shareType(emptyList()))
    }

    @Test
    fun `which shared files to save`() {
        assertEquals(listOf("a", "b"), filesToSave(streams = listOf("a", "b"), clip = listOf("a", "b"), text = null))
        assertEquals(listOf("a"), filesToSave(streams = emptyList(), clip = listOf("a"), text = null))
        assertEquals(listOf("a"), filesToSave(streams = listOf("a"), clip = emptyList(), text = "caption"))
        // A browser shares a link with the site's icon in the clip, as a preview: save the link
        assertEquals(emptyList(), filesToSave(streams = emptyList(), clip = listOf("icon"), text = "https://example.org"))
        assertEquals(listOf("a"), filesToSave(streams = emptyList(), clip = listOf("a"), text = ""))
    }

    @Test
    fun `the inbox is the share-folder setting, in Debian`() {
        assertEquals(File(home, "Shared"), inbox(Settings(), home) { time }.folder)
        assertEquals("~/Shared", inbox(Settings(), home) { time }.label)
        assertEquals(File(base, "srv/in"), inbox(Settings(shareFolder = "/srv/in"), home) { time }.folder)
        assertEquals("/srv/in", inbox(Settings(shareFolder = "/srv/in"), home) { time }.label)
    }

    @Test
    fun `keeps the shared file's name, made safe`() {
        val inbox = Inbox(File(home, "Shared"), "~/Shared") { time }
        assertEquals(File(home, "Shared/photo.jpg"), inbox.newFile("photo.jpg", "jpg"))
        assertEquals("passwd", inbox.newFile("../../etc/passwd", null).name)
        assertEquals("ab", inbox.newFile("a\u0000b\n", null).name)
        assertEquals("x.txt", inbox.newFile("dir\\x.txt", null).name)
    }

    @Test
    fun `never overwrites a file`() {
        val inbox = Inbox(File(home, "Shared"), "~/Shared") { time }
        val first = inbox.newFile("photo.jpg", null)
        first.writeText("first")
        assertEquals("photo (2).jpg", inbox.newFile("photo.jpg", null).name)
        assertEquals("photo (3).jpg", inbox.newFile("photo.jpg", null).name)
        assertEquals("first", first.readText())
        File(home, "Shared/notes").mkdirs()
        assertEquals("notes (2)", inbox.newFile("notes", null).name)
    }

    @Test
    fun `files without a name are named by the time`() {
        val inbox = Inbox(File(home, "Shared"), "~/Shared") { time }
        assertEquals("shared-2026-10-04-210509.png", inbox.newFile(null, "png").name)
        assertEquals("shared-2026-10-04-210509 (2).png", inbox.newFile("..", "png").name)
        assertEquals("shared-2026-10-04-210509", inbox.newFile(" ", null).name)
    }

    @Test
    fun `long names are cut, keeping the extension`() {
        val inbox = Inbox(File(home, "Shared"), "~/Shared") { time }
        val name = inbox.newFile("a".repeat(300) + ".jpg", null).name
        assertEquals(MAX_SHARED_NAME, name.length)
        assertTrue(name.endsWith("a.jpg"))
    }

    @Test
    fun `saves text in a txt file named after its subject`() {
        val inbox = Inbox(File(home, "Shared"), "~/Shared") { time }
        val page = inbox.saveText("https://example.com", "A page: the title")
        assertEquals("A page: the title.txt", page.name)
        assertEquals("https://example.com\n", page.readText())
        val plain = inbox.saveText("two\nlines\n", null)
        assertEquals("shared-2026-10-04-210509.txt", plain.name)
        assertEquals("two\nlines\n", plain.readText())
    }

    @Test
    fun `says what was saved where`() {
        val inbox = Inbox(File(home, "Shared"), "~/Shared") { time }
        val one = File(home, "Shared/photo.jpg")
        assertEquals("Saved photo.jpg in ~/Shared", inbox.notice(listOf(one), failed = 0))
        assertEquals("Saved 2 files in ~/Shared", inbox.notice(listOf(one, one), failed = 0))
        assertEquals("Saved photo.jpg in ~/Shared; 1 couldn't be read", inbox.notice(listOf(one), failed = 1))
        assertEquals("Nothing saved: 2 files couldn't be read", inbox.notice(emptyList(), failed = 2))
        assertEquals("Nothing to save", inbox.notice(emptyList(), failed = 0))
    }
}
