package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenFileRequestTest {
    private val top = createTempDirectory("open-file").toFile()
    private val rootfs = File(top, "rootfs")
    private val dir = File(rootfs, "tmp/.mynx/requests").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }

    private data class Opened(val path: String, val kind: FileKind?, val with: OpenWith, val edit: Boolean)

    private val opened = mutableListOf<Opened>()
    private var refusal: String? = null
    private val requests = MynxRequests(
        dir, home,
        files = WebApps(home, rootfs.path, tools),
        openFile = { path, kind, with, edit ->
            opened += Opened(path, kind, with, edit)
            refusal
        },
    )

    @AfterTest
    fun cleanup() {
        deleteTree(top)
    }

    private fun write(path: String, text: String) = File(rootfs, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun settings(text: String) = write("root/.config/mynx/settings.conf", text)

    @Test
    fun `opens a file in mynx's viewer for its kind, as the setting says`() {
        write("root/main.py", "print(1)\n")
        assertEquals("""{"ok":true,"with":"mynx","kind":"code"}""", ask("open-file", "/root/main.py", "default", "0"))
        assertEquals(listOf(Opened("/root/main.py", FileKind.CODE, OpenWith.MYNX, false)), opened)
    }

    @Test
    fun `the setting can send a kind to another app`() {
        settings("open-audio = android\n")
        write("root/song.mp3", "ID3")
        assertEquals("""{"ok":true,"with":"android","kind":"audio"}""", ask("open-file", "/root/song.mp3", "default", "0"))
        assertEquals(Opened("/root/song.mp3", FileKind.AUDIO, OpenWith.ANDROID, false), opened.single())
    }

    @Test
    fun `asked for one, it wins over the setting`() {
        settings("open-markdown = android\n")
        write("root/notes.md", "# Hi\n")
        assertEquals("""{"ok":true,"with":"mynx","kind":"markdown"}""", ask("open-file", "/root/notes.md", "mynx", "1"))
        assertEquals(Opened("/root/notes.md", FileKind.MARKDOWN, OpenWith.MYNX, true), opened.single())
        ask("open-file", "/root/notes.md", "android", "0")
        assertEquals(OpenWith.ANDROID, opened.last().with)
    }

    @Test
    fun `a file whose name says nothing is text if it looks like text`() {
        write("root/notes", "just words\n")
        assertEquals("""{"ok":true,"with":"mynx","kind":"text"}""", ask("open-file", "/root/notes", "default", "0"))
        File(rootfs, "root/blob").writeBytes(byteArrayOf(1, 0, 2, 0))
        assertEquals("""{"ok":true,"with":"android","kind":null}""", ask("open-file", "/root/blob", "default", "0"))
        assertEquals(Opened("/root/blob", null, OpenWith.ANDROID, false), opened.last())
    }

    @Test
    fun `mynx has no viewer for a file of no kind`() {
        File(rootfs, "root/app.apk").writeBytes(byteArrayOf(0x50, 0x4b, 3, 4, 0))
        assertEquals(
            """{"ok":false,"error":"mynx has no viewer for app.apk: open it with another app (--with android)"}""",
            ask("open-file", "/root/app.apk", "mynx", "0"),
        )
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `only text, code, Markdown and HTML open to edit`() {
        File(rootfs, "root/song.mp3").writeBytes(byteArrayOf(1, 2))
        assertEquals(
            """{"ok":false,"error":"only text, code, Markdown and HTML files open to edit: song.mp3 is audio"}""",
            ask("open-file", "/root/song.mp3", "default", "1"),
        )
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `an HTML file opens rendered, or its source to edit`() {
        write("root/site/index.html", "<p>hi</p>\n")
        assertEquals("""{"ok":true,"with":"mynx","kind":"html"}""", ask("open-file", "/root/site/index.html", "default", "0"))
        assertEquals("""{"ok":true,"with":"mynx","kind":"html"}""", ask("open-file", "/root/site/index.html", "default", "1"))
        assertEquals(Opened("/root/site/index.html", FileKind.HTML, OpenWith.MYNX, true), opened.last())
    }

    @Test
    fun `says when the file is missing, a folder, or not a full path`() {
        File(rootfs, "root/src").mkdirs()
        assertEquals("""{"ok":false,"error":"no such file: /root/gone.txt"}""", ask("open-file", "/root/gone.txt", "default", "0"))
        assertEquals(
            """{"ok":false,"error":"/root/src is a folder: open folders in Files"}""",
            ask("open-file", "/root/src", "default", "0"),
        )
        assertEquals("""{"ok":false,"error":"the path must start with /: notes.txt"}""", ask("open-file", "notes.txt", "default", "0"))
        assertEquals("""{"ok":false,"error":"open-file needs 1 argument"}""", ask("open-file"))
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `refuses a choice it doesn't know`() {
        write("root/a.txt", "a")
        assertEquals(
            """{"ok":false,"error":"open with mynx, android or default, not vlc"}""",
            ask("open-file", "/root/a.txt", "vlc", "0"),
        )
    }

    @Test
    fun `follows links as Debian sees them`() {
        write("root/real/notes.md", "# Hi\n")
        Files.createSymbolicLink(File(home, "notes").toPath(), Paths.get("/root/real/notes.md"))
        assertEquals("""{"ok":true,"with":"mynx","kind":"markdown"}""", ask("open-file", "/root/notes", "default", "0"))
        assertEquals("/root/notes", opened.single().path)
    }

    @Test
    fun `says why the app didn't open it`() {
        write("root/a.txt", "a")
        refusal = "the app isn't on screen"
        assertEquals("""{"ok":false,"error":"the app isn't on screen"}""", ask("open-file", "/root/a.txt", "default", "0"))
    }

    @Test
    fun `without a viewer, this build says it can't open files`() {
        write("root/a.txt", "a")
        val plain = MynxRequests(dir, home)
        File(dir, "q.req").writeText("open-file\n/root/a.txt\ndefault\n0\n")
        plain.processPending()
        assertEquals("""{"ok":false,"error":"this build can't open files yet"}""", File(dir, "q.reply").readText())
    }

    @Test
    fun `open-file-kind gives the kind and where the setting opens it`() {
        settings("open-code = android\n")
        write("root/main.kt", "fun main() {}\n")
        write("root/notes.md", "# Hi\n")
        File(rootfs, "root/blob").writeBytes(byteArrayOf(0, 0))
        assertEquals("""{"ok":true,"kind":"code","with":"android"}""", ask("open-file-kind", "/root/main.kt"))
        assertEquals("""{"ok":true,"kind":"markdown","with":"mynx"}""", ask("open-file-kind", "/root/notes.md"))
        assertEquals("""{"ok":true,"kind":null,"with":"android"}""", ask("open-file-kind", "/root/blob"))
        assertEquals("""{"ok":false,"error":"no such file: /root/x.md"}""", ask("open-file-kind", "/root/x.md"))
        assertEquals(emptyList(), opened)
    }

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").readText()
    }
}
