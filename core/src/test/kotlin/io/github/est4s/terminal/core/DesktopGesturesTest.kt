package io.github.est4s.terminal.core

import io.github.est4s.terminal.core.X11Input.Button
import io.github.est4s.terminal.core.X11Input.Move
import io.github.est4s.terminal.core.X11Input.Scroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LEFT_CLICK = listOf(Button(1, true), Button(1, false))
private val RIGHT_CLICK = listOf(Button(3, true), Button(3, false))
private val MIDDLE_CLICK = listOf(Button(2, true), Button(2, false))

class TouchpadGesturesTest {
    // An X screen of 500 × 1000, a 10 px slop.
    private val pad = TouchpadGestures(slop = 10f).apply { screen(500, 1000) }

    private fun slowMove(id: Int, fromX: Float, fromY: Float, toX: Float, toY: Float, ms: Long, steps: Int = 10): List<X11Input> {
        val out = mutableListOf<X11Input>()
        for (i in 1..steps) {
            val x = fromX + (toX - fromX) * i / steps
            val y = fromY + (toY - fromY) * i / steps
            out += pad.move(listOf(Finger(id, x, y)), ms + i * 50L)
        }
        return out
    }

    @Test
    fun `the pointer starts in the middle of the screen`() {
        assertEquals(250f, pad.pointerX)
        assertEquals(500f, pad.pointerY)
    }

    @Test
    fun `a finger moves the pointer by as much as it moved, past the slop`() {
        pad.down(0, 100f, 100f, 0)
        val out = slowMove(0, 100f, 100f, 100f, 160f, 0, steps = 6)
        // The first 10 px only tell a move from a tap.
        assertEquals(550f, pad.pointerY)
        assertEquals(250f, pad.pointerX)
        assertEquals(Move(250f, 550f), out.last())
        assertTrue(out.all { it is Move })
    }

    @Test
    fun `a quick move goes further than a slow one`() {
        pad.down(0, 100f, 100f, 0)
        pad.move(listOf(Finger(0, 100f, 111f)), 10)
        pad.move(listOf(Finger(0, 100f, 211f)), 30) // 5 px a ms
        assertTrue(pad.pointerY > 600f, "${pad.pointerY}")
    }

    @Test
    fun `the pointer stays on the screen`() {
        pad.down(0, 100f, 100f, 0)
        slowMove(0, 100f, 100f, 100f, 900f, 0, steps = 40)
        slowMove(0, 100f, 900f, 900f, 900f, 2000, steps = 40)
        assertEquals(999f, pad.pointerY)
        assertEquals(499f, pad.pointerX)
    }

    @Test
    fun `a tap is a left click where the pointer is`() {
        pad.down(0, 100f, 100f, 0)
        pad.move(listOf(Finger(0, 103f, 102f)), 50)
        assertEquals(LEFT_CLICK, pad.up(0, 120))
        assertEquals(250f, pad.pointerX)
    }

    @Test
    fun `a slow touch that doesn't move isn't a click`() {
        pad.down(0, 100f, 100f, 0)
        assertEquals(emptyList(), pad.up(0, 400))
    }

    @Test
    fun `a moving finger isn't a click`() {
        pad.down(0, 100f, 100f, 0)
        pad.move(listOf(Finger(0, 100f, 150f)), 50)
        assertEquals(emptyList(), pad.up(0, 100))
    }

    @Test
    fun `two quick taps are a double click`() {
        pad.down(0, 100f, 100f, 0)
        val first = pad.up(0, 80)
        pad.down(0, 104f, 100f, 200)
        assertEquals(LEFT_CLICK + LEFT_CLICK, first + pad.up(0, 280))
    }

    @Test
    fun `a tap and then a drag holds the left button while the finger moves`() {
        pad.down(0, 100f, 100f, 0)
        pad.up(0, 80)
        pad.down(0, 100f, 100f, 200)
        val drag = slowMove(0, 100f, 100f, 100f, 200f, 200)
        assertEquals(Button(1, true), drag.first())
        assertEquals(Move(250f, 590f), drag.last())
        assertEquals(listOf(Button(1, false)), pad.up(0, 900))
    }

    @Test
    fun `a move long after a tap is no drag`() {
        pad.down(0, 100f, 100f, 0)
        pad.up(0, 80)
        pad.down(0, 100f, 100f, 1000)
        assertTrue(slowMove(0, 100f, 100f, 100f, 200f, 1000).none { it is Button })
    }

