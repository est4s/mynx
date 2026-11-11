package io.github.est4s.terminal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.est4s.terminal.core.StripColors
import kotlin.math.roundToInt

private const val LABEL_FONT = "fonts/PixelifySans.ttf"
private const val TEXT_FONT = "fonts/JetBrainsMonoNerdFontMono-Regular.ttf"

/** Touch mode's pixel style, from the logo (docs/TOUCH-DESIGN.md "Style"). */
object Pixel {
    const val BACKGROUND: Int = 0xFF0D0221.toInt()
    const val CARD: Int = 0xFF170838.toInt()
    const val LINE: Int = 0xFF3D2A70.toInt()
    const val TEXT: Int = 0xFFF2ECFF.toInt()
    const val DIM: Int = 0xFFB3A6DC.toInt()
    const val CYAN: Int = 0xFF00F3FF.toInt()
    const val PINK: Int = 0xFFFF0BBB.toInt()
    const val PINK_TEXT: Int = 0xFFFF7FDC.toInt()
    const val SCRIM: Int = 0xB30D0221.toInt()

    const val SHADOW_DP = 4

    /** The palette as the views that took the theme's strip colours read it: Chat, its sheets, the history. */
    val COLORS = StripColors(background = BACKGROUND, selectedBackground = CARD, accent = CYAN, mark = PINK, text = TEXT)
    private const val BORDER_DP = 2

    private val labels = HashMap<Int, Typeface>()
    private var mono: Typeface? = null

    /** Pixelify Sans (assets/fonts/PixelifySans.ttf) at [weight] 400-700 (variable font; Typeface.Builder + setFontVariationSettings), cached; Typeface.DEFAULT_BOLD if missing. */
    fun label(context: Context, weight: Int = 500): Typeface {
        val w = weight.coerceIn(400, 700)
        synchronized(labels) {
            labels[w]?.let { return it }
            // The fonts are fetched in CI, never committed: a clone's tests
            // run without them, so a missing asset falls back, never throws.
            val face = if (!hasAsset(context, LABEL_FONT)) null else runCatching {
                Typeface.Builder(context.assets, LABEL_FONT)
                    .setFontVariationSettings("'wght' $w")
                    .setWeight(w)
                    .build()
            }.getOrNull()
            return (face ?: Typeface.DEFAULT_BOLD).also { labels[w] = it }
        }
    }

    /** JetBrains Mono Nerd Font Mono (assets/fonts/JetBrainsMonoNerdFontMono-Regular.ttf), cached; Typeface.MONOSPACE if missing. */
    fun text(context: Context): Typeface {
        mono?.let { return it }
        val face = if (!hasAsset(context, TEXT_FONT)) null else runCatching {
            Typeface.createFromAsset(context.assets, TEXT_FONT)
        }.getOrNull()
        return (face ?: Typeface.MONOSPACE).also { mono = it }
    }

