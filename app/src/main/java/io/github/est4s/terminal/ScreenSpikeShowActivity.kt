package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SPIKE (throwaway, never merged): phone B. Connects to
 * [ScreenSpikeHostActivity] on phone A, decodes the H.264 it sends onto a
 * SurfaceView, and sends its touches back. Delays are measured with A's
 * clock mapped to B's by ping and pong.
 */
class ScreenSpikeShowActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val log = SpikeLog()
    private val prefs by lazy { getSharedPreferences("screen-spike", Context.MODE_PRIVATE) }
    private var conn: Connection? = null
    private var streamRoot: FrameLayout? = null

    private sealed class Item {
        class Config(val width: Int, val height: Int, val csd: ByteArray) : Item()
        class Frame(val ptsA: Long, val data: ByteArray, val meta: Meta) : Item()
    }

    private class Meta(val recvB: Long, val touchSeq: Int, val touchTB: Long) {
        var queuedB = 0L
    }

    private inner class Connection(val host: String, val port: Int, val scale: Float, val fps: Int, val kbps: Int, val iframe: Int) {
        @Volatile var running = true
        var socket: Socket? = null
        var out: OutputStream? = null
        val writer = Executors.newSingleThreadExecutor()
        val queue = LinkedBlockingQueue<Item>()
        @Volatile var surface: SurfaceHolder? = null
        var decoder: MediaCodec? = null
        val metas = HashMap<Long, Meta>()

        // Clock: A's time = B's time + offset, from the ping with the shortest round trip lately.
        @Volatile var offset = 0L
        @Volatile var haveOffset = false
        val pings = ArrayDeque<Pair<Long, Long>>() // rtt, offset
        @Volatile var bestRtt = 0L
        var touchSeq = 0
        var lastShownTouch = 0
        val touchSent = HashMap<Int, Long>()

        @Volatile var recvBytes = 0L
        @Volatile var recvFrames = 0
        @Volatile var shownFrames = 0
        val netDelay = SpikeWindow()
        val glassDelay = SpikeWindow()
        val decodeDelay = SpikeWindow()
        val touchRound = SpikeWindow()
        @Volatile var aStats = "A: -"
        @Volatile var videoW = 0
        @Volatile var videoH = 0

        fun send(type: Int, payload: ByteArray) {
            val o = out ?: return
            if (!running) return
            writer.execute {
                try {
                    SpikeWire.write(o, type, payload)
                } catch (e: IOException) {
                    fail("Send failed: $e")
                }
            }
        }

        fun fail(why: String) {
            if (!running) return
            note(why)
            close()
        }

        fun close() {
            running = false
            writer.shutdown()
            thread(name = "spike-close") { runCatching { socket?.close() } }
        }

        fun toA(tB: Long) = tB + offset
        fun toB(tA: Long) = tA - offset
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        showForm()
    }

    @Deprecated("Replaced by OnBackInvokedDispatcher on API 33+")
    override fun onBackPressed() {
        back()
    }

    private fun back() {
        if (conn != null) {
            disconnect()
            showForm()
        } else {
            finish()
        }
    }

    override fun onStop() {
        // The surface goes with the screen; the spike doesn't recover, it just starts over.
        if (conn != null) {
            disconnect()
            showForm()
        }
        super.onStop()
    }

    private fun note(line: String) {
        log.add(line)
    }

    private fun showForm() {
        showBars()
        val ctx: Context = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Pixel.dp(ctx, 16), Pixel.dp(ctx, 16), Pixel.dp(ctx, 16), Pixel.dp(ctx, 16))
        }
        fun label(text: String, size: Float = 14f) = TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
        }
        fun field(key: String, default: String, hint: String, type: Int) = EditText(ctx).apply {
            setText(prefs.getString(key, default))
            this.hint = hint
            inputType = type
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        column.addView(label("Screen spike: show (phone B)", 20f).apply { setTextColor(Color.rgb(0, 220, 255)) })
        column.addView(label("Start \"Screen spike: host\" on phone A, then type the address it shows."))
        val address = field("address", "192.168.1.2:${SpikeWire.PORT}", "A's IP:port", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val scale = field("scale", "1.0", "scale of B's screen size (1.0, 0.75, 0.5)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val fps = field("fps", "60", "frames per second", InputType.TYPE_CLASS_NUMBER)
        val mbps = field("mbps", "8", "Mbit/s", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val iframe = field("iframe", "2", "I-frame every N seconds", InputType.TYPE_CLASS_NUMBER)
        column.addView(label("A's address (IP:port)", 12f)); column.addView(address)
        column.addView(label("Scale (B's screen size × this)", 12f)); column.addView(scale)
        column.addView(label("Frames per second", 12f)); column.addView(fps)
        column.addView(label("Bitrate, Mbit/s", 12f)); column.addView(mbps)
        column.addView(label("I-frame interval, seconds", 12f)); column.addView(iframe)
        column.addView(Button(ctx).apply {
            text = "Connect"
            setOnClickListener {
                val text = address.text.toString().trim()
                prefs.edit()
                    .putString("address", text).putString("scale", scale.text.toString())
                    .putString("fps", fps.text.toString()).putString("mbps", mbps.text.toString())
                    .putString("iframe", iframe.text.toString()).apply()
                val host = text.substringBeforeLast(':')
                val port = text.substringAfterLast(':', "").toIntOrNull() ?: SpikeWire.PORT
                connect(
                    Connection(
                        if (text.contains(':')) host else text, port,
                        scale.text.toString().toFloatOrNull()?.coerceIn(0.1f, 2f) ?: 1f,
                        fps.text.toString().toIntOrNull() ?: 60,
                        ((mbps.text.toString().toFloatOrNull() ?: 8f) * 1000).toInt(),
                        iframe.text.toString().toIntOrNull() ?: 2,
                    ),
                )
            }
        })
        column.addView(label("Back leaves the stream. The last stream's log:", 12f))
        column.addView(label(log.text(), 11f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.rgb(12, 12, 18))
            addView(column)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        streamRoot = null
        setContentView(scroll)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun showBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun connect(c: Connection) {
        conn = c
        log.add("Connecting to ${c.host}:${c.port}")
        hideBars()
        val ctx: Context = this
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val box = FrameLayout(ctx)
        val video = SurfaceView(ctx)
        val dots = LocalDots(ctx)
        box.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val stats = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.rgb(160, 255, 160))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(Pixel.dp(ctx, 6), Pixel.dp(ctx, 4), Pixel.dp(ctx, 6), Pixel.dp(ctx, 4))
        }
        root.addView(stats, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val toggle = Button(ctx).apply {
            text = "stats"
            isAllCaps = false
            alpha = 0.6f
            setOnClickListener { stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        root.addView(toggle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        streamRoot = root
        setContentView(root)

        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                c.surface = holder
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                c.surface = null
            }
        })

        dots.setOnTouchListener { v, ev ->
            sendTouch(c, v, ev)
            dots.show(ev)
            true
        }

        // After the bars have gone, the screen's size is what A draws at.
        root.postDelayed({
            if (conn !== c) return@postDelayed
            val w = (root.width * c.scale).toInt()
            val h = (root.height * c.scale).toInt()
            val dpi = (resources.displayMetrics.densityDpi * c.scale).toInt()
            note("B's screen ${root.width}x${root.height} at ${resources.displayMetrics.densityDpi} dpi; asking for ${w}x$h at $dpi dpi")
            thread(name = "spike-net") { talk(c, w, h, dpi) }
            thread(name = "spike-decode") { decode(c) }
            tick(c, stats, root, box)
        }, 500)
    }

    private fun sendTouch(c: Connection, v: View, ev: MotionEvent) {
        if (v.width == 0 || v.height == 0) return
        val seq = ++c.touchSeq
        val tB = SpikeWire.nowUs()
        c.touchSent[seq] = tB
        if (c.touchSent.size > 500) c.touchSent.remove(seq - 500)
        val masked = ev.actionMasked
        val payload = SpikeWire.build {
            writeInt(seq); writeLong(tB); writeInt(masked); writeInt(ev.actionIndex); writeInt(ev.pointerCount)
            for (i in 0 until ev.pointerCount) {
                writeInt(ev.getPointerId(i))
                writeFloat(ev.getX(i) / v.width)
                writeFloat(ev.getY(i) / v.height)
            }
        }
        c.send(SpikeWire.TOUCH, payload)
    }

    private fun disconnect() {
        val c = conn ?: return
        conn = null
        c.close()
        note("Disconnected")
    }

    private fun talk(c: Connection, w: Int, h: Int, dpi: Int) {
        try {
            val socket = Socket()
            c.socket = socket
            socket.tcpNoDelay = true
            runCatching { socket.receiveBufferSize = 1 shl 20 }
            socket.connect(InetSocketAddress(c.host, c.port), 5000)
            c.out = BufferedOutputStream(socket.getOutputStream(), 1 shl 14)
            note("Connected")
            c.send(SpikeWire.HELLO, SpikeWire.build {
                writeInt(w); writeInt(h); writeInt(dpi); writeInt(c.fps); writeInt(c.kbps); writeInt(c.iframe)
            })
            ping(c)
            val input = DataInputStream(socket.getInputStream().buffered(1 shl 16))
            while (c.running) {
                val msg = SpikeWire.read(input)
                val now = SpikeWire.nowUs()
                c.recvBytes += msg.payload.size + 5
                val p = DataInputStream(ByteArrayInputStream(msg.payload))
                when (msg.type) {
                    SpikeWire.CONFIG -> {
                        val vw = p.readInt()
                        val vh = p.readInt()
                        val csd = msg.payload.copyOfRange(8, msg.payload.size)
                        note("Config from A: ${vw}x$vh, ${csd.size} bytes")
                        c.queue.put(Item.Config(vw, vh, csd))
                    }
                    SpikeWire.FRAME -> {
                        val ptsA = p.readLong()
                        val sentA = p.readLong()
                        p.readInt() // flags
                        val touchSeq = p.readInt()
                        val touchTB = p.readLong()
                        val data = msg.payload.copyOfRange(32, msg.payload.size)
                        if (c.haveOffset) c.netDelay.add(now - c.toB(sentA))
                        c.recvFrames++
                        c.queue.put(Item.Frame(ptsA, data, Meta(now, touchSeq, touchTB)))
                    }
                    SpikeWire.PONG -> {
                        val tB0 = p.readLong()
                        val tA = p.readLong()
                        val rtt = now - tB0
                        synchronized(c.pings) {
                            c.pings.addLast(rtt to (tA - (tB0 + now) / 2))
                            while (c.pings.size > 20) c.pings.removeFirst()
                            val best = c.pings.minBy { it.first }
                            c.bestRtt = best.first
                            c.offset = best.second
                            c.haveOffset = true
                        }
                    }
                    SpikeWire.STATS_A -> c.aStats = String(msg.payload, Charsets.UTF_8)
                    SpikeWire.LOG -> note("A: " + String(msg.payload, Charsets.UTF_8))
                    else -> note("Unknown message ${msg.type}")
                }
            }
        } catch (e: IOException) {
            c.fail("Connection: $e")
        } catch (e: Throwable) {
            c.fail("Network thread crashed: ${e.stackTraceToString().take(800)}")
        }
    }

    private fun ping(c: Connection) {
        if (!c.running) return
        c.send(SpikeWire.PING, SpikeWire.build { writeLong(SpikeWire.nowUs()) })
        main.postDelayed({ ping(c) }, 500)
    }

    private fun decode(c: Connection) {
        val info = MediaCodec.BufferInfo()
        var pending: Item.Config? = null
        var dropped = 0
        try {
            while (c.running) {
                val item = c.queue.poll(4, TimeUnit.MILLISECONDS)
                if (item is Item.Config) pending = item
                val config = pending
                if (config != null) {
                    val holder = c.surface
                    if (holder != null && holder.surface.isValid) {
                        pending = null
                        startDecoder(c, config, holder)
                    }
                }
                if (item is Item.Frame) {
                    val dec = c.decoder
                    if (dec == null) {
                        if (dropped++ % 30 == 0) note("Frames before the decoder: dropped ($dropped)")
                    } else {
                        var index = -1
                        while (c.running && index < 0) {
                            index = dec.dequeueInputBuffer(10_000)
                            if (index < 0) drainOutput(c, dec, info)
                        }
                        if (index >= 0) {
                            val buffer = dec.getInputBuffer(index)!!
                            buffer.clear()
                            buffer.put(item.data)
                            item.meta.queuedB = SpikeWire.nowUs()
                            synchronized(c.metas) { c.metas[item.ptsA] = item.meta }
                            dec.queueInputBuffer(index, 0, item.data.size, item.ptsA, 0)
                        }
                    }
                }
                c.decoder?.let { drainOutput(c, it, info) }
            }
        } catch (e: Throwable) {
            if (c.running) c.fail("Decoder: ${e.stackTraceToString().take(800)}")
        }
        runCatching { c.decoder?.stop() }
        runCatching { c.decoder?.release() }
        c.decoder = null
    }

    private fun startDecoder(c: Connection, config: Item.Config, holder: SurfaceHolder) {
        runCatching { c.decoder?.stop() }
        runCatching { c.decoder?.release() }
        c.decoder = null
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var done = false
        for (lowLatency in listOf(true, false)) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height)
            if (lowLatency) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            try {
                dec.configure(format, holder.surface, null, 0)
                done = true
                val lowSupported = if (Build.VERSION.SDK_INT >= 30) {
                    dec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                } else false
                note("Decoder ${dec.name} (${if (lowLatency) "low-latency keys" else "plain"}; FEATURE_LowLatency: $lowSupported)")
                break
            } catch (e: Exception) {
                note("Decoder configure failed (${if (lowLatency) "low-latency" else "plain"}): $e")
                dec.reset()
            }
        }
        if (!done) throw IllegalStateException("The decoder took no format")
        dec.start()
        c.decoder = dec
        c.videoW = config.width
        c.videoH = config.height
        // The SPS and PPS first.
        val index = dec.dequeueInputBuffer(1_000_000)
        if (index >= 0) {
            val buffer = dec.getInputBuffer(index)!!
            buffer.clear()
            buffer.put(config.csd)
            dec.queueInputBuffer(index, 0, config.csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        } else {
            note("No decoder input buffer for the config")
        }
        main.post { fitVideo(c) }
    }

    private fun drainOutput(c: Connection, dec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = dec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val now = SpikeWire.nowUs()
                    val meta = synchronized(c.metas) { c.metas.remove(info.presentationTimeUs) }
                    dec.releaseOutputBuffer(index, true)
                    c.shownFrames++
                    if (meta != null) {
                        c.decodeDelay.add(now - meta.queuedB)
                        if (c.haveOffset) c.glassDelay.add(now - c.toB(info.presentationTimeUs))
                        if (meta.touchSeq > c.lastShownTouch) {
                            c.lastShownTouch = meta.touchSeq
                            if (meta.touchTB > 0) c.touchRound.add(now - meta.touchTB)
                        }
                    }
                    synchronized(c.metas) { if (c.metas.size > 300) c.metas.clear() }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> note("Decoder output: ${dec.outputFormat}")
                else -> return
            }
        }
    }

    private fun fitVideo(c: Connection) {
        val root = streamRoot ?: return
        val box = root.getChildAt(0)
        if (c.videoW == 0 || root.width == 0) return
        val s = minOf(root.width / c.videoW.toFloat(), root.height / c.videoH.toFloat())
        box.layoutParams = FrameLayout.LayoutParams((c.videoW * s).toInt(), (c.videoH * s).toInt(), Gravity.CENTER)
    }

    private fun tick(c: Connection, stats: TextView, root: View, box: View) {
        if (conn !== c) return
        val bytes = c.recvBytes; c.recvBytes = 0
        val recv = c.recvFrames; c.recvFrames = 0
        val shown = c.shownFrames; c.shownFrames = 0
        val b = "B: ${root.width}x${root.height} shows ${c.videoW}x${c.videoH} in ${box.width}x${box.height}\n" +
            "B: got $recv fps, shown $shown fps, %.2f Mbit/s, queue ${c.queue.size}\n".format(bytes * 8 / 1e6) +
            "B: clock ping ${c.bestRtt / 1000.0} ms (best of 20)${if (c.haveOffset) "" else " - no clock yet"}\n" +
            "B: A sent→B got ms ${c.netDelay.takeMs()}\n" +
            "B: decode ms ${c.decodeDelay.takeMs()}\n" +
            "B: A drew→B shown ms ${c.glassDelay.takeMs()}\n" +
            "B: touch→A drew→B shown ms ${c.touchRound.takeMs()}"
        c.send(SpikeWire.STATS_B, SpikeWire.text(b))
        stats.text = b + "\n" + c.aStats + "\n" + log.text().lines().take(8).joinToString("\n")
        if (!c.running) {
            stats.text = "Stopped. Back for the form.\n" + stats.text
            return
        }
        main.postDelayed({ tick(c, stats, root, box) }, 1000)
    }

    /** B's own fingers, drawn where they are: compare with A's red dots in the video. */
    private class LocalDots(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 255, 60)
            style = Paint.Style.STROKE
            strokeWidth = Pixel.dp(context, 3).toFloat()
        }
        private val points = ArrayList<Float>()

        fun show(ev: MotionEvent) {
            points.clear()
            val up = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
            if (!up) {
                for (i in 0 until ev.pointerCount) {
                    if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP && i == ev.actionIndex) continue
                    points.add(ev.getX(i))
                    points.add(ev.getY(i))
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            var i = 0
            while (i + 1 < points.size) {
                canvas.drawCircle(points[i], points[i + 1], Pixel.dp(context, 30).toFloat(), paint)
                i += 2
            }
        }
    }
}
