package io.github.est4s.terminal

import android.app.Application
import android.graphics.Typeface
import android.view.Surface
import io.github.est4s.terminal.core.DesktopSession
import io.github.est4s.terminal.core.DesktopStatus
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.X11Input
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
}

private class SampleHost(private val status: DesktopStatus, override val link: DesktopScreenLink) : DesktopView.Host {
    override fun status() = status
    override fun start(done: (String?) -> Unit) = Unit
    override fun logs() = link.log() + "\n\n/tmp/.mynx/desktop/x11.log:\nXlorie: new surface applied\n" +
        "\n\n/tmp/.mynx/desktop/session.log:\nxfce4-session: Could not connect to the session bus\n"
    override fun touch() = DesktopTouch.TOUCHPAD
    override fun showTabs() = Unit
}

private val XFCE_MISSING = DesktopStatus(
    running = false, session = DesktopSession.XFCE, sessionRunning = false,
    problem = "XFCE isn't installed: apt install xfce4 dbus-x11 installs it (or mynx set desktop none runs the X server alone)",
)
private val RUNNING = DesktopStatus(running = true, session = DesktopSession.XFCE, sessionRunning = true, problem = null)

/** The desktop's view without an X server: its bar, notices, key bar and pointer (see [Screenshots]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class DesktopScreenshotTest {
    private fun shoot(w: Int, h: Int, name: String, host: DesktopView.Host, log: Boolean = false) {
        val bars = Files.createTempDirectory("keybars").toFile()
        val view = Screenshots.open(w, h) { activity ->
            DesktopView(activity, Typeface.MONOSPACE, bars, host).apply { colors = Screenshots.colors }
        }
        view.shown()
        Screenshots.settle()
        if (log) view.toggleLog()
        Screenshots.settle()
        Screenshots.save(view, name)
        view.hidden()
    }

    @Test
    fun missing() = shoot(1080, 2400, "desktop", SampleHost(XFCE_MISSING, NoServer()))

    @Test
    fun connecting() = shoot(1080, 2400, "desktop-connecting", SampleHost(RUNNING, NoServer()))

    // Connected: the screen is the server's (black here), with our pointer in the middle.
    @Test
    fun connected() = shoot(1080, 2400, "desktop-connected", SampleHost(RUNNING, NoServer(connected = true)))

    @Test
    fun log() = shoot(1080, 2400, "desktop-log", SampleHost(RUNNING, NoServer(connected = true)), log = true)

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "desktop-360dp", SampleHost(XFCE_MISSING, NoServer()))

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "desktop-landscape", SampleHost(RUNNING, NoServer(connected = true)))
}
