package io.github.est4s.terminal.core

/** The most a picture zooms, times its fitted size. */
const val IMAGE_ZOOM_MAX = 8f
/** A double tap's zoom, times the fitted size. */
const val IMAGE_ZOOM_DOUBLE_TAP = 3f

/**
 * The full-screen picture's zoom and position: [scale] (screen pixels per
 * picture pixel) and where its top-left corner is on screen ([x], [y]).
 * It starts fitted (the whole picture, centred, never larger than its own
 * size), zooms between that and [IMAGE_ZOOM_MAX] times it, and stays on
 * screen: a side larger than the screen can't leave a gap, a smaller one
 * is centred.
 */
class ImageZoom {
    private var viewW = 0f
    private var viewH = 0f
    private var imageW = 0f
    private var imageH = 0f
    private var fit = 1f

    var scale = 1f
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    /** Whether it shows the whole picture, as it started. */
    val fitted get() = scale <= fit * 1.001f

    /** A new screen or picture size: fitted again. */
    fun layout(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float) {
        viewW = viewWidth
        viewH = viewHeight
        imageW = imageWidth
        imageH = imageHeight
        fit = if (imageW <= 0f || imageH <= 0f) 1f else minOf(1f, viewW / imageW, viewH / imageH)
        scale = fit
        keepInside()
    }

    /** A pinch: [factor] times bigger, keeping the picture's point at ([focusX], [focusY]) under it. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (imageW <= 0f || imageH <= 0f || factor <= 0f) return
        val next = (scale * factor).coerceIn(fit, fit * IMAGE_ZOOM_MAX)
        val applied = next / scale
        x = focusX - (focusX - x) * applied
        y = focusY - (focusY - y) * applied
        scale = next
        keepInside()
    }

    /** A drag by ([dx], [dy]) screen pixels. */
    fun panBy(dx: Float, dy: Float) {
        if (imageW <= 0f || imageH <= 0f) return
        x += dx
        y += dy
        keepInside()
    }

    /** A double tap: zoomed in at ([focusX], [focusY]) when fitted, else fitted again. */
    fun toggle(focusX: Float, focusY: Float) {
        if (fitted) zoomBy(IMAGE_ZOOM_DOUBLE_TAP, focusX, focusY) else zoomBy(fit / scale, focusX, focusY)
    }

    private fun keepInside() {
        x = inside(x, imageW * scale, viewW)
        y = inside(y, imageH * scale, viewH)
    }

    private fun inside(at: Float, size: Float, view: Float) =
        if (size <= view) (view - size) / 2 else at.coerceIn(view - size, 0f)
}
