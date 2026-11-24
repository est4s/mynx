package io.github.est4s.terminal

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.chatTitle

/** What a chat row's long press can do, in the history and in the drawer. */
interface ChatRowActions {
    /** Pins [row] to the top of the lists ([on]), or takes it off. */
    fun pinChat(row: ChatHistoryRow, on: Boolean)
    /** Names [row] [title] (already checked with [chatTitle]). */
    fun renameChat(row: ChatHistoryRow, title: String)
    /** Deletes [row] for good (already confirmed). */
    fun deleteChat(row: ChatHistoryRow)
}

/** A pinned row's mark, at its end. */
internal fun pinMark(context: Context) = ImageView(context).apply {
    setImageDrawable(Pixel.icon(context, PixelIcon.PIN, Pixel.DIM, 14))
    scaleType = ImageView.ScaleType.FIT_CENTER
    contentDescription = "Pinned"
}

/** A chat row's long press, the app's own [pixelMenu] dropped from [anchor] (the row): Pin (or Unpin), Rename, Delete (after a confirmation). */
internal fun showChatRowMenu(anchor: View, row: ChatHistoryRow, actions: ChatRowActions) {
    val context = anchor.context
    pixelMenu(anchor, listOf(
        PixelMenuEntry(if (row.pinned) "Unpin" else "Pin") { actions.pinChat(row, !row.pinned) },
        PixelMenuEntry("Rename") { askChatName(context, row, actions) },
        PixelMenuEntry("Delete") { confirmChatDelete(context, row, actions) },
    ))
}

private fun confirmChatDelete(context: Context, row: ChatHistoryRow, actions: ChatRowActions) {
    pixelDialog(
        context, "Delete chat?",
        message = "\"${row.title}\" and its transcript go for good. This can't be undone.",
        ok = "Delete", danger = true,
    ) { actions.deleteChat(row) }
}

// A text field in a pixel dialog: Rename only while the name isn't blank.
private fun askChatName(context: Context, row: ChatHistoryRow, actions: ChatRowActions) {
    val field = EditText(context).apply {
        setText(row.title)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        selectAll()
    }
    val dialog = pixelDialog(context, "Rename chat", field = field, ok = "Rename") {
        chatTitle(field.text.toString())?.let { actions.renameChat(row, it) }
    }
    fun check() = dialog.setOkEnabled(chatTitle(field.text.toString()) != null)
    check()
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            check()
        }
    })
    field.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
        dialog.ok()
        true
    }
}
