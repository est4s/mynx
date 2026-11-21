package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.ClipOp
import io.github.est4s.terminal.core.FILE_PLACES
import io.github.est4s.terminal.core.FileClip
import io.github.est4s.terminal.core.FileEntry
import io.github.est4s.terminal.core.FileJob
import io.github.est4s.terminal.core.chatRelativeTime
import io.github.est4s.terminal.core.deleteJob
import io.github.est4s.terminal.core.fileNameProblem
import io.github.est4s.terminal.core.filesBack
import io.github.est4s.terminal.core.fileSizeText
import io.github.est4s.terminal.core.folderCrumbs
import io.github.est4s.terminal.core.isPictureName
import io.github.est4s.terminal.core.newFolderJob
import io.github.est4s.terminal.core.pasteJob
import io.github.est4s.terminal.core.renameJob

/** What Files' taps do; MainActivity implements it. */
interface FilesActions {
    /** Chat here: a new chat whose agent runs in [folder], shown. */
    fun chatHere(folder: String)
    /** Terminal here: a new tab with a shell in [folder], shown. */
    fun terminalHere(folder: String)
    /** A picture's row: full screen. */
    fun viewPicture(path: String)
    /** Another file's row: another app opens it ("open with"), else Share. */
    fun openFile(path: String)
    /** The selection's Share. */
    fun share(paths: List<String>)
    /** Runs [job]'s argv in Debian; [done] gets its exit code and output, on the main thread. */
    fun run(job: FileJob.Run, done: (Int, String) -> Unit)
    /** The top row's ← at the top of the tree: the chat home. */
    fun close()
}

/**
 * Touch mode's file manager (docs/TOUCH-DESIGN.md "Files"), in the pixel
 * style: core's listing, crumbs, places and jobs drawn, nothing decided
 * here. [list] reads a folder, hidden names too, off the main thread (the
 * view hides them itself, so a job's taken names are complete); every
 * change is a core [FileJob] that [FilesActions.run] runs in Debian, one
 * at a time, its output on screen if it fails. A picture's row shows its
 * [thumbnails] square once the row scrolls into sight: a folder of
 * photos would otherwise decode every one of them up front.
 */