    /** A square box: [fill], a 2 dp [border] (0 = none), and with [shadow] the logo's hard pink shadow 4 dp right and down (the view needs 4 dp more room right and bottom: [SHADOW_DP]); pressed state from a ripple in [ripple] (null = not pressable). */
    fun box(context: Context, fill: Int = CARD, border: Int = LINE, shadow: Boolean = false, ripple: Int? = null): Drawable {
        val face = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(fill)
            if (border != 0) setStroke(dp(context, BORDER_DP), border)
        }
        val pressable: Drawable =
            if (ripple == null) face else RippleDrawable(ColorStateList.valueOf(ripple), face, null)
        if (!shadow) return pressable
        val drop = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(PINK)
        }
        val s = dp(context, SHADOW_DP)
        return LayerDrawable(arrayOf(drop, pressable)).apply {
            setLayerInset(0, s, s, 0, 0)
            setLayerInset(1, 0, 0, s, s)
        }
    }

    /** A filled block icon drawn on a 20-unit grid (no curves), [sizeDp] square, in [color]. */
    fun icon(context: Context, icon: PixelIcon, color: Int, sizeDp: Int = 20): Drawable =
        PixelIconDrawable(cells(icon), color, dp(context, sizeDp))

    /** A square thumb in [LINE] for [view]'s scroll bar (API 29+; older phones keep theirs). */
    fun scrollbar(view: View) {
        if (Build.VERSION.SDK_INT < 29) return
        view.verticalScrollbarThumbDrawable = ColorDrawable(LINE)
        view.verticalScrollbarTrackDrawable = null
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun hasAsset(context: Context, path: String): Boolean =
        runCatching { context.assets.open(path).close() }.isSuccess

    /** Each icon's blocks as x, y, w, h on the 20-unit grid. */
    private fun cells(icon: PixelIcon): IntArray = when (icon) {
        PixelIcon.MENU -> intArrayOf(3, 4, 14, 2, 3, 9, 14, 2, 3, 14, 14, 2)
        PixelIcon.PLUS -> intArrayOf(9, 3, 2, 14, 3, 9, 14, 2)
        PixelIcon.MIC -> intArrayOf(
            7, 2, 6, 9,                  // the capsule
            4, 8, 2, 4, 14, 8, 2, 4, 5, 12, 10, 2, // its cup
            9, 14, 2, 3, 6, 17, 8, 2,    // stand and base
        )
        PixelIcon.SEND -> intArrayOf(9, 2, 2, 2, 7, 4, 6, 2, 5, 6, 10, 2, 8, 8, 4, 10)
        PixelIcon.STOP -> intArrayOf(5, 5, 10, 10)
        PixelIcon.BACK -> intArrayOf(2, 9, 2, 2, 4, 7, 2, 6, 6, 5, 2, 10, 8, 8, 10, 4)
        PixelIcon.CHEVRON -> intArrayOf(7, 4, 3, 2, 9, 6, 3, 2, 11, 8, 3, 4, 9, 12, 3, 2, 7, 14, 3, 2)
        PixelIcon.TERMINAL -> intArrayOf(
            2, 3, 16, 2, 2, 15, 16, 2, 2, 3, 2, 14, 16, 3, 2, 14, // the frame
            5, 7, 2, 2, 7, 9, 2, 2, 5, 11, 2, 2,                  // >
            10, 11, 5, 2,                                         // _
        )
        PixelIcon.CHAT -> intArrayOf(
            2, 3, 16, 2, 2, 12, 16, 2, 2, 3, 2, 11, 16, 3, 2, 11, // the bubble
            5, 14, 4, 2, 5, 16, 2, 2,                             // its tail
            6, 7, 8, 2,                                           // a line of text
        )
        PixelIcon.MORE -> intArrayOf(8, 2, 4, 4, 8, 8, 4, 4, 8, 14, 4, 4)
        PixelIcon.CHECK -> intArrayOf(2, 10, 3, 3, 4, 12, 3, 3, 6, 14, 3, 3, 9, 11, 3, 3, 11, 8, 3, 3, 13, 5, 3, 3, 15, 3, 3, 3)
        PixelIcon.CAMERA -> intArrayOf(
            2, 6, 16, 2, 2, 15, 16, 2, 2, 6, 2, 11, 16, 6, 2, 11, // the body
            7, 3, 6, 3,                                         // its top
            8, 9, 4, 4,                                         // the lens
        )
        PixelIcon.IMAGE -> intArrayOf(
            2, 3, 16, 2, 2, 15, 16, 2, 2, 3, 2, 14, 16, 3, 2, 14, // the frame
            4, 11, 3, 4, 7, 8, 3, 7, 10, 11, 3, 4,                // a hill
            12, 6, 3, 3,                                        // the sun
        )
        PixelIcon.FILE -> intArrayOf(
            4, 2, 8, 2, 4, 2, 2, 16, 4, 16, 12, 2, 14, 6, 2, 12, // the page
            10, 2, 2, 6, 10, 6, 6, 2,                           // its folded corner
            7, 10, 5, 2, 7, 13, 5, 2,                           // two lines
        )
        PixelIcon.BOLT -> intArrayOf(11, 1, 3, 3, 9, 4, 3, 3, 7, 7, 3, 2, 7, 9, 8, 2, 11, 11, 3, 3, 9, 14, 3, 3, 7, 17, 3, 2)
        PixelIcon.CHIP -> intArrayOf(
            5, 5, 10, 10,                                       // the chip
            7, 2, 2, 3, 11, 2, 2, 3, 7, 15, 2, 3, 11, 15, 2, 3, // its pins
            2, 7, 3, 2, 2, 11, 3, 2, 15, 7, 3, 2, 15, 11, 3, 2,
        )
        PixelIcon.REFRESH -> intArrayOf(
            4, 4, 10, 2, 4, 4, 2, 12, 4, 14, 12, 2, 14, 9, 2, 7, // the loop
            14, 2, 2, 6, 16, 4, 2, 2,                           // its arrow
        )
        PixelIcon.CLOSE -> IntArray(7 * 8).also { a ->
            for (k in 0 until 7) {
                val i = 3 + 2 * k
                intArrayOf(i, i, 2, 2, i, 18 - i, 2, 2).copyInto(a, k * 8)
            }
        }
    }
}

