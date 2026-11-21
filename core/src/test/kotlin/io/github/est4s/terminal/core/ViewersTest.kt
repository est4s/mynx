package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewersTest {
    private val dir: File = Files.createTempDirectory("viewers").toFile()

    @AfterTest
    fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    @Test
    fun `each kind has its viewer in mynx`() {
        assertEquals(Viewer.IMAGE, viewerFor(FileKind.PICTURE, OpenWith.MYNX))
        for (kind in listOf(FileKind.MARKDOWN, FileKind.CODE, FileKind.TEXT)) assertEquals(Viewer.TEXT, viewerFor(kind, OpenWith.MYNX))
        for (kind in listOf(FileKind.AUDIO, FileKind.VIDEO)) assertEquals(Viewer.MEDIA, viewerFor(kind, OpenWith.MYNX))
    }

    @Test
    fun `another app, or no kind, is no viewer of mynx's`() {
        for (kind in FileKind.entries) assertNull(viewerFor(kind, OpenWith.ANDROID), kind.word)
        assertNull(viewerFor(null, OpenWith.MYNX))
        assertNull(viewerFor(null, OpenWith.ANDROID))
    }

    @Test
    fun `viewers are named as mynx open names them`() {
        assertEquals("mynx's code viewer", viewerName(FileKind.CODE))
        assertEquals("mynx's Markdown viewer", viewerName(FileKind.MARKDOWN))
        assertEquals("mynx's picture viewer", viewerName(FileKind.PICTURE))
        assertEquals("mynx's text viewer", viewerName(FileKind.TEXT))
        assertEquals("mynx's audio player", viewerName(FileKind.AUDIO))
        assertEquals("mynx's video player", viewerName(FileKind.VIDEO))
    }

    @Test
    fun `a file's kind is its name's, else its link target's`() {
        val file = File(dir, "x").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        assertEquals(FileKind.CODE, fileKindOfFile("/root/a.py", "/root/a.py", file))
        assertEquals(FileKind.AUDIO, fileKindOfFile("/root/latest", "/root/song.mp3", file))
    }

    @Test
    fun `a file whose names say nothing is text if it starts as text`() {
        val text = File(dir, "notes").apply { writeText("hello\n") }
        val binary = File(dir, "blob").apply { writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0)) }
        assertEquals(FileKind.TEXT, fileKindOfFile("/root/notes", null, text))
        assertNull(fileKindOfFile("/root/blob", "/root/blob", binary))
        assertNull(fileKindOfFile("/root/gone", null, File(dir, "gone")))
    }
}
