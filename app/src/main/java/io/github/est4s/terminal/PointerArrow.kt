package io.github.est4s.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The desktop's pointer, our own arrow: the X server's cursor needs its
 * shared state, which this side doesn't map, and it draws none into the
 * other phone's part either. On the desktop's view, and over a stream of
 * the desktop on the other phone.
 */
class PointerArrow(context: Context) : View(context) {
    private var px = -1f
    private var py = -1f
    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    // Drawn in dp (the canvas is scaled), so the outline is 1.2 dp.
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        strokeJoin = Paint.Join.ROUND
    }
    // The usual arrow, its tip at 0, 0, in dp.
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4.5f, 13f); lineTo(7.5f, 20f)
        lineTo(10.5f, 18.8f); lineTo(7.5f, 12f); lineTo(13f, 12f); close()
    }

    init {
        isClickable = false
        isFocusable = false
    }

    /** Its tip at [x], [y] (view pixels). */
    fun at(x: Float, y: Float) {
        px = x
        py = y
        invalidate()
    }

    /** Not drawn (the pointer is on another monitor). */
    fun away() = at(-1f, -1f)

    override fun onDraw(canvas: Canvas) {
        if (px < 0) return
        canvas.save()
        canvas.translate(px, py)
        canvas.scale(density, density)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
        canvas.restore()
    }
}
