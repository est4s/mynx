package io.github.est4s.terminal

import android.app.Activity
import android.app.Application
import android.widget.EditText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A chat row's Rename and Delete, [pixelDialog]s: see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ChatRowDialogScreenshotTest {
    private fun shoot(name: String, show: (Activity) -> PixelDialog) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = show(activity)
        val decor = dialog.dialog.window!!.decorView
        Screenshots.saveWindow(decor, 1080 - Pixel.dp(activity, 32), Pixel.BACKGROUND, name)
    }

    @Test
    fun rename() = shoot("chat-rename") { activity ->
        val field = EditText(activity).apply {
            setText("Plan the weather station")
            setSingleLine(true)
        }
        pixelDialog(activity, "Rename chat", field = field, ok = "Rename") {}
    }

    @Test
    fun renameBlank() = shoot("chat-rename-blank") { activity ->
        pixelDialog(activity, "Rename chat", field = EditText(activity), ok = "Rename") {}.apply { setOkEnabled(false) }
    }

    @Test
    fun delete() = shoot("chat-delete") { activity ->
        pixelDialog(
            activity, "Delete chat?",
            message = "\"Explain proot's hard links\" and its transcript go for good. This can't be undone.",
            ok = "Delete", danger = true,
        ) {}
    }
}
