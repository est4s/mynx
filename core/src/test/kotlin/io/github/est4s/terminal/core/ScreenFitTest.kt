package io.github.est4s.terminal.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFitTest {
    // The Pixel 10's desktop view in portrait (1080 x 1886 px, its bars above and below, 422 dpi): 536 x 943 X pixels at 2 px.
    private val pixel = PhysicalScreen(1080, 1886, 422f, 422f)
    // A Nord 2 (1080 x 2400 at 409 dpi, full screen), its video the same size.
    private val nord = DesktopOther(1080, 2400, 409f, 409f, 1080, 2400)

    private fun fit(
        side: ScreenSide = ScreenSide.RIGHT,
        align: ScreenAlignment = ScreenAlignment(),
        view: PhysicalScreen = pixel,
        other: DesktopOther = nord,
        mode: ScreenDesktopMode = ScreenDesktopMode.EXTEND,
    ) = desktopFit(view, 2, other, side, mode, align)

    private fun near(expected: Float, actual: Float, within: Float = 0.5f) =
        assertTrue(abs(expected - actual) <= within, "expected about $expected, was $actual")

    @Test
    fun `an X pixel is as big on the other phone, so its part is its screen at this phone's X pixel`() {
        val f = fit()
        // 2 px an X pixel here at 422 dpi is 1.938 px there at 409: 2400 px is 1238 X pixels, 1080 is 553 (552, a mode step).
        assertEquals(PixelRect(0, 0, 536, 943), f.monitors.host)
        assertEquals(PixelRect(536, 0, 552, 943), f.monitors.screen)
    }

    @Test
    fun `the taller phone shows bands, as across the seam both share the shorter height`() {
        val f = fit()
        // This phone is the shorter (its bars take height): all of its view, and the other's picture centred, black above and below.
        assertEquals(PixelRect(0, 0, 1080, 1886), f.hostSurface)
        assertEquals(PixelRect(0, 286, 1078, 1828), f.picture)
    }

    @Test
    fun `the picture keeps to the edge facing this phone`() {
        // On the left, the other phone's right edge faces this one.
        val f = fit(ScreenSide.LEFT)
        assertEquals(PixelRect(2, 286, 1078, 1828), f.picture)
        assertEquals(PixelRect(0, 0, 552, 943), f.monitors.screen)
        assertEquals(PixelRect(552, 0, 536, 943), f.monitors.host)
    }

    @Test
    fun `above or below, they share the narrower width`() {
        val below = fit(ScreenSide.BELOW)
        // 553 X pixels across there, 536 here: this phone's width, the other's picture centred across, at its top.
        assertEquals(PixelRect(0, 0, 536, 943), below.monitors.host)
        assertEquals(PixelRect(0, 943, 536, 1238), below.monitors.screen)
        assertEquals(PixelRect(0, 0, 1080, 1886), below.hostSurface)
        assertEquals(PixelRect(17, 0, 1047, 2400), below.picture)
        val above = fit(ScreenSide.ABOVE)
        assertEquals(PixelRect(0, 0, 536, 1238), above.monitors.screen)
        assertEquals(PixelRect(0, 1238, 536, 943), above.monitors.host)
        assertEquals(PixelRect(17, 0, 1047, 2400), above.picture)
    }

    @Test
    fun `when this phone is the bigger, its own view shows the bands`() {
        // The Pixel 10 in landscape (its view 2424 x 760: 1208 x 380 X pixels), the Nord 2 upright below it.
        val wide = PhysicalScreen(2424, 760, 422f, 422f)
        val f = fit(ScreenSide.BELOW, view = wide)
        // The Nord 2 is 555 X pixels across: 552 shared, this phone's surface that wide in its middle.
        assertEquals(PixelRect(0, 0, 552, 380), f.monitors.host)
        assertEquals(PixelRect(0, 380, 552, 1238), f.monitors.screen)
        assertEquals(PixelRect(655, 0, 1108, 760), f.hostSurface)
        assertEquals(PixelRect(0, 0, 1074, 2400), f.picture)
        // The surface's own X screen is what the layout says.
        val g = DesktopGeometry(f.hostSurface.width, f.hostSurface.height, 2)
        assertEquals(Pair(552, 380), Pair(g.width, g.height))
    }

    @Test
    fun `the second half is the same sizes as one monitor over both`() {
        val f = fit(mode = ScreenDesktopMode.SPAN)
        assertEquals(ScreenDesktopMode.SPAN, f.monitors.mode)
        assertEquals(PixelRect(536, 0, 552, 943), f.monitors.screen)
        assertEquals(Pair(1088, 943), Pair(f.monitors.width, f.monitors.height))
    }

    @Test
    fun `the offset moves the other phone's picture along the seam, in mm`() {
        // 1 mm there is 16.1 px (409 dpi): 5 mm is 80.5.
        val down = fit(align = ScreenAlignment(offset = 5f))
        assertEquals(367, down.picture.y)
        assertEquals(PixelRect(0, 0, 1080, 1886), down.hostSurface)
        val up = fit(align = ScreenAlignment(offset = -5f))
        assertEquals(206, up.picture.y)
    }

    @Test
    fun `an offset past the other phone's band takes this phone's height, and the bands come here`() {
        // 25 mm up: the Nord 2's top would be 60 X pixels below this view's top, so only the rest is shared.
        val f = fit(align = ScreenAlignment(offset = -25f))
        assertEquals(0, f.picture.y)
        assertEquals(882, f.monitors.host.height)
        assertEquals(882, f.monitors.screen.height)
        assertEquals(PixelRect(0, 120, 1080, 1764), f.hostSurface)
        // As far down: the band is at the bottom here, the picture at the bottom there.
        val down = fit(align = ScreenAlignment(offset = 25f))
        assertEquals(PixelRect(0, 0, 1080, 1764), down.hostSurface)
        near(2400f, (down.picture.y + down.picture.height).toFloat(), 2f)
    }

    @Test
    fun `the scale grows the other phone's picture, keeping the seam's start`() {
        val f = fit(align = ScreenAlignment(scale = 1.05f))
        // Bigger pixels there: fewer of them across (526, a mode step 520) and the same 943 high, taller.
        assertEquals(PixelRect(536, 0, 520, 943), f.monitors.screen)
        assertEquals(1919, f.picture.height)
        assertEquals((2400 - 1919) / 2, f.picture.y)
    }

    @Test
    fun `the other phone's video is drawn into the picture's part of it`() {
        // A video at half the screen's size: the picture's part halves too.
        val f = fit(other = nord.copy(streamWidth = 540, streamHeight = 1200))
        assertEquals(PixelRect(0, 143, 539, 914), f.picture)
    }

    @Test
    fun `without its own dpi the other phone's density stands in`() {
        val f = fit(other = DesktopOther(1080, 2400, 0f, 0f, 1080, 2400, density = 409))
        assertEquals(PixelRect(536, 0, 552, 943), f.monitors.screen)
    }

    @Test
    fun `the alignment stays within its limits`() {
        assertEquals(ScreenAlignment(MAX_ALIGN_OFFSET_MM, MAX_ALIGN_SCALE), ScreenAlignment(999f, 3f).clamped())
        assertEquals(ScreenAlignment(-MAX_ALIGN_OFFSET_MM, MIN_ALIGN_SCALE), ScreenAlignment(-999f, 0.1f).clamped())
        // However far, both phones still share some of the seam.
        val f = fit(align = ScreenAlignment(offset = MAX_ALIGN_OFFSET_MM))
        assertTrue(f.monitors.host.height >= 943 / 2 - 1)
    }
}

