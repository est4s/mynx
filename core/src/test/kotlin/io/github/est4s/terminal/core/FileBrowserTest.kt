package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBrowserListTest {
    private val base = createTempDirectory("files").toFile()
    private val rootfs = File(base, "rootfs")
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val apps = WebApps(home, rootfs.path, File(base, "tools").apply { mkdirs() })

    // Links in here point at real-looking paths: never deleteRecursively().
    @AfterTest
    fun cleanUp() {
        deleteTree(base)
    }

    private fun write(guest: String, text: String = "x") =
        File(rootfs, guest.removePrefix("/")).apply { parentFile.mkdirs() }.also { it.writeText(text) }

    private fun dir(guest: String) = File(rootfs, guest.removePrefix("/")).apply { mkdirs() }

    private fun link(guest: String, target: String) {
        val file = File(rootfs, guest.removePrefix("/")).apply { parentFile.mkdirs() }
        Files.createSymbolicLink(file.toPath(), Path.of(target))
    }

    private fun names(folder: String, hidden: Boolean = false) =
        listFolder(apps, folder, hidden).getOrThrow().map { it.name }

    @Test
    fun `folders come first, then files, by name ignoring case`() {
        write("/root/b.txt")
        write("/root/A.txt")
        dir("/root/zeta")
        dir("/root/Alpha")
        write("/root/c.txt")
        assertEquals(listOf("Alpha", "zeta", "A.txt", "b.txt", "c.txt"), names("/root"))
    }

    @Test
    fun `entries carry their Debian path, size and time`() {
        write("/root/src/notes.txt", "hello").setLastModified(1_700_000_000_000)
        dir("/root/src/lib")
        val list = listFolder(apps, "/root/src", false).getOrThrow()
        assertEquals(FileEntry("lib", "/root/src/lib", folder = true, link = false, size = 0, modified = list[0].modified), list[0])
        assertEquals(FileEntry("notes.txt", "/root/src/notes.txt", false, false, 5, 1_700_000_000_000), list[1])
    }

    @Test
    fun `paths in the root folder have one slash`() {
        dir("/etc")
        assertTrue("/etc" in listFolder(apps, "/", false).getOrThrow().map { it.path })
    }

    @Test
    fun `hidden names only when asked`() {
        write("/root/.bashrc")
        write("/root/seen")
        assertEquals(listOf("seen"), names("/root"))
        assertEquals(listOf(".bashrc", "seen"), names("/root", hidden = true))
    }

    @Test
    fun `links follow Debian's paths, a link to a folder is a folder, a broken one a file`() {
        dir("/srv/site")
        write("/srv/data.bin", "12345678")
        link("/root/site", "/srv/site")
        link("/root/data", "../srv/data.bin")
        link("/root/gone", "/nowhere")
        val list = listFolder(apps, "/root", false).getOrThrow().associateBy { it.name }
        assertEquals(true to true, list.getValue("site").let { it.folder to it.link })
        assertEquals(false to true, list.getValue("data").let { it.folder to it.link })
        assertEquals(8, list.getValue("data").size)
        assertEquals(false to true, list.getValue("gone").let { it.folder to it.link })
        assertEquals(listOf("site", "data", "gone"), list.keys.toList())
    }

    @Test
    fun `a folder reached through a link lists its files under the link's path`() {
        write("/srv/site/index.html")
        link("/root/site", "/srv/site")
        assertEquals(listOf("/root/site/index.html"), listFolder(apps, "/root/site", false).getOrThrow().map { it.path })
    }

    @Test
    fun `faked hard links' own files never show`() {
        write("/root/.l2s.a0001")
        write("/root/a")
        assertEquals(listOf("a"), names("/root", hidden = true))
    }

    @Test
    fun `a missing folder or a file fails with a short reason`() {
        write("/root/file.txt")
        val missing = listFolder(apps, "/root/none", false)
        assertTrue(missing.isFailure)
        assertEquals("/root/none doesn't exist", missing.exceptionOrNull()?.message)
        assertEquals("/root/file.txt isn't a folder", listFolder(apps, "/root/file.txt", false).exceptionOrNull()?.message)
        assertTrue(listFolder(apps, "relative", false).isFailure)
    }

    @Test
    fun `an empty folder is an empty list`() {
        dir("/root/empty")
        assertEquals(emptyList(), names("/root/empty"))
    }
}

