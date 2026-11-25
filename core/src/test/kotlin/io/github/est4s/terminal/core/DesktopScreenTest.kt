package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopScreenTest {
    @Test
    fun `an X pixel is about three quarters of a dp, whole phone pixels, at least one`() {
        // The spike's 2 on a Pixel 10 (2.625) read well.
        assertEquals(2, desktopScale(2.625f))
        assertEquals(3, desktopScale(3.5f))
        assertEquals(1, desktopScale(1f))
        assertEquals(1, desktopScale(0.75f))
    }

    @Test
    fun `the X screen is the surface over the scale, its width a multiple of 8`() {
        val g = DesktopGeometry(surfaceWidth = 1080, surfaceHeight = 2100, scale = 2)
        assertEquals(536, g.width) // 540 down to the server's mode granularity
        assertEquals(1050, g.height)
    }

    @Test
    fun `touches map onto the X screen, which is stretched over the whole surface`() {
        val g = DesktopGeometry(surfaceWidth = 1080, surfaceHeight = 2100, scale = 2)
        assertEquals(0f, g.toX(0f))
        assertEquals(536f, g.toX(1080f))
        assertEquals(525f, g.toY(1050f))
        assertEquals(1080f, g.fromX(536f))
        assertEquals(1050f, g.fromY(525f))
    }

    @Test
    fun `a tiny surface still gets a screen`() {
        val g = DesktopGeometry(surfaceWidth = 10, surfaceHeight = 1, scale = 2)
        assertEquals(8, g.width)
        assertEquals(1, g.height)
    }

    @Test
    fun `with nothing covering the screen it isn't moved`() {
        assertEquals(0, keyboardPan(full = 2000, visible = 2000, pointerY = 1900f, current = 0, margin = 100))
        assertEquals(0, keyboardPan(full = 2000, visible = 2000, pointerY = 1900f, current = 300, margin = 100))
    }

    @Test
    fun `the keyboard moves the screen up just enough to keep the pointer in sight`() {
        // 1200 px show; the pointer at 1500 must be 100 above the visible bottom.
        assertEquals(400, keyboardPan(full = 2000, visible = 1200, pointerY = 1500f, current = 0, margin = 100))
    }

    @Test
    fun `the screen stays put while the pointer moves inside what shows`() {
        assertEquals(400, keyboardPan(full = 2000, visible = 1200, pointerY = 900f, current = 400, margin = 100))
    }

    @Test
    fun `it moves back down when the pointer goes up past the top`() {
        assertEquals(200, keyboardPan(full = 2000, visible = 1200, pointerY = 300f, current = 400, margin = 100))
        assertEquals(0, keyboardPan(full = 2000, visible = 1200, pointerY = 20f, current = 400, margin = 100))
    }

    @Test
    fun `it never moves past the bottom of the screen`() {
        assertEquals(800, keyboardPan(full = 2000, visible = 1200, pointerY = 1999f, current = 0, margin = 100))
    }

    @Test
    fun `the first surface gets the screen's size`() {
        val surface = DesktopSurface()
        assertEquals(listOf(X11Input.ScreenSize(536, 1050, 120)), surface.handover(536, 1050, 120, fresh = true))
    }

    @Test
    fun `a resize sends the new size`() {
        val surface = DesktopSurface()
        surface.handover(536, 1050, 120, fresh = true)
        assertEquals(listOf(X11Input.ScreenSize(1048, 512, 120)), surface.handover(1048, 512, 120, fresh = false))
    }

    @Test
    fun `the same size again for the same surface sends nothing`() {
        val surface = DesktopSurface()
        surface.handover(536, 1050, 120, fresh = true)
        assertEquals(emptyList(), surface.handover(536, 1050, 120, fresh = false))
    }

    @Test
    fun `a new surface of the same size makes the server rebuild its screen, so it's drawn whole`() {
        // The server draws only what changed since the last frame; a changed
        // mode (here the rate, for a moment) gives it a new screen buffer.
        val surface = DesktopSurface()
        surface.handover(536, 1050, 120, fresh = true)
        assertEquals(
            listOf(X11Input.ScreenSize(536, 1050, 121), X11Input.ScreenSize(536, 1050, 120)),
            surface.handover(536, 1050, 120, fresh = true),
        )
    }

    @Test
    fun `after reconnecting, the server's size is unknown, so it's sent`() {
        val surface = DesktopSurface()
        surface.handover(536, 1050, 120, fresh = true)
        surface.reconnected()
        assertEquals(listOf(X11Input.ScreenSize(536, 1050, 120)), surface.handover(536, 1050, 120, fresh = true))
    }
}

