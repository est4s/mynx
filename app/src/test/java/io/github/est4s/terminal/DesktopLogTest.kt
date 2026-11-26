package io.github.est4s.terminal

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.Selection
import android.text.Spannable
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.est4s.terminal.core.DesktopSession
import io.github.est4s.terminal.core.DesktopStatus
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.desktopBackLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

private class QuietLink : DesktopScreenLink {
    override val connected = true
    override var listener: (() -> Unit)? = null
    override fun log() = "07:12:03 connected to the X server"
    override fun send(events: List<X11Input>) = Unit
    override fun surface(surface: Surface, width: Int, height: Int, framerate: Int, fresh: Boolean) = Unit
    override fun surfaceGone() = Unit
    override fun androidClipboard(text: String?) = Unit
}

private class LogHost(var text: String) : DesktopView.Host {
    override val link: DesktopScreenLink = QuietLink()
    override fun status() = DesktopStatus(running = true, session = DesktopSession.XFCE, sessionRunning = true, problem = null)
    override fun start(done: (String?) -> Unit) = Unit
    override fun logs() = text
    override fun touch() = DesktopTouch.TOUCHPAD
    override fun back() = Unit
    override fun backLabel() = desktopBackLabel(false)
    override fun stop() = Unit
    override fun install() = Unit
}

/** The Log panel: it refreshes every second, which mustn't undo a selection, and Copy copies it all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class DesktopLogTest {
    private val logs = "07:12:03 connected to the X server\n\n/tmp/.mynx/desktop/x11.log:\nXlorie: new surface applied\n"

    private fun open(host: LogHost): DesktopView {
        val bars = Files.createTempDirectory("keybars").toFile()
        val view = Screenshots.open(1080, 2400) { activity -> DesktopView(activity, Typeface.MONOSPACE, bars, host) }
        view.shown()
        view.toggleLog()
        Screenshots.settle()
        return view
    }

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun logText(view: DesktopView) =
        views(view).filterIsInstance<TextView>().first { it.isShown && it.text.contains("x11.log") }

    @Test
    fun aRefreshWithTheSameLogsKeepsTheSelection() {
        val view = open(LogHost(logs))
        val text = logText(view)
        Selection.setSelection(text.text as Spannable, 0, 8)
        view.refresh()
        assertEquals(0, text.selectionStart)
        assertEquals(8, text.selectionEnd)
        view.hidden()
    }

    @Test
    fun copyPutsTheLogsOnTheClipboard() {
        val view = open(LogHost(logs))
        views(view).filterIsInstance<TextView>().first { it.isShown && it.text == "Copy" }.performClick()
        val clip = view.context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(logs, clip.getItemAt(0).text.toString())
        view.hidden()
    }
}
