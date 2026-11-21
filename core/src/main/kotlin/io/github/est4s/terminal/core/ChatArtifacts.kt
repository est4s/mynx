package io.github.est4s.terminal.core

import java.nio.file.Files
import java.nio.file.LinkOption

/*
 * Artifact cards: when the agent makes or changes an app in `~/apps` or
 * `~/terminal-apps`, or writes an APK, a Markdown file or a picture, the
 * chat shows a card for it with what can be done with it (Open, Run,
 * Install, Copy, View, Share).
 *
 * - Hints ([artifactHints]) come from a turn's completed tool calls: the
 *   paths of Write, Edit and NotebookEdit (relative ones against the
 *   chat's folder, `~/` against home), and, only as a hint, paths a Bash
 *   command names in the app folders or ending in `.apk` or a picture's
 *   extension ([chatImageFile]).
 * - [ArtifactFinder] checks them on disk (off the main thread) with the
 *   rules Home and the menu use: a web app is what [WebApps] lists from
 *   `~/apps` (also a file written through a link there), a terminal app
 *   an executable file right in `~/terminal-apps` or `~/apps`, labelled
 *   as the menu labels it. A Markdown file (`.md`, `.markdown`) counts
 *   anywhere, but only when written (Write, Edit), not named in a command.
 *   A picture outside the app folders counts anywhere, written or named
 *   (a picture in an app folder is the app's card); its [ChatArtifact.version]
 *   is the file's time, so a picture drawn again changes its card. A command's path counts only if it changed
 *   since the turn started (a command that just reads an app gives no
 *   card), so history, with no turn times, only goes by written files.
 * - Audio and video ([chatAudioFile], [chatVideoFile]) count as pictures do.
 * - Files written (Write, Edit, NotebookEdit) that got no card of their own
 *   share one [ArtifactKind.CHANGED_FILES] card per turn, listing them in
 *   [ChatArtifact.files] in the order first written.
 * - One card per app per turn (the items after the last prompt), changed
 *   in place when the app changes again ([withArtifactCards]).
 */

enum class ArtifactKind { WEB_APP, TERMINAL_APP, APK, MARKDOWN, IMAGE, AUDIO, VIDEO, CHANGED_FILES }

/**
 * What a card is about. [path] is the Debian path as found (the app's
 * folder in `~/apps`, the terminal app's file, the APK); [shareFile] the
 * link-free Debian path of what Share sends (a web app's `index.html`,
 * else its `app.conf`); [appName] a web app's name, for its screen;
 * [version] a picture's, audio or video file's time (else 0), so its card
 * shows it anew. A [ArtifactKind.CHANGED_FILES] card's [path] is
 * [CHANGED_FILES_CARD] (one per turn), [files] the Debian paths written and
 * [shareFile] the first of them.
 */
data class ChatArtifact(
    val kind: ArtifactKind,
    val path: String,
    val label: String,
    val shareFile: String,
    val appName: String? = null,
    val version: Long = 0L,
    val files: List<String> = emptyList(),
)

/** The [ChatArtifact.path] of a turn's changed files card. */
const val CHANGED_FILES_CARD = "changed-files"

/** Whether [name] is an audio file, by its extension ([fileKindOf]). */
fun chatAudioFile(name: String): Boolean = fileKindOf(name) == FileKind.AUDIO

/** Whether [name] is a video file, by its extension ([fileKindOf]). */
fun chatVideoFile(name: String): Boolean = fileKindOf(name) == FileKind.VIDEO

private fun media(path: String) = chatImageFile(path) || chatAudioFile(path) || chatVideoFile(path)

/**
 * A path a tool call touched; [fromCommand]: named in a Bash command, so only
 * a hint (it counts if it changed in the turn); [written]: the call wrote it
 * (a Write or Edit, or a command's `>`, `>>` or `tee`), so it can be a
 * Markdown card or a changed file.
 */
data class ArtifactHint(val path: String, val fromCommand: Boolean, val written: Boolean = !fromCommand)

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
            call.name == "Bash" -> {
                val written = commandWrites(call.title, cwd, home)
                for (word in call.title.split(COMMAND_SPLIT)) {
                    val token = word.substringAfterLast('=')
                    if (token.isEmpty() || token.indexOfAny(GLOB_CHARS) >= 0) continue
                    val path = debianPath(token, cwd, home) ?: continue
                    if (inAppFolder(path, home) || path.endsWith(".apk", ignoreCase = true) || media(path)) {
                        hints += ArtifactHint(path, true, written = path in written)
                    }
                }
                written.forEach { hints += ArtifactHint(it, true, written = true) }
            }
        }
    }
    return hints.toList()
}

