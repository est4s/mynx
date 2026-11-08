package io.github.est4s.terminal.core

import java.io.File

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
 *   problem otherwise). [checkConfig] also reports one that isn't in
 *   `services.conf`; opening the app then refuses.
 * - Any other key: "line N: unknown key 'K'". A key given twice: the
 *   last counts, reported.
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
 * - 200 answers carry the file, [webAppMimeType] and, for text types,
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
 * URL) go there. A message's further lines follow, each indented by four
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
 *     and forgotten (a backend that never answers isn't left running);
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
val WEB_APP_REQUEST_NAMES = listOf("app-list", "app-open", "app-reload")

/** Whether [name] can be an app's name (a DNS label: see the contract above). */
fun isWebAppName(name: String): Boolean = TODO()

/** `https://NAME.mynx.invalid`, a static app's origin (no slash at the end). */
fun webAppOrigin(name: String): String = TODO()

/** The app [host] (a URL's host, any case) belongs to: `sketch.mynx.invalid` → `sketch`; null for any other host or a bad name. */
fun webAppNameOfHost(host: String): String? = TODO()

/** The MIME type for a file [name], by its ending in any case; `application/octet-stream` when unknown. */
fun webAppMimeType(name: String): String = TODO()

/** An app's `app.conf`, as parsed; null where it doesn't say. */
data class AppConf(
    val name: String? = null,
    /** Relative to the app's folder, as written. */
    val icon: String? = null,
    val url: String? = null,
    val service: String? = null,
)

data class ParsedAppConf(val conf: AppConf, val problems: List<String>)

/**
 * Parses an `app.conf`. Checks that need the folder or `services.conf`
 * (the icon's file, the service's existence) are [WebApps]'s and
 * [checkConfig]'s.
 */
fun parseAppConf(text: String): ParsedAppConf = TODO()

/** The label from an `index.html`'s start ([html], up to 4096 bytes of it), or null: see THE LABEL. */
fun htmlTitle(html: String): String? = TODO()

/** The label from a folder name: `pixel-hero` → "Pixel Hero", cut to [WEB_APP_LABEL_MAX]. */
fun folderLabel(name: String): String = TODO()

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
    /** Every app, `~/apps` first winning, sorted by name. */
    fun list(): List<WebApp> = TODO()

    /** The app named [name], or null. */
    fun find(name: String): WebApp? = TODO()

    /** [guest] with every link along it resolved as Debian sees it (a link-free guest path), or null: see LINKS. */
    fun resolve(guest: String): String? = TODO()

    /** Where the guest path [guest] lives on the host ([TOOLS_MOUNT] → [toolsDir], else [hostPath]). */
    fun host(guest: String): File? = TODO()

    /** The answer to [method] on [encodedPath] of static app [app]: see STATIC APPS. */
    fun respond(app: WebApp, method: String, encodedPath: String): WebAppResponse = TODO()

    /** A value that changes when any file under [app]'s root does: see AUTO-RELOAD. */
    fun fingerprint(app: WebApp): Long = TODO()
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
fun webAppProblems(home: File): List<ConfigProblems> = TODO()

/** One poll's outcome: whether to reload now, and when to poll next. */
data class ReloadStep(val reload: Boolean, val nextMs: Long)

/**
 * When a static app's screen reloads, from the fingerprints it polls
 * ([first] taken as the page loads): unchanged, poll again in 1000 ms;
 * changed, again in 300 ms; the same twice after a change, reload (then
 * 1000 ms). Not thread-safe: one poller.
 */
class AutoReload(first: Long) {
    fun check(fingerprint: Long): ReloadStep = TODO()
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
    fun opened(url: String): Unit = TODO()

    /** One entry; [source] a URL or null, [line] 0 when unknown. */
    fun entry(level: WebAppLogLevel, message: String, source: String?, line: Int): Unit = TODO()
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
fun <S> ServiceManager<S>.forWebApps(): WebAppServices = TODO()

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
}

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
    fun requests(): Map<String, Later> = TODO()

    /** App [name]'s screen finished: stops the service opening it started, if nothing else needs it. */
    fun screenClosed(name: String): Unit = TODO()
}

/** The `app-*` requests ([WEB_APP_REQUEST_NAMES]), for [MynxRequests]'s `later`. */
fun webAppRequests(launcher: WebAppLauncher): Map<String, Later> = launcher.requests()
