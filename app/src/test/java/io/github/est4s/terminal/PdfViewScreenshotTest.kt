package io.github.est4s.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.est4s.terminal.core.PdfRect
import io.github.est4s.terminal.core.PdfRenderSize
import io.github.est4s.terminal.core.PdfSelection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// US Letter, A4 and a landscape slide: height / width.
private const val LETTER = 792f / 612f
private const val SLIDE = 9f / 16f

/**
 * Stand-in pages, drawn here: Robolectric has no PDF renderer (Android's
 * PdfRenderer is pdfium, native), so the view gets bitmaps like a
 * render's: white, with a heading and lines of "text" at the size asked.
 */
private class FakePages : PdfViewActions {
    private val main = Handler(Looper.getMainLooper())
    override fun close() = Unit
    override fun share() = Unit
    override fun openWithAnotherApp() = Unit

    override fun render(page: Int, size: PdfRenderSize, stillWanted: () -> Boolean, done: (Bitmap?, String?) -> Unit) {
        main.post { done(if (stillWanted()) drawPage(page, size) else null, null) }
    }

    private fun drawPage(page: Int, size: PdfRenderSize): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val unit = size.width / 100f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt() }
        val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B4BA.toInt() }
        ink.textSize = unit * 6
        canvas.drawText(if (page == 0) "Quarterly report" else "Section $page", unit * 10, unit * 16, ink)
        ink.textSize = unit * 3
        canvas.drawText("Page ${page + 1}", unit * 10, size.height - unit * 6, ink)
        var y = unit * 24
        var line = 0
        while (y < size.height - unit * 14) {
            val end = if (line % 7 == 6) 55f else 90f
            canvas.drawRect(unit * 10, y, unit * end, y + unit * 1.4f, grey)
            y += if (line % 7 == 6) unit * 6 else unit * 3.2f
            line++
        }
        return bitmap
    }
}

/** The PDF viewer with stand-in pages (see [FakePages]), zoomed, at a page, and its error (see [Screenshots]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class PdfViewScreenshotTest {
    private fun shoot(w: Int, h: Int, name: String, aspects: List<Float>, then: (PdfView) -> Unit = {}) {
        val view = Screenshots.open(w, h) { activity -> PdfView(activity, "/root/docs/quarterly-report.pdf", FakePages()) }
        // Kept on screen for the picture.
        view.indicatorHold = 60_000L
        view.showPages(aspects)
        Screenshots.settle()
        then(view)
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    private fun key(view: PdfView, code: Int) {
        view.onKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        view.onKey(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test
    fun pages() = shoot(1080, 2400, "pdf-pages", List(12) { LETTER })

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun pagesNarrow() = shoot(945, 2100, "pdf-pages-360dp", List(12) { LETTER })

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun pagesLandscape() = shoot(2400, 1080, "pdf-pages-landscape", List(12) { LETTER })

    @Test
    fun slides() = shoot(1080, 2400, "pdf-slides", List(8) { SLIDE }) { key(it, KeyEvent.KEYCODE_PAGE_DOWN) }

    @Test
    fun zoomed() = shoot(1080, 2400, "pdf-zoomed", List(12) { LETTER }) { view ->
        repeat(4) { key(view, KeyEvent.KEYCODE_PLUS) }
        // The zoom settles, and the pages in sight render sharper.
        Screenshots.settle()
    }

    @Test
    fun atPage() = shoot(1080, 2400, "pdf-page-3", List(12) { LETTER }) { it.pages.goTo(2) }

    @Test
    fun selection() = shoot(1080, 2400, "pdf-selection", List(12) { LETTER }) { view ->
        // Robolectric can't select (no pdfium): the stand-in lines' rects, in a 612-point page's units (FakePages' 1/100 of its width).
        val unit = 612f / 100
        fun line(index: Int, from: Float, to: Float): PdfRect {
            val y = 24f + 3.2f * index
            return PdfRect(from * unit, (y - 0.7f) * unit, to * unit, (y + 2.1f) * unit)
        }
        view.pages.showSelection(PdfSelection(0, "selected text", listOf(line(2, 40f, 90f), line(3, 10f, 90f), line(4, 10f, 62f))))
    }

    @Test
    fun password() {
        val view = Screenshots.open(1080, 2400) { activity -> PdfView(activity, "/root/docs/payslip.pdf", FakePages()) }
        view.showError("payslip.pdf is protected by a password: mynx's viewer can't open it.")
        Screenshots.settle()
        Screenshots.save(view, "pdf-password")
    }
}
