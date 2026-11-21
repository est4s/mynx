package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.PdfColumn
import io.github.est4s.terminal.core.PdfHandle
import io.github.est4s.terminal.core.PdfLatest
import io.github.est4s.terminal.core.PdfPageSpace
import io.github.est4s.terminal.core.PdfPoint
import io.github.est4s.terminal.core.PdfRect
import io.github.est4s.terminal.core.PdfRenderSize
import io.github.est4s.terminal.core.PdfMatch
import io.github.est4s.terminal.core.PdfSearch
import io.github.est4s.terminal.core.PdfSelection
import io.github.est4s.terminal.core.PdfZoom
import io.github.est4s.terminal.core.pdfBarPlace
import io.github.est4s.terminal.core.pdfBefore
import io.github.est4s.terminal.core.pdfBox
import io.github.est4s.terminal.core.pdfGoToPage
import io.github.est4s.terminal.core.pdfGrab
import io.github.est4s.terminal.core.pdfHandles
import io.github.est4s.terminal.core.pdfIndicator
import io.github.est4s.terminal.core.pdfPlan
import io.github.est4s.terminal.core.pdfRevealBy
import io.github.est4s.terminal.core.pdfSelectionEnds
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

// Behind the pages.
private const val PDF_BACKGROUND = 0xFF4A4D52.toInt()
// A zoom is settled (and rendered sharper) once it hasn't changed for this long.
private const val SETTLE_MS = 250L
// Selected text's tint: the accent at about 35%.
private const val SELECTION_ALPHA = 0x59
// Two points this close in height (PDF points) are on one line.
private const val LINE_POINTS = 4f
// Find searches once typing stops for this long: each search goes through every page.
private const val SEARCH_DELAY_MS = 300L
// Find's matches: the accent at about 25%, the current one pink at about 45%.
private const val MATCH_ALPHA = 0x40
private const val CURRENT_MATCH_ALPHA = 0x73

/** What the PDF view asks of its activity. */
interface PdfViewActions {
    /** ← or Back. */
    fun close()

    /** ⋮ Share. */
    fun share()

    /** ⋮ Open with another app, and the button under an error. */
    fun openWithAnotherApp()

    /**
     * Render [page] at [size], off the main thread, unless [stillWanted]
     * says no by then; then [done] on the main thread with the bitmap,
     * or null and why not (null and null: it wasn't wanted any more).
     */
    fun render(page: Int, size: PdfRenderSize, stillWanted: () -> Boolean, done: (Bitmap?, String?) -> Unit)

    /** Whether pages' text can be selected (Android 15's PdfRenderer can). */
    val canSelect: Boolean get() = false

    /**
     * Select [page]'s text from [start] to [stop] (in its PDF points),
     * off the main thread; then [done] on the main thread with what's
     * selected, or null for nothing.
     */
    fun select(page: Int, start: PdfPoint, stop: PdfPoint, done: (PdfSelection?) -> Unit) = done(null)

    /** All of [page]'s text, as [select] gives it. */
    fun selectAll(page: Int, done: (PdfSelection?) -> Unit) = done(null)

    /** Whether pages' text can be searched (Android 15's PdfRenderer can). */
    val canSearch: Boolean get() = false

    /**
     * Where [query] is on [page] (case ignored), off the main thread; then
     * [done] on the main thread with each match's line rects in the page's
     * PDF points, or null when it couldn't search.
     */
    fun search(page: Int, query: String, done: (List<List<PdfRect>>?) -> Unit) = done(null)
}

/**
 * The PDF viewer's screen (`PdfViewActivity`, docs/TOUCH-DESIGN.md
 * "PDF"): the top bar with ←, the Debian file [path]'s name over its
 * folder and ⋮ (Find, Go to page, Share, Open with another app), the pages
 * ([PdfPagesView]) and a page indicator ("3 / 12") that fades when
 * they're still. Rendering is the activity's ([PdfViewActions.render]).
 * Find (Android 15+) is a bar at the bottom; core's [PdfSearch] keeps
 * the matches as the pages are searched one by one.
 */
