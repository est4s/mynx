package io.github.est4s.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import io.github.est4s.terminal.core.KeyPress
import io.github.est4s.terminal.core.tabShortcut

private const val TAG = "PocketTerminal"

// Owned by the service, so it never keeps a closed activity alive: it forwards
// to whichever activity is attached right now, if any.
class SessionClient(private val service: TerminalService) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        service.tabs.onOutput(changedSession)
        service.activity?.onScreenUpdated(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        service.tabs.setShellTitle(changedSession, changedSession.title ?: "")
    }

    // The library prints "[Process completed (code N) - press Enter]" for tabs
    // that stay open; ViewClient handles the Enter.
    override fun onSessionFinished(finishedSession: TerminalSession) = service.onSessionFinished(finishedSession)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        service.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = service.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(service).toString()
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = service.tabs.onBell(session)
    override fun onColorsChanged(session: TerminalSession) {
        service.activity?.onScreenUpdated(session)
    }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
}

class ViewClient(private val activity: MainActivity) : TerminalViewClient {
    // Pinch to zoom: change the font size once the gesture is big enough.
    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        activity.textSizePx += if (scale > 1f) 2 else -2
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) = activity.showKeyboard()
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val key = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        tabShortcut(KeyPress(key, ctrl = e.isCtrlPressed, shift = e.isShiftPressed, alt = e.isAltPressed))?.let {
            // Holding the keys must not open or close a row of tabs.
            if (e.repeatCount == 0) activity.onTabAction(it)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            activity.restartShell(session)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
}
