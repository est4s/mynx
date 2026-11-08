package io.github.est4s.terminal.core

import java.io.File

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
 * - part D builds the `serial-*` calls ([BridgeSerial], `Rfc2217Client`).
 * Change the shapes here first, then everything else.
 *
 * THE CHANNEL
 *
 * The page's script, `mynx.js`, is in the tools (`web/mynx.js`,
 * [BRIDGE_SCRIPT_FILE]) and the view serves it to any page at
 * `https://mynx.invalid/mynx.js` ([BRIDGE_SCRIPT_URL], through
 * [WebApps.bridgeScript]); alone it has no power. The app makes a
 * `WebView.createWebMessageChannel()` per port it posts and posts one end
 * with `postWebMessage(WebMessage(BRIDGE_PORT_MESSAGE, [port]),
 * Uri.parse(app.origin))`, so Android hands it over only when the main
 * frame is on the app's origin; at `onPageCommitVisible` and again at
 * `onPageFinished` (each a new channel and a new [BridgeSession]).
 * mynx.js keeps the FIRST port it receives and closes later ones, so a
 * later session doesn't close an earlier one: a page load may have two
 * sessions, both closed when the page goes (the next page starting, a
 * reload, the screen finishing), or each when its port closes. Sessions
 * know nothing of each other; the app keeps the page's. Before posting,
 * the view asks [Bridge.refusal] (the page's origin; a served app's
 * service must be running): when it refuses, no port, and it was logged
 * (`warn bridge: no bridge for ORIGIN: WHY`).
 *
 * As a second fence, the app passes each message with the view's
 * current URL's origin to [BridgeSession.receive], which ignores (and
 * logs, once per session: `warn bridge: message from ORIGIN ignored`)
 * any whose origin isn't the app's. A page's own third-party scripts
 * share its origin and so its bridge: the guide says so.
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
 * logged (`warn bridge: bad message: WHY`). With an id: a repeated id
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
 * overload). Every refusal is logged (`warn bridge CALL: MESSAGE`).
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
 * The bridge is an RFC 2217 client of the port's own server
 * (`127.0.0.1:TCPPORT`, [BridgeHost.connectSerial]), as `serial-port`
 * is, so the rules stay: one user at a time (refused "PORT is busy"
 * while a program or the path has it), a Bluetooth link opens when it
 * arrives and closes when it leaves, RTS dropped before DTR on close,
 * raised DTR first.
 * - `serial-list` → `{"ports":[{"name","kind":"usb"|"bluetooth"|
 *   "ble-uart","path","url","in_use":BOOL,"device":TEXT}]}` from
 *   `usb-list`'s ports and `bt-list no-ask`'s bound ports (Bluetooth
 *   left out, not refused, when it can't be asked).
 * - `serial-open PORT [baud=N] [data=5..8] [parity=none|even|odd|mark|
 *   space] [stop=1|1.5|2] [lower]` (baud 115200, 8N1 unless given;
 *   `lower`: DTR and RTS down on open, DTR first, as the monitor's
 *   `--lower`; else both raised): a stream. First line
 *   `{"open":{"port":PORT,"handle":H,"baud":N}}` (H: an integer, per
 *   session), then `{"data":BASE64}` for what the port sends. Stopping
 *   it closes the port; a port that goes away (unplugged, the link
 *   dropped) answers a refusal with why.
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
     * Connects to 127.0.0.1:[tcpPort] off the main thread (part D;
     * `app/.../BridgeSerial.kt`, a thread of its own). [ready] gets the
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
 * Part D's serial calls for one session ([bridgeSerial] makes one per
 * session; until D lands, [NO_BRIDGE_SERIAL]). [calls] are the
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
 * is the app; [serial] makes a session's [BridgeSerial] (part D's
 * `bridgeSerial`); [rootfs] is the rootfs's host folder (temp files).
 */
class Bridge(
    private val calls: MynxRequests,
    private val apps: WebApps,
    private val permissions: AppPermissions,
    private val host: BridgeHost,
    private val rootfs: File,
    private val serial: (WebApp) -> BridgeSerial = NO_BRIDGE_SERIAL,
) {
    /** Call when the service starts: empties the apps' temp folders' `bridge-*` files left from before. */
    fun start(): Unit = TODO("part A")

    /** The wire names offered (`hello`'s `calls`), in [BRIDGE_CALLS] order. */
    fun offered(): List<String> = TODO("part A")

    /**
     * Why [app]'s page on [origin] gets no bridge, or null when it
     * does: [origin] must be the app's (scheme and host in any case,
     * port as given); a served app needs a `service`, and it must be
     * running. A refusal is logged (`warn bridge: no bridge for ORIGIN:
     * WHY`) before it's returned.
     */
    fun refusal(app: WebApp, origin: String): String? = TODO("part A")

    /**
     * A new session for one port posted to a page of [app] (after [refusal] said
     * null). [send] takes each message to post on the port (any thread;
     * the app posts it on the port); the hello is sent before this
     * returns.
     */
    fun session(app: WebApp, send: (String) -> Unit): BridgeSession = TODO("part A")
}

/** One posted port's bridge (a page load may have two) (see SESSIONS AND CANCELLATION). Main thread. */
class BridgeSession internal constructor() {
    /** A message from the page; [origin] is the view's current URL's origin (the second fence). */
    fun receive(message: String, origin: String): Unit = TODO("part A")

    /** Ends the session: every call cancelled, nothing sent after. */
    fun close(): Unit = TODO("part A")

    /** Whether [close] was called. */
    val closed: Boolean get() = TODO("part A")
}
