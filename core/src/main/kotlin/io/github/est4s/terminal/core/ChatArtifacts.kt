package io.github.est4s.terminal.core

import java.nio.file.Files
import java.nio.file.LinkOption

/*
 * Artifact cards: when the agent makes or changes an app in `~/apps` or
 * `~/terminal-apps`, or writes an APK, the chat shows a card for it with
 * what can be done with it (Open, Run, Install, Share).
 *
 * - Hints ([artifactHints]) come from a turn's completed tool calls: the
 *   paths of Write, Edit and NotebookEdit (relative ones against the
 *   chat's folder, `~/` against home), and, only as a hint, paths a Bash
 *   command names in the app folders or ending in `.apk`.
 * - [ArtifactFinder] checks them on disk (off the main thread) with the
 *   rules Home and the menu use: a web app is what [WebApps] lists from
 *   `~/apps` (also a file written through a link there), a terminal app
 *   an executable file right in `~/terminal-apps` or `~/apps`, labelled
 *   as the menu labels it. A command's path counts only if it changed
 *   since the turn started (a command that just reads an app gives no
 *   card), so history, with no turn times, only goes by written files.
 * - One card per app per turn (the items after the last prompt), changed
 *   in place when the app changes again ([withArtifactCards]).
 */

enum class ArtifactKind { WEB_APP, TERMINAL_APP, APK }

/**
 * What a card is about. [path] is the Debian path as found (the app's
 * folder in `~/apps`, the terminal app's file, the APK); [shareFile] the
 * link-free Debian path of what Share sends (a web app's `index.html`,
 * else its `app.conf`); [appName] a web app's name, for its screen.
 */
data class ChatArtifact(
    val kind: ArtifactKind,
    val path: String,
    val label: String,
    val shareFile: String,
    val appName: String? = null,
)

/** A path a tool call touched; [fromCommand]: named in a Bash command, so only a hint. */
data class ArtifactHint(val path: String, val fromCommand: Boolean)

private val WRITE_TOOLS = setOf("Write", "Edit", "NotebookEdit")
private val COMMAND_SPLIT = Regex("""[\s;&|<>()'"`]+""")
private val GLOB_CHARS = charArrayOf('$', '*', '?', '[', '{')
private const val APPS = "apps"
private const val TERMINAL_APPS = "terminal-apps"

/** Whether a tool call can touch an artifact, so a completed one is worth a look on disk. */
fun artifactTool(call: ToolCallInfo): Boolean = call.name in WRITE_TOOLS || call.name == "Bash"

/** The paths [calls] touched that may be artifacts, in order, each once: see the top of this file. */
fun artifactHints(calls: List<ToolCallInfo>, cwd: String, home: String): List<ArtifactHint> {
    val hints = LinkedHashSet<ArtifactHint>()
    for (call in calls) {
        if (call.status != ToolStatus.COMPLETED) continue
        when {
            call.name in WRITE_TOOLS -> if (call.title != call.name && call.title.isNotBlank()) {
                debianPath(call.title.trim(), cwd, home)?.let { hints += ArtifactHint(it, false) }
            }
            call.name == "Bash" -> for (word in call.title.split(COMMAND_SPLIT)) {
                val token = word.substringAfterLast('=')
                if (token.isEmpty() || token.indexOfAny(GLOB_CHARS) >= 0) continue
                val path = debianPath(token, cwd, home) ?: continue
                if (inAppFolder(path, home) || path.endsWith(".apk", ignoreCase = true)) hints += ArtifactHint(path, true)
            }
        }
    }
    return hints.toList()
}

/** Clocks and file times may differ a little: a turn counts as starting this much earlier. */
const val ARTIFACT_CLOCK_SLACK_MS = 2_000L

/** Worth a look as soon as its call completes: in an app folder, or an APK. */
internal fun ArtifactHint.direct(home: String) =
    fromCommand || inAppFolder(path, home) || path.endsWith(".apk", ignoreCase = true)