@SuppressLint("ViewConstructor")
class PdfView(
    context: Context,
    private val path: String,
    private val actions: PdfViewActions,
) : LinearLayout(context) {
    private val name = path.trimEnd('/').substringAfterLast('/')

    /** How long the indicator stays after the pages last moved. */
    var indicatorHold = 1500L

    val pages = PdfPagesView(context, actions) { page, count -> showIndicator(page, count) }

    // Over a selection: Copy, Select all, ✕.
    private val selectionBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(barButton("Copy") { copySelection() }, LayoutParams(WRAP_CONTENT, dp(44)))
        addView(barButton("Select all") { pages.selectAll() }, LayoutParams(WRAP_CONTENT, dp(44)))
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, PixelIcon.CLOSE, Pixel.DIM, 16))
            scaleType = ImageView.ScaleType.CENTER
            background = pixelRipple()
            contentDescription = "Clear the selection"
            setOnClickListener { pages.clearSelection() }
        }, LayoutParams(dp(44), dp(44)))
        visibility = View.GONE
    }

    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(4), 0, dp(4), 0)
        addView(iconButton(PixelIcon.BACK, Pixel.CYAN, "Back") { actions.close() }, LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(6), dp(8), dp(6))
            addView(TextView(context).apply {
                typeface = Pixel.label(context, 700)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Pixel.TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                text = name
            })
            addView(TextView(context).apply {
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Pixel.DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
                text = path.substringBeforeLast('/', "").ifEmpty { "/" }.replaceFirst(Regex("^/root(?=/|$)"), "~")
            })
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(iconButton(PixelIcon.MORE, Pixel.TEXT, "More") { showMore(it) }, LayoutParams(dp(48), dp(52)))
    }

    private val indicator = TextView(context).apply {
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE)
        alpha = 0f
    }
    private val fade = Runnable { indicator.animate().alpha(0f).setDuration(300).start() }

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
            override fun afterTextChanged(s: Editable?) {
                this@PdfView.removeCallbacks(searchLater)
                this@PdfView.postDelayed(searchLater, SEARCH_DELAY_MS)
            }
        })
        setOnEditorActionListener { _, _, _ ->
            if (search?.query == text.toString()) stepFind(1) else startSearch()
            true
        }
    }
    private val findCount = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Pixel.DIM)
        setPadding(dp(4), 0, dp(4), 0)
    }
    private val findBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE)
        setPadding(dp(4), dp(2), dp(4), dp(2))
        addView(findInput, LayoutParams(0, dp(48), 1f))
        addView(findCount, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(glyphButton("↑", "Previous match") { stepFind(-1) }, LayoutParams(dp(44), dp(48)))
        addView(glyphButton("↓", "Next match") { stepFind(1) }, LayoutParams(dp(44), dp(48)))
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, PixelIcon.CLOSE, Pixel.TEXT, 16))
            scaleType = ImageView.ScaleType.CENTER
            background = pixelRipple()
            contentDescription = "Close find"
            setOnClickListener { closeFind() }
        }, LayoutParams(dp(44), dp(48)))
        visibility = View.GONE
    }
    // The search being run and shown; a new one replaces it, and answers for the old one are dropped.
    private var search: PdfSearch? = null
    private val searchLater = Runnable { startSearch() }

    private val messageText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.DIM)
        gravity = Gravity.CENTER
    }
    private val openButton = TextView(context).apply {
        text = "Open with another app"
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Pixel.BACKGROUND)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
        isFocusable = true
        setOnClickListener { actions.openWithAnotherApp() }
    }
    private val message = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Pixel.BACKGROUND)
        addView(messageText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(openButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(MATCH_PARENT, dp(2)))
        addView(FrameLayout(context).apply {
            addView(pages, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(indicator, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(20)
            })
            addView(selectionBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        }, LayoutParams(MATCH_PARENT, 0, 1f))
        addView(findBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pages.selectionMoved = { selected, box -> placeBar(selected, box) }
        showMessage("Loading…", open = false)
    }

    /** Find, from ⋮ or Ctrl+F: the bar at the bottom, its text selected to type over. */
    fun openFind() {
        if (!actions.canSearch || pages.count == 0) return
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        findInput.selectAll()
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Finds [query] now (as typing it and waiting would): for the screenshots. */
    fun find(query: String) {
        openFind()
        findInput.setText(query)
        startSearch()
    }

    /** Closes Find and its highlights (Back's first job); true when it was open. */
    fun closeFind(): Boolean {
        if (findBar.visibility != View.VISIBLE) return false
        removeCallbacks(searchLater)
        findBar.visibility = View.GONE
        search = null
        findCount.text = ""
        pages.showSearch(null)
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(findInput.windowToken, 0)
        pages.requestFocus()
        return true
    }

    // A new search for what's typed, from the page in view, a page at a time.
    private fun startSearch() {
        removeCallbacks(searchLater)
        val query = findInput.text.toString()
        val started = PdfSearch(query, pages.currentPage, pages.count)
        search = started
        pages.showSearch(started)
        findCount.text = started.label
        searchNext(started)
    }

    private fun searchNext(running: PdfSearch) {
        val page = running.nextPage ?: return
        actions.search(page, running.query) { found ->
            if (search !== running) return@search
            val moved = running.found(page, found.orEmpty())
            findCount.text = running.label
            pages.showSearch(running)
            if (moved) running.currentMatch?.let(pages::reveal)
            searchNext(running)
        }
    }

    private fun stepFind(by: Int) {
        val running = search ?: return startSearch()
        if (!running.step(by)) return
        findCount.text = running.label
        pages.showSearch(running)
        running.currentMatch?.let(pages::reveal)
    }

    /** Clears the selection, if there's one (Back's first job); true when there was. */
    fun clearSelection(): Boolean = pages.clearSelection()

    /** Copy (and Ctrl+C): the selected text on the clipboard; false with none. */
    fun copySelection(): Boolean {
        val text = pages.selection?.text ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(name, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        pages.clearSelection()
        return true
    }

    // The bar over the selection, whose box on screen is [box] (null: it has none), or gone without one.
    private fun placeBar(selected: Boolean, box: PdfRect?) {
        if (!selected) {
            selectionBar.visibility = View.GONE
            return
        }
        if (selectionBar.measuredWidth == 0) selectionBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = pages.width.toFloat()
        val h = pages.height.toFloat()
        val place = pdfBarPlace(
            box ?: PdfRect(0f, 0f, w, h),
            selectionBar.measuredWidth.toFloat(),
            selectionBar.measuredHeight.toFloat(),
            w,
            h,
            dp(8).toFloat(),
            pages.handleSize,
        )
        selectionBar.translationX = place.x
        selectionBar.translationY = place.y
        selectionBar.visibility = View.VISIBLE
    }

    private fun barButton(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.CYAN)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = pixelRipple()
        isFocusable = true
        setOnClickListener { onTap() }
    }

    /** The document is open: its pages' height / width, in order, and their widths in PDF points (for selecting text). */
    fun showPages(aspects: List<Float>, pointWidths: List<Float> = emptyList()) {
        message.visibility = View.GONE
        pages.visibility = View.VISIBLE
        if (aspects.isEmpty()) {
            showMessage("$name has no pages.", open = true)
            return
        }
        pages.show(aspects, pointWidths)
    }

    /** It can't be shown: [text] says why, over Open with another app. */
    fun showError(text: String) {
        pages.visibility = View.GONE
        indicator.visibility = View.GONE
        showMessage(text, open = true)
    }

    /** Go to page, from ⋮ or G: a number to type. */
    fun askPage() {
        val count = pages.count
        if (count == 0) return
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "1–$count"
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Go to page")
            .setView(FrameLayout(context).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(field)
            })
            .setPositiveButton("Go") { _, _ -> pdfGoToPage(field.text.toString(), count)?.let(pages::goTo) }
            .setNegativeButton("Cancel", null)
            .create()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            dialog.dismiss()
            pdfGoToPage(field.text.toString(), count)?.let(pages::goTo)
            true
        }
        dialog.show()
        field.requestFocus()
    }

    /** Esc, G, Ctrl+F, and the keys that scroll and zoom; true when it was one. */
    fun onKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_F && event.isCtrlPressed) {
            if (event.action == KeyEvent.ACTION_DOWN) openFind()
            return true
        }
        // Typing in Find: its keys are the field's, but Esc closes it.
        if (findInput.hasFocus()) {
            if (key != KeyEvent.KEYCODE_ESCAPE) return false
            if (event.action == KeyEvent.ACTION_DOWN) closeFind()
            return true
        }
        if (key == KeyEvent.KEYCODE_C && event.isCtrlPressed && pages.selection != null) {
            if (event.action == KeyEvent.ACTION_DOWN) copySelection()
            return true
        }
        when (key) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD,
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Unit
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val page = pages.height * 9f / 10
        when (key) {
            KeyEvent.KEYCODE_ESCAPE -> if (!closeFind() && !pages.clearSelection()) actions.close()
            KeyEvent.KEYCODE_G -> askPage()
            KeyEvent.KEYCODE_DPAD_DOWN -> pages.scrollBy(0f, dp(60).toFloat())
            KeyEvent.KEYCODE_DPAD_UP -> pages.scrollBy(0f, -dp(60).toFloat())
            KeyEvent.KEYCODE_DPAD_RIGHT -> pages.scrollBy(dp(120).toFloat(), 0f)
            KeyEvent.KEYCODE_DPAD_LEFT -> pages.scrollBy(-dp(120).toFloat(), 0f)
            KeyEvent.KEYCODE_PAGE_DOWN -> pages.scrollBy(0f, page)
            KeyEvent.KEYCODE_SPACE -> pages.scrollBy(0f, if (event.isShiftPressed) -page else page)
            KeyEvent.KEYCODE_PAGE_UP -> pages.scrollBy(0f, -page)
            KeyEvent.KEYCODE_MOVE_HOME -> pages.goTo(0)
            KeyEvent.KEYCODE_MOVE_END -> pages.toEnd()
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> pages.zoomBy(1.25f)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> pages.zoomBy(0.8f)
        }
        return true
    }

    private fun showMessage(text: String, open: Boolean) {
        messageText.text = text
        openButton.visibility = if (open) View.VISIBLE else View.GONE
        message.visibility = View.VISIBLE
    }

    private fun showIndicator(page: Int, count: Int) {
        if (count == 0) return
        indicator.text = pdfIndicator(page, count)
        indicator.animate().cancel()
        indicator.alpha = 1f
        removeCallbacks(fade)
        postDelayed(fade, indicatorHold)
    }

    private fun showMore(anchor: View) {
        val entries = ArrayList<PixelMenuEntry>()
        if (actions.canSearch && pages.count > 0) entries += PixelMenuEntry("Find") { openFind() }
        if (pages.count > 1) entries += PixelMenuEntry("Go to page") { askPage() }
        entries += PixelMenuEntry("Share") { actions.share() }
        entries += PixelMenuEntry("Open with another app") { actions.openWithAnotherApp() }
        pixelMenu(anchor, entries)
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

    private fun iconButton(icon: PixelIcon, color: Int, description: String, onTap: (View) -> Unit) = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, color, 20))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onTap(it) }
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}

