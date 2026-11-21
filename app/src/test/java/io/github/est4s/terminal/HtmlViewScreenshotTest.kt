package io.github.est4s.terminal

import android.app.Application
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import io.github.est4s.terminal.core.mediaFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val noHtmlActions = object : HtmlViewActions {
    override fun close() = Unit
    override fun back() = Unit
    override fun reload() = Unit
    override fun viewSource() = Unit
    override fun share() = Unit
    override fun openWithAnotherApp() = Unit
}

private const val PAGE = "/root/projects/quarterly-report/index.html"

/**
 * The page viewer's chrome: Robolectric's WebView draws nothing, so the
 * page is a stand-in (a white block saying so); the error screen is the
 * real one. See [Screenshots].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class HtmlViewScreenshotTest {
    private fun page(w: Int, h: Int, name: String, title: String) {
        val view = Screenshots.open(w, h) { activity ->
            HtmlViewerView(activity, noHtmlActions).apply {
                showPage(title, mediaFolder(PAGE))
                setPage(TextView(activity).apply {
                    text = "(the page, drawn by WebView on a phone)"
                    setTextColor(Color.DKGRAY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.WHITE)
                })
            }
        }
        Screenshots.save(view, name)
    }

    @Test
    fun shown() = page(1080, 2400, "html-page", "Quarterly report: sales, costs and what's next for the team")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = page(945, 2100, "html-page-360dp", "index.html")

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = page(2400, 1080, "html-page-landscape", "Quarterly report")

    @Test
    fun cantLoad() {
        val view = Screenshots.open(1080, 2400) { activity ->
            HtmlViewerView(activity, noHtmlActions).apply {
                showPage("index.html", mediaFolder(PAGE))
                showError("index.html isn't there.")
            }
        }
        Screenshots.save(view, "html-page-error")
    }
}
