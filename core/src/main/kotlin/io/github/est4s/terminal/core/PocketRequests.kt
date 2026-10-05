package io.github.est4s.terminal.core

import java.io.File
import java.net.URLDecoder

private val REQUEST_FILE = Regex("[A-Za-z0-9_-]{1,64}\\.req")

/** A request from `pocket`: its [name] and the lines after it. */
data class PocketRequest(val name: String, val args: List<String>)

/**
 * A notification `pocket notify` asks for. [shell] is the tab it came
 * from (`POCKET_SHELL`); with [ifAway], skip it while that tab is on
 * screen.
 */
data class Notice(val title: String, val text: String, val shell: Int?, val ifAway: Boolean)

private const val MAX_NOTICE_TITLE = 100
private const val MAX_NOTICE_TEXT = 1000
private const val DEFAULT_VIBRATE_MS = 300L
private const val MAX_VIBRATE_MS = 5000L
/** The longest text `pocket clipboard set` copies (Android's clipboard goes through Binder, which has a ~1 MB limit). */
const val MAX_CLIPBOARD_TEXT = 200_000

private class Refused(message: String) : Exception(message)

/**
 * Answers the `pocket` command. It writes a request to [dir] as `ID.req`
 * (renamed into place when complete): the request name on the first
 * line, one argument per line after it. The answer goes to `ID.reply` as
 * JSON, also renamed into place, and the request is removed. [home] is
 * root's home in Debian. The app applies what changed (see the names
 * returned by [processPending]). [notify] shows a [Notice] and returns
 * null, or says why it didn't; [openUrl] likewise opens a web link,
 * and [installApk] opens Android's installer for an APK (a host file).
 * [vibrate] and [setClipboard] work the same way; [readClipboard] gives
 * the clipboard's text or fails with the reason. [share] opens
 * Android's share sheet for a [Share].
 *
 * Requests in [later] are answered later, or stream (see [Later]).
 * [sweep] cancels those whose `pocket` cancelled them or has gone
 * ([alive] says whether a process still runs).
 */
