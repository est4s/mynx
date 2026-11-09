package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Which menu [HomeMenus.items] starts at. */
const val HOME_MAIN = "main"

private const val SERVICE_MENU = "service:"
private const val LABEL_MAX = 20
private const val LABEL_LINES = 5
private const val LABEL_BYTES = 4096
private val GAME_DIRS = listOf("/opt/neon-games", "games")
private val AGENTS = listOf("claude" to "Claude Code", "codex" to "Codex", "gemini" to "Gemini CLI")
// The one agent touch mode opens as a chat; the others run in a tab.
private const val CHAT_AGENT = "claude"
private val AGENT_DIRS = listOf(".local/bin", "/usr/local/bin", "/usr/bin", "/bin")
private val LABEL_LINE = Regex("""^#\s*label:\s*(.*\S)""")
private val BUILT_IN_MENU = listOf(
    "Shell" to "shell", "AI agents" to "agents", "Services" to "services", "Apps" to "apps",
    "Terminal apps" to "terminal-apps", "Games" to "games", "Files" to "files", "Settings" to "settings",
)
private val EXECUTE_BITS = setOf(
    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE,
)

/** What tapping a [HomeItem] does. */
sealed interface HomeAction {
    /** Opens the list [HomeMenus.items] gives for [name]. */
    data class Menu(val name: String) : HomeAction
    /** A new tab running [command]. */
    data class Tab(val command: List<String>) : HomeAction
    /** The Chat screen, with the agent [agent]. */
    data class Chat(val agent: String) : HomeAction
    /** The web app [name]'s screen. */
    data class App(val name: String) : HomeAction
    /** A new tab with a shell. */
    data object Shell : HomeAction
    /** A line that says something, like "(no apps yet)". */
    data object None : HomeAction
    /** [op] on the background service [name]: [ServiceManager.act]. */
    data class Service(val name: String, val op: ServiceOp) : HomeAction
}

/**
 * A background service as Home lists it, from [ServiceManager.homeServices].
 * [state] is `mynx service list`'s word (so one waiting to restart is
 * "stopped", as in the terminal's list); [saved] is false for an unsaved
 * one, whose [autostart] means nothing.
 */
data class HomeService(val name: String, val state: String, val saved: Boolean, val autostart: Boolean)

data class HomeItem(val label: String, val action: HomeAction)

/**
 * Touch mode's Home: the launcher menu's lists (`tools/bin/menu`'s
 * `menu_items`), from the same files, and what each item does. What runs a
 * program goes through `menu --act`, so both views run the same thing.
 * The main list leaves out Exit: closing the menu's tab means nothing here.
 * [servicesActive] is how many background services run and [services] is
 * the list as it was when this was made, so a change needs a new one
 * (the snapshot is what lets it leave the main thread, unlike [Services]).
 * Reads files only: any thread may use it.
 */
