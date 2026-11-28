package io.github.est4s.terminal.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Either phone turning while the desktop is across both: the layout is
 * worked out afresh from both shapes as they are now, whatever turned
 * first, and the pair's alignment keeps meaning mm along the seam.
 */
class DesktopTurnTest {
    // The Pixel 10's desktop view upright (536 x 943 X pixels at 2 px) and on its side (1208 x 380).
    private val pixelUp = PhysicalScreen(1080, 1886, 422f, 422f)
    private val pixelSide = PhysicalScreen(2424, 760, 422f, 422f)
    // A Nord 2 upright and on its side (409 dpi), its video its screen's size.
    private val nordUp = DesktopOther(1080, 2400, 409f, 409f, 1080, 2400)
    private val nordSide = DesktopOther(2400, 1080, 409f, 409f, 2400, 1080)

    private fun near(expected: Float, actual: Float, within: Float) =
        assertTrue(abs(expected - actual) <= within, "expected about $expected, was $actual")

    private fun fit(
        view: PhysicalScreen,
        other: DesktopOther,
        side: ScreenSide,
        mode: ScreenDesktopMode = ScreenDesktopMode.EXTEND,
        align: ScreenAlignment = ScreenAlignment(),
    ) = desktopFit(view, 2, other, side, mode, align)

    @Test
    fun `the other phone turned on its side is laid out as if it had been so from the start`() {
        val screens = DesktopScreens()
        screens.view(1080, 1886, 422f, 422f, 2)
        screens.other(nordUp, ScreenSide.RIGHT, ScreenDesktopMode.EXTEND)
        assertEquals(true, screens.other(nordSide, ScreenSide.RIGHT, ScreenDesktopMode.EXTEND))
        val f = screens.fit!!
        assertEquals(fit(pixelUp, nordSide, ScreenSide.RIGHT), f)
        // Its edge along the seam is the short one now (557 X pixels high, under this phone's 943): both share
        // that, and the bands are this phone's, its picture the other's whole height.
        assertEquals(f.monitors.host.height, f.monitors.screen.height)
        assertTrue(f.monitors.screen.height in 550..560, "${f.monitors.screen}")
        assertTrue(f.hostSurface.height < 1886 && f.hostSurface.y > 0, "${f.hostSurface}")
        assertEquals(0, f.picture.y)
        near(1080f, f.picture.height.toFloat(), 2f)
    }

    @Test
    fun `whichever phone turns first, the layout is the same`() {
        for (side in ScreenSide.values()) {
            val aFirst = DesktopScreens().apply {
                view(1080, 1886, 422f, 422f, 2)
                other(nordUp, side, ScreenDesktopMode.EXTEND)
                view(2424, 760, 422f, 422f, 2)
                other(nordSide, side, ScreenDesktopMode.EXTEND)
            }
            val bFirst = DesktopScreens().apply {
                view(1080, 1886, 422f, 422f, 2)
                other(nordUp, side, ScreenDesktopMode.EXTEND)
                other(nordSide, side, ScreenDesktopMode.EXTEND)
                view(2424, 760, 422f, 422f, 2)
            }
            assertEquals(fit(pixelSide, nordSide, side), aFirst.fit, "$side")
            assertEquals(aFirst.fit, bFirst.fit, "$side")
        }
    }

    @Test
    fun `both on their sides, one above the other, make one tall desktop`() {
        for (side in listOf(ScreenSide.ABOVE, ScreenSide.BELOW)) {
            for (mode in ScreenDesktopMode.values()) {
                val m = fit(pixelSide, nordSide, side, mode).monitors
                // They share the narrower width (this phone's 1208 X pixels); 380 high here, 557 there.
                assertEquals(m.host.width, m.screen.width, "$side $mode")
                assertEquals(1208, m.width)
                assertEquals(m.host.height + m.screen.height, m.height)
                val shape = m.width.toFloat() / m.height
                assertTrue(shape in 1.2f..1.4f, "$side $mode: ${m.width} x ${m.height}, about 4:3")
            }
        }
        val above = fit(pixelSide, nordSide, ScreenSide.ABOVE).monitors
        assertEquals(0, above.screen.y)
        assertEquals(above.screen.height, above.host.y)
    }

    @Test
    fun `both on their sides side by side make one wide one, sharing this phone's height`() {
        val f = fit(pixelSide, nordSide, ScreenSide.RIGHT, ScreenDesktopMode.SPAN)
        assertEquals(380, f.monitors.host.height)
        assertEquals(380, f.monitors.screen.height)
        assertEquals(PixelRect(0, 0, 2424, 760), f.hostSurface)
        // The other phone's picture is the middle of its height, black above and below.
        assertTrue(f.picture.y > 0 && f.picture.y + f.picture.height < 1080, "${f.picture}")
    }

    @Test
    fun `the offset still moves the other phone's picture by mm along the seam after it turns`() {
        // Both on their sides, the other below: the seam runs across, so the offset moves its picture sideways.
        val plain = fit(pixelSide, nordSide, ScreenSide.BELOW).picture
        val moved = fit(pixelSide, nordSide, ScreenSide.BELOW, align = ScreenAlignment(offset = 3f)).picture
        // 3 mm at 409 dpi is 48.3 px.
        near(48.3f, (moved.x - plain.x).toFloat(), 1.5f)
    }

    @Test
    fun `when the turn makes the other phone the shorter along the seam, the offset moves this phone's bands instead`() {
        // Upright beside this phone its long edge faced the seam; on its side its short edge does.
        val plain = fit(pixelUp, nordSide, ScreenSide.RIGHT)
        val moved = fit(pixelUp, nordSide, ScreenSide.RIGHT, align = ScreenAlignment(offset = 3f))
        assertEquals(plain.picture, moved.picture)
        // The other phone's picture further down is this phone's part further up: 3 mm at 422 dpi is 49.8 px.
        near(-49.8f, (moved.hostSurface.y - plain.hostSurface.y).toFloat(), 2f)
    }

    @Test
    fun `aligning after a turn, rulers are 2 mm apart on both and drags count in mm`() {
        for (other in listOf(nordUp, nordSide)) {
            for (side in ScreenSide.values()) {
                val f = fit(pixelSide, other, side)
                // 2 mm is 33.2 px here (422 dpi), 32.2 there (409 dpi), whichever way either phone is turned.
                near(33.2f, f.hostRuler.step, 0.1f)
                near(32.2f, f.otherRuler.step, 0.1f)
            }
        }
        val screens = DesktopScreens().apply {
            view(2424, 760, 422f, 422f, 2)
            other(nordUp, ScreenSide.BELOW, ScreenDesktopMode.EXTEND)
            other(nordSide, ScreenSide.BELOW, ScreenDesktopMode.EXTEND)
        }
        // 32.2 px of a drag there is 2 mm.
        near(2f, screens.aligned(ScreenMessage.AlignInput(1, 32.2f, 1f, 0f)).offset, 0.05f)
    }

    @Test
    fun `the alignment kept is applied to the new shape`() {
        val screens = DesktopScreens().apply {
            view(1080, 1886, 422f, 422f, 2)
            align(ScreenAlignment(offset = 2f, scale = 1.01f))
            other(nordUp, ScreenSide.BELOW, ScreenDesktopMode.EXTEND)
            other(nordSide, ScreenSide.BELOW, ScreenDesktopMode.EXTEND)
        }
        assertEquals(fit(pixelUp, nordSide, ScreenSide.BELOW, align = ScreenAlignment(2f, 1.01f)), screens.fit)
    }
}
