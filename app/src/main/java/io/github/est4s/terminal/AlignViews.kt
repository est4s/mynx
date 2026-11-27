package io.github.est4s.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import io.github.est4s.terminal.core.AlignStep
import io.github.est4s.terminal.core.DesktopRuler
import io.github.est4s.terminal.core.ScreenSide

// The ruler's strip, and its short lines' share of it.
private const val STRIP_DP = 28

/**
 * Aligning the desktop across both phones: a ruler strip along one edge
 * of this view ([show]'s edge: LEFT is the view's left edge, ABOVE its
 * top), drawn by the app over the picture. Its lines are a
 * [DesktopRuler]'s, in units that map to this view's pixels as `start +
 * at × scale`, from 0 to `length` of those units; every fifth (1 cm) is
 * long and cyan, the rest short. Both phones draw the same X pixels'
 * lines, so they meet across the seam once the pictures line up.
 */
class AlignRulerView(context: Context) : View(context) {
    private var edge: ScreenSide? = null
    private var ruler: DesktopRuler? = null
    private var start = 0f
    private var scale = 1f
    private var length = 0f
    private val strip = Pixel.dp(context, STRIP_DP).toFloat()
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint().apply { style = Paint.Style.STROKE }

    init {
        // Over the picture, never in the way of its touches.
        isClickable = false
        isFocusable = false
    }

    /** The ruler on [edge]: [ruler]'s lines, at `start + at × scale` of this view's pixels, from 0 to [length] of its units. */
    fun show(edge: ScreenSide, ruler: DesktopRuler, start: Float = 0f, scale: Float = 1f, length: Float) {
        if (edge == this.edge && ruler == this.ruler && start == this.start && scale == this.scale && length == this.length) return
        this.edge = edge
        this.ruler = ruler
        this.start = start
        this.scale = scale
        this.length = length
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        if (edge == null && visibility == GONE) return
        edge = null
        ruler = null
        visibility = GONE
    }

    /** What's shown now (for tests): the edge and the ruler. */
    val shown: Pair<ScreenSide, DesktopRuler>? get() = edge?.let { e -> ruler?.let { Pair(e, it) } }

    override fun onDraw(canvas: Canvas) {
        val e = edge ?: return
        val r = ruler ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val vertical = e == ScreenSide.LEFT || e == ScreenSide.RIGHT
        // The strip: the card's colour, a little see-through, its inner side a line.
        fill.color = (Pixel.CARD and 0x00FFFFFF) or (0xD8 shl 24)
        line.color = Pixel.LINE
        line.strokeWidth = Pixel.dp(context, 1).toFloat()
        when (e) {
            ScreenSide.LEFT -> { canvas.drawRect(0f, 0f, strip, h, fill); canvas.drawLine(strip, 0f, strip, h, line) }
            ScreenSide.RIGHT -> { canvas.drawRect(w - strip, 0f, w, h, fill); canvas.drawLine(w - strip, 0f, w - strip, h, line) }
            ScreenSide.ABOVE -> { canvas.drawRect(0f, 0f, w, strip, fill); canvas.drawLine(0f, strip, w, strip, line) }
            ScreenSide.BELOW -> { canvas.drawRect(0f, h - strip, w, h, fill); canvas.drawLine(0f, h - strip, w, h - strip, line) }
        }
        for (l in r.lines(length)) {
            val at = start + l.at * scale
            if (at < 0f || at > (if (vertical) h else w)) continue
            val reach = if (l.major) strip else strip / 2
            line.color = if (l.major) Pixel.CYAN else Pixel.TEXT
            line.strokeWidth = Pixel.dp(context, if (l.major) 2 else 1).toFloat()
            when (e) {
                ScreenSide.LEFT -> canvas.drawLine(0f, at, reach, at, line)
                ScreenSide.RIGHT -> canvas.drawLine(w, at, w - reach, at, line)
                ScreenSide.ABOVE -> canvas.drawLine(at, 0f, at, reach, line)
                ScreenSide.BELOW -> canvas.drawLine(at, h, at, h - reach, line)
            }
        }
    }
}

// Three side by side fit a 360 dp phone: no minimum width.
private fun TextView.narrow() = apply { minWidth = 0 }

/** The edge of the screen's (the other phone's) picture that faces the host, when it sits on the host's [side] (the host's own edge is [side]). */
fun screenSeamEdge(side: ScreenSide): ScreenSide = when (side) {
    ScreenSide.LEFT -> ScreenSide.RIGHT
    ScreenSide.RIGHT -> ScreenSide.LEFT
    ScreenSide.ABOVE -> ScreenSide.BELOW
    ScreenSide.BELOW -> ScreenSide.ABOVE
}

/**
 * Aligning's panel, on both phones: [pixelMenuBox]'s card with a title,
 * what to do ([hint]), the values ([values]) and [pixelDialog]'s
 * buttons, Cancel, Reset and Done (filled), which call [step].
 */
class AlignPanel(context: Context, hint: String, private val step: (AlignStep) -> Unit) : LinearLayout(context) {
    private val valuesText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.TEXT)
    }
    private val hintText = TextView(context).apply {
        text = hint
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.DIM)
        setLineSpacing(0f, 1.15f)
    }

    init {
        orientation = VERTICAL
        val dp = { v: Int -> Pixel.dp(context, v) }
        val shadow = dp(Pixel.SHADOW_DP)
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.CYAN, shadow = true)
        setPadding(dp(20), dp(16), dp(20) + shadow, dp(16) + shadow)
        isClickable = true
        addView(TextView(context).apply {
            text = "Align screens"
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Pixel.TEXT)
        })
        addView(hintText, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(valuesText, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            addView(pixelButton(context, "Cancel", Pixel.CARD, Pixel.LINE, Pixel.TEXT) { step(AlignStep.CANCEL) }.narrow())
            addView(pixelButton(context, "Reset", Pixel.CARD, Pixel.LINE, Pixel.TEXT) { step(AlignStep.RESET) }.narrow(),
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
            addView(pixelButton(context, "Done", Pixel.CYAN, 0, Pixel.BACKGROUND) { step(AlignStep.DONE) }.narrow(),
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    /** The values as they are now ("offset +1.5 mm · scale 1.012"). */
    var values: String
        get() = valuesText.text.toString()
        set(value) {
            if (valuesText.text.toString() != value) valuesText.text = value
        }

    var hint: String
        get() = hintText.text.toString()
        set(value) {
            if (hintText.text.toString() != value) hintText.text = value
        }
}