private val HEREDOC = Regex("""<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1""")
private val SEGMENT_SPLIT = Regex("""&&|\|\||;|\n""")
private val CD = Regex("""^\s*cd\s+(['"]?)([^\s;&|<>()'"`]+)\1\s*$""")
private const val TARGET = """(['"]?)([^\s;&|<>()'"`]+)\1"""
private val REDIRECT = Regex("""(?:^|[^<>])>{1,2}\|?[ \t]*$TARGET""")
private val TEE = Regex("""\btee\s+(?:-\S+\s+)*$TARGET""")
private val SCRATCH = listOf("/tmp/", "/var/tmp/", "/dev/", "/proc/", "/sys/")

/**
 * The files [command] writes with `>`, `>>` or `tee`, as Debian paths, in
 * order: here-documents' bodies are skipped, and a `cd` moves the folder
 * later relative paths start from. Scratch places (`/tmp`, `/dev`) and
 * paths with a variable or a glob don't count.
 */
internal fun commandWrites(command: String, cwd: String, home: String): Set<String> {
    val lines = ArrayList<String>()
    var heredocEnd: String? = null
    for (line in command.lines()) {
        if (heredocEnd != null) {
            if (line.trim() == heredocEnd) heredocEnd = null
            continue
        }
        lines += line
        heredocEnd = HEREDOC.findAll(line).lastOrNull()?.groupValues?.get(2)
    }
    val found = LinkedHashSet<String>()
    var folder = cwd
    for (segment in lines.joinToString("\n").split(SEGMENT_SPLIT)) {
        CD.find(segment)?.let { cd ->
            cd.groupValues[2].takeIf { it.indexOfAny(GLOB_CHARS) < 0 }?.let { debianPath(it, folder, home) }?.let { folder = it }
            return@let
        } ?: run {
            for (match in REDIRECT.findAll(segment) + TEE.findAll(segment)) {
                val raw = match.groupValues[2]
                if (raw.indexOfAny(GLOB_CHARS) >= 0) continue
                val path = debianPath(raw, folder, home) ?: continue
                if (SCRATCH.none { path.startsWith(it) } && path != "/dev/null") found += path
            }
        }
    }
    return found
}

/** Clocks and file times may differ a little: a turn counts as starting this much earlier. */
const val ARTIFACT_CLOCK_SLACK_MS = 2_000L

/**
 * Worth a look as soon as its call completes: every hint is now (a
 * command's only names app folders, APKs and media, and any written file
 * can be a changed file). Kept as the one place that decides.
 */
@Suppress("UNUSED_PARAMETER")
internal fun ArtifactHint.direct(home: String) = true

private fun markdown(path: String) = path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)

private fun inAppFolder(path: String, home: String) =
    path.startsWith("$home/$APPS/") || path.startsWith("$home/$TERMINAL_APPS/")