    @Test
    fun `a long press holds the left button, and the finger drags`() {
        pad.down(0, 100f, 100f, 0)
        assertEquals(LONG_PRESS_MS, pad.timeoutAt)
        assertEquals(emptyList(), pad.timeout(LONG_PRESS_MS - 1))
        assertEquals(listOf(Button(1, true)), pad.timeout(LONG_PRESS_MS))
        assertNull(pad.timeoutAt)
        val drag = slowMove(0, 100f, 100f, 100f, 200f, LONG_PRESS_MS)
        assertTrue(drag.none { it is Button })
        assertEquals(listOf(Button(1, false)), pad.up(0, 2000))
    }

    @Test
    fun `moving before the long press is over cancels it`() {
        pad.down(0, 100f, 100f, 0)
        pad.move(listOf(Finger(0, 100f, 130f)), 100)
        assertNull(pad.timeoutAt)
        assertEquals(emptyList(), pad.timeout(LONG_PRESS_MS))
    }

    @Test
    fun `a two-finger tap is a right click`() {
        pad.down(0, 100f, 100f, 0)
        pad.down(1, 200f, 100f, 20)
        assertEquals(emptyList(), pad.up(1, 100))
        assertEquals(RIGHT_CLICK, pad.up(0, 110))
    }

    @Test
    fun `a three-finger tap is a middle click, which pastes in X`() {
        pad.down(0, 100f, 100f, 0)
        pad.down(1, 200f, 100f, 10)
        pad.down(2, 300f, 100f, 20)
        pad.up(2, 100)
        pad.up(1, 100)
        assertEquals(MIDDLE_CLICK, pad.up(0, 110))
    }

    @Test
    fun `two fingers dragging scroll, the content following the fingers`() {
        pad.down(0, 100f, 500f, 0)
        pad.down(1, 200f, 500f, 10)
        val out = mutableListOf<X11Input>()
        for (i in 1..10) out += pad.move(listOf(Finger(0, 100f, 500f - i * 12f), Finger(1, 200f, 500f - i * 12f)), 10L + i * 30)
        assertTrue(out.all { it is Scroll }, "$out")
        // Fingers up 120 px scroll down 3 notches, at 40 px a notch.
        val notches = out.sumOf { (it as Scroll).dy.toDouble() }
        assertEquals(3.0, notches, 0.01)
        assertEquals(0.0, out.sumOf { (it as Scroll).dx.toDouble() }, 0.01)
        assertEquals(emptyList(), pad.up(0, 400) + pad.up(1, 410))
        assertEquals(250f, pad.pointerX, "scrolling leaves the pointer")
    }

    @Test
    fun `the finger left after scrolling doesn't move the pointer`() {
        pad.down(0, 100f, 500f, 0)
        pad.down(1, 200f, 500f, 10)
        pad.move(listOf(Finger(0, 100f, 400f), Finger(1, 200f, 400f)), 100)
        pad.up(1, 150)
        assertEquals(emptyList(), pad.move(listOf(Finger(0, 100f, 300f)), 200))
        assertEquals(500f, pad.pointerY)
    }

    @Test
    fun `a drag that gets a second finger still lets go of the button`() {
        pad.down(0, 100f, 100f, 0)
        pad.timeout(LONG_PRESS_MS)
        pad.down(1, 200f, 100f, 600)
        pad.up(1, 700)
        assertEquals(listOf(Button(1, false)), pad.up(0, 800))
    }

    @Test
    fun `a cancelled drag lets go of the button`() {
        pad.down(0, 100f, 100f, 0)
        pad.timeout(LONG_PRESS_MS)
        assertEquals(listOf(Button(1, false)), pad.cancel())
        assertEquals(emptyList(), pad.cancel())
    }

    @Test
    fun `a mouse moves the pointer where it points, and the finger goes on from there`() {
        assertEquals(Move(499f, 10f), pad.moveTo(600f, 10f))
        pad.down(0, 100f, 100f, 0)
        slowMove(0, 100f, 100f, 100f, 160f, 0, steps = 6)
        assertEquals(60f, pad.pointerY)
    }

    @Test
    fun `a smaller screen brings the pointer onto it`() {
        pad.screen(200, 100)
        assertEquals(199f, pad.pointerX)
        assertEquals(99f, pad.pointerY)
    }
}

class DirectGesturesTest {
    // An X screen of 500 × 1000, a 10 px slop.
    private val direct = DirectGestures(slop = 10f).apply { screen(500, 1000) }

