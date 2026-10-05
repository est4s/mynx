package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabSwipeTest {
    // 2.5 px to the dp, as on many phones.
    private val swipe = TabSwipe(density = 2.5f)

    private fun gesture(dx: Float, dy: Float, ms: Long, setup: TabSwipe.() -> Unit = {}): Int? {
        swipe.down(100f, 500f, 1000)
        swipe.setup()
        swipe.move(100f + dx / 2, 500f + dy / 2)
        return swipe.up(100f + dx, 500f + dy, 1000 + ms)
    }

    @Test
    fun `a quick swipe to the left goes to the next tab, to the right the one before`() {
        assertEquals(1, gesture(dx = -300f, dy = 20f, ms = 200))
        assertEquals(-1, gesture(dx = 300f, dy = -20f, ms = 200))
    }

    @Test
    fun `a short move isn't a swipe`() {
        // 60 dp is needed: 150 px here.
        assertNull(gesture(dx = -140f, dy = 0f, ms = 100))
        assertEquals(1, gesture(dx = -160f, dy = 0f, ms = 100))
    }

    @Test
    fun `a mostly vertical move scrolls, it doesn't switch tabs`() {
        assertNull(gesture(dx = -300f, dy = 200f, ms = 200))
    }

    @Test
    fun `a slow drag isn't a swipe`() {
        assertNull(gesture(dx = -300f, dy = 0f, ms = 600))
    }

    @Test
    fun `two fingers pinch, they don't switch tabs`() {
        assertNull(gesture(dx = -300f, dy = 0f, ms = 200) { secondFinger() })
    }

    @Test
    fun `a move that wanders back isn't a swipe`() {
        swipe.down(100f, 500f, 1000)
        swipe.move(100f, 900f)
        assertNull(swipe.up(-200f, 500f, 1200))
    }

    @Test
    fun `nothing without a finger down`() {
        assertNull(swipe.up(-200f, 500f, 1200))
    }

    @Test
    fun `steps to the next or previous tab, without wrapping around`() {
        assertEquals(2, swipeTarget(selected = 1, count = 4, step = 1))
        assertEquals(0, swipeTarget(selected = 1, count = 4, step = -1))
        assertNull(swipeTarget(selected = 3, count = 4, step = 1))
        assertNull(swipeTarget(selected = 0, count = 4, step = -1))
    }
}
