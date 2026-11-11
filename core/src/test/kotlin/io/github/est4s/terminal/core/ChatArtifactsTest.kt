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
                ArtifactHint("/root/apps/demo/index.html", false),
                ArtifactHint("/root/work/b.apk", false),
                ArtifactHint("/root/apps/x/main.js", false),
                ArtifactHint("/root/notes.ipynb", false),
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
                ArtifactHint("/root/apps/demo/index.html", true),
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
    fun `a folder that isn't an app yet, or a bad name, gives no card`() {
        write("/root/apps/half/notes.txt", "x")
        write("/root/apps/Bad_Name/index.html", "<title>x</title>")
        assertEquals(
            emptyList(),
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
            ),
            finder.find(
                listOf("tool.py", "plain-one", "lib.py").map { ArtifactHint("/root/terminal-apps/$it", false) } +
                    ArtifactHint("/root/apps/old-tool", false),
                null,
            ),
        )
    }

    @Test
    fun `an APK the agent wrote counts anywhere, and nothing else outside the app folders`() {
        write("/root/work/app.apk", "PK")
        write("/root/work/notes.txt", "x")
        write("/opt/mynx/apps/x/index.html", "<title>x</title>")
        assertEquals(
            listOf(ChatArtifact(ArtifactKind.APK, "/root/work/app.apk", "app.apk", "/root/work/app.apk")),
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

    private val web = ChatArtifact(ArtifactKind.WEB_APP, "/root/apps/demo", "Demo", "/root/apps/demo/index.html", appName = "demo")
    private val apk = ChatArtifact(ArtifactKind.APK, "/root/a.apk", "a.apk", "/root/a.apk")

    @Test
    fun `cards join the turn's end, and change in place within the turn`() {
        var n = 0
        val newId = { "card-${++n}" }
        val before = listOf<ChatItem>(ChatItem.User("u1", "make it"), ChatItem.Agent("a1", "ok"))
        val once = withArtifactCards(before, listOf(web), newId)
        assertEquals(before + ChatItem.Artifact("card-1", web), once)
        assertSame(once, withArtifactCards(once, listOf(web), newId), "nothing new: the same list")
        val renamed = web.copy(label = "Demo 2")
        val twice = withArtifactCards(once + ChatItem.Agent("a2", "more"), listOf(renamed, apk), newId)
        assertEquals(
            before + ChatItem.Artifact("card-1", renamed) + ChatItem.Agent("a2", "more") + ChatItem.Artifact("card-2", apk),
            twice,
        )
        val next = withArtifactCards(twice + ChatItem.User("u2", "again"), listOf(web), newId)
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
        assertEquals(listOf(ArtifactAction.COPY, ArtifactAction.SHARE), chatArtifactActions(md, canInstall = true))
        assertEquals("Copy", ArtifactAction.COPY.label)
    }
}
