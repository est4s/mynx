package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.ColorScheme
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.SyntaxSpan
import io.github.est4s.terminal.core.TextDoc
import io.github.est4s.terminal.core.TextHistory
import io.github.est4s.terminal.core.codeLanguageOf
import io.github.est4s.terminal.core.TextEdit
import io.github.est4s.terminal.core.findMatches
import io.github.est4s.terminal.core.replaceAll
import io.github.est4s.terminal.core.replaceMatch
import io.github.est4s.terminal.core.highlight
import io.github.est4s.terminal.core.lineStarts
import io.github.est4s.terminal.core.mdRows
import io.github.est4s.terminal.core.parseMarkdownFile
import io.github.est4s.terminal.core.syntaxLanguage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.ceil

private val VIEWER_WORKER: Executor = Executors.newSingleThreadExecutor { work -> Thread(work, "text-viewer").apply { isDaemon = true } }
// Past this, text isn't highlighted: a TextView with spans over megabytes lays out slowly.
private const val HIGHLIGHT_LIMIT = 256 * 1024
// Highlighting again waits for typing to stop this long.
private const val HIGHLIGHT_DELAY_MS = 300L
// Line numbers are counted on every key while the text is this small; past it, with the highlighting.
private const val QUICK_LINES_LIMIT = 100_000
private const val CODE_SP = 14f

/** What the text viewer asks of its activity. */
interface TextViewerActions {
    /** ← or Back, with nothing left unsaved. */
    fun close()

    /**
     * Saves [doc]'s text; the activity then calls [TextViewerView.saved],
     * [TextViewerView.saveFailed] or [TextViewerView.saveCancelled].
     */
    fun save(doc: TextDoc)
    fun share()
    fun openWithAnotherApp()

    /** A link or picture in a Markdown file, its URL as written (relative to the file). */
    fun openLink(url: String)

    /** A Markdown file's picture, its URL as written; [done] on the main thread, null if it can't be shown. */
    fun loadImage(url: String, done: (Bitmap?) -> Unit)
}

/** A find match's background; the current one's is brighter. */
private class FindSpan(color: Int) : BackgroundColorSpan(color)

/**
 * The text viewer's screen (`TextViewActivity`, docs/TOUCH-DESIGN.md "The
 * text viewer") for the Debian file [path] of [kind] (text, code or
 * Markdown): View shows it read-only (code in [scheme]'s colours, line
 * numbered; Markdown drawn by [MarkdownViews]), Edit in an [EditText] with
 * the same highlighting, run on [worker] after typing stops. Saving and
 * everything outside the screen are [actions]'. The model is core's
 * ([TextDoc], [TextHistory], [findMatches]).
 *
 * Undo, Redo and Save sit in a row under the top bar while editing: a
 * phone's top bar has no room for them beside the name and the switch.
 */
