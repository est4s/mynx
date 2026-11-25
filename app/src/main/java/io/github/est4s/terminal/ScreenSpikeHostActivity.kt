package io.github.est4s.terminal

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * SPIKE (throwaway, never merged): phone A. Draws a mynx screen on a
 * VirtualDisplay of its own (a Presentation), encodes it to H.264 and sends
 * it over TCP to the phone running [ScreenSpikeShowActivity], injecting the
 * touches that phone sends back. Everything that happens is on screen.
 */
class ScreenSpikeHostActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val log = SpikeLog()
    private lateinit var logText: TextView
    private lateinit var statsText: TextView
    private lateinit var probeText: TextView
    private var server: ServerSocket? = null
    @Volatile private var session: Session? = null
    private var stream: Stream? = null
    @Volatile private var bStats = "-"
    private var probeSummary = ""

    private class Injected(val seq: Int, val tB: Long, val atUs: Long)

    private inner class Session(val socket: Socket) {
        val out: OutputStream = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)
        @Volatile var closed = false

        fun send(type: Int, payload: ByteArray) {
            if (closed) return
            try {
                SpikeWire.write(out, type, payload)
            } catch (e: IOException) {
                note("Send failed: $e")
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            thread(name = "spike-close") { runCatching { socket.close() } }
            main.post { if (stream?.session === this) stopStream() }
        }
    }

    private inner class Stream(val session: Session, val width: Int, val height: Int) {
        lateinit var encoder: MediaCodec
        var display: VirtualDisplay? = null
        var screen: SpikeScreen? = null
        var flagsName = "?"
        var codecName = "?"
        @Volatile var running = true
        var drain: Thread? = null
        val injected = ArrayList<Injected>()
        var downTime = 0L
        val pointers = LinkedHashMap<Int, FloatArray>()

        // Per second, read and reset by the stats tick.
        @Volatile var frames = 0
        @Volatile var bytes = 0L
        @Volatile var touches = 0
        val encodeDelay = SpikeWindow()
        val injectToFrame = SpikeWindow()
        val dispatchTime = SpikeWindow()
        var firstFrame = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        probe()
        startServer()
        tick()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopStream()
        session?.close()
        runCatching { server?.close() }
        super.onDestroy()
    }

    private fun note(line: String) {
        log.add(line)
        main.post { logText.text = log.text() }
        session?.let { s -> if (!s.closed) thread(name = "spike-log") { s.send(SpikeWire.LOG, SpikeWire.text(line)) } }
    }

    private fun buildUi(): View {
        val ctx: Context = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Pixel.dp(ctx, 12), Pixel.dp(ctx, 12), Pixel.dp(ctx, 12), Pixel.dp(ctx, 12))
        }
        fun label(text: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setTextIsSelectable(true)
        }
        column.addView(label("Screen spike: host (phone A)", 20f, Color.rgb(0, 220, 255)))
        column.addView(label("On phone B, open \"Screen spike: show\" and type one of these:", 14f))
        column.addView(label(SpikeWire.addresses().joinToString("\n").ifEmpty { "(no network address: is Wi-Fi on?)" }, 22f, Color.YELLOW))
        column.addView(label("The same clock is on B's Demo page: photograph both phones for the glass-to-glass delay.", 12f, Color.LTGRAY))
        column.addView(SpikeClock(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Pixel.dp(ctx, 100)))
        statsText = label("Waiting for phone B…", 13f, Color.rgb(160, 255, 160)).apply { typeface = android.graphics.Typeface.MONOSPACE }
        column.addView(statsText)
        val pagesRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Home", "Demo", "Type").forEachIndexed { i, name ->
            pagesRow.addView(Button(ctx).apply {
                text = "B: $name"
                isAllCaps = false
                setOnClickListener { stream?.screen?.showPage(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(pagesRow)
        column.addView(label("Keyboard for B: tap a field on B, then type here (A's keyboard).", 12f, Color.LTGRAY))
        column.addView(keysForB(ctx))
        probeText = label("", 12f, Color.rgb(255, 200, 120)).apply { typeface = android.graphics.Typeface.MONOSPACE }
        column.addView(probeText)
        logText = label("", 11f, Color.LTGRAY).apply { typeface = android.graphics.Typeface.MONOSPACE }
        column.addView(logText)
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
        return scroll
    }

    // Typing on A goes to the focused view on B's screen, as key events (as a hardware keyboard would).
    private fun keysForB(ctx: Context) = EditText(ctx).apply {
        hint = "type here → B's focused field"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                val decor = stream?.screen?.window?.decorView ?: return
                repeat(before) { sendKey(decor, KeyEvent.KEYCODE_DEL) }
                val added = s.subSequence(start, start + count).toString()
                if (added.isEmpty()) return
                val events = keys.getEvents(added.toCharArray())
                if (events != null) {
                    events.forEach { decor.dispatchKeyEvent(it) }
                } else {
                    // No key for it (an emoji, ü on some maps): straight into the field.
                    val field = decor.findFocus() as? EditText
                    if (field != null) field.text.replace(field.selectionStart.coerceAtLeast(0), field.selectionEnd.coerceAtLeast(0), added)
                    else note("Typed \"$added\": no key for it and no field focused on B")
                }
            }
        })
        setOnEditorActionListener { _, _, _ ->
            stream?.screen?.window?.decorView?.let { sendKey(it, KeyEvent.KEYCODE_ENTER) }
            true
        }
    }

    private fun sendKey(decor: View, code: Int) {
        val now = SystemClock.uptimeMillis()
        decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private val flagCombos = listOf(
        "PRESENTATION|OWN_CONTENT_ONLY" to (DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY),
        "OWN_CONTENT_ONLY" to DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        "PRESENTATION" to DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        "0 (none)" to 0,
        "PUBLIC|OWN_CONTENT_ONLY" to (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY),
        "PUBLIC|PRESENTATION" to (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION),
    )

    /** Which flags give a display a Presentation can show on, without MediaProjection: each tried once. */
    private fun probe() {
        val dm = getSystemService(DisplayManager::class.java)
        val lines = ArrayList<String>()
        lines.add("Probe: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        for ((name, flags) in flagCombos) {
            val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2)
            var vd: VirtualDisplay? = null
            var screen: SpikeScreen? = null
            val result = try {
                vd = dm.createVirtualDisplay("mynx-spike-probe", 64, 64, 160, reader.surface, flags)
                if (vd == null) "createVirtualDisplay gave null" else {
                    screen = SpikeScreen(this, vd.display) {}
                    screen.show()
                    "OK (display ${vd.display.displayId}, flags 0x${Integer.toHexString(vd.display.flags)})"
                }
            } catch (e: Throwable) {
                "FAIL ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { screen?.dismiss() }
                runCatching { vd?.release() }
                reader.close()
            }
            lines.add("  $name: $result")
        }
        probeSummary = lines.joinToString("\n")
        probeText.text = probeSummary
        log.add("Probe done")
    }

    private fun startServer() {
        thread(name = "spike-server") {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SpikeWire.PORT))
                }
            } catch (e: IOException) {
                note("Can't listen on port ${SpikeWire.PORT}: $e")
                return@thread
            }
            server = socket
            note("Listening on port ${SpikeWire.PORT}")
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (!socket.isClosed) note("accept failed: $e")
                    break
                }
                session?.close()
                client.tcpNoDelay = true
                runCatching { client.sendBufferSize = 1 shl 20 }
                val s = Session(client)
                session = s
                note("Phone B connected from ${client.inetAddress.hostAddress}")
                thread(name = "spike-reader") { readFrom(s) }
            }
        }
    }

    private fun readFrom(s: Session) {
        val input = DataInputStream(s.socket.getInputStream().buffered(1 shl 16))
        try {
            s.send(SpikeWire.LOG, SpikeWire.text(probeSummary))
            while (true) {
                val msg = SpikeWire.read(input)
                val p = DataInputStream(ByteArrayInputStream(msg.payload))
                when (msg.type) {
                    SpikeWire.HELLO -> {
                        val w = p.readInt(); val h = p.readInt(); val dpi = p.readInt()
                        val fps = p.readInt(); val kbps = p.readInt(); val iframe = p.readInt()
                        note("B asks for ${w}x$h at $dpi dpi, $fps fps, $kbps kbit/s, I-frame every $iframe s")
                        main.post { startStream(s, w, h, dpi, fps, kbps, iframe) }
                    }
                    SpikeWire.PING -> {
                        val tB = p.readLong()
                        s.send(SpikeWire.PONG, SpikeWire.build { writeLong(tB); writeLong(SpikeWire.nowUs()) })
                    }
                    SpikeWire.TOUCH -> {
                        val bytes = msg.payload
                        main.post { inject(s, bytes) }
                    }
                    SpikeWire.STATS_B -> bStats = String(msg.payload, Charsets.UTF_8)
                    SpikeWire.PAGE -> {
                        val page = p.readInt()
                        main.post { stream?.screen?.showPage(page) }
                    }
                    else -> note("Unknown message ${msg.type}")
                }
            }
        } catch (e: IOException) {
            note("Phone B left: $e")
        } catch (e: Throwable) {
            note("Reader crashed: ${e.stackTraceToString().take(800)}")
        }
        s.close()
    }

    private fun startStream(s: Session, askedW: Int, askedH: Int, dpi: Int, fps: Int, kbps: Int, iframe: Int) {
        stopStream()
        if (s.closed) return
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val video = caps.videoCapabilities
            var w = askedW
            var h = askedH
            if (!video.isSizeSupported(w, h)) {
                w = w / 16 * 16
                h = h / 16 * 16
            }
            while (!video.isSizeSupported(w, h) && w > 160) {
                w = (w * 3 / 4) / 16 * 16
                h = (h * 3 / 4) / 16 * 16
            }
            if (w != askedW || h != askedH) note("Encoder can't do ${askedW}x$askedH; using ${w}x$h")
            val hw = if (Build.VERSION.SDK_INT >= 29) " hw=${encoder.codecInfo.isHardwareAccelerated}" else ""
            val cbr = caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            note("Encoder ${encoder.name}$hw, CBR supported: $cbr, widths ${video.supportedWidths} heights ${video.supportedHeights}")

            val stream = Stream(s, w, h)
            stream.codecName = encoder.name
            var configured = false
            for (full in listOf(true, false)) {
                val format = encoderFormat(w, h, fps, kbps, iframe, full, cbr)
                try {
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    note("Encoder configured (${if (full) "low-latency keys" else "plain keys"}): $format")
                    configured = true
                    break
                } catch (e: Exception) {
                    note("configure failed (${if (full) "low-latency keys" else "plain keys"}): $e")
                    encoder.reset()
                }
            }
            if (!configured) throw IllegalStateException("The encoder took no format")
            val surface: Surface = encoder.createInputSurface()
            encoder.start()
            stream.encoder = encoder
            this.stream = stream

            val dm = getSystemService(DisplayManager::class.java)
            for ((name, flags) in flagCombos) {
                var vd: VirtualDisplay? = null
                try {
                    vd = dm.createVirtualDisplay("mynx-screen-spike", w, h, dpi, surface, flags)
                        ?: throw IllegalStateException("createVirtualDisplay gave null")
                    val screen = SpikeScreen(this, vd.display, ::note)
                    screen.show()
                    stream.display = vd
                    stream.screen = screen
                    stream.flagsName = name
                    note("Streaming display ${vd.display.displayId} with flags $name")
                    break
                } catch (e: Throwable) {
                    note("Display with $name failed: $e")
                    runCatching { vd?.release() }
                }
            }
            if (stream.screen == null) throw IllegalStateException("No VirtualDisplay flags worked")
            stream.drain = thread(name = "spike-drain") { drain(stream) }
        } catch (e: Throwable) {
            note("Couldn't start the stream: ${e.stackTraceToString().take(1500)}")
            stopStream()
        }
    }

    private fun encoderFormat(w: Int, h: Int, fps: Int, kbps: Int, iframe: Int, full: Boolean, cbr: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframe)
            // A still screen still sends frames (10 a second), so B never waits long for a picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (full) {
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

    private fun drain(st: Stream) {
        val info = MediaCodec.BufferInfo()
        try {
            while (st.running && !st.session.closed) {
                val index = st.encoder.dequeueOutputBuffer(info, 100_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    note("Encoder output: ${st.encoder.outputFormat}")
                    continue
                }
                if (index < 0) continue
                val buffer = st.encoder.getOutputBuffer(index)
                val data = ByteArray(info.size)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                }
                val flags = info.flags
                val pts = info.presentationTimeUs
                st.encoder.releaseOutputBuffer(index, false)
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    st.session.send(SpikeWire.CONFIG, SpikeWire.build { writeInt(st.width); writeInt(st.height); write(data) })
                    note("Sent the codec config (${data.size} bytes)")
                    continue
                }
                val now = SpikeWire.nowUs()
                if (st.firstFrame) {
                    st.firstFrame = false
                    note("First frame: now - pts = ${(now - pts) / 1000} ms (should be small: pts is the monotonic clock)")
                }
                st.encodeDelay.add(now - pts)
                var seq = 0
                var tB = 0L
                synchronized(st.injected) {
                    val it = st.injected.iterator()
                    while (it.hasNext()) {
                        val inj = it.next()
                        if (inj.atUs > pts) break
                        seq = inj.seq
                        tB = inj.tB
                        st.injectToFrame.add(pts - inj.atUs)
                        it.remove()
                    }
                }
                st.session.send(SpikeWire.FRAME, SpikeWire.build {
                    writeLong(pts); writeLong(SpikeWire.nowUs()); writeInt(flags); writeInt(seq); writeLong(tB); write(data)
                })
                st.frames++
                st.bytes += data.size
            }
        } catch (e: Throwable) {
            if (st.running) note("Encoder loop stopped: $e")
        }
    }

    private fun inject(s: Session, payload: ByteArray) {
        val st = stream ?: return
        if (st.session !== s) return
        val screen = st.screen ?: return
        val decor = screen.window?.decorView ?: return
        val p = DataInputStream(ByteArrayInputStream(payload))
        val seq = p.readInt()
        val tB = p.readLong()
        val masked = p.readInt()
        val actionIndex = p.readInt()
        val count = p.readInt()
        val props = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }
        for (i in 0 until count) {
            props[i].id = p.readInt()
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = p.readFloat() * st.width
            coords[i].y = p.readFloat() * st.height
            coords[i].pressure = 1f
            coords[i].size = 1f
        }
        val now = SystemClock.uptimeMillis()
        if (masked == MotionEvent.ACTION_DOWN) st.downTime = now
        val action = when (masked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> masked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> masked
        }
        val event = MotionEvent.obtain(st.downTime, now, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val start = SpikeWire.nowUs()
        try {
            decor.dispatchTouchEvent(event)
        } catch (e: Throwable) {
            note("dispatchTouchEvent threw: $e")
        } finally {
            event.recycle()
        }
        val end = SpikeWire.nowUs()
        st.dispatchTime.add(end - start)
        st.touches++
        // The dots: where each finger is now.
        when (masked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> st.pointers.clear()
            else -> {
                st.pointers.clear()
                for (i in 0 until count) {
                    if (masked == MotionEvent.ACTION_POINTER_UP && i == actionIndex) continue
                    st.pointers[props[i].id] = floatArrayOf(coords[i].x, coords[i].y)
                }
            }
        }
        screen.dots.set(st.pointers.values.flatMap { it.toList() }.toFloatArray())
        synchronized(st.injected) {
            st.injected.add(Injected(seq, tB, end))
            while (st.injected.size > 200) st.injected.removeAt(0)
        }
    }

    private fun stopStream() {
        val st = stream ?: return
        stream = null
        st.running = false
        runCatching { st.drain?.join(300) }
        runCatching { st.screen?.dismiss() }
        runCatching { st.display?.release() }
        runCatching { st.encoder.stop() }
        runCatching { st.encoder.release() }
        note("Stream stopped")
    }

    private fun tick() {
        val st = stream
        val a = if (st == null) "A: no stream" else {
            val frames = st.frames; st.frames = 0
            val bytes = st.bytes; st.bytes = 0
            val touches = st.touches; st.touches = 0
            "A: ${st.width}x${st.height} ${st.flagsName}\n" +
                "A: sent $frames fps, %.2f Mbit/s\n".format(bytes * 8 / 1e6) +
                "A: encode delay ms ${st.encodeDelay.takeMs()}\n" +
                "A: touches $touches/s, dispatch ms ${st.dispatchTime.takeMs()}\n" +
                "A: inject→frame ms ${st.injectToFrame.takeMs()}"
        }
        statsText.text = "$a\n$bStats"
        logText.text = log.text()
        session?.let { s -> if (!s.closed && st != null) thread(name = "spike-stats") { s.send(SpikeWire.STATS_A, SpikeWire.text(a)) } }
        main.postDelayed(::tick, 1000)
    }
}
