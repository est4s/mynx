package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.ClipOp
import io.github.est4s.terminal.core.FileClip
import io.github.est4s.terminal.core.FileEntry
import io.github.est4s.terminal.core.FileJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val HOUR = 3_600_000L
private const val FOLDER = "/root/projects/tip-calc"

private val noFileActions = object : FilesActions {
    override fun chatHere(folder: String) = Unit
    override fun terminalHere(folder: String) = Unit
    override fun viewPicture(path: String) = Unit
    override fun openFile(path: String) = Unit
    override fun share(paths: List<String>) = Unit
    override fun run(job: FileJob.Run, done: (Int, String) -> Unit) = Unit
    override fun close() = Unit
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
    ) {
        val view = Screenshots.open(w, h) { activity ->
            FilesView(activity, list = { _, _ -> }, home = "/root", actions = noFileActions)
        }
        view.show(FOLDER, entries, selection, clip)
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

    @Test
    fun pasting() = shoot(
        1080, 2400, "files-paste",
        clip = FileClip(ClipOp.MOVE, listOf("/root/Download/photo.jpg")),
    )
}
