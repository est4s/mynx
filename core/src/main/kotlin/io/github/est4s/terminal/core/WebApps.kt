package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files

/*
 * Apps (web apps): the contract the parts share (work log, "Next", 0).
 * Part A (core) builds everything in this file and [checkConfig]'s
 * part; part B (the Android app) builds `WebAppActivity` and the
 * [WebAppHost]; part C (`mynx app`, the menu, the guide) builds on the
 * requests and the rules below. Change the shapes here first, then
 * everything else.
 *
 * WHAT AN APP IS
 *
 * A folder `~/apps/NAME/` (the host folder `HOME/apps/NAME`, [WebApps]'s
 * `home` being root's home in the rootfs) or `/opt/mynx/apps/NAME/` (the
 * tools' own folder, `toolsDir/apps/NAME`: [TOOLS_MOUNT] isn't under
 * the rootfs, so [hostPath] doesn't know it) that has
 * - an `index.html` (a file, links followed): a **static** app, or
 * - an `app.conf` with a valid `url`: a **served** app (its
 *   `index.html`, if any, is ignored).
 * `~/apps/NAME` may be a link to a folder elsewhere
 * (`ln -s ~/pixel-hero/assets ~/apps/pixel-hero`); links are followed
 * as Debian would (below). Executable files in `~/apps` are terminal
 * apps from before (the menu lists them), never apps; plain files,
 * dangling links and folders with neither are skipped quietly.
 *
 * NAME is a DNS label, as it's the origin's host: [isWebAppName],
 * `[a-z0-9-]`, 1 to 40 characters, not starting or ending with `-`
 * (agent's decision: the plan said `[a-z0-9-]{1,40}`; a DNS label
 * can't start or end with a hyphen). A folder with another name and an
 * `index.html` or `app.conf` isn't an app: [checkConfig] says why.
 * For the same NAME, `~/apps` wins over `/opt/mynx/apps` (only an app
 * shadows: a folder in `~/apps` that isn't one doesn't hide the tools').
 *
 * APP.CONF
 *
 * Optional, `key = value` per line, `#` comments and blank lines,
 * spaces around `=` and at the ends trimmed. Bad lines are skipped and
 * reported, as in every mynx file ("line N: …"):
 * - `name`: the label, up to [WEB_APP_LABEL_MAX] characters (longer is
 *   cut, and reported); empty is a problem.
 * - `icon`: a PNG, JPEG or WebP file in the folder, relative to it
 *   (confined as pages are), for part 2's shortcuts. Missing, outside
 *   the folder or another type: a problem, and no icon.
 * - `url`: `http://127.0.0.1:PORT[/PATH][?QUERY]` or
 *   `http://localhost:PORT…`, PORT 1024 to 65535 (nothing here can
 *   listen below). Anything else is a problem and the line is skipped.
 * - `service`: a service name ([isServiceName]); only with `url` (a
 *   problem otherwise). One that isn't in `services.conf` is a problem
 *   too ([checkConfig] and `app-list`'s `problems`); opening the app
 *   then refuses.
 * - Any other key: "line N: unknown key 'K'". A key given twice: the
 *   last good line counts, reported (a bad line is skipped as if it
 *   weren't there).
 * - A folder with an `app.conf` but no `url` and no `index.html` isn't
 *   an app; [checkConfig] says so ("not an app: no index.html, and no
 *   url").
 *
 * THE LABEL ([WebApp.label]; the menu's bash scan must give the same,
 * tested on `tests/fixtures/apps/`): `app.conf`'s `name`; else the
 * `<title>` of `index.html`: the text between the first `<title…>` (any
 * case, attributes allowed) and the next `</title>` within the file's
 * first 4096 bytes, `&amp; &lt; &gt; &quot; &#39;` decoded, runs of
 * whitespace (line breaks too) made one space, trimmed; when that's
 * empty or missing, the folder name with each `-`-separated word
 * capitalised (`pixel-hero` → "Pixel Hero", as the menu's `game_label`).
 * Every label is cut to [WEB_APP_LABEL_MAX] characters.
 *
 * LINKS, AS DEBIAN SEES THEM ([WebApps.resolve])
 *
 * Paths are resolved in Debian's terms (guest paths), one component at
 * a time, so host-side links above the rootfs (`/data/user/0`) never
 * count: a relative link target is taken against its folder; an
 * absolute one is a guest path, except that proot's link2symlink fakes
 * hard links as links to `.l2s.*` files holding **host** paths (`NAME`
 * → `/data/data/…/rootfs/…/.l2s.NAME0001` → `….l2s.NAME0001.0002`, the
 * file): a target whose last part starts with `.l2s.` and that lies in
 * the rootfs (either spelling of the app's data folder: compare
 * canonical paths) is turned back into a guest path. Guest paths map to
 * host files by [TOOLS_MOUNT] → `toolsDir`, else [hostPath]. More than
 * 40 links: unresolved (a loop).
 *
 * STATIC APPS: ORIGIN AND FILES
 *
 * The origin is `https://NAME.mynx.invalid` ([webAppOrigin]; `.invalid`
 * is reserved, RFC 2606; https makes it a secure context), and the
 * screen loads [WebApp.url], `https://NAME.mynx.invalid/`. The view
 * hands every request whose scheme is `https` and host
 * [webAppNameOfHost] names an app to [WebApps.respond] (on WebView's
 * own thread: it only reads files); other hosts go to the network as
 * usual. `respond` takes the method and the URL's **encoded** path
 * (`Uri.getEncodedPath()`, no query or fragment):
 * - GET and HEAD only; others: 405.
 * - The path is split at `/`, each segment percent-decoded (UTF-8; `+`
 *   stays `+`). A decoded segment `..` or `.`, one holding `/` or NUL,
 *   or starting with `.l2s.`, or bad percent-encoding: 404. So
 *   `/../x`, `/%2e%2e/x` and `/a%2fb` are all 404 (WebView already
 *   folds plain `..`; encoded ones arrive as they are).
 * - An empty path or one ending in `/` means that folder's
 *   `index.html`. A folder asked without the `/`: 404 (WebView can't
 *   take a redirect from an intercept).
 * - The file is resolved as above; it must exist, be a regular file,
 *   and its resolved guest path must be the app's root (its folder,
 *   resolved) or below it. Anything else is 404: a link out of the
 *   folder is as missing as a file that isn't there.
 * - 200 answers carry the file, [webAppMimeType] (of the name asked
 *   for, not a link's target: a faked hard link's is `.l2s.…`) and, for text types,
 *   the encoding `utf-8`; every answer has `Cache-Control: no-store`
 *   (so a reload sees edits). 404 and 405 have a short plain-text body.
 *   No Range requests in part 1 (whole files only).
 *
 * SERVED APPS
 *
 * The origin is the `url`'s (`http://127.0.0.1:PORT`), and the screen
 * loads the `url`. Plain http is allowed for `127.0.0.1` and
 * `localhost` only (the app's network security config). Nothing is
 * intercepted or confined, and there's no auto-reload (`mynx app
 * reload`). The port is probed on 127.0.0.1 for both spellings (on
 * Android `localhost` may be `::1`).
 *
 * AUTO-RELOAD (static apps)
 *
 * While the screen is on screen, the app polls [WebApps.fingerprint]
 * off the main thread and feeds each value to an [AutoReload], which
 * says when to poll next and when to reload: every 1000 ms; on a change,
 * again after 300 ms, and reload once two polls agree. The fingerprint
 * covers every file under the root (links followed, confined; `.l2s.*`
 * names skipped; at most [WEB_APP_FINGERPRINT_FILES] files): each one's
 * relative path, size and mtime.
 *
 * THE LOG, FOR AGENTS ([WebAppLog])
 *
 * `/tmp/.mynx/apps/NAME.log` ([WEB_APP_LOG_DIR]; its host path is under
 * the rootfs), at most [WEB_APP_LOG_LINES] lines after each write (the
 * oldest go). Each open of the screen adds `--- opened YYYY-MM-DD
 * HH:MM:SS URL`; then one line per entry:
 * `[HH:MM:SS.mmm] LEVEL SOURCE:LINE MESSAGE`, local time. LEVEL is
 * `debug`, `log`, `warn` or `error` (Android's TIP and DEBUG are
 * `debug`); SOURCE is the script's path when it's on the app's origin
 * (`/app.js`), else its full URL; `:LINE` is left out when 0, and
 * `SOURCE:LINE ` when there's no source. Console messages
 * (`onConsoleMessage`, which uncaught errors reach too), load errors
 * (`error`, "load failed: DESCRIPTION", source the URL) and HTTP errors
 * of the main page and subresources (`error`, "HTTP 404", source the
 * URL) go there, but for a 404 for exactly `/favicon.ico` on the app's
 * origin: the WebView asks for that itself (an app's page linking a
 * missing icon by another path, or a query, still logs it). A message's further lines follow, each indented by four
 * spaces (each counts as a line). `mynx app log NAME [-n N]` reads the
 * file itself; nothing asks the app.
 *
 * REQUESTS ([WEB_APP_REQUEST_NAMES], [webAppRequests]; all [Later]s,
 * answered on the main thread; arguments one per line as usual)
 *
 * - `app-list` → `{"ok":true,"apps":[APP…]}`, sorted by name, the
 *   apps there are (shadowed ones left out). APP:
 *   `{"name","label","path","kind":"static"|"served","origin","url",
 *   "service","icon","problems":[…]}`: `path` is the Debian path as
 *   listed (`/root/apps/sketch`, `/opt/mynx/apps/sketch`, links not
 *   resolved), `service` and `icon` (the Debian path) null when there's
 *   none, `problems` the app's `app.conf` problems (strings, as
 *   [checkConfig] gives them).
 * - `app-open NAME` (waits up to [WEB_APP_OPEN_SECONDS]) →
 *   `{"ok":true,"name","opened":true,"origin","url",
 *   "started_service":BOOL}`, once the screen was asked to open (not
 *   once the page has loaded). In order, refusing with:
 *   - no NAME: "app-open needs a name";
 *   - unknown: "no app named NAME (mynx app list shows them)";
 *   - [WebAppHost.onScreen] false: "the app must be on screen to open
 *     an app: bring mynx to the front and try again" (Android won't
 *     start an activity from the background);
 *   - served with a `service`: [WebAppServices.status] null → "no
 *     service named S (app.conf's service; mynx service list shows
 *     them)"; STOPPING → "S is stopping: try again in a moment";
 *     STOPPED or FAILED → [WebAppServices.start] (its refusal is the
 *     answer), and the launcher remembers it started S for NAME unless
 *     S has `autostart = yes`; RUNNING → nothing. Then it probes the
 *     port ([WebAppHost.portAnswers]) every 250 ms until it answers, for
 *     up to [WEB_APP_PORT_WAIT_MS]; after that: "S didn't answer on
 *     127.0.0.1:PORT within 15 s (mynx service log S shows its
 *     output)", and a service this request started is stopped again
 *     and forgotten (a backend that never answers isn't left running;
 *     nor is it when `mynx` cancels the request while it waits);
 *   - served without a `service`: one probe; none: "nothing answers on
 *     127.0.0.1:PORT (app.conf's url)";
 *   - [WebAppHost.open] refused: its reason (a service started by this
 *     request is stopped again).
 *   `started_service` says this request started it. Opening an app
 *   whose screen is open brings it back (Android's one task per app);
 *   a service started for it earlier stays remembered.
 * - `app-reload NAME` → `{"ok":true,"name"}` after
 *   [WebAppHost.reload]; refuses "NAME isn't open" when it isn't, and
 *   as `app-open` for no name or an unknown app.
 *
 * THE SERVICE'S LIFECYCLE
 *
 * When an app's screen finishes (closed, back at its first page, swiped
 * from recents: not merely going to the background), the activity tells
 * the service, which calls [WebAppLauncher.screenClosed]. If the
 * launcher started the app's service, it forgets that and stops it with
 * [WebAppServices.stop], unless another app using the same service has
 * its screen open ([WebAppHost.isOpen]: then that one inherits it) or
 * the service isn't running any more. A service already running when
 * the app opened, or with `autostart = yes`, is never stopped. What the
 * launcher remembers is in memory only: lost when Android kills the
 * app, which is fine (services come back as they do today).
 *
 * PART 2 (work log "Next", 0): the bridge is `Bridge.kt`, permissions
 * `AppPermissions.kt`; this file gets the keys, shortcuts and the open
 * step below (part A builds them, part B the Android side).
 *
 * APP.CONF, PART 2: two more keys, checked as the others (the line
 * skipped on a bad value), both in `app-list`'s APP
 * (`"fullscreen":"no"|"yes"|"edge","screen_awake":BOOL`) and [WebApp]:
 * - `fullscreen = no|yes|edge` ([Fullscreen], default no; maintainer's
 *   decision, 2026-11-08, over the plan's first answer; other values:
 *   "line N: fullscreen is no, yes or edge"):
 *   - `yes`: the status and navigation bars hidden (API 30+:
 *     `WindowInsetsController`, swipe to show them for a moment; 26-29:
 *     the immersive-sticky flags), hidden again after the keyboard or
 *     a rotation; the page is kept clear of the camera cutout (padded
 *     for it, as for the keyboard).
 *   - `edge`: bars hidden as for `yes`, and the page runs under the
 *     cutout too (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, ALWAYS
 *     on API 30+); only the keyboard is padded for. The guide tells
 *     games to keep controls clear of the corners
 *     (`env(safe-area-inset-*)`).
 * - `screen-awake = yes|no` (default no; else "line N: screen-awake is
 *   yes or no"): `FLAG_KEEP_SCREEN_ON` on the app's window, which
 *   Android honours only while it's visible.
 *
 * HOME SCREEN SHORTCUTS
 *
 * `app-shortcut NAME` (a [Later], [WEB_APP_SHORTCUT_SECONDS]; part A
 * adds it to [WEB_APP_REQUEST_NAMES]) → `{"ok":true,"name","id"}` once
 * Android was asked ([WebAppHost.pinShortcut]: `ShortcutManager.
 * requestPinShortcut`, which shows Android's own "Add to home screen"
 * dialog; the answer doesn't wait for it). Refused as `app-open` for no
 * name or an unknown app, "mynx must be on screen to add a shortcut"
 * when [WebAppHost.onScreen] is false, and with the host's reason (a
 * launcher that can't pin). The menu's Apps list ends with "Add to home
 * screen…", which lists the apps and runs `mynx app shortcut NAME`.
 *
 * [appShortcut] decides what it shows: id [webAppShortcutId] (`app-NAME`),
 * the label, and the icon:
 * - with `app.conf`'s `icon`: that file (host path), made adaptive by
 *   the app: scaled to fit [SHORTCUT_SAFE_ZONE] of the 108 dp square
 *   (the safe zone's 66 dp) keeping its proportions, centred, on
 *   [AppShortcut.background], the theme's background;
 * - else a generated one: [AppShortcut.letter], the label's first letter
 *   or digit in upper case (none: the name's first character), drawn in
 *   [AppShortcut.foreground] on [AppShortcut.background]. The background
 *   is one of the theme's chromatic colours, `color1`-`color6` and
 *   `color9`-`color14` in that order, picked by the name's
 *   `String.hashCode()` (stable by Kotlin's and Java's spec) mod 12, made
 *   non-negative (`Math.floorMod`); the foreground is the theme's
 *   `background` or `foreground`, whichever has the higher [contrast]
 *   with it (the background on ties). The theme is the user's colours
 *   (`colors.properties` over Neon) at the time it's made.
 * The intent opens `WebAppActivity` for `mynx-app://NAME` with the
 * extra [WEB_APP_FROM_SHORTCUT]: it starts and binds the service if
 * mynx isn't running, opens `MainActivity` instead when Debian isn't
 * set up yet, then runs [WebAppLauncher.openStep] itself, showing
 * "Starting LABEL…" and any error as text on the screen (and in the
 * log). Changed labels and icons update a pinned shortcut at the next
 * open ([WebAppHost.updateShortcut], `updateShortcuts`); a shortcut for
 * an app that's gone shows "no app named NAME (mynx app list shows
 * them)" on its screen.
 *
 * THE OPEN STEP ([WebAppLauncher.openStep])
 *
 * What `app-open` does after its on-screen check, shared with the
 * shortcut's start: the app looked up, its service started if needed
 * and remembered, the port waited for, then [show] (the host's
 * [WebAppHost.open] for `app-open`; the activity loading itself for a
 * shortcut); refusals worded as `app-open`'s and a service it started
 * stopped again on failure or cancel. `app-open` keeps its answer.
 */

