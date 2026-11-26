package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMonitorsTest {
    // The Pixel 10's desktop view in portrait (2 phone pixels an X pixel), and a Nord 2's monitor.
    private val pixel = Pair(528, 943)
    private val nord = Pair(536, 1200)

    @Test
    fun `the other phone's monitor is its screen at its own X pixel, the width a whole mode step`() {
        // A Nord 2: 1080 x 2400 at 480 dpi is 3 px a dp, so 2 px an X pixel (0.75 dp); 540 rounds down to 536.
        assertEquals(Pair(536, 1200), secondMonitorSize(1080, 2400, 480))
        // A Pixel 10 at 420 dpi: 2.625 px a dp, 2 px an X pixel too.
        assertEquals(Pair(1208, 540), secondMonitorSize(2424, 1080, 420))
        // A low-density tablet: 1 px an X pixel.
        assertEquals(Pair(800, 1280), secondMonitorSize(800, 1280, 160))
    }

    @Test
    fun `right of this phone the other monitor starts where this one ends`() {
        val m = desktopMonitors(pixel, nord, ScreenSide.RIGHT, ScreenDesktopMode.EXTEND)
        assertEquals(PixelRect(0, 0, 528, 943), m.host)
        assertEquals(PixelRect(528, 0, 536, 1200), m.screen)
        assertEquals(Pair(1064, 1200), Pair(m.width, m.height))
    }

    @Test
    fun `left and above put the other monitor first, everything from 0, 0`() {
        val left = desktopMonitors(pixel, nord, ScreenSide.LEFT, ScreenDesktopMode.EXTEND)
        assertEquals(PixelRect(0, 0, 536, 1200), left.screen)
        assertEquals(PixelRect(536, 0, 528, 943), left.host)
        val above = desktopMonitors(pixel, nord, ScreenSide.ABOVE, ScreenDesktopMode.EXTEND)
        assertEquals(PixelRect(0, 0, 536, 1200), above.screen)
        assertEquals(PixelRect(0, 1200, 528, 943), above.host)
        val below = desktopMonitors(pixel, nord, ScreenSide.BELOW, ScreenDesktopMode.EXTEND)
        assertEquals(PixelRect(0, 0, 528, 943), below.host)
        assertEquals(PixelRect(0, 943, 536, 1200), below.screen)
    }

    @Test
    fun `as the second half, the other phone's part matches this one's edge, so both make one rectangle`() {
        val right = desktopMonitors(pixel, nord, ScreenSide.RIGHT, ScreenDesktopMode.SPAN)
        // 536 x 1200 scaled to 943 high is 421 wide: 416, a whole mode step.
        assertEquals(PixelRect(528, 0, 416, 943), right.screen)
        assertEquals(Pair(944, 943), Pair(right.width, right.height))
        val below = desktopMonitors(pixel, nord, ScreenSide.BELOW, ScreenDesktopMode.SPAN)
        assertEquals(PixelRect(0, 943, 528, 1182), below.screen)
        assertEquals(Pair(528, 2125), Pair(below.width, below.height))
    }

    @Test
    fun `the X server is told the layout in one event`() {
        val m = desktopMonitors(pixel, nord, ScreenSide.LEFT, ScreenDesktopMode.EXTEND)
        val bytes = ByteBuffer.wrap(X11Input.Monitors(m).encode()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, bytes.capacity())
        assertEquals(100, bytes.get(0).toInt())
        assertEquals(1, bytes.get(1).toInt())
        assertEquals(listOf(528, 943, 536, 0, 536, 1200, 0, 0), (0 until 8).map { bytes.getShort(2 + 2 * it).toInt() })
        val span = ByteBuffer.wrap(X11Input.Monitors(desktopMonitors(pixel, nord, ScreenSide.RIGHT, ScreenDesktopMode.SPAN)).encode())
        assertEquals(2, span.get(1).toInt())
    }

    @Test
    fun `no other phone is one monitor, this phone's`() {
        val bytes = ByteBuffer.wrap(X11Input.Monitors.alone(528, 943).encode()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, bytes.get(1).toInt())
        assertEquals(listOf(528, 943, 0, 0, 0, 0, 0, 0), (0 until 8).map { bytes.getShort(2 + 2 * it).toInt() })
    }

    @Test
    fun `the server says what each surface shows`() {
        val e = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        e.put(0, 101.toByte())
        listOf(536, 0, 528, 943, 0, 0, 536, 1200).forEachIndexed { i, v -> e.putShort(2 + 2 * i, v.toShort()) }
        val alone = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        alone.put(0, 101.toByte())
        listOf(0, 0, 528, 943).forEachIndexed { i, v -> alone.putShort(2 + 2 * i, v.toShort()) }
        val events = X11Events((e.array() + alone.array()).inputStream())
        assertEquals(X11ServerEvent.MonitorsShown(PixelRect(536, 0, 528, 943), PixelRect(0, 0, 536, 1200)), events.next())
        assertEquals(X11ServerEvent.MonitorsShown(PixelRect(0, 0, 528, 943), null), events.next())
        assertNull(events.next())
    }
}

class DesktopScreensTest {
    private val screens = DesktopScreens().apply { host(528, 943) }
    // The other phone: a Nord 2, its video 1080 x 2400.
    private fun nordRight(mode: ScreenDesktopMode = ScreenDesktopMode.EXTEND) =
        screens.other(DesktopOther(1080, 2400, 480, 1080, 2400), ScreenSide.RIGHT, mode)

