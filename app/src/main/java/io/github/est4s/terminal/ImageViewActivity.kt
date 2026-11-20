package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.est4s.terminal.core.ImageZoom
import java.io.File

// The decoded picture's long side: twice the screen's, for zooming, but no more than a canvas draws comfortably.
private const val MAX_SIDE = 4096
private const val EXTRA_FILE = "file"
private const val EXTRA_NAME = "name"

/**
 * A picture full screen (Chat's images: the user's attachments and
 * pictures the agent made), on black: pinch or double-tap to zoom, drag to
 * move, a tap hides or shows the bar with ← and the file's name. Back
 * closes it. Started with the picture's file on the phone ([intent]); it's
 * decoded off the main thread ([decodeUpright]), and a file that can't be
 * shown says so on screen. The zoom is core's [ImageZoom].
 */
class ImageViewActivity : Activity() {
    private lateinit var picture: ZoomView
    private lateinit var bar: LinearLayout
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(intent.getStringExtra(EXTRA_FILE) ?: "")
        val name = intent.getStringExtra(EXTRA_NAME) ?: file.name
        title = name
        window.decorView.setBackgroundColor(Color.BLACK)
        picture = ZoomView(this) { bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        message = TextView(this).apply {
            text = "Loading…"
            setTextColor(Pixel.DIM)
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val pad = Pixel.dp(context, 24)
            setPadding(pad, pad, pad, pad)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            addView(FrameLayout(context).apply {
                contentDescription = "Back"
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
                addView(ImageView(context).apply {
                    setImageDrawable(Pixel.icon(context, PixelIcon.BACK, Pixel.CYAN, 20))
                }, FrameLayout.LayoutParams(Pixel.dp(context, 20), Pixel.dp(context, 20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(Pixel.dp(context, 48), Pixel.dp(context, 48)))
            addView(TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                typeface = Pixel.label(context, 700)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, 0, Pixel.dp(context, 16), 0)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val root = FrameLayout(this).apply {
            addView(picture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            // The picture goes under the system bars; the bar keeps clear of them and the camera hole.
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val sides = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    bar.setPadding(sides.left, sides.top, sides.right, 0)
                } else {
                    @Suppress("DEPRECATION")
                    bar.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, 0)
                }
                insets
            }
        }
        setContentView(root)
        val longSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val maxSide = minOf(MAX_SIDE, longSide * 2)
        Thread({
            val result = runCatching { decodeUpright(file, maxSide) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val bitmap = result.getOrNull()
                if (bitmap == null) {
                    message.text = if (!file.isFile) "$name isn't there any more." else
                        "Can't show $name: ${result.exceptionOrNull()?.toString() ?: "Android can't decode this picture."}"
                } else {
                    message.visibility = View.GONE
                    picture.show(bitmap)
                }
            }
        }, "image-view").apply { isDaemon = true }.start()
    }

    companion object {
        /** Shows the picture in [file] (a file on the phone), titled [name]. */
        fun intent(context: Context, file: File, name: String): Intent =
            Intent(context, ImageViewActivity::class.java)
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_NAME, name)
    }
}

// The picture, drawn where ImageZoom says; [tap] is a single tap.
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class ZoomView(context: Context, private val tap: () -> Unit) : View(context) {
    private var bitmap: Bitmap? = null
    private val zoom = ImageZoom()
    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaler.isInProgress) return false
            zoom.panBy(-distanceX, -distanceY)
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom.toggle(e.x, e.y)
            invalidate()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            tap()
            return true
        }
    })

    fun show(picture: Bitmap) {
        bitmap = picture
        fit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    private fun fit() {
        val shown = bitmap ?: return
        zoom.layout(width.toFloat(), height.toFloat(), shown.width.toFloat(), shown.height.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val shown = bitmap ?: return
        drawMatrix.setScale(zoom.scale, zoom.scale)
        drawMatrix.postTranslate(zoom.x, zoom.y)
        canvas.drawBitmap(shown, drawMatrix, paint)
    }
}
