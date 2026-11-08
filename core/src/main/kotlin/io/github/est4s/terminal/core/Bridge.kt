package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

/*
 * The bridge (web apps, part 2): the phone from an app's JavaScript.
 * The contract the parts share (work log, "Next", 0):
 * - part A (core) builds this file but the serial calls, the seam
 *   ([ReplySink], [MynxRequests.call]), [AppPermissions], and the
 *   `WebApps.kt` additions;
 * - part B (the app) builds the channel ([BridgeHost], the port, the
 *   origin fence), the prompt, notifications, rotation, the front
 *   activity, shortcuts, full screen and screen awake;
 * - part C (tools) builds `tools/web/mynx.js` (whose JSDoc is the JS
 *   side of this contract), `mynx app permissions`/`shortcut`, the
 *   example and the guide;
 * - part D builds the `serial-*` calls ([bridgeSerial], [Rfc2217Client]).
 * Change the shapes here first, then everything else.
 *
 * THE CHANNEL
 *
 * The page's script, `mynx.js`, is in the tools (`web/mynx.js`,
 * [BRIDGE_SCRIPT_FILE]) and the view serves it to any page at
 * `https://mynx.invalid/mynx.js` ([BRIDGE_SCRIPT_URL], through
 * [WebApps.bridgeScript]); alone it has no power. No port is posted at
 * page commit or finish: once mynx.js listens, it asks for one with
 * `fetch('https://mynx.invalid/hello', {mode: 'no-cors'})`. The view
 * answers that 204 and, if it is on the app's origin and
 * [Bridge.refusal] says go, makes a `WebView.createWebMessageChannel()`
 * and posts one end with `postWebMessage(WebMessage(BRIDGE_PORT_MESSAGE,
 * [port]), Uri.parse(app.origin))`, so Android hands it over only when
 * the main frame is on the app's origin. One port, and one
 * [BridgeSession], per hello: each script that asked has its own.
 * Sessions know nothing of each other; the app keeps the page's, and
 * closes them all when the page goes (the next page starting, a reload,
 * the screen finishing), or each when its port closes. When
 * [Bridge.refusal] refuses, no port, and it was logged (`warn bridge no
 * bridge for ORIGIN: WHY`).
 *
 * As a second fence, the app passes each message with the view's
 * current URL's origin to [BridgeSession.receive], which ignores (and
 * logs, once per session: `warn bridge message from ORIGIN ignored`)
 * any whose origin isn't the app's. A page's own third-party scripts
 * share its origin and so its bridge: the guide says so.
 *
 * The log: the host writes each of the bridge's messages to the app's
 * log with source `bridge` ([BridgeHost.log]), so a line reads `LEVEL
 * bridge MESSAGE`. A call's refusal is `warn bridge CALL: MESSAGE`;
 * the rest name no call: `warn bridge bad message: WHY`, `warn bridge
 * call N is already open`.
 *
 * MESSAGES (JSON text, one object per WebMessage)
 *
 * The session's first message, sent from [Bridge.session]:
 *   {"hello":{"app":NAME,"label":LABEL,"calls":[CALL…]}}
 * CALL are the wire names offered ([Bridge.offered]: [BRIDGE_CALLS],
 * less `camera` when [BridgeHost.cameraApp] is false). mynx.js's
 * `mynx.ready` resolves on it.
 *
 * Page → app:
 *   {"id":N,"call":CALL,"args":[LINE…]}   N: an integer ≥ 1, new per
 *                                         call in the session
 *   {"id":N,"cancel":true}                stop call N (a stream's
 *                                         `stop()`)
 * LINEs are strings: exactly the lines `mynx` sends after the request
 * name for the same command (each request's KDoc says them), or what a
 * bridge-own call takes (below). Binary data is base64 (WebMessage only
 * carries strings before API 33).
 *
 * App → page:
 *   {"id":N,"line":{…}}                   a stream's reading
 *   {"id":N,"reply":{"ok":true,…}}        the answer, once; the call is
 *                                         over
 *   {"id":N,"reply":{"ok":false,"error":MESSAGE,"code":CODE}}
 * Answers are the requests' own JSON (as `mynx --json` prints them);
 * mynx.js leaves `ok` out. CODE ([BridgeError]): `denied` (the app's
 * permission said no), `refused` (anything else core refuses with,
 * timeouts too), `closed` (the session closed under it, or its
 * permission dialog closed unanswered). `no-bridge` is mynx.js's own
 * (no port within 5 s, or not in mynx).
 *
 * Bad messages: text that isn't a JSON object, over
 * [MAX_BRIDGE_MESSAGE_CHARS], or without a usable `id`: ignored and
 * logged (`warn bridge bad message: WHY`). With an id: a repeated id
 * still open, a missing or non-string `call`, `args` not a list of
 * strings → refused at once. A call not offered: refused "CALL isn't
 * offered to apps" (so config requests can't be reached by mistake). A
 * cancel for an id that isn't open: ignored. More than
 * [MAX_BRIDGE_OPEN_CALLS] open at once: refused "too many calls at
 * once".
 *
 * HOW A CALL RUNS (on the main thread)
 *
 * [BridgeSession.receive] looks the call up in [BRIDGE_CALLS]; checks
 * its [BridgeCall.permission] through [AppPermissions.check] (which may
 * ask, so the call may wait); then a [BridgeRoute.REQUEST] call runs its
 * request through [MynxRequests.call] with a sink of the session's (the
 * same handler, rules and answers as from `mynx`; the `android-*`
 * settings are the handlers' checks), and a [BridgeRoute.OWN] call runs
 * the bridge's own [Later] the same way ([MynxRequests.call]'s handler
 * overload). Every refusal is logged (`warn bridge CALL: MESSAGE`, or
 * `warn bridge MESSAGE` before a call has a name).
 *
 * TIMEOUTS: a call whose [Call.seconds] isn't null is refused
 * "CALL timed out after N s" after that long ([BridgeHost.schedule]),
 * and cancelled ([Call.cancel]) so its work stops, as `mynx` gives up.
 * Streams (null) run until stopped or the session closes. A permission
 * dialog's wait doesn't count.
 *
 * SESSIONS AND CANCELLATION
 *
 * A [BridgeSession] holds every call it started. A cancel message runs
 * [Call.stop] (the handler's `onCancel`, which may answer, as a
 * recording does; else `{"ok":true}`), and that answer is sent.
 * [BridgeSession.close] (the page going: the next page starting, a reload,
 * auto-reload; the port closing; the screen finishing) runs
 * [Call.cancel] on every open call (nothing more is sent), withdraws
 * waiting permission checks, closes the [BridgeSerial] (its ports let
 * go), drops BLE links its `ble-connect` calls hold (they're calls),
 * unlocks the screen's rotation if it locked it, and deletes the
 * session's temp files. Going to the background stops nothing (as in a
 * tab: a location stream keeps going). Calling it twice is harmless;
 * nothing is sent after it.
 *
 * Ids: [MynxRequests.call] names its requests `bridge-N` (no pid in
 * front), so [MynxRequests.sweep] leaves them be.
 *
 * THE BRIDGE'S OWN CALLS ([BridgeRoute.OWN])
 *
 * - `notify`: lines TITLE, TEXT, then `if-away`. [BridgeHost.notify]
 *   posts it with the app as its target (tapping it opens the app's
 *   screen; `if-away`: not while that screen is in front). Answer as
 *   `notify`'s: `{"shown":true}` or `{"shown":false,"reason"}`. Title
 *   and text cut as `mynx notify` cuts them.
 * - `rotation-lock [portrait|landscape|current]` (none: current) and
 *   `rotation-unlock`: the app's own screen ([BridgeHost.rotation]),
 *   not the global [RotationLocks] (those belong to processes); ends
 *   with the session. Answer `{"locked":"portrait"|"landscape"|
 *   "current"|"no"}`.
 * - `camera-quick [back|front]` (none: back), `camera`: core makes a
 *   target in the app's temp folder (`/tmp/.mynx/apps/NAME/`,
 *   [WEB_APP_TEMP_DIR]), runs the `camera-quick PATH FACING` / `camera
 *   PATH` request, reads the file back and deletes it. Answer
 *   `{"type":"image/jpeg","bytes":N,"data":BASE64}`. Over
 *   [MAX_BRIDGE_DATA_BYTES]: refused "the photo is too big for a page:
 *   N MB (at most 16 MB)". `camera` (the phone's camera app) only while
 *   [BridgeHost.cameraApp].
 * - `audio-record [seconds=N] [rate=HZ] [format=m4a|aac|ogg|opus|wav]`
 *   (format m4a unless given): a stream, as `audio-record` (line
 *   `{"recording":true}` once it records); stopping it (or N s) ends
 *   the recording, and the answer is `{"type":MIME,"bytes":N,
 *   "seconds":S,"data":BASE64}` (MIME: `audio/mp4`, `audio/aac`,
 *   `audio/ogg`, `audio/wav`); the file is deleted. Capped as photos.
 * - `audio-play file=PATH` (a static app's own file: PATH is its URL
 *   path, `/sounds/ding.mp3`, confined as [WebApps.respond] confines;
 *   a served app is refused "file= is for static apps: send data=")
 *   or `audio-play data=BASE64 type=MIME` (bytes to a temp file named
 *   by the type's ending, deleted when it ends): runs `audio-play`;
 *   answer `{"seconds":S}`. A stream in that it runs until the sound
 *   ends or it's stopped.
 * - `share-files` then, per file, `name=NAME`, `type=MIME`,
 *   `data=BASE64` (in that order; NAME made safe as [Inbox] makes names,
 *   at most [MAX_SHARE_FILES]): the files go to a temp folder, then the
 *   `share` request; answer `{"count":N}`. Those files are deleted
 *   after [BRIDGE_SHARE_KEEP_MS] (the receiving app reads them later),
 *   or when the service starts again ([Bridge.start] empties the temp
 *   folder).
 * - `service-list`, `service-start [NAME]`, `service-stop [NAME]`,
 *   `service-restart [NAME]`, `service-log [NAME] [LINES]`: only the
 *   app's own service (`app.conf`'s `service`; maintainer's answer),
 *   so they need no permission. NAME (empty or left out: the app's
 *   own) must be it, else refused "NAME isn't APP's service (app.conf's
 *   service)"; an app with no `service` (every static app): "APP has no
 *   service (app.conf's service)". Then the `service-*` request with
 *   that name; `service-list` answers `{"services":[SERVICE]}` with
 *   only the app's own (as `service-list` gives each). They're OWN
 *   because of that guard; the work is the requests'. (Stopping it
 *   doesn't close the session; the next page load gets no bridge.)
 * - `serial-list`, `serial-open`, `serial-write`, `serial-set`: part
 *   D's, see [BridgeSerial].
 *
 * Temp files: the app's temp folder is `/tmp/.mynx/apps/NAME/` in
 * Debian (the rootfs's `tmp/.mynx/apps/NAME` on the host), named
 * `bridge-ID.EXT` per call; core makes and deletes them.
 *
 * THE SERIAL CALLS (part D, [BridgeSerial])
 *
 * The bridge is an RFC 2217 client ([Rfc2217Client]) of the port's own
 * server (`127.0.0.1:TCPPORT`, [BridgeHost.connectSerial]), as
 * `serial-port` is, so the rules stay: one user at a time (refused "PORT
 * is busy: another program has it" while a program or the path has it:
 * listed `in_use`, or the server closing without a greeting), a
 * Bluetooth link opens when it arrives and closes when it leaves, RTS
 * dropped before DTR on close, raised DTR first. A server that greets
 * and closes before answering our setup: refused "PORT didn't open:
 * ERROR" (the port's `error` from `bt-list`, when it has one); no answer
 * within [SERIAL_OPEN_SECONDS]: "PORT didn't open within 30 s".
 * - `serial-list` → `{"ports":[{"name","kind":"usb"|"bluetooth"|
 *   "ble-uart","path","url","in_use":BOOL,"device":TEXT}]}` from
 *   `usb-list`'s ports and `bt-list --no-ask`'s bound ports (Bluetooth
 *   left out, not refused, when it can't be asked). `device`: a USB
 *   device's product, maker or ids; a Bluetooth port's paired name or
 *   address. `url` is null for a bound port not served now.
 * - `serial-open PORT [baud=N] [data=5..8] [parity=none|even|odd|mark|
 *   space] [stop=1|1.5|2] [lower]` (baud 115200, 8N1 unless given;
 *   `lower`: DTR and RTS down on open, DTR first, as the monitor's
 *   `--lower`; else both raised): a stream. First line
 *   `{"open":{"port":PORT,"handle":H,"baud":N}}` (H: an integer, per
 *   session), then `{"data":BASE64}` for what the port sends (what came
 *   before the open line follows it). Stopping it closes the port
 *   (answer `{"ok":true}`); a port that goes away (unplugged, the link
 *   dropped) answers a refusal, "PORT went away (WHY)".
 * - `serial-write H BASE64` → `{"bytes":N}` once handed to the socket.
 * - `serial-set H baud=N | dtr=0|1 | rts=0|1 | data=… | parity=… |
 *   stop=…` (any of them, in one call) → `{"ok":true}` once sent.
 * - Unknown H: refused "no open port H".
 */