class DesktopRulerTest {
    private val pixel = PhysicalScreen(1080, 1886, 422f, 422f)
    private val nord = DesktopOther(1080, 2400, 409f, 409f, 1080, 2400)

    @Test
    fun `both rulers mark the same X lines, 2 mm apart here`() {
        val f = desktopFit(pixel, 2, nord, ScreenSide.RIGHT, ScreenDesktopMode.EXTEND, ScreenAlignment())
        // 2 mm here is 33.2 px (16.6 X pixels at 2 px each).
        assertEquals(0f, f.hostRuler.origin)
        assertEquals(33.2f, f.hostRuler.step, 0.1f)
        // There the same X lines start at the picture's top, 32.2 px apart (1.938 px an X pixel).
        assertEquals(286f, f.otherRuler.origin, 1f)
        assertEquals(32.2f, f.otherRuler.step, 0.1f)
    }

    @Test
    fun `a ruler's lines fill its length, every fifth a long one`() {
        val r = DesktopRuler(origin = 10f, step = 20f)
        val lines = r.lines(100f)
        assertEquals(listOf(-10f, 10f, 30f, 50f, 70f, 90f).drop(1), lines.map { it.at })
        assertEquals(listOf(true, false, false, false, false), lines.map { it.major })
        assertEquals(listOf(true, false), DesktopRuler(origin = 50f, step = 20f).lines(80f).filter { it.at in 50f..70f }.map { it.major })
        // Before the origin too: -5 steps back is long again.
        assertEquals(true, DesktopRuler(origin = 110f, step = 20f).lines(120f).first { it.at == 10f }.major)
    }
}

class DesktopAlignInputTest {
    private val pixel = PhysicalScreen(1080, 1886, 422f, 422f)
    private val nord = DesktopOther(1080, 2400, 409f, 409f, 1080, 2400)
    private fun fit(a: ScreenAlignment, side: ScreenSide = ScreenSide.RIGHT) =
        desktopFit(pixel, 2, nord, side, ScreenDesktopMode.EXTEND, a)

    @Test
    fun `a drag there moves the picture with the finger`() {
        val start = ScreenAlignment()
        val moved = alignAfter(pixel, 2, nord, ScreenSide.RIGHT, start, ScreenMessage.AlignInput(1, 50f, 1f, 0f))
        assertEquals(fit(start).picture.y + 50, fit(moved).picture.y, "picture y")
        assertEquals(50f / 16.1f, moved.offset, 0.05f)
    }

