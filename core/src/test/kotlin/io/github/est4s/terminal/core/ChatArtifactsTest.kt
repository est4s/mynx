package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatArtifactsTest {
    private val top = Files.createTempDirectory("artifacts").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val finder = ArtifactFinder(WebApps(home, rootfs.path, tools))

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    private fun write(guest: String, text: String, executable: Boolean = false): File =
        File(rootfs, guest.removePrefix("/")).apply {
            parentFile.mkdirs()
            writeText(text)
            setExecutable(executable)
        }

    private fun call(name: String, title: String, status: ToolStatus = ToolStatus.COMPLETED, id: String = "t-$title") =
        ToolCallInfo(id, name, title, ToolKind.EDIT, status)

    private fun hints(vararg calls: ToolCallInfo, cwd: String = "/root/work") =
        artifactHints(calls.toList(), cwd, "/root")

    @Test
    fun `written files are hints, relative to the chat's folder or home`() {
        assertEquals(
            listOf(
                ArtifactHint("/root/apps/demo/index.html", false, toolIds = listOf("t-~/apps/demo/index.html")),
                ArtifactHint("/root/work/b.apk", false, toolIds = listOf("t-b.apk")),
                ArtifactHint("/root/apps/x/main.js", false, toolIds = listOf("t-../apps/./x//main.js")),
                ArtifactHint("/root/notes.ipynb", false, toolIds = listOf("t-/root/notes.ipynb")),
            ),
            hints(
                call("Write", "~/apps/demo/index.html"),
                call("Edit", "b.apk"),
                call("Edit", "../apps/./x//main.js"),
                call("NotebookEdit", "/root/notes.ipynb"),
            ),
        )
    }

    @Test
    fun `only completed writes count, not reads or failed or running ones`() {
        assertEquals(
            emptyList(),
            hints(
                call("Read", "/root/apps/a/index.html"),
                call("Write", "/root/apps/b/index.html", ToolStatus.FAILED),
                call("Write", "/root/apps/c/index.html", ToolStatus.IN_PROGRESS),
                call("Write", "Write"),
            ),
        )
    }

    @Test
    fun `a command's paths are hints only in the app folders, or APKs`() {
        assertEquals(
            listOf(
                ArtifactHint("/root/apps/demo", true),
                ArtifactHint("/root/apps/demo/index.html", true, written = true),
                ArtifactHint("/root/terminal-apps/tool.py", true),
                ArtifactHint("/root/work/build/out.apk", true),
            ),
            hints(
                call(
                    "Bash",
                    "mkdir -p ~/apps/demo && cat > \"~/apps/demo/index.html\" <<'EOF'\n<p>/root/other</p>\nEOF\n" +
                        "chmod +x ~/terminal-apps/tool.py; ls ~/apps/*; echo \$HOME/apps/x; " +
                        "apksigner sign --out=build/out.apk build/aligned.apk.idsig /tmp/x",
                ),
            ),
        )
    }

    @Test
    fun `files a command writes are written hints, wherever they are`() {
        assertEquals(
            listOf(
                ArtifactHint("/root/scripts/phone-status.sh", true, written = true),
                ArtifactHint("/root/scripts/README.md", true, written = true),
                ArtifactHint("/root/log.txt", true, written = true),
                ArtifactHint("/root/work/out.csv", true, written = true),
            ),
            hints(
                call("Bash", "mkdir -p ~/scripts\ncat > ~/scripts/phone-status.sh <<'EOF'\necho hi > body.txt\nEOF"),
                call("Bash", "cd ~/scripts && cat >README.md <<-EOF\n# a > b\n\tEOF\necho done"),
                call("Bash", "date | tee -a ~/log.txt; make 2>&1 >/dev/null; ls > /tmp/list 2>/tmp/err"),
                call("Bash", "python3 conv.py >> 'out.csv'; echo \$x > \$HOME/y"),
            ),
        )
    }

    @Test
    fun `a file a command wrote in this turn is a Markdown card or a changed file`() {
        val md = write("/root/scripts/README.md", "# Hi")
        val sh = write("/root/scripts/run.sh", "echo hi")
        val old = write("/root/scripts/old.sh", "echo old")
        listOf(md, sh).forEach { it.setLastModified(5_000_000L) }
        old.setLastModified(1_000_000L)
        val hints = listOf("/root/scripts/README.md", "/root/scripts/run.sh", "/root/scripts/old.sh")
            .map { ArtifactHint(it, true, written = true) }
        assertEquals(
            listOf(
                ChatArtifact(ArtifactKind.MARKDOWN, "/root/scripts/README.md", "README.md", "/root/scripts/README.md"),
                changed("/root/scripts/run.sh"),
            ),
            finder.find(hints, 2_000_000L),
        )
    }

    @Test
    fun `a static web app written to is found with its page's title`() {
        write("/root/apps/demo/index.html", "<title>Demo page</title>")
        assertEquals(
            listOf(ChatArtifact(ArtifactKind.WEB_APP, "/root/apps/demo", "Demo page", "/root/apps/demo/index.html", appName = "demo")),
            finder.find(listOf(ArtifactHint("/root/apps/demo/index.html", false), ArtifactHint("/root/apps/demo/style.css", false)), null),
        )
    }

    @Test
    fun `a served web app shares its page if it has one, else its app conf`() {
        write("/root/apps/srv/app.conf", "name = Server\nurl = http://127.0.0.1:8080/\n")
        assertEquals(
            listOf(ChatArtifact(ArtifactKind.WEB_APP, "/root/apps/srv", "Server", "/root/apps/srv/app.conf", appName = "srv")),
            finder.find(listOf(ArtifactHint("/root/apps/srv/server.py", false)), null),
        )
    }

    @Test
    fun `a folder that isn't an app yet, or a bad name, gives no app card, only changed files`() {
        write("/root/apps/half/notes.txt", "x")
        write("/root/apps/Bad_Name/index.html", "<title>x</title>")
        assertEquals(
            listOf(changed("/root/apps/half/notes.txt", "/root/apps/Bad_Name/index.html")),
            finder.find(listOf(ArtifactHint("/root/apps/half/notes.txt", false), ArtifactHint("/root/apps/Bad_Name/index.html", false)), null),
        )
    }

    @Test
    fun `an app reached through a link in apps is found by the file written`() {
        write("/root/hero/index.html", "<title>Hero</title>")
        Files.createSymbolicLink(File(home, "apps/hero").toPath().also { it.parent.toFile().mkdirs() }, File("/root/hero").toPath())
        assertEquals(
            listOf(ChatArtifact(ArtifactKind.WEB_APP, "/root/apps/hero", "Hero", "/root/hero/index.html", appName = "hero")),
            finder.find(listOf(ArtifactHint("/root/hero/index.html", false)), null),
        )
    }

    @Test
    fun `terminal apps are executable files, labelled as the menu labels them`() {
        write("/root/terminal-apps/tool.py", "#!/bin/sh\n# label: My tool\n", executable = true)
        write("/root/terminal-apps/plain-one", "#!/bin/sh\n", executable = true)
        write("/root/apps/old-tool", "#!/bin/sh\n", executable = true)
        write("/root/terminal-apps/lib.py", "not executable\n")
        assertEquals(
            listOf(
                ChatArtifact(ArtifactKind.TERMINAL_APP, "/root/terminal-apps/tool.py", "My tool", "/root/terminal-apps/tool.py"),
                ChatArtifact(ArtifactKind.TERMINAL_APP, "/root/terminal-apps/plain-one", "Plain One", "/root/terminal-apps/plain-one"),
                ChatArtifact(ArtifactKind.TERMINAL_APP, "/root/apps/old-tool", "Old Tool", "/root/apps/old-tool"),
                changed("/root/terminal-apps/lib.py"),
            ),
            finder.find(
                listOf("tool.py", "plain-one", "lib.py").map { ArtifactHint("/root/terminal-apps/$it", false) } +
                    ArtifactHint("/root/apps/old-tool", false),
                null,
            ),
        )
    }

    @Test
    fun `an APK the agent wrote counts anywhere, and other files outside the app folders are only changed files`() {
        write("/root/work/app.apk", "PK")
        write("/root/work/notes.txt", "x")
        write("/opt/mynx/apps/x/index.html", "<title>x</title>")
        assertEquals(
            listOf(
                ChatArtifact(ArtifactKind.APK, "/root/work/app.apk", "app.apk", "/root/work/app.apk"),
                changed("/root/work/notes.txt"),
            ),
            finder.find(
                listOf("/root/work/app.apk", "/root/work/notes.txt", "/opt/mynx/apps/x/index.html", "/root/work/gone.apk")
                    .map { ArtifactHint(it, false) },
                null,
            ),
        )
    }

    @Test
    fun `a command's path counts only if it changed in this turn`() {
        val apk = write("/root/work/app.apk", "PK")
        write("/root/apps/demo/index.html", "<title>Demo</title>")
        apk.setLastModified(1_000_000L)
        File(home, "apps/demo").setLastModified(1_000_000L)
        File(home, "apps/demo/index.html").setLastModified(5_000_000L)
        val hints = listOf("/root/work/app.apk", "/root/apps/demo", "/root/apps/demo/index.html").map { ArtifactHint(it, true) }
        assertEquals(listOf("/root/apps/demo"), finder.find(hints, 2_000_000L).map { it.path })
        assertEquals(2, finder.find(hints, 500_000L).size)
        assertEquals(emptyList(), finder.find(hints, null), "no turn start known: commands don't count")
    }

    private fun changed(vararg files: String) =
        ChatArtifact(ArtifactKind.CHANGED_FILES, CHANGED_FILES_CARD, "Changed files", files[0], files = files.toList())

    private val web = ChatArtifact(ArtifactKind.WEB_APP, "/root/apps/demo", "Demo", "/root/apps/demo/index.html", appName = "demo")
    private val apk = ChatArtifact(ArtifactKind.APK, "/root/a.apk", "a.apk", "/root/a.apk")

    @Test
    fun `at the turn's end its cards move below its last words`() {
        val before = listOf<ChatItem>(
            ChatItem.User("u1", "make it"), ChatItem.Artifact("card-1", web), ChatItem.Agent("a1", "done"),
        )
        assertEquals(
            listOf(ChatItem.User("u1", "make it"), ChatItem.Agent("a1", "done"), ChatItem.Artifact("card-1", web), ChatItem.Artifact("card-2", apk)),
            withArtifactCards(before, listOf(web, apk), toEnd = true) { "card-2" },
        )
        val earlier = listOf<ChatItem>(ChatItem.Artifact("old", apk), ChatItem.User("u2", "again"), ChatItem.Agent("a2", "ok"))
        assertSame(earlier, withArtifactCards(earlier, emptyList(), toEnd = true) { "x" }, "no cards in the turn: the same list")
    }

    @Test
    fun `cards join the turn's end, and change in place within the turn`() {
        var n = 0
        val newId = { "card-${++n}" }
        val before = listOf<ChatItem>(ChatItem.User("u1", "make it"), ChatItem.Agent("a1", "ok"))
        val once = withArtifactCards(before, listOf(web), newId = newId)
        assertEquals(before + ChatItem.Artifact("card-1", web), once)
        assertSame(once, withArtifactCards(once, listOf(web), newId = newId), "nothing new: the same list")
        val renamed = web.copy(label = "Demo 2")
        val twice = withArtifactCards(once + ChatItem.Agent("a2", "more"), listOf(renamed, apk), newId = newId)
        assertEquals(
            before + ChatItem.Artifact("card-1", renamed) + ChatItem.Agent("a2", "more") + ChatItem.Artifact("card-2", apk),
            twice,
        )
        val next = withArtifactCards(twice + ChatItem.User("u2", "again"), listOf(web), newId = newId)
        assertEquals(ChatItem.Artifact("card-3", web), next.last(), "a new turn, a new card")
    }

    @Test
    fun `history gets each turn's cards at its end`() {
        write("/root/apps/demo/index.html", "<title>Demo</title>")
        val items = listOf(
            ChatItem.User("history:u1", "make"),
            ChatItem.Tool("history:tool:1", call("Write", "/root/apps/demo/index.html")),
            ChatItem.Agent("history:a1", "done"),
            ChatItem.User("history:u2", "chat"),
            ChatItem.Tool("history:tool:2", call("Bash", "touch ~/apps/demo/index.html", id = "b")),
        )
        val shown = withHistoryArtifacts(items, finder, "/root", "/root")
        assertEquals(items.take(3) + ChatItem.Artifact("history:artifact:1", web.copy(label = "Demo")) + items.drop(3), shown)
    }

    @Test
    fun `a card's line and buttons`() {
        assertEquals("Web app · ~/apps/demo", chatArtifactLine(web, "/root"))
        assertEquals(listOf(ArtifactAction.OPEN, ArtifactAction.ADD_TO_HOME, ArtifactAction.SHARE), chatArtifactActions(web, canInstall = false))
        val tool = ChatArtifact(ArtifactKind.TERMINAL_APP, "/root/terminal-apps/t", "T", "/root/terminal-apps/t")
        assertEquals("Terminal app · ~/terminal-apps/t", chatArtifactLine(tool, "/root"))
        assertEquals(listOf(ArtifactAction.RUN, ArtifactAction.SHARE), chatArtifactActions(tool, canInstall = true))
        assertEquals("APK · /tmp/a.apk", chatArtifactLine(apk.copy(path = "/tmp/a.apk"), "/root"))
        assertEquals(listOf(ArtifactAction.INSTALL, ArtifactAction.SHARE), chatArtifactActions(apk, canInstall = true))
        assertEquals(listOf(ArtifactAction.SHARE), chatArtifactActions(apk, canInstall = false))
        assertTrue(chatArtifactNote(apk, canInstall = false)!!.contains("Share"))
        assertEquals(null, chatArtifactNote(apk, canInstall = true))
        assertEquals(null, chatArtifactNote(web, canInstall = false))
    }

    @Test
    fun `run is the terminal apps menu's own action, in a tab`() {
        val tool = ChatArtifact(ArtifactKind.TERMINAL_APP, "/root/terminal-apps/t", "T", "/root/terminal-apps/t")
        assertEquals(runThenShell("keybar menu menu --act 'app:/root/terminal-apps/t'"), chatArtifactRunCommand(tool))
        assertEquals(runThenShell("keybar menu menu --act 'shortcut:demo'"), chatArtifactShortcutCommand(web))
    }

    @Test
    fun `a Markdown file the agent wrote counts anywhere, and is looked for as soon as it's written`() {
        write("/root/notes/plan.md", "# Plan")
        write("/root/README.MARKDOWN", "x")
        assertEquals(
            listOf(
                ChatArtifact(ArtifactKind.MARKDOWN, "/root/notes/plan.md", "plan.md", "/root/notes/plan.md"),
                ChatArtifact(ArtifactKind.MARKDOWN, "/root/README.MARKDOWN", "README.MARKDOWN", "/root/README.MARKDOWN"),
            ),
            finder.find(
                listOf("/root/notes/plan.md", "/root/README.MARKDOWN", "/root/gone.md").map { ArtifactHint(it, false) },
                null,
            ),
        )
        assertTrue(ArtifactHint("/root/notes/plan.md", false).direct("/root"))
        assertEquals(emptyList(), hints(call("Bash", "cat /root/notes/plan.md")), "a command's .md is no hint")
    }

    @Test
    fun `a Markdown card's line and buttons`() {
        val md = ChatArtifact(ArtifactKind.MARKDOWN, "/root/notes/plan.md", "plan.md", "/root/notes/plan.md")
        assertEquals("Markdown · ~/notes/plan.md", chatArtifactLine(md, "/root"))
        assertEquals(listOf(ArtifactAction.VIEW, ArtifactAction.COPY, ArtifactAction.SHARE), chatArtifactActions(md, canInstall = true))
        assertEquals("Copy", ArtifactAction.COPY.label)
    }

    @Test
    fun `an image the agent made counts anywhere, written or named in a command that changed it`() {
        val plot = write("/root/work/plot.png", "png")
        val old = write("/root/work/old.JPG", "jpg")
        write("/root/apps/demo/index.html", "<title>Demo</title>")
        write("/root/apps/demo/logo.png", "png")
        plot.setLastModified(5_000_000L)
        old.setLastModified(1_000_000L)
        assertEquals(
            listOf(
                ArtifactHint("/root/work/plot.png", true),
                ArtifactHint("/root/work/old.JPG", true),
            ),
            hints(call("Bash", "python3 plot.py -o plot.png && cp old.JPG /tmp/x.txt")),
        )
        assertTrue(ArtifactHint("/root/work/plot.png", false).direct("/root"))
        val image = ChatArtifact(ArtifactKind.IMAGE, "/root/work/plot.png", "plot.png", "/root/work/plot.png", version = 5_000_000L)
        assertEquals(
            listOf(image),
            finder.find(listOf("/root/work/plot.png", "/root/work/old.JPG").map { ArtifactHint(it, true) }, 2_000_000L),
        )
        assertEquals(listOf(image), finder.find(listOf(ArtifactHint("/root/work/plot.png", false)), null), "written: no turn start needed")
        assertEquals(
            listOf(ArtifactKind.WEB_APP),
            finder.find(listOf(ArtifactHint("/root/apps/demo/logo.png", false)), null).map { it.kind },
            "an app's picture is the app's card",
        )
        assertEquals(emptyList(), finder.find(listOf(ArtifactHint("/root/work/gone.png", false)), null))
    }

    @Test
    fun `an image drawn again changes its card`() {
        val image = ChatArtifact(ArtifactKind.IMAGE, "/root/plot.png", "plot.png", "/root/plot.png", version = 1L)
        val items = listOf<ChatItem>(ChatItem.User("u1", "plot"), ChatItem.Artifact("card-1", image))
        val redrawn = image.copy(version = 2L)
        assertEquals(listOf(ChatItem.User("u1", "plot"), ChatItem.Artifact("card-1", redrawn)), withArtifactCards(items, listOf(redrawn)) { "new" })
    }

    @Test
    fun `an image card's line and buttons`() {
        val image = ChatArtifact(ArtifactKind.IMAGE, "/root/work/plot.png", "plot.png", "/root/work/plot.png", version = 1L)
        assertEquals("Image · ~/work/plot.png", chatArtifactLine(image, "/root"))
        assertEquals(listOf(ArtifactAction.VIEW, ArtifactAction.SHARE), chatArtifactActions(image, canInstall = true))
        assertEquals("View", ArtifactAction.VIEW.label)
    }

    @Test
    fun `audio and video the agent made count anywhere, written or named in a command that changed them`() {
        val song = write("/root/work/song.MP3", "id3")
        val clip = write("/root/work/clip.webm", "webm")
        val old = write("/root/work/old.wav", "riff")
        write("/root/apps/demo/index.html", "<title>Demo</title>")
        write("/root/apps/demo/beep.ogg", "ogg")
        song.setLastModified(5_000_000L)
        clip.setLastModified(5_000_000L)
        old.setLastModified(1_000_000L)
        assertEquals(
            listOf("/root/work/song.MP3", "/root/work/clip.webm", "/root/work/old.wav").map { ArtifactHint(it, true) },
            hints(call("Bash", "ffmpeg -i in.mid song.MP3 clip.webm; ls old.wav notes.txt")).filter { it.path != "/root/work/in.mid" },
        )
        assertTrue(ArtifactHint("/root/work/in.mid", true) in hints(call("Bash", "timidity in.mid")))
        assertTrue(ArtifactHint("/root/work/song.mp3", false).direct("/root"))
        val audio = ChatArtifact(ArtifactKind.AUDIO, "/root/work/song.MP3", "song.MP3", "/root/work/song.MP3", version = 5_000_000L)
        val video = ChatArtifact(ArtifactKind.VIDEO, "/root/work/clip.webm", "clip.webm", "/root/work/clip.webm", version = 5_000_000L)
        assertEquals(
            listOf(audio, video),
            finder.find(listOf("/root/work/song.MP3", "/root/work/clip.webm", "/root/work/old.wav").map { ArtifactHint(it, true) }, 2_000_000L),
        )
        assertEquals(listOf(video), finder.find(listOf(ArtifactHint("/root/work/clip.webm", false)), null), "written: no turn start needed")
        assertEquals(
            listOf(ArtifactKind.WEB_APP),
            finder.find(listOf(ArtifactHint("/root/apps/demo/beep.ogg", false)), null).map { it.kind },
            "an app's sound is the app's card",
        )
        for (name in listOf("a.mp3", "a.m4a", "a.aac", "a.ogg", "a.opus", "a.flac", "a.wav", "a.mid", "a.midi", "a.oga")) assertTrue(chatAudioFile(name), name)
        for (name in listOf("a.mp4", "a.webm", "a.mkv", "a.3gp", "a.mov", "a.m4v")) assertTrue(chatVideoFile(name), name)
        assertEquals(false, chatAudioFile("mp3"))
        assertEquals(false, chatVideoFile("a.mp3"))
    }

    @Test
    fun `audio and video cards' lines and buttons`() {
        val audio = ChatArtifact(ArtifactKind.AUDIO, "/root/work/song.mp3", "song.mp3", "/root/work/song.mp3", version = 1L)
        val video = ChatArtifact(ArtifactKind.VIDEO, "/tmp/clip.mp4", "clip.mp4", "/tmp/clip.mp4", version = 1L)
        assertEquals("Audio · ~/work/song.mp3", chatArtifactLine(audio, "/root"))
        assertEquals("Video · /tmp/clip.mp4", chatArtifactLine(video, "/root"))
        assertEquals(listOf(ArtifactAction.PLAY, ArtifactAction.SHARE), chatArtifactActions(audio, canInstall = true))
        assertEquals(listOf(ArtifactAction.PLAY, ArtifactAction.SHARE), chatArtifactActions(video, canInstall = false))
        assertEquals("Play", ArtifactAction.PLAY.label)
    }

    @Test
    fun `files written in a turn without a card of their own share one changed files card, in order, each once`() {
        write("/root/work/main.py", "print(1)")
        write("/root/work/util.py", "x = 1")
        write("/root/work/plan.md", "# Plan")
        write("/root/work/plot.png", "png")
        val found = finder.find(
            hints(
                call("Write", "main.py"),
                call("Edit", "/root/work/util.py"),
                call("Edit", "main.py", id = "again"),
                call("Write", "plan.md"),
                call("Write", "plot.png"),
                call("Write", "gone.txt"),
                call("Bash", "touch other.py"),
            ),
            null,
        )
        assertEquals(listOf(ArtifactKind.MARKDOWN, ArtifactKind.IMAGE, ArtifactKind.CHANGED_FILES), found.map { it.kind })
        assertEquals(
            changed("/root/work/main.py", "/root/work/util.py").copy(
                fileTools = mapOf("/root/work/main.py" to listOf("t-main.py", "again"), "/root/work/util.py" to listOf("t-/root/work/util.py")),
            ),
            found.last(),
        )
        assertTrue(ArtifactHint("/root/work/main.py", false).direct("/root"), "written files are looked for at once")
    }

    @Test
    fun `a file written again in the turn is one hint with each call's ID`() {
        assertEquals(
            listOf(ArtifactHint("/root/work/a.py", false, toolIds = listOf("w1", "e1", "e2"))),
            hints(call("Write", "a.py", id = "w1"), call("Edit", "/root/work/a.py", id = "e1"), call("Edit", "./a.py", id = "e2")),
        )
        assertEquals(
            listOf(ArtifactHint("/root/work/out.txt", true, written = true)),
            hints(call("Bash", "echo hi > out.txt")),
            "a command's file has no edits to diff",
        )
    }

    @Test
    fun `a changed file a command wrote too has no tool calls to diff`() {
        write("/root/work/a.py", "x")
        write("/root/work/b.py", "y")
        val found = finder.find(
            hints(call("Edit", "a.py", id = "e1"), call("Bash", "echo more >> a.py"), call("Bash", "echo y > b.py")),
            2_000L,
        )
        assertEquals(changed("/root/work/a.py", "/root/work/b.py"), found.single())
    }

    @Test
    fun `a changed file's row carries the tool calls that wrote it`() {
        val card = changed("/root/work/a.py", "/root/b.txt").copy(fileTools = mapOf("/root/work/a.py" to listOf("w1", "e1")))
        assertEquals(
            listOf(
                ChangedFileRow("/root/work/a.py", "a.py", "~/work", toolIds = listOf("w1", "e1")),
                ChangedFileRow("/root/b.txt", "b.txt", "~"),
            ),
            changedFileRows(card, "/root"),
        )
    }

    @Test
    fun `the changed files card changes in place within its turn`() {
        val one = changed("/root/a.py")
        val items = withArtifactCards(listOf(ChatItem.User("u1", "go")), listOf(one)) { "card-1" }
        val two = changed("/root/a.py", "/root/b.py")
        assertEquals(listOf(ChatItem.User("u1", "go"), ChatItem.Artifact("card-1", two)), withArtifactCards(items, listOf(two)) { "new" })
    }

    @Test
    fun `a changed files card's line, buttons and rows`() {
        val card = changed("/root/work/a.py", "/etc/hosts")
        assertEquals("Changed files · 2 files", chatArtifactLine(card, "/root"))
        assertEquals("Changed files · 1 file", chatArtifactLine(changed("/root/a.py"), "/root"))
        assertEquals(emptyList(), chatArtifactActions(card, canInstall = true), "its rows open the files")
        assertEquals(null, chatArtifactNote(card, canInstall = false))
        assertEquals(
            listOf(ChangedFileRow("/root/work/a.py", "a.py", "~/work"), ChangedFileRow("/etc/hosts", "hosts", "/etc")),
            changedFileRows(card, "/root"),
        )
        assertEquals(listOf(ChangedFileRow("/root/x", "x", "~")), changedFileRows(changed("/root/x"), "/root"))
        assertEquals(listOf(ChangedFileRow("/x", "x", "/")), changedFileRows(changed("/x"), "/root"))
    }
}
