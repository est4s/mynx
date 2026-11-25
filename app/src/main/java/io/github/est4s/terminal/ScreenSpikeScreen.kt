package io.github.est4s.terminal

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.ToolsInstaller
import io.github.est4s.terminal.core.WebApps
import java.io.File

// SPIKE (throwaway): what phone A draws on its off-screen display for phone B.
// Pages: touch mode's real Home (HomeView, actions only logged), and a demo with
// a clock, an animation, a scrolling list, an EditText and a terminal-like log.

internal class SpikeScreen(
    outer: Context,
    display: Display,
    private val log: (String) -> Unit,
) : Presentation(outer, display) {
    lateinit var dots: SpikeDots
        private set
    private lateinit var pages: FrameLayout
    private val built = ArrayList<View>()
    private var home: HomeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(30, 30, 40))
        }
        listOf("Home", "Demo", "Type").forEachIndexed { i, name ->
            tabs.addView(Button(ctx).apply {
                text = name
                isAllCaps = false
                setOnClickListener { showPage(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        pages = FrameLayout(ctx)
        column.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        dots = SpikeDots(ctx)
        root.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        built.add(runCatching { homePage(ctx) }.getOrElse { e ->
            log("Home failed: $e")
            TextView(ctx).apply {
                text = "Touch mode's Home couldn't be built here:\n${e.stackTraceToString()}"
                setTextColor(Color.RED)
            }
        })
        built.add(demoPage(ctx))
        built.add(typePage(ctx))
        built.forEach { pages.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        showPage(1)
    }

    fun showPage(index: Int) {
        built.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        if (index == 0) home?.refresh()
        log("Page: ${listOf("Home", "Demo", "Type").getOrElse(index) { "?" }}")
    }

    private fun homePage(ctx: Context): View {
        val base = ctx.applicationContext.filesDir
        val rootfs = RootfsInstaller(base).rootfs
        val apps = WebApps(File(rootfs, "root"), rootfs.absolutePath, ToolsInstaller(base).tools)
        val view = HomeView(ctx, { HomeMenus(apps) }, { action -> log("Home action (not run in the spike): $action") }) { name, _ ->
            "Not in the spike: $name"
        }
        home = view
        view.refresh()
        return view
    }

    private fun demoPage(ctx: Context): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 16, 24))
        }
        column.addView(SpikeClock(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Pixel.dp(ctx, 120)))
        column.addView(EditText(ctx).apply {
            hint = "Tap here, then type on A's \"keyboard for B\" field"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(SpikeTerminal(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Pixel.dp(ctx, 140)))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (i in 1..80) {
            list.addView(TextView(ctx).apply {
                text = "Row $i: scroll me, the quick brown fox jumps over the lazy dog"
                textSize = 16f
                setPadding(Pixel.dp(ctx, 12), Pixel.dp(ctx, 10), Pixel.dp(ctx, 12), Pixel.dp(ctx, 10))
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.HSVToColor(floatArrayOf((i * 23f) % 360f, 0.5f, 0.35f)))
                isClickable = true
                setOnClickListener { log("Tapped row $i") }
            })
        }
        column.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun typePage(ctx: Context): View = ScrollView(ctx).apply {
        setBackgroundColor(Color.rgb(250, 250, 245))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Pixel.dp(ctx, 16), Pixel.dp(ctx, 16), Pixel.dp(ctx, 16), Pixel.dp(ctx, 16))
            addView(TextView(ctx).apply {
                text = "Small text, to judge sharpness: the quick brown fox jumps over the lazy dog 0123456789"
                textSize = 11f
                setTextColor(Color.BLACK)
            })
            addView(EditText(ctx).apply {
                hint = "A multi-line field"
                minLines = 6
                gravity = Gravity.TOP
                setTextColor(Color.BLACK)
            })
        })
    }
}

/** Where the injected touches are, drawn over everything (B draws its own, local, to compare). */
internal class SpikeDots(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 60, 60) }
    private var points = FloatArray(0)

    fun set(xy: FloatArray) {
        points = xy
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        var i = 0
        while (i + 1 < points.size) {
            canvas.drawCircle(points[i], points[i + 1], Pixel.dp(context, 28).toFloat(), paint)
            i += 2
        }
    }
}

/** A ms clock redrawn every frame (A shows the same clock): photograph both phones for glass-to-glass delay. */
internal class SpikeClock(context: Context) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = Pixel.dp(context, 44).toFloat()
    }
    private val box = Paint().apply { color = Color.rgb(0, 200, 255) }
    private var running = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val ms = SystemClock.uptimeMillis()
        canvas.drawText(spikeClockText(ms), Pixel.dp(context, 12).toFloat(), height * 0.55f, paint)
        // A square sweeping across: motion for the encoder.
        val x = ((ms % 2000) / 2000f) * (width - Pixel.dp(context, 30))
        canvas.drawRect(x, height - Pixel.dp(context, 30).toFloat(), x + Pixel.dp(context, 30), height.toFloat(), box)
    }
}

internal fun spikeClockText(ms: Long): String = "%03d.%03d".format((ms / 1000) % 1000, ms % 1000)

/** Terminal-like output: monospace lines added ten times a second. */
internal class SpikeTerminal(context: Context) : TextView(context) {
    private var n = 0
    private val tick = object : Runnable {
        override fun run() {
            n++
            val lines = (text?.toString() ?: "").split('\n').takeLast(7) + "$ build step $n: compiling src/file$n.kt ... ok (${n * 37 % 997} ms)"
            text = lines.joinToString("\n")
            postDelayed(this, 100)
        }
    }

    init {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Color.rgb(120, 255, 120))
        setBackgroundColor(Color.BLACK)
        setPadding(Pixel.dp(context, 8), Pixel.dp(context, 4), Pixel.dp(context, 8), Pixel.dp(context, 4))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