class PocketRequests(
    private val dir: File,
    private val home: File,
    now: () -> Long = System::currentTimeMillis,
    private val notify: (Notice) -> String? = { "notifications aren't available" },
    private val openUrl: (String) -> String? = { "links can't be opened here" },
    private val installApk: (File) -> String? = { "apps can't be installed here" },
    private val vibrate: (Long) -> String? = { "vibration isn't available here" },
    private val setClipboard: (String) -> String? = { "the clipboard isn't available here" },
    private val readClipboard: () -> Result<String> = { Result.failure(Exception("the clipboard isn't available here")) },
    private val share: (Share) -> String? = { "sharing isn't available here" },
    private val later: Map<String, Later> = emptyMap(),
    private val alive: (Int) -> Boolean = { pid -> File("/proc/$pid").exists() },
) {
    private val config = File(home, CONFIG_DIR)
    private val settingsFile = File(config, "settings.conf")
    private val colorsFile = File(config, "colors.properties")
    private val themesDir = File(home, THEMES_DIR)
    private val keyBarsDir = File(config, "keybars")
    private val history = ConfigHistory(config, File(home, UNDO_DIR), now)

    private val open = mutableSetOf<PendingReply>()

    private fun undoKeep() = loadSettings(settingsFile).settings.undoKeep

    /** Call when the app starts: edits made while it was closed become an undo step. */
    fun start() {
        runCatching { history.observe(HAND_EDITS, undoKeep()) }
    }

    /** Answers every waiting request; returns those handled, in order. */
    fun processPending(): List<PocketRequest> {
        val pending = dir.listFiles { f -> f.isFile && REQUEST_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }
        return pending.map { file ->
            val lines = runCatching { file.readText().lines().dropLastWhile { it.isEmpty() } }.getOrDefault(emptyList())
            val request = PocketRequest(lines.firstOrNull()?.trim().orEmpty(), lines.drop(1))
            val id = file.name.removeSuffix(".req")
            later[request.name]?.let { handler ->
                startLater(id, request, handler)
                file.delete()
                return@map request
            }
            val reply = try {
                val change = changeName(request)
                if (change != null) history.observe(HAND_EDITS, undoKeep())
                answer(request).also { if (change != null) history.observe(change, undoKeep()) }
            } catch (e: Refused) {
                refusal(e.message!!)
            }
            writeReply(dir, id, reply)
            file.delete()
            request
        }
    }

    private fun startLater(id: String, request: PocketRequest, handler: Later) {
        val reply = PendingReply(dir, id) { synchronized(open) { open -= it } }
        synchronized(open) { open += reply }
        val wait = File(dir, "$id.wait.tmp")
        wait.writeText(handler.seconds?.toString() ?: "stream")
        wait.renameTo(File(dir, "$id.wait"))
        try {
            handler.start(request.args, reply)
        } catch (e: Refused) {
            reply.refuse(e.message!!)
        }
    }

    /** Whether a [Later] request is still running: while one is, call [sweep] now and then. */
    fun hasOpen(): Boolean = synchronized(open) { open.isNotEmpty() }

    /** Cancels the [Later] requests that `pocket` cancelled (`ID.cancel`) or whose `pocket` has gone. */
    fun sweep() {
        for (reply in synchronized(open) { open.toList() }) {
            val pid = pidOfRequest(reply.id)
            when {
                File(dir, "${reply.id}.cancel").exists() -> reply.cancel(listening = true)
                pid != null && !alive(pid) -> reply.cancel(listening = false)
            }
        }
    }

    private fun answer(request: PocketRequest): String {
        val args = request.args
        fun need(count: Int) {
            if (args.size < count) throw Refused("${request.name} needs $count argument${if (count > 1) "s" else ""}")
        }
        return when (request.name) {
            "check" -> {
                history.observe(args.firstOrNull()?.takeIf { it.isNotBlank() } ?: HAND_EDITS, undoKeep())
                ok("problems" to problemsJson(checkConfig(home)))
            }
            "undo" -> ok("undone" to (history.undo(undoKeep())?.let(::json) ?: "null"))
            "undo-list" -> {
                history.observe(HAND_EDITS, undoKeep())
                val steps = history.steps().map { obj("reason" to json(it.reason), "time" to it.time.toString()) }
                ok("keep" to undoKeep().toString(), "steps" to array(steps))
            }
            "settings" -> settings()
            "set" -> {
                need(2)
                val (key, value) = args
                val text = setSetting(settingsFile.takeIf { it.isFile }?.readText(), key, value)
                    .getOrElse { throw Refused(it.message!!) }
                writeAtomically(settingsFile, text)
                ok("key" to json(key), "value" to json(value))
            }
            "reset" -> {
                need(1)
                val key = args[0]
                if (key == "all") {
                    settingsFile.delete()
                    ok("key" to json("all"))
                } else {
                    val def = SETTINGS.firstOrNull { it.key == key }
                        ?: throw Refused("unknown setting '$key' (pocket settings lists them)")
                    if (settingsFile.isFile) {
                        writeAtomically(settingsFile, unsetSetting(settingsFile.readText(), key).getOrThrow())
                    }
                    ok("key" to json(key), "value" to json(def.default))
                }
            }
            "theme-reset" -> {
                colorsFile.delete()
                ok("name" to json(BUILT_IN_THEMES.first()))
            }
            "themes" -> themes()
            "theme-show" -> {
                need(1)
                val (text, source) = theme(args[0])
                ok("name" to json(args[0]), "source" to json(source), "text" to json(text))
            }
            "theme-set" -> {
                need(1)
                writeAtomically(colorsFile, colorsFileFor(args[0], theme(args[0]).first))
                ok("name" to json(args[0]))
            }
            "preview-colors" -> ok("problems" to array(parseColorScheme(args.joinToString("\n"), NEON).problems.map(::json)))
            "preview-end" -> ok()
            "keybars" -> keyBars()
            "keybar-show" -> {
                need(1)
                val name = args[0]
                val user = userKeyBar(name)
                val text = user?.readText() ?: builtInKeyBarText(name) ?: throw Refused(noKeyBar(name))
                ok("name" to json(name), "file" to (user?.let { json(debianPath(it)) } ?: "null"), "text" to json(text))
            }
            "keybar-edit" -> {
                need(1)
                val name = args[0]
                if (!isKeyBarName(name)) throw Refused("'$name' can't be a bar name: use letters, digits, - and _")
                val file = File(keyBarsDir, "$name.conf")
                if (!file.isFile) writeAtomically(file, builtInKeyBarText(name) ?: NEW_KEY_BAR)
                ok("file" to json(debianPath(file)))
            }
            "keybar-reset" -> {
                need(1)
                val name = args[0]
                val user = userKeyBar(name)
                when {
                    builtInKeyBarText(name) == null && user == null -> throw Refused(noKeyBar(name))
                    builtInKeyBarText(name) == null ->
                        throw Refused("$name has no built-in bar to go back to; delete ${debianPath(user!!)} to remove it")
                    user == null -> throw Refused("$name is already the built-in bar")
                }
                user!!.delete()
                ok()
            }
            "notify" -> notify(args)
            "open-url" -> {
                need(1)
                val url = args[0].trim()
                if (!isWebLink(url)) throw Refused("only http and https links open: $url")
                openUrl(url)?.let { throw Refused(it) }
                ok()
            }
            "install-apk" -> {
                need(1)
                val path = args[0].trim()
                val apk = hostPath(path, home.parentFile.path)?.let(::File)
                    ?: throw Refused("the path must start with /: $path")
                when {
                    !apk.exists() -> throw Refused("no such file: $path")
                    !apk.isFile || !apk.name.endsWith(".apk", ignoreCase = true) -> throw Refused("not an APK: $path")
                }
                installApk(apk)?.let { throw Refused(it) }
                ok()
            }
            "vibrate" -> {
                val ms = args.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    it.toLongOrNull()?.takeIf { ms -> ms in 1..MAX_VIBRATE_MS }
                        ?: throw Refused("vibrate takes milliseconds from 1 to $MAX_VIBRATE_MS")
                } ?: DEFAULT_VIBRATE_MS
                vibrate(ms)?.let { throw Refused(it) }
                ok()
            }
            "clipboard-get" -> {
                clipboardAllowed()
                ok("text" to json(readClipboard().getOrElse { throw Refused(it.message ?: "couldn't read the clipboard") }))
            }
            "clipboard-set" -> {
                clipboardAllowed()
                val text = decoded(request)
                if (text.length > MAX_CLIPBOARD_TEXT) {
                    throw Refused("too long for the clipboard: ${text.length} characters (at most $MAX_CLIPBOARD_TEXT)")
                }
                setClipboard(text)?.let { throw Refused(it) }
                ok()
            }
            "share" -> {
                shareAllowed()
                if (args.isEmpty()) throw Refused("share needs a file")
                if (args.size > MAX_SHARE_FILES) throw Refused("too many files: ${args.size} (at most $MAX_SHARE_FILES at once)")
                val files = args.map { path ->
                    val file = hostPath(path, home.parentFile.path)?.let(::File)
                        ?: throw Refused("the path must start with /: $path")
                    when {
                        !file.exists() -> throw Refused("no such file: $path")
                        !file.isFile -> throw Refused("not a file: $path")
                    }
                    file
                }
                share(Share(files, null))?.let { throw Refused(it) }
                ok("count" to files.size.toString())
            }
            "share-text" -> {
                shareAllowed()
                val text = decoded(request)
                when {
                    text.isEmpty() -> throw Refused("nothing to share")
                    text.length > MAX_SHARE_TEXT ->
                        throw Refused("too long to share: ${text.length} characters (at most $MAX_SHARE_TEXT)")
                }
                share(Share(emptyList(), text))?.let { throw Refused(it) }
                ok()
            }
            else -> throw Refused("unknown request '${request.name}'")
        }
    }

    // Lines: title, text, then options: shell=N, if-away, agent, took=SECONDS.
    // Agent notices follow the agent-notify settings.
    private fun notify(args: List<String>): String {
        val title = args.getOrNull(0)?.trim().orEmpty()
        if (title.isEmpty()) throw Refused("notify needs a title")
        var shell: Int? = null
        var ifAway = false
        var agent = false
        var took: Int? = null
        for (option in args.drop(2)) {
            val value = option.substringAfter('=', "")
            when {
                option == "if-away" -> ifAway = true
                option == "agent" -> agent = true
                option.startsWith("shell=") && value.toIntOrNull() != null -> shell = value.toInt()
                option.startsWith("took=") && (value.toIntOrNull() ?: -1) >= 0 -> took = value.toInt()
                else -> throw Refused("unknown notify option '$option'")
            }
        }
        if (agent) {
            val settings = loadSettings(settingsFile).settings
            if (!settings.agentNotify) return notShown("agent-notify is off (pocket set agent-notify on)")
            if (took != null && took < settings.agentNotifyAfter) {
                return notShown("the turn took ${took}s; agent-notify-after is ${settings.agentNotifyAfter}s")
            }
        }
        val text = args.getOrNull(1)?.trim().orEmpty()
        val reason = notify(Notice(title.cut(MAX_NOTICE_TITLE), text.cut(MAX_NOTICE_TEXT), shell, ifAway))
        return if (reason == null) ok("shown" to "true") else notShown(reason)
    }

    // Text sent percent-encoded, so it can hold line breaks.
    private fun decoded(request: PocketRequest): String = try {
        URLDecoder.decode(request.args.getOrNull(0).orEmpty(), "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw Refused("${request.name}: badly encoded text")
    }

    private fun shareAllowed() {
        if (!loadSettings(settingsFile).settings.androidShare) {
            throw Refused("sharing is off (pocket set android-share on)")
        }
    }

    private fun clipboardAllowed() {
        if (!loadSettings(settingsFile).settings.androidClipboard) {
            throw Refused("clipboard access is off (pocket set android-clipboard on)")
        }
    }

    private fun notShown(reason: String) = ok("shown" to "false", "reason" to json(reason))

    private fun String.cut(max: Int) = if (length <= max) this else take(max - 1) + "…"

    // The name of the change a request makes, for `pocket undo`; null if it changes nothing.
    private fun changeName(request: PocketRequest): String? {
        val args = request.args.joinToString(" ")
        return when (request.name) {
            "set", "reset" -> "${request.name} $args"
            "theme-set", "theme-reset", "keybar-edit", "keybar-reset" -> "${request.name.replace('-', ' ')} $args".trim()
            else -> null
        }
    }

    private fun settings(): String {
        val parsed = loadSettings(settingsFile)
        val values = parsed.settings.values()
        val list = SETTINGS.map { def ->
            obj(
                "key" to json(def.key),
                "value" to json(values.getValue(def.key)),
                "default" to json(def.default),
                "description" to json(def.description),
                "choices" to (def.choices?.let { array(it.map(::json)) } ?: "null"),
            )
        }
        return ok("settings" to array(list), "problems" to array(parsed.problems.map(::json)))
    }

    private fun themes(): String {
        val colors = colorsFile.takeIf { it.isFile }?.readText()
        // No colours file: the app shows Neon.
        val current = if (colors == null) "neon" else themeNameOf(colors)
        val user = userThemes()
        val list = BUILT_IN_THEMES.filter { it !in user }.map { obj("name" to json(it), "source" to json("built-in")) } +
            user.map { (name, file) -> obj("name" to json(name), "source" to json(debianPath(file))) }
        return ok("current" to (current?.let(::json) ?: "null"), "themes" to array(list))
    }

    private fun userThemes(): Map<String, File> =
        themesDir.listFiles { f -> f.isFile && f.name.endsWith(THEME_SUFFIX) }.orEmpty()
            .associateBy { it.name.removeSuffix(THEME_SUFFIX) }
            .filterKeys { isKeyBarName(it) }
            .toSortedMap()

    /** A theme's text and where it comes from; the user's wins. */
    private fun theme(name: String): Pair<String, String> {
        userThemes()[name]?.let { return it.readText() to debianPath(it) }
        val builtIn = builtInThemeText(name) ?: throw Refused("no theme '$name' (pocket theme list shows them)")
        return builtIn to "built-in"
    }

    private fun keyBars(): String {
        val user = keyBarsDir.listFiles { f -> f.isFile && f.name.endsWith(".conf") }.orEmpty()
            .map { it.name.removeSuffix(".conf") }.filter { isKeyBarName(it) }
        val list = (BUILT_IN_KEY_BARS + user).distinct().sorted().map { name ->
            obj(
                "name" to json(name),
                "builtIn" to (name in BUILT_IN_KEY_BARS).toString(),
                "file" to (userKeyBar(name)?.let { json(debianPath(it)) } ?: "null"),
            )
        }
        return ok("keybars" to array(list))
    }

    private fun userKeyBar(name: String): File? =
        if (isKeyBarName(name)) File(keyBarsDir, "$name.conf").takeIf { it.isFile } else null

    private fun noKeyBar(name: String) = "no key bar '$name' (pocket keybar list shows them)"

    private fun debianPath(file: File) = "~/" + file.relativeTo(home).invariantSeparatorsPath

    private fun writeAtomically(file: File, text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw Refused("couldn't write ${debianPath(file)}")
    }
}

private const val HAND_EDITS = "edits by hand"
private const val UNDO_DIR = ".local/state/pocket-terminal/undo"

private const val NEW_KEY_BAR = """# A key bar: one button per line, "label = keys". Buttons fill two
# rows in this order, the first half on top. Format: see ~/AGENTS.md.
Esc  = Esc
Tab  = Tab
Ctrl = Ctrl
Quit = q
←    = Left
↓    = Down
↑    = Up
→    = Right
"""

private fun problemsJson(found: List<ConfigProblems>) = array(found.map {
    obj("file" to json(it.file), "problems" to array(it.problems.map(::json)))
})

internal fun ok(vararg fields: Pair<String, String>) = obj("ok" to "true", *fields)

internal fun obj(vararg fields: Pair<String, String>) =
    fields.joinToString(",", "{", "}") { (key, value) -> "${json(key)}:$value" }

private fun array(items: List<String>) = items.joinToString(",", "[", "]")

internal fun json(text: String): String = buildString {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}