/** The folder of the user's apps, in root's home. */
const val WEB_APPS_DIR = "apps"
/** The tools' own apps, in Debian ([TOOLS_MOUNT]). */
const val TOOLS_WEB_APPS = "$TOOLS_MOUNT/apps"
/** The apps' logs, in Debian. */
const val WEB_APP_LOG_DIR = "/tmp/.mynx/apps"
/** An app's optional settings file, in its folder. */
const val WEB_APP_CONF = "app.conf"
/** The domain under which each static app gets its own host (`NAME.mynx.invalid`). */
const val WEB_APP_DOMAIN = "mynx.invalid"
/**
 * The scheme of the data URI each app's screen is started with
 * (`mynx-app://NAME`): Android keeps one task per URI
 * (`documentLaunchMode="intoExisting"`), so opening it again brings it back.
 */
const val WEB_APP_TASK_SCHEME = "mynx-app"
const val WEB_APP_LABEL_MAX = 20
const val WEB_APP_LOG_LINES = 1000
const val WEB_APP_PORT_WAIT_MS = 15_000L
const val WEB_APP_PORT_PROBE_MS = 250L
/** How long `mynx` waits for `app-open`: the port wait and some. */
const val WEB_APP_OPEN_SECONDS = 30
const val WEB_APP_FINGERPRINT_FILES = 5000