@SuppressLint("ViewConstructor")
class FilesView(
    context: Context,
    /** Lists a folder off the main thread; calls done on the main thread. */
    private val list: (folder: String, done: (Result<List<FileEntry>>) -> Unit) -> Unit,
    /** Root's home as Debian sees it, the crumbs' ~. */
    private val home: String,
    private val actions: FilesActions,
    /** Pictures' squares, [FILES_THUMBNAIL_DP]; null: icons only. */
    private val thumbnails: ChatThumbnails? = null,
) : LinearLayout(context) {
    /** The folder on show. */
    var folder: String = home
        private set
    private var showHidden = false
    // Null while it's read, or when it can't be: then [problem] says why.
    private var entries: List<FileEntry>? = null
    private var problem: String? = null
    private val selected = LinkedHashSet<String>()
    private var clip: FileClip? = null
    private var running: FileJob.Run? = null
    private var asked = 0
    // Pictures' rows not yet asked for their square: their icon, and the entry.
    private val waitingThumbnails = ArrayList<Pair<ImageView, FileEntry>>()

    private val backButton = iconButton(PixelIcon.BACK, Pixel.CYAN, "Back") { if (!back()) actions.close() }
    private val crumbsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val crumbsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(crumbsRow)
    }
    private val moreButton: ImageView = iconButton(PixelIcon.MORE, Pixel.TEXT, "More") { showMore() }
    private val clearButton = iconButton(PixelIcon.CLOSE, Pixel.CYAN, "Clear the selection") { clearSelection() }
    private val countText = TextView(context).apply {
        typeface = Pixel.label(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        setPadding(dp(4), 0, dp(8), 0)
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(4), 0, dp(4), 0)
        addView(backButton, LayoutParams(dp(52), dp(52)))
        addView(clearButton, LayoutParams(dp(52), dp(52)))
        addView(crumbsScroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(countText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(moreButton, LayoutParams(dp(52), dp(52)))
    }

    private val placesRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(16), dp(10), dp(16), dp(6))
    }
    private val placesScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(placesRow)
    }
    private val copyButton = selectionButton("Copy") { cutOrCopy(ClipOp.COPY) }
    private val cutButton = selectionButton("Cut") { cutOrCopy(ClipOp.MOVE) }
    private val renameButton = selectionButton("Rename") { rename() }
    private val deleteButton = selectionButton("Delete") { confirmDelete() }
    private val shareButton = selectionButton("Share") { actions.share(selected.toList()) }
    private val selectionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(12), dp(10), dp(12), dp(6))
        listOf(copyButton, cutButton, renameButton, deleteButton, shareButton).forEachIndexed { i, button ->
            addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private val rows = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(16))
    }
    private val scroll = ScrollView(context).apply {
        addView(rows)
        Pixel.scrollbar(this)
        setOnScrollChangeListener { _, _, _, _, _ -> loadVisibleThumbnails() }
    }

    private val jobText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val jobRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(16), dp(4), dp(16), dp(4))
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Pixel.CYAN)
        }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        addView(jobText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        visibility = View.GONE
    }

    private val pasteText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val pasteBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.PINK)
        setPadding(dp(14), dp(6), dp(6), dp(6))
        addView(pasteText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionButton("Paste here", main = true) { paste() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        addView(iconButton(PixelIcon.CLOSE, Pixel.TEXT, "Forget what's to paste") {
            clip = null
            render()
        }, LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        visibility = View.GONE
    }

    private val bottomRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
        addView(actionButton("Chat here", main = true) { actions.chatHere(folder) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionButton("Terminal here", main = false) { actions.terminalHere(folder) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)))
        addView(placesScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(selectionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(jobRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pasteBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(16), 0)
        })
        addView(bottomRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    /** Shows [path], read afresh, with nothing selected. */
    fun open(path: String) {
        folder = path
        selected.clear()
        entries = null
        problem = null
        scroll.scrollTo(0, 0)
        render()
        refresh()
    }

    /** Reads the folder again (off the main thread); an answer to an older ask is dropped. */
    fun refresh() {
        val own = ++asked
        list(folder) { result -> if (own == asked) took(result) }
    }

    private fun took(result: Result<List<FileEntry>>) {
        result.onSuccess { list ->
            entries = list
            problem = null
            // Gone meanwhile (a job, another program): not selected any more.
            selected.retainAll(list.mapTo(HashSet()) { it.path })
        }.onFailure {
            entries = null
            problem = it.message ?: it.javaClass.simpleName
        }
        render()
    }

    /** Draws [entries] in [folder], with [selection] selected and [clip] to paste (the screenshots give them directly). */
    internal fun show(folder: String, entries: List<FileEntry>, selection: List<String> = emptyList(), clip: FileClip? = null) {
        this.folder = folder
        this.entries = entries
        problem = null
        selected.clear()
        selected.addAll(selection)
        this.clip = clip
        render()
    }

    /** Back: leaves the selection, else goes up a folder; false at a place (core's [filesBack]). */
    fun back(): Boolean {
        if (selected.isNotEmpty()) {
            clearSelection()
            return true
        }
        val parent = filesBack(folder) ?: return false
        open(parent)
        return true
    }

    private fun clearSelection() {
        selected.clear()
        render()
    }

    private fun render() {
        val selecting = selected.isNotEmpty()
        backButton.visibility = if (selecting) View.GONE else View.VISIBLE
        crumbsScroll.visibility = if (selecting) View.GONE else View.VISIBLE
        moreButton.visibility = if (selecting) View.GONE else View.VISIBLE
        clearButton.visibility = if (selecting) View.VISIBLE else View.GONE
        countText.visibility = if (selecting) View.VISIBLE else View.GONE
        countText.text = "${selected.size} selected"
        placesScroll.visibility = if (selecting) View.GONE else View.VISIBLE
        selectionRow.visibility = if (selecting) View.VISIBLE else View.GONE
        setEnabledLook(renameButton, selected.size == 1)
        renderCrumbs()
        renderPlaces()
        renderRows()
        val job = running
        jobRow.visibility = if (job != null) View.VISIBLE else View.GONE
        jobText.text = job?.title ?: ""
        val toPaste = clip
        pasteBar.visibility = if (toPaste != null) View.VISIBLE else View.GONE
        pasteText.text = if (toPaste == null) "" else "${toPaste.paths.size} to paste"
    }

    private fun renderCrumbs() {
        crumbsRow.removeAllViews()
        val crumbs = folderCrumbs(folder, home)
        crumbs.forEachIndexed { i, (label, path) ->
            val last = i == crumbs.lastIndex
            if (i > 0) crumbsRow.addView(TextView(context).apply {
                text = "/"
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Pixel.DIM)
            })
            crumbsRow.addView(TextView(context).apply {
                text = label
                typeface = if (last) Pixel.label(context, 700) else Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (last) 20f else 16f)
                setTextColor(if (last) Pixel.TEXT else Pixel.CYAN)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                minWidth = dp(32)
                setPadding(dp(6), 0, dp(6), 0)
                if (!last) {
                    background = pixelRipple()
                    isFocusable = true
                    setOnClickListener { open(path) }
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        // The folder itself is the crumb that matters: keep the end in sight.
        crumbsScroll.post { crumbsScroll.scrollTo(crumbsRow.width, 0) }
    }

    private fun renderPlaces() {
        placesRow.removeAllViews()
        FILE_PLACES.forEachIndexed { i, (label, path) ->
            val here = path == folder
            placesRow.addView(TextView(context).apply {
                text = label
                typeface = Pixel.label(context, 600)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (here) Pixel.BACKGROUND else Pixel.TEXT)
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(14), 0, dp(14), 0)
                background = if (here) {
                    Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
                } else {
                    Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
                }
                isFocusable = true
                setOnClickListener { open(path) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        waitingThumbnails.clear()
        val all = entries
        val why = problem
        when {
            why != null -> rows.addView(note("Can't read this folder: $why"))
            all == null -> rows.addView(note("Reading…"))
            else -> {
                val visible = if (showHidden) all else all.filter { !it.name.startsWith(".") }
                if (visible.isEmpty()) {
                    val hidden = all.size - visible.size
                    rows.addView(note(if (hidden > 0) "Only hidden files here ($hidden): ⋮ shows them." else "This folder is empty."))
                }
                val now = System.currentTimeMillis()
                visible.take(MAX_ROWS).forEach { entry ->
                    rows.addView(row(entry, now), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    })
                }
                if (visible.size > MAX_ROWS) {
                    rows.addView(note("…and ${visible.size - MAX_ROWS} more: Terminal here lists them all."))
                }
                // Once laid out: which rows are in sight is only known then.
                rows.post { loadVisibleThumbnails() }
            }
        }
    }

    // A box with the kind's icon (a tick when selected), the name, and size · date.
    private fun row(entry: FileEntry, now: Long) = LinearLayout(context).apply {
        val picked = entry.path in selected
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        background = Pixel.box(context, fill = Pixel.CARD, border = if (picked) Pixel.CYAN else Pixel.LINE, ripple = Pixel.LINE)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isFocusable = true
        val (icon, color) = when {
            picked -> PixelIcon.CHECK to Pixel.CYAN
            entry.folder -> PixelIcon.FOLDER to Pixel.CYAN
            entry.link -> PixelIcon.LINK to Pixel.DIM
            isPictureName(entry.name) -> PixelIcon.IMAGE to Pixel.PINK
            else -> PixelIcon.FILE to Pixel.DIM
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, icon, color))
            scaleType = ImageView.ScaleType.CENTER
        }
        addView(iconView, LayoutParams(dp(FILES_THUMBNAIL_DP), dp(FILES_THUMBNAIL_DP)).apply { marginEnd = dp(12) })
        if (!picked && !entry.folder && thumbnails != null && isPictureName(entry.name)) waitingThumbnails += iconView to entry
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = entry.name
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Pixel.TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(context).apply {
                text = detail(entry, now)
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Pixel.DIM)
                maxLines = 1
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (entry.folder && !picked) {
            addView(ImageView(context).apply {
                setImageDrawable(Pixel.icon(context, PixelIcon.CHEVRON, Pixel.DIM))
            }, LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        }
        setOnClickListener { if (selected.isNotEmpty()) toggle(entry) else openEntry(entry) }
        setOnLongClickListener {
            toggle(entry)
            true
        }
    }

    // Asks for the squares of the waiting rows now in sight (and a screen below, to scroll into).
    internal fun loadVisibleThumbnails() {
        val thumbnails = thumbnails ?: return
        if (waitingThumbnails.isEmpty() || scroll.height == 0) return
        val top = scroll.scrollY - rows.top
        val bottom = top + 2 * scroll.height
        val iterator = waitingThumbnails.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            val row = view.parent as? View ?: continue
            if (row.bottom < top || row.top > bottom) continue
            iterator.remove()
            val key = entry.path + "\u0000" + entry.modified
            view.tag = key
            thumbnails.load(entry.path, entry.modified) { bitmap ->
                // Not if the row was drawn anew meanwhile (another folder, a selection).
                if (view.tag == key) {
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun detail(entry: FileEntry, now: Long): String {
        val parts = ArrayList<String>()
        if (entry.link) parts += "link"
        if (!entry.folder) parts += fileSizeText(entry.size)
        if (entry.modified > 0) parts += chatRelativeTime(now, entry.modified)
        return parts.joinToString(" · ")
    }

    private fun openEntry(entry: FileEntry) {
        when {
            entry.folder -> open(entry.path)
            isPictureName(entry.name) -> actions.viewPicture(entry.path)
            else -> actions.openFile(entry.path)
        }
    }

    private fun toggle(entry: FileEntry) {
        if (!selected.remove(entry.path)) selected.add(entry.path)
        render()
    }

    private fun showMore() {
        pixelMenu(moreButton, listOf(
            PixelMenuEntry(if (showHidden) "Hide hidden files" else "Show hidden files") {
                showHidden = !showHidden
                render()
            },
            PixelMenuEntry("New folder", greyed = entries == null) { newFolder() },
        ))
    }

    // The names in this folder, hidden ones too, for a job's checks; null (and why, shown) until it's read.
    private fun takenNames(): Set<String>? {
        val all = entries
        if (all == null) {
            toast(if (problem != null) "Can't change a folder that can't be read" else "Still reading the folder")
            return null
        }
        return all.mapTo(HashSet()) { it.name }
    }

    private fun cutOrCopy(op: ClipOp) {
        clip = FileClip(op, selected.toList())
        selected.clear()
        render()
    }

    private fun paste() {
        val toPaste = clip ?: return
        val taken = takenNames() ?: return
        start(pasteJob(toPaste, folder, taken)) { clip = null }
    }

    private fun rename() {
        if (selected.size != 1) {
            toast("Select one to rename")
            return
        }
        val path = selected.first()
        val taken = takenNames() ?: return
        val old = path.substringAfterLast('/')
        askName("Rename", old, "Rename") { name ->
            start(renameJob(path, name, taken)) { selected.clear() }
        }
    }

    private fun newFolder() {
        val taken = takenNames() ?: return
        askName("New folder", "", "Create") { name ->
            start(newFolderJob(folder, name, taken))
        }
    }

    private fun confirmDelete() {
        val paths = selected.toList()
        if (paths.isEmpty()) return
        val what = if (paths.size == 1) "\"${paths[0].substringAfterLast('/')}\"" else "${paths.size} items"
        AlertDialog.Builder(context)
            .setMessage("Delete $what? This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> start(deleteJob(paths)) { selected.clear() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Runs [job] unless one is running; [after] once it worked. Either way the folder is read again.
    private fun start(job: FileJob, after: () -> Unit = {}) {
        when (job) {
            is FileJob.Refused -> tell(null, job.reason)
            is FileJob.Run -> {
                val busy = running
                if (busy != null) {
                    toast("Wait for this to finish: ${busy.title}")
                    return
                }
                running = job
                render()
                actions.run(job) { code, output ->
                    running = null
                    if (code == 0) after() else tell("${job.title}: failed", output.ifBlank { "It stopped with exit code $code." })
                    render()
                    refresh()
                }
            }
        }
    }

    // A text field in a dialog; OK only while core finds the name fine (fileNameProblem says why not).
    private fun askName(title: String, start: String, ok: String, then: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(start)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            // The name without its extension, ready to type over.
            val dot = start.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else start.length)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(FrameLayout(context).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(field)
            })
            .setPositiveButton(ok) { _, _ -> then(field.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .create()
        fun check(): Boolean {
            val name = field.text.toString().trim()
            val why = fileNameProblem(name)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = why == null
            field.error = if (name.isEmpty()) null else why
            return why == null
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
                then(field.text.toString().trim())
            }
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    // A failure stays on screen until it's read: there's no log to look in on the phone.
    private fun tell(title: String?, text: String) {
        if (!isAttachedToWindow) return
        val builder = AlertDialog.Builder(context).setMessage(text).setPositiveButton("OK", null)
        if (title != null) builder.setTitle(title)
        builder.show()
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun setEnabledLook(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.setTextColor(if (enabled) Pixel.TEXT else Pixel.DIM)
    }

    private fun selectionButton(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.TEXT)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(44)
        setPadding(dp(2), 0, dp(2), 0)
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
        isFocusable = true
        setOnClickListener { onTap() }
    }

    // The main one filled cyan, as the chat home's buttons; the other boxed.
    private fun actionButton(label: String, main: Boolean, onTap: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (main) Pixel.BACKGROUND else Pixel.TEXT)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = if (main) {
            Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
        } else {
            Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
        }
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.DIM)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun iconButton(icon: PixelIcon, color: Int, description: String, onTap: () -> Unit) = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, color))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun dp(value: Int) = Pixel.dp(context, value)

    companion object {
        // A LinearLayout draws every row: past this, the terminal lists the rest.
        private const val MAX_ROWS = 500
        /** A row's icon and a picture's square. */
        const val FILES_THUMBNAIL_DP = 40
    }
}
