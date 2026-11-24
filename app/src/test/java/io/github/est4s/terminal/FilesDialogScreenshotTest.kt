package io.github.est4s.terminal

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Files' Rename, New folder and Delete, [pixelDialog]s: see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class FilesDialogScreenshotTest {
    private fun shoot(name: String, show: (Activity) -> PixelDialog) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = show(activity)
        val decor = dialog.dialog.window!!.decorView
        Screenshots.saveWindow(decor, 1080 - Pixel.dp(activity, 32), Pixel.BACKGROUND, name)
    }

    @Test
    fun rename() = shoot("files-rename") { activity ->
        askFileName(activity, "Rename", "weather-station.py", "Rename") {}
    }

    // A name core refuses: the main button greyed, the reason under the field.
    @Test
    fun renameRefused() = shoot("files-rename-refused") { activity ->
        askFileName(activity, "Rename", "weather-station.py", "Rename") {}.also {
            firstEditText(it.dialog.window!!.decorView)!!.setText("sensors/weather.py")
        }
    }

    @Test
    fun newFolder() = shoot("files-new-folder") { activity ->
        askFileName(activity, "New folder", "", "Create") {}
    }

    @Test
    fun delete() = shoot("files-delete") { activity ->
        confirmFilesDelete(activity, listOf("/root/projects/tip-calc/notes.md")) {}
    }

    @Test
    fun deleteMany() = shoot("files-delete-many") { activity ->
        confirmFilesDelete(activity, listOf("/root/a.txt", "/root/b.txt", "/root/c")) {}
    }
}

// The dialog's name field, to type into.
private fun firstEditText(view: View): EditText? {
    if (view is EditText) return view
    if (view is ViewGroup) for (i in 0 until view.childCount) firstEditText(view.getChildAt(i))?.let { return it }
    return null
}