    @Test
    fun `alone, the screen is this phone's monitor`() {
        assertNull(screens.monitors)
        assertEquals(PixelRect(0, 0, 528, 943), screens.hostRect)
        assertNull(screens.otherRect)
        assertEquals(listOf(PixelRect(0, 0, 528, 943)), screens.areas)
        assertEquals(X11Input.Monitors.alone(528, 943), screens.event())
    }

    @Test
    fun `with the other phone it lays out both`() {
        nordRight()
        assertEquals(PixelRect(528, 0, 536, 1200), screens.otherRect)
        assertEquals(listOf(PixelRect(0, 0, 528, 943), PixelRect(528, 0, 536, 1200)), screens.areas)
        assertEquals(X11Input.Monitors(screens.monitors!!), screens.event())
        screens.other(null, ScreenSide.RIGHT, ScreenDesktopMode.EXTEND)
        assertNull(screens.otherRect)
    }

    @Test
    fun `what the server says it shows wins, until the layout changes`() {
        nordRight()
        // A client (XFCE's display settings) put the other monitor on the left.
        screens.shown(X11ServerEvent.MonitorsShown(PixelRect(536, 0, 528, 943), PixelRect(0, 0, 536, 1200)))
        assertEquals(PixelRect(536, 0, 528, 943), screens.hostRect)
        assertEquals(PixelRect(0, 0, 536, 1200), screens.otherRect)
        screens.host(600, 943)
        assertEquals(PixelRect(0, 0, 600, 943), screens.hostRect)
        assertEquals(PixelRect(600, 0, 536, 1200), screens.otherRect)
    }

    @Test
    fun `a server alone says nothing of the other phone`() {
        nordRight()
        screens.shown(X11ServerEvent.MonitorsShown(PixelRect(0, 0, 528, 943), null))
        assertEquals(PixelRect(528, 0, 536, 1200), screens.otherRect)
    }

    @Test
    fun `the other phone's video maps to its monitor and back`() {
        nordRight()
        assertEquals(Pair(268f, 600f), screens.fromStream(540f, 1200f))
        assertEquals(Pair(540f, 1200f), screens.toStream(528f + 268f, 600f))
        // The pointer on this phone isn't on the other.
        assertNull(screens.toStream(100f, 100f))
    }

    @Test
    fun `the second half stretches the other phone's part over its video`() {
        nordRight(ScreenDesktopMode.SPAN)
        assertEquals(PixelRect(528, 0, 416, 943), screens.otherRect)
        assertEquals(Pair(208f, 471.5f), screens.fromStream(540f, 1200f))
        assertEquals(ScreenDesktopMode.SPAN, screens.monitors!!.mode)
    }

    @Test
    fun `nothing changed, nothing to tell`() {
        assertEquals(true, screens.host(528, 943).not())
        assertEquals(true, nordRight())
        assertEquals(false, nordRight())
        assertEquals(true, nordRight(ScreenDesktopMode.SPAN))
    }
}

class RemoteDesktopTouchTest {
    private val pointer = DesktopPointer()
    private val pad = TouchpadGestures(slop = 10f, pointer = pointer).apply { monitor(PixelRect(500, 0, 400, 800)) }
    private val direct = DirectGestures(slop = 10f, pointer = pointer).apply { monitor(PixelRect(500, 0, 400, 800)) }
    private val half: (Float, Float) -> Pair<Float, Float> = { x, y -> Pair(x / 2, y / 2) }

    private fun touch(action: Int, index: Int, vararg points: TouchPoint) = ScreenMessage.Touch(1, action, index, points.toList())

    @Test
    fun `a tap on the other phone is a click where the pointer is`() {
        assertEquals(emptyList(), desktopTouch(pad, touch(ACTION_DOWN, 0, TouchPoint(0, 200f, 200f)), half, 0))
        assertEquals(listOf(X11Input.Button(1, true), X11Input.Button(1, false)), desktopTouch(pad, touch(ACTION_UP, 0, TouchPoint(0, 200f, 200f)), half, 100))
    }

    @Test
    fun `direct touch is where the finger is, mapped onto the monitor`() {
        assertEquals(listOf(X11Input.Move(600f, 100f)), desktopTouch(direct, touch(ACTION_DOWN, 0, TouchPoint(0, 200f, 200f)), half, 0))
    }

    @Test
    fun `a second finger, moves and a cancel`() {
        desktopTouch(pad, touch(ACTION_DOWN, 0, TouchPoint(0, 200f, 200f)), half, 0)
        desktopTouch(pad, touch(ACTION_POINTER_DOWN, 1, TouchPoint(0, 200f, 200f), TouchPoint(3, 400f, 200f)), half, 10)
        val scroll = desktopTouch(pad, touch(ACTION_MOVE, 0, TouchPoint(0, 200f, 100f), TouchPoint(3, 400f, 100f)), half, 50)
        assertEquals(listOf<X11Input>(X11Input.Scroll(-0f, 50f / SCROLL_PX_PER_NOTCH)), scroll)
        desktopTouch(pad, touch(ACTION_POINTER_UP, 1, TouchPoint(0, 200f, 100f), TouchPoint(3, 400f, 100f)), half, 60)
        assertEquals(emptyList(), desktopTouch(pad, touch(ACTION_CANCEL, 0), half, 70))
    }
}
