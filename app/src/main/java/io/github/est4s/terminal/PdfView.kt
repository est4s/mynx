package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import io.github.est4s.terminal.core.PdfColumn
import io.github.est4s.terminal.core.PdfRenderSize
import io.github.est4s.terminal.core.PdfZoom
import io.github.est4s.terminal.core.pdfGoToPage
import io.github.est4s.terminal.core.pdfIndicator
import io.github.est4s.terminal.core.pdfPlan
import java.util.concurrent.ConcurrentHashMap

// Behind the pages.
private const val PDF_BACKGROUND = 0xFF4A4D52.toInt()
// A zoom is settled (and rendered sharper) once it hasn't changed for this long.
private const val SETTLE_MS = 250L

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
}

/**
 * The PDF viewer's screen (`PdfViewActivity`, docs/TOUCH-DESIGN.md
 * "PDF"): the top bar with ←, the Debian file [path]'s name over its
 * folder and ⋮ (Go to page, Share, Open with another app), the pages
 * ([PdfPagesView]) and a page indicator ("3 / 12") that fades when
 * they're still. Rendering is the activity's ([PdfViewActions.render]).
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
        }, LayoutParams(MATCH_PARENT, 0, 1f))
        showMessage("Loading…", open = false)
    }

    /** The document is open: its pages' height / width, in order. */
    fun showPages(aspects: List<Float>) {
        message.visibility = View.GONE
        pages.visibility = View.VISIBLE
        if (aspects.isEmpty()) {
            showMessage("$name has no pages.", open = true)
            return
        }
        pages.show(aspects)
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

    /** Esc, G, and the keys that scroll and zoom; true when it was one. */
    fun onKey(event: KeyEvent): Boolean {
        val key = event.keyCode
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
            KeyEvent.KEYCODE_ESCAPE -> actions.close()
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
        if (pages.count > 1) entries += PixelMenuEntry("Go to page") { askPage() }
        entries += PixelMenuEntry("Share") { actions.share() }
        entries += PixelMenuEntry("Open with another app") { actions.openWithAnotherApp() }
        pixelMenu(anchor, entries)
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

    val count: Int get() = aspects.size

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
            moved(zoom.let { column?.current(it.top, it.bottom) ?: 0 }, count)
            return true
        }
    })

    init {
        setBackgroundColor(PDF_BACKGROUND)
        isFocusable = true
    }

    /** The document's pages, height / width each. */
    fun show(pageAspects: List<Float>) {
        aspects = pageAspects
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
    }
}
