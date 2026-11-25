package io.github.est4s.terminal.core

import kotlin.math.roundToInt

/** A phone's screen: its size in pixels and its density (dpi). */
data class PhoneScreen(val width: Int, val height: Int, val density: Int)

data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Both phones in one virtual screen, in the host's pixels, starting at 0, 0 (an X screen's monitors). */
data class ScreenArrangement(val host: PixelRect, val screen: PixelRect) {
    val width get() = maxOf(host.x + host.width, screen.x + screen.width)
    val height get() = maxOf(host.y + host.height, screen.y + screen.height)
}

fun screenSide(word: String): ScreenSide? = ScreenSide.entries.firstOrNull { it.word == word }

fun screenAlign(word: String): ScreenAlign? = ScreenAlign.entries.firstOrNull { it.word == word }

/**
 * Where the host and the screen sit, for the desktop's extended
 * monitor: the screen is scaled to the host's density, so a window is
 * the same size on both, then put on the host's [side], lined up along
 * that edge by [align].
 */
fun arrangeScreens(
    host: PhoneScreen,
    screen: PhoneScreen,
    side: ScreenSide,
    align: ScreenAlign = ScreenAlign.START,
): ScreenArrangement {
    val scale = host.density.toDouble() / screen.density
    val sw = (screen.width * scale).roundToInt()
    val sh = (screen.height * scale).roundToInt()
    // The screen's offset along the shared edge from the host's start (negative: before it).
    fun along(hostSize: Int, screenSize: Int) = when (align) {
        ScreenAlign.START -> 0
        ScreenAlign.CENTER -> (hostSize - screenSize) / 2
        ScreenAlign.END -> hostSize - screenSize
    }
    val (dx, dy) = when (side) {
        ScreenSide.RIGHT -> host.width to along(host.height, sh)
        ScreenSide.LEFT -> -sw to along(host.height, sh)
        ScreenSide.BELOW -> along(host.width, sw) to host.height
        ScreenSide.ABOVE -> along(host.width, sw) to -sh
    }
    val ox = -minOf(0, dx)
    val oy = -minOf(0, dy)
    return ScreenArrangement(PixelRect(ox, oy, host.width, host.height), PixelRect(ox + dx, oy + dy, sw, sh))
}
