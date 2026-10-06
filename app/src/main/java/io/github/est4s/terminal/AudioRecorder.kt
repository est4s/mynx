package io.github.est4s.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import io.github.est4s.terminal.core.RecordQuery
import io.github.est4s.terminal.core.RecordReport
import io.github.est4s.terminal.core.wavHeader
import java.io.RandomAccessFile
import io.github.est4s.terminal.core.AudioFormat as Format

/**
 * Answers `pc26 audio record`: MediaRecorder for compressed formats,
 * AudioRecord for WAV (raw PCM, written on a thread of its own). Android
 * only lets the app in use start recording, so [onScreen] must be true
 * to start, unless something is recording already (the service then
 * has the microphone foreground type). [askPermission] shows Android's
 * dialog, or returns false when it can't. [recordingChanged] says
 * whether anything is recording.
 */
class AudioRecorder(
    private val context: Context,
    private val handler: Handler,
    private val onScreen: () -> Boolean,
    private val askPermission: (Array<String>, (Boolean) -> Unit) -> Boolean,
    private val recordingChanged: (Boolean) -> Unit,
) {
    private val jobs = mutableSetOf<Job>()

    fun record(query: RecordQuery, report: RecordReport) {
        val request = Request(query, report)
        report.onStop { request.stop() }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            handler.post { request.start() }
            return
        }
        val asked = askPermission(arrayOf(Manifest.permission.RECORD_AUDIO)) { allowed ->
            if (allowed) handler.post { request.start() }
            else report.fail("the microphone wasn't allowed (allow it in the app's Android settings)")
        }
        if (!asked) report.fail("the app must be on screen the first time, so Android can ask to allow the microphone")
    }

    /** Ends every recording, saving what it has. */
    fun stopAll() {
        synchronized(jobs) { jobs.toList() }.forEach { it.stop() }
    }

    // `pc26` may stop before recording starts (while Android asks to
    // allow the microphone): then it never starts.
    private inner class Request(private val query: RecordQuery, private val report: RecordReport) {
        private var stopped = false
        private var job: Job? = null

        fun start() {
            if (!onScreen() && synchronized(jobs) { jobs.isEmpty() }) {
                return report.fail("the app must be on screen to start (Android only lets the app in use start recording)")
            }
            if (query.format == Format.OGG && Build.VERSION.SDK_INT < 29) {
                return report.fail("recording to .ogg needs Android 10 or newer")
            }
            val job = if (query.format == Format.WAV) WavJob(query, report) else CompressedJob(query, report)
            synchronized(this) {
                if (stopped) return
                this.job = job
            }
            synchronized(jobs) {
                jobs += job
                if (jobs.size == 1) recordingChanged(true)
            }
            try {
                job.start()
                report.started()
            } catch (e: Exception) {
                job.abandon()
                report.fail("couldn't record: ${e.message ?: "the microphone is busy"}")
            }
        }

        fun stop() {
            val job = synchronized(this) {
                stopped = true
                job
            }
            job?.stop()
        }
    }

    private abstract inner class Job(val query: RecordQuery, val report: RecordReport) {
        private var startedAt = 0L
        private var ended = false

        abstract fun begin()
        /** Stops recording; true if the file is complete. */
        abstract fun finish(): Boolean
        abstract fun release()

        fun start() {
            startedAt = SystemClock.elapsedRealtime()
            begin()
        }

        /** Ends the recording and reports it, once. */
        fun stop() {
            synchronized(this) {
                if (ended) return
                ended = true
            }
            val complete = runCatching { finish() }.getOrDefault(false)
            release()
            done()
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            if (complete) report.recorded(query.seconds?.toDouble()?.coerceAtMost(seconds) ?: seconds)
            else report.fail("nothing was recorded")
        }

        fun abandon() {
            synchronized(this) { ended = true }
            runCatching { release() }
            done()
        }

        private fun done() {
            synchronized(jobs) {
                jobs -= this
                if (jobs.isEmpty()) recordingChanged(false)
            }
        }
    }

    private inner class CompressedJob(query: RecordQuery, report: RecordReport) : Job(query, report) {
        @Suppress("DEPRECATION") // MediaRecorder(Context) is API 31+
        private val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

        override fun begin() {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            when (query.format) {
                Format.AAC -> {
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                Format.OGG -> {
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                }
                else -> {
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
            }
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(query.rate)
            recorder.setAudioEncodingBitRate(if (query.format == Format.OGG) 64_000 else 128_000)
            recorder.setOutputFile(query.partial.path)
            query.seconds?.let { seconds ->
                recorder.setMaxDuration(seconds * 1000)
                recorder.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) Thread { stop() }.start()
                }
            }
            recorder.prepare()
            recorder.start()
        }

        // stop() throws when nothing was recorded yet; after the maximum
        // length the recorder has stopped itself, and the file is complete.
        override fun finish(): Boolean = runCatching { recorder.stop() }.isSuccess || query.partial.length() > 0

        override fun release() = recorder.release()
    }

    private inner class WavJob(query: RecordQuery, report: RecordReport) : Job(query, report) {
        private val bufferBytes = AudioRecord.getMinBufferSize(
            query.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(query.rate / 5) * 2
        private lateinit var input: AudioRecord
        @Volatile private var running = true
        private var thread: Thread? = null
        private var dataBytes = 0L

        @SuppressLint("MissingPermission") // checked in record()
        override fun begin() {
            input = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, query.rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
                )
            } catch (e: SecurityException) {
                throw IllegalStateException("the microphone wasn't allowed")
            }
            if (input.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("the microphone can't record at ${query.rate} Hz")
            }
            input.startRecording()
            val file = RandomAccessFile(query.partial, "rw")
            file.setLength(0)
            file.write(wavHeader(query.rate, 1, 0))
            val limit = query.seconds?.let { it.toLong() * query.rate * 2 }
            thread = Thread {
                val buffer = ByteArray(bufferBytes)
                file.use {
                    while (running && (limit == null || dataBytes < limit)) {
                        val read = input.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        val keep = if (limit == null) read else minOf(read.toLong(), limit - dataBytes).toInt()
                        it.write(buffer, 0, keep)
                        dataBytes += keep
                    }
                    it.seek(0)
                    it.write(wavHeader(query.rate, 1, dataBytes.toInt()))
                }
                // Reached the length asked for: end it from here.
                if (running) Thread { stop() }.start()
            }.apply { start() }
        }

        override fun finish(): Boolean {
            running = false
            runCatching { input.stop() }
            if (Thread.currentThread() !== thread) thread?.join()
            return dataBytes > 0
        }

        override fun release() {
            if (::input.isInitialized) input.release()
        }
    }
}
