package io.github.est4s.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.NEON
import io.github.est4s.terminal.core.TextDoc
import io.github.est4s.terminal.core.TextLoad
import io.github.est4s.terminal.core.loadText
import java.util.concurrent.Executor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val PYTHON = """
    #!/usr/bin/env python3
    ""${'"'}Splits a bill and adds the tip.""${'"'}

    import math
    import sys

    TIPS = {"low": 10, "usual": 15, "generous": 20}


    def tip(total: float, percent: int) -> float:
        # Rounded up to whole cents, so nobody pays less.
        return math.ceil(total * percent) / 100


    class Bill:
        def __init__(self, total, people=1):
            self.total = total
            self.people = max(1, people)

        def share(self, percent=15):
            whole = self.total + tip(self.total, percent)
            return round(whole / self.people, 2)


    if __name__ == "__main__":
        bill = Bill(float(sys.argv[1]), int(sys.argv[2]))
        print(f"Each pays {bill.share(TIPS['usual'])} (a very long line that runs past the edge of the screen)")
""".trimIndent()

private val MARKDOWN = """
    # Tip calculator

    A small web app in `~/apps/tip-calc`, made in chat. See [the notes](notes.md) or the [site](https://example.com).

    ![The app on a phone](screenshot.png)

    ## What it does

    - **split** the bill between people
    - round *up* to a whole amount
      - to the cent, or the euro
    - remember the last tip

    > Tips are a percentage of the bill
    > before tax.

    ```python
    def tip(total, percent):
        return math.ceil(total * percent) / 100
    ```

    | Bill | 15% | 20% |
    |---:|---:|---:|
    | 40.00 | 6.00 | 8.00 |
    | 85.50 | 12.83 | 17.10 |

    ---

    1. Open it from Home
    2. Type the bill
""".trimIndent()

private val noViewerActions = object : TextViewerActions {
    override fun close() = Unit
    override fun save(doc: TextDoc) = Unit
    override fun share() = Unit
    override fun openWithAnotherApp() = Unit
    override fun openLink(url: String) = Unit
    override fun loadImage(url: String, done: (Bitmap?) -> Unit) {
        // A phone-shaped picture: a dark screen with a pink button.
        val picture = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            drawColor(Color.rgb(13, 2, 33))
            drawRect(60f, 150f, 300f, 200f, Paint().apply { color = Color.rgb(255, 11, 187) })
            drawRect(60f, 40f, 300f, 110f, Paint().apply { color = Color.rgb(0, 243, 255) })
        }
        done(picture)
    }
}

private fun docOf(text: String): TextDoc {
    val bytes = (text + "\n").toByteArray()
    return TextDoc(loadText(bytes, bytes.size.toLong()) as TextLoad.Text, 0)
}

/** The text viewer: code, Markdown and Edit, fed a file's text directly (see [Screenshots]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class TextViewScreenshotTest {
    private fun shoot(w: Int, h: Int, name: String, path: String, kind: FileKind, text: String, edit: Boolean = false) {
        val view = Screenshots.open(w, h) { activity ->
            TextViewerView(activity, path, kind, NEON, noViewerActions, Executor { it.run() })
        }
        view.showDoc(docOf(text), edit)
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    @Test
    fun code() = shoot(1080, 2400, "text-code", "/root/apps/tip-calc/tip.py", FileKind.CODE, PYTHON)

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun codeNarrow() = shoot(945, 2100, "text-code-360dp", "/root/apps/tip-calc/tip.py", FileKind.CODE, PYTHON)

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun codeLandscape() = shoot(2400, 1080, "text-code-landscape", "/root/apps/tip-calc/tip.py", FileKind.CODE, PYTHON)

    @Test
    fun markdown() = shoot(1080, 2400, "text-markdown", "/root/apps/tip-calc/README.md", FileKind.MARKDOWN, MARKDOWN)

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun markdownNarrow() = shoot(945, 2100, "text-markdown-360dp", "/root/apps/tip-calc/README.md", FileKind.MARKDOWN, MARKDOWN)

    @Test
    fun editCode() = shoot(1080, 2400, "text-edit", "/root/apps/tip-calc/tip.py", FileKind.CODE, PYTHON, edit = true)

    @Test
    fun editMarkdown() = shoot(1080, 2400, "text-edit-markdown", "/root/apps/tip-calc/README.md", FileKind.MARKDOWN, MARKDOWN, edit = true)

    @Test
    fun plainText() = shoot(1080, 2400, "text-plain", "/root/notes/todo.txt", FileKind.TEXT, "Buy milk\nCall the bank about the card, and ask why the last statement shows a fee twice\n\n- fix the bike")
}
