package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;

import io.github.est4s.terminal.TermScreensKt;
import io.github.est4s.terminal.core.ScreenTermsKt;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The extended screen's terminals with Termux's real emulator (no
 * process: these tests feed it bytes): the host's tap, and a screen's
 * remote session drawing what the host's emulator shows from a snapshot,
 * then staying in step as the same bytes reach both.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MynxTerminalsTest {
    private static final int COLUMNS = 20;
    private static final int ROWS = 5;

    private static final TerminalSessionClient CLIENT = new TerminalSessionClient() {
        @Override public void onTextChanged(TerminalSession changedSession) {}
        @Override public void onTitleChanged(TerminalSession changedSession) {}
        @Override public void onSessionFinished(TerminalSession finishedSession) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public Integer getTerminalCursorStyle() { return 0; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    };

    private static final class Typed implements MynxTerminals.Input {
        final List<String> typed = Collections.synchronizedList(new ArrayList<>());

        @Override public void typed(byte[] data) { typed.add(new String(data, StandardCharsets.UTF_8)); }

        @Override public boolean sends(byte[] data) { return ScreenTermsKt.screenEmulatorSends(data); }
    }

    private static TerminalEmulator host() {
        return new TerminalEmulator(sink(), COLUMNS, ROWS, 10, 20, 100, CLIENT);
    }

    // The host's emulator answers into its pty: here, nowhere.
    private static TerminalOutput sink() {
        return new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static byte[] snapshot(TerminalEmulator emulator) {
        return ScreenTermsKt.termSnapshot(TermScreensKt.termScreenOf(emulator, COLUMNS, ROWS), ScreenTermsKt.TERM_STATE_MAX);
    }

    // Everything the screen shows: every row's text and styles (scrollback too), the cursor, the modes.
    private static String look(TerminalEmulator emulator) {
        StringBuilder out = new StringBuilder();
        TerminalBuffer screen = emulator.getScreen();
        for (int row = -screen.getActiveTranscriptRows(); row < emulator.mRows; row++) {
            out.append(screen.getSelectedText(0, row, emulator.mColumns - 1, row)).append('|');
            for (int col = 0; col < emulator.mColumns; col++) out.append(Long.toHexString(screen.getStyleAt(row, col))).append(',');
            out.append(screen.getLineWrap(row) ? "~" : "").append('\n');
        }
        out.append("cursor ").append(emulator.getCursorRow()).append(',').append(emulator.getCursorCol())
            .append(" alt ").append(emulator.isAlternateBufferActive())
            .append(" keys ").append(emulator.isCursorKeysApplicationMode())
            .append(" mouse ").append(emulator.isMouseTrackingActive())
            .append(" cursor shown ").append(emulator.isCursorEnabled());
        return out.toString();
    }

    private static final String SHELL =
        "line one\r\nline two\r\n\u001b[1;31mred bold\u001b[0m and \u001b[38;2;10;20;30mrgb\u001b[0m\r\n" +
            "a line long enough to wrap around\r\n漢字 é\r\nmore\r\nand more\r\n$ ";

    @Test
    public void aSnapshotDrawsWhatTheHostShows() {
        TerminalEmulator host = host();
        append(host, SHELL);
        Typed typed = new Typed();
        TerminalSession screen = MynxTerminals.remote(CLIENT, COLUMNS, ROWS, 100, typed);
        MynxTerminals.feed(screen, snapshot(host));
        assertEquals(look(host), look(screen.getEmulator()));
    }

    @Test
    public void afterASnapshotTheSameBytesKeepThemInStep() {
        TerminalEmulator host = host();
        append(host, SHELL + "\u001b[2;4r\u001b[?1h\u001b[33m");
        TerminalSession screen = MynxTerminals.remote(CLIENT, COLUMNS, ROWS, 100, new Typed());
        MynxTerminals.feed(screen, snapshot(host));
        String more = "yellow\r\n\n\n\n\u001b[0mscrolled in the margins\u001b[r\u001b[5;1Hend";
        append(host, more);
        MynxTerminals.feed(screen, more.getBytes(StandardCharsets.UTF_8));
        assertEquals(look(host), look(screen.getEmulator()));
    }

    @Test
    public void aFullScreenProgramsScreenAndTheShellUnderIt() {
        TerminalEmulator host = host();
        append(host, SHELL + "vim\r\n\u001b[?1049h\u001b[?1000h\u001b[?1006h\u001b[?25l\u001b[H\u001b[7m NORMAL \u001b[0m\u001b[3;5Hx");
        TerminalSession screen = MynxTerminals.remote(CLIENT, COLUMNS, ROWS, 100, new Typed());
        MynxTerminals.feed(screen, snapshot(host));
        assertEquals(look(host), look(screen.getEmulator()));
        // The program quits: both show the shell again.
        String quit = "\u001b[?1049l\u001b[?1000l\u001b[?25h$ ";
        append(host, quit);
        MynxTerminals.feed(screen, quit.getBytes(StandardCharsets.UTF_8));
        assertEquals(look(host), look(screen.getEmulator()));
    }

    @Test
    public void aSnapshotAtAnotherSizeAfterTheScreenResized() {
        TerminalEmulator host = host();
        append(host, SHELL);
        TerminalSession screen = MynxTerminals.remote(CLIENT, COLUMNS, ROWS, 100, new Typed());
        // The screen's view fits 15 x 4; the host resizes to it and sends its state.
        screen.getEmulator().resize(15, 4, 10, 20);
        host.resize(15, 4, 10, 20);
        MynxTerminals.resize(screen, 15, 4);
        MynxTerminals.feed(screen, ScreenTermsKt.termSnapshot(TermScreensKt.termScreenOf(host, 15, 4), ScreenTermsKt.TERM_STATE_MAX));
        assertEquals(look(host), look(screen.getEmulator()));
    }

    @Test
    public void whatsTypedGoesToTheHostButTheEmulatorsAnswersDont() throws Exception {
        Typed typed = new Typed();
        TerminalSession screen = MynxTerminals.remote(CLIENT, COLUMNS, ROWS, 100, typed);
        assertTrue(MynxTerminals.isRemote(screen));
        assertTrue(screen.isRunning());
        screen.write("ls\r");
        // A question from the host's program: the host's emulator answers it, not this one.
        MynxTerminals.feed(screen, "\u001b[6n\u001b[c".getBytes(StandardCharsets.UTF_8));
        screen.getEmulator().paste("pasted");
        long deadline = System.currentTimeMillis() + 5000;
        while (typed.typed.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(typed.typed.contains("ls\r"));
        assertTrue(typed.typed.contains("pasted"));
        for (String t : typed.typed) assertFalse(t, t.startsWith("\u001b["));
        MynxTerminals.endRemote(screen);
        assertFalse(screen.isRunning());
    }

    @Test
    public void theTapGetsTheOutputBeforeTheEmulator() {
        TerminalSession session = new TerminalSession("/system/bin/sh", "/", new String[]{"sh"}, new String[0], 100, CLIENT);
        assertTrue(MynxTerminals.tee(session));
        session.mEmulator = new TerminalEmulator(session, COLUMNS, ROWS, 10, 20, 100, CLIENT);
        ByteArrayOutputStream tapped = new ByteArrayOutputStream();
        assertTrue(MynxTerminals.setTap(session, data -> tapped.write(data, 0, data.length)));
        byte[] out = "hello".getBytes(StandardCharsets.UTF_8);
        session.mProcessToTerminalIOQueue.write(out, 0, out.length);
        session.mMainThreadHandler.sendEmptyMessage(1);
        shadowOf(Looper.getMainLooper()).idle();
        assertArrayEquals(out, tapped.toByteArray());
        assertEquals("hello", session.getEmulator().getScreen().getSelectedText(0, 0, COLUMNS - 1, 0).trim());
        // No tap: the output still reaches the emulator, as with the library's own handler.
        assertTrue(MynxTerminals.setTap(session, null));
        byte[] again = " again".getBytes(StandardCharsets.UTF_8);
        session.mProcessToTerminalIOQueue.write(again, 0, again.length);
        session.mMainThreadHandler.sendEmptyMessage(1);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(5, tapped.size());
        assertEquals("hello again", session.getEmulator().getScreen().getSelectedText(0, 0, COLUMNS - 1, 0).trim());
    }
}
