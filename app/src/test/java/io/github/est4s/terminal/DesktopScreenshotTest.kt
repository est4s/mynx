package io.github.est4s.terminal

import android.app.Application
import android.graphics.Typeface
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.est4s.terminal.core.DesktopSession
import io.github.est4s.terminal.core.DesktopStatus
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.desktopBackLabel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/** A link that never reaches an X server: the view's chrome is all there is to see. */
private class NoServer(override val connected: Boolean = false) : DesktopScreenLink {
    override var listener: (() -> Unit)? = null
    override fun log() = "07:12:03 connected to the X server\n07:14:40 the X server stopped"
    override fun send(events: List<X11Input>) = Unit
    override fun surface(surface: Surface, width: Int, height: Int, framerate: Int, fresh: Boolean) = Unit
    override fun surfaceGone() = Unit
    override fun androidClipboard(text: String?) = Unit
}

private class SampleHost(
    private val status: DesktopStatus,
    override val link: DesktopScreenLink,
    private val fromTouch: Boolean = false,
) : DesktopView.Host {
    override fun status() = status
    override fun start(done: (String?) -> Unit) = Unit
    override fun logs() = link.log() + "\n\n/tmp/.mynx/desktop/x11.log:\nXlorie: new surface applied\n" +
        "\n\n/tmp/.mynx/desktop/session.log:\nxfce4-session: Could not connect to the session bus\n"
    override fun touch() = DesktopTouch.TOUCHPAD
    override fun back() = Unit
    override fun backLabel() = desktopBackLabel(fromTouch)
    override fun stop() = Unit
    override fun restart(done: (String?) -> Unit) = Unit
    override fun setTouch(touch: DesktopTouch) = Unit
    override fun install() = Unit
}

private val XFCE_MISSING = DesktopStatus(
    running = false, session = DesktopSession.XFCE, sessionRunning = false,
    problem = "XFCE isn't installed: mynx desktop install installs it (or mynx set desktop none runs the X server alone)",
    install = listOf("xfce4", "xfce4-terminal", "dbus-x11", "tango-icon-theme"),
)
private val RUNNING = DesktopStatus(running = true, session = DesktopSession.XFCE, sessionRunning = true, problem = null)

/** The desktop's view without an X server: its bar, notices, key bar and pointer (see [Screenshots]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class DesktopScreenshotTest {
    private fun shoot(
        w: Int, h: Int, name: String, host: DesktopView.Host, log: Boolean = false, then: (DesktopView) -> Unit = {},
    ) {
        val bars = Files.createTempDirectory("keybars").toFile()
        val view = Screenshots.open(w, h) { activity ->
            DesktopView(activity, Typeface.MONOSPACE, bars, host).apply { colors = Screenshots.colors }
        }
        view.shown()
        Screenshots.settle()
        if (log) view.toggleLog()
        then(view)
        // No settle here: save()'s 1.5 s keeps Shut down's 3 s confirmation on screen.
        Screenshots.save(view, name)
        view.hidden()
    }

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun tap(view: DesktopView, label: String) =
        views(view).filterIsInstance<TextView>().first { it.isShown && it.text == label }.performClick()

    @Test
    fun missing() = shoot(1080, 2400, "desktop", SampleHost(XFCE_MISSING, NoServer()))

    @Test
    fun connecting() = shoot(1080, 2400, "desktop-connecting", SampleHost(RUNNING, NoServer()))

    // Connected: the screen is the server's (black here), with our pointer in the middle.
    @Test
    fun connected() = shoot(1080, 2400, "desktop-connected", SampleHost(RUNNING, NoServer(connected = true)))

    // Opened from touch view: its back button says so.
    @Test
    fun fromTouch() = shoot(1080, 2400, "desktop-from-touch", SampleHost(RUNNING, NoServer(connected = true), fromTouch = true))

    @Test
    fun log() = shoot(1080, 2400, "desktop-log", SampleHost(RUNNING, NoServer(connected = true)), log = true)

    // The bar's ⋮: Touch (its choice), Log, Restart desktop, Shut down.
    @Test
    fun menu() = shoot(1080, 2400, "desktop-menu", SampleHost(RUNNING, NoServer(connected = true))) { it.toggleMenu(byKeyboard = false) }

    // Shut down's first tap: the row turns pink and asks for a second.
    @Test
    fun menuShutDown() = shoot(1080, 2400, "desktop-menu-shut-down", SampleHost(RUNNING, NoServer(connected = true))) {
        it.toggleMenu(byKeyboard = false)
        tap(it, "Shut down")
    }

    // Opened with Ctrl+Shift+M: the keyboard's row is marked; the log is behind it.
    @Test
    fun menuKeyboard() = shoot(1080, 2400, "desktop-menu-keyboard", SampleHost(RUNNING, NoServer(connected = true)), log = true) {
        it.toggleMenu(byKeyboard = true)
    }

    // Nothing runs: only Touch and Log.
    @Test
    fun menuStopped() = shoot(1080, 2400, "desktop-menu-stopped", SampleHost(XFCE_MISSING, NoServer())) { it.toggleMenu(byKeyboard = false) }

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun menuNarrow() = shoot(945, 2100, "desktop-menu-360dp", SampleHost(RUNNING, NoServer(connected = true))) {
        it.toggleMenu(byKeyboard = false)
        tap(it, "Shut down")
    }

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun menuLandscape() = shoot(2400, 1080, "desktop-menu-landscape", SampleHost(RUNNING, NoServer(connected = true))) {
        it.toggleMenu(byKeyboard = false)
    }

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "desktop-360dp", SampleHost(XFCE_MISSING, NoServer()))

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "desktop-landscape", SampleHost(RUNNING, NoServer(connected = true)))
}
