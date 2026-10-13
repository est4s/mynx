package io.github.est4s.terminal

import android.os.Handler
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.TerminalSession
import io.github.est4s.terminal.core.AdoptedTab
import io.github.est4s.terminal.core.Foreground
import io.github.est4s.terminal.core.ServiceDef
import io.github.est4s.terminal.core.ServiceHost

// A hidden session's size until it's shown. The library starts a session's
// process on its first updateSize(), which a tab gets from attachSession():
// a session that's never shown must be given one.
private const val COLUMNS = 80
private const val ROWS = 24
private const val CELL_WIDTH_PX = 10
private const val CELL_HEIGHT_PX = 20

/**
 * The app's side of the background services: each one a [TerminalSession]
 * that no tab shows. [newSession] makes one (not started yet) as a tab's,
 * with the wrapper's command; [stopSession] stops a session's proot.
 * The tabs side ([openTab], [followTab], [adoptTab], [foregroundOf]) is
 * the service's. Everything runs on [handler]'s (the main) thread.
 */
class ServiceSessions(
    private val handler: Handler,
    private val newSession: (def: ServiceDef, saved: Boolean) -> TerminalSession,
    private val stopSession: (TerminalSession) -> Unit,
    private val openTab: (name: String, session: TerminalSession) -> String?,
    private val followTab: (name: String, session: TerminalSession) -> Unit,
    private val adoptTab: (name: String, shell: Int) -> Result<AdoptedTab<TerminalSession>>,
    private val foregroundOf: (TerminalSession) -> Foreground?,
    private val notifyGaveUp: (name: String, exitCode: Int?) -> Unit,
    private val onChanged: () -> Unit,
) : ServiceHost<TerminalSession> {
    /** Set when the app exits: nothing starts again, nothing is reported. */
    var exiting = false

    override fun start(def: ServiceDef, saved: Boolean): TerminalSession {
        check(!exiting) { "the app is exiting" }
        val session = newSession(def, saved)
        session.updateSize(COLUMNS, ROWS, CELL_WIDTH_PX, CELL_HEIGHT_PX)
        check(session.pid > 0) { "its terminal didn't start" }
        return session
    }

    override fun pid(session: TerminalSession): Int? = session.pid.takeIf { it > 0 }

    // proot ignores TERM, so it goes to the command's process group, which
    // the wrapper wrote down; what hasn't ended after the timeout is forced.
    override fun stop(session: TerminalSession, group: Int?, timeoutSeconds: Int) {
        if (group == null) {
            stopSession(session)
            return
        }
        runCatching { Os.kill(-group, OsConstants.SIGTERM) }
        handler.postDelayed({ if (session.isRunning) stopSession(session) }, timeoutSeconds * 1000L)
    }

    override fun transcript(session: TerminalSession): String =
        session.emulator?.screen?.transcriptText.orEmpty()

    override fun open(name: String, session: TerminalSession): String? = openTab(name, session)

    override fun started(name: String, session: TerminalSession) = followTab(name, session)

    override fun adopt(name: String, shell: Int): Result<AdoptedTab<TerminalSession>> = adoptTab(name, shell)

    override fun foreground(session: TerminalSession): Foreground? = foregroundOf(session)

    override fun schedule(delayMs: Long, block: () -> Unit) {
        handler.postDelayed({ if (!exiting) block() }, delayMs)
    }

    override fun gaveUp(name: String, exitCode: Int?) {
        if (!exiting) notifyGaveUp(name, exitCode)
    }

    override fun changed() {
        if (!exiting) onChanged()
    }
}
