package io.github.est4s.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.MynxTerminals
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.est4s.terminal.core.ScreenShare
import io.github.est4s.terminal.core.ScreenTarget
import io.github.est4s.terminal.core.SentTerm
import io.github.est4s.terminal.core.TermOpened
import io.github.est4s.terminal.core.TermScreen
import io.github.est4s.terminal.core.Tabs
import io.github.est4s.terminal.core.screenEmulatorSends
import io.github.est4s.terminal.core.terminalBytes
import io.github.est4s.terminal.core.terminalCodePoint

private const val FIRST_COLUMNS = 80
private const val FIRST_ROWS = 24

/**
 * On the host: its terminal tabs sent to the other phone (core's
 * ScreenCasting's terminal half; docs/ARCHITECTURE.md, "Extended screen:
 * terminals on B"). Sending takes the tab off the strip, its session
 * running on, and taps its output ([MynxTerminals.tee], put in every
 * session as it's made); taking it back puts it back. Main thread.
 */
class TermCaster(private val host: Host) {
    interface Host {
        val tabs: Tabs<TerminalSession>
        /** The tab whose shell has MYNX_SHELL=[id]. */
        fun sessionOfShell(id: Int): TerminalSession?
        /** A shell started for the other phone, not on the strip (it starts once it has a size). */
        fun newShell(): TerminalSession
        /** A fresh tab on the strip: sending the last one leaves this phone one. */
        fun newTab()
        fun output(id: Int, data: ByteArray)
        /** A sent session that ended for good: its files go. */
        fun ended(session: TerminalSession)
        /** This phone's typing goes to [session] now, or (null) no longer. */
        fun typing(session: TerminalSession?, title: String)
    }

    private class Cast(val id: Int, val session: TerminalSession, val rename: String?) {
        var columns = FIRST_COLUMNS
        var rows = FIRST_ROWS
    }

    private val casts = LinkedHashMap<Int, Cast>()

    /** The sent terminal [session] is, or null. */
    fun idOf(session: TerminalSession): Int? = casts.values.firstOrNull { it.session === session }?.id

    /** The sessions sent (to stop them with the app). */
    val sessions: List<TerminalSession> get() = casts.values.map { it.session }

    /** What the other phone calls [id]: the tab's name, else what its programs call it. */
    fun titleOf(id: Int): String? {
        val cast = casts[id] ?: return null
        return cast.rename ?: cast.session.title?.takeIf { it.isNotBlank() } ?: "terminal"
    }

    fun openTerm(id: Int, target: ScreenTarget.Terminal): Result<TermOpened> {
        val tabs = host.tabs
        val which = target.name
        val session: TerminalSession
        var rename: String? = null
        var title = "terminal"
        if (which == "new") {
            session = host.newShell()
        } else {
            val tab = if (which.startsWith("shell:")) {
                val shell = which.removePrefix("shell:").toIntOrNull()
                val found = shell?.let(host::sessionOfShell) ?: return fail("that tab isn't on this phone's strip")
                tabs.find(found) ?: return fail("that tab isn't on this phone's strip")
            } else {
                val n = which.toIntOrNull() ?: return fail("a tab is its number (1 is the first) or new")
                tabs.tabs.getOrNull(n - 1) ?: return fail("there's no tab $n (this phone has ${tabs.tabs.size})")
            }
            if (tab.service != null) return fail("${tab.title} is the service ${tab.service}'s tab: services' tabs stay here")
            if (tab.from != null) return fail("${tab.title} is from ${tab.from}")
            session = tab.session
            rename = tab.rename
            title = tab.title
        }
        if (!MynxTerminals.setTap(session, MynxTerminals.Tap { data -> host.output(id, data) })) {
            return fail("this tab can't be sent (its session isn't one mynx can share): send a new one (mynx screen send tab new)")
        }
        val cast = Cast(id, session, rename)
        session.emulator?.let {
            cast.columns = it.mColumns
            cast.rows = it.mRows
        }
        casts[id] = cast
        if (which != "new") {
            if (tabs.tabs.size == 1) host.newTab()
            tabs.close(session)
        }
        return Result.success(TermOpened(title, cast.columns, cast.rows))
    }

    private fun fail(why: String): Result<TermOpened> = Result.failure(Exception(why))

    fun termScreen(id: Int): TermScreen? {
        val cast = casts[id] ?: return null
        return termScreenOf(cast.session.emulator, cast.columns, cast.rows)
    }

    fun resizeTerm(id: Int, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val cast = casts[id] ?: return
        cast.columns = columns.coerceAtLeast(4)
        cast.rows = rows.coerceAtLeast(4)
        // A new tab's shell starts here, at the other phone's size.
        cast.session.updateSize(cast.columns, cast.rows, cellWidth.coerceAtLeast(1), cellHeight.coerceAtLeast(1))
    }

    fun writeTerm(id: Int, data: ByteArray) {
        val session = casts[id]?.session ?: return
        session.write(data, 0, data.size)
    }

