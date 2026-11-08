package io.github.est4s.terminal.core

import java.io.File

/*
 * Apps' permissions (web apps, part 2): the contract the parts share
 * (work log, "Next", 0). Part A (core) builds this file and its part of
 * [checkConfig]; part B (the app) shows the prompt [BridgeHost.ask]
 * asks for; part C builds `mynx app permissions` on the requests below
 * and documents the file in the guide. Change the shapes here first.
 *
 * WHAT NEEDS ONE
 *
 * Only the private things ([AppPermission]): location, the camera, the
 * microphone, reading the clipboard, notifications, Bluetooth (every
 * `ble-*` call) and serial ports (every `serial-*` call, USB or
 * Bluetooth). Vibration, the flashlight, the battery, sensors, writing
 * the clipboard, share, playing sound, rotation and the app's own
 * service just work ([BRIDGE_CALLS] says which call needs which). The
 * `android-*` settings and Android's own permission dialogs still apply
 * on top: the requests' handlers check them after this, as they do for
 * `mynx`.
 *
 * THE FILE: `~/.config/mynx/app-permissions.conf` ([APP_PERMISSIONS_CONF])
 *
 *     # What each app may use: allow or deny. Not listed: asked the
 *     # first time. mynx app permissions changes it.
 *     [weather]
 *     location = allow
 *     camera = deny
 *
 * One `[NAME]` block per app, as `services.conf`; `PERMISSION = allow |
 * deny` lines in it; `#` comments and blank lines; spaces around `=`,
 * `[`, `]` and at the ends trimmed. Bad lines are skipped and reported
 * ("line N: …", as every mynx file):
 * - a block name that isn't [isWebAppName]: "line N: 'X' can't be an
 *   app's name", and its lines are skipped up to the next block (no
 *   further report for them);
 * - a line before any block: "line N: expected [app] first";
 * - a line without `=`: "line N: expected permission = allow or deny";
 * - an unknown permission: "line N: unknown permission 'P' (location,
 *   camera, microphone, clipboard, notifications, bluetooth, serial)";
 * - a value other than `allow`/`deny`: "line N: P is allow or deny";
 * - a permission given twice in a block: the last good line counts,
 *   reported ("line N: P is already set on line M: the last counts");
 * - a block given twice: its lines merge, reported once ("line N: [X]
 *   is already on line M").
 * An app that isn't installed is fine: its answers wait for it. A
 * missing file means every answer is "ask".
 *
 * Core reads the file at every call that needs a permission (no cache:
 * a hand edit counts at once). Writes ([setAppPermission],
 * [resetAppPermissions]) change only the line or block concerned and
 * keep the rest, comments included; a new file starts with
 * [NEW_APP_PERMISSIONS]; a new block goes at the end, after one blank
 * line; `ask` removes the line (and a block left empty, with its
 * header). Written atomically (a `.tmp` renamed into place). Undo
 * covers the file as any in `~/.config/mynx/`: each write names its
 * change (below) through [AppPermissions]' `changed`.
 *
 * ASKING ([AppPermissions.check], used by [Bridge])
 *
 * - `allow`: the call goes on.
 * - `deny`: refused with code `denied`: "Weather isn't allowed to use
 *   your location (mynx app permissions weather location allow)" (the
 *   app's label, [AppPermission.what], the app's name and the word).
 * - not listed: when the app's screen is in front
 *   ([BridgeHost.inFront]), [BridgeHost.ask] shows mynx's dialog
 *   ("Weather wants to use your location", **Allow** / **Don't
 *   allow**; no "only this time", maintainer's answer). Either answer
 *   is written to the file (change "app permission weather location
 *   allow (asked)") and logged (`log bridge permission location: allow
 *   (asked)`), then the call goes on or is refused `denied`. A dialog
 *   closed with neither (back, the screen finishing): refused `closed`
 *   "Weather's question about your location was closed", nothing
 *   written. Not in front: refused (`refused`) "Weather must be on
 *   screen to ask for your location", nothing written.
 * - Calls needing the same app's same permission while it asks wait
 *   for the one answer (one dialog). Each session's waiting calls are
 *   refused `closed` when it closes; the dialog is dismissed when no
 *   call waits for it any more.
 *
 * REQUESTS ([APP_PERMISSION_REQUEST_NAMES], [AppPermissions.requests];
 * [Later]s answered at once, on the main thread)
 *
 * - `app-permissions [NAME]` →
 *   `{"ok":true,"apps":[{"name","label","installed":BOOL,
 *   "permissions":{"location":"allow"|"deny"|"ask",…}}]}`: every
 *   [AppPermission] for each app, in [AppPermission] order; without
 *   NAME, every installed app and every app in the file, sorted by
 *   name (`label` null for one not installed). NAME neither installed
 *   nor in the file: "no app named NAME (mynx app list shows them)".
 * - `app-permission-set NAME PERMISSION allow|deny|ask` →
 *   `{"ok":true,"name","permission","value"}`; change "app permission
 *   NAME PERMISSION VALUE". `app-permission-set NAME reset` → removes
 *   the block, `{"ok":true,"name","reset":true}`; change "app
 *   permissions NAME reset". Refused: a bad name ("'X' can't be an
 *   app's name"), an unknown permission (as the file says it), a bad value ("P is
 *   allow, deny or ask"), too few lines ("app-permission-set needs NAME
 *   PERMISSION allow|deny|ask, or NAME reset"). NAME need not be
 *   installed.
 */