/** One row of [pixelMenu]; a [greyed] one still answers a tap (to say why it can't go). */
class PixelMenuEntry(val label: String, val greyed: Boolean = false, val onTap: () -> Unit)

/** A small menu in the pixel style, dropped from [anchor]'s end: Android's PopupMenu takes the system theme. */
fun pixelMenu(anchor: View, entries: List<PixelMenuEntry>) {
    val context = anchor.context
    val dp = { v: Int -> Pixel.dp(context, v) }
    val shadow = dp(Pixel.SHADOW_DP)
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.CYAN, shadow = true)
        // Inside the 2 dp border, and the shadow's room right and bottom.
        setPadding(dp(2), dp(2), dp(2) + shadow, dp(2) + shadow)
        minimumWidth = dp(200)
    }
    val popup = PopupWindow(column, WRAP_CONTENT, WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
    }
    entries.forEach { entry ->
        column.addView(TextView(context).apply {
            text = entry.label
            typeface = Pixel.label(context, 500)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (entry.greyed) Pixel.DIM else Pixel.TEXT)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            background = pixelRipple()
            isFocusable = true
            setOnClickListener {
                popup.dismiss()
                entry.onTap()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    popup.showAsDropDown(anchor, 0, 0, Gravity.END)
}

enum class PixelIcon { MENU, PLUS, MIC, SEND, STOP, BACK, CHEVRON, TERMINAL, CHAT, CLOSE, MORE, CHECK, CAMERA, IMAGE, FILE, BOLT, CHIP, REFRESH }

/** Text in another typeface (TypefaceSpan(Typeface) is API 28+): Pixelify Sans for headings in Chat's Markdown. */
class PixelFontSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = face
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = face
    }
}

/** Fills [cells] (x, y, w, h on a 20-unit grid) scaled to its bounds, edges rounded to whole pixels so blocks stay crisp. */
private class PixelIconDrawable(
    private val cells: IntArray,
    color: Int,
    private val sizePx: Int,
) : Drawable() {
    private val baseAlpha = Color.alpha(color)
    private val paint = Paint().apply {
        this.color = color
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val unit = minOf(b.width(), b.height()) / 20f
        val left = b.left + (b.width() - unit * 20f) / 2f
        val top = b.top + (b.height() - unit * 20f) / 2f
        var i = 0
        while (i + 3 < cells.size) {
            val x = cells[i]; val y = cells[i + 1]; val w = cells[i + 2]; val h = cells[i + 3]
            canvas.drawRect(
                (left + x * unit).roundToInt().toFloat(),
                (top + y * unit).roundToInt().toFloat(),
                (left + (x + w) * unit).roundToInt().toFloat(),
                (top + (y + h) * unit).roundToInt().toFloat(),
                paint,
            )
            i += 4
        }
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        paint.alpha = baseAlpha * alpha / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