    // One finger from x, y by dx, dy in [steps] moves [stepMs] apart, from [ms].
    private fun drag(id: Int, x: Float, y: Float, dx: Float, dy: Float, ms: Long, steps: Int = 10, stepMs: Long = 16): List<X11Input> {
        val out = mutableListOf<X11Input>()
        for (i in 1..steps) out += direct.move(listOf(Finger(id, x + dx * i / steps, y + dy * i / steps)), ms + i * stepMs)
        return out
    }

    private fun scrolled(out: List<X11Input>) =
        out.filterIsInstance<Scroll>().let { s -> s.sumOf { it.dx.toDouble() } to s.sumOf { it.dy.toDouble() } }

    @Test
    fun `a tap clicks where the finger is`() {
        assertEquals(listOf(Move(100f, 200f)), direct.down(0, 100f, 200f, 0))
        assertEquals(LEFT_CLICK, direct.up(0, 100))
        assertEquals(100f, direct.pointerX)
        assertEquals(200f, direct.pointerY)
    }

    @Test
    fun `a slower tap, before the long press, is still a click`() {
        direct.down(0, 100f, 200f, 0)
        assertEquals(LEFT_CLICK, direct.up(0, LONG_PRESS_MS - 50))
    }

    @Test
    fun `two taps are two clicks in one place, which X takes for a double click`() {
        direct.down(0, 100f, 200f, 0)
        val first = direct.up(0, 60)
        direct.down(0, 103f, 201f, 150)
        assertEquals(LEFT_CLICK + LEFT_CLICK, first + direct.up(0, 210))
    }

    @Test
    fun `a finger moving scrolls, the content following it, and leaves the pointer`() {
        direct.down(0, 100f, 500f, 0)
        val out = drag(0, 100f, 500f, 0f, -120f, 0, stepMs = 30)
        assertTrue(out.all { it is Scroll }, "$out")
        // Up 120 px: down 3 notches at 40 px a notch, in small steps as the finger goes.
        assertEquals(3.0, scrolled(out).second, 0.01)
        assertTrue(out.size >= 10, "smooth steps: $out")
        assertEquals(100f, direct.pointerX)
        assertEquals(500f, direct.pointerY)
        assertEquals(emptyList(), direct.up(0, 1000))
    }

    @Test
    fun `a mostly vertical scroll stays vertical`() {
        direct.down(0, 100f, 500f, 0)
        val out = mutableListOf<X11Input>()
        for (i in 1..10) out += direct.move(listOf(Finger(0, 100f + (if (i % 2 == 0) 4f else -4f), 500f - i * 15f)), i * 30L)
        assertEquals(0.0, scrolled(out).first, 0.0001)
        assertTrue(scrolled(out).second > 3.0)
    }

    @Test
    fun `a diagonal scroll goes both ways`() {
        direct.down(0, 100f, 500f, 0)
        val (dx, dy) = scrolled(drag(0, 100f, 500f, 80f, -80f, 0, stepMs = 30))
        assertTrue(dx < -1.5 && dy > 1.5, "$dx $dy")
    }

    @Test
    fun `a flick flings on after the finger leaves, slowing down until it stops`() {
        direct.down(0, 100f, 800f, 0)
        drag(0, 100f, 800f, 0f, -300f, 0, steps = 10, stepMs = 10) // 3 px a ms, up
        assertEquals(emptyList(), direct.up(0, 105))
        assertTrue(direct.flinging)
        val steps = mutableListOf<Scroll>()
        var ms = 105L
        while (direct.flinging && ms < 10_000) {
            ms += 16
            steps += direct.fling(ms).filterIsInstance<Scroll>()
        }
        assertTrue(!direct.flinging, "a fling ends")
        assertTrue(steps.all { it.dy > 0f && it.dx == 0f }, "on in the finger's direction: $steps")
        assertTrue(steps.first().dy > steps.last().dy * 5, "slowing down: ${steps.first()} ${steps.last()}")
        // 3 px a ms dying away over FLING_DECAY_MS goes about 3 × FLING_DECAY_MS px.
        val notches = steps.sumOf { it.dy.toDouble() }
        assertEquals(3.0 * FLING_DECAY_MS / SCROLL_PX_PER_NOTCH, notches, 2.0)
    }

    @Test
    fun `a slow scroll doesn't fling`() {
        direct.down(0, 100f, 500f, 0)
        drag(0, 100f, 500f, 0f, -60f, 0, steps = 10, stepMs = 40) // 0.15 px a ms
        direct.up(0, 410)
        assertTrue(!direct.flinging)
    }

    @Test
    fun `a finger that stops before it lifts doesn't fling`() {
        direct.down(0, 100f, 800f, 0)
        drag(0, 100f, 800f, 0f, -300f, 0, steps = 10, stepMs = 10)
        direct.move(listOf(Finger(0, 100f, 500f)), 300)
        direct.up(0, 310)
        assertTrue(!direct.flinging)
    }

