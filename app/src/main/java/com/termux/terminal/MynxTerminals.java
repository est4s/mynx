package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * What the extended screen needs of Termux's terminal library that it
 * doesn't offer (v0.118.3; docs/ARCHITECTURE.md, "Extended screen:
 * terminals on B"). In the library's own package for its package-private
 * fields; the few private ones by reflection, each failing softly.
 * <p>
 * On the host: {@link #tee} gives a session, before it starts, a handler
 * of ours in place of its own: the same as the library's (its output into
 * the emulator, its end), but it also hands each part of the output to a
 * {@link Tap} first, so a sent tab's bytes can go to the other phone.
 * <p>
 * On the screen: {@link #remote} makes a session with no process for a
 * terminal the host sent: its emulator is fed the host's bytes
 * ({@link #feed}); what's typed into it (the terminal view, the key bar)
 * and its mouse reports and pastes go to an {@link Input}, while its
 * answers to programs' questions stay here (the host's emulator answers).
 * All on the main thread but the input, which comes on a thread of its own.
 */
public final class MynxTerminals {
    private MynxTerminals() {}

    /** Gets a sent session's output, on the main thread, before its emulator does. */
    public interface Tap {
        void output(byte[] data);
    }

    /** Gets what's typed into a remote session (and its mouse reports and pastes). */
    public interface Input {
        void typed(byte[] data);

        /** Whether its emulator's own output goes too: a mouse report or a paste does, an answer doesn't. */
        boolean sends(byte[] data);
    }

    // The library's MainThreadHandler's messages (private there).
    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    /** The library's MainThreadHandler, with a tap. */
    @SuppressLint("HandlerLeak")
    private static final class TeeHandler extends Handler {
        private final TerminalSession session;
        private final byte[] buffer = new byte[4 * 1024];
        volatile Tap tap;

        TeeHandler(TerminalSession session) {
            super(Looper.getMainLooper());
            this.session = session;
        }

        @Override
        public void handleMessage(Message msg) {
            int read = session.mProcessToTerminalIOQueue.read(buffer, false);
            if (read > 0) {
                give(buffer, read);
                session.mEmulator.append(buffer, read);
                session.notifyScreenUpdate();
            }
            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                session.cleanupResources(exitCode);
                String exit = "\r\n[Process completed";
                if (exitCode > 0) {
                    exit += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    exit += " (signal " + (-exitCode) + ")";
                }
                exit += " - press Enter]";
                byte[] bytes = exit.getBytes(StandardCharsets.UTF_8);
                give(bytes, bytes.length);
                session.mEmulator.append(bytes, bytes.length);
                session.notifyScreenUpdate();
                session.mClient.onSessionFinished(session);
            }
        }

        private void give(byte[] data, int length) {
            Tap t = tap;
            if (t == null) return;
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            try {
                t.output(copy);
            } catch (RuntimeException e) {
                // The other phone's trouble mustn't stop this terminal.
            }
        }
    }

    /**
     * Puts our handler in [session], which must not have started (its
     * threads read the field from the start). False if it couldn't: the
     * session works as before, but can't be sent.
     */
    public static boolean tee(TerminalSession session) {
        try {
            Field field = TerminalSession.class.getDeclaredField("mMainThreadHandler");
            field.setAccessible(true);
            field.set(session, new TeeHandler(session));
            return field.get(session) instanceof TeeHandler;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Sets (or, null, clears) [session]'s tap; false if it has no handler of ours. */
    public static boolean setTap(TerminalSession session, Tap tap) {
        if (!(session.mMainThreadHandler instanceof TeeHandler)) return false;
        ((TeeHandler) session.mMainThreadHandler).tap = tap;
        return true;
    }

    /** The emulator's output on the screen: answers stay, typing (mouse, paste) goes; the rest to the session. */
    private static final class RemoteOutput extends TerminalOutput {
        private final TerminalSession session;
        private final Input input;

        RemoteOutput(TerminalSession session, Input input) {
            this.session = session;
            this.input = input;
        }

        @Override
        public void write(byte[] data, int offset, int count) {
            byte[] copy = new byte[count];
            System.arraycopy(data, offset, copy, 0, count);
            if (input.sends(copy)) input.typed(copy);
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
            session.titleChanged(oldTitle, newTitle);
        }

        // OSC 52 from the host's programs: the host's emulator copies, there.
        @Override
        public void onCopyTextToClipboard(String text) {}

        @Override
        public void onPasteTextFromClipboard() {
            session.onPasteTextFromClipboard();
        }

        @Override
        public void onBell() {
            session.onBell();
        }

        @Override
        public void onColorsChanged() {
            session.onColorsChanged();
        }
    }

    // A pid no process has (Linux's pid_max is at most 2^22): write() takes input, and a kill finds nothing.
    private static final int NO_PROCESS = Integer.MAX_VALUE;

    /**
     * A session with no process, [columns] x [rows], for a terminal the
     * host sent. What's typed into it goes to [input], from a thread of
     * its own. Main thread.
     */
    public static TerminalSession remote(TerminalSessionClient client, int columns, int rows, int transcriptRows, Input input) {
        final TerminalSession session = new TerminalSession("/system/bin/false", "/", new String[]{"false"}, new String[0], transcriptRows, client);
        session.mEmulator = new TerminalEmulator(new RemoteOutput(session, input), Math.max(columns, 4), Math.max(rows, 4), 10, 20, transcriptRows, client);
        try {
            Field fd = TerminalSession.class.getDeclaredField("mTerminalFileDescriptor");
            fd.setAccessible(true);
            // updateSize sets the size of this fd's pty: none.
            fd.setInt(session, -1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Then it's fd 0, the app's /dev/null: the ioctl fails, harmlessly.
        }
        synchronized (session) {
            session.mShellPid = NO_PROCESS;
        }
        Thread reader = new Thread("mynx-remote-input") {
            @Override
            public void run() {
                byte[] buffer = new byte[4096];
                while (true) {
                    int read = session.mTerminalToProcessIOQueue.read(buffer, true);
                    if (read == -1) return;
                    if (read == 0) continue;
                    byte[] copy = new byte[read];
                    System.arraycopy(buffer, 0, copy, 0, read);
                    try {
                        input.typed(copy);
                    } catch (RuntimeException e) {
                        // The link went: what's typed goes nowhere.
                    }
                }
            }
        };
        reader.setDaemon(true);
        reader.start();
        return session;
    }

    /** Feeds a remote session the host's bytes. Main thread. */
    public static void feed(TerminalSession session, byte[] data) {
        TerminalEmulator emulator = session.mEmulator;
        if (emulator == null || data.length == 0) return;
        emulator.append(data, data.length);
        session.notifyScreenUpdate();
    }

    /** Makes a remote session's emulator [columns] x [rows], as the host's is, before a state is fed. Main thread. */
    public static void resize(TerminalSession session, int columns, int rows) {
        TerminalEmulator emulator = session.mEmulator;
        if (emulator == null || columns < 2 || rows < 2) return;
        if (emulator.mColumns == columns && emulator.mRows == rows) return;
        emulator.resize(columns, rows, intField(emulator, "mCellWidthPixels", 10), intField(emulator, "mCellHeightPixels", 20));
    }

    /** A remote session is done: nothing more is typed into it. */
    public static void endRemote(TerminalSession session) {
        synchronized (session) {
            session.mShellPid = -1;
        }
        session.mTerminalToProcessIOQueue.close();
        session.mProcessToTerminalIOQueue.close();
    }

    /** Whether [session] is a remote one ([remote]). */
    public static boolean isRemote(TerminalSession session) {
        return session.mEmulator != null && session.mShellPid == NO_PROCESS;
    }

    private static Object field(Object of, String name) throws ReflectiveOperationException {
        Field field = of.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(of);
    }

    private static int intField(Object of, String name, int otherwise) {
        try {
            return (Integer) field(of, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return otherwise;
        }
    }

    /**
     * The emulator's state the screen needs to go on from a snapshot:
     * its DECSET flags, scrolling margins (top, bottom, left, right) and
     * the style programs write in (foreground, background, effects).
     * What reflection can't read falls back to a reset terminal's.
     */
    public static int[] modes(TerminalEmulator emulator) {
        int flags = intField(emulator, "mCurrentDecSetFlags", (1 << 3) | (1 << 4));
        return new int[]{
            flags,
            intField(emulator, "mTopMargin", 0),
            intField(emulator, "mBottomMargin", emulator.mRows),
            intField(emulator, "mLeftMargin", 0),
            intField(emulator, "mRightMargin", emulator.mColumns),
            emulator.mForeColor,
            emulator.mBackColor,
            emulator.mEffect,
        };
    }

    /** The main buffer (with the scrollback) while a program has the alternate screen; null if it can't be read. */
    public static TerminalBuffer mainBuffer(TerminalEmulator emulator) {
        try {
            return (TerminalBuffer) field(emulator, "mMainBuffer");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
