package io.github.est4s.terminal.core

const val CHAT_HOME_CONTINUE_MAX = 3
const val DRAWER_CHATS_MAX = 5

/** One of the drawer's Linux items: Home's main list item; [opensList] when it's a [HomeAction.Menu] (drawn with ›); [logo], its picture. */
data class DrawerItem(val label: String, val action: HomeAction, val opensList: Boolean, val logo: HomeLogo? = null)

/**
 * What touch view's chat home and its drawer show (docs/TOUCH-DESIGN.md).
 * [continueRows]: the live chats (working or waiting for you), in history
 * order, at most [CHAT_HOME_CONTINUE_MAX]. [drawerPinned]: the pinned ones,
 * all of them, in history order. [drawerChats] (its Recents): the others,
 * live ones first, then the rest, each in history order, at most
 * [DRAWER_CHATS_MAX].
 * [linux]: Home's main list as it is. [servicesActive] 0 shows nothing.
 * Without the agent, the prompt box gives way to [installAction].
 */
data class ChatHome(
    val continueRows: List<ChatHistoryRow>,
    val drawerPinned: List<ChatHistoryRow>,
    val drawerChats: List<ChatHistoryRow>,
    val linux: List<DrawerItem>,
    val servicesActive: Int,
    val agentInstalled: Boolean,
    val installAction: HomeAction,
    val footer: String,
)

/**
 * The chat home for [history] ([chatHistory]'s rows) and Home's [main]
 * list. [mode] is the `chat-mode` setting; [cwd] the guest folder new chats
 * start in, shown with [home] as `~` in the footer.
 */
fun chatHome(
    history: List<ChatHistoryRow>,
    main: List<HomeItem>,
    servicesActive: Int,
    agentInstalled: Boolean,
    mode: String,
    cwd: String,
    home: String,
): ChatHome {
    fun isLive(row: ChatHistoryRow) = row.state == ChatRowState.WORKING || row.state == ChatRowState.WAITING
    val (pinned, others) = history.partition { it.pinned }
    val (live, rest) = others.partition(::isLive)
    return ChatHome(
        continueRows = history.filter(::isLive).take(CHAT_HOME_CONTINUE_MAX),
        drawerPinned = pinned,
        drawerChats = (live + rest).take(DRAWER_CHATS_MAX),
        linux = main.map { DrawerItem(it.label, it.action, it.action is HomeAction.Menu, it.logo) },
        servicesActive = servicesActive,
        agentInstalled = agentInstalled,
        installAction = menuActTab("agent:claude"),
        footer = "claude code · ${footerMode(mode)} · ${footerDir(cwd, home)}",
    )
}

/** A history row's folder ([ChatHistoryRow.cwd]) as the footer shows one: `~/site/`; null for the hub's own. */
fun chatFolderLabel(cwd: String?, home: String): String? = cwd?.let { footerDir(it, home) }

// "Default (asks)" → "default": the mode's name without its explanation.
private fun footerMode(mode: String): String =
    CHAT_MODES.firstOrNull { it.value == mode }?.name?.substringBefore(" (")?.lowercase() ?: mode

private fun footerDir(cwd: String, home: String): String {
    val dir = cwd.trimEnd('/')
    val base = home.trimEnd('/')
    val shown = when {
        dir == base -> "~"
        base.isNotEmpty() && dir.startsWith("$base/") -> "~" + dir.removePrefix(base)
        else -> dir
    }
    return "$shown/"
}