    @Test
    fun `a fling is no faster than the most`() {
        direct.down(0, 100f, 900f, 0)
        direct.move(listOf(Finger(0, 100f, 880f)), 1)
        direct.move(listOf(Finger(0, 100f, 100f)), 2)
        direct.up(0, 3)
        val first = direct.fling(19).filterIsInstance<Scroll>().single()
        assertTrue(first.dy * SCROLL_PX_PER_NOTCH <= FLING_MAX_SPEED * 16 + 0.01f, "$first")
    }

    @Test
    fun `a touch stops a fling, and isn't a click`() {
        direct.down(0, 100f, 800f, 0)
        drag(0, 100f, 800f, 0f, -300f, 0, steps = 10, stepMs = 10)
        direct.up(0, 105)
        direct.fling(121)
        direct.down(0, 100f, 300f, 200)
        assertTrue(!direct.flinging)
        assertEquals(emptyList(), direct.fling(216))
        assertEquals(emptyList(), direct.up(0, 260))
        // The next tap clicks again.
        direct.down(0, 100f, 300f, 400)
        assertEquals(LEFT_CLICK, direct.up(0, 450))
    }

    @Test
    fun `a long press, let go, is a right click where it was`() {
        direct.down(0, 100f, 200f, 0)
        assertEquals(LONG_PRESS_MS, direct.timeoutAt)
        assertEquals(emptyList(), direct.timeout(LONG_PRESS_MS))
        assertTrue(direct.holding, "the view buzzes")
        assertEquals(RIGHT_CLICK, direct.up(0, 900))
        assertTrue(!direct.holding)
    }

    @Test
    fun `a long press and then a move drags with the left button, to select`() {
        direct.down(0, 100f, 200f, 0)
        direct.timeout(LONG_PRESS_MS)
        assertEquals(emptyList(), direct.move(listOf(Finger(0, 105f, 200f)), 520))
        assertEquals(
            listOf(Move(100f, 200f), Button(1, true), Move(130f, 200f)),
            direct.move(listOf(Finger(0, 130f, 200f)), 540),
        )
        assertEquals(listOf(Move(160f, 210f)), direct.move(listOf(Finger(0, 160f, 210f)), 560))
        assertEquals(listOf(Move(160f, 210f), Button(1, false)), direct.up(0, 600))
        assertTrue(!direct.flinging, "a drag doesn't fling")
    }

    @Test
    fun `moving before the long press scrolls, and the long press doesn't come`() {
        direct.down(0, 100f, 500f, 0)
        direct.move(listOf(Finger(0, 100f, 470f)), 100)
        assertNull(direct.timeoutAt)
        assertEquals(emptyList(), direct.timeout(LONG_PRESS_MS))
        assertTrue(!direct.holding)
    }

    @Test
    fun `a cancelled drag lets go of the button`() {
        direct.down(0, 100f, 200f, 0)
        direct.timeout(LONG_PRESS_MS)
        direct.move(listOf(Finger(0, 150f, 200f)), 600)
        assertEquals(listOf(Button(1, false)), direct.cancel())
        assertEquals(emptyList(), direct.cancel())
    }

    @Test
    fun `cancelling ends a fling`() {
        direct.down(0, 100f, 800f, 0)
        drag(0, 100f, 800f, 0f, -300f, 0, steps = 10, stepMs = 10)
        direct.up(0, 105)
        direct.cancel()
        assertTrue(!direct.flinging)
    }

    @Test
    fun `a two-finger tap is a right click, three fingers a middle click`() {
        direct.down(0, 100f, 500f, 0)
        direct.down(1, 200f, 500f, 10)
        assertEquals(RIGHT_CLICK, direct.up(1, 80) + direct.up(0, 90))
        direct.down(0, 100f, 500f, 1000)
        direct.down(1, 200f, 500f, 1010)
        direct.down(2, 300f, 500f, 1020)
        assertEquals(MIDDLE_CLICK, direct.up(2, 1080) + direct.up(1, 1085) + direct.up(0, 1090))
    }