/**
 * The pages, one under another on grey, each fitted to the width (core's
 * [PdfColumn]), with pinch and double-tap zoom over them all, drags and
 * flings (core's [PdfZoom]). It asks for renders as core's [pdfPlan]
 * says: the pages in sight at the zoom once it settles, a few each way
 * at the width; it lets the far ones go. A page not rendered yet is
 * white. [moved] gets the current page and the count whenever the pages
 * move.
 *
 * Where [PdfViewActions.canSelect] (Android 15+), a long press selects
 * the word under it (and a drag on from there extends it); the
 * selection is tinted, with handles at its ends that drag it further,
 * on its page (core's [PdfPageSpace] maps the touches to the page's
 * points). One selection request is in flight at a time, the latest
 * waiting ([PdfLatest]). [selectionMoved] hears where it is on screen.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PdfPagesView(
    context: Context,
    private val actions: PdfViewActions,
    private val moved: (page: Int, count: Int) -> Unit,
) : View(context) {
    private var aspects: List<Float> = emptyList()
    private var column: PdfColumn? = null
    private val zoom = PdfZoom()
    // The zoom pages in sight are rendered at: the last one that settled.
    private var settledZoom = 1f
    private val bitmaps = HashMap<Int, Bitmap>()
    // The size asked for each page being rendered; read by the render thread.
    private val pending = ConcurrentHashMap<Int, PdfRenderSize>()
    private val failed = HashSet<Int>()
    private val gap = Pixel.dp(context, 8).toFloat()
    private val paper = Paint().apply { color = Color.WHITE }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val scroller = OverScroller(context)
    private var flingX = 0
    private var flingY = 0

    // Selecting text.
    private var pointWidths: List<Float> = emptyList()
    private val selectPaint = Paint()
    private val handlePaint = Paint()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val grabSlop = Pixel.dp(context, 28).toFloat()
    private val stem = Pixel.dp(context, 2).toFloat()
    private val requests = PdfLatest<SelectRequest>()
    // Bumped when the selection is cleared or replaced: answers to older requests are dropped.
    private var generation = 0
    private var multiTouch = false
    private var dragging = false
    private var longPressed = false
    private var longPressX = 0f
    private var longPressY = 0f
    // The long press's word hasn't come yet; the finger has moved off it, extending it.
    private var awaitingWord = false
    private var extending = false
    // A drag selects from [dragFrom] to the finger, or from the finger to [dragUntil] when it's before [dragFrom].
    private var dragFrom = PdfPoint(0f, 0f)
    private var dragUntil = PdfPoint(0f, 0f)
    // From the finger to the end it moves, on screen.
    private var dragDx = 0f
    private var dragDy = 0f

    private class SelectRequest(val generation: Int, val page: Int, val start: PdfPoint, val stop: PdfPoint, val fresh: Boolean)

    /** A selection handle's side, hanging below the line's end. */
    val handleSize = Pixel.dp(context, 14).toFloat()

    /** The selected text, if any. */
    var selection: PdfSelection? = null
        private set

    /** Hears whether there's a selection, and its box on screen (null: its text has no place on the page), whenever it or the pages move. */
    var selectionMoved: (selected: Boolean, box: PdfRect?) -> Unit = { _, _ -> }

    val count: Int get() = aspects.size

    /** The page mostly in view. */
    val currentPage: Int get() = column?.current(zoom.top, zoom.bottom) ?: 0

    // Find's matches, drawn over the pages.
    private var search: PdfSearch? = null
    private val matchPaint = Paint()

    private val settle = Runnable {
        settledZoom = zoom.scale
        plan()
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            changed(zoomed = true)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaler.isInProgress) return false
            zoom.panBy(-distanceX, -distanceY)
            changed(zoomed = false)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaler.isInProgress) return false
            flingX = 0
            flingY = 0
            // Unbounded here: PdfZoom keeps the pages inside, and the fling stops where they stop.
            scroller.fling(0, 0, velocityX.toInt(), velocityY.toInt(), Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            zoom.toggle(e.x, e.y)
            changed(zoomed = true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (clearSelection()) return true
            moved(zoom.let { column?.current(it.top, it.bottom) ?: 0 }, count)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!actions.canSelect || multiTouch || scaler.isInProgress) return
            val space = space() ?: return
            val page = space.pageAt(e.x, e.y) ?: return
            val point = space.toPage(page, e.x, e.y)
            longPressed = true
            longPressX = e.x
            longPressY = e.y
            awaitingWord = true
            extending = false
            dragging = false
            generation++
            requests.clear()
            request(SelectRequest(generation, page, point, point, fresh = true))
        }
    })

    init {
        setBackgroundColor(PDF_BACKGROUND)
        isFocusable = true
    }

    /** The document's pages, height / width each, and their widths in PDF points. */
    fun show(pageAspects: List<Float>, pagePointWidths: List<Float> = emptyList()) {
        aspects = pageAspects
        pointWidths = pagePointWidths
        clearSelection()
        bitmaps.clear()
        pending.clear()
        failed.clear()
        relayout(keep = false)
    }

    /** [page] at the top. */
    fun goTo(page: Int) {
        val shown = column ?: return
        if (shown.count == 0) return
        scroller.forceFinished(true)
        zoom.scrollTo(shown.top(page.coerceIn(0, shown.count - 1)) - gap / 2)
        changed(zoomed = false)
    }

    /** The last page's end at the bottom. */
    fun toEnd() {
        val shown = column ?: return
        scroller.forceFinished(true)
        zoom.scrollTo(shown.height)
        changed(zoomed = false)
    }

    /** Scrolls the pages [dx], [dy] screen pixels (a key's). */
    fun scrollBy(dx: Float, dy: Float) {
        scroller.forceFinished(true)
        zoom.panBy(-dx, -dy)
        changed(zoomed = false)
    }

    /** Zooms [factor] times about the middle (a key's). */
    fun zoomBy(factor: Float) {
        scroller.forceFinished(true)
        zoom.zoomBy(factor, width / 2f, height / 2f)
        changed(zoomed = true)
    }

    /** Draws [shown]'s matches (null: none), the current one brighter. */
    fun showSearch(shown: PdfSearch?) {
        search = shown
        invalidate()
    }

    /** Scrolls so [match] is in sight. */
    fun reveal(match: PdfMatch) {
        val space = space() ?: return
        if (match.page >= count) return
        val box = pdfBox(match.rects.map { space.toScreen(match.page, it) }) ?: return
        val by = pdfRevealBy(box, width.toFloat(), height.toFloat(), Pixel.dp(context, 48).toFloat())
        if (by.x == 0f && by.y == 0f) {
            invalidate()
            return
        }
        scroller.forceFinished(true)
        zoom.panBy(by.x, by.y)
        changed(zoomed = false)
    }

    /** Shows [chosen] as the selection (null: none): what the renderer selected, or a stand-in's. */
    fun showSelection(chosen: PdfSelection?) {
        selection = chosen
        invalidate()
        reportSelection()
    }

    /** Clears the selection; true when there was one. */
    fun clearSelection(): Boolean {
        generation++
        requests.clear()
        dragging = false
        longPressed = false
        extending = false
        awaitingWord = false
        if (selection == null) return false
        showSelection(null)
        return true
    }

    /** Select all: the whole text of the selection's page, or of the current one. */
    fun selectAll() {
        val shown = column ?: return
        if (!actions.canSelect || shown.count == 0) return
        val page = selection?.page ?: shown.current(zoom.top, zoom.bottom)
        generation++
        requests.clear()
        dragging = false
        val asked = generation
        actions.selectAll(page) { found -> if (asked == generation && found != null) showSelection(found) }
    }

    private fun space(): PdfPageSpace? = column?.let { PdfPageSpace(it, zoom, pointWidths) }

    private fun reportSelection() {
        val chosen = selection
        if (chosen == null) {
            selectionMoved(false, null)
            return
        }
        val space = space()
        selectionMoved(true, space?.let { s -> pdfBox(chosen.rects.map { s.toScreen(chosen.page, it) }) })
    }

    private fun request(asked: SelectRequest) {
        requests.offer(asked)?.let { send(it) }
    }

    private fun send(asked: SelectRequest) {
        actions.select(asked.page, asked.start, asked.stop) { found ->
            // Cleared or replaced since: its queue went with it.
            if (asked.generation != generation) return@select
            if (asked.fresh) {
                awaitingWord = false
                if (found == null) longPressed = false
            }
            if (found != null) {
                if (asked.fresh) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showSelection(found)
            }
            requests.done()?.let { send(it) }
        }
    }

    // The handles' centres on screen: below the first line's start and the last line's end.
    private fun handleCentres(): Pair<PdfPoint, PdfPoint>? {
        val chosen = selection ?: return null
        val space = space() ?: return null
        val (start, stop) = pdfHandles(chosen.rects) ?: return null
        val a = space.toScreen(chosen.page, start)
        val b = space.toScreen(chosen.page, stop)
        val half = handleSize / 2
        return PdfPoint(a.x - half, a.y + half) to PdfPoint(b.x + half, b.y + half)
    }

    // A touch on a handle starts dragging that end; false when it's on neither.
    private fun grab(x: Float, y: Float): Boolean {
        val chosen = selection ?: return false
        val space = space() ?: return false
        val (startCentre, stopCentre) = handleCentres() ?: return false
        val handle = pdfGrab(x, y, startCentre, stopCentre, grabSlop) ?: return false
        val (start, stop) = pdfSelectionEnds(chosen.rects) ?: return false
        val moving = if (handle == PdfHandle.START) start else stop
        val fixed = if (handle == PdfHandle.START) stop else start
        val at = space.toScreen(chosen.page, moving)
        dragDx = at.x - x
        dragDy = at.y - y
        dragFrom = fixed
        dragUntil = fixed
        return true
    }

    // A drag on from a long press: once past the touch slop, from the word's start or to its end.
    private fun extend(x: Float, y: Float) {
        val chosen = selection ?: return
        if (awaitingWord) return
        if (!extending) {
            if (hypot(x - longPressX, y - longPressY) < touchSlop) return
            val (start, stop) = pdfSelectionEnds(chosen.rects) ?: return
            dragFrom = start
            dragUntil = stop
            extending = true
        }
        dragTo(x, y)
    }

    // The moving end to the screen point ([x], [y]), kept on the selection's page.
    private fun dragTo(x: Float, y: Float) {
        val chosen = selection ?: return
        val space = space() ?: return
        val finger = space.toPageInside(chosen.page, x, y)
        val (start, stop) = if (pdfBefore(finger, dragFrom, LINE_POINTS)) finger to dragUntil else dragFrom to finger
        request(SelectRequest(generation, chosen.page, start, stop, fresh = false))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout(keep = true)

    // A new width or document: the column again, the same place at the top if [keep].
    private fun relayout(keep: Boolean) {
        if (width <= 0 || height <= 0 || aspects.isEmpty()) return
        val old = column
        val anchor = if (keep && old != null) old.anchor(zoom.top) else null
        val next = PdfColumn(aspects, width.toFloat(), gap)
        column = next
        zoom.layout(width.toFloat(), height.toFloat(), next.height)
        if (anchor != null) zoom.scrollTo(next.at(anchor.first, anchor.second))
        changed(zoomed = false)
    }

    private fun changed(zoomed: Boolean) {
        invalidate()
        if (zoomed) {
            removeCallbacks(settle)
            postDelayed(settle, SETTLE_MS)
        }
        plan()
        column?.let { moved(it.current(zoom.top, zoom.bottom), it.count) }
        if (selection != null) reportSelection()
    }

    // Asks for what core's plan says, and lets go of what it drops.
    private fun plan() {
        val shown = column ?: return
        val have = HashMap<Int, Int>()
        for ((page, bitmap) in bitmaps) have[page] = bitmap.width
        for ((page, size) in pending) have[page] = maxOf(have[page] ?: 0, size.width)
        // A page that failed isn't asked for again until it's been far away.
        for (page in failed) have[page] = Int.MAX_VALUE
        val plan = pdfPlan(shown, zoom.top, zoom.bottom, settledZoom, have)
        for (page in plan.drop) {
            bitmaps.remove(page)
            pending.remove(page)
            failed.remove(page)
        }
        for (render in plan.render) {
            val page = render.page
            val size = render.size
            pending[page] = size
            actions.render(page, size, { pending[page] == size }) { bitmap, error -> rendered(page, size, bitmap, error) }
        }
    }

    private fun rendered(page: Int, size: PdfRenderSize, bitmap: Bitmap?, error: String?) {
        // Asked for again since, or dropped: this one's out of date.
        if (pending[page] != size) return
        pending.remove(page)
        if (bitmap != null) {
            bitmaps[page] = bitmap
            invalidate()
        } else if (error != null) {
            failed += page
        }
        plan()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                longPressed = false
                extending = false
                dragging = grab(event.x, event.y)
                if (dragging) {
                    scroller.forceFinished(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
        }
        if (dragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragTo(event.x + dragDx, event.y + dragDy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
        if (longPressed) {
            // A drag on from a long press extends the word it selected, either way.
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !multiTouch) extend(event.x, event.y)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                longPressed = false
                extending = false
            }
            // It still needs to see the gesture end.
            gestures.onTouchEvent(event)
            return true
        }
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val dx = scroller.currX - flingX
        val dy = scroller.currY - flingY
        flingX = scroller.currX
        flingY = scroller.currY
        if (dx != 0 || dy != 0) {
            val beforeX = zoom.x
            val beforeY = zoom.y
            zoom.panBy(dx.toFloat(), dy.toFloat())
            // Against the ends it was going to: nothing more to move.
            if (zoom.x == beforeX && zoom.y == beforeY) scroller.forceFinished(true)
            changed(zoomed = false)
        }
        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val shown = column ?: return
        val scale = zoom.scale
        for (page in shown.pagesIn(zoom.top, zoom.bottom)) {
            val top = zoom.y + shown.top(page) * scale
            rect.set(zoom.x, top, zoom.x + shown.width * scale, top + shown.height(page) * scale)
            canvas.drawRect(rect, paper)
            val bitmap = bitmaps[page] ?: continue
            canvas.drawBitmap(bitmap, null, rect, paint)
        }
        drawMatches(canvas, shown)
        drawSelection(canvas, shown)
    }

    // Find's matches on the pages in sight.
    private fun drawMatches(canvas: Canvas, shown: PdfColumn) {
        val running = search ?: return
        if (running.matches.isEmpty()) return
        val space = PdfPageSpace(shown, zoom, pointWidths)
        val inSight = shown.pagesIn(zoom.top, zoom.bottom)
        val current = running.currentMatch
        running.matches.forEach { match ->
            if (match.page !in inSight || match.page >= shown.count) return@forEach
            matchPaint.color = if (match === current) {
                (Pixel.PINK and 0x00FFFFFF) or (CURRENT_MATCH_ALPHA shl 24)
            } else {
                (Pixel.CYAN and 0x00FFFFFF) or (MATCH_ALPHA shl 24)
            }
            for (line in match.rects) {
                val r = space.toScreen(match.page, line)
                canvas.drawRect(r.left, r.top, r.right, r.bottom, matchPaint)
            }
        }
    }

    // The selected lines tinted, and a handle hanging from each end: a stem along the line's edge, a block below it.
    private fun drawSelection(canvas: Canvas, shown: PdfColumn) {
        val chosen = selection ?: return
        if (chosen.page >= shown.count) return
        val space = PdfPageSpace(shown, zoom, pointWidths)
        selectPaint.color = (Pixel.CYAN and 0x00FFFFFF) or (SELECTION_ALPHA shl 24)
        handlePaint.color = Pixel.CYAN
        for (line in chosen.rects) {
            val r = space.toScreen(chosen.page, line)
            canvas.drawRect(r.left, r.top, r.right, r.bottom, selectPaint)
        }
        val first = chosen.rects.firstOrNull() ?: return
        val start = space.toScreen(chosen.page, first)
        val stop = space.toScreen(chosen.page, chosen.rects.last())
        canvas.drawRect(start.left - stem, start.top, start.left, start.bottom, handlePaint)
        canvas.drawRect(start.left - handleSize, start.bottom, start.left, start.bottom + handleSize, handlePaint)
        canvas.drawRect(stop.right, stop.top, stop.right + stem, stop.bottom, handlePaint)
        canvas.drawRect(stop.right, stop.bottom, stop.right + handleSize, stop.bottom + handleSize, handlePaint)
    }
}