// [raw] as an absolute Debian path with `.` and `..` worked out (links left alone).
internal fun debianPath(raw: String, cwd: String, home: String): String? {
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
        val written = LinkedHashSet<String>()
        for (hint in hints) {
            if (hint.fromCommand && (since == null || !changedSince(hint.path, since))) continue
            val outsideApps = !inAppFolder(hint.path, home)
            val artifact = when {
                hint.written && markdown(hint.path) -> markdownAt(hint.path)
                chatImageFile(hint.path) && outsideApps -> mediaAt(hint.path, ArtifactKind.IMAGE)
                chatAudioFile(hint.path) && outsideApps -> mediaAt(hint.path, ArtifactKind.AUDIO)
                chatVideoFile(hint.path) && outsideApps -> mediaAt(hint.path, ArtifactKind.VIDEO)
                else -> artifactAt(hint.path, home) { webApps }
            }
            if (artifact == null) {
                if (hint.written && apps.resolve(hint.path)?.let(apps::host)?.isFile == true) written += hint.path
                continue
            }
            if (artifact.path !in found) found[artifact.path] = artifact
        }
        if (written.isNotEmpty()) {
            val files = written.toList()
            found[CHANGED_FILES_CARD] = ChatArtifact(ArtifactKind.CHANGED_FILES, CHANGED_FILES_CARD, "Changed files", files[0], files = files)
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

    private fun markdownAt(path: String): ChatArtifact? {
        val resolved = apps.resolve(path) ?: return null
        if (apps.host(resolved)?.isFile != true) return null
        return ChatArtifact(ArtifactKind.MARKDOWN, path, path.substringAfterLast('/'), resolved)
    }

    private fun mediaAt(path: String, kind: ArtifactKind): ChatArtifact? {
        val resolved = apps.resolve(path) ?: return null
        val file = apps.host(resolved)?.takeIf { it.isFile } ?: return null
        return ChatArtifact(kind, path, path.substringAfterLast('/'), resolved, version = file.lastModified())
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
fun withArtifactCards(items: List<ChatItem>, found: List<ChatArtifact>, toEnd: Boolean = false, newId: () -> String): List<ChatItem> {
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
    val done = result ?: items
    return if (toEnd) cardsLast(done, turnStart) else done
}

// The turn's cards (from [turnStart]) after its other items, in their order; nothing to move: [items] itself.
private fun cardsLast(items: List<ChatItem>, turnStart: Int): List<ChatItem> {
    val turn = items.subList(turnStart, items.size)
    val (cards, others) = turn.partition { it is ChatItem.Artifact }
    if (cards.isEmpty() || turn == others + cards) return items
    return items.subList(0, turnStart) + others + cards
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
    OPEN("Open"), ADD_TO_HOME("Add to home screen"), RUN("Run"), INSTALL("Install"), COPY("Copy"), VIEW("View"), PLAY("Play"),
    SHARE("Share"),
}

/** [canInstall]: this build may open Android's installer for a file (debug builds only). */
fun chatArtifactActions(artifact: ChatArtifact, canInstall: Boolean): List<ArtifactAction> = when (artifact.kind) {
    ArtifactKind.WEB_APP -> listOf(ArtifactAction.OPEN, ArtifactAction.ADD_TO_HOME, ArtifactAction.SHARE)
    ArtifactKind.TERMINAL_APP -> listOf(ArtifactAction.RUN, ArtifactAction.SHARE)
    ArtifactKind.APK -> if (canInstall) listOf(ArtifactAction.INSTALL, ArtifactAction.SHARE) else listOf(ArtifactAction.SHARE)
    // View opens the Markdown viewer; Copy puts the file's text on the clipboard ([shareFile]).
    ArtifactKind.MARKDOWN -> listOf(ArtifactAction.VIEW, ArtifactAction.COPY, ArtifactAction.SHARE)
    // View shows it full screen; the card shows it too.
    ArtifactKind.IMAGE -> listOf(ArtifactAction.VIEW, ArtifactAction.SHARE)
    // Play opens the media player.
    ArtifactKind.AUDIO, ArtifactKind.VIDEO -> listOf(ArtifactAction.PLAY, ArtifactAction.SHARE)
    // Its rows ([changedFileRows]) open the files in their viewer.
    ArtifactKind.CHANGED_FILES -> emptyList()
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
        ArtifactKind.MARKDOWN -> "Markdown"
        ArtifactKind.IMAGE -> "Image"
        ArtifactKind.AUDIO -> "Audio"
        ArtifactKind.VIDEO -> "Video"
        ArtifactKind.CHANGED_FILES -> {
            val n = artifact.files.size
            return "Changed files · $n ${if (n == 1) "file" else "files"}"
        }
    }
    return "$kind · ${homePath(artifact.path, home)}"
}

private fun homePath(path: String, home: String) =
    if (path == home) "~" else if (path.startsWith("$home/")) "~" + path.removePrefix(home) else path

/** A row of a changed files card: the file's Debian [path], its [name], its [folder] (`~/work`). */
data class ChangedFileRow(val path: String, val name: String, val folder: String)

/** A changed files card's rows, one per file, in order. */
fun changedFileRows(artifact: ChatArtifact, home: String): List<ChangedFileRow> = artifact.files.map { path ->
    val folder = path.substringBeforeLast('/', "").ifEmpty { "/" }
    ChangedFileRow(path, path.substringAfterLast('/'), homePath(folder, home))
}

/** Run: a tab running the terminal app as the menu's Terminal apps item does, with its key bar. */
fun chatArtifactRunCommand(artifact: ChatArtifact): List<String> = menuActTab("app:${artifact.path}").command

/** Add to home screen: the menu's own item, in a tab (Android asks; what mynx says stays on screen). */
fun chatArtifactShortcutCommand(artifact: ChatArtifact): List<String> = menuActTab("shortcut:${artifact.appName}").command
