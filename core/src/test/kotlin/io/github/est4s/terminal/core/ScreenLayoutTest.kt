package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenLayoutTest {
    // A Pixel 10 (1080 x 2424 at 420 dpi) and a Nord 2 (1080 x 2400 at 480 dpi).
    private val pixel = PhoneScreen(1080, 2424, 420)
    private val nord = PhoneScreen(1080, 2400, 480)

    @Test
    fun `the screen is scaled into the host's pixels, so both are their real size`() {
        val a = arrangeScreens(pixel, nord, ScreenSide.RIGHT)
        assertEquals(PixelRect(0, 0, 1080, 2424), a.host)
        assertEquals(PixelRect(1080, 0, 945, 2100), a.screen)
        assertEquals(2025, a.width)
        assertEquals(2424, a.height)
    }

    @Test
    fun `on the left the screen comes first, and everything starts at 0`() {
        val a = arrangeScreens(pixel, nord, ScreenSide.LEFT)
        assertEquals(PixelRect(0, 0, 945, 2100), a.screen)
        assertEquals(PixelRect(945, 0, 1080, 2424), a.host)
    }

    @Test
    fun `above and below stack them`() {
        val above = arrangeScreens(pixel, nord, ScreenSide.ABOVE)
        assertEquals(PixelRect(0, 0, 945, 2100), above.screen)
        assertEquals(PixelRect(0, 2100, 1080, 2424), above.host)
        assertEquals(4524, above.height)
        val below = arrangeScreens(pixel, nord, ScreenSide.BELOW)
        assertEquals(PixelRect(0, 2424, 945, 2100), below.screen)
    }

    @Test
    fun `alignment moves the screen along the host's edge`() {
        assertEquals(162, arrangeScreens(pixel, nord, ScreenSide.RIGHT, ScreenAlign.CENTER).screen.y)
        assertEquals(324, arrangeScreens(pixel, nord, ScreenSide.RIGHT, ScreenAlign.END).screen.y)
        // A screen taller than the host: the host moves instead.
        val tall = arrangeScreens(nord, PhoneScreen(1600, 2560, 240), ScreenSide.RIGHT, ScreenAlign.CENTER)
        assertEquals(PixelRect(0, 1360, 1080, 2400), tall.host)
        assertEquals(PixelRect(1080, 0, 3200, 5120), tall.screen)
    }

    @Test
    fun `words for the side and alignment`() {
        assertEquals(ScreenSide.ABOVE, screenSide("above"))
        assertNull(screenSide("behind"))
        assertEquals(ScreenAlign.CENTER, screenAlign("center"))
    }
}
