package io.github.est4s.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import io.github.est4s.terminal.core.ColorScheme
import io.github.est4s.terminal.core.MdAlign
import io.github.est4s.terminal.core.MdImage
import io.github.est4s.terminal.core.MdRow
import io.github.est4s.terminal.core.MdStyleKind
import io.github.est4s.terminal.core.MdStyled
import io.github.est4s.terminal.core.NEON
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.SyntaxKind
import io.github.est4s.terminal.core.SyntaxSpan
import io.github.est4s.terminal.core.highlight
import io.github.est4s.terminal.core.loadColorScheme
import io.github.est4s.terminal.core.syntaxColor
import io.github.est4s.terminal.core.syntaxLanguage
import java.io.File
import kotlin.math.ceil

// One level of a list or quote.
private const val MD_INDENT_DP = 18
// Wider cells wrap: a table of prose stays readable, and the table scrolls sideways.
private const val MD_CELL_MAX_DP = 220
// A picture in a Markdown file, at most this high.
private const val MD_IMAGE_MAX_DP = 480
private val MD_HEADING_SCALE = floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f)
private const val COLORS_FILE = "root/.config/mynx/colors.properties"

// Code and tables scroll sideways; nothing in them needs scrolling to when it
// takes the focus: a tall selectable row that did would make the rows jump.
internal class MdSideScroll(context: Context) : HorizontalScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

/** A link in Markdown text. Selectable text ignores a ClickableSpan's taps: the host's text finds them itself. */
class MdLink(val url: String, private val color: Int, private val open: (String) -> Unit) : ClickableSpan() {
    override fun onClick(widget: View) = open(url)

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = true
    }
}

/** A syntax colour: a span of its own, so highlighting again removes only these. */
class SyntaxColorSpan(color: Int) : ForegroundColorSpan(color)

