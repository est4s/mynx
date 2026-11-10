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
}
