package io.github.est4s.terminal.core

/** [removed] at [start] became [inserted]. */
data class TextEdit(val start: Int, val removed: String, val inserted: String) {
    /** Where [inserted] ends: the cursor after the edit. */
    val end: Int get() = start + inserted.length

    fun applyTo(text: String): String = text.substring(0, start) + inserted + text.substring(start + removed.length)

    fun inverse(): TextEdit = TextEdit(start, inserted, removed)
}

/** What changed from [before] to [after], as one edit (the common start and end left out); null if nothing. */
fun textEdit(before: String, after: String): TextEdit? {
    if (before == after) return null
    var start = 0
    val shorter = minOf(before.length, after.length)
    while (start < shorter && before[start] == after[start]) start++
    var end = 0
    while (end < shorter - start && before[before.length - 1 - end] == after[after.length - 1 - end]) end++
    return TextEdit(start, before.substring(start, before.length - end), after.substring(start, after.length - end))
}

/**
 * The editor's Undo and Redo: the text and the edits that led to it, kept
 * as edits (not copies) so a 1 MB file's history stays small. Edits
 * recorded within [mergeMs] of the last are one step, as typing a word is;
 * [newStep] ends a step early. Past [maxChars] of edits, the oldest go.
 */
class TextHistory(text: String, private val mergeMs: Long = 1000, private val maxChars: Int = 2_000_000) {
    var text: String = text
        private set

    private val undos = ArrayDeque<TextEdit>()
    private val redos = ArrayDeque<TextEdit>()
    private var lastRecord = Long.MIN_VALUE
    private var merging = false
    private var held = 0

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** The text is now [next] (the user typed), at [now] (ms). */
    fun record(next: String, now: Long) {
        val edit = textEdit(text, next) ?: return
        redos.clear()
        if (merging && undos.isNotEmpty() && now - lastRecord < mergeMs) {
            val last = undos.removeLast()
            held -= size(last)
            val before = last.inverse().applyTo(text)
            undos.addLast(textEdit(before, next) ?: return)
        } else {
            undos.addLast(edit)
        }
        held += size(undos.last())
        while (held > maxChars && undos.size > 1) held -= size(undos.removeFirst())
        text = next
        lastRecord = now
        merging = true
    }

    /** Ends the step being typed: the next edit starts a new one. */
    fun newStep() {
        merging = false
    }

    /** Takes the last step back; the edit that does it to the text (already in [text]), or null if there's none. */
    fun undo(): TextEdit? {
        val step = undos.removeLastOrNull() ?: return null
        held -= size(step)
        redos.addLast(step)
        merging = false
        return step.inverse().also { text = it.applyTo(text) }
    }

    /** Does the last undone step again; as [undo]. */
    fun redo(): TextEdit? {
        val step = redos.removeLastOrNull() ?: return null
        undos.addLast(step)
        held += size(step)
        merging = false
        return step.also { text = it.applyTo(text) }
    }

    private fun size(edit: TextEdit) = edit.removed.length + edit.inserted.length
}