/** The page's script, in the tools' folder. */
const val BRIDGE_SCRIPT_FILE = "web/mynx.js"
/** Where any page loads `mynx.js` from: the view answers this host itself. */
const val BRIDGE_SCRIPT_URL = "https://$WEB_APP_DOMAIN/mynx.js"
/** The data of the WebMessage that carries the bridge's port. */
const val BRIDGE_PORT_MESSAGE = "mynx-bridge"
/** The apps' temp folders, in Debian: `/tmp/.mynx/apps/NAME/`. */
const val WEB_APP_TEMP_DIR = WEB_APP_LOG_DIR
/** The most a photo, a recording or a file to play or share may be (decoded). */
const val MAX_BRIDGE_DATA_BYTES = 16 * 1024 * 1024
/** The longest message the bridge reads: 16 MB as base64, and some. */
const val MAX_BRIDGE_MESSAGE_CHARS = 24_000_000
/** The most calls one session may have open at once. */
const val MAX_BRIDGE_OPEN_CALLS = 64
/** How long files shared from a page are kept for the receiving app. */
const val BRIDGE_SHARE_KEEP_MS = 10 * 60 * 1000L

/** A refusal's `code` in the page's reply. `no-bridge` is mynx.js's own. */
enum class BridgeError(val word: String) { DENIED("denied"), REFUSED("refused"), CLOSED("closed") }

