package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAttachmentsTest {
    private val photo = ChatAttachment("/root/Shared/photo (2).jpg", "photo (2).jpg", image = true)
    private val notes = ChatAttachment("/root/Shared/notes].txt", "notes].txt", image = false)

    @Test
    fun `attachments follow the text as a line each`() {
        assertEquals(
            "Look at these\n\n[Attached image: /root/Shared/photo (2).jpg]\n[Attached file: /root/Shared/notes].txt]",
            promptWithAttachments("Look at these", listOf(photo, notes)),
        )
        assertEquals("[Attached image: /root/Shared/photo (2).jpg]", promptWithAttachments("  ", listOf(photo)))
        assertEquals("just text", promptWithAttachments("just text", emptyList()))
    }

    @Test
    fun `splitting gives the text and the paths back`() {
        for (text in listOf("Look at these", "two\n\nparagraphs", "")) {
            val sent = promptWithAttachments(text, listOf(photo, notes))
            assertEquals(text to listOf(photo.path, notes.path), splitAttachments(sent))
        }
        assertEquals("plain" to emptyList(), splitAttachments("plain"))
    }

    @Test
    fun `only lines at the end are attachments`() {
        val typed = "[Attached file: /etc/passwd]\nwhat's that line?"
        assertEquals(typed to emptyList(), splitAttachments(typed))
        assertEquals("a\n[Attached file: x]" to emptyList(), splitAttachments("a\n[Attached file: x]"), "not after a blank line")
    }

    @Test
    fun `a path with a line break can't be sent as one`() {
        val odd = ChatAttachment("/root/Shared/a\nb.txt", "a\nb.txt", image = false)
        assertEquals("hi\n\n[Attached file: /root/Shared/a b.txt]", promptWithAttachments("hi", listOf(odd)))
    }

    @Test
    fun `a thumbnail decodes at the largest halving that still covers its square`() {
        // A 12 MP photo for a 96 px square: its short side halves five times to 94, so four (189).
        assertEquals(16, thumbnailSampleSize(4032, 3024, 96))
        assertEquals(16, thumbnailSampleSize(3024, 4032, 96))
        assertEquals(2, thumbnailSampleSize(200, 1000, 96))
        assertEquals(2, thumbnailSampleSize(192, 192, 96))
        assertEquals(1, thumbnailSampleSize(191, 191, 96))
        assertEquals(1, thumbnailSampleSize(50, 40, 96))
        // A size the decoder couldn't read.
        assertEquals(1, thumbnailSampleSize(-1, -1, 96))
        assertEquals(1, thumbnailSampleSize(4032, 3024, 0))
    }
}
