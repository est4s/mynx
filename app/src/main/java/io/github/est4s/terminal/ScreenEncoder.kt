package io.github.est4s.terminal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import io.github.est4s.terminal.core.VIDEO_CONFIG
import io.github.est4s.terminal.core.VIDEO_KEY
import kotlin.concurrent.thread

/**
 * A sent screen's H.264 encoder (spike #117 found what works):
 * MediaCodec fed by its input [surface], which a private display (a
 * sent app) or the X server (the desktop's part on the other phone)
 * draws into; the low-latency keys where the encoder takes them, an
 * I-frame every 2 s, a still screen repeated at 10 fps. Each part goes
 * to [encoded] (our [VIDEO_KEY] and [VIDEO_CONFIG] flags, its time in
 * µs, its bytes) from the encoder's own thread. [start] throws when the
 * encoder takes no format for the size.
 */
class ScreenEncoder private constructor(
    private val codec: MediaCodec,
    val surface: Surface,
    private val encoded: (Int, Long, ByteArray) -> Unit,
) {
    @Volatile private var running = true
    private val drain = thread(name = "mynx-screen-encode", isDaemon = true) { drain() }

    /** Asks for a key frame now. Any thread. */
    fun keyFrame() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Stops it; whatever drew into [surface] must have let go of it first. */
    fun stop() {
        running = false
        // Its dequeue waits at most 100 ms: then the encoder can go.
        runCatching { drain.join(300) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val data = ByteArray(info.size)
                codec.getOutputBuffer(index)?.let { buffer ->
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                }
                val flags = info.flags
                val pts = info.presentationTimeUs
                codec.releaseOutputBuffer(index, false)
                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                var ours = 0
                if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) ours = ours or VIDEO_KEY
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) ours = ours or VIDEO_CONFIG
                if (data.isNotEmpty()) encoded(ours, pts, data)
            }
        } catch (e: IllegalStateException) {
            // Stopped under us: the stream is going.
        }
    }

    companion object {
        /** A [width] x [height] encoder at [fps] and [kbps], started. */
        fun start(width: Int, height: Int, fps: Int, kbps: Int, encoded: (Int, Long, ByteArray) -> Unit): ScreenEncoder {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val cbr = runCatching {
                codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
                    .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }.getOrDefault(false)
            var configured = false
            for (lowLatency in listOf(true, false)) {
                try {
                    codec.configure(format(width, height, fps, kbps, lowLatency, cbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    configured = true
                    break
                } catch (e: Exception) {
                    codec.reset()
                }
            }
            if (!configured) {
                codec.release()
                error("it took no format for ${width}x$height")
            }
            val surface = try {
                codec.createInputSurface().also { codec.start() }
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            return ScreenEncoder(codec, surface, encoded)
        }

        private fun format(w: Int, h: Int, fps: Int, kbps: Int, lowLatency: Boolean, cbr: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // A still screen still sends 10 frames a second, so a lost one is soon made good.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (Build.VERSION.SDK_INT >= 29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                if (lowLatency) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                    if (Build.VERSION.SDK_INT >= 29) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    }
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                }
            }
    }
}