/** Colours [text] with [spans] (core's [highlight]) in [scheme]'s colours, replacing any it had. */
fun applySyntax(text: Spannable, spans: List<SyntaxSpan>, scheme: ColorScheme) {
    text.getSpans(0, text.length, SyntaxColorSpan::class.java).forEach { text.removeSpan(it) }
    val colors = HashMap<SyntaxKind, Int>()
    for (span in spans) {
        val end = minOf(span.end, text.length)
        if (span.start >= end) continue
        val color = colors.getOrPut(span.kind) { syntaxColor(span.kind, scheme) }
        text.setSpan(SyntaxColorSpan(color), span.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** [code] highlighted as [language] if core knows it, else as it is. */
fun syntaxSpanned(code: String, language: String?, scheme: ColorScheme): CharSequence {
    val name = language?.let(::syntaxLanguage) ?: return code
    return SpannableString(code).also { applySyntax(it, highlight(code, name), scheme) }
}

private object TerminalScheme {
    private var read: Pair<Long, ColorScheme>? = null

    @Synchronized
    fun get(context: Context): ColorScheme {
        val file = File(RootfsInstaller(context.filesDir).rootfs, COLORS_FILE)
        val modified = file.lastModified()
        read?.let { (time, scheme) -> if (time == modified) return scheme }
        val scheme = runCatching { loadColorScheme(file).scheme }.getOrDefault(NEON)
        read = modified to scheme
        return scheme
    }
}

/**
 * The terminal's colours (`~/.config/mynx/colors.properties`), Neon when
 * there are none or they can't be read: what code is highlighted in.
 * Read again only when the file changes.
 */
fun terminalColorScheme(context: Context): ColorScheme = TerminalScheme.get(context)

/**
 * Markdown drawn as views, a [Cell] per row of core's `mdRows`: chat's
 * replies and the text viewer's Markdown files. [makeText] makes the host's
 * selectable text (its taps on [MdLink]s are its own); [openLink] opens a
 * link or picture's URL as the host allows. Code blocks with a language
 * core knows are highlighted in [scheme]'s colours (the terminal's: chat
 * passes [terminalColorScheme]) checked against the block's own fill.
 * [loadImage] gets a picture's URL (as written: relative to the file) and
 * calls back on the main thread with it, or null; without it pictures
 * show as their alt text.
 */
class MarkdownViews(
    private val context: Context,
    private val colors: StripColors,
    private val makeText: (color: Int, sp: Float, links: Boolean) -> TextView,
    private val openLink: (String) -> Unit,
    scheme: ColorScheme = terminalColorScheme(context),
    private val loadImage: ((String, (Bitmap?) -> Unit) -> Unit)? = null,
) {
    /** A row's views; [bind] shows a row of the same [type] in them again. */
    class Cell(val type: Class<*>, val view: View, val bind: (MdRow) -> Unit)

    private val codeScheme = scheme.copy(background = colors.selectedBackground)

    /** [row]'s place in a vertical LinearLayout, after [before]. */
    fun params(row: MdRow, before: MdRow?): LinearLayout.LayoutParams {
        val height = if (row is MdRow.Rule) dp(2) else WRAP_CONTENT
        return LinearLayout.LayoutParams(MATCH_PARENT, height).apply {
            topMargin = when {
                before == null -> 0
                row is MdRow.Text && row.heading > 0 -> dp(12)
                row is MdRow.Text && row.marker != null && before is MdRow.Text && before.marker != null -> dp(3)
                else -> dp(8)
            }
            // Text rows indent with spans, for the hanging indent.
            if (row !is MdRow.Text) leftMargin = (row.indent + row.quote) * dp(MD_INDENT_DP)
        }
    }

    /** A new cell for [row], not yet bound. */
    fun cell(row: MdRow): Cell = when (row) {
        is MdRow.Text -> textCell(row)
        is MdRow.Code -> codeCell(row)
        is MdRow.Table -> tableCell(row)
        is MdRow.Rule -> Cell(row.javaClass, View(context).apply { setBackgroundColor(Pixel.LINE) }) { }
        is MdRow.Images -> imagesCell(row)
    }

    /** Every row of [rows] into [column] (emptied first): a whole file at once. */
    fun fill(column: LinearLayout, rows: List<MdRow>) {
        column.removeAllViews()
        rows.forEachIndexed { i, row ->
            val made = cell(row)
            made.bind(row)
            column.addView(made.view, params(row, rows.getOrNull(i - 1)))
        }
    }

    private fun textCell(first: MdRow.Text): Cell {
        val view = makeText(colors.text, 15f, true).apply { setLineSpacing(0f, 1.15f) }
        return Cell(first.javaClass, view) { row -> view.text = rowText(row as MdRow.Text, view) }
    }

    private fun rowText(row: MdRow.Text, view: TextView): CharSequence {
        val out = SpannableStringBuilder()
        val marker = row.marker
        if (marker != null) out.append(marker).append(' ')
        out.append(spanned(row.text))
        val end = out.length
        if (end == 0) return out
        if (row.heading > 0) {
            out.setSpan(PixelFontSpan(Pixel.label(context, 700)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(MD_HEADING_SCALE[row.heading - 1]), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        repeat(row.quote) {
            out.setSpan(QuoteSpan(colors.accent), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard(dp(8)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val first = row.indent * dp(MD_INDENT_DP)
        val rest = if (marker != null) first + ceil(view.paint.measureText("$marker ")).toInt() else first
        if (rest > 0) {
            // A line break in an item starts a paragraph, which would get the first line's margin.
            val breakAt = out.indexOf('\n')
            val firstEnd = if (breakAt < 0) end else breakAt + 1
            out.setSpan(LeadingMarginSpan.Standard(first, rest), 0, firstEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (firstEnd < end) out.setSpan(LeadingMarginSpan.Standard(rest), firstEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** [styled]'s text with its styles as spans. */
    fun spanned(styled: MdStyled): SpannableString {
        val out = SpannableString(styled.text)
        for (style in styled.styles) {
            if (style.start >= style.end) continue
            val spans: List<Any> = when (style.kind) {
                MdStyleKind.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                MdStyleKind.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                MdStyleKind.STRIKE -> listOf(StrikethroughSpan())
                // All of it is in the mono font already: code stands out in cyan, as in the mock-up.
                MdStyleKind.CODE -> listOf(ForegroundColorSpan(colors.accent))
                // A picture inside text is its alt text, a link to the picture.
                MdStyleKind.LINK, MdStyleKind.IMAGE -> listOf(MdLink(style.url.orEmpty(), colors.accent, openLink))
            }
            spans.forEach { out.setSpan(it, style.start, style.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return out
    }

    private fun codeCell(first: MdRow.Code): Cell {
        var code = ""
        val language = plainText(Pixel.DIM, 12f).apply { typeface = Pixel.label(context, 500) }
        val copy = plainText(colors.accent, 15f).apply {
            typeface = Pixel.label(context, 600)
            text = "Copy"
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(36)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = RippleDrawable(ColorStateList.valueOf((colors.accent and 0x00FFFFFF) or 0x40000000), null, ColorDrawable(colors.text))
            isFocusable = true
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                text = "Copied"
                postDelayed({ text = "Copy" }, 1500L)
            }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(4), 0)
            addView(language, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(copy)
        }
        val body = makeText(colors.text, 13f, false).apply {
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE)
            addView(head)
            addView(MdSideScroll(context).apply { addView(body) })
        }
        return Cell(first.javaClass, card) { row ->
            val block = row as MdRow.Code
            code = block.text
            language.text = block.language.orEmpty()
            body.text = syntaxSpanned(block.text, block.language, codeScheme)
        }
    }

    private fun tableCell(first: MdRow.Table): Cell {
        val grid = TableLayout(context).apply {
            setDividerDrawable(GradientDrawable().apply {
                setColor(Pixel.LINE)
                setSize(1, dp(2))
            })
            setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE)
        }
        var drawnLines: List<List<MdStyled>> = emptyList()
        return Cell(first.javaClass, MdSideScroll(context).apply { addView(grid) }) { row ->
            val table = (row as MdRow.Table).table
            val lines = listOf(table.header) + table.rows
            lines.forEachIndexed { r, line ->
                if (line == drawnLines.getOrNull(r)) return@forEachIndexed
                val rowView = grid.getChildAt(r) as? TableRow ?: TableRow(context).also { made ->
                    if (r == 0) made.setBackgroundColor(colors.selectedBackground)
                    grid.addView(made)
                }
                while (rowView.childCount > line.size) rowView.removeViewAt(rowView.childCount - 1)
                line.forEachIndexed { c, styled ->
                    val cell = rowView.getChildAt(c) as? TextView ?: tableText(r == 0).also { rowView.addView(it) }
                    cell.gravity = when (table.aligns.getOrNull(c)) {
                        MdAlign.CENTER -> Gravity.CENTER_HORIZONTAL
                        MdAlign.RIGHT -> Gravity.END
                        else -> Gravity.START
                    }
                    cell.text = spanned(styled)
                }
            }
            while (grid.childCount > lines.size) grid.removeViewAt(grid.childCount - 1)
            drawnLines = lines
        }
    }

    private fun tableText(header: Boolean) = makeText(colors.text, 14f, true).apply {
        maxWidth = dp(MD_CELL_MAX_DP)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        if (header) setTypeface(typeface, Typeface.BOLD)
    }

    // Each picture on a line of its own; until it's loaded (or if it can't be), its alt text.
    private fun imagesCell(first: MdRow.Images): Cell {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        return Cell(first.javaClass, column) { row ->
            column.removeAllViews()
            (row as MdRow.Images).images.forEach { image ->
                column.addView(picture(image), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
        }
    }

    private fun picture(image: MdImage): View {
        val target = image.link ?: image.url
        val label = plainText(colors.accent, 14f).apply {
            text = "▣ " + image.alt.ifBlank { image.url.substringAfterLast('/') }
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
        }
        val shown = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            maxHeight = dp(MD_IMAGE_MAX_DP)
            contentDescription = image.alt
            visibility = View.GONE
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openLink(target) }
            addView(shown, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        loadImage?.invoke(image.url) { bitmap ->
            if (bitmap != null) {
                shown.setImageBitmap(bitmap)
                shown.visibility = View.VISIBLE
                label.visibility = View.GONE
            }
        }
        return box
    }

    private fun plainText(color: Int, sp: Float) = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}
