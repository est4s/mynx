package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.est4s.terminal.core.ColorScheme
import io.github.est4s.terminal.core.DiffLineKind
import io.github.est4s.terminal.core.DiffRow
import io.github.est4s.terminal.core.FileDiff
import io.github.est4s.terminal.core.SyntaxKind
import io.github.est4s.terminal.core.diffSummary
import io.github.est4s.terminal.core.diffTints
import io.github.est4s.terminal.core.syntaxColor
import kotlin.math.ceil

private const val DIFF_SP = 14f
// Past this many columns a line isn't laid out whole without wrapping (a minified file's line).
private const val MOST_COLUMNS = 1500

/** What the diff view asks of its activity. */
interface DiffViewActions {
    /** ← or Back. */
    fun close()

    /** Open file: the file in mynx's viewer. */
    fun openFile()

    /** Share the diff, as a unified `.diff`. */
    fun shareDiff()
}

/**
 * The diff view's screen (`DiffViewActivity`, docs/TOUCH-DESIGN.md "A
 * turn's changes"): a turn's changes to the Debian file [path], drawn in
 * [scheme]'s colours. The top bar has ←, the name over its folder, the
 * counts (`+N −M`) and ⋮ (Open file, Share the diff, Wrap lines). The
 * body is core's [DiffRow]s in a [ListView], so only the rows in sight
 * are laid out however long the diff is: each line with its old and new
 * numbers, a `+`/`−` mark, added and removed lines on core's
 * [diffTints], the text in its language's colours (checked against its
 * own row's tint). Unwrapped, the list is as wide as its longest line and
 * scrolls sideways.
 */
