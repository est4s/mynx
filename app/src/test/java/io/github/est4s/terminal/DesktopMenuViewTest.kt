package io.github.est4s.terminal

import android.app.Application
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.est4s.terminal.core.AlignStep
import io.github.est4s.terminal.core.DesktopAlignKey
import io.github.est4s.terminal.core.DesktopMenuOther
import io.github.est4s.terminal.core.DesktopRuler
import io.github.est4s.terminal.core.DesktopSession
import io.github.est4s.terminal.core.DesktopStatus
import io.github.est4s.terminal.core.DesktopTouch
import io.github.est4s.terminal.core.ScreenDesktopMode
import io.github.est4s.terminal.core.ScreenSide
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.desktopBackLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

private class SentLink : DesktopScreenLink {
    val sent = mutableListOf<X11Input>()
    override val connected = true
    override var listener: (() -> Unit)? = null
    override fun log() = "07:12:03 connected to the X server"
    override fun send(events: List<X11Input>) {
        sent += events
    }
    override fun surface(surface: Surface, width: Int, height: Int, framerate: Int, fresh: Boolean) = Unit
    override fun surfaceGone() = Unit
    override fun androidClipboard(text: String?) = Unit
}

private class MenuHost : DesktopView.Host {
    override val link = SentLink()
    var saved = DesktopTouch.TOUCHPAD
    val calls = mutableListOf<String>()
    override fun status() = DesktopStatus(running = true, session = DesktopSession.XFCE, sessionRunning = true, problem = null)
    override fun start(done: (String?) -> Unit) {
        calls += "start"
    }
    override fun logs() = "07:12:03 connected to the X server\n\n/tmp/.mynx/desktop/x11.log:\nXlorie: new surface applied\n"
    override fun touch() = saved
    override fun back() = Unit
    override fun backLabel() = desktopBackLabel(false)
    override fun stop() {
        calls += "stop"
    }
    override fun install() = Unit
    override fun restart(done: (String?) -> Unit) {
        calls += "restart"
    }
    override fun setTouch(touch: DesktopTouch) {
        calls += "touch ${touch.word}"
        saved = touch
    }

    // The extended screen: a phone linked as this one's screen, and aligning with it.
    var other: DesktopMenuOther? = null
    var alignShown: DesktopAligning? = null
    override fun otherPhone() = other
    override fun setScreenPlace(side: ScreenSide): String? {
        calls += "place ${side.word}"
        other = other?.copy(side = side)
        return null
    }
    override fun setScreenDesktop(mode: ScreenDesktopMode): String? {
        calls += "screen-desktop ${mode.word}"
        other = other?.copy(mode = mode)
        return null
    }
    override fun align(): String? {
        calls += "align"
        alignShown = DesktopAligning(ScreenSide.RIGHT, DesktopRuler(0f, 33f), "offset 0 mm · scale 1.000", "Nord 2")
        return null
    }
    override fun aligning() = alignShown
    override fun alignKey(key: DesktopAlignKey) {
        calls += "key $key"
    }
    override fun alignStep(step: AlignStep) {
        calls += "step $step"
        if (step != AlignStep.RESET) alignShown = null
    }
}

// Android's KeyEvent meta state for Ctrl+Shift.
private const val CTRL_SHIFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