/** The apps' permissions file, in [CONFIG_DIR]. */
const val APP_PERMISSIONS_CONF = "app-permissions.conf"

/** What a new `app-permissions.conf` starts with. */
const val NEW_APP_PERMISSIONS = """# What each app may use: allow or deny. Not listed: asked the
# first time. mynx app permissions changes it.
"""

/** Every request [AppPermissions.requests] answers. */
val APP_PERMISSION_REQUEST_NAMES = listOf("app-permissions", "app-permission-set")

/**
 * A permission an app asks for: its [word] in the file and in
 * `mynx app permissions`, and [what] it is in sentences ("Weather wants
 * to use [what]").
 */
enum class AppPermission(val word: String, val what: String) {
    LOCATION("location", "your location"),
    CAMERA("camera", "the camera"),
    MICROPHONE("microphone", "the microphone"),
    /** Reading the clipboard; writing it needs nothing. */
    CLIPBOARD("clipboard", "your clipboard"),
    NOTIFICATIONS("notifications", "notifications"),
    /** Every `ble-*` call. */
    BLUETOOTH("bluetooth", "Bluetooth"),
    /** Every `serial-*` call, USB or Bluetooth ports. */
    SERIAL("serial", "serial ports"),
    ;

    companion object {
        fun of(word: String): AppPermission? = entries.firstOrNull { it.word == word }
    }
}

/** An app's answer for one permission; [ASK] is "not listed". */
enum class PermissionAnswer(val word: String) {
    ALLOW("allow"), DENY("deny"), ASK("ask");

    companion object {
        fun of(word: String): PermissionAnswer? = entries.firstOrNull { it.word == word }
    }
}

/** The file as parsed: each app's listed answers (only `allow`/`deny`), and the problems. */
data class ParsedAppPermissions(
    val apps: Map<String, Map<AppPermission, PermissionAnswer>>,
    val problems: List<String>,
)

private val PERMISSION_WORDS = AppPermission.entries.joinToString(", ") { it.word }

private fun unknownPermission(word: String) = "unknown permission '$word' ($PERMISSION_WORDS)"

private fun badAppName(name: String) = "'$name' can't be an app's name"

// A block's header line: `[NAME]`, spaces trimmed; null for any other line.
private fun blockName(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
    return trimmed.substring(1, trimmed.length - 1).trim()
}

private fun isComment(line: String) = line.trim().let { it.isEmpty() || it.startsWith("#") }

// A `permission = value` line's permission word, trimmed; null for comments and headers.
private fun permissionWord(line: String): String? =
    if (isComment(line) || blockName(line) != null || '=' !in line) null else line.substringBefore('=').trim()

