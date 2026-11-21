package io.github.est4s.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.est4s.terminal.core.ClipOp
import io.github.est4s.terminal.core.FileClip
import io.github.est4s.terminal.core.FileEntry
import io.github.est4s.terminal.core.FileJob
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.OpenWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

private const val HOUR = 3_600_000L
private const val FOLDER = "/root/projects/tip-calc"

private open class NoFileActions(private val granted: Boolean = true) : FilesActions {
    override fun chatHere(folder: String) = Unit
    override fun terminalHere(folder: String) = Unit
    override fun openFile(path: String, with: OpenWith?) = Unit
    override fun fileKind(path: String): FileKind? = null
    override fun share(paths: List<String>) = Unit
    override fun run(job: FileJob.Run, done: (Int, String) -> Unit) = Unit
    override fun close() = Unit
    override fun storageGranted() = granted
    override fun allowStorage() = Unit
}

/** The sample pictures, drawn: logo.png a pink block on dark, the screenshot a sky and a field. */
private val PICTURES: File by lazy {
    Files.createTempDirectory("files-pictures").toFile().also { dir ->
        val logo = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(logo).apply {
            drawColor(Color.rgb(13, 2, 33))
            drawRect(50f, 50f, 150f, 150f, Paint().apply { color = Color.rgb(255, 11, 187) })
        }
        File(dir, "logo.png").outputStream().use { logo.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shot = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        Canvas(shot).apply {
            drawColor(Color.rgb(120, 180, 240))
            drawRect(0f, 250f, 300f, 400f, Paint().apply { color = Color.rgb(70, 150, 70) })
        }
        File(dir, "screenshot-2026-11-20.jpg").outputStream().use { shot.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
}

// Folders first, then files, as core sorts them: one of each kind, a link and a hidden one.
private fun sampleEntries(now: Long): List<FileEntry> {
    fun entry(name: String, folder: Boolean = false, link: Boolean = false, size: Long = 0, age: Long = HOUR) =
        FileEntry(name, "$FOLDER/$name", folder, link, size, now - age)
    return listOf(
        entry(".git", folder = true, age = 2 * HOUR),
        entry("assets", folder = true, age = 26 * HOUR),
        entry("node_modules", folder = true, link = true, age = 9 * 24 * HOUR),
        entry("src", folder = true, age = 5 * 60_000),
        entry(".env", size = 112),
        entry("index.html", size = 2_310, age = 5 * 60_000),
        entry("logo.png", size = 48_211, age = 3 * HOUR),
        entry("notes-from-the-very-long-meeting-about-the-tip-calculator.md", size = 9_120),
        entry("package.json", size = 734, age = 40 * 60_000),
        entry("screenshot-2026-11-20.jpg", size = 1_843_200, age = 30 * HOUR),
    )
}

/** Touch mode's Files, fed entries directly (no listing): see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class FilesScreenshotTest {
    private fun shoot(
        w: Int,
        h: Int,
        name: String,
        entries: List<FileEntry> = sampleEntries(System.currentTimeMillis()),
        selection: List<String> = emptyList(),
        clip: FileClip? = null,
        folder: String = FOLDER,
        storageGranted: Boolean = true,
    ) {
        val view = Screenshots.open(w, h) { activity ->
            val thumbnails = ChatThumbnails(
                activity,
                { path -> File(PICTURES, path.substringAfterLast('/')) },
                Executor { it.run() },
                FilesView.FILES_THUMBNAIL_DP,
            )
            FilesView(activity, list = { _, _ -> }, home = "/root", actions = NoFileActions(storageGranted), thumbnails = thumbnails)
        }
        view.show(folder, entries, selection, clip)
        Screenshots.settle()
        // As the post after layout does on a phone: the rows have their places now.
        view.loadVisibleThumbnails()
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    @Test
    fun folder() = shoot(1080, 2400, "files")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun narrow() = shoot(945, 2100, "files-360dp")

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun landscape() = shoot(2400, 1080, "files-landscape")

    @Test
    fun empty() = shoot(1080, 2400, "files-empty", entries = emptyList())

    @Test
    fun selecting() = shoot(
        1080, 2400, "files-selected",
        selection = listOf("$FOLDER/index.html", "$FOLDER/logo.png"),
        clip = FileClip(ClipOp.COPY, listOf("/root/Download/a.txt", "/root/Download/b.txt", "/root/Download/c.txt")),
    )

    // The phone's storage before Android allows all of it: the bar asking for it.
    @Test
    fun storage() = shoot(1080, 2400, "files-storage", folder = "/storage/emulated/0/Download", storageGranted = false)

    @Test
    fun pasting() = shoot(
        1080, 2400, "files-paste",
        clip = FileClip(ClipOp.MOVE, listOf("/root/Download/photo.jpg")),
    )
}