/** Whether a call runs a `mynx` request as it is, or is the bridge's own (see THE BRIDGE'S OWN CALLS). */
enum class BridgeRoute { REQUEST, OWN }

/**
 * One call apps are offered: its wire name [call] (the message's
 * `call`), the [js] function that sends it, its [route], the [request]
 * it runs (an OWN call's main request, if any, for the docs), the
 * [permission] it needs, and whether mynx.js gives a `MynxStream`
 * ([stream]: the page can stop it; it may run until stopped).
 */
data class BridgeCall(
    val call: String,
    val js: String,
    val route: BridgeRoute,
    val request: String?,
    val permission: AppPermission?,
    val stream: Boolean = false,
)

private fun req(call: String, js: String, permission: AppPermission? = null, stream: Boolean = false) =
    BridgeCall(call, js, BridgeRoute.REQUEST, call, permission, stream)

private fun own(call: String, js: String, request: String?, permission: AppPermission? = null, stream: Boolean = false) =
    BridgeCall(call, js, BridgeRoute.OWN, request, permission, stream)

/**
 * Everything apps are offered, and nothing else (maintainer's decision:
 * all mynx has for the phone, but settings and config, raw USB and UF2).
 * The guide's table and mynx.js follow it.
 */
val BRIDGE_CALLS: List<BridgeCall> = listOf(
    req("location", "mynx.location(options)", AppPermission.LOCATION),
    req("location-stream", "mynx.location.stream(options)", AppPermission.LOCATION, stream = true),
    req("sensor-list", "mynx.sensor.list()"),
    req("sensor", "mynx.sensor(name, options)"),
    req("sensor-stream", "mynx.sensor.stream(name, options)", stream = true),
    req("vibrate", "mynx.vibrate(ms, options)"),
    req("vibrate-effect", "mynx.vibrate.effect(name)"),
    req("vibrate-wave", "mynx.vibrate.wave(steps, options)", stream = true),
    req("vibrate-chain", "mynx.vibrate.chain(primitives)"),
    req("vibrate-list", "mynx.vibrate.list()"),
    req("vibrate-stop", "mynx.vibrate.stop()"),
    req("torch", "mynx.torch.on(percent) / mynx.torch.off()"),
    req("battery", "mynx.battery()"),
    req("battery-stream", "mynx.battery.stream(options)", stream = true),
    req("clipboard-get", "mynx.clipboard.get()", AppPermission.CLIPBOARD),
    req("clipboard-set", "mynx.clipboard.set(text)"),
    req("share-text", "mynx.share.text(text)"),
    own("share-files", "mynx.share.files(files)", "share"),
    own("notify", "mynx.notify(title, text, options)", null, AppPermission.NOTIFICATIONS),
    own("camera-quick", "mynx.camera({quick})", "camera-quick", AppPermission.CAMERA),
    own("camera", "mynx.camera()", "camera", AppPermission.CAMERA),
    own("audio-record", "mynx.audio.record(options)", "audio-record", AppPermission.MICROPHONE, stream = true),
    own("audio-play", "mynx.audio.play(source)", "audio-play", stream = true),
    own("rotation-lock", "mynx.rotation.lock(side)", null),
    own("rotation-unlock", "mynx.rotation.unlock()", null),
    req("ble-scan", "mynx.ble.scan(options)", AppPermission.BLUETOOTH),
    req("ble-scan-stream", "mynx.ble.scan.stream(options)", AppPermission.BLUETOOTH, stream = true),
    req("ble-services", "mynx.ble.services(device)", AppPermission.BLUETOOTH),
    req("ble-read", "mynx.ble.read(device, char, options)", AppPermission.BLUETOOTH),
    req("ble-write", "mynx.ble.write(device, char, value, options)", AppPermission.BLUETOOTH),
    req("ble-notify", "mynx.ble.notify(device, char, options)", AppPermission.BLUETOOTH, stream = true),
    req("ble-connect", "mynx.ble.connect(device)", AppPermission.BLUETOOTH, stream = true),
    own("serial-list", "mynx.serial.list()", "usb-list", AppPermission.SERIAL),
    own("serial-open", "mynx.serial.open(port, options)", null, AppPermission.SERIAL, stream = true),
    own("serial-write", "port.write(data)", null, AppPermission.SERIAL),
    own("serial-set", "port.set(options)", null, AppPermission.SERIAL),
    own("service-list", "mynx.service.list()", "service-list"),
    own("service-start", "mynx.service.start(name)", "service-start"),
    own("service-stop", "mynx.service.stop(name)", "service-stop"),
    own("service-restart", "mynx.service.restart(name)", "service-restart"),
    own("service-log", "mynx.service.log(name, options)", "service-log"),
)