class HomeMenus(
    private val apps: WebApps,
    private val servicesActive: Int = 0,
    private val services: List<HomeService> = emptyList(),
) {
    private val guestHome get() = apps.guestHome

    /** The list called [menu] ([HOME_MAIN], or a [HomeAction.Menu]'s name); empty if there's none. */
    fun items(menu: String): List<HomeItem> = when (menu) {
        HOME_MAIN -> main()
        "apps" -> webApps()
        "shortcuts" -> byLabel(apps.list()).map { HomeItem(it.label, act("shortcut:${it.name}")) }
        "terminal-apps" -> terminalApps()
        "games" -> games()
        "agents" -> AGENTS.map { (name, title) ->
            when {
                !installed(name) -> HomeItem("$title (install)", act("agent:$name"))
                name == CHAT_AGENT -> HomeItem(title, HomeAction.Chat(name))
                else -> HomeItem(title, act("agent:$name"))
            }
        }
        "settings" -> listOf(
            HomeItem("App settings", act("settings")),
            HomeItem("System", HomeAction.Menu("system")),
            HomeItem("Help", HomeAction.Menu("help")),
        )
        "system" -> listOf(HomeItem("Update all", act("update")), HomeItem("System info", act("info"))) +
            if (gitHubReady()) listOf(HomeItem("GitHub setup", act("github"))) else emptyList()
        "help" -> listOf(
            HomeItem("Getting started", act("welcome")),
            HomeItem("Release notes", act("notes")),
            HomeItem("Report a bug", act("report")),
            HomeItem("About", act("about")),
        )
        "services" -> serviceRows()
        else -> if (menu.startsWith(SERVICE_MENU)) serviceItems(menu.removePrefix(SERVICE_MENU)) else emptyList()
    }

    private fun act(action: String) = HomeAction.Tab(runThenShell("keybar menu menu --act " + shellQuote(action)))

    private fun serviceRows(): List<HomeItem> =
        services.map { HomeItem("${it.name}${if (it.saved) "" else "*"} ${it.state}", HomeAction.Menu(SERVICE_MENU + it.name)) }
            .ifEmpty { listOf(HomeItem("(no services yet)", act("svchelp"))) }

    private fun serviceItems(name: String): List<HomeItem> {
        val service = services.find { it.name == name }
            ?: return listOf(HomeItem("(no service named $name)", HomeAction.None))
        fun item(label: String, op: ServiceOp) = HomeItem(label, HomeAction.Service(name, op))
        val running = service.state == ServiceState.RUNNING.word || service.state == ServiceState.STOPPING.word
        return listOfNotNull(
            item("Open", ServiceOp.OPEN),
            if (running) item("Stop", ServiceOp.STOP) else item("Start", ServiceOp.START),
            item("Restart", ServiceOp.RESTART),
            HomeItem("Log", act("svclog:$name")),
            when {
                !service.saved -> null
                service.autostart -> item("Autostart: on", ServiceOp.AUTOSTART_OFF)
                else -> item("Autostart: off", ServiceOp.AUTOSTART_ON)
            },
        )
    }

    private fun mainEntries(): List<Pair<String, String>> {
        for (guest in listOf("$guestHome/.config/mynx/menu.conf", "$TOOLS_MOUNT/menu.conf")) {
            val text = apps.host(guest)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } ?: continue
            parseMenuFile(text).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return BUILT_IN_MENU
    }

    private fun main(): List<HomeItem> {
        val entries = mainEntries()
        val items = entries.mapNotNull { (label, action) -> item(label, action) }.toMutableList()
        if (!gitHubReady()) {
            val agents = items.indexOfFirst { it.action == HomeAction.Menu("agents") }
            items.add(if (agents >= 0) agents + 1 else items.size, HomeItem("Set up GitHub", act("github")))
        }
        val notice = File(apps.rootfs, UPDATE_NOTICE)
        if (notice.isFile && notice.length() > 0) items.add(0, HomeItem("Update available", act("appupdate")))
        return items
    }

    private fun item(label: String, action: String): HomeItem? = when (action) {
        "shell" -> HomeItem(label, HomeAction.Shell)
        "exit" -> null
        "services" -> HomeItem(
            if (servicesActive > 0) "$label ● $servicesActive" else label,
            HomeAction.Menu("services"),
        )
        "apps", "terminal-apps", "games", "settings", "agents", "system", "help" -> HomeItem(label, HomeAction.Menu(action))
        "app-settings" -> HomeItem(label, act("settings"))
        "files", "welcome" -> HomeItem(label, act(action))
        else -> HomeItem(label, act("cmd:" + action.removePrefix("run ")))
    }

    private fun byLabel(list: List<WebApp>) = list.sortedWith(compareBy({ it.label.uppercase() }, { it.label }))

    private fun webApps(): List<HomeItem> {
        val found = byLabel(apps.list()).map { HomeItem(it.label, HomeAction.App(it.name)) }
        val items = found.ifEmpty { listOf(HomeItem("(no apps yet)", HomeAction.None)) }
        val keepTerminalApps = mainEntries().none { it.second == "terminal-apps" }
        return items +
            (if (keepTerminalApps) listOf(HomeItem("Terminal apps", HomeAction.Menu("terminal-apps"))) else emptyList()) +
            (if (found.isEmpty()) emptyList() else listOf(HomeItem("Add to home screen…", HomeAction.Menu("shortcuts"))))
    }

    private fun programs(folder: String): List<Pair<String, File>> {
        val names = apps.host(folder)?.list()?.sorted() ?: return emptyList()
        return names.mapNotNull { name ->
            val guest = "$folder/$name"
            val file = apps.resolve(guest)?.let(apps::host) ?: return@mapNotNull null
            if (file.isFile && executable(file)) guest to file else null
        }
    }

    // By the mode bits: on Android, canExecute() asks the kernel, which
    // refuses every file in an app's data.
    private fun executable(file: File) = runCatching {
        Files.getPosixFilePermissions(file.toPath()).any { it in EXECUTE_BITS }
    }.getOrDefault(false)

    private fun terminalApps(): List<HomeItem> {
        val found = listOf("$TOOLS_MOUNT/terminal-apps", "$guestHome/terminal-apps", "$guestHome/apps")
            .flatMap(::programs)
            .map { (guest, file) -> HomeItem(appLabel(guest, file), act("app:$guest")) }
            .sortedWith(compareBy({ it.label.uppercase() }, { it.label }))
        return found.ifEmpty { listOf(HomeItem("(no terminal apps yet)", HomeAction.None)) }
    }

    private fun games(): List<HomeItem> {
        val found = GAME_DIRS.map { if (it.startsWith("/")) it else "$guestHome/$it" }
            .flatMap(::programs)
            .map { (guest, _) -> HomeItem(gameLabel(guest), act("game:$guest")) }
        return found.ifEmpty { listOf(HomeItem("(no games yet)", HomeAction.None)) }
    }

    private fun installed(command: String) = AGENT_DIRS.any { dir ->
        val folder = if (dir.startsWith("/")) dir else "$guestHome/$dir"
        apps.resolve("$folder/$command")?.let(apps::host)?.isFile == true
    }

    private fun gitHubReady(): Boolean {
        val hosts = apps.host("$guestHome/.config/gh/hosts.yml") ?: return false
        val config = apps.host("$guestHome/.gitconfig")?.takeIf { it.isFile } ?: return false
        return hosts.length() > 0 && gitEmailSet(runCatching { config.readText() }.getOrDefault(""))
    }

    private fun appLabel(guest: String, file: File): String {
        val head = ByteArray(LABEL_BYTES)
        val size = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0).coerceAtLeast(0)
        return head.decodeToString(0, size).lineSequence().take(LABEL_LINES)
            .firstNotNullOfOrNull { LABEL_LINE.find(it)?.groupValues?.get(1) }
            ?.take(LABEL_MAX) ?: gameLabel(guest)
    }
}