class DesktopNoticeTest {
    private fun status(running: Boolean = false, problem: String? = null) =
        DesktopStatus(running, DesktopSession.XFCE, sessionRunning = running, problem = problem)

    @Test
    fun `a connected server shows its screen`() {
        assertEquals(null, desktopNotice(connected = true, status = status(running = true), starting = false, error = null))
    }

    @Test
    fun `before the app is ready there's nothing to start`() {
        assertEquals(DesktopNotice("Waiting for the app…", canStart = false, showLog = false), desktopNotice(false, null, false, null))
    }

    @Test
    fun `a failed start says why, with the log`() {
        assertEquals(
            DesktopNotice("The desktop didn't start: the X server didn't start: boom", canStart = true, showLog = true),
            desktopNotice(false, status(), starting = false, error = "the X server didn't start: boom"),
        )
    }

    @Test
    fun `while starting, and while the server runs but hasn't connected, it says so`() {
        assertEquals(DesktopNotice("Starting the desktop…", canStart = false, showLog = false), desktopNotice(false, status(), starting = true, error = null))
        assertEquals(
            DesktopNotice("Connecting to the X server…", canStart = false, showLog = true),
            desktopNotice(false, status(running = true), starting = false, error = null),
        )
    }

    @Test
    fun `what stops a start is shown, to fix and try again`() {
        assertEquals(
            DesktopNotice("XFCE isn't installed: apt install xfce4 dbus-x11", canStart = true, showLog = false),
            desktopNotice(false, status(problem = "XFCE isn't installed: apt install xfce4 dbus-x11"), false, null),
        )
    }

    @Test
    fun `a desktop that isn't running can be started`() {
        assertEquals(DesktopNotice("The desktop isn't running.", canStart = true, showLog = false), desktopNotice(false, status(), false, null))
    }

    @Test
    fun `opening the desktop starts it only when nothing runs and nothing's missing`() {
        assertTrue(desktopStartsOnOpen(connected = false, status = status()))
        assertFalse(desktopStartsOnOpen(connected = true, status = status(running = true)))
        assertFalse(desktopStartsOnOpen(connected = false, status = status(running = true)))
        assertFalse(desktopStartsOnOpen(connected = false, status = status(problem = "no xkb-data")))
        assertFalse(desktopStartsOnOpen(connected = false, status = null))
    }
}

class DesktopLogsTest {
    private val rootfs = kotlin.io.path.createTempDirectory("desktop-logs").toFile()

    @kotlin.test.AfterTest
    fun cleanup() {
        rootfs.deleteRecursively()
    }

    private fun write(path: String, text: String) =
        java.io.File(rootfs, path.removePrefix("/")).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    fun `the view's log is the link's, then the server's and the session's logs`() {
        write(X11_LOG, "server line\n")
        write(DESKTOP_SESSION_LOG, "session line\n")
        assertEquals(
            "12:00:00 connected\n\n$X11_LOG:\nserver line\n\n\n$DESKTOP_SESSION_LOG:\nsession line\n",
            desktopLogs(rootfs, "12:00:00 connected"),
        )
    }

    @Test
    fun `a missing log is left out`() {
        write(X11_LOG, "server line\n")
        assertEquals("\n\n$X11_LOG:\nserver line\n", desktopLogs(rootfs, ""))
    }

    @Test
    fun `a long log shows its end, from a line's start`() {
        write(X11_LOG, "first\n" + "x".repeat(10) + "\nlast\n")
        assertEquals("\n\n$X11_LOG:\nlast\n", desktopLogs(rootfs, "", tail = 8))
    }
}