/** The ⋮ menu's rows do what they say, from taps and from a hardware keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class DesktopMenuViewTest {
    private fun open(host: MenuHost): DesktopView {
        val bars = Files.createTempDirectory("keybars").toFile()
        val view = Screenshots.open(1080, 2400) { activity -> DesktopView(activity, Typeface.MONOSPACE, bars, host) }
        view.shown()
        Screenshots.settle()
        return view
    }

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun shownTexts(view: DesktopView) = views(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun tap(view: DesktopView, label: String) {
        views(view).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == label }.performClick()
        Screenshots.settle()
    }

    private fun key(view: DesktopView, code: Int, meta: Int = 0): Boolean {
        val down = view.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        val up = view.onKey(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        Screenshots.settle()
        return down && up
    }

    @Test
    fun theBarHasTheMenuButtonAndNoLogButton() {
        val view = open(MenuHost())
        val texts = shownTexts(view)
        assertTrue("⋮" in texts)
        assertFalse("Log" in texts)
        view.hidden()
    }

    @Test
    fun shutDownTakesTwoTaps() {
        val host = MenuHost()
        val view = open(host)
        tap(view, "⋮")
        tap(view, "Shut down")
        assertEquals(emptyList<String>(), host.calls)
        tap(view, "Tap again to shut down")
        assertEquals(listOf("stop"), host.calls)
        view.hidden()
    }

    @Test
    fun theConfirmationGoesBackAfterThreeSeconds() {
        val view = open(MenuHost())
        tap(view, "⋮")
        tap(view, "Shut down")
        // settle() runs 1.5 s of posts; three of them are past the 3 s.
        repeat(3) { Screenshots.settle() }
        assertTrue("Shut down" in shownTexts(view))
        assertFalse("Tap again to shut down" in shownTexts(view))
        view.hidden()
    }

    @Test
    fun touchSwitchesTheSettingAndTheViewAtOnce() {
        val host = MenuHost()
        val view = open(host)
        tap(view, "⋮")
        tap(view, "Direct")
        assertEquals(listOf("touch direct"), host.calls)
        assertTrue(shownTexts(view).any { it.endsWith("· direct") })
        // The menu stays, to show the change.
        assertTrue("Restart desktop" in shownTexts(view))
        view.hidden()
    }

    @Test
    fun logAndRestartCloseTheMenu() {
        val host = MenuHost()
        val view = open(host)
        tap(view, "⋮")
        tap(view, "Log")
        assertTrue(shownTexts(view).any { "x11.log" in it })
        assertFalse("Restart desktop" in shownTexts(view))
        tap(view, "⋮")
        assertTrue("Hide log" in shownTexts(view))
        tap(view, "Restart desktop")
        assertEquals(listOf("restart"), host.calls)
        view.hidden()
    }

    @Test
    fun ctrlShiftMOpensItAndTheKeysWorkIt() {
        val host = MenuHost()
        val view = open(host)
        assertTrue(key(view, KeyEvent.KEYCODE_M, CTRL_SHIFT))
        assertTrue("Restart desktop" in shownTexts(view))
        // Up from the first row is the last: Shut down, twice.
        key(view, KeyEvent.KEYCODE_DPAD_UP)
        key(view, KeyEvent.KEYCODE_ENTER)
        assertTrue("Enter again to shut down" in shownTexts(view))
        key(view, KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("stop"), host.calls)
        view.hidden()
    }

    @Test
    fun escapeClosesItAndItsKeysDontReachTheDesktop() {
        val host = MenuHost()
        val view = open(host)
        key(view, KeyEvent.KEYCODE_M, CTRL_SHIFT)
        host.link.sent.clear()
        assertTrue(key(view, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(key(view, KeyEvent.KEYCODE_A))
        assertTrue(key(view, KeyEvent.KEYCODE_ESCAPE))
        assertFalse("Restart desktop" in shownTexts(view))
        assertEquals(emptyList<X11Input>(), host.link.sent)
        view.hidden()
    }

    @Test
    fun backClosesTheMenuFirst() {
        val view = open(MenuHost())
        tap(view, "⋮")
        assertTrue(view.back())
        assertFalse("Restart desktop" in shownTexts(view))
        assertFalse(view.back())
        view.hidden()
    }

    @Test
    fun theOtherPhonesSectionShowsOnlyWhileOneIsLinked() {
        val view = open(MenuHost())
        tap(view, "⋮")
        assertFalse("OTHER PHONE" in shownTexts(view))
        view.hidden()
        val linked = MenuHost().apply { other = DesktopMenuOther(ScreenSide.RIGHT, ScreenDesktopMode.EXTEND) }
        val shown = open(linked)
        tap(shown, "⋮")
        val texts = shownTexts(shown)
        for (t in listOf("OTHER PHONE", "Screen sits", "Left", "Right", "Above", "Below", "Desktop there", "Own monitor", "Half of one", "Align screens…")) {
            assertTrue(t, t in texts)
        }
        shown.hidden()
    }

    @Test
    fun theOtherPhonesChoicesSetTheSettingsAndKeepTheMenuOpen() {
        val host = MenuHost().apply { other = DesktopMenuOther(ScreenSide.RIGHT, ScreenDesktopMode.EXTEND) }
        val view = open(host)
        tap(view, "⋮")
        tap(view, "Above")
        tap(view, "Half of one")
        assertEquals(listOf("place above", "screen-desktop span"), host.calls)
        assertTrue("Align screens…" in shownTexts(view))
        tap(view, "Align screens…")
        assertEquals("align", host.calls.last())
        assertFalse("Screen sits" in shownTexts(view))
        view.hidden()
    }

    @Test
    fun aligningShowsItsPanelAndTakesTheKeys() {
        val host = MenuHost().apply { other = DesktopMenuOther(ScreenSide.RIGHT, ScreenDesktopMode.EXTEND) }
        val view = open(host)
        host.align()
        view.refresh()
        Screenshots.settle()
        assertTrue("Align screens" in shownTexts(view))
        assertTrue("offset 0 mm · scale 1.000" in shownTexts(view))
        host.link.sent.clear()
        assertTrue(key(view, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(key(view, KeyEvent.KEYCODE_PLUS))
        assertEquals(listOf("align", "key FORWARD", "key BIGGER"), host.calls)
        // Not the desktop's keys meanwhile.
        assertEquals(emptyList<X11Input>(), host.link.sent)
        tap(view, "Reset")
        assertEquals("step RESET", host.calls.last())
        tap(view, "Done")
        assertEquals("step DONE", host.calls.last())
        assertFalse("Align screens" in shownTexts(view))
        view.hidden()
    }

    @Test
    fun backCancelsAligning() {
        val host = MenuHost().apply { other = DesktopMenuOther(ScreenSide.RIGHT, ScreenDesktopMode.EXTEND) }
        val view = open(host)
        host.align()
        view.refresh()
        assertTrue(view.back())
        assertEquals("step CANCEL", host.calls.last())
        assertFalse(view.back())
        view.hidden()
    }
}