/**
 * A socket to a serial port's RFC 2217 server on 127.0.0.1, from
 * [BridgeHost.connectSerial]. [send] queues bytes (any thread; never
 * blocks); [close] closes it (nothing is called back after).
 */
interface BridgeSocket {
    fun send(bytes: ByteArray)
    fun close()
}

/**
 * What the bridge needs from the app. Called on the main thread;
 * callbacks may come on any thread unless said otherwise (the bridge
 * posts them back through [schedule] with delay 0).
 */
interface BridgeHost {
    /** Whether [app]'s screen is in front (resumed and on screen): asking needs it, `if-away` skips while it is. */
    fun inFront(app: String): Boolean

    /**
     * Shows mynx's dialog on [app]'s screen: "LABEL wants to use
     * WHAT", **Allow** / **Don't allow** (only called while [inFront]).
     * [answer] gets true, false, or null when the dialog closed
     * unanswered; once, on the main thread. The returned handle
     * dismisses it (then [answer] isn't called).
     */
    fun ask(app: WebApp, permission: AppPermission, answer: (Boolean?) -> Unit): () -> Unit

    /** Adds a line to [app]'s log ([WebAppLog]: source `bridge`, line 0). */
    fun log(app: String, level: WebAppLogLevel, message: String)

    /** Posts a notification whose tap opens [app]'s screen; null when shown, else why not. */
    fun notify(app: WebApp, title: String, text: String, ifAway: Boolean): String?

    /** Holds [app]'s own screen's rotation ([Orientation]), or frees it (null); null, or why not ("the app's screen isn't open"). */
    fun rotation(app: String, orientation: Orientation?): String?

    /** Whether the phone's camera app can be opened from an app's screen (B: the front activity can start it for a result). */
    val cameraApp: Boolean

    /** Whether the service [name] runs ([ServiceManager]'s RUNNING): a served app gets the bridge only then. */
    fun serviceRunning(name: String): Boolean

    /**
     * Connects to 127.0.0.1:[tcpPort] off the main thread
     * (`app/.../BridgeSockets.kt`, threads of its own). [ready] gets the
     * socket or why not; then [received] gets what comes in, and
     * [closed] once when the other side closed or failed (null: closed
     * cleanly).
     */
    fun connectSerial(
        tcpPort: Int,
        received: (ByteArray) -> Unit,
        closed: (String?) -> Unit,
        ready: (Result<BridgeSocket>) -> Unit,
    )