@SuppressLint("ViewConstructor")
class TextViewerView(
    context: Context,
    private val path: String,
    private val kind: FileKind,
    private val scheme: ColorScheme,
    private val actions: TextViewerActions,
    private val worker: Executor = VIEWER_WORKER,
) : LinearLayout(context) {
    /** The file as shown, with the text being edited; null until it's loaded. */
    var doc: TextDoc? = null
        private set

    /** Whether it's in Edit mode. */
    var editing = false
        private set

    private val name = path.trimEnd('/').substringAfterLast('/')
    private val language: String? = when (kind) {
        FileKind.MARKDOWN -> "markdown"
        else -> codeLanguageOf(name)?.let(::syntaxLanguage)
    }
    private var wrap = kind != FileKind.CODE
    private var history: TextHistory? = null
    private var saving = false
    private var afterSave: (() -> Unit)? = null
    // Set while the view changes the editor's text itself (undo, loading): not the user typing.
    private var applying = false
    // Counts texts handed to the worker, so an old result is dropped.
    private var generation = 0

    private val nameText = TextView(context).apply {
        typeface = Pixel.label(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val folderText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Pixel.DIM)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        text = path.substringBeforeLast('/', "").ifEmpty { "/" }.replaceFirst(Regex("^/root(?=/|$)"), "~")
    }
    private val editButton = barButton("Edit") { startEditing() }
    private val viewSwitch = pixelSegment(context, "View") { setEditing(false) }
    private val editSwitch = pixelSegment(context, "Edit") { startEditing() }
    private val modeSwitch = pixelSegments(context).apply {
        addView(viewSwitch, LayoutParams(WRAP_CONTENT, dp(40)))
        addView(editSwitch, LayoutParams(WRAP_CONTENT, dp(40)))
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(4), 0, dp(4), 0)
        addView(iconButton(PixelIcon.BACK, Pixel.CYAN, "Back") { back() }, LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(6), dp(8), dp(6))
            addView(nameText)
            addView(folderText)
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(if (kind == FileKind.MARKDOWN) modeSwitch else editButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(iconButton(PixelIcon.MORE, Pixel.TEXT, "More") { showMore(it) }, LayoutParams(dp(48), dp(52)))
    }

    private val undoButton = glyphButton("↶", "Undo") { undo() }
    private val redoButton = glyphButton("↷", "Redo") { redo() }
    private val saveButton = barButton("Save", main = true) { requestSave() }
    private val editRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(12), dp(6))
        addView(undoButton, LayoutParams(dp(52), dp(44)))
        addView(redoButton, LayoutParams(dp(52), dp(44)))
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(saveButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        visibility = View.GONE
    }

    // The read-only reason, or why saving failed.
    private val notice = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextIsSelectable(true)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE)
        visibility = View.GONE
    }

    private val scroll = ViewerScroll(context).apply {
        isFillViewport = true
        Pixel.scrollbar(this)
    }
    private val messageText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.DIM)
        gravity = Gravity.CENTER
        setTextIsSelectable(true)
    }
    private val otherAppButton = barButton("Open with another app", main = true) { actions.openWithAnotherApp() }
    private val message = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Pixel.BACKGROUND)
        addView(messageText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(otherAppButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }
    private val content = FrameLayout(context).apply {
        addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private val codeText = TextView(context).apply {
        styleCode(this)
        setTextIsSelectable(true)
    }
    private val editor = EditText(context).apply {
        styleCode(this)
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            (if (kind == FileKind.CODE) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        highlightColor = (Pixel.CYAN and 0x00FFFFFF) or 0x55000000
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(Pixel.CYAN)
                setSize(dp(2), 0)
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!applying && s != null) typed(s)
            }
        })
    }
    private val codeNumbers = LineNumbers(context, codeText)
    private val editorNumbers = LineNumbers(context, editor)
    private val markdownColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }
    private val markdown = MarkdownViews(
        context,
        Pixel.COLORS,
        { color, sp, links -> markdownText(color, sp, links) },
        { url -> actions.openLink(url) },
        scheme,
        { url, done -> actions.loadImage(url, done) },
    )

    private val findInput = EditText(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.TEXT)
        setHintTextColor(Pixel.DIM)
        hint = "Find"
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = null
        setPadding(dp(12), 0, dp(4), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = find(0)
        })
        setOnEditorActionListener { _, _, _ ->
            step(1)
            true
        }
    }
    private val findCount = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Pixel.DIM)
        setPadding(dp(4), 0, dp(4), 0)
    }
    private val replaceToggle = glyphButton("⇄", "Replace") { toggleReplace() }
    private val replaceInput = EditText(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.TEXT)
        setHintTextColor(Pixel.DIM)
        hint = "Replace with"
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = null
        setPadding(dp(12), 0, dp(4), 0)
        setOnEditorActionListener { _, _, _ ->
            replaceCurrent()
            true
        }
    }
    private val replaceRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(replaceInput, LayoutParams(0, dp(48), 1f))
        addView(smallButton("Replace") { replaceCurrent() }, LayoutParams(WRAP_CONTENT, dp(48)))
        addView(smallButton("All") { replaceEvery() }, LayoutParams(WRAP_CONTENT, dp(48)))
        visibility = View.GONE
    }
    private val findBar = LinearLayout(context).apply {
        orientation = VERTICAL
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE)
        setPadding(dp(4), dp(2), dp(4), dp(2))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(findInput, LayoutParams(0, dp(48), 1f))
            addView(findCount, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(glyphButton("↑", "Previous match") { step(-1) }, LayoutParams(dp(40), dp(48)))
            addView(glyphButton("↓", "Next match") { step(1) }, LayoutParams(dp(40), dp(48)))
            addView(replaceToggle, LayoutParams(dp(40), dp(48)))
            addView(iconButton(PixelIcon.CLOSE, Pixel.TEXT, "Close find", 16) { closeFind() }, LayoutParams(dp(40), dp(48)))
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(replaceRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        visibility = View.GONE
    }
    private var matches: List<Pair<TextView, IntRange>> = emptyList()
    private var current = -1

    private val highlightLater = Runnable { highlightEditor() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(MATCH_PARENT, dp(2)))
        addView(editRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(notice, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(content, LayoutParams(MATCH_PARENT, 0, 1f))
        addView(findBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        showMessage("Loading…", otherApp = false)
        renderBar()
    }

    /** Shows [loaded]: in Edit if [edit] and it can be edited. Again after a reload. */
    fun showDoc(loaded: TextDoc, edit: Boolean) {
        doc = loaded
        history = null
        saving = false
        afterSave = null
        message.visibility = View.GONE
        showNotice(loaded.load.readOnlyReason, error = false)
        editing = edit && !loaded.readOnly
        if (editing) fillEditor(loaded.text)
        renderBody()
        renderBar()
    }

    /** The file isn't text: [reason], and Open with another app. */
    fun showNotText(reason: String) {
        doc = null
        showMessage(reason, otherApp = true)
        renderBar()
    }

    /** The file can't be read: [text] says why. */
    fun showError(text: String) {
        doc = null
        showMessage(text, otherApp = false)
        renderBar()
    }

    /** The save of [text] went through; the file's time is now [modified]. */
    fun saved(text: String, modified: Long) {
        val shown = doc ?: return
        doc = shown.copy(savedText = text, modified = modified)
        saving = false
        history?.newStep()
        showNotice(shown.load.readOnlyReason, error = false)
        renderBar()
        afterSave?.also { afterSave = null }?.invoke()
    }

    fun saveFailed(why: String) {
        saving = false
        afterSave = null
        showNotice(why, error = true)
        renderBar()
    }

    /** Saving was called off (the file changed on disk, and the user said Cancel). */
    fun saveCancelled() {
        saving = false
        afterSave = null
        renderBar()
    }

    /** Back: closes Find, else asks about unsaved changes, else closes. */
    fun back() {
        when {
            findBar.visibility == View.VISIBLE -> closeFind()
            doc?.dirty == true -> askUnsaved { actions.close() }
            else -> actions.close()
        }
    }

    /** Ctrl+S, Ctrl+F, Ctrl+H and Esc; true when it was one. */
    fun onKey(event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val key = event.keyCode
        val ours = (ctrl && (key == KeyEvent.KEYCODE_S || key == KeyEvent.KEYCODE_F || key == KeyEvent.KEYCODE_H)) ||
            key == KeyEvent.KEYCODE_ESCAPE
        if (!ours) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        when {
            key == KeyEvent.KEYCODE_S -> requestSave()
            key == KeyEvent.KEYCODE_F -> openFind()
            key == KeyEvent.KEYCODE_H -> openReplace()
            findBar.visibility == View.VISIBLE -> closeFind()
            editing -> leaveEditing()
            else -> back()
        }
        return true
    }

    // Modes

    private fun startEditing() {
        val shown = doc ?: return
        if (shown.readOnly) {
            toast(shown.load.readOnlyReason ?: "This file can't be edited here.")
            return
        }
        setEditing(true)
    }

    private fun setEditing(on: Boolean) {
        val shown = doc ?: return
        if (on == editing) return
        editing = on
        if (on) {
            fillEditor(shown.text)
        } else {
            removeCallbacks(highlightLater)
            replaceRow.visibility = View.GONE
            styleReplaceToggle()
        }
        renderBody()
        renderBar()
        if (on) {
            editor.requestFocus()
            editor.setSelection(0)
        }
    }

    // Esc in Edit: back to View, asking first if there's anything unsaved.
    private fun leaveEditing() {
        if (doc?.dirty == true) askUnsaved { setEditing(false) } else setEditing(false)
    }

    private fun askUnsaved(then: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Save changes to $name?")
            .setMessage("Your changes aren't saved yet.")
            .setPositiveButton("Save") { _, _ ->
                afterSave = then
                requestSave(keepAfter = true)
            }
            .setNegativeButton("Discard") { _, _ ->
                discard()
                then()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun discard() {
        val shown = doc ?: return
        doc = shown.edited(shown.savedText)
        history = null
        if (editing) fillEditor(shown.savedText)
        renderBody()
        renderBar()
    }

    private fun requestSave(keepAfter: Boolean = false) {
        if (!keepAfter) afterSave = null
        val shown = doc ?: return
        if (shown.readOnly || saving) return
        if (!shown.dirty) {
            afterSave?.also { afterSave = null }?.invoke()
            return
        }
        saving = true
        renderBar()
        actions.save(shown)
    }

    // Drawing

    private fun renderBar() {
        val shown = doc
        val dirty = shown?.dirty == true
        nameText.text = if (dirty) "• $name" else name
        val canEdit = shown != null && !shown.readOnly
        editButton.visibility = if (shown != null && !editing) View.VISIBLE else View.GONE
        editButton.alpha = if (canEdit) 1f else 0.5f
        modeSwitch.visibility = if (shown != null) View.VISIBLE else View.GONE
        stylePixelSegment(viewSwitch, !editing)
        stylePixelSegment(editSwitch, editing)
        editSwitch.alpha = if (canEdit) 1f else 0.5f
        editRow.visibility = if (editing || dirty) View.VISIBLE else View.GONE
        undoButton.visibility = if (editing) View.VISIBLE else View.INVISIBLE
        redoButton.visibility = if (editing) View.VISIBLE else View.INVISIBLE
        undoButton.alpha = if (history?.canUndo == true) 1f else 0.35f
        redoButton.alpha = if (history?.canRedo == true) 1f else 0.35f
        saveButton.text = if (saving) "Saving…" else "Save"
        saveButton.alpha = if (dirty && !saving) 1f else 0.5f
    }

    private fun renderBody() {
        val shown = doc ?: return
        clearFind()
        scroll.removeAllViews()
        when {
            editing -> scroll.addView(codeRow(editor, editorNumbers, scrolls = false))
            kind == FileKind.MARKDOWN -> {
                scroll.setBackgroundColor(Pixel.BACKGROUND)
                markdown.fill(markdownColumn, mdRows(parseMarkdownFile(shown.text)))
                scroll.addView(markdownColumn)
            }
            else -> {
                codeText.setText(SpannableString(shown.text), TextView.BufferType.SPANNABLE)
                codeNumbers.starts = lineStarts(shown.text)
                scroll.addView(codeRow(codeText, codeNumbers, scrolls = !wrap))
                highlightView(shown.text)
            }
        }
        if (editing || kind != FileKind.MARKDOWN) scroll.setBackgroundColor(scheme.background)
        scroll.follow = editing
        scroll.scrollTo(0, 0)
        if (findBar.visibility == View.VISIBLE) find(0)
    }

    // The line numbers beside [text], which scrolls sideways itself unless it wraps.
    private fun codeRow(text: TextView, numbers: LineNumbers, scrolls: Boolean): View {
        (text.parent as? ViewGroup)?.removeView(text)
        (numbers.parent as? ViewGroup)?.removeView(numbers)
        text.setHorizontallyScrolling(!wrap && text is EditText)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(numbers, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        if (scrolls) {
            row.addView(MdSideScroll(context).apply {
                isFillViewport = true
                addView(text, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }, LayoutParams(0, MATCH_PARENT, 1f))
        } else {
            row.addView(text, LayoutParams(0, MATCH_PARENT, 1f))
        }
        return row
    }

    private fun fillEditor(text: String) {
        applying = true
        editor.setText(text)
        applying = false
        history = TextHistory(text)
        editorNumbers.starts = lineStarts(text)
        highlightEditor()
    }

    private fun typed(text: Editable) {
        val shown = doc ?: return
        val now = text.toString()
        val kept = history
        if (kept == null) history = TextHistory(now) else kept.record(now, SystemClock.uptimeMillis())
        doc = shown.edited(now)
        if (now.length < QUICK_LINES_LIMIT) editorNumbers.starts = lineStarts(now)
        removeCallbacks(highlightLater)
        postDelayed(highlightLater, HIGHLIGHT_DELAY_MS)
        renderBar()
        if (findBar.visibility == View.VISIBLE) find(current.coerceAtLeast(0))
    }

    private fun undo() {
        val edit = history?.undo() ?: return
        applyEdit(edit.start, edit.removed.length, edit.inserted, edit.end)
    }

    private fun redo() {
        val edit = history?.redo() ?: return
        applyEdit(edit.start, edit.removed.length, edit.inserted, edit.end)
    }

    private fun applyEdit(start: Int, length: Int, inserted: String, cursor: Int) {
        val shown = doc ?: return
        val text = editor.text
        applying = true
        text.replace(start, start + length, inserted)
        applying = false
        val now = text.toString()
        // An IME's composing text can leave the two apart: start the history again from what's there.
        if (now != history?.text) history = TextHistory(now)
        doc = shown.edited(now)
        editor.setSelection(cursor.coerceIn(0, text.length))
        editorNumbers.starts = lineStarts(now)
        removeCallbacks(highlightLater)
        postDelayed(highlightLater, HIGHLIGHT_DELAY_MS)
        renderBar()
    }

    private fun highlightView(text: String) {
        val lang = language ?: return
        if (text.length > HIGHLIGHT_LIMIT) return
        val ticket = ++generation
        worker.execute {
            val spans = highlight(text, lang)
            post {
                if (ticket != generation || editing) return@post
                val shown = codeText.text as? Spannable ?: return@post
                if (shown.length == text.length) applySyntax(shown, spans, scheme)
            }
        }
    }

    private fun highlightEditor() {
        val text = editor.text.toString()
        val ticket = ++generation
        val lang = language
        worker.execute {
            val spans = if (lang == null || text.length > HIGHLIGHT_LIMIT) emptyList<SyntaxSpan>() else highlight(text, lang)
            val starts = lineStarts(text)
            post {
                if (ticket != generation || !editing || editor.text.toString() != text) return@post
                applySyntax(editor.text, spans, scheme)
                editorNumbers.starts = starts
            }
        }
    }

    private fun showMessage(text: String, otherApp: Boolean) {
        messageText.text = text
        otherAppButton.visibility = if (otherApp) View.VISIBLE else View.GONE
        message.visibility = View.VISIBLE
    }

    private fun showNotice(text: String?, error: Boolean) {
        notice.text = text.orEmpty()
        notice.setTextColor(if (error) Pixel.PINK_TEXT else Pixel.DIM)
        notice.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ⋮

    private fun showMore(anchor: View) {
        val shown = doc
        val entries = ArrayList<PixelMenuEntry>()
        if (editing && kind != FileKind.MARKDOWN) entries += PixelMenuEntry("Stop editing") { leaveEditing() }
        if (shown != null) {
            entries += PixelMenuEntry(if (wrap) "Wrap lines  ✓" else "Wrap lines") { toggleWrap() }
            entries += PixelMenuEntry("Find") { openFind() }
            if (!shown.readOnly) entries += PixelMenuEntry("Replace") { openReplace() }
        }
        entries += PixelMenuEntry("Share") { actions.share() }
        entries += PixelMenuEntry("Open with…") { actions.openWithAnotherApp() }
        entries += PixelMenuEntry("Copy path") {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("path", path))
            toast("Copied $path")
        }
        pixelMenu(anchor, entries)
    }

    private fun toggleWrap() {
        wrap = !wrap
        if (editing) {
            editor.setHorizontallyScrolling(!wrap)
            editor.requestLayout()
        } else {
            renderBody()
        }
    }

    // Find

    private fun openFind() {
        val shown = doc ?: return
        findBar.visibility = View.VISIBLE
        replaceToggle.visibility = if (shown.readOnly) View.GONE else View.VISIBLE
        findInput.requestFocus()
        findInput.selectAll()
        find(0)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        replaceRow.visibility = View.GONE
        clearFind()
        if (editing) editor.requestFocus()
    }

    /** Types [query] into Find and [replacement] into Replace: for the screenshots. */
    fun typeFind(query: String, replacement: String = "") {
        findInput.setText(query)
        replaceInput.setText(replacement)
    }

    // Replace (⋮, Ctrl+H or ⇄): Find with a second row. Replacing changes the text, so it's in Edit.
    private fun openReplace() {
        val shown = doc ?: return
        if (shown.readOnly) {
            toast(shown.load.readOnlyReason ?: "This file can't be edited here.")
            return
        }
        val searching = findBar.visibility == View.VISIBLE && findInput.text.isNotEmpty()
        if (!editing) setEditing(true)
        if (findBar.visibility != View.VISIBLE) openFind()
        replaceRow.visibility = View.VISIBLE
        styleReplaceToggle()
        if (searching) replaceInput.requestFocus() else findInput.requestFocus()
        find(current.coerceAtLeast(0))
    }

    private fun toggleReplace() {
        if (replaceRow.visibility == View.VISIBLE) {
            replaceRow.visibility = View.GONE
            styleReplaceToggle()
            findInput.requestFocus()
        } else {
            openReplace()
        }
    }

    private fun styleReplaceToggle() {
        replaceToggle.setTextColor(if (replaceRow.visibility == View.VISIBLE) Pixel.CYAN else Pixel.TEXT)
    }

    // Replace: the current match, then on to the next.
    private fun replaceCurrent() {
        if (!editing) return openReplace()
        if (matches.getOrNull(current)?.first !== editor) return
        val done = replaceMatch(editor.text.toString(), matches.map { it.second }, current, replaceInput.text.toString())
        replaceInEditor(done.edit)
        find(done.next.coerceAtLeast(0))
    }

    // All: every match, as one step to undo.
    private fun replaceEvery() {
        if (!editing) return openReplace()
        val query = findInput.text.toString()
        val done = replaceAll(editor.text.toString(), query, replaceInput.text.toString()) ?: return toast("No matches")
        replaceInEditor(done.edit)
        find(0)
        toast(if (done.count == 1) "Replaced 1" else "Replaced ${done.count}")
    }

    // Into the editor as typing goes (so Undo has it), as a step of its own.
    private fun replaceInEditor(edit: TextEdit) {
        history?.newStep()
        editor.text.replace(edit.start, edit.start + edit.removed.length, edit.inserted)
        history?.newStep()
    }

    private fun clearFind() {
        for (target in findTargets()) {
            val text = target.text as? Spannable ?: continue
            text.getSpans(0, text.length, FindSpan::class.java).forEach { text.removeSpan(it) }
        }
        matches = emptyList()
        current = -1
        findCount.text = ""
    }

    // What Find looks through: the editor, the code, or the Markdown's selectable text.
    private fun findTargets(): List<TextView> = when {
        editing -> listOf(editor)
        kind == FileKind.MARKDOWN -> ArrayList<TextView>().also { collectText(markdownColumn, it) }
        else -> listOf(codeText)
    }

    private fun collectText(view: View, out: MutableList<TextView>) {
        if (view is TextView && view.isTextSelectable) out += view
        if (view is ViewGroup) for (i in 0 until view.childCount) collectText(view.getChildAt(i), out)
    }

    // Finds the query again, from match [at] (kept when it's still there).
    private fun find(at: Int) {
        val query = findInput.text.toString()
        val targets = findTargets()
        for (target in targets) {
            val text = target.text as? Spannable ?: continue
            text.getSpans(0, text.length, FindSpan::class.java).forEach { text.removeSpan(it) }
        }
        matches = targets.flatMap { target -> findMatches(target.text.toString(), query).map { target to it } }
        current = if (matches.isEmpty()) -1 else at.coerceIn(0, matches.lastIndex)
        paintMatches()
    }

    private fun step(by: Int) {
        if (matches.isEmpty()) return
        current = ((current + by) % matches.size + matches.size) % matches.size
        paintMatches()
    }

    private fun paintMatches() {
        val query = findInput.text.toString()
        findCount.text = when {
            query.isEmpty() -> ""
            matches.isEmpty() -> "No matches"
            else -> "${current + 1}/${matches.size}"
        }
        val dim = (Pixel.CYAN and 0x00FFFFFF) or 0x40000000
        val bright = (Pixel.PINK and 0x00FFFFFF) or 0x99000000.toInt()
        matches.forEachIndexed { i, (target, range) ->
            val text = target.text as? Spannable ?: return@forEachIndexed
            text.getSpans(range.first, range.last + 1, FindSpan::class.java).forEach { text.removeSpan(it) }
            text.setSpan(FindSpan(if (i == current) bright else dim), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        matches.getOrNull(current)?.let { (target, range) -> reveal(target, range.first) }
    }

    // Scrolls so [offset] in [target] is in sight, a third of the way down.
    private fun reveal(target: TextView, offset: Int) {
        val layout = target.layout ?: return
        val line = layout.getLineForOffset(offset)
        var y = layout.getLineTop(line) + target.totalPaddingTop
        var view: View = target
        while (true) {
            val parent = view.parent as? View ?: return
            if (parent === scroll) break
            y += view.top
            if (parent is HorizontalScrollView) {
                val x = layout.getPrimaryHorizontal(offset).toInt() + target.totalPaddingLeft
                parent.scrollTo((x - parent.width / 3).coerceAtLeast(0), 0)
            }
            view = parent
        }
        if (target is EditText) target.bringPointIntoView(offset)
        scroll.smoothScrollTo(0, (y - scroll.height / 3).coerceAtLeast(0))
    }

    // Pieces

    private fun styleCode(view: TextView) {
        view.typeface = Pixel.text(context)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, CODE_SP)
        view.setTextColor(scheme.foreground)
        view.setLineSpacing(0f, 1.1f)
        view.setPadding(dp(8), dp(8), dp(16), dp(24))
    }

    private fun markdownText(color: Int, sp: Float, @Suppress("UNUSED_PARAMETER") links: Boolean) = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextIsSelectable(true)
        setOnTouchListener(MdLinkTaps())
    }

    private fun barButton(label: String, main: Boolean = false, onTap: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (main) Pixel.BACKGROUND else Pixel.TEXT)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        background = if (main) {
            Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
        } else {
            Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
        }
        isFocusable = true
        setOnClickListener { onTap() }
    }

    // A word to tap in the Replace row.
    private fun smallButton(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.CYAN)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = pixelRipple()
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun glyphButton(glyph: String, description: String, onTap: () -> Unit) = TextView(context).apply {
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(Pixel.TEXT)
        gravity = Gravity.CENTER
        contentDescription = description
        background = pixelRipple()
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun iconButton(icon: PixelIcon, color: Int, description: String, sizeDp: Int = 20, onTap: (View) -> Unit) = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, color, sizeDp))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onTap(it) }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = Pixel.dp(context, value)
}

// Selectable text takes the focus, and a ScrollView scrolls to what has it: a tap
// on a tall row would make it jump. The editor's cursor is followed, though.
private class ViewerScroll(context: Context) : ScrollView(context) {
    var follow = true

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (follow) super.computeScrollDeltaToGetChildRectOnScreen(rect) else 0
}

/**
 * A tap on an [MdLink] in selectable text: such text takes taps for its
 * selection and ignores a ClickableSpan's, so the link is found here.
 */
private class MdLinkTaps : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
        val row = view as? TextView ?: return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            android.view.MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
                val tap = kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop &&
                    event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout()
                if (tap && !row.hasSelection()) linkAt(row, event)?.onClick(row)
            }
        }
        return false
    }

    private fun linkAt(row: TextView, event: android.view.MotionEvent): MdLink? {
        val layout = row.layout ?: return null
        val x = event.x - row.totalPaddingLeft + row.scrollX
        val line = layout.getLineForVertical((event.y - row.totalPaddingTop + row.scrollY).toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        val text = row.text as? android.text.Spanned ?: return null
        return text.getSpans(offset, offset, MdLink::class.java).firstOrNull()
    }
}

