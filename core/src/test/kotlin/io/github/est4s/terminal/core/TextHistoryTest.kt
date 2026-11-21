package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextHistoryTest {
    @Test
    fun `an edit is what changed between two texts`() {
        assertEquals(TextEdit(6, "", "big "), textEdit("hello world", "hello big world"))
        assertEquals(TextEdit(1, "bc", ""), textEdit("abcd", "ad"))
        assertEquals(TextEdit(0, "a", "b"), textEdit("a", "b"))
        // Repeated letters: the change is found once, not twice.
        assertEquals(TextEdit(3, "", "a"), textEdit("aaa", "aaaa"))
        assertNull(textEdit("same", "same"))
    }

    @Test
    fun `an edit applies, and its inverse takes it back`() {
        val edit = textEdit("hello world", "hello big world")!!
        assertEquals("hello big world", edit.applyTo("hello world"))
        assertEquals("hello world", edit.inverse().applyTo("hello big world"))
        assertEquals(10, edit.end)
    }

    @Test
    fun `undo and redo step through the edits`() {
        val history = TextHistory("a", mergeMs = 0)
        assertFalse(history.canUndo)
        history.record("ab", now = 0)
        history.record("abc", now = 10)
        assertTrue(history.canUndo)
        val undo = history.undo()!!
        assertEquals("ab", history.text)
        assertEquals(TextEdit(2, "c", ""), undo)
        assertTrue(history.canRedo)
        history.undo()
        assertEquals("a", history.text)
        assertNull(history.undo())
        assertEquals(TextEdit(1, "", "b"), history.redo())
        assertEquals("ab", history.text)
        history.redo()
        assertEquals("abc", history.text)
        assertNull(history.redo())
    }

    @Test
    fun `typing in quick succession is one step`() {
        val history = TextHistory("", mergeMs = 1000)
        history.record("h", now = 0)
        history.record("hi", now = 300)
        history.record("hi!", now = 900)
        history.record("hi! x", now = 3000)
        history.undo()
        assertEquals("hi!", history.text)
        history.undo()
        assertEquals("", history.text)
        assertFalse(history.canUndo)
    }

    @Test
    fun `a merged step undoes as one edit`() {
        val history = TextHistory("one two", mergeMs = 1000)
        history.record("one 2two", now = 0)
        history.record("one 2", now = 100)
        assertEquals(TextEdit(4, "2", "two"), history.undo())
        assertEquals("one two", history.text)
    }

    @Test
    fun `a new edit after undo drops what could be redone`() {
        val history = TextHistory("a", mergeMs = 0)
        history.record("ab", now = 0)
        history.undo()
        history.record("ax", now = 10)
        assertFalse(history.canRedo)
        history.undo()
        assertEquals("a", history.text)
    }

    @Test
    fun `nothing merges across an undo or a break`() {
        val history = TextHistory("", mergeMs = 1000)
        history.record("a", now = 0)
        history.record("ab", now = 10)
        history.undo()
        history.record("x", now = 20)
        history.record("xy", now = 30)
        history.newStep()
        history.record("xyz", now = 40)
        history.undo()
        assertEquals("xy", history.text)
        history.undo()
        assertEquals("", history.text)
    }

    @Test
    fun `recording the text it already has changes nothing`() {
        val history = TextHistory("a", mergeMs = 0)
        history.record("ab", now = 0)
        history.undo()
        history.record("a", now = 5)
        assertTrue(history.canRedo)
    }

    @Test
    fun `old steps go when the history holds too much`() {
        val history = TextHistory("", mergeMs = 0, maxChars = 10)
        history.record("12345", now = 0)
        history.record("1234567890", now = 1)
        history.record("1234567890abcde", now = 2)
        history.undo()
        history.undo()
        assertEquals("12345", history.text)
        assertFalse(history.canUndo)
    }
}