/** Every request [webAppRequests] answers. */
val WEB_APP_REQUEST_NAMES = listOf("app-list", "app-open", "app-reload", "app-shortcut")

private val WEB_APP_NAME = Regex("[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?")

/** Whether [name] can be an app's name (a DNS label: see the contract above). */
fun isWebAppName(name: String): Boolean = WEB_APP_NAME.matches(name)

/** `https://NAME.mynx.invalid`, a static app's origin (no slash at the end). */
fun webAppOrigin(name: String): String = "https://$name.$WEB_APP_DOMAIN"

/** The app [host] (a URL's host, any case) belongs to: `sketch.mynx.invalid` → `sketch`; null for any other host or a bad name. */
fun webAppNameOfHost(host: String): String? {
    val lower = host.lowercase()
    if (!lower.endsWith(".$WEB_APP_DOMAIN")) return null
    return lower.removeSuffix(".$WEB_APP_DOMAIN").takeIf(::isWebAppName)
}

private val MIME_TYPES = mapOf(
    "html" to "text/html", "htm" to "text/html",
    "js" to "text/javascript", "mjs" to "text/javascript",
    "css" to "text/css",
    "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
    "json" to "application/json", "map" to "application/json",
    "webmanifest" to "application/manifest+json",
    "xml" to "application/xml",
    "svg" to "image/svg+xml",
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "avif" to "image/avif", "ico" to "image/x-icon", "bmp" to "image/bmp",
    "wasm" to "application/wasm",
    "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
    "wav" to "audio/wav", "m4a" to "audio/mp4", "flac" to "audio/flac", "mid" to "audio/midi", "midi" to "audio/midi",
    "mp4" to "video/mp4", "webm" to "video/webm", "ogv" to "video/ogg",
    "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
    "pdf" to "application/pdf", "zip" to "application/zip",
)