/** Parses `app-permissions.conf` (see THE FILE). */
fun parseAppPermissions(text: String): ParsedAppPermissions {
    val apps = linkedMapOf<String, MutableMap<AppPermission, PermissionAnswer>>()
    val problems = mutableListOf<String>()
    val blockOn = mutableMapOf<String, Int>()
    val setOn = mutableMapOf<Pair<String, AppPermission>, Int>()
    var current: String? = null
    var skipping = false
    text.lines().forEachIndexed { i, line ->
        val number = i + 1
        fun problem(text: String) { problems += "line $number: $text" }
        if (isComment(line)) return@forEachIndexed
        val name = blockName(line)
        if (name != null) {
            if (!isWebAppName(name)) {
                problem(badAppName(name))
                skipping = true
                current = null
                return@forEachIndexed
            }
            skipping = false
            current = name
            blockOn[name]?.let { problem("[$name] is already on line $it") } ?: run { blockOn[name] = number }
            apps.getOrPut(name) { linkedMapOf() }
            return@forEachIndexed
        }
        if (skipping) return@forEachIndexed
        val app = current ?: return@forEachIndexed problem("expected [app] first")
        if ('=' !in line) return@forEachIndexed problem("expected permission = allow or deny")
        val word = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        val permission = AppPermission.of(word) ?: return@forEachIndexed problem(unknownPermission(word))
        val answer = PermissionAnswer.of(value)?.takeIf { it != PermissionAnswer.ASK }
            ?: return@forEachIndexed problem("$word is allow or deny")
        setOn[app to permission]?.let { problem("$word is already set on line $it: the last counts") }
        setOn[app to permission] = number
        apps.getValue(app)[permission] = answer
    }
    return ParsedAppPermissions(apps, problems)
}

// The lines of each of [app]'s blocks: (header index, end index exclusive).
private fun blocksOf(lines: List<String>, app: String): List<IntRange> {
    val headers = lines.indices.filter { blockName(lines[it]) != null }
    return headers.mapIndexedNotNull { n, at ->
        if (blockName(lines[at]) != app) return@mapIndexedNotNull null
        at until (headers.getOrNull(n + 1) ?: lines.size)
    }
}

private fun joinLines(lines: List<String>): String =
    lines.dropLastWhile { it.isBlank() }.joinToString("\n", postfix = "\n")

private fun splitLines(text: String): List<String> = text.removeSuffix("\n").split("\n")

fun loadAppPermissions(file: File): ParsedAppPermissions =
    if (file.isFile) parseAppPermissions(file.readText()) else ParsedAppPermissions(emptyMap(), emptyList())

/**
 * [text] (null: no file yet) with [app]'s [permission] set to [answer]
 * ([PermissionAnswer.ASK] removes the line, and an emptied block);
 * everything else kept. Fails with a message for people on a bad name.
 */
fun setAppPermission(text: String?, app: String, permission: AppPermission, answer: PermissionAnswer): Result<String> {
    if (!isWebAppName(app)) return Result.failure(IllegalArgumentException(badAppName(app)))
    val lines = splitLines(text ?: NEW_APP_PERMISSIONS).toMutableList()
    val blocks = blocksOf(lines, app)
    val matching = blocks.flatMap { block -> block.drop(1).filter { permissionWord(lines[it]) == permission.word } }
    val line = "${permission.word} = ${answer.word}"
    when {
        answer == PermissionAnswer.ASK -> {
            if (matching.isEmpty()) return Result.success(text ?: NEW_APP_PERMISSIONS)
            matching.sortedDescending().forEach { lines.removeAt(it) }
            for (block in blocksOf(lines, app).asReversed()) {
                if (block.drop(1).all { lines[it].isBlank() }) block.reversed().forEach { lines.removeAt(it) }
            }
        }
        matching.isNotEmpty() -> {
            lines[matching.first()] = line
            matching.drop(1).sortedDescending().forEach { lines.removeAt(it) }
        }
        blocks.isNotEmpty() -> {
            val block = blocks.last()
            lines.add(block.last { lines[it].isNotBlank() } + 1, line)
        }
        else -> {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) lines += ""
            lines += "[$app]"
            lines += line
        }
    }
    return Result.success(joinLines(lines))
}

/** [text] without [app]'s block (every one of them, if given twice); everything else kept. */
fun resetAppPermissions(text: String, app: String): String {
    val lines = splitLines(text).toMutableList()
    val blocks = blocksOf(lines, app)
    if (blocks.isEmpty()) return text
    for (block in blocks.asReversed()) block.reversed().forEach { lines.removeAt(it) }
    return joinLines(lines)
}