/** `~/.gitconfig` has `email` in its `[user]` section. */
private fun gitEmailSet(config: String): Boolean {
    var inUser = false
    for (raw in config.lines()) {
        val line = raw.trim()
        if (line.startsWith("[")) inUser = line.lowercase() == "[user]"
        else if (inUser && line.lowercase().substringBefore('=').trim() == "email" && line.substringAfter('=', "").isNotBlank()) return true
    }
    return false
}

/** As the launcher menu names a game after its file: `neon-flap.py` → "Neon Flap". */
private fun gameLabel(path: String): String =
    path.substringAfterLast('/').split('-', '_', '.')
        .filter { it.isNotEmpty() && it != "py" && it != "sh" }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        .take(LABEL_MAX)

/** The items of a `menu.conf`, as (label, action); lines the launcher menu would refuse are left out. */
private fun parseMenuFile(text: String): List<Pair<String, String>> = text.lines().mapNotNull { raw ->
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
    val label = line.substringBefore('=').trim().take(LABEL_MAX)
    val action = line.substringAfter('=').trim()
    val known = action in MENU_ACTIONS || (action.startsWith("run ") && action.length > 4)
    if (label.isEmpty() || !known) null else label to action
}

private val MENU_ACTIONS = setOf(
    "shell", "files", "apps", "terminal-apps", "games", "settings", "app-settings", "agents", "services",
    "system", "help", "welcome", "exit",
)