/**
 * The line numbers beside [target], in its font, dimmed: each number at
 * its line's first row, so wrapped lines get one. Only what's in sight is
 * drawn. [starts] are core's `lineStarts` of the target's text.
 */
@SuppressLint("ViewConstructor")
private class LineNumbers(context: Context, private val target: TextView) : View(context) {
    var starts: IntArray = intArrayOf(0)
        set(value) {
            val longer = value.size.toString().length != field.size.toString().length
            field = value
            if (longer) requestLayout()
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val clip = Rect()

    init {
        setPadding(Pixel.dp(context, 8), 0, Pixel.dp(context, 4), 0)
        target.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.textSize = target.textSize
        paint.typeface = target.typeface
        val digits = maxOf(2, starts.size.toString().length)
        val width = ceil(paint.measureText("0".repeat(digits))).toInt() + paddingLeft + paddingRight
        setMeasuredDimension(width, View.getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val layout = target.layout ?: return
        paint.textSize = target.textSize
        paint.typeface = target.typeface
        paint.color = mixDim(target.currentTextColor)
        if (!canvas.getClipBounds(clip)) clip.set(0, 0, width, height)
        val top = target.totalPaddingTop - target.scrollY
        val firstRow = layout.getLineForVertical((clip.top - top).coerceAtLeast(0))
        val firstOffset = layout.getLineStart(firstRow)
        // The last line starting at or before the first row in sight.
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= firstOffset) lo = mid else hi = mid - 1
        }
        val textLength = target.text.length
        val x = (width - paddingRight).toFloat()
        var i = lo
        while (i < starts.size && starts[i] <= textLength) {
            val baseline = layout.getLineBaseline(layout.getLineForOffset(starts[i])) + top
            if (baseline - paint.textSize > clip.bottom) break
            canvas.drawText((i + 1).toString(), x, baseline.toFloat(), paint)
            i++
        }
    }

    // The text colour, halfway to see-through: numbers stay out of the way.
    private fun mixDim(color: Int) = (color and 0x00FFFFFF) or 0x73000000
}
