package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ShownStream
import io.github.est4s.terminal.core.TouchPoint
import io.github.est4s.terminal.core.TouchTrack
import io.github.est4s.terminal.core.streamFit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * On the screen (phone B): a stream the host sent, full screen, over
 * whatever the app showed (Back returns to it). The video, decoded
 * (MediaCodec, low-latency where the decoder takes it) onto a
 * SurfaceView as big as fits; touches go back in the video's pixels
 * ([TouchTrack]). A line around it while the host's typing comes here.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ScreenStreamView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun touch(touch: ScreenMessage.Touch)
        /** The decoder took [stream]'s part [pts]: the host may send more. Any thread. */
        fun decoded(stream: Int, pts: Long)
        /** The decoder starts over and needs a key frame. Any thread. */
        fun askKeyFrame(stream: Int)
    }

    private val video = SurfaceView(context)
    private val mark = Mark(context)
    private val track = TouchTrack()

    @Volatile private var stream: ShownStream? = null
    @Volatile private var decoder: Decoder? = null
    @Volatile private var holder: SurfaceHolder? = null

    /** The stream shown, or null. */
    val shown: ShownStream? get() = stream

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(mark, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
        isClickable = true
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                holder = h
                restart()
            }

            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                holder = null
                decoder?.stop()
                decoder = null
            }
        })
    }

    /** Shows [shown] (from its next key frame). */
    fun show(shown: ShownStream) {
        stream = shown
        mark.on = false
        visibility = View.VISIBLE
        fitVideo()
        restart()
    }

    /** Stops showing it. */
    fun hide() {
        stream = null
        decoder?.stop()
        decoder = null
        visibility = View.GONE
    }

    /** A part of the video, on the link's thread. */
    fun video(message: ScreenMessage.Video) {
        val shown = stream ?: return
        if (message.stream != shown.id) return
        decoder?.offer(message)
    }

    /** Whether the host's typing comes to this screen. */
    fun focus(typing: Boolean) {
        mark.on = typing
    }

    private fun restart() {
        val shown = stream ?: return
        val surface = holder ?: return
        decoder?.stop()
        decoder = Decoder(shown, surface).also { it.start() }
        host.askKeyFrame(shown.id)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fitVideo() }
    }

    private fun fitVideo() {
        val shown = stream ?: return
        val fit = streamFit(width, height, shown.width, shown.height) ?: return
        val w = (shown.width * fit.scale).toInt()
        val h = (shown.height * fit.scale).toInt()
        val params = video.layoutParams as LayoutParams
        if (params.width == w && params.height == h) return
        video.layoutParams = LayoutParams(w, h, Gravity.CENTER)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val shown = stream ?: return true
        val fit = streamFit(width, height, shown.width, shown.height) ?: return true
        val points = (0 until event.pointerCount).map { TouchPoint(event.getPointerId(it), event.getX(it), event.getY(it)) }
        track.map(shown.id, event.actionMasked, event.actionIndex, points, fit, shown.width, shown.height)?.let(host::touch)
        return true
    }

    /** One stream's decoder on its own thread: parts queue, each queued to MediaCodec as it comes, shown at once. */
    private inner class Decoder(private val shown: ShownStream, private val surface: SurfaceHolder) {
        private val queue = LinkedBlockingQueue<ScreenMessage.Video>()
        @Volatile private var running = true
        private var codec: MediaCodec? = null
        private var config: ByteArray? = null
        private var asked = 0L

        fun start() {
            thread(name = "mynx-screen-decode", isDaemon = true) { run() }
        }

        fun offer(m: ScreenMessage.Video) {
            if (running) queue.offer(m)
        }

        fun stop() {
            running = false
        }

        private fun run() {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val m = queue.poll(4, TimeUnit.MILLISECONDS)
                    if (m != null) take(m)
                    codec?.let { drain(it, info) }
                }
            } catch (e: Exception) {
                // The surface went, or the codec broke: a new one starts with the next show.
            }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }

        private fun take(m: ScreenMessage.Video) {
            if (m.config) {
                config = m.data
                host.decoded(shown.id, m.pts)
                return
            }
            var dec = codec
            if (dec == null) {
                if (!m.key) {
                    // Not a key frame: it can't start a decoder; ask again now and then.
                    val now = System.currentTimeMillis()
                    if (now - asked > 1000) {
                        asked = now
                        host.askKeyFrame(shown.id)
                    }
                    host.decoded(shown.id, m.pts)
                    return
                }
                dec = startCodec() ?: return
            }
            var index = -1
            while (running && index < 0) index = dec.dequeueInputBuffer(10_000)
            if (index < 0) return
            val buffer = dec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(m.data)
            dec.queueInputBuffer(index, 0, m.data.size, m.pts, 0)
            host.decoded(shown.id, m.pts)
        }

        private fun startCodec(): MediaCodec? {
            val surface = surface.surface?.takeIf { it.isValid } ?: return null
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            var done = false
            for (lowLatency in listOf(true, false)) {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, shown.width, shown.height)
                if (lowLatency) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                try {
                    dec.configure(format, surface, null, 0)
                    done = true
                    break
                } catch (e: Exception) {
                    dec.reset()
                }
            }
            if (!done) {
                dec.release()
                return null
            }
            dec.start()
            codec = dec
            // H.264's SPS and PPS first, when they came apart from the key frame.
            config?.let { csd ->
                val index = dec.dequeueInputBuffer(100_000)
                if (index >= 0) {
                    dec.getInputBuffer(index)?.let { b ->
                        b.clear()
                        b.put(csd)
                        dec.queueInputBuffer(index, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    }
                }
            }
            return dec
        }

        private fun drain(dec: MediaCodec, info: MediaCodec.BufferInfo) {
            while (true) {
                val index = dec.dequeueOutputBuffer(info, 0)
                if (index >= 0) dec.releaseOutputBuffer(index, true) else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
            }
        }
    }

    /** The line around the screen while typing on the host comes here. */
    private class Mark(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Pixel.CYAN
            style = Paint.Style.STROKE
            strokeWidth = Pixel.dp(context, 3).toFloat()
        }

        var on = false
            set(value) {
                field = value
                postInvalidate()
            }

        override fun onDraw(canvas: Canvas) {
            if (!on) return
            val half = paint.strokeWidth / 2
            canvas.drawRect(half, half, width - half, height - half, paint)
        }
    }
}
