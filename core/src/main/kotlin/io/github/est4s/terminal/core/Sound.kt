package io.github.est4s.terminal.core

import java.io.File
import java.io.InputStream

/** Where Debian's sound server (PulseAudio) and the app meet, in Debian's /tmp. */
const val SOUND_DIR = "/tmp/.mynx/sound"
/** The server's socket: programs find it through PULSE_SERVER. */
const val SOUND_SOCKET = "$SOUND_DIR/native"
/** The pipe the server plays into and the app reads: s16le, [SOUND_RATE] Hz, stereo. */
const val SOUND_OUT = "$SOUND_DIR/out"
const val SOUND_RATE = 48000
/** The server's pid, written by [SOUND_SERVER]: a Pulse outlives the proot that ran it. */
const val SOUND_PID = "$SOUND_DIR/pid"
/** The app's script that runs the server, in Debian. */
const val SOUND_SERVER = "$TOOLS_MOUNT/lib/sound-server"
/** The script's exit code when PulseAudio isn't installed in Debian. */
const val SOUND_NOT_INSTALLED = 3

private const val FRAME = 4
// 20 ms: the pipe has no clock, so the AudioTrack's blocking writes set
// the pace, and small writes keep that even.
private const val CHUNK = SOUND_RATE / 50 * FRAME
private const val PIPE_WAIT_MS = 1000L

/** The phone's speaker, as [PipePlayer] uses it. */
interface SoundOut {
    fun play()
    fun pause()
    /** Blocks until [length] bytes, whole frames, are queued. */
    fun write(buffer: ByteArray, length: Int)
}

/**
 * Plays the server's pipe on [out]. The server writes nothing while it's
 * quiet, so the app calls [pauseIfIdle] every [idleMs] while [playing]:
 * a paused track lets the phone's audio sleep.
 */
class PipePlayer(private val out: SoundOut, private val now: () -> Long, val idleMs: Long = 500) {
    @Volatile var playing = false
        private set
    @Volatile private var lastSound = 0L

    /**
     * Opens the pipe with [open] (null while the server hasn't made it),
     * plays it until it ends, and again: the server deletes and makes it
     * anew each time it starts. Returns once [stopped].
     */
    fun run(open: () -> InputStream?, stopped: () -> Boolean, sleep: (Long) -> Unit = Thread::sleep) {
        while (!stopped()) {
            val input = open()
            if (input == null) sleep(PIPE_WAIT_MS) else input.use { play(it) }
        }
    }

    /** Plays [input] until it ends. */
    fun play(input: InputStream) {
        val buffer = ByteArray(CHUNK + FRAME)
        var held = 0
        while (true) {
            val read = input.read(buffer, held, CHUNK)
            if (read < 0) break
            held += read
            val whole = held - held % FRAME
            if (whole == 0) continue
            start()
            out.write(buffer, whole)
            lastSound = now()
            System.arraycopy(buffer, whole, buffer, 0, held - whole)
            held -= whole
        }
        stop()
    }

    fun pauseIfIdle() {
        if (playing && now() - lastSound >= idleMs) stop()
    }

    @Synchronized private fun start() {
        if (playing) return
        playing = true
        lastSound = now()
        out.play()
    }

    @Synchronized private fun stop() {
        if (!playing) return
        playing = false
        out.pause()
    }
}

/** How long to wait before starting the sound server again after it stopped. */
class ServerRestarts {
    private var failures = 0

    /** Null: don't. A server that ran a while starts the count over. */
    fun after(exitCode: Int, ranMs: Long): Long? {
        if (exitCode == SOUND_NOT_INSTALLED) return null
        if (ranMs >= 30_000) failures = 0
        return (1000L shl failures.coerceAtMost(6)).coerceAtMost(60_000).also { failures++ }
    }
}

/**
 * The sound server's pid from its [pidFile], while that process is still
 * PulseAudio (in [proc]), so a pid taken over by another program is left
 * alone. Proot doesn't hide pids: Debian's are Android's.
 */
fun soundServerPid(pidFile: File, proc: File = File("/proc")): Int? {
    val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return null
    val comm = runCatching { File(proc, "$pid/comm").readText().trim() }.getOrNull()
    return pid.takeIf { comm == "pulseaudio" }
}
