package io.github.est4s.terminal

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.est4s.terminal.core.Launch
import io.github.est4s.terminal.core.PipePlayer
import io.github.est4s.terminal.core.SOUND_RATE
import io.github.est4s.terminal.core.ServerRestarts
import io.github.est4s.terminal.core.SoundOut
import io.github.est4s.terminal.core.soundServerPid
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * The sound device: runs Debian's PulseAudio (`sound-server`, started by
 * [launch]) outside any tab and plays the [pipe] it writes on the phone.
 * The server's output goes to [log], its pid to [pidFile]. Call from
 * [handler]'s thread.
 */
class SoundDevice(
    private val launch: () -> Launch,
    private val workDir: File,
    private val pipe: File,
    private val log: File,
    private val pidFile: File,
    private val installed: () -> Boolean,
    private val handler: Handler,
) {
    private var run: Run? = null

    val running: Boolean get() = run != null

    /** Starts the server, or starts it again; null when it started, else why not. */
    fun start(): String? {
        if (!installed()) return "PulseAudio isn't installed (pocket sound install installs it)"
        stop()
        run = Run().also { it.start() }
        return null
    }

    fun stop() {
        run?.stop()
        run = null
    }

    private inner class Run {
        @Volatile private var stopped = false
        @Volatile private var process: Process? = null
        private val track = newTrack()
        private val player = PipePlayer(TrackOut(), SystemClock::elapsedRealtime)
        private val idleCheck = object : Runnable {
            override fun run() {
                if (stopped) return
                player.pauseIfIdle()
                if (player.playing && !stopped) handler.postDelayed(this, player.idleMs)
            }
        }

        fun start() {
            thread(name = "sound-server", isDaemon = true) { serve() }
            thread(name = "sound-pipe", isDaemon = true) {
                runCatching {
                    player.run(open = { if (pipe.exists()) FileInputStream(pipe) else null }, stopped = { stopped })
                }
                track.release()
            }
        }

        fun stop() {
            stopped = true
            handler.removeCallbacks(idleCheck)
            // Killing proot leaves Pulse running: stop Pulse itself.
            soundServerPid(pidFile)?.let { runCatching { Os.kill(it, OsConstants.SIGTERM) } }
            process?.destroyForcibly()
            // A reader waiting for the pipe to open would wait forever:
            // opening it to write lets it through, to the end of the pipe.
            runCatching {
                Os.close(Os.open(pipe.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0))
            }
        }

        private fun serve() {
            val restarts = ServerRestarts()
            while (!stopped) {
                val started = SystemClock.elapsedRealtime()
                val code = runCatching {
                    log.parentFile?.mkdirs()
                    val l = launch()
                    val builder = ProcessBuilder(l.argv).directory(workDir)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    builder.environment().putAll(l.env)
                    val p = builder.start()
                    process = p
                    if (stopped) p.destroyForcibly()
                    p.waitFor()
                }.getOrDefault(-1)
                if (stopped) break
                val delay = restarts.after(code, SystemClock.elapsedRealtime() - started) ?: break
                Thread.sleep(delay)
            }
        }

        private inner class TrackOut : SoundOut {
            override fun play() {
                track.play()
                handler.post {
                    handler.removeCallbacks(idleCheck)
                    if (!stopped) handler.postDelayed(idleCheck, player.idleMs)
                }
            }

            override fun pause() = track.pause()

            override fun write(buffer: ByteArray, length: Int) {
                track.write(buffer, 0, length)
            }
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SOUND_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SOUND_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