    /** Runs [block] on the main thread after [delayMs]; the handle cancels it. */
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * The serial calls for one session ([bridgeSerial] makes one per
 * session; with none, [NO_BRIDGE_SERIAL]). [calls] are the
 * `serial-*` [Later]s (see THE SERIAL CALLS); [close] lets go of every
 * port the session opened (RTS, then DTR down).
 */
interface BridgeSerial {
    fun calls(): Map<String, Later>
    fun close()
}

/** No serial ports: every `serial-*` call refused "serial ports aren't available here". */
val NO_BRIDGE_SERIAL: (WebApp) -> BridgeSerial = {
    object : BridgeSerial {
        override fun calls() = BRIDGE_SERIAL_CALLS.associateWith {
            Later(5) { _, reply -> reply.refuse("serial ports aren't available here") }
        }

        override fun close() {}
    }
}

/** The calls a [BridgeSerial] answers. */
val BRIDGE_SERIAL_CALLS = listOf("serial-list", "serial-open", "serial-write", "serial-set")

/**
 * The bridge: one per service, on the main thread. [calls] runs the
 * requests; [apps] finds apps and paths; [permissions] decides; [host]
 * is the app; [serial] makes a session's [BridgeSerial] ([bridgeSerial]
 * in the app); [rootfs] is the rootfs's host folder (temp files).
 */
class Bridge(
    private val calls: MynxRequests,
    private val apps: WebApps,
    private val permissions: AppPermissions,
    private val host: BridgeHost,
    private val rootfs: File,
    private val serial: (WebApp) -> BridgeSerial = NO_BRIDGE_SERIAL,
) {
    private var temps = 0
    private val tempRoot = File(rootfs, WEB_APP_TEMP_DIR.removePrefix("/"))

    /** Call when the service starts: empties the apps' temp folders' `bridge-*` files left from before. */
    fun start() {
        for (folder in tempRoot.listFiles().orEmpty()) {
            if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            folder.listFiles().orEmpty().filter { it.name.startsWith(BRIDGE_TEMP) }.forEach(::deleteNoFollow)
        }
    }

    /** The wire names offered (`hello`'s `calls`), in [BRIDGE_CALLS] order. */
    fun offered(): List<String> = BRIDGE_CALLS.filter { it.call != "camera" || host.cameraApp }.map { it.call }

    /**
     * Why [app]'s page on [origin] gets no bridge, or null when it
     * does: [origin] must be the app's (scheme and host in any case,
     * port as given); a served app needs a `service`, and it must be
     * running. A refusal is logged (`warn bridge no bridge for ORIGIN:
     * WHY`) before it's returned.
     */
    fun refusal(app: WebApp, origin: String): String? {
        val service = app.service
        val why = when {
            !sameOrigin(origin, app.origin) -> "not ${app.name}'s origin (${app.origin})"
            app.kind == WebAppKind.SERVED && service == null -> "${app.name} has no service (app.conf's service)"
            service != null && !host.serviceRunning(service) -> "$service isn't running"
            else -> return null
        }
        host.log(app.name, WebAppLogLevel.WARN, "no bridge for $origin: $why")
        return why
    }

    /**
     * A new session for one port posted to a page of [app] (after
     * [refusal] said null). [send] takes each message to post on the
     * port (any thread; the app posts it on the port); the hello is sent
     * before this returns. A page load has one per hello (see THE
     * CHANNEL): sessions know nothing of each other.
     */
    fun session(app: WebApp, send: (String) -> Unit): BridgeSession =
        BridgeSession(this, app, send).also { it.hello() }

    internal val requests get() = calls
    internal val appsOnDisk get() = apps
    internal val decider get() = permissions
    internal val bridgeHost get() = host
    internal val rootfsDir get() = rootfs
    internal fun serialFor(app: WebApp) = serial(app)

    /** A new temp file (or folder, with no [ending]) in [app]'s temp folder: its host file and Debian path. */
    internal fun newTemp(app: String, ending: String): Pair<File, String> {
        val name = "$BRIDGE_TEMP${++temps}$ending"
        val folder = File(tempRoot, app).apply { mkdirs() }
        return File(folder, name) to "$WEB_APP_TEMP_DIR/$app/$name"
    }
}

private const val BRIDGE_TEMP = "bridge-"
private const val QUICK_SECONDS = 10
private const val SERVICE_WAIT_SECONDS = MAX_STOP_TIMEOUT + 30
private val RECORD_FORMATS = mapOf(
    "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav",
)
private val AUDIO_ENDINGS = mapOf(
    "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/wav" to "wav",
    "audio/wave" to "wav", "audio/x-wav" to "wav", "audio/mp4" to "m4a", "audio/m4a" to "m4a", "audio/x-m4a" to "m4a",
    "audio/aac" to "aac", "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/webm" to "webm", "audio/midi" to "mid",
)

/** Whether two origins are the same: scheme and host in any case, the port as given. */
internal fun sameOrigin(a: String, b: String): Boolean {
    fun parts(origin: String): Triple<String, String, String>? {
        val at = origin.indexOf("://").takeIf { it > 0 } ?: return null
        val rest = origin.substring(at + 3)
        if (rest.isEmpty() || '/' in rest) return null
        val colon = rest.lastIndexOf(':')
        val host = if (colon >= 0) rest.substring(0, colon) else rest
        val port = if (colon >= 0) rest.substring(colon + 1) else ""
        return Triple(origin.substring(0, at).lowercase(), host.lowercase(), port)
    }
    return parts(a)?.let { it == parts(b) } ?: false
}

/** Deletes [file], a folder with what's in it, without following links (a link goes as a link). */
internal fun deleteNoFollow(file: File) {
    runCatching {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        } else {
            Files.delete(path)
        }
    }
}

/** A parsed JSON value (as [parseJson] gives them) as JSON text again. */
internal fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> json(value)
    is Boolean, is Long, is Int -> value.toString()
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k.toString()) + ":" + toJson(v) }
    is List<*> -> value.joinToString(",", "[", "]") { toJson(it) }
    else -> json(value.toString())
}

private fun megabytes(bytes: Long) = (bytes + (1 shl 20) - 1) / (1 shl 20)