    @Test
    fun `sideways seams take the drag across`() {
        val start = ScreenAlignment()
        val moved = alignAfter(pixel, 2, nord, ScreenSide.BELOW, start, ScreenMessage.AlignInput(1, -10f, 1f, 0f))
        assertEquals(fit(start, ScreenSide.BELOW).picture.x - 10, fit(moved, ScreenSide.BELOW).picture.x)
    }

    @Test
    fun `a pinch scales about the fingers, so the line under them stays`() {
        val start = ScreenAlignment()
        val before = fit(start).otherRuler
        // Fingers at a long line, 5 steps below the origin.
        val focus = before.origin + 5 * before.step
        val after = fit(alignAfter(pixel, 2, nord, ScreenSide.RIGHT, start, ScreenMessage.AlignInput(1, 0f, 1.02f, focus))).otherRuler
        assertEquals(focus, after.origin + 5 * after.step, 1f)
        assertEquals(before.step * 1.02f, after.step, 0.05f)
    }

    @Test
    fun `nudging from the keyboard moves a tenth of a millimetre, scales a thousandth`() {
        assertEquals(ScreenAlignment(0.2f, 1f), alignNudge(ScreenAlignment(0.1f, 1f), DesktopAlignKey.FORWARD))
        assertEquals(ScreenAlignment(0f, 1f), alignNudge(ScreenAlignment(0.1f, 1f), DesktopAlignKey.BACK))
        assertEquals(1.001f, alignNudge(ScreenAlignment(), DesktopAlignKey.BIGGER).scale, 0.00001f)
        assertEquals(0.999f, alignNudge(ScreenAlignment(), DesktopAlignKey.SMALLER).scale, 0.00001f)
    }

    @Test
    fun `the keys while aligning`() {
        // Up and Down along an upright seam, Left and Right along a flat one.
        assertEquals(DesktopAlignKey.FORWARD, desktopAlignKey(20, vertical = true))
        assertEquals(DesktopAlignKey.BACK, desktopAlignKey(19, vertical = true))
        assertEquals(null, desktopAlignKey(22, vertical = true))
        assertEquals(DesktopAlignKey.FORWARD, desktopAlignKey(22, vertical = false))
        assertEquals(DesktopAlignKey.BIGGER, desktopAlignKey(81, vertical = true))
        assertEquals(DesktopAlignKey.BIGGER, desktopAlignKey(70, vertical = true))
        assertEquals(DesktopAlignKey.SMALLER, desktopAlignKey(69, vertical = true))
        assertEquals(DesktopAlignKey.DONE, desktopAlignKey(66, vertical = true))
        assertEquals(DesktopAlignKey.CANCEL, desktopAlignKey(111, vertical = true))
        assertEquals(DesktopAlignKey.RESET, desktopAlignKey(46, vertical = true))
    }

    @Test
    fun `the values read as people say them`() {
        assertEquals("offset 0 mm · scale 1.000", alignText(ScreenAlignment()))
        assertEquals("offset +1.5 mm · scale 1.012", alignText(ScreenAlignment(1.5f, 1.012f)))
        assertEquals("offset -0.3 mm · scale 0.998", alignText(ScreenAlignment(-0.3f, 0.998f)))
    }
}

class AlignGesturesTest {
    private fun ruler() = DesktopRuler(origin = 100f, step = 30f)

    @Test
    fun `one finger along the edge shifts, its moves sent as they come`() {
        val g = AlignGestures(vertical = true)
        assertEquals(null, g.down(0, 50f, 500f))
        val m = g.move(listOf(Finger(0, 55f, 520f)))
        assertEquals(ScreenMessage.AlignInput(7, 20f, 1f, 520f), m?.copy(stream = 7))
        assertEquals(DesktopRuler(120f, 30f), g.preview(ruler(), m!!))
    }

    @Test
    fun `across a flat seam the finger's x counts`() {
        val g = AlignGestures(vertical = false)
        g.down(0, 50f, 500f)
        assertEquals(-10f, g.move(listOf(Finger(0, 40f, 900f)))?.shift)
    }

    @Test
    fun `two fingers pinch about their middle`() {
        val g = AlignGestures(vertical = true)
        g.down(0, 100f, 400f)
        g.down(1, 100f, 600f)
        val m = g.move(listOf(Finger(0, 100f, 390f), Finger(1, 100f, 610f)))!!
        assertEquals(1.1f, m.zoom, 0.0001f)
        assertEquals(0f, m.shift, 0.0001f)
        assertEquals(500f, m.focus)
        val p = g.preview(ruler(), m)
        // About 500: the origin 400 above it goes to 440 above.
        assertEquals(500f - 440f, p.origin, 0.01f)
        assertEquals(33f, p.step, 0.01f)
        // A finger lifted: the other drags on from where it is.
        g.up(1)
        assertEquals(10f, g.move(listOf(Finger(0, 100f, 400f)))?.shift)
    }
}
