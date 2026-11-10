package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTest {
    private val top = Files.createTempDirectory("home").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        deleteTree(top)
    }

    private fun menus(services: Int = 0, list: List<HomeService> = emptyList()) =
        HomeMenus(WebApps(home, rootfs.path, tools), services, list)
    private fun act(action: String) = HomeAction.Tab(runThenShell("keybar menu menu --act " + shellQuote(action)))
    private fun labels(menu: String, services: Int = 0) = menus(services).items(menu).map { it.label }

    private fun write(file: File, text: String, executable: Boolean = false) {
        file.parentFile.mkdirs()
        file.writeText(text)
        file.setExecutable(executable)
    }

    private fun userMenu(text: String) = write(File(home, ".config/mynx/menu.conf"), text)

    private fun gitHubReady() {
        write(File(home, ".config/gh/hosts.yml"), "github.com:\n")
        write(File(home, ".gitconfig"), "[user]\n\temail = a@b\n")
    }

    @Test
    fun `the menu file reads as the launcher menu reads it, without Exit`() {
        gitHubReady()
        File(fixturesDir(), "menu.conf").copyTo(File(home, ".config/mynx/menu.conf"))
        assertEquals(
            listOf(
                HomeItem("Shell", HomeAction.Shell),
                HomeItem("Top", act("cmd:htop -d 5")),
                HomeItem("Tools", HomeAction.Menu("apps")),
                HomeItem("Way too long a label", act("files")),
            ),
            menus().items("main"),
        )
    }

    @Test
    fun `each menu action goes where the launcher menu's does`() {
        gitHubReady()
        userMenu("a = terminal-apps\nb = games\nc = settings\nd = app-settings\ne = agents\nf = services\ng = system\nh = help\ni = welcome\n")
        assertEquals(
            listOf(
                HomeAction.Menu("terminal-apps"), HomeAction.Menu("games"), HomeAction.Menu("settings"),
                act("settings"), HomeAction.Menu("agents"), HomeAction.Menu("services"),
                HomeAction.Menu("system"), HomeAction.Menu("help"), act("welcome"),
            ),
            menus().items("main").map { it.action },
        )
    }

    @Test
    fun `the tools' menu is used when the user has none, or one with no good line`() {
        gitHubReady()
        write(File(tools, "menu.conf"), "From tools = files\n")
        assertEquals(listOf("From tools"), labels("main"))
        userMenu("nothing good here\n")
        assertEquals(listOf("From tools"), labels("main"))
    }

    @Test
    fun `with no menu file at all it has the launcher's built-in items`() {
        gitHubReady()
        assertEquals(
            listOf("Shell", "AI agents", "Services", "Apps", "Terminal apps", "Games", "Files", "Settings"),
            labels("main"),
        )
    }

    @Test
    fun `an update comes first and running services are counted`() {
        gitHubReady()
        userMenu("Services = services\n")
        write(File(rootfs, UPDATE_NOTICE), "0.9.0\n")
        val items = menus(services = 2).items("main")
        assertEquals(listOf("Update available", "Services ● 2"), items.map { it.label })
        assertEquals(act("appupdate"), items[0].action)
    }

    @Test
    fun `an empty update notice is no update`() {
        gitHubReady()
        userMenu("Files = files\n")
        write(File(rootfs, UPDATE_NOTICE), "")
        assertEquals(listOf("Files"), labels("main"))
    }

    @Test
    fun `GitHub setup is offered after AI agents until it is done, then in System`() {
        userMenu("Agents = agents\nFiles = files\n")
        assertEquals(listOf("Agents", "Set up GitHub", "Files"), labels("main"))
        assertEquals(listOf("Update all", "System info"), labels("system"))
        gitHubReady()
        assertEquals(listOf("Agents", "Files"), labels("main"))
        assertEquals(HomeItem("GitHub setup", act("github")), menus().items("system").last())
    }

    @Test
    fun `GitHub setup comes last with no AI agents item, and needs git's email`() {
        userMenu("Files = files\n")
        write(File(home, ".config/gh/hosts.yml"), "github.com:\n")
        write(File(home, ".gitconfig"), "[core]\n\temail = not-this-one\n")
        assertEquals(listOf("Files", "Set up GitHub"), labels("main"))
    }

    private fun webApp(name: String, title: String) = write(File(home, "apps/$name/index.html"), "<title>$title</title>")

    @Test
    fun `apps are sorted by label and open natively`() {
        userMenu("Terminal apps = terminal-apps\n")
        webApp("zed", "alpha")
        webApp("abc", "Zulu")
        val items = menus().items("apps")
        assertEquals(listOf("alpha", "Zulu", "Add to home screen…"), items.map { it.label })
        assertEquals(HomeAction.App("zed"), items[0].action)
        assertEquals(HomeAction.Menu("shortcuts"), items[2].action)
        assertEquals(listOf(HomeItem("alpha", act("shortcut:zed")), HomeItem("Zulu", act("shortcut:abc"))), menus().items("shortcuts"))
    }

    @Test
    fun `no apps says so, and a menu file without terminal apps keeps them a tap away`() {
        userMenu("Apps = apps\n")
        assertEquals(
            listOf(HomeItem("(no apps yet)", HomeAction.None), HomeItem("Terminal apps", HomeAction.Menu("terminal-apps"))),
            menus().items("apps"),
        )
    }

    @Test
    fun `terminal apps need the executable bit, use their label line and sort by label`() {
        write(File(tools, "terminal-apps/tuner"), "#!/bin/sh\n# label: Guitar tuner\n", executable = true)
        write(File(home, "terminal-apps/my-tool.py"), "#!/bin/sh\n", executable = true)
        write(File(home, "terminal-apps/module.py"), "not executable\n")
        assertEquals(
            listOf(
                HomeItem("Guitar tuner", act("app:/opt/mynx/terminal-apps/tuner")),
                HomeItem("My Tool", act("app:/root/terminal-apps/my-tool.py")),
            ),
            menus().items("terminal-apps"),
        )
    }

    @Test
    fun `a label is only looked for near the top of the file`() {
        write(File(home, "terminal-apps/late"), "1\n2\n3\n4\n5\n# label: Too late\n", executable = true)
        assertEquals(listOf("Late"), labels("terminal-apps"))
    }

    @Test
    fun `games are named after their files, and a guest link counts`() {
        write(File(rootfs, "opt/neon-games/neon-flap.py"), "x", executable = true)
        write(File(rootfs, "opt/my/real-game"), "x", executable = true)
        File(home, "games").mkdirs()
        Files.createSymbolicLink(File(home, "games/linked_game.sh").toPath(), File("/opt/my/real-game").toPath())
        assertEquals(
            listOf(
                HomeItem("Neon Flap", act("game:/opt/neon-games/neon-flap.py")),
                HomeItem("Linked Game", act("game:/root/games/linked_game.sh")),
            ),
            menus().items("games"),
        )
    }

    @Test
    fun `empty lists say so`() {
        assertEquals(listOf(HomeItem("(no games yet)", HomeAction.None)), menus().items("games"))
        assertEquals(listOf(HomeItem("(no terminal apps yet)", HomeAction.None)), menus().items("terminal-apps"))
    }

    @Test
    fun `installed Claude Code opens the Chat screen, other agents stay terminal`() {
        write(File(home, ".local/bin/claude"), "x", executable = true)
        write(File(rootfs, "usr/local/bin/gemini"), "x", executable = true)
        assertEquals(
            listOf(
                HomeItem("Claude Code", HomeAction.Chat("claude")),
                HomeItem("Codex (install)", act("agent:codex")),
                HomeItem("Gemini CLI", act("agent:gemini")),
            ),
            menus().items("agents"),
        )
    }

    @Test
    fun `Claude Code not installed stays the install row`() {
        assertEquals(HomeItem("Claude Code (install)", act("agent:claude")), menus().items("agents").first())
    }

    @Test
    fun `claude counts as installed through its real binary's link`() {
        val apps = WebApps(home, rootfs.path, tools)
        assertEquals(null, agentBinary(apps, "claude"))
        write(File(home, ".local/share/claude/v1/claude"), "x", executable = true)
        File(home, ".local/bin").mkdirs()
        // A link with Debian's absolute path: only Debian can follow it.
        java.nio.file.Files.createSymbolicLink(File(home, ".local/bin/claude").toPath(), File("/root/.local/share/claude/v1/claude").toPath())
        assertEquals("/root/.local/bin/claude", agentBinary(apps, "claude"))
    }

    @Test
    fun `an agent found anywhere the menu looks is found at that path, the home folder first`() {
        val apps = WebApps(home, rootfs.path, tools)
        write(File(rootfs, "usr/local/bin/claude"), "x", executable = true)
        assertEquals("/usr/local/bin/claude", agentBinary(apps, "claude"))
        write(File(home, ".local/bin/claude"), "x", executable = true)
        assertEquals("/root/.local/bin/claude", agentBinary(apps, "claude"))
    }

    @Test
    fun `settings and help are the launcher's`() {
        assertEquals(
            listOf(HomeItem("App settings", act("settings")), HomeItem("System", HomeAction.Menu("system")), HomeItem("Help", HomeAction.Menu("help"))),
            menus().items("settings"),
        )
        assertEquals(
            listOf(
                HomeItem("Getting started", act("welcome")), HomeItem("Release notes", act("notes")),
                HomeItem("Report a bug", act("report")), HomeItem("About", act("about")),
            ),
            menus().items("help"),
        )
    }

    @Test
    fun `system runs its items in a tab, and an unknown menu is empty`() {
        gitHubReady()
        assertEquals(act("update"), menus().items("system")[0].action)
        assertEquals(act("info"), menus().items("system")[1].action)
        assertEquals(emptyList(), menus().items("nope"))
    }

    private val web = HomeService("web", "running", saved = true, autostart = true)
    private val db = HomeService("database-server-long", "stopped", saved = true, autostart = false)
    private val tab = HomeService("tab", "stopping", saved = false, autostart = false)
    private fun service(name: String, op: ServiceOp) = HomeAction.Service(name, op)

    @Test
    fun `Services lists each service with its state, whole names, unsaved ones marked`() {
        assertEquals(
            listOf(
                HomeItem("web running", HomeAction.Menu("service:web")),
                HomeItem("database-server-long stopped", HomeAction.Menu("service:database-server-long")),
                HomeItem("tab* stopping", HomeAction.Menu("service:tab")),
            ),
            menus(list = listOf(web, db, tab)).items("services"),
        )
    }

    @Test
    fun `with no services the list says so and opens the how-to, as the terminal's does`() {
        assertEquals(listOf(HomeItem("(no services yet)", act("svchelp"))), menus().items("services"))
    }

    @Test
    fun `a running service offers what the launcher menu's does, in its order`() {
        assertEquals(
            listOf(
                HomeItem("Open", service("web", ServiceOp.OPEN)),
                HomeItem("Stop", service("web", ServiceOp.STOP)),
                HomeItem("Restart", service("web", ServiceOp.RESTART)),
                HomeItem("Log", act("svclog:web")),
                HomeItem("Autostart: on", service("web", ServiceOp.AUTOSTART_OFF)),
            ),
            menus(list = listOf(web)).items("service:web"),
        )
    }

    @Test
    fun `a stopped service offers Start, and its autostart turns on`() {
        val name = db.name
        assertEquals(
            listOf(
                HomeItem("Open", service(name, ServiceOp.OPEN)),
                HomeItem("Start", service(name, ServiceOp.START)),
                HomeItem("Restart", service(name, ServiceOp.RESTART)),
                HomeItem("Log", act("svclog:$name")),
                HomeItem("Autostart: off", service(name, ServiceOp.AUTOSTART_ON)),
            ),
            menus(list = listOf(db)).items("service:$name"),
        )
    }

    @Test
    fun `one stopping still offers Stop, and an unsaved one has no Autostart`() {
        assertEquals(listOf("Open", "Stop", "Restart", "Log"), menus(list = listOf(tab)).items("service:tab").map { it.label })
    }

    @Test
    fun `a failed service offers Start`() {
        val failed = web.copy(state = "failed")
        assertEquals("Start", menus(list = listOf(failed)).items("service:web")[1].label)
    }

    @Test
    fun `a service removed while its list was open is a line that does nothing`() {
        assertEquals(
            listOf(HomeItem("(no service named gone)", HomeAction.None)),
            menus(list = listOf(web)).items("service:gone"),
        )
    }
}