    @Test
    fun `pinching out zooms in around the fingers, and taps go where they point`() {
        direct.down(0, 200f, 500f, 0)
        direct.down(1, 300f, 500f, 10)
        // Apart to twice the distance, about the same middle (250, 500).
        val out = direct.move(listOf(Finger(0, 150f, 500f), Finger(1, 350f, 500f)), 100)
        assertEquals(emptyList(), out, "zooming is ours, not the X server's")
        assertEquals(2f, direct.zoom.scale, 0.001f)
        assertEquals(250f, direct.zoom.screenX(250f), 0.01f)
        assertEquals(500f, direct.zoom.screenY(500f), 0.01f)
        assertEquals(emptyList(), direct.up(1, 200) + direct.up(0, 210), "no click")
        // A tap 50 px left of the middle is 25 X pixels left of it.
        assertEquals(listOf(Move(225f, 500f)), direct.down(0, 200f, 500f, 400))
        assertEquals(LEFT_CLICK, direct.up(0, 450))
    }

    @Test
    fun `scrolling while zoomed in follows the finger on the X screen`() {
        direct.down(0, 200f, 500f, 0)
        direct.down(1, 300f, 500f, 10)
        direct.move(listOf(Finger(0, 150f, 500f), Finger(1, 350f, 500f)), 100)
        direct.up(1, 200)
        direct.up(0, 210)
        direct.down(0, 250f, 600f, 1000)
        // 120 px on the phone is 60 X pixels at twice the size.
        assertEquals(1.5, scrolled(drag(0, 250f, 600f, 0f, -120f, 1000, stepMs = 40)).second, 0.01)
    }

    @Test
    fun `two fingers moving together pan while zoomed in`() {
        direct.down(0, 200f, 500f, 0)
        direct.down(1, 300f, 500f, 10)
        direct.move(listOf(Finger(0, 150f, 500f), Finger(1, 350f, 500f)), 100)
        val before = direct.zoom.screenX(250f)
        direct.move(listOf(Finger(0, 190f, 500f), Finger(1, 390f, 500f)), 200)
        // Moved right 40 px: what was 20 X pixels left of the middle is under it.
        assertEquals(before - 20f, direct.zoom.screenX(250f), 0.01f)
        assertEquals(2f, direct.zoom.scale, 0.001f)
    }

    @Test
    fun `the zoom stays between the whole screen and the most`() {
        direct.down(0, 240f, 500f, 0)
        direct.down(1, 260f, 500f, 10)
        direct.move(listOf(Finger(0, 0f, 500f), Finger(1, 499f, 500f)), 100)
        assertEquals(DESKTOP_MOST_ZOOM, direct.zoom.scale)
        direct.move(listOf(Finger(0, 249f, 500f), Finger(1, 251f, 500f)), 200)
        // Pinching back in past the whole screen is the whole screen again.
        assertEquals(DesktopZoom(), direct.zoom)
        // At the whole screen, two fingers moving don't pan it.
        direct.move(listOf(Finger(0, 300f, 600f), Finger(1, 302f, 600f)), 300)
        assertEquals(DesktopZoom(), direct.zoom)
    }

    @Test
    fun `a zoomed screen doesn't pan past its edges`() {
        direct.down(0, 200f, 500f, 0)
        direct.down(1, 300f, 500f, 10)
        direct.move(listOf(Finger(0, 150f, 500f), Finger(1, 350f, 500f)), 100)
        direct.move(listOf(Finger(0, 900f, 1500f), Finger(1, 1100f, 1500f)), 200)
        assertEquals(0f, direct.zoom.x)
        assertEquals(0f, direct.zoom.y)
        direct.move(listOf(Finger(0, -2000f, -2000f), Finger(1, -1800f, -2000f)), 300)
        assertEquals(-500f, direct.zoom.x)
        assertEquals(-1000f, direct.zoom.y)
    }

    @Test
    fun `a new screen size shows the whole screen again`() {
        direct.down(0, 200f, 500f, 0)
        direct.down(1, 300f, 500f, 10)
        direct.move(listOf(Finger(0, 150f, 500f), Finger(1, 350f, 500f)), 100)
        direct.screen(1000, 500)
        assertEquals(DesktopZoom(), direct.zoom)
    }

    @Test
    fun `the finger is kept on the screen`() {
        assertEquals(listOf(Move(499f, 0f)), direct.down(0, 800f, -5f, 0))
    }
}

class DesktopZoomTest {
    @Test
    fun `the whole screen maps a point to itself`() {
        assertEquals(30f, DesktopZoom().screenX(30f))
        assertEquals(40f, DesktopZoom().viewY(40f))
    }

    @Test
    fun `a zoom maps both ways`() {
        val z = DesktopZoom(scale = 2f, x = -100f, y = -50f)
        assertEquals(75f, z.screenX(50f))
        assertEquals(50f, z.viewX(75f))
        assertEquals(50f, z.screenY(50f))
        assertEquals(50f, z.viewY(50f))
    }
}