class FileBrowserTest {
    @Test
    fun `crumbs start at home's tilde under home, else at the root`() {
        assertEquals(
            listOf("~" to "/root", "src" to "/root/src", "app" to "/root/src/app"),
            folderCrumbs("/root/src/app", "/root"),
        )
        assertEquals(listOf("~" to "/root"), folderCrumbs("/root", "/root"))
        assertEquals(listOf("/" to "/", "etc" to "/etc", "apt" to "/etc/apt"), folderCrumbs("/etc/apt", "/root"))
        assertEquals(listOf("/" to "/"), folderCrumbs("/", "/root"))
        assertEquals(listOf("/" to "/", "rootless" to "/rootless"), folderCrumbs("/rootless", "/root"))
    }

    @Test
    fun `the parent folder, none above the root`() {
        assertEquals("/root/src", parentFolder("/root/src/app"))
        assertEquals("/", parentFolder("/root"))
        assertNull(parentFolder("/"))
    }

    @Test
    fun `the places are home, Download, the phone and Debian`() {
        assertEquals(
            listOf("Home" to "/root", "Download" to "/storage/emulated/0/Download", "Phone" to "/storage/emulated/0", "Debian" to "/"),
            FILE_PLACES,
        )
    }

    @Test
    fun `sizes read short`() {
        assertEquals("0 B", fileSizeText(0))
        assertEquals("1023 B", fileSizeText(1023))
        assertEquals("1.0 KB", fileSizeText(1024))
        assertEquals("12 KB", fileSizeText(12 * 1024 + 100))
        assertEquals("3.4 MB", fileSizeText((3.4 * 1024 * 1024).toLong()))
        assertEquals("1.0 MB", fileSizeText(1024 * 1024 - 1))
        assertEquals("2.0 GB", fileSizeText(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `pictures are what the chat shows`() {
        assertTrue(isPictureName("cat.JPG"))
        assertTrue(isPictureName("a.webp"))
        assertFalse(isPictureName("a.svg"))
        assertFalse(isPictureName("jpg"))
    }

    @Test
    fun `bad names say why`() {
        assertNull(fileNameProblem("notes.txt"))
        assertNull(fileNameProblem(".bashrc"))
        assertNotNull(fileNameProblem(""))
        assertNotNull(fileNameProblem("   "))
        assertNotNull(fileNameProblem("."))
        assertNotNull(fileNameProblem(".."))
        assertNotNull(fileNameProblem("a/b"))
        assertNotNull(fileNameProblem("a\u0000b"))
        assertNull(fileNameProblem("é".repeat(127)))
        assertNotNull(fileNameProblem("é".repeat(128)))
    }

    @Test
    fun `a pasted name gets a number when it's taken`() {
        assertEquals("a.txt", pasteName("a.txt", setOf("b.txt")))
        assertEquals("a (2).txt", pasteName("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", pasteName("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals(".bashrc (2)", pasteName(".bashrc", setOf(".bashrc")))
        assertEquals("src (2)", pasteName("src", setOf("src")))
        assertEquals("x.tar (2).gz", pasteName("x.tar.gz", setOf("x.tar.gz")))
    }

    private fun script(job: FileJob): String {
        assertIs<FileJob.Run>(job)
        assertEquals(listOf("/bin/sh", "-c"), job.argv.take(2))
        assertEquals(3, job.argv.size)
        return job.argv[2]
    }

    @Test
    fun `copying runs cp for each item, numbering taken names`() {
        val job = pasteJob(FileClip(ClipOp.COPY, listOf("/root/a.txt", "/tmp/a.txt", "/root/it's")), "/srv", setOf("a.txt"))
        assertEquals(
            "set -e\n" +
                "cp -a -- '/root/a.txt' '/srv/a (2).txt'\n" +
                "cp -a -- '/tmp/a.txt' '/srv/a (3).txt'\n" +
                "cp -a -- '/root/it'\\''s' '/srv/it'\\''s'",
            script(job),
        )
        assertEquals("Copying 3 items", (job as FileJob.Run).title)
    }

    @Test
    fun `copying into the same folder makes a numbered copy`() {
        val job = pasteJob(FileClip(ClipOp.COPY, listOf("/root/a.txt")), "/root", setOf("a.txt"))
        assertEquals("set -e\ncp -a -- '/root/a.txt' '/root/a (2).txt'", script(job))
        assertEquals("Copying a.txt", (job as FileJob.Run).title)
    }

    @Test
    fun `pasting into the root folder`() {
        assertEquals("set -e\ncp -a -- '/root/a' '/a'", script(pasteJob(FileClip(ClipOp.COPY, listOf("/root/a")), "/", emptySet())))
    }

    @Test
    fun `moving runs mv, skipping what's already here`() {
        val job = pasteJob(FileClip(ClipOp.MOVE, listOf("/root/a.txt", "/srv/b", "/tmp/b")), "/srv", setOf("b"))
        assertEquals("set -e\nmv -- '/root/a.txt' '/srv/a.txt'\nmv -- '/tmp/b' '/srv/b (2)'", script(job))
        assertEquals("Moving 2 items", (job as FileJob.Run).title)
        assertEquals(FileJob.Refused("Already here"), pasteJob(FileClip(ClipOp.MOVE, listOf("/srv/b")), "/srv", setOf("b")))
    }

    @Test
    fun `a folder can't go into itself`() {
        assertIs<FileJob.Refused>(pasteJob(FileClip(ClipOp.MOVE, listOf("/root/src")), "/root/src", emptySet()))
        assertIs<FileJob.Refused>(pasteJob(FileClip(ClipOp.MOVE, listOf("/root/src")), "/root/src/app", emptySet()))
        assertIs<FileJob.Refused>(pasteJob(FileClip(ClipOp.COPY, listOf("/root/src")), "/root/src/app", emptySet()))
        assertIs<FileJob.Run>(pasteJob(FileClip(ClipOp.MOVE, listOf("/root/src")), "/root/srcs", emptySet()))
        assertIs<FileJob.Refused>(pasteJob(FileClip(ClipOp.COPY, emptyList()), "/root", emptySet()))
    }

    @Test
    fun `deleting runs rm -rf on every path, never the root`() {
        val job = deleteJob(listOf("/root/a", "/root/b c"))
        assertEquals("set -e\nrm -rf -- '/root/a' '/root/b c'", script(job))
        assertEquals("Deleting 2 items", (job as FileJob.Run).title)
        assertEquals("Deleting a", (deleteJob(listOf("/root/a")) as FileJob.Run).title)
        assertIs<FileJob.Refused>(deleteJob(emptyList()))
        assertIs<FileJob.Refused>(deleteJob(listOf("/root/a", "/")))
    }

    @Test
    fun `renaming checks the new name, then moves it in its folder`() {
        val job = renameJob("/root/a.txt", "b.txt", setOf("a.txt"))
        assertEquals("set -e\nmv -- '/root/a.txt' '/root/b.txt'", script(job))
        assertEquals("Renaming a.txt", (job as FileJob.Run).title)
        assertEquals(FileJob.Refused("b.txt already exists"), renameJob("/root/a.txt", "b.txt", setOf("a.txt", "b.txt")))
        assertEquals(FileJob.Refused("Same name"), renameJob("/root/a.txt", "a.txt", setOf("a.txt")))
        assertEquals(FileJob.Refused(fileNameProblem("a/b")!!), renameJob("/root/a.txt", "a/b", setOf("a.txt")))
        assertIs<FileJob.Refused>(renameJob("/", "x", emptySet()))
    }

    @Test
    fun `a new folder is made with mkdir`() {
        val job = newFolderJob("/root", "new", setOf("old"))
        assertEquals("set -e\nmkdir -- '/root/new'", script(job))
        assertEquals("New folder new", (job as FileJob.Run).title)
        assertEquals("set -e\nmkdir -- '/new'", script(newFolderJob("/", "new", emptySet())))
        assertEquals(FileJob.Refused("old already exists"), newFolderJob("/root", "old", setOf("old")))
        assertEquals(FileJob.Refused(fileNameProblem("")!!), newFolderJob("/root", "", emptySet()))
    }
}