private val TEXT_TYPES = setOf(
    "text/javascript", "application/json", "application/manifest+json", "application/xml", "image/svg+xml",
)

/** Whether answers of [mime] carry the encoding `utf-8`. */
internal fun isTextMime(mime: String) = mime.startsWith("text/") || mime in TEXT_TYPES

/** The MIME type for a file [name], by its ending in any case; `application/octet-stream` when unknown. */
fun webAppMimeType(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot < 0) return "application/octet-stream"
    return MIME_TYPES[name.substring(dot + 1).lowercase()] ?: "application/octet-stream"
}

/** An app's `app.conf`, as parsed; null where it doesn't say. */
data class AppConf(
    val name: String? = null,
    /** Relative to the app's folder, as written. */
    val icon: String? = null,
    val url: String? = null,
    val service: String? = null,
    val fullscreen: Fullscreen = Fullscreen.NO,
    val screenAwake: Boolean = false,
)

/** `app.conf`'s `fullscreen` (see APP.CONF, PART 2): [word] as written there and in `app-list`. */
enum class Fullscreen(val word: String) {
    NO("no"), YES("yes"), EDGE("edge");

    companion object {
        fun of(word: String): Fullscreen? = entries.firstOrNull { it.word == word }
    }
}

data class ParsedAppConf(val conf: AppConf, val problems: List<String>)

/**
 * Parses an `app.conf`. Checks that need the folder or `services.conf`
 * (the icon's file, the service's existence) are [WebApps]'s and
 * [checkConfig]'s.
 */
fun parseAppConf(text: String): ParsedAppConf {
    var conf = AppConf()
    val problems = mutableListOf<String>()
    val setOn = mutableMapOf<String, Int>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val number = i + 1
        fun problem(text: String) { problems += "line $number: $text" }
        if ('=' !in line) return@forEachIndexed problem("expected key = value")
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        if (key !in APP_CONF_KEYS) return@forEachIndexed problem("unknown key '$key'")
        // Only good lines count as set: a bad one is skipped, as if it weren't there.
        fun set(change: AppConf.() -> AppConf) {
            setOn[key]?.let { problem("$key is already set on line $it: the last counts") }
            setOn[key] = number
            conf = conf.change()
        }
        if (value.isEmpty() && key != "url") return@forEachIndexed problem("$key must not be empty")
        when (key) {
            "name" -> {
                val cut = value.take(WEB_APP_LABEL_MAX)
                if (cut != value) problem("name is longer than $WEB_APP_LABEL_MAX characters: cut to '$cut'")
                set { copy(name = cut) }
            }
            "icon" -> when {
                value.startsWith("/") -> problem("icon must be a file in the app's folder, relative to it")
                webAppMimeType(value) !in ICON_TYPES -> problem("icon must be a PNG, JPEG or WebP file")
                else -> set { copy(icon = value) }
            }
            "url" -> if (servedPort(value) == null) {
                problem("url must be http://127.0.0.1:PORT/… or http://localhost:PORT/…, PORT 1024 to 65535")
            } else {
                set { copy(url = value) }
            }
            "service" -> if (isServiceName(value)) set { copy(service = value) } else problem(badServiceName(value))
            "fullscreen" -> {
                val fullscreen = Fullscreen.of(value)
                if (fullscreen == null) problem("fullscreen is no, yes or edge") else set { copy(fullscreen = fullscreen) }
            }
            "screen-awake" -> when (value) {
                "yes" -> set { copy(screenAwake = true) }
                "no" -> set { copy(screenAwake = false) }
                else -> problem("screen-awake is yes or no")
            }
        }
    }
    if (conf.service != null && conf.url == null) {
        problems += "line ${setOn["service"]}: service needs a url (it's for served apps)"
        conf = conf.copy(service = null)
    }
    return ParsedAppConf(conf, problems)
}

private val APP_CONF_KEYS = setOf("name", "icon", "url", "service", "fullscreen", "screen-awake")
private val ICON_TYPES = setOf("image/png", "image/jpeg", "image/webp")
private val SERVED_URL = Regex("""http://(127\.0\.0\.1|localhost):([0-9]{1,5})(/[^\s?#]*)?(\?[^\s#]*)?""")

/** A served app's port, from its `url`; null when it isn't one `app.conf` takes. */
internal fun servedPort(url: String): Int? {
    val match = SERVED_URL.matchEntire(url) ?: return null
    return match.groupValues[2].toInt().takeIf { it in 1024..65535 }
}

private fun servedOrigin(url: String) = url.substringBefore('?').let { rest ->
    val afterScheme = rest.removePrefix("http://")
    "http://" + afterScheme.substringBefore('/')
}

