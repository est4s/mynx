package io.github.est4s.terminal.core

import kotlin.math.abs

/**
 * Tells a quick sideways swipe on the terminal (to switch tabs) from
 * scrolling, dragging and pinching. Feed it one finger's touch events,
 * in px; [density] is px per dp.
 */
class TabSwipe(private val density: Float) {
    private var startX = 0f
    private var startY = 0f
    private var startMs = 0L
    private var down = false
    private var wander = 0f

    fun down(x: Float, y: Float, ms: Long) {
        startX = x
        startY = y
        startMs = ms
        wander = 0f
        down = true
    }

    fun move(x: Float, y: Float) {
        if (down) wander = maxOf(wander, abs(y - startY))
    }

    /** A second finger: a pinch, never a swipe. */
    fun secondFinger() {
        down = false
    }

    /** +1 for the next tab (swiped left), -1 for the one before, null: not a swipe. */
    fun up(x: Float, y: Float, ms: Long): Int? {
        if (!down) return null
        move(x, y)
        down = false
        val dx = x - startX
        return when {
            abs(dx) < MIN_SWIPE_DP * density -> null
            wander * 2 > abs(dx) -> null
            ms - startMs > MAX_SWIPE_MS -> null
            dx < 0 -> 1
            else -> -1
        }
    }
}

private const val MIN_SWIPE_DP = 60
private const val MAX_SWIPE_MS = 400

/** The tab a swipe of [step] goes to from [selected], of [count]; null at either end. */
fun swipeTarget(selected: Int, count: Int, step: Int): Int? = (selected + step).takeIf { it in 0 until count }
