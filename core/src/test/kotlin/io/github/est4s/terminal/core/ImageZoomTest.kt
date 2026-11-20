package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageZoomTest {
    private fun near(expected: Float, actual: Float, what: String) = assertEquals(expected, actual, 0.01f, what)

    // A 2000×1000 picture on a 1000×2000 screen: fits at 0.5, 500 high, centred.
    private fun zoom() = ImageZoom().apply { layout(1000f, 2000f, 2000f, 1000f) }

    @Test
    fun `a picture starts fitted and centred`() {
        val zoom = zoom()
        near(0.5f, zoom.scale, "scale")
        near(0f, zoom.x, "x")
        near(750f, zoom.y, "y")
        assertTrue(zoom.fitted)
    }

    @Test
    fun `a small picture is not blown up to fit`() {
        val zoom = ImageZoom().apply { layout(1000f, 2000f, 100f, 50f) }
        near(1f, zoom.scale, "scale")
        near(450f, zoom.x, "x")
        near(975f, zoom.y, "y")
    }

    @Test
    fun `pinching zooms about the fingers and stops at the limits`() {
        val zoom = zoom()
        zoom.zoomBy(2f, 500f, 1000f)
        near(1f, zoom.scale, "scale")
        // The point under the fingers stays there: image (1000, 500) at the screen's centre.
        near(-500f, zoom.x, "x")
        near(500f, zoom.y, "y")
        assertFalse(zoom.fitted)
        zoom.zoomBy(100f, 500f, 1000f)
        near(0.5f * IMAGE_ZOOM_MAX, zoom.scale, "no further than the most")
        zoom.zoomBy(0.0001f, 500f, 1000f)
        near(0.5f, zoom.scale, "no smaller than fitted")
        near(0f, zoom.x, "x, fitted again")
        near(750f, zoom.y, "y, fitted again")
    }

    @Test
    fun `panning stops at the picture's edges, and a side narrower than the screen stays centred`() {
        val zoom = zoom()
        zoom.zoomBy(2f, 500f, 1000f) // 2000×1000 on 1000×2000
        zoom.panBy(-10_000f, -10_000f)
        near(-1000f, zoom.x, "right edge at the screen's")
        near(500f, zoom.y, "shorter than the screen: centred")
        zoom.panBy(10_000f, 0f)
        near(0f, zoom.x, "left edge at the screen's")
    }

    @Test
    fun `a double tap zooms in there, and another fits again`() {
        val zoom = zoom()
        zoom.toggle(0f, 1000f)
        near(0.5f * IMAGE_ZOOM_DOUBLE_TAP, zoom.scale, "zoomed in")
        near(0f, zoom.x, "kept inside: the left edge")
        zoom.toggle(0f, 1000f)
        assertTrue(zoom.fitted)
        near(750f, zoom.y, "y")
    }

    @Test
    fun `a new screen size fits again`() {
        val zoom = zoom()
        zoom.zoomBy(3f, 0f, 0f)
        zoom.layout(2000f, 1000f, 2000f, 1000f)
        near(1f, zoom.scale, "scale")
        near(0f, zoom.x, "x")
        near(0f, zoom.y, "y")
    }

    @Test
    fun `nothing to show is left alone`() {
        val zoom = ImageZoom().apply { layout(1000f, 2000f, 0f, 0f) }
        zoom.zoomBy(2f, 0f, 0f)
        zoom.panBy(5f, 5f)
        near(1f, zoom.scale, "scale")
    }
}
