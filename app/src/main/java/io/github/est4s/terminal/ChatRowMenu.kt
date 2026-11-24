package io.github.est4s.terminal

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
    AlertDialog.Builder(context)
        .setMessage("Delete \"${row.title}\"? Its transcript goes too; this can't be undone.")
        .setPositiveButton("Delete") { _, _ -> actions.deleteChat(row) }
        .setNegativeButton("Cancel", null)
        .show()
}

// A text field in a dialog, as Files' Rename: OK only while the name isn't blank.
private fun askChatName(context: Context, row: ChatHistoryRow, actions: ChatRowActions) {
    val field = EditText(context).apply {
        setText(row.title)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        selectAll()
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle("Rename chat")
        .setView(FrameLayout(context).apply {
            setPadding(Pixel.dp(context, 20), Pixel.dp(context, 8), Pixel.dp(context, 20), 0)
            addView(field)
        })
        .setPositiveButton("Rename") { _, _ -> chatTitle(field.text.toString())?.let { actions.renameChat(row, it) } }
        .setNegativeButton("Cancel", null)
        .create()
    fun check(): Boolean {
        val ok = chatTitle(field.text.toString()) != null
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = ok
        return ok
    }
    dialog.setOnShowListener { check() }
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            check()
        }
    })
    field.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
        if (check()) {
            dialog.dismiss()
            chatTitle(field.text.toString())?.let { actions.renameChat(row, it) }
        }
        true
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
    field.requestFocus()
}