private val TITLE_START = Regex("<title(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("\\s+")

/** The label from an `index.html`'s start ([html], up to 4096 bytes of it), or null: see THE LABEL. */
fun htmlTitle(html: String): String? {
    val start = TITLE_START.find(html) ?: return null
    val end = html.indexOf("</title>", start.range.last + 1, ignoreCase = true)
    if (end < 0) return null
    val text = html.substring(start.range.last + 1, end)
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace(WHITESPACE, " ").trim()
    return text.take(WEB_APP_LABEL_MAX).ifEmpty { null }
}

/** The label from a folder name: `pixel-hero` → "Pixel Hero", cut to [WEB_APP_LABEL_MAX]. */
fun folderLabel(name: String): String =
    name.split('-').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        .take(WEB_APP_LABEL_MAX)

enum class WebAppKind(val word: String) { STATIC("static"), SERVED("served") }

/**
 * One app. [path] is its Debian path as listed (links not resolved);
 * [root] its folder resolved (a guest path, link-free: what pages are
 * confined to). [url] is the page its screen loads (static:
 * `https://NAME.mynx.invalid/`), [origin] that page's origin, [port]
 * a served app's port. [icon] is a Debian path. [problems] are its
 * `app.conf`'s, with the folder checks.
 */
data class WebApp(
    val name: String,
    val label: String,
    val path: String,
    val root: String,
    val kind: WebAppKind,
    val origin: String,
    val url: String,
    val port: Int?,
    val service: String?,
    val icon: String?,
    val problems: List<String>,
    val fullscreen: Fullscreen = Fullscreen.NO,
    val screenAwake: Boolean = false,
)

/** What [WebApps.respond] answers: [file] to send for 200 (a host file), else [body]. */
data class WebAppResponse(
    val status: Int,
    val reason: String,
    val mime: String,
    /** `utf-8` for text types, else null (WebResourceResponse takes it apart from the type). */
    val encoding: String?,
    val headers: Map<String, String>,
    val file: File? = null,
    val body: String? = null,
)

/**
 * The apps on disk. [home] is root's home (a host folder in the rootfs),
 * [rootfs] the rootfs's host path, [toolsDir] the tools' host folder
 * (mounted at [TOOLS_MOUNT]). Reads files only, holds nothing: any
 * thread may use it, and the activity makes its own.
 */
class WebApps(val home: File, val rootfs: String, val toolsDir: File) {
    private val guestHome = home.absoluteFile.relativeToOrNull(File(rootfs).absoluteFile)
        ?.invariantSeparatorsPath?.let { "/$it" } ?: "/root"
    private val rootfsCanonical by lazy { runCatching { File(rootfs).canonicalPath }.getOrDefault(rootfs) }

    /** Every app, `~/apps` first winning, sorted by name. */
    fun list(): List<WebApp> {
        val found = sortedMapOf<String, WebApp>()
        for (folder in listOf(TOOLS_WEB_APPS, "$guestHome/$WEB_APPS_DIR")) {
            for (name in names(folder)) inspect(name, "$folder/$name")?.app?.let { found[name] = it }
        }
        return found.values.toList()
    }

    /** The app named [name], or null. */
    fun find(name: String): WebApp? {
        if (!isWebAppName(name)) return null
        return inspect(name, "$guestHome/$WEB_APPS_DIR/$name")?.app ?: inspect(name, "$TOOLS_WEB_APPS/$name")?.app
    }

    /** What's wrong in each folder of `~/apps` that looks like an app, by its Debian path ([webAppProblems]). */
    internal fun problems(): List<Pair<String, List<String>>> {
        val folder = "$guestHome/$WEB_APPS_DIR"
        return names(folder).mapNotNull { name ->
            val found = inspect(name, "$folder/$name") ?: return@mapNotNull null
            when {
                !isWebAppName(name) -> "~/$WEB_APPS_DIR/$name" to listOf(
                    "not a usable app name: use a-z, 0-9 and -, up to 40 characters, not starting or ending with -",
                )
                else -> "~/$WEB_APPS_DIR/$name/$WEB_APP_CONF" to found.problems
            }
        }.filter { it.second.isNotEmpty() }
    }

    private fun names(folder: String): List<String> =
        host(folder)?.list().orEmpty().sorted()

    private class Inspected(val app: WebApp?, val problems: List<String>)

    // A folder with an index.html or app.conf; app is null when it isn't one (a bad name, no url and no page).
    private fun inspect(name: String, path: String): Inspected? {
        val root = resolve(path) ?: return null
        if (host(root)?.isDirectory != true) return null
        val index = confinedFile(root, "index.html")
        val confFile = confinedFile(root, WEB_APP_CONF)
        if (index == null && confFile == null) return null
        if (!isWebAppName(name)) return Inspected(null, emptyList())

        val parsed = confFile?.let { f -> runCatching { parseAppConf(f.readText()) }.getOrNull() } ?: ParsedAppConf(AppConf(), emptyList())
        val conf = parsed.conf
        val problems = parsed.problems.toMutableList()
        val icon = conf.icon?.let { icon ->
            val guest = resolve("$root/$icon")
            when {
                guest == null || !inside(guest, root) -> null.also { problems += "icon: $icon is outside the app's folder" }
                host(guest)?.isFile != true -> null.also { problems += "icon: no such file $icon in the app's folder" }
                else -> guest
            }
        }
        if (conf.service != null && conf.url != null) {
            val defined = loadServices(File(home, "$CONFIG_DIR/$SERVICES_CONF")).services.any { it.name == conf.service }
            if (!defined) problems += "service: no service named ${conf.service} in ~/$CONFIG_DIR/$SERVICES_CONF"
        }
        val served = conf.url
        if (served == null && index == null) {
            problems += "not an app: no index.html, and no url"
            return Inspected(null, problems)
        }
        val label = conf.name ?: index?.let(::readTitle) ?: folderLabel(name)
        val app = if (served != null) {
            WebApp(name, label, path, root, WebAppKind.SERVED, servedOrigin(served), served, servedPort(served), conf.service, icon, problems)
        } else {
            WebApp(name, label, path, root, WebAppKind.STATIC, webAppOrigin(name), webAppOrigin(name) + "/", null, null, icon, problems)
        }.copy(fullscreen = conf.fullscreen, screenAwake = conf.screenAwake)
        return Inspected(app, problems)
    }

    private fun readTitle(file: File): String? = runCatching {
        val bytes = file.inputStream().use { it.readNBytes(4096) }
        htmlTitle(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    // The host file of [name] in folder [root] (a resolved guest path), if it's a file inside it.
    private fun confinedFile(root: String, name: String): File? {
        val guest = resolve("$root/$name") ?: return null
        if (!inside(guest, root)) return null
        return host(guest)?.takeIf { it.isFile }
    }

    private fun inside(path: String, root: String) =
        path == root || path.startsWith(if (root == "/") "/" else "$root/")

    /** [guest] with every link along it resolved as Debian sees it (a link-free guest path), or null: see LINKS. */
    fun resolve(guest: String): String? {
        if (!guest.startsWith("/")) return null
        val done = ArrayList<String>()
        val todo = ArrayDeque(guest.split('/').filter { it.isNotEmpty() })
        var links = 0
        while (todo.isNotEmpty()) {
            when (val part = todo.removeFirst()) {
                "." -> continue
                ".." -> { done.removeLastOrNull(); continue }
                else -> done += part
            }
            val file = host("/" + done.joinToString("/")) ?: return null
            val path = file.toPath()
            if (!Files.isSymbolicLink(path)) continue
            if (++links > MAX_LINKS) return null
            val target = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() ?: return null
            done.removeAt(done.lastIndex)
            val next = fakedHardLink(target) ?: target
            if (next.startsWith("/")) done.clear()
            next.split('/').filter { it.isNotEmpty() }.asReversed().forEach(todo::addFirst)
        }
        return "/" + done.joinToString("/")
    }

    // link2symlink's target, a host path to a `.l2s.*` file in the rootfs, as a guest path.
    private fun fakedHardLink(target: String): String? {
        val file = File(target)
        if (!target.startsWith("/") || !file.name.startsWith(L2S)) return null
        val parent = runCatching { file.parentFile.canonicalPath }.getOrNull() ?: return null
        return when {
            parent == rootfsCanonical -> "/${file.name}"
            parent.startsWith("$rootfsCanonical/") -> parent.removePrefix(rootfsCanonical) + "/" + file.name
            else -> null
        }
    }

    /** Where the guest path [guest] lives on the host ([TOOLS_MOUNT] → [toolsDir], else [hostPath]). */
    fun host(guest: String): File? = when {
        guest == TOOLS_MOUNT -> toolsDir
        guest.startsWith("$TOOLS_MOUNT/") -> File(toolsDir, guest.removePrefix("$TOOLS_MOUNT/"))
        else -> hostPath(guest, rootfs)?.let(::File)
    }

    /** The answer to [method] on [encodedPath] of static app [app]: see STATIC APPS. */
    fun respond(app: WebApp, method: String, encodedPath: String): WebAppResponse {
        if (method != "GET" && method != "HEAD") {
            return plain(405, "Method Not Allowed", "only GET and HEAD\n", mapOf("Allow" to "GET, HEAD"))
        }
        if (app.kind != WebAppKind.STATIC) return notFound()
        val raw = encodedPath.removePrefix("/").split('/')
        val segments = mutableListOf<String>()
        for (encoded in raw) {
            if (encoded.isEmpty()) continue
            val segment = percentDecode(encoded) ?: return notFound()
            if (segment == "." || segment == ".." || '/' in segment || '\u0000' in segment || segment.startsWith(L2S)) {
                return notFound()
            }
            segments += segment
        }
        if (raw.last().isEmpty()) segments += "index.html"
        val guest = resolve(app.root + "/" + segments.joinToString("/")) ?: return notFound()
        if (!inside(guest, app.root)) return notFound()
        val file = host(guest)?.takeIf { it.isFile } ?: return notFound()
        val mime = webAppMimeType(segments.last())
        return WebAppResponse(200, "OK", mime, if (isTextMime(mime)) "utf-8" else null, NO_STORE, file = file)
    }

    /**
     * The answer for a request to [BRIDGE_SCRIPT_URL]'s host (part 2):
     * `/mynx.js` is the tools' [BRIDGE_SCRIPT_FILE] (`text/javascript`,
     * utf-8, no-store); GET and HEAD only (else 405); any other path 404.
     */
    fun bridgeScript(method: String, encodedPath: String): WebAppResponse {
        if (method != "GET" && method != "HEAD") {
            return plain(405, "Method Not Allowed", "only GET and HEAD\n", mapOf("Allow" to "GET, HEAD"))
        }
        if (encodedPath != "/mynx.js") return notFound()
        val file = File(toolsDir, BRIDGE_SCRIPT_FILE).takeIf { it.isFile } ?: return notFound()
        return WebAppResponse(200, "OK", "text/javascript", "utf-8", NO_STORE, file = file)
    }

    /** A value that changes when any file under [app]'s root does: see AUTO-RELOAD. */
    fun fingerprint(app: WebApp): Long {
        var hash = FNV_OFFSET
        var files = 0
        val seen = mutableSetOf<String>()
        fun add(text: String) {
            for (b in text.toByteArray()) hash = (hash xor (b.toLong() and 0xff)) * FNV_PRIME
        }
        fun walk(guest: String, relative: String) {
            if (!seen.add(guest)) return
            for (name in host(guest)?.list().orEmpty().sorted()) {
                if (files >= WEB_APP_FINGERPRINT_FILES) return
                if (name.startsWith(L2S)) continue
                val resolved = resolve("$guest/$name") ?: continue
                if (!inside(resolved, app.root)) continue
                val file = host(resolved) ?: continue
                when {
                    file.isDirectory -> walk(resolved, "$relative$name/")
                    file.isFile -> {
                        files++
                        add("$relative$name\u0000${file.length()}\u0000${file.lastModified()}\n")
                    }
                }
            }
        }
        walk(app.root, "")
        return hash
    }
}

private const val MAX_LINKS = 40
private const val L2S = ".l2s."
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private val NO_STORE = mapOf("Cache-Control" to "no-store")

private fun plain(status: Int, reason: String, body: String, headers: Map<String, String> = emptyMap()) =
    WebAppResponse(status, reason, "text/plain", "utf-8", NO_STORE + headers, body = body)

private fun notFound() = plain(404, "Not Found", "not found\n")

// Percent-decodes one path segment as UTF-8 (`+` stays); null for bad encoding.
private fun percentDecode(text: String): String? {
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val hex = text.substring(i + 1, i + 3)
            if (hex.any { Character.digit(it, 16) < 0 }) return null
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }.getOrNull()
}

/**
 * The problems [checkConfig] reports for the folders in `~/apps` (only
 * the user's: the tools' apps are tested with the app): each folder
 * with an `index.html` or `app.conf` and a bad name (file
 * `~/apps/NAME`: "not a usable app name: use a-z, 0-9 and -, …"), each
 * `app.conf`'s problems (file `~/apps/NAME/app.conf`), an unknown
 * `service`. The rootfs is [home]'s parent, as in [checkConfig]. A
 * link to `/opt/mynx/…` can't be followed here (no tools folder), so
 * it counts as dangling.
 */
fun webAppProblems(home: File): List<ConfigProblems> =
    // Nothing exists below /dev/null: the tools' folder isn't here.
    WebApps(home, home.parentFile.path, File("/dev/null/no-tools")).problems().map { (file, problems) ->
        ConfigProblems(file, problems)
    }

/** One poll's outcome: whether to reload now, and when to poll next. */
data class ReloadStep(val reload: Boolean, val nextMs: Long)

/**
 * When a static app's screen reloads, from the fingerprints it polls
 * ([first] taken as the page loads): unchanged, poll again in 1000 ms;
 * changed, again in 300 ms; the same twice after a change, reload (then
 * 1000 ms). Not thread-safe: one poller.
 */
class AutoReload(first: Long) {
    private var last = first
    private var settling = false

    fun check(fingerprint: Long): ReloadStep {
        if (fingerprint != last) {
            last = fingerprint
            settling = true
            return ReloadStep(false, SETTLE_MS)
        }
        val reload = settling
        settling = false
        return ReloadStep(reload, POLL_MS)
    }

    private companion object {
        const val POLL_MS = 1000L
        const val SETTLE_MS = 300L
    }
}

/** A log entry's level, as the log writes it. */
enum class WebAppLogLevel(val word: String) { DEBUG("debug"), LOG("log"), WARN("warn"), ERROR("error") }

/**
 * App [name]'s log, [file] its host file (`ROOTFS/tmp/.mynx/apps/NAME.log`),
 * [origin] the app's (sources on it are shortened to their path).
 * [now] is ms since 1970, written in [zone]'s time. Calls may come from
 * any thread; each writes at once.
 */
class WebAppLog(
    val file: File,
    val origin: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    /** The screen opened [url]: `--- opened …`. */
    fun opened(url: String) {
        append(listOf("--- opened ${time("yyyy-MM-dd HH:mm:ss")} $url"))
    }

    /** One entry; [source] a URL or null, [line] 0 when unknown. */
    fun entry(level: WebAppLogLevel, message: String, source: String?, line: Int) {
        val where = when {
            source.isNullOrEmpty() -> ""
            source == origin -> "/"
            source.startsWith("$origin/") -> source.removePrefix(origin)
            else -> source
        }
        val at = when {
            where.isEmpty() -> ""
            line > 0 -> "$where:$line "
            else -> "$where "
        }
        val parts = message.trimEnd('\n', '\r').split(Regex("\r?\n"))
        append(listOf("[${time("HH:mm:ss.SSS")}] ${level.word} $at${parts.first()}") + parts.drop(1).map { "    $it" })
    }

    /**
     * An HTTP error [status] for [url] (`error`, "HTTP 404"), except a 404
     * for exactly the app's own `/favicon.ico`: the WebView asks for that
     * itself, so an app with no icon would log it at every open.
     */
    fun httpError(status: Int?, url: String?) {
        if (status == 404 && url == "$origin/favicon.ico") return
        entry(WebAppLogLevel.ERROR, "HTTP $status", url, 0)
    }

    private fun time(pattern: String) =
        java.time.format.DateTimeFormatter.ofPattern(pattern).format(java.time.Instant.ofEpochMilli(now()).atZone(zone))

    private fun append(lines: List<String>) = synchronized(LOG_LOCK) {
        runCatching {
            file.parentFile?.mkdirs()
            val kept = (if (file.isFile) file.readLines() else emptyList()) + lines
            val tmp = File(file.path + ".tmp")
            tmp.writeText(kept.takeLast(WEB_APP_LOG_LINES).joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) tmp.delete()
        }
        Unit
    }

    private companion object {
        // One lock for every log: the activity and the service may each have one for the same app.
        val LOG_LOCK = Any()
    }
}

/** A service as the launcher needs it. */
data class WebAppServiceStatus(val state: ServiceState, val autostart: Boolean)

/**
 * The services side, on the main thread like [ServiceManager] (see
 * [forWebApps]).
 */
interface WebAppServices {
    /** [name]'s state, `services.conf` read again first; null when there's no such service. */
    fun status(name: String): WebAppServiceStatus?
    /** Starts [name] by hand; null, or why not. */
    fun start(name: String): String?
    /** Stops [name]; null once asked, or why not. */
    fun stop(name: String): String?
}

/** [ServiceManager] as the launcher's [WebAppServices]: `reload()` and `services[name]`, `start`, `stop`. */
fun <S> ServiceManager<S>.forWebApps(): WebAppServices {
    val manager = this
    return object : WebAppServices {
        override fun status(name: String): WebAppServiceStatus? {
            manager.reload()
            val service = manager.services[name] ?: return null
            return WebAppServiceStatus(service.state, service.def.autostart)
        }

        override fun start(name: String) = manager.start(name)

        override fun stop(name: String) = manager.stop(name)
    }
}

/**
 * What the app does for the launcher. All of it is called on the main
 * thread and must not block; callbacks come back on the main thread.
 */
interface WebAppHost {
    /** Whether one of the app's activities is in front, so it may start another. */
    val onScreen: Boolean
    /** Opens (or brings back) [app]'s screen; null, or why not. */
    fun open(app: WebApp): String?
    /** Whether [name]'s screen exists (open, in front or not). */
    fun isOpen(name: String): Boolean
    /** Reloads [name]'s screen; false when it isn't open. */
    fun reload(name: String): Boolean
    /** Tries a TCP connection to 127.0.0.1:[port] (off the main thread, ~1 s timeout); [done] says whether it answered. */
    fun portAnswers(port: Int, done: (Boolean) -> Unit)
    /** Runs [block] after [delayMs]. */
    fun schedule(delayMs: Long, block: () -> Unit)
    /** Asks Android to pin [shortcut] (`requestPinShortcut`); null once asked, or why not. */
    fun pinShortcut(shortcut: AppShortcut): String? = "shortcuts aren't available here"
    /** Updates [shortcut] if it's pinned (`updateShortcuts`), at each open. */
    fun updateShortcut(shortcut: AppShortcut) {}
}

/** How long `mynx` waits for `app-shortcut`. */
const val WEB_APP_SHORTCUT_SECONDS = 10
/** The intent extra (a boolean) a shortcut's intent carries: the screen opens the app itself. */
const val WEB_APP_FROM_SHORTCUT = "from-shortcut"
/** The share of an adaptive icon's 108 dp an `app.conf` icon is scaled to fit: the 66 dp safe zone. */
const val SHORTCUT_SAFE_ZONE = 66.0 / 108.0

/** `app-NAME`, an app's shortcut id. */
fun webAppShortcutId(name: String): String = "app-$name"

/**
 * What an app's shortcut shows (see HOME SCREEN SHORTCUTS): [icon], the
 * host file of `app.conf`'s `icon`, or null for the generated [letter];
 * colours are 0xRRGGBB with full alpha (as [ColorScheme]'s).
 */
data class AppShortcut(
    val id: String,
    val name: String,
    val label: String,
    val icon: File?,
    val letter: String,
    val background: Int,
    val foreground: Int,
)

/** [app]'s shortcut under the theme [colors]; [iconFile] is its icon's host file, if it has one. */
fun appShortcut(app: WebApp, iconFile: File?, colors: ColorScheme): AppShortcut {
    val letter = (app.label.firstOrNull { it.isLetterOrDigit() } ?: app.name.first()).uppercase()
    val index = SHORTCUT_COLORS[Math.floorMod(app.name.hashCode(), SHORTCUT_COLORS.size)]
    val background = colors.palette[index] ?: colors.foreground
    val foreground = if (contrast(background, colors.background) >= contrast(background, colors.foreground)) {
        colors.background
    } else {
        colors.foreground
    }
    return AppShortcut(webAppShortcutId(app.name), app.name, app.label, iconFile, letter, background, foreground)
}

// The theme's chromatic colours, normal then bright.
private val SHORTCUT_COLORS = listOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14)

/** What [WebAppLauncher.openStep] did: the [app] shown, and whether it started its service. */
data class WebAppOpened(val app: WebApp, val startedService: Boolean)

/**
 * Opens apps and keeps their services' lifecycle (see the contract
 * above). The service makes one, passes [requests] to [MynxRequests]'s
 * `later` (through [webAppRequests]), and calls [screenClosed] when an
 * app's screen finishes. [now] is for the port wait's deadline.
 */
class WebAppLauncher(
    private val apps: WebApps,
    private val host: WebAppHost,
    private val services: WebAppServices,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // App name → the service opening it started (and may stop when it closes).
    private val startedFor = mutableMapOf<String, String>()

    fun requests(): Map<String, Later> = mapOf(
        "app-list" to Later(QUICK_SECONDS) { _, reply ->
            reply.answer(ok("apps" to array(apps.list().map(::appJson))))
        },
        "app-open" to Later(WEB_APP_OPEN_SECONDS) { args, reply -> open(args, reply) },
        "app-reload" to Later(QUICK_SECONDS) { args, reply ->
            val app = named(args, "app-reload", reply) ?: return@Later
            if (host.reload(app.name)) reply.answer(ok("name" to json(app.name))) else reply.refuse("${app.name} isn't open")
        },
        "app-shortcut" to Later(WEB_APP_SHORTCUT_SECONDS) { args, reply -> shortcut(args, reply) },
    ).mapValues { (name, later) ->
        // Anything thrown would leave the request to fail again at every poll.
        Later(later.seconds, later.change) { args, reply ->
            try {
                later.start(args, reply)
            } catch (e: Exception) {
                reply.refuse("$name failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun named(args: List<String>, request: String, reply: PendingReply): WebApp? {
        val name = args.firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null.also { reply.refuse("$request needs a name") }
        return apps.find(name) ?: null.also { reply.refuse("no app named $name (mynx app list shows them)") }
    }

    private fun open(args: List<String>, reply: PendingReply) {
        val app = named(args, "app-open", reply) ?: return
        if (!host.onScreen) {
            return reply.refuse("mynx must be on screen to open an app: bring it to the front and try again")
        }
        val cancel = openStep(app.name, host::open) { result ->
            result.fold(
                { opened ->
                    reply.ok(
                        "name" to json(opened.app.name),
                        "opened" to "true",
                        "origin" to json(opened.app.origin),
                        "url" to json(opened.app.url),
                        "started_service" to opened.startedService.toString(),
                    )
                },
                { reply.refuse(it.message ?: "couldn't open ${app.name}") },
            )
        }
        reply.onCancel { cancel() }
    }

    private fun shortcut(args: List<String>, reply: PendingReply) {
        val app = named(args, "app-shortcut", reply) ?: return
        if (!host.onScreen) return reply.refuse("mynx must be on screen to add a shortcut")
        val shortcut = shortcutOf(app)
        host.pinShortcut(shortcut)?.let { return reply.refuse(it) }
        reply.ok("name" to json(app.name), "id" to json(shortcut.id))
    }

    private fun shortcutOf(app: WebApp) = appShortcut(
        app,
        app.icon?.let(apps::host),
        loadColorScheme(File(apps.home, "$CONFIG_DIR/colors.properties")).scheme,
    )

    private class Started(val service: String, val autostart: Boolean)

    /**
     * THE OPEN STEP: opens app [name] (looked up, its service started
     * and waited for), then calls [show] with it (null: shown, else why
     * not). [done] gets the result, or the refusal as `app-open` words
     * it; once, on the main thread. The handle cancels it (a service it
     * started is stopped; [done] isn't called). Once shown, the app's
     * pinned shortcut is updated ([WebAppHost.updateShortcut]).
     */
    fun openStep(name: String, show: (WebApp) -> String?, done: (Result<WebAppOpened>) -> Unit): () -> Unit {
        var cancelled = false
        val handle = { cancelled = true }
        fun fail(message: String) = done(Result.failure(Exception(message)))
        val app = apps.find(name) ?: return handle.also { fail("no app named $name (mynx app list shows them)") }

        // Shows the screen; [started] is the service this step started, if any.
        fun finish(started: Started?) {
            show(app)?.let { error ->
                started?.let { services.stop(it.service) }
                return fail(error)
            }
            if (started != null && !started.autostart) startedFor[app.name] = started.service
            runCatching { host.updateShortcut(shortcutOf(app)) }
            done(Result.success(WebAppOpened(app, started != null)))
        }

        val port = app.port
        if (app.kind == WebAppKind.STATIC || port == null) return handle.also { finish(null) }
        val service = app.service
        if (service == null) {
            host.portAnswers(port) { answered ->
                when {
                    cancelled -> {}
                    answered -> finish(null)
                    else -> fail("nothing answers on 127.0.0.1:$port (app.conf's url)")
                }
            }
            return handle
        }
        val status = services.status(service)
            ?: return handle.also { fail("no service named $service (app.conf's service; mynx service list shows them)") }
        var started: Started? = null
        when (status.state) {
            ServiceState.STOPPING -> return handle.also { fail("$service is stopping: try again in a moment") }
            ServiceState.STOPPED, ServiceState.FAILED -> {
                services.start(service)?.let { return handle.also { _ -> fail(it) } }
                started = Started(service, status.autostart)
            }
            ServiceState.RUNNING -> {}
        }
        val deadline = now() + WEB_APP_PORT_WAIT_MS
        fun probe() {
            host.portAnswers(port) { answered ->
                when {
                    cancelled -> started?.let { services.stop(it.service) }
                    answered -> finish(started)
                    now() >= deadline -> {
                        started?.let { services.stop(it.service) }
                        fail(
                            "$service didn't answer on 127.0.0.1:$port within ${WEB_APP_PORT_WAIT_MS / 1000} s " +
                                "(mynx service log $service shows its output)",
                        )
                    }
                    else -> host.schedule(WEB_APP_PORT_PROBE_MS, ::probe)
                }
            }
        }
        probe()
        return handle
    }

    /** App [name]'s screen finished: stops the service opening it started, if nothing else needs it. */
    fun screenClosed(name: String) {
        val service = startedFor.remove(name) ?: return
        val heir = apps.list().firstOrNull { it.name != name && it.service == service && host.isOpen(it.name) }
        if (heir != null) {
            startedFor.putIfAbsent(heir.name, service)
            return
        }
        val status = services.status(service) ?: return
        if (status.state != ServiceState.RUNNING || status.autostart) return
        services.stop(service)
    }

    private fun appJson(app: WebApp) = obj(
        "name" to json(app.name),
        "label" to json(app.label),
        "path" to json(app.path),
        "kind" to json(app.kind.word),
        "origin" to json(app.origin),
        "url" to json(app.url),
        "service" to (app.service?.let(::json) ?: "null"),
        "icon" to (app.icon?.let(::json) ?: "null"),
        "problems" to array(app.problems.map(::json)),
        "fullscreen" to json(app.fullscreen.word),
        "screen_awake" to app.screenAwake.toString(),
    )

    private companion object {
        const val QUICK_SECONDS = 10
    }
}

/** The `app-*` requests ([WEB_APP_REQUEST_NAMES]), for [MynxRequests]'s `later`. */
fun webAppRequests(launcher: WebAppLauncher): Map<String, Later> = launcher.requests()
