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
 *   app's name"), an unknown permission or value (as the file's
 *   messages), too few lines ("app-permission-set needs NAME
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

/** Parses `app-permissions.conf` (see THE FILE). */
fun parseAppPermissions(text: String): ParsedAppPermissions = TODO("part A")

fun loadAppPermissions(file: File): ParsedAppPermissions =
    if (file.isFile) parseAppPermissions(file.readText()) else ParsedAppPermissions(emptyMap(), emptyList())

/**
 * [text] (null: no file yet) with [app]'s [permission] set to [answer]
 * ([PermissionAnswer.ASK] removes the line, and an emptied block);
 * everything else kept. Fails with a message for people on a bad name.
 */
fun setAppPermission(text: String?, app: String, permission: AppPermission, answer: PermissionAnswer): Result<String> =
    TODO("part A")

/** [text] without [app]'s block (every one of them, if given twice); everything else kept. */
fun resetAppPermissions(text: String, app: String): String = TODO("part A")

/** The file's problems, for [checkConfig] (nothing when there's no file). */
fun appPermissionProblems(home: File): List<ConfigProblems> = TODO("part A")

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
    /** [app]'s answer for [permission], the file read again. */
    fun answer(app: String, permission: AppPermission): PermissionAnswer = TODO("part A")

    /** Writes [answer] for [app]'s [permission]; [asked] adds " (asked)" to the change's name. */
    fun set(app: String, permission: AppPermission, answer: PermissionAnswer, asked: Boolean = false): Result<Unit> =
        TODO("part A")

    /**
     * Decides whether [app] may use [permission] now (see ASKING),
     * asking through [host] when it isn't listed. [done] gets null to go
     * on, else the refusal; once, on the main thread, perhaps before
     * this returns. The returned handle withdraws this caller (a
     * session closing): [done] is then called with `closed`, and the
     * dialog dismissed once nobody waits.
     */
    fun check(app: WebApp, permission: AppPermission, host: BridgeHost, done: (PermissionRefusal?) -> Unit): () -> Unit =
        TODO("part A")

    /** `app-permissions` and `app-permission-set`, for [MynxRequests]'s `later`. */
    fun requests(): Map<String, Later> = TODO("part A")
}