/** The file's problems, for [checkConfig] (nothing when there's no file). */
fun appPermissionProblems(home: File): List<ConfigProblems> {
    val file = File(home, "$CONFIG_DIR/$APP_PERMISSIONS_CONF")
    if (!file.isFile) return emptyList()
    val problems = parseAppPermissions(file.readText()).problems
    return if (problems.isEmpty()) emptyList() else listOf(ConfigProblems("~/$CONFIG_DIR/$APP_PERMISSIONS_CONF", problems))
}

/** Why a permission check didn't let a call go on: [code] is `denied`, `refused` or `closed` ([BridgeError]). */
data class PermissionRefusal(val code: BridgeError, val message: String)

/**
 * The permissions of every app, from the file under [home] (root's
 * home). [apps] gives labels and which apps are installed; [changed]
 * gets each change's name after the file was written, for undo (the
 * service wires it to [MynxRequests.recordChange]). Main thread only.
 */
class AppPermissions(
    private val home: File,
    private val apps: WebApps,
    private val changed: (String) -> Unit = {},
) {
    private val file = File(home, "$CONFIG_DIR/$APP_PERMISSIONS_CONF")

    /** The dialogs open: each app's permission, and the callers waiting for its answer. */
    private class Asking(val waiters: MutableList<Waiter>) {
        var dismiss: () -> Unit = {}
    }

    private class Waiter(val done: (PermissionRefusal?) -> Unit) {
        var finished = false

        fun finish(refusal: PermissionRefusal?) {
            if (finished) return
            finished = true
            done(refusal)
        }
    }

    private val asking = mutableMapOf<Pair<String, AppPermission>, Asking>()

    private fun read() = loadAppPermissions(file).apps

    /** [app]'s answer for [permission], the file read again. */
    fun answer(app: String, permission: AppPermission): PermissionAnswer =
        read()[app]?.get(permission) ?: PermissionAnswer.ASK

    /** Writes [answer] for [app]'s [permission]; [asked] adds " (asked)" to the change's name. */
    fun set(app: String, permission: AppPermission, answer: PermissionAnswer, asked: Boolean = false): Result<Unit> =
        write(app, permission, answer).onSuccess {
            changed("app permission $app ${permission.word} ${answer.word}" + if (asked) " (asked)" else "")
        }

    private fun write(app: String, permission: AppPermission, answer: PermissionAnswer): Result<Unit> = runCatching {
        val text = file.takeIf { it.isFile }?.readText()
        writeFile(setAppPermission(text, app, permission, answer).getOrThrow())
    }

    private fun writeFile(text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("couldn't write ~/$CONFIG_DIR/$APP_PERMISSIONS_CONF")
        }
    }

    /**
     * Decides whether [app] may use [permission] now (see ASKING),
     * asking through [host] when it isn't listed. [done] gets null to go
     * on, else the refusal; once, on the main thread, perhaps before
     * this returns. The returned handle withdraws this caller (a
     * session closing): [done] is then called with `closed`, and the
     * dialog dismissed once nobody waits.
     */
    fun check(app: WebApp, permission: AppPermission, host: BridgeHost, done: (PermissionRefusal?) -> Unit): () -> Unit {
        when (answer(app.name, permission)) {
            PermissionAnswer.ALLOW -> { done(null); return {} }
            PermissionAnswer.DENY -> { done(denied(app, permission)); return {} }
            PermissionAnswer.ASK -> {}
        }
        val key = app.name to permission
        val waiter = Waiter(done)
        val open = asking[key]
        if (open != null) {
            open.waiters += waiter
        } else {
            if (!host.inFront(app.name)) {
                done(PermissionRefusal(BridgeError.REFUSED, "${app.label} must be on screen to ask for ${permission.what}"))
                return {}
            }
            val entry = Asking(mutableListOf(waiter))
            asking[key] = entry
            entry.dismiss = host.ask(app, permission) { allow -> answered(app, permission, entry, allow, host) }
        }
        return {
            val entry = asking[key]
            if (!waiter.finished && entry != null && entry.waiters.remove(waiter)) {
                waiter.finish(closed(app, permission))
                if (entry.waiters.isEmpty()) {
                    asking.remove(key)
                    entry.dismiss()
                }
            }
        }
    }

    private fun answered(app: WebApp, permission: AppPermission, entry: Asking, allow: Boolean?, host: BridgeHost) {
        val key = app.name to permission
        if (asking[key] === entry) asking.remove(key)
        val waiters = entry.waiters.toList()
        entry.waiters.clear()
        if (allow == null) {
            waiters.forEach { it.finish(closed(app, permission)) }
            return
        }
        val answer = if (allow) PermissionAnswer.ALLOW else PermissionAnswer.DENY
        set(app.name, permission, answer, asked = true).onFailure {
            host.log(app.name, WebAppLogLevel.WARN, "permission ${permission.word}: ${it.message}")
        }
        host.log(app.name, WebAppLogLevel.LOG, "permission ${permission.word}: ${answer.word} (asked)")
        waiters.forEach { it.finish(if (allow) null else denied(app, permission)) }
    }

    private fun denied(app: WebApp, permission: AppPermission) = PermissionRefusal(
        BridgeError.DENIED,
        "${app.label} isn't allowed to use ${permission.what} (mynx app permissions ${app.name} ${permission.word} allow)",
    )

    private fun closed(app: WebApp, permission: AppPermission) =
        PermissionRefusal(BridgeError.CLOSED, "${app.label}'s question about ${permission.what} was closed")

    /** `app-permissions` and `app-permission-set`, for [MynxRequests]'s `later`. */
    fun requests(): Map<String, Later> = mapOf(
        "app-permissions" to Later(REQUEST_SECONDS) { args, reply -> reply.answer(list(args)) },
        "app-permission-set" to Later(REQUEST_SECONDS, change = ::changeOf) { args, reply -> reply.answer(setFrom(args)) },
    )

    private fun list(args: List<String>): String {
        val listed = read()
        val installed = apps.list().associateBy { it.name }
        val name = args.firstOrNull()?.trim().orEmpty()
        val names = if (name.isEmpty()) {
            (installed.keys + listed.keys).toSortedSet().toList()
        } else {
            if (name !in installed && name !in listed) return refusal("no app named $name (mynx app list shows them)")
            listOf(name)
        }
        return ok("apps" to array(names.map { app ->
            val answers = listed[app].orEmpty()
            obj(
                "name" to json(app),
                "label" to (installed[app]?.label?.let(::json) ?: "null"),
                "installed" to (app in installed).toString(),
                "permissions" to obj(*AppPermission.entries.map {
                    it.word to json((answers[it] ?: PermissionAnswer.ASK).word)
                }.toTypedArray()),
            )
        }))
    }

    private fun setFrom(args: List<String>): String {
        val words = args.map { it.trim() }
        val name = words.getOrNull(0).orEmpty()
        val usage = "app-permission-set needs NAME PERMISSION allow|deny|ask, or NAME reset"
        if (words.size == 2 && words[1] == "reset") {
            if (!isWebAppName(name)) return refusal(badAppName(name))
            return runCatching {
                if (file.isFile) writeFile(resetAppPermissions(file.readText(), name))
                ok("name" to json(name), "reset" to "true")
            }.getOrElse { refusal(it.message ?: "couldn't write the file") }
        }
        if (words.size < 3) return refusal(usage)
        if (!isWebAppName(name)) return refusal(badAppName(name))
        val permission = AppPermission.of(words[1]) ?: return refusal(unknownPermission(words[1]))
        val answer = PermissionAnswer.of(words[2]) ?: return refusal("${permission.word} is allow, deny or ask")
        return write(name, permission, answer).fold(
            { ok("name" to json(name), "permission" to json(permission.word), "value" to json(answer.word)) },
            { refusal(it.message ?: "couldn't write the file") },
        )
    }

    private fun changeOf(args: List<String>): String {
        val words = args.map { it.trim() }
        return if (words.size == 2 && words[1] == "reset") "app permissions ${words[0]} reset"
        else "app permission ${words.joinToString(" ")}"
    }

    private companion object {
        const val REQUEST_SECONDS = 10
    }
}
