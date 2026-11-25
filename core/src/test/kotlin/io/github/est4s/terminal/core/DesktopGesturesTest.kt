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
    fun `a cancelled drag lets go of the button`() {
        pad.down(0, 100f, 100f, 0)
        pad.timeout(LONG_PRESS_MS)
        assertEquals(listOf(Button(1, false)), pad.cancel())
        assertEquals(emptyList(), pad.cancel())
    }

    @Test
    fun `a smaller screen brings the pointer onto it`() {
        pad.screen(200, 100)
        assertEquals(199f, pad.pointerX)
        assertEquals(99f, pad.pointerY)
    }
}

class DirectGesturesTest {
    private val direct = DirectGestures(slop = 10f).apply { screen(500, 1000) }

    @Test
    fun `a tap clicks where the finger is`() {
        assertEquals(listOf(Move(100f, 200f)), direct.down(0, 100f, 200f, 0))
        assertEquals(LEFT_CLICK, direct.up(0, 100))
        assertEquals(100f, direct.pointerX)
        assertEquals(200f, direct.pointerY)
    }

    @Test
    fun `a drag presses where it started and follows the finger`() {
        direct.down(0, 100f, 200f, 0)
        assertEquals(emptyList(), direct.move(listOf(Finger(0, 105f, 200f)), 20))
        assertEquals(
            listOf(Move(100f, 200f), Button(1, true), Move(130f, 200f)),
            direct.move(listOf(Finger(0, 130f, 200f)), 40),
        )
        assertEquals(listOf(Move(160f, 200f)), direct.move(listOf(Finger(0, 160f, 200f)), 60))
        assertEquals(listOf(Move(160f, 200f), Button(1, false)), direct.up(0, 80))
    }

    @Test
    fun `a long press is a right click`() {
        direct.down(0, 100f, 200f, 0)
        assertEquals(LONG_PRESS_MS, direct.timeoutAt)
        assertEquals(RIGHT_CLICK, direct.timeout(LONG_PRESS_MS))
        assertEquals(emptyList(), direct.up(0, 900))
    }

    @Test
    fun `two fingers scroll, and a two-finger tap is a right click`() {
        direct.down(0, 100f, 500f, 0)
        direct.down(1, 200f, 500f, 10)
        assertEquals(RIGHT_CLICK, direct.up(1, 80) + direct.up(0, 90))

        direct.down(0, 100f, 500f, 1000)
        direct.down(1, 200f, 500f, 1010)
        val out = direct.move(listOf(Finger(0, 100f, 400f), Finger(1, 200f, 400f)), 1100)
        assertTrue(out.isNotEmpty() && out.all { it is Scroll }, "$out")
        assertEquals(emptyList(), direct.up(0, 1200) + direct.up(1, 1210))
    }

    @Test
    fun `the finger is kept on the screen`() {
        assertEquals(listOf(Move(499f, 0f)), direct.down(0, 800f, -5f, 0))
    }
}