private fun inAppFolder(path: String, home: String) =
    path.startsWith("$home/$APPS/") || path.startsWith("$home/$TERMINAL_APPS/")

// [raw] as an absolute Debian path with `.` and `..` worked out (links left alone).
private fun debianPath(raw: String, cwd: String, home: String): String? {
    if (raw.contains('\u0000') || raw.contains('\n')) return null
    val full = when {
        raw == "~" -> home
        raw.startsWith("~/") -> home + raw.substring(1)
        raw.startsWith("/") -> raw
        raw.startsWith("~") -> return null
        else -> "$cwd/$raw"
    }
    val parts = ArrayList<String>()
    for (part in full.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts += part
        }
    }
    return "/" + parts.joinToString("/")
}

/** Finds what [hints] are on disk; [since]: when the turn started (ms), null when unknown. */
fun interface ArtifactLookup {
    fun find(hints: List<ArtifactHint>, since: Long?): List<ChatArtifact>
}

/** [ArtifactLookup] on the disk [apps] reads. Reads files only: call it off the main thread. */
class ArtifactFinder(private val apps: WebApps) : ArtifactLookup {
    override fun find(hints: List<ArtifactHint>, since: Long?): List<ChatArtifact> {
        val home = apps.guestHome
        // Only the ~/apps apps: the tools' own are never the agent's work.
        val webApps by lazy { apps.list().filter { it.path == "$home/$APPS/${it.name}" } }
        val found = LinkedHashMap<String, ChatArtifact>()
        for (hint in hints) {
            if (hint.fromCommand && (since == null || !changedSince(hint.path, since))) continue
            val artifact = artifactAt(hint.path, home) { webApps } ?: continue
            if (artifact.path !in found) found[artifact.path] = artifact
        }
        return found.values.toList()
    }

    private fun artifactAt(path: String, home: String, webApps: () -> List<WebApp>): ChatArtifact? {
        if (path.endsWith(".apk", ignoreCase = true)) {
            val resolved = apps.resolve(path) ?: return null
            if (apps.host(resolved)?.isFile != true) return null
            return ChatArtifact(ArtifactKind.APK, path, path.substringAfterLast('/'), resolved)
        }
        for (folder in listOf(TERMINAL_APPS, APPS)) {
            val prefix = "$home/$folder/"
            if (!path.startsWith(prefix)) continue
            val name = path.removePrefix(prefix).substringBefore('/')
            val entry = prefix + name
            val resolved = apps.resolve(entry) ?: return null
            val file = apps.host(resolved) ?: return null
            if (file.isFile && isExecutable(file)) {
                return ChatArtifact(ArtifactKind.TERMINAL_APP, entry, terminalAppLabel(entry, file), resolved)
            }
            if (folder == APPS) webApps().firstOrNull { it.name == name }?.let { return web(it) }
            return null
        }
        // A file written through a link: `~/apps/hero` → `~/hero`.
        val resolved = apps.resolve(path) ?: return null
        return webApps().firstOrNull { resolved == it.root || resolved.startsWith(it.root.trimEnd('/') + "/") }?.let(::web)
    }

    private fun web(app: WebApp): ChatArtifact {
        val index = "${app.root}/index.html"
        val share = if (apps.host(index)?.isFile == true) index else "${app.root}/$WEB_APP_CONF"
        return ChatArtifact(ArtifactKind.WEB_APP, app.path, app.label, share, appName = app.name)
    }