private fun cutText(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"

// A name for a shared file, made safe as the inbox makes names.
private fun safeShareName(wanted: String): String {
    val name = wanted.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim()
    val safe = if (name.isEmpty() || name == "." || name == "..") "file" else name
    if (safe.length <= MAX_SHARED_NAME) return safe
    val ext = safe.substringAfterLast('.', "").takeIf { it.length in 1..10 }?.let { ".$it" } ?: ""
    return safe.take(MAX_SHARED_NAME - ext.length) + ext
}

/** A sink an own call passes to the request it runs: straight to its callbacks, on the caller's thread. */
private class InnerSink(private val onLine: (String) -> Unit = {}, private val onAnswer: (String) -> Unit) : ReplySink {
    override fun waiting(seconds: Int?) {}
    override fun line(json: String) = onLine(json)
    override fun answer(json: String) = onAnswer(json)
    override fun discard() {}
}

private class BridgeRefused(message: String) : Exception(message)

/** One posted port's bridge; a page load has one per hello (see THE CHANNEL). Main thread. */
class BridgeSession internal constructor(
    private val bridge: Bridge,
    private val app: WebApp,
    private val send: (String) -> Unit,
) {
    private val host = bridge.bridgeHost
    private val calls = bridge.requests
    private val serial = bridge.serialFor(app)
    private val own: Map<String, Later> = ownCalls() + serial.calls()
    private val open = linkedMapOf<Long, Entry>()
    private val temps = java.util.Collections.synchronizedSet(mutableSetOf<File>())
    private var foreignLogged = false
    private var rotationHeld = false
    private var isClosed = false

    private class Entry(val id: Long, val def: BridgeCall) {
        var call: Call? = null
        var withdraw: (() -> Unit)? = null
        var timer: (() -> Unit)? = null
        var finished = false
    }

    internal fun hello() {
        val hello = obj("app" to json(app.name), "label" to json(app.label), "calls" to array(bridge.offered().map(::json)))
        post(obj("hello" to hello))
    }

    private fun post(message: String) {
        if (!isClosed) runCatching { send(message) }
    }

    private fun log(message: String) = host.log(app.name, WebAppLogLevel.WARN, message)

    /** A message from the page; [origin] is the view's current URL's origin (the second fence). */
    fun receive(message: String, origin: String) {
        if (isClosed) return
        if (!sameOrigin(origin, app.origin)) {
            if (!foreignLogged) log("message from $origin ignored")
            foreignLogged = true
            return
        }
        if (message.length > MAX_BRIDGE_MESSAGE_CHARS) return log("bad message: longer than $MAX_BRIDGE_MESSAGE_CHARS characters")
        val parsed = try {
            parseJson(message)
        } catch (e: JsonException) {
            return log("bad message: not JSON (${e.message})")
        }
        if (parsed !is Map<*, *>) return log("bad message: not a JSON object")
        val id = (parsed["id"] as? Long)?.takeIf { it >= 1 } ?: return log("bad message: no id (an integer from 1)")
        if (parsed["cancel"] == true) return cancel(id)
        if (id in open) return refuse(id, null, "call $id is already open")
        val name = parsed["call"] as? String ?: return refuse(id, null, "a call needs a name")
        val rawArgs = parsed["args"] ?: emptyList<String>()
        val args = (rawArgs as? List<*>)?.takeIf { list -> list.all { it is String } }?.map { it as String }
            ?: return refuse(id, name, "args must be a list of strings")
        val def = BRIDGE_CALLS.firstOrNull { it.call == name }?.takeIf { it.call in bridge.offered() }
            ?: return refuse(id, name, "$name isn't offered to apps")
        if (open.size >= MAX_BRIDGE_OPEN_CALLS) return refuse(id, name, "too many calls at once")
        val entry = Entry(id, def)
        open[id] = entry
        val permission = def.permission ?: return run(entry, args)
        val withdraw = bridge.decider.check(app, permission, host) { refusal ->
            entry.withdraw = null
            when {
                refusal != null -> finish(entry, refusalJson(refusal.message, refusal.code))
                !isClosed && !entry.finished -> run(entry, args)
            }
        }
        if (entry.call == null && !entry.finished) entry.withdraw = withdraw
    }

    private fun cancel(id: Long) {
        val entry = open[id] ?: return
        val call = entry.call
        if (call != null) call.stop() else entry.withdraw?.invoke()
    }

    private fun run(entry: Entry, args: List<String>) {
        val def = entry.def
        val sink = object : ReplySink {
            override fun waiting(seconds: Int?) {}
            override fun line(json: String) {
                host.schedule(0) { if (!isClosed && !entry.finished) post("""{"id":${entry.id},"line":$json}""") }
            }
            override fun answer(json: String) {
                host.schedule(0) { finish(entry, json) }
            }
            override fun discard() {
                host.schedule(0) { forget(entry) }
            }
        }
        val call = try {
            when (def.route) {
                BridgeRoute.REQUEST -> calls.call(def.call, args, sink)
                BridgeRoute.OWN -> calls.call(own[def.call] ?: return finish(entry, refusalJson("${def.call} isn't available here")), args, sink)
            }
        } catch (e: Exception) {
            return finish(entry, refusalJson("${def.call} failed: ${e.message ?: e.javaClass.simpleName}"))
        }
        entry.call = call
        val seconds = call.seconds
        if (!call.done && seconds != null && seconds > 0 && !entry.finished) {
            entry.timer = host.schedule(seconds * 1000L) {
                if (isClosed || entry.finished) return@schedule
                forget(entry)
                call.cancel()
                reply(entry.id, def.call, refusalJson("${def.call} timed out after $seconds s"))
            }
        }
    }

    private fun refusalJson(message: String, code: BridgeError = BridgeError.REFUSED) =
        """{"ok":false,"error":${json(message)},"code":${json(code.word)}}"""

    private fun forget(entry: Entry) {
        if (entry.finished) return
        entry.finished = true
        if (open[entry.id] === entry) open.remove(entry.id)
        entry.timer?.invoke()
    }

    private fun finish(entry: Entry, json: String) {
        if (isClosed || entry.finished) return
        forget(entry)
        reply(entry.id, entry.def.call, json)
    }

    private fun refuse(id: Long, call: String?, message: String) = reply(id, call, refusalJson(message))

    // Sends the answer, a refusal with its code (refused unless it has one), and logs a refusal.
    private fun reply(id: Long, call: String?, answer: String) {
        val parsed = runCatching { parseJson(answer) as? Map<*, *> }.getOrNull()
        var out = answer
        if (parsed != null && parsed["ok"] == false) {
            if ("code" !in parsed) out = answer.trimEnd().removeSuffix("}") + ""","code":"${BridgeError.REFUSED.word}"}"""
            log(if (call != null) "$call: ${parsed["error"]}" else "${parsed["error"]}")
        }
        post("""{"id":$id,"reply":$out}""")
    }

    /** Ends the session: every call cancelled, nothing sent after. */
    fun close() {
        if (isClosed) return
        isClosed = true
        for (entry in open.values.toList()) {
            entry.finished = true
            entry.timer?.invoke()
            val call = entry.call
            runCatching { if (call != null) call.cancel() else entry.withdraw?.invoke() }
        }
        open.clear()
        runCatching { serial.close() }
        if (rotationHeld) runCatching { host.rotation(app.name, null) }
        rotationHeld = false
        temps.toList().forEach(::deleteNoFollow)
        temps.clear()
    }

    /** Whether [close] was called. */
    val closed: Boolean get() = isClosed

    // THE BRIDGE'S OWN CALLS

    private fun ownCalls(): Map<String, Later> = mapOf(
        "notify" to Later(QUICK_SECONDS) { args, reply -> notify(args, reply) },
        "rotation-lock" to Later(QUICK_SECONDS) { args, reply -> rotationLock(args, reply) },
        "rotation-unlock" to Later(QUICK_SECONDS) { _, reply ->
            host.rotation(app.name, null)?.let { throw BridgeRefused(it) }
            rotationHeld = false
            reply.ok("locked" to json("no"))
        },
        "camera-quick" to Later(QUICK_SHOT_SECONDS) { args, reply ->
            val side = args.firstOrNull()?.trim().orEmpty().ifEmpty { "back" }
            if (side != "front" && side != "back") throw BridgeRefused("the camera is front or back, not '$side'")
            photo("camera-quick", listOf(side), reply)
        },
        "camera" to Later(CAMERA_APP_SECONDS) { _, reply -> photo("camera", emptyList(), reply) },
        "audio-record" to Later(null) { args, reply -> record(args, reply) },
        "audio-play" to Later(null) { args, reply -> play(args, reply) },
        "share-files" to Later(QUICK_SECONDS) { args, reply -> shareFiles(args, reply) },
        "service-list" to Later(QUICK_SECONDS) { args, reply -> service("service-list", args, reply) },
        "service-start" to Later(QUICK_SECONDS) { args, reply -> service("service-start", args, reply) },
        "service-stop" to Later(SERVICE_WAIT_SECONDS) { args, reply -> service("service-stop", args, reply) },
        "service-restart" to Later(SERVICE_WAIT_SECONDS) { args, reply -> service("service-restart", args, reply) },
        "service-log" to Later(QUICK_SECONDS) { args, reply -> service("service-log", args, reply) },
    ).mapValues { (name, later) ->
        Later(later.seconds) { args, reply ->
            try {
                later.start(args.map { it.trim() }, reply)
            } catch (e: BridgeRefused) {
                reply.refuse(e.message!!)
            } catch (e: Exception) {
                reply.refuse("$name failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun notify(args: List<String>, reply: PendingReply) {
        val title = args.getOrNull(0).orEmpty()
        if (title.isEmpty()) throw BridgeRefused("notify needs a title")
        var ifAway = false
        for (option in args.drop(2).filter { it.isNotEmpty() }) {
            if (option == "if-away") ifAway = true else throw BridgeRefused("unknown notify option '$option'")
        }
        val text = args.getOrNull(1).orEmpty()
        val why = host.notify(app, cutText(title, MAX_NOTICE_TITLE), cutText(text, MAX_NOTICE_TEXT), ifAway)
        if (why == null) reply.ok("shown" to "true") else reply.ok("shown" to "false", "reason" to json(why))
    }

    private fun rotationLock(args: List<String>, reply: PendingReply) {
        val words = args.filter { it.isNotEmpty() }
        val orientation = when (words.firstOrNull()) {
            null, "current" -> Orientation.CURRENT
            "portrait" -> Orientation.PORTRAIT
            "landscape" -> Orientation.LANDSCAPE
            else -> null
        }
        if (orientation == null || words.size > 1) throw BridgeRefused("rotation-lock is portrait, landscape or current")
        host.rotation(app.name, orientation)?.let { throw BridgeRefused(it) }
        rotationHeld = true
        reply.ok("locked" to json(orientation.word))
    }

    private fun newTemp(ending: String): Pair<File, String> =
        bridge.newTemp(app.name, ending).also { temps += it.first }

    private fun dropTemp(file: File) {
        deleteNoFollow(file)
        temps -= file
    }

    private fun parse(answer: String): Map<*, *> = runCatching { parseJson(answer) as? Map<*, *> }.getOrNull() ?: emptyMap<String, Any?>()

    // Answers [reply] with [file]'s bytes as base64 (then deletes it), or why not.
    private fun data(reply: PendingReply, file: File, type: String, what: String, vararg fields: Pair<String, String>) {
        try {
            val bytes = file.length()
            when {
                !file.isFile -> reply.refuse("couldn't read the $what")
                bytes > MAX_BRIDGE_DATA_BYTES -> reply.refuse(
                    "the $what is too big for a page: ${megabytes(bytes)} MB (at most ${MAX_BRIDGE_DATA_BYTES shr 20} MB)",
                )
                else -> {
                    val data = Base64.getEncoder().encodeToString(file.readBytes())
                    reply.ok("type" to json(type), "bytes" to bytes.toString(), *fields, "data" to json(data))
                }
            }
        } finally {
            dropTemp(file)
        }
    }

    private fun photo(request: String, extra: List<String>, reply: PendingReply) {
        val (file, path) = newTemp(".jpg")
        val inner = calls.call(request, listOf(path) + extra, InnerSink { answer ->
            if (parse(answer)["ok"] == true) data(reply, file, "image/jpeg", "photo") else {
                dropTemp(file)
                reply.answer(answer)
            }
        })
        reply.onCancel {
            inner.cancel()
            dropTemp(file)
        }
    }

    private fun record(args: List<String>, reply: PendingReply) {
        val format = args.firstOrNull { it.startsWith("format=") }?.substringAfter('=') ?: "m4a"
        val type = RECORD_FORMATS[format] ?: throw BridgeRefused("the format is m4a, aac, ogg, opus or wav")
        val (file, path) = newTemp(".$format")
        val options = args.filter { it.isNotEmpty() && !it.startsWith("format=") }
        val inner = calls.call("audio-record", listOf(path) + options, InnerSink({ reply.line(it) }) { answer ->
            val answered = parse(answer)
            if (answered["ok"] == true) {
                data(reply, file, type, "recording", "seconds" to toJson(answered["seconds"]))
            } else {
                dropTemp(file)
                reply.answer(answer)
            }
        })
        // A stop or the session closing: the recording ends, and says what it saved.
        reply.onCancel { inner.stop() }
    }

    private fun decode(data: String): ByteArray {
        if (data.length.toLong() * 3 / 4 > MAX_BRIDGE_DATA_BYTES) {
            throw BridgeRefused("the data is too big: ${megabytes(data.length.toLong() * 3 / 4)} MB (at most ${MAX_BRIDGE_DATA_BYTES shr 20} MB)")
        }
        return try {
            Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            throw BridgeRefused("data= isn't base64")
        }
    }

    private fun play(args: List<String>, reply: PendingReply) {
        val options = args.filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val path = options["file"]
        val data = options["data"]
        val played: Pair<File?, String> = when {
            path != null -> {
                if (app.kind != WebAppKind.STATIC) throw BridgeRefused("file= is for static apps: send data=")
                val response = if (path.startsWith("/")) bridge.appsOnDisk.respond(app, "GET", path) else null
                val found = response?.file?.takeIf { response.status == 200 }
                    ?: throw BridgeRefused("no such file in ${app.name}: $path")
                val rootfs = bridge.rootfsDir.absoluteFile
                val relative = found.absoluteFile.relativeToOrNull(rootfs)?.invariantSeparatorsPath
                if (relative != null && !relative.startsWith("..")) {
                    null to "/$relative"
                } else {
                    // The tools' apps aren't under the rootfs, where the player looks.
                    val (copy, guest) = newTemp(found.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
                    found.copyTo(copy, overwrite = true)
                    copy to guest
                }
            }
            data != null -> {
                val bytes = decode(data)
                val ending = AUDIO_ENDINGS[options["type"]?.lowercase()]?.let { ".$it" } ?: ""
                val (file, guest) = newTemp(ending)
                file.writeBytes(bytes)
                file to guest
            }
            else -> throw BridgeRefused("audio-play needs file=PATH or data=BASE64 type=MIME")
        }
        val (temp, guest) = played
        val inner = calls.call("audio-play", listOf(guest), InnerSink { answer ->
            temp?.let(::dropTemp)
            val answered = parse(answer)
            when {
                answered["ok"] != true -> reply.answer(answer)
                "seconds" in answered -> reply.ok("seconds" to toJson(answered["seconds"]))
                else -> reply.ok()
            }
        })
        reply.onCancel {
            inner.stop()
            temp?.let(::dropTemp)
        }
    }

    private fun shareFiles(args: List<String>, reply: PendingReply) {
        val usage = "share-files takes name=, type= and data= for each file"
        if (args.isEmpty() || args.size % 3 != 0) throw BridgeRefused(usage)
        val files = args.chunked(3).map { (name, type, data) ->
            if (!name.startsWith("name=") || !type.startsWith("type=") || !data.startsWith("data=")) throw BridgeRefused(usage)
            name.substringAfter('=') to data.substringAfter('=')
        }
        if (files.size > MAX_SHARE_FILES) throw BridgeRefused("too many files: ${files.size} (at most $MAX_SHARE_FILES at once)")
        val decoded = files.map { (name, data) -> name to decode(data) }
        // Not the session's: the receiving app reads them after the page may have gone.
        val (folder, guest) = bridge.newTemp(app.name, "")
        val removeLater = { deleteNoFollow(folder) }
        try {
            folder.mkdirs()
            val taken = mutableSetOf<String>()
            val paths = decoded.map { (wanted, bytes) ->
                val safe = safeShareName(wanted)
                var name = safe
                var n = 1
                while (!taken.add(name)) {
                    n++
                    val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
                    name = "${safe.take(dot)} ($n)${safe.drop(dot)}"
                }
                File(folder, name).writeBytes(bytes)
                "$guest/$name"
            }
            calls.call("share", paths, InnerSink { answer ->
                if (parse(answer)["ok"] == true) host.schedule(BRIDGE_SHARE_KEEP_MS, removeLater) else removeLater()
                reply.answer(answer)
            })
        } catch (e: Exception) {
            removeLater()
            throw e
        }
    }

    private fun service(request: String, args: List<String>, reply: PendingReply) {
        val own = app.service ?: throw BridgeRefused("${app.name} has no service (app.conf's service)")
        if (request == "service-list") {
            calls.call(request, emptyList(), InnerSink { answer ->
                val answered = parse(answer)
                if (answered["ok"] != true) return@InnerSink reply.answer(answer)
                val mine = (answered["services"] as? List<*>).orEmpty().filter { (it as? Map<*, *>)?.get("name") == own }
                reply.ok("services" to toJson(mine))
            })
            return
        }
        val name = args.firstOrNull().orEmpty().ifEmpty { own }
        if (name != own) throw BridgeRefused("$name isn't ${app.name}'s service (app.conf's service)")
        val inner = calls.call(request, listOf(own) + args.drop(1), InnerSink { reply.answer(it) })
        reply.onCancel { inner.cancel() }
    }
}
