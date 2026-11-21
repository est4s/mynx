package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextDocTest {
    private fun load(text: String): TextLoad.Text = load(text.toByteArray())

    private fun load(bytes: ByteArray, size: Long = bytes.size.toLong()): TextLoad.Text =
        assertIs<TextLoad.Text>(loadText(bytes, size))

    private fun roundTrip(bytes: ByteArray) {
        val loaded = load(bytes)
        assertContentEquals(bytes, saveText(loaded, loaded.text), "kept as found: ${String(bytes)}")
    }

    @Test
    fun `plain text shows without its final newline, which comes back on save`() {
        val loaded = load("one\ntwo\n")
        assertEquals("one\ntwo", loaded.text)
        assertEquals(LineEnding.LF, loaded.lineEnding)
        assertTrue(loaded.finalNewline)
        assertFalse(loaded.bom)
        assertFalse(loaded.truncated)
        assertNull(loaded.readOnlyReason)
        assertEquals("one\ntwo\nthree\n", String(saveText(loaded, "one\ntwo\nthree")))
    }

    @Test
    fun `no final newline stays without one`() {
        val loaded = load("a\nb")
        assertFalse(loaded.finalNewline)
        assertEquals("a\nb", loaded.text)
        assertEquals("a\nbc", String(saveText(loaded, "a\nbc")))
    }

    @Test
    fun `CRLF shows as LF and is saved as CRLF`() {
        val loaded = load("a\r\nb\r\n")
        assertEquals("a\nb", loaded.text)
        assertEquals(LineEnding.CRLF, loaded.lineEnding)
        assertEquals("a\r\nb\r\nc\r\n", String(saveText(loaded, "a\nb\nc")))
        assertEquals("x\r\ny\r\n", String(saveText(loaded, "x\r\ny")), "pasted CRLF isn't doubled")
    }

    @Test
    fun `mixed endings show as LF and save with the more common one`() {
        val loaded = load("a\r\nb\r\nc\nd")
        assertEquals("a\nb\nc\nd", loaded.text)
        assertEquals(LineEnding.CRLF, loaded.lineEnding)
        assertEquals(LineEnding.LF, load("a\nb\nc\r\n").lineEnding)
    }

    @Test
    fun `a BOM is hidden and kept`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hé\n".toByteArray()
        val loaded = load(bytes)
        assertTrue(loaded.bom)
        assertEquals("hé", loaded.text)
        roundTrip(bytes)
    }

    @Test
    fun `files are saved byte for byte as found`() {
        for (text in listOf("", "\n", "\n\n", "a", "a\n", "a\r\n", "x\r\n\r\ny", "ü € 😀\n", "tab\there\n")) {
            roundTrip(text.toByteArray())
        }
    }

    @Test
    fun `an empty file is empty text`() {
        val loaded = load("")
        assertEquals("", loaded.text)
        assertFalse(loaded.finalNewline)
    }

    @Test
    fun `binary isn't text`() {
        val nul = loadText(byteArrayOf(0x61, 0, 0x62), 3)
        assertIs<TextLoad.NotText>(nul)
        assertTrue(nul.reason.contains("isn't text"))
        assertIs<TextLoad.NotText>(loadText(byteArrayOf(0x61, 0xC3.toByte(), 0x28), 3), "invalid UTF-8")
        assertIs<TextLoad.NotText>(loadText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0), 4), "UTF-16")
    }

    @Test
    fun `a file over 1 MB shows its first MB, cut at a line, read-only`() {
        val line = "0123456789abcdé\n".toByteArray()
        val bytes = ByteArray(TEXT_LIMIT + 5000) { line[it % line.size] }
        val loaded = load(bytes, size = 3_000_000)
        assertTrue(loaded.truncated)
        assertTrue(loaded.readOnly)
        assertTrue(loaded.readOnlyReason!!.contains("1 MB"))
        val shown = loaded.text.toByteArray().size + 1
        assertTrue(shown <= TEXT_LIMIT && shown > TEXT_LIMIT - line.size, "cut at the last whole line: $shown")
        assertTrue(loaded.text.endsWith("é"))
    }

    @Test
    fun `a long file with no newlines is cut at a whole character`() {
        val bytes = "é".toByteArray().let { e -> ByteArray(TEXT_LIMIT + 1) { e[it % 2] } }
        val loaded = load(bytes, size = bytes.size.toLong())
        assertTrue(loaded.truncated)
        assertEquals(TEXT_LIMIT / 2, loaded.text.length)
        val odd = ByteArray(TEXT_LIMIT + 10) { if (it == 0) 'a'.code.toByte() else "é".toByteArray()[(it - 1) % 2] }
        assertTrue(load(odd, odd.size.toLong()).text.all { it == 'a' || it == 'é' })
    }

    @Test
    fun `a document knows when it's changed and when it was saved`() {
        val doc = TextDoc(load("a\n"), modified = 1000L)
        assertFalse(doc.dirty)
        assertFalse(doc.readOnly)
        val edited = doc.edited("ab")
        assertTrue(edited.dirty)
        assertEquals("a", edited.savedText)
        assertFalse(edited.edited("a").dirty)
        val saved = edited.saved(modified = 2000L)
        assertFalse(saved.dirty)
        assertEquals(2000L, saved.modified)
        assertTrue(saved.changedOnDisk(3000L))
        assertFalse(saved.changedOnDisk(2000L))
        assertEquals("ab\n", String(saveText(edited)))
    }

    @Test
    fun `saving runs cat into the file in place and removes the temp file`() {
        val job = assertIs<FileJob.Run>(saveJob("/tmp/.mynx/saves/1-a b.txt", "/root/it's here.txt"))
        assertEquals(
            listOf("/bin/sh", "-c", "cat \"\$1\" > \"\$2\" && rm -f \"\$1\"", "mynx-save", "/tmp/.mynx/saves/1-a b.txt", "/root/it's here.txt"),
            job.argv,
        )
        assertEquals("Saving it's here.txt", job.title)
        assertIs<FileJob.Refused>(saveJob("/tmp/.mynx/saves/x", ""))
    }

    @Test
    fun `save temp files go in the saves folder with a safe name`() {
        assertEquals("/tmp/.mynx/saves/42-notes.md", saveTempPath("/root/notes.md", 42L))
        assertEquals("/tmp/.mynx/saves/7-a_b_c", saveTempPath("/root/a b\nc", 7L))
        assertEquals("/tmp/.mynx/saves/7-file", saveTempPath("/", 7L))
    }

    @Test
    fun `find ignores case and doesn't overlap`() {
        assertEquals(listOf(0..1, 3..4, 6..7), findMatches("Ab ab AB", "ab"))
        assertEquals(listOf(0..1, 2..3), findMatches("aaaa", "aa"))
        assertEquals(emptyList(), findMatches("abc", ""))
        assertEquals(listOf(1..1), findMatches("xÉx", "é"))
    }

    @Test
    fun `replacing a match changes just it and goes on to the next`() {
        val text = "ab Ab ab"
        val done = replaceMatch(text, findMatches(text, "ab"), 1, "xyz")
        assertEquals("ab xyz ab", done.text)
        assertEquals(TextEdit(3, "Ab", "xyz"), done.edit)
        assertEquals(1, done.count)
        assertEquals(6, done.cursor)
        assertEquals(1, done.next, "the match after it is now the second")
        // The last one: back round to the first.
        assertEquals(0, replaceMatch(text, findMatches(text, "ab"), 2, "q").next)
    }

    @Test
    fun `a replacement holding the query isn't found again at once`() {
        val text = "a a"
        val done = replaceMatch(text, findMatches(text, "a"), 0, "aa")
        assertEquals("aa a", done.text)
        assertEquals(2, done.next, "past the replacement: the old second match")
    }

    @Test
    fun `the match at or after an offset, else the first`() {
        val matches = listOf(0..1, 5..6, 9..9)
        assertEquals(1, matchAtOrAfter(matches, 2))
        assertEquals(1, matchAtOrAfter(matches, 5))
        assertEquals(0, matchAtOrAfter(matches, 10))
        assertEquals(-1, matchAtOrAfter(emptyList(), 0))
    }

    @Test
    fun `replace all changes every match, case ignored, as one edit`() {
        val done = replaceAll("x Foo foo y FOO", "foo", "bar")
        assertEquals("x bar bar y bar", done?.text)
        assertEquals(3, done?.count)
        assertEquals(TextEdit(2, "Foo foo y FOO", "bar bar y bar"), done?.edit)
        assertEquals(15, done?.cursor)
        assertNull(replaceAll("abc", "z", "y"), "nothing to replace")
        assertNull(replaceAll("abc", "", "y"))
        assertEquals("", replaceAll("aaaa", "aa", "")?.text, "a replacement may be empty")
    }

    @Test
    fun `line starts number the lines`() {
        assertContentEquals(intArrayOf(0), lineStarts(""))
        assertContentEquals(intArrayOf(0, 2, 3), lineStarts("a\n\nb"))
        assertContentEquals(intArrayOf(0, 2), lineStarts("a\n"))
    }
}