@SuppressLint("ViewConstructor")
class DiffView(
    context: Context,
    private val path: String,
    private val scheme: ColorScheme,
    private val actions: DiffViewActions,
) : LinearLayout(context) {
    /** The diff shown; null while loading, or when it's too big or failed. */
    var diff: FileDiff? = null
        private set

    private var rows: List<DiffRow> = emptyList()
    private var wrap = true
    private var digits = 1
    private val tints = diffTints(scheme)
    private val dimText = (scheme.foreground and 0x00FFFFFF) or 0x80000000.toInt()
    private val headerBackground = mix(scheme.background, scheme.foreground, 0.08)
    private val syntaxColors = HashMap<Int, HashMap<SyntaxKind, Int>>()

    private val name = path.trimEnd('/').substringAfterLast('/')
    private val nameText = TextView(context).apply {
        typeface = Pixel.label(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        text = name
    }
    private val folderText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Pixel.DIM)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        text = path.substringBeforeLast('/', "").ifEmpty { "/" }.replaceFirst(Regex("^/root(?=/|$)"), "~")
    }
    private val countsText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.DIM)
        maxLines = 1
        setPadding(dp(8), 0, dp(4), 0)
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
            addView(nameText)
            addView(folderText)
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(countsText, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(iconButton(PixelIcon.MORE, Pixel.TEXT, "More") { showMore(it) }, LayoutParams(dp(48), dp(52)))
    }

    private val list = DiffList(context).apply {
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(0)
        cacheColorHint = 0
        isVerticalScrollBarEnabled = true
        setBackgroundColor(scheme.background)
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(24))
        isFocusable = true
        Pixel.scrollbar(this)
    }
    private val side = HorizontalScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(scheme.background)
    }
    private val messageText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.DIM)
        gravity = Gravity.CENTER
    }
    private val openButton = TextView(context).apply {
        text = "Open file"
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Pixel.BACKGROUND)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
        isFocusable = true
        setOnClickListener { actions.openFile() }
    }
    private val message = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Pixel.BACKGROUND)
        addView(messageText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(openButton, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }
    private val content = FrameLayout(context)

    // A row's measure: the width of one character of the monospaced font.
    private val charWidth: Float by lazy { rowText().paint.measureText("0") }

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(MATCH_PARENT, dp(2)))
        addView(content, LayoutParams(MATCH_PARENT, 0, 1f))
        showMessage("Loading…", open = false)
    }

    /** Shows [shown], laid out as core's [rows] (`diffRows`). */
    fun showDiff(shown: FileDiff, rows: List<DiffRow>) {
        diff = shown
        this.rows = rows
        var most = 1
        for (row in rows) if (row is DiffRow.Line) most = maxOf(most, row.line.oldNumber ?: 0, row.line.newNumber ?: 0)
        digits = most.toString().length
        countsText.text = counts(shown)
        if (rows.isEmpty()) {
            showMessage("No changes.", open = true)
            return
        }
        renderBody()
    }

    /** A side was over core's limit: nothing to show but the file. */
    fun showTooBig() {
        diff = null
        countsText.text = "too big"
        showMessage("$name is too big to diff here.", open = true)
    }

    /** The changes can't be read: [text] says why. */
    fun showError(text: String) {
        diff = null
        showMessage(text, open = true)
    }

    /** Esc, and the keys that scroll; true when it was one. */
    fun onKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        val step = when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> dp(60)
            KeyEvent.KEYCODE_DPAD_UP -> -dp(60)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> list.height * 9 / 10
            KeyEvent.KEYCODE_PAGE_UP -> -list.height * 9 / 10
            KeyEvent.KEYCODE_DPAD_LEFT -> -dp(120)
            KeyEvent.KEYCODE_DPAD_RIGHT -> dp(120)
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_ESCAPE -> 0
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (key) {
            KeyEvent.KEYCODE_ESCAPE -> actions.close()
            KeyEvent.KEYCODE_MOVE_HOME -> list.setSelection(0)
            KeyEvent.KEYCODE_MOVE_END -> list.setSelection(rows.size - 1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!wrap) side.smoothScrollBy(step, 0)
            else -> list.smoothScrollBy(step, 120)
        }
        return true
    }

    // Drawing

    private fun showMessage(text: String, open: Boolean) {
        messageText.text = text
        openButton.visibility = if (open) View.VISIBLE else View.GONE
        content.removeAllViews()
        content.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun renderBody() {
        val first = list.firstVisiblePosition
        content.removeAllViews()
        (list.parent as? ViewGroup)?.removeView(list)
        side.removeAllViews()
        if (wrap) {
            list.fixedWidth = 0
            content.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            list.fixedWidth = listWidth()
            side.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            content.addView(side, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // A new adapter: the rows' views are made again, wrapping or not.
        list.adapter = Rows()
        list.setSelection(first.coerceAtMost(rows.size - 1).coerceAtLeast(0))
    }

    // As wide as the longest row (tabs counted as 4), up to MOST_COLUMNS.
    private fun listWidth(): Int {
        var columns = 0
        for (row in rows) {
            val length = when (row) {
                is DiffRow.Line -> gutterLength() + row.line.text.length + 3 * row.line.text.count { it == '\t' }
                is DiffRow.Header -> row.text.length
                else -> 0
            }
            columns = maxOf(columns, length)
        }
        return ceil(minOf(columns, MOST_COLUMNS) * charWidth).toInt() + dp(8) + dp(24)
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as? TextView ?: rowText()
            bind(view, rows[position])
            return view
        }
    }

    private fun rowText() = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, DIFF_SP)
        setLineSpacing(0f, 1.1f)
        setHorizontallyScrolling(!wrap)
        if (!wrap) maxLines = 1
        layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    // Old number, new number, mark: "  12   13 + ".
    private fun gutterLength() = digits * 2 + 4

    private fun gutter(old: Int?, new: Int?, mark: Char): String =
        (old?.toString() ?: "").padStart(digits) + " " + (new?.toString() ?: "").padStart(digits) + " " + mark + " "

    private fun bind(view: TextView, row: DiffRow) {
        view.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        when (row) {
            is DiffRow.Header -> {
                view.setPadding(dp(8), dp(6), dp(16), dp(6))
                view.setBackgroundColor(headerBackground)
                view.setTextColor(syntaxColorOn(headerBackground, SyntaxKind.FUNCTION))
                view.text = row.text
            }
            is DiffRow.Fold -> {
                view.setPadding(dp(8), dp(6), dp(16), dp(6))
                view.setBackgroundColor(scheme.background)
                view.setTextColor(dimText)
                view.text = " ".repeat(gutterLength()) + "⋯ ${row.lines} ${if (row.lines == 1) "line" else "lines"}"
            }
            is DiffRow.NoNewline -> {
                view.setPadding(dp(8), 0, dp(16), dp(2))
                view.setBackgroundColor(scheme.background)
                view.setTextColor(dimText)
                view.text = " ".repeat(gutterLength()) + "\\ No newline at end of file"
            }
            is DiffRow.Line -> {
                val line = row.line
                val (background, mark) = when (line.kind) {
                    DiffLineKind.ADDED -> tints.added to '+'
                    DiffLineKind.REMOVED -> tints.removed to '−'
                    DiffLineKind.SAME -> scheme.background to ' '
                }
                view.setPadding(dp(8), 0, dp(16), 0)
                view.setBackgroundColor(background)
                view.setTextColor(scheme.foreground)
                val start = gutter(line.oldNumber, line.newNumber, mark)
                val text = SpannableStringBuilder(start).append(line.text)
                text.setSpan(ForegroundColorSpan(dimText), 0, start.length - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (line.kind != DiffLineKind.SAME) {
                    val kind = if (line.kind == DiffLineKind.ADDED) SyntaxKind.ADDED else SyntaxKind.REMOVED
                    text.setSpan(ForegroundColorSpan(syntaxColorOn(background, kind)), start.length - 2, start.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                for (span in row.spans) {
                    val from = start.length + span.start
                    val to = minOf(start.length + span.end, text.length)
                    if (from >= to) continue
                    text.setSpan(ForegroundColorSpan(syntaxColorOn(background, span.kind)), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // Wrapped rows continue under the text, not under the numbers.
                if (wrap) text.setSpan(LeadingMarginSpan.Standard(0, ceil(start.length * charWidth).toInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                view.text = SpannableString(text)
            }
        }
    }

    // [kind]'s colour checked against [background] (a row's tint), as core picks it for a theme.
    private fun syntaxColorOn(background: Int, kind: SyntaxKind): Int =
        syntaxColors.getOrPut(background) { HashMap() }.getOrPut(kind) { syntaxColor(kind, scheme.copy(background = background)) }

    // `+12 −3` in the top bar, the numbers in green and red.
    private fun counts(shown: FileDiff): CharSequence {
        val summary = diffSummary(shown)
        val bar = scheme.copy(background = Pixel.BACKGROUND)
        val text = SpannableString(summary)
        if (summary.startsWith("+")) {
            val space = summary.indexOf(' ').takeIf { it > 0 } ?: summary.length
            text.setSpan(ForegroundColorSpan(syntaxColor(SyntaxKind.ADDED, bar)), 0, space, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (space < summary.length) {
                text.setSpan(ForegroundColorSpan(syntaxColor(SyntaxKind.REMOVED, bar)), space + 1, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (shown.isNew) {
            text.setSpan(ForegroundColorSpan(syntaxColor(SyntaxKind.ADDED, bar)), 0, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    // ⋮

    private fun showMore(anchor: View) {
        val shown = diff
        val entries = ArrayList<PixelMenuEntry>()
        entries += PixelMenuEntry("Open file") { actions.openFile() }
        if (shown != null && shown.hunks.isNotEmpty()) entries += PixelMenuEntry("Share the diff") { actions.shareDiff() }
        if (shown != null && rows.isNotEmpty()) entries += PixelMenuEntry(if (wrap) "Wrap lines  ✓" else "Wrap lines") { toggleWrap() }
        pixelMenu(anchor, entries)
    }

    private fun toggleWrap() {
        wrap = !wrap
        renderBody()
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

// [from] moved [amount] of the way to [to], opaque.
private fun mix(from: Int, to: Int, amount: Double): Int {
    fun channel(shift: Int): Int {
        val a = from shr shift and 0xff
        val b = to shr shift and 0xff
        return (a + (b - a) * amount).toInt() shl shift
    }
    return (0xff shl 24) or channel(16) or channel(8) or channel(0)
}

/**
 * The rows' list. Unwrapped it sits in a HorizontalScrollView, which
 * measures its child's width as UNSPECIFIED (a ListView then takes its
 * first row's width): [fixedWidth], when set, is the width it takes, at
 * least the screen's.
 */
private class DiffList(context: Context) : ListView(context) {
    var fixedWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (fixedWidth > 0) {
            MeasureSpec.makeMeasureSpec(maxOf(fixedWidth, MeasureSpec.getSize(widthMeasureSpec)), MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}
