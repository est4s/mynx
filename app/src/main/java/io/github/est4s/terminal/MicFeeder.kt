package io.github.est4s.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.FileObserver
import android.os.Handler
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.est4s.terminal.core.MIC_CHUNK
import io.github.est4s.terminal.core.Mic
import io.github.est4s.terminal.core.SOUND_INPUTS_NAME
import io.github.est4s.terminal.core.SOUND_RATE
import io.github.est4s.terminal.core.Silence
import io.github.est4s.terminal.core.micState
import io.github.est4s.terminal.core.micUsers
import java.io.File
import java.io.FileDescriptor
import kotlin.concurrent.thread

/**
 * The sound device's microphone: `sound-watch` lists in [dir] who
 * records from Pulse, and only while someone does, the phone's
 * microphone goes into the pipe `in` (or zeros, when it can't: see
 * [micState]). [allowed] is the `android-microphone` setting;
 * [changed] gets each new state, before the microphone opens, so the
 * service can add the microphone foreground type and name who listens.
 * Call from [handler]'s thread.
 */
class MicFeeder(
    private val context: Context,
    private val handler: Handler,
    private val dir: File,
    private val allowed: () -> Boolean,
    private val onScreen: () -> Boolean,
    private val askPermission: (Array<String>, (Boolean) -> Unit) -> Boolean,
    private val changed: (Mic) -> Unit,
) {
    private var watcher: FileObserver? = null
    private var feed: Feed? = null
    private var state: Mic = Mic.Off
    // Asked once while programs keep recording: a refusal isn't asked again.
    private var asked = false

    @Suppress("DEPRECATION") // the File constructor needs API 29
    fun start() {
        if (watcher != null) return
        dir.mkdirs()
        watcher = object : FileObserver(dir.path, MOVED_TO or CLOSE_WRITE or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == SOUND_INPUTS_NAME) handler.post { update() }
            }
        }.also { it.startWatching() }
        update()
    }

    fun stop() {
        watcher?.stopWatching()
        watcher = null
        set(Mic.Off)
    }

    /** Decides again: after a recording starts or stops, the settings change or the app comes on screen. */
    fun update() {
        if (watcher == null) return
        val users = runCatching { micUsers(File(dir, SOUND_INPUTS_NAME).readText()) }.getOrDefault(emptyList())
        if (users.isEmpty()) asked = false
        val permitted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (users.isNotEmpty() && allowed() && !permitted && !asked) {
            asked = askPermission(arrayOf(Manifest.permission.RECORD_AUDIO)) { handler.post { update() } }
        }
        set(micState(users, allowed(), permitted, canStart = onScreen() || state is Mic.On))
    }

    private fun set(next: Mic) {
        val was = state
        state = next
        if (next != was) changed(next)
        // Only a change between microphone, silence and nothing restarts the feed.
        if (next.javaClass == was.javaClass && (next is Mic.Off || feed != null)) return
        feed?.stop()
        feed = when (next) {
            Mic.Off -> null
            is Mic.On -> Feed(File(dir, "in"), live = true)
            is Mic.Silent -> Feed(File(dir, "in"), live = false)
        }?.also { it.start() }
    }

    /** Writes the microphone, or silence, into the pipe on a thread of its own. */
    private inner class Feed(private val pipe: File, private val live: Boolean) {
        @Volatile private var stopped = false
        private var thread: Thread? = null

        fun start() {
            thread = thread(name = "sound-mic", isDaemon = true) {
                val record = if (live) runCatching { newRecord() }.getOrNull() else null
                val silence = Silence(SystemClock::elapsedRealtime)
                val buffer = ByteArray(MIC_CHUNK)
                var out: FileDescriptor? = null
                try {
                    record?.startRecording()
                    while (!stopped) {
                        val read = record?.read(buffer, 0, MIC_CHUNK) ?: silence.read(buffer)
                        if (read <= 0) break
                        out = out ?: open() ?: continue
                        if (!write(out, buffer, read)) {
                            runCatching { Os.close(out) }
                            out = null
                        }
                    }
                } finally {
                    out?.let { runCatching { Os.close(it) } }
                    record?.let { runCatching { it.stop() }; it.release() }
                }
            }
        }

        fun stop() {
            stopped = true
        }

        // Non-blocking: Pulse reads nothing while it suspends the source,
        // and a blocked write would never notice the feed stopping. Null
        // while Pulse hasn't made the pipe (or isn't reading it).
        private fun open(): FileDescriptor? = try {
            Os.open(pipe.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
        } catch (e: ErrnoException) {
            Thread.sleep(200)
            null
        }

        // A full pipe drops the chunk; false once Pulse has gone.
        private fun write(out: FileDescriptor, buffer: ByteArray, length: Int): Boolean {
            var done = 0
            while (done < length) {
                done += try {
                    Os.write(out, buffer, done, length - done)
                } catch (e: ErrnoException) {
                    return e.errno == OsConstants.EAGAIN
                }
            }
            return true
        }
    }

    @SuppressLint("MissingPermission") // checked in update()
    private fun newRecord(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SOUND_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC, SOUND_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, MIC_CHUNK * 4),
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) {
                it.release()
                throw IllegalStateException("the microphone can't record at $SOUND_RATE Hz")
            }
        }
    }
}