    // The link itself (made in this turn) or what it points to.
    private fun changedSince(path: String, since: Long): Boolean {
        val file = apps.host(path) ?: return false
        val own = runCatching { Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrNull()
        val target = apps.resolve(path)?.let(apps::host)?.takeIf { it.exists() }?.lastModified()
        return (own ?: Long.MIN_VALUE) >= since || (target ?: Long.MIN_VALUE) >= since
    }
}

/**
 * [items] with [found] as cards in its last turn (the items after the last
 * prompt): a card already there for the same path is replaced in place,
 * others are added at the end with [newId]. Nothing new: [items] itself.
 */
fun withArtifactCards(items: List<ChatItem>, found: List<ChatArtifact>, newId: () -> String): List<ChatItem> {
    if (found.isEmpty()) return items
    val turnStart = items.indexOfLast { it is ChatItem.User } + 1
    var result: MutableList<ChatItem>? = null
    for (artifact in found) {
        val current = result ?: items
        val at = (turnStart until current.size).firstOrNull { (current[it] as? ChatItem.Artifact)?.artifact?.path == artifact.path }
        if (at != null && (current[at] as ChatItem.Artifact).artifact == artifact) continue
        val list = result ?: items.toMutableList().also { result = it }
        if (at != null) list[at] = ChatItem.Artifact(list[at].id, artifact) else list += ChatItem.Artifact(newId(), artifact)
    }
    return result ?: items
}

/** A transcript's rows ([readClaudeHistory]) with each turn's cards at its end, as the disk is now. Off the main thread. */
fun withHistoryArtifacts(items: List<ChatItem>, lookup: ArtifactLookup, cwd: String, home: String): List<ChatItem> {
    var n = 0
    val result = ArrayList<ChatItem>(items.size)
    val calls = ArrayList<ToolCallInfo>()
    fun endTurn() {
        val hints = artifactHints(calls, cwd, home)
        calls.clear()
        if (hints.isEmpty()) return
        val found = runCatching { lookup.find(hints, null) }.getOrDefault(emptyList())
        found.forEach { result += ChatItem.Artifact("history:artifact:${++n}", it) }
    }
    for (item in items) {
        if (item is ChatItem.User) endTurn()
        if (item is ChatItem.Tool) calls += item.call
        result += item
    }
    endTurn()
    return result
}

/** What a card's buttons do. */
enum class ArtifactAction(val label: String) {
    OPEN("Open"), ADD_TO_HOME("Add to home screen"), RUN("Run"), INSTALL("Install"), SHARE("Share"),
}

/** [canInstall]: this build may open Android's installer for a file (debug builds only). */
fun chatArtifactActions(artifact: ChatArtifact, canInstall: Boolean): List<ArtifactAction> = when (artifact.kind) {
    ArtifactKind.WEB_APP -> listOf(ArtifactAction.OPEN, ArtifactAction.ADD_TO_HOME, ArtifactAction.SHARE)
    ArtifactKind.TERMINAL_APP -> listOf(ArtifactAction.RUN, ArtifactAction.SHARE)
    ArtifactKind.APK -> if (canInstall) listOf(ArtifactAction.INSTALL, ArtifactAction.SHARE) else listOf(ArtifactAction.SHARE)
}

const val CHAT_APK_NO_INSTALL = "mynx can't install apps itself: Share it to a file manager, or open it from the Files app, to install it."

/** A line under the buttons, or null. */
fun chatArtifactNote(artifact: ChatArtifact, canInstall: Boolean): String? =
    if (artifact.kind == ArtifactKind.APK && !canInstall) CHAT_APK_NO_INSTALL else null

/** A card's second line: `Web app · ~/apps/demo`. */
fun chatArtifactLine(artifact: ChatArtifact, home: String): String {
    val kind = when (artifact.kind) {
        ArtifactKind.WEB_APP -> "Web app"
        ArtifactKind.TERMINAL_APP -> "Terminal app"
        ArtifactKind.APK -> "APK"
    }
    val path = if (artifact.path.startsWith("$home/")) "~" + artifact.path.removePrefix(home) else artifact.path
    return "$kind · $path"
}

/** Run: a tab running the terminal app as the menu's Terminal apps item does, with its key bar. */
fun chatArtifactRunCommand(artifact: ChatArtifact): List<String> = menuActTab("app:${artifact.path}").command

/** Add to home screen: the menu's own item, in a tab (Android asks; what mynx says stays on screen). */
fun chatArtifactShortcutCommand(artifact: ChatArtifact): List<String> = menuActTab("shortcut:${artifact.appName}").command
