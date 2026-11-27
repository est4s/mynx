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
import io.github.est4s.terminal.core.AlignGestures
import io.github.est4s.terminal.core.AlignStep
import io.github.est4s.terminal.core.DesktopRuler
import io.github.est4s.terminal.core.Finger
import io.github.est4s.terminal.core.ScreenAlignment
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ScreenSide
import io.github.est4s.terminal.core.ShownStream
import io.github.est4s.terminal.core.TouchPoint
import io.github.est4s.terminal.core.TouchTrack
import io.github.est4s.terminal.core.alignText
import io.github.est4s.terminal.core.streamFit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * On the screen (phone B): a stream the host sent, full screen, over
 * whatever the app showed (Back returns to it). The video, decoded
 * (MediaCodec, low-latency where the decoder takes it) onto a
 * SurfaceView as big as fits; touches go back in the video's pixels
 * ([TouchTrack]). A line around it while the host's typing comes here;
 * the host's desktop pointer over it while it's on this phone's part.
 * While the host aligns the desktop across both phones ([align]), a
 * ruler along the edge facing it and a panel (Cancel, Reset, Done) are
 * over the picture, and touches drag or pinch the picture instead
 * ([AlignGestures], sent as the host's AlignInput).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ScreenStreamView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun touch(touch: ScreenMessage.Touch)
        /** The decoder took [stream]'s part [pts]: the host may send more. Any thread. */
        fun decoded(stream: Int, pts: Long)
        /** The decoder starts over and needs a key frame. Any thread. */
        fun askKeyFrame(stream: Int)
        /** A drag or pinch while aligning, for the host. */
        fun alignInput(input: ScreenMessage.AlignInput) {}
        /** Done, Reset or Cancel while aligning, for the host. */
        fun alignAction(stream: Int, step: AlignStep) {}
    }

    private val video = SurfaceView(context)
    private val mark = Mark(context)
    private val arrow = PointerArrow(context)
    // The desktop's pointer, in the video's pixels (null: not on this stream).
    private var pointerAt: Pair<Float, Float>? = null
    private val track = TouchTrack()
    private val ruler = AlignRulerView(context).apply { visibility = View.GONE }
    private val alignPanel = AlignPanel(context, "Drag along the edge, or pinch, until the lines meet the other phone's.") { step ->
        stream?.let { host.alignAction(it.id, step) }
    }.apply { visibility = View.GONE }
    // Aligning: the host's last word, the ruler drawn (moved at once by a drag, before the host's answer), the gestures.
    private var aligning: ScreenMessage.Align? = null
    private var shownRuler: DesktopRuler? = null
    private var alignGestures: AlignGestures? = null
    private var fingers = 0

    /** Whether the host aligns the desktop here now. */
    val isAligning: Boolean get() = aligning != null

    @Volatile private var stream: ShownStream? = null
    @Volatile private var decoder: Decoder? = null
    @Volatile private var holder: SurfaceHolder? = null

    /** The stream shown, or null. */
    val shown: ShownStream? get() = stream

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(arrow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(mark, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ruler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val margin = Pixel.dp(context, 16)
        addView(alignPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(margin, margin, margin, margin)
        })
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
        pointerAt = null
        arrow.away()
        visibility = View.VISIBLE
        fitVideo()
        restart()
    }

    /** Stops showing it. */
    fun hide() {
        endAlign()
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

    /** The host's desktop pointer on [id] at [x], [y] of its video, or not on it ([shown] false). */
    fun pointer(id: Int, x: Float, y: Float, shown: Boolean) {
        if (stream?.id != id) return
        pointerAt = if (shown) Pair(x, y) else null
        drawPointer()
    }

    private fun drawPointer() {
        val shown = stream ?: return
        val at = pointerAt ?: return arrow.away()
        val fit = streamFit(width, height, shown.width, shown.height) ?: return arrow.away()
        arrow.at(fit.left + at.first * fit.scale, fit.top + at.second * fit.scale)
    }

    /** The host aligns the desktop across both phones on [message]'s stream: its ruler and values; off, it's over. */
    fun align(message: ScreenMessage.Align) {
        if (stream?.id != message.stream) return
        if (!message.on) return endAlign()
        aligning = message
        alignPanel.values = alignText(ScreenAlignment(message.offset, message.scale))
        // While a finger is down the ruler follows it; the host's answers catch up after.
        if (fingers == 0 || shownRuler == null) shownRuler = DesktopRuler(message.origin, message.step)
        if (alignGestures == null) alignGestures = AlignGestures(vertical(message.side))
        drawRuler()
    }

    private fun vertical(side: ScreenSide) = side == ScreenSide.LEFT || side == ScreenSide.RIGHT

    private fun endAlign() {
        aligning = null
        shownRuler = null
        alignGestures = null
        fingers = 0
        ruler.hide()
        alignPanel.visibility = View.GONE
    }

    private fun drawRuler() {
        val a = aligning ?: return
        val shown = stream ?: return
        val r = shownRuler ?: return
        val fit = streamFit(width, height, shown.width, shown.height) ?: return
        val edge = screenSeamEdge(a.side)
        val vertical = vertical(a.side)
        ruler.show(
            edge, r,
            start = if (vertical) fit.top else fit.left,
            scale = fit.scale,
            length = (if (vertical) shown.height else shown.width).toFloat(),
        )
        // Out of the ruler's way: at the top when the ruler is at the bottom.
        val gravity = if (edge == ScreenSide.BELOW) Gravity.TOP else Gravity.BOTTOM
        val params = alignPanel.layoutParams as LayoutParams
        if (params.gravity != gravity) {
            params.gravity = gravity
            alignPanel.layoutParams = params
        }
        alignPanel.visibility = View.VISIBLE
    }

    // A touch while aligning: the picture's, in the video's pixels.
    private fun alignTouch(event: MotionEvent, shown: ShownStream) {
        val g = alignGestures ?: return
        val fit = streamFit(width, height, shown.width, shown.height) ?: return
        fun finger(i: Int) = Finger(event.getPointerId(i), (event.getX(i) - fit.left) / fit.scale, (event.getY(i) - fit.top) / fit.scale)
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val f = finger(i)
                g.down(f.id, f.x, f.y)
                fingers = event.pointerCount
            }
            MotionEvent.ACTION_MOVE -> g.move((0 until event.pointerCount).map(::finger))?.let { m ->
                val input = m.copy(stream = shown.id)
                host.alignInput(input)
                shownRuler = shownRuler?.let { g.preview(it, input) }
                drawRuler()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                g.up(event.getPointerId(i))
                fingers = event.pointerCount - 1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                g.cancel()
                fingers = 0
            }
        }
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
        post {
            fitVideo()
            drawPointer()
            drawRuler()
        }
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
        if (aligning != null) {
            alignTouch(event, shown)
            return true
        }
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