    fun closeTerm(id: Int, back: Boolean) {
        val cast = casts.remove(id) ?: return
        MynxTerminals.setTap(cast.session, null)
        if (typedTo === cast.session) typingToTerm(null)
        if (!back) {
            host.ended(cast.session)
            return
        }
        val tabs = host.tabs
        // A new tab's shell that never had a size never started: nothing to bring back.
        if (cast.session.emulator == null) {
            host.ended(cast.session)
            return
        }
        tabs.open(cast.session)
        tabs.setShellTitle(cast.session, cast.session.title ?: "")
        cast.rename?.let { tabs.rename(cast.session, it) }
    }

    /** The session typing goes to now, if a sent terminal's. */
    var typedTo: TerminalSession? = null
        private set

    fun typingToTerm(term: SentTerm?) {
        val session = term?.let { casts[it.id]?.session }
        typedTo = session
        host.typing(session, term?.title ?: "")
    }

    /** Everything goes (the app stops): the sessions are the service's to stop. */
    fun forgetAll() {
        casts.values.forEach { MynxTerminals.setTap(it.session, null) }
        casts.clear()
        typedTo = null
    }
}

/**
 * Typing on this phone for a terminal that isn't the one shown (a sent
 * one, the other phone touched last): keys written to [session] as the
 * terminal view writes them (Termux's KeyHandler, the view's Ctrl
 * mapping). The sticky Ctrl and Alt come from the key bar.
 */
class TermKeys(private val takeCtrl: () -> Boolean, private val takeAlt: () -> Boolean) {
    /** A key pressed; false if it isn't one for the terminal (Back, the volume keys). */
    fun key(session: TerminalSession, keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem && keyCode != KeyEvent.KEYCODE_ENTER) return false
        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let { session.write(it) }
            return true
        }
        val ctrl = event.isCtrlPressed || takeCtrl()
        val alt = event.isAltPressed || takeAlt()
        if (code(session, keyCode, ctrl, alt, event.isShiftPressed)) return true
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON).inv()
        val char = event.getUnicodeChar(meta)
        if (char == 0) return false
        codePoint(session, char, ctrl, alt)
        return true
    }

    /** A key bar's or the keyboard's key [keyCode] with its modifiers, if it has a terminal sequence. */
    fun code(session: TerminalSession, keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean {
        val emulator = session.emulator ?: return true
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        if (shift) mod = mod or KeyHandler.KEYMOD_SHIFT
        val code = KeyHandler.getCode(keyCode, mod, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
            ?: return false
        session.write(code)
        return true
    }

    /** A character typed (the soft keyboard's, the key bar's). */
    fun codePoint(session: TerminalSession, codePoint: Int, ctrl: Boolean, alt: Boolean) {
        val bytes = terminalBytes(terminalCodePoint(codePoint, ctrl), alt)
        session.write(bytes, 0, bytes.size)
    }
}

/**
 * On the screen: the terminals the host sent, each a tab here with a
 * session of no process ([MynxTerminals.remote]) that the host's bytes
 * feed. What's typed into one goes to the host. Main thread.
 */
class RemoteTerms(
    private val tabs: () -> Tabs<TerminalSession>,
    private val client: TerminalSessionClient,
    private val share: () -> ScreenShare,
    private val peerName: () -> String,
    /** The last tab here went with it: a fresh one. */
    private val newTab: () -> Unit,
) {
    private val sessions = LinkedHashMap<Int, TerminalSession>()

    fun idOf(session: TerminalSession): Int? = sessions.entries.firstOrNull { it.value === session }?.key

    fun isRemote(session: TerminalSession): Boolean = idOf(session) != null

    fun started(id: Int, title: String, columns: Int, rows: Int) {
        if (id in sessions) return
        val session = MynxTerminals.remote(client, columns, rows, TRANSCRIPT_ROWS, object : MynxTerminals.Input {
            override fun typed(data: ByteArray) = share().termInput(id, data)
            override fun sends(data: ByteArray): Boolean = screenEmulatorSends(data)
        })
        sessions[id] = session
        tabs().openFrom(session, peerName(), title)
    }

    fun state(id: Int, columns: Int, rows: Int, data: ByteArray) {
        val session = sessions[id] ?: return
        MynxTerminals.resize(session, columns, rows)
        MynxTerminals.feed(session, data)
    }

    fun output(id: Int, data: ByteArray) {
        sessions[id]?.let { MynxTerminals.feed(it, data) }
    }

    fun title(id: Int, title: String) {
        sessions[id]?.let { tabs().setShellTitle(it, title) }
    }

    fun ended(id: Int) {
        val session = sessions.remove(id) ?: return
        MynxTerminals.endRemote(session)
        val tabs = tabs()
        if (tabs.tabs.size == 1 && tabs.find(session) != null) newTab()
        tabs.close(session)
    }

    /** The terminal view here fits [session] (one of ours) in [columns] x [rows]: the host's size follows. */
    fun fitted(session: TerminalSession, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val id = idOf(session) ?: return
        share().fitTerm(id, columns, rows, cellWidth, cellHeight)
    }

    /** [session] (one of ours) was touched here: the host's typing comes to it. */
    fun touched(session: TerminalSession) {
        idOf(session)?.let { share().termTouched(it) }
    }

    /** Closing its tab here gives it back to the host. */
    fun giveBack(session: TerminalSession): Boolean {
        val id = idOf(session) ?: return false
        share().take("$id")
        return true
    }

    companion object {
        const val TRANSCRIPT_ROWS = 2000
    }
}
