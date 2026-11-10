package io.github.est4s.terminal

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import io.github.est4s.terminal.core.NEON
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.stripColors
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

// A Pixel-like phone: 1080 px across at 420 dpi (density 2.625) is 412 dp.
const val PHONE_QUALIFIERS = "w412dp-h914dp-port-420dpi"
const val NARROW_QUALIFIERS = "w360dp-h800dp-port-420dpi"
const val LANDSCAPE_QUALIFIERS = "w914dp-h412dp-land-420dpi"

/** Lays its one child out at exactly [w]×[h] pixels, whatever size the test's window has. */
class FixedFrame(context: Context, val w: Int, val h: Int) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val height = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(width, height)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, w, h)
    }
}

/**
 * Touch mode's screens as PNGs, for looking at without a phone: views laid
 * out in an activity (their posts run only once attached) and drawn into a
 * bitmap. Saved to `mynx.screenshots` (`app/build/screenshots`), which CI
 * uploads as the `screenshots` artifact. Nothing compares them.
 */
object Screenshots {
    private val dir = File(System.getProperty("mynx.screenshots") ?: "build/screenshots")

    /** The default theme's colours, as the activity gives the views. */
    val colors: StripColors = NEON.stripColors()

    /** [make]'s view in a new activity, [w]×[h] pixels. */
    fun <V : View> open(w: Int, h: Int, make: (Activity) -> V): V {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = make(activity)
        activity.setContentView(FixedFrame(activity, w, h).apply { addView(view) })
        settle()
        return view
    }

    /** Runs what's posted to the main thread, delayed posts and frames too. */
    fun settle() {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) }
    }

    /** As [settle], also for work another thread posts back (HomeView reads its lists on one). */
    fun settleThreads() {
        repeat(50) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        settle()
    }

    fun save(view: View, name: String) {
        val frame = view.parent as FixedFrame
        val w = frame.w
        val h = frame.h
        // Laid out again here in case the window's own pass hasn't run.
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        settle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "couldn't write $name.png" }
        }
        println("Screenshot: ${File(dir, "$name.png").absolutePath}")
    }
}
