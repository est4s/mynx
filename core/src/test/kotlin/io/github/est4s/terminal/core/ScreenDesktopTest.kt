package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMonitorsTest {
    // The Pixel 10's monitor and a Nord 2's beside it, as desktopFit lays them out.
    private val left = DesktopMonitors(ScreenDesktopMode.EXTEND, PixelRect(536, 0, 528, 943), PixelRect(0, 0, 536, 943))

    @Test
    fun `the X server is told the layout in one event`() {
        val bytes = ByteBuffer.wrap(X11Input.Monitors(left).encode()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, bytes.capacity())
        assertEquals(100, bytes.get(0).toInt())
        assertEquals(1, bytes.get(1).toInt())
        assertEquals(listOf(528, 943, 536, 0, 536, 943, 0, 0), (0 until 8).map { bytes.getShort(2 + 2 * it).toInt() })
        val span = ByteBuffer.wrap(X11Input.Monitors(left.copy(mode = ScreenDesktopMode.SPAN)).encode())
        assertEquals(2, span.get(1).toInt())
        assertEquals(Pair(1064, 943), Pair(left.width, left.height))
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
    // The Pixel 10's desktop view (536 x 943 X pixels), and a Nord 2, its video 1080 x 2400: desktopFit's numbers.
    private val screens = DesktopScreens().apply { view(1080, 1886, 422f, 422f, 2) }
    private fun nordRight(mode: ScreenDesktopMode = ScreenDesktopMode.EXTEND) =
        screens.other(DesktopOther(1080, 2400, 409f, 409f, 1080, 2400), ScreenSide.RIGHT, mode)

    @Test
    fun `alone, the screen is this phone's monitor`() {
        assertNull(screens.monitors)
        assertNull(screens.hostSurface)
        assertEquals(PixelRect(0, 0, 536, 943), screens.hostRect)
        assertNull(screens.otherRect)
        assertEquals(listOf(PixelRect(0, 0, 536, 943)), screens.areas)
        assertEquals(X11Input.Monitors.alone(536, 943), screens.event())
    }

    @Test
    fun `with the other phone it lays out both`() {
        nordRight()
        assertEquals(PixelRect(536, 0, 552, 943), screens.otherRect)
        assertEquals(listOf(PixelRect(0, 0, 536, 943), PixelRect(536, 0, 552, 943)), screens.areas)
        assertEquals(X11Input.Monitors(screens.monitors!!), screens.event())
        assertEquals(PixelRect(0, 0, 1080, 1886), screens.hostSurface)
        screens.other(null, ScreenSide.RIGHT, ScreenDesktopMode.EXTEND)
        assertNull(screens.otherRect)
    }

    @Test
    fun `what the server says it shows wins, until the layout changes`() {
        nordRight()
        // A client (XFCE's display settings) put the other monitor on the left.
        screens.shown(X11ServerEvent.MonitorsShown(PixelRect(552, 0, 536, 943), PixelRect(0, 0, 552, 943)))
        assertEquals(PixelRect(552, 0, 536, 943), screens.hostRect)
        assertEquals(PixelRect(0, 0, 552, 943), screens.otherRect)
        screens.view(1200, 1886, 422f, 422f, 2)
        assertEquals(PixelRect(0, 0, 600, 943), screens.hostRect)
        assertEquals(PixelRect(600, 0, 552, 943), screens.otherRect)
    }

    @Test
    fun `a server alone says nothing of the other phone`() {
        nordRight()
        screens.shown(X11ServerEvent.MonitorsShown(PixelRect(0, 0, 536, 943), null))
        assertEquals(PixelRect(536, 0, 552, 943), screens.otherRect)
    }

    @Test
    fun `the other phone's video maps to its picture and back, the bands held at its edge`() {
        nordRight()
        // The picture is 0, 286, 1078 x 1828 of the video.
        assertEquals(Pair(0f, 0f), screens.fromStream(0f, 286f))
        assertEquals(Pair(552f, 943f), screens.fromStream(1078f, 2114f))
        assertEquals(0f, screens.fromStream(500f, 100f).second)
        val (x, y) = screens.toStream(536f + 276f, 471.5f)!!
        assertEquals(539f, x, 0.01f)
        assertEquals(1200f, y, 0.01f)
        // The pointer on this phone isn't on the other.
        assertNull(screens.toStream(100f, 100f))
    }

    @Test
    fun `the second half is one monitor over both`() {
        nordRight(ScreenDesktopMode.SPAN)
        assertEquals(PixelRect(536, 0, 552, 943), screens.otherRect)
        assertEquals(ScreenDesktopMode.SPAN, screens.monitors!!.mode)
    }

    @Test
    fun `the alignment moves the other phone's picture`() {
        nordRight()
        assertEquals(true, screens.align(ScreenAlignment(offset = 5f)))
        assertEquals(367, screens.fit!!.picture.y)
        assertEquals(false, screens.align(ScreenAlignment(offset = 5f)))
        val dragged = screens.aligned(ScreenMessage.AlignInput(1, 50f, 1f, 0f))
        assertEquals(5f + 50f / 16.1f, dragged.offset, 0.05f)
        // Kept within its limits.
        screens.align(ScreenAlignment(offset = 900f))
        assertEquals(MAX_ALIGN_OFFSET_MM, screens.alignment.offset)
    }

    @Test
    fun `nothing changed, nothing to tell`() {
        assertEquals(false, screens.view(1080, 1886, 422f, 422f, 2))
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
