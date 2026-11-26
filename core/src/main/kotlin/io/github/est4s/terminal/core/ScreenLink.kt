package io.github.est4s.terminal.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Timer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

/** What the link needs from Android: this phone's screen and mDNS (NsdManager). */
interface ScreenPlatform {
    /** This phone's screen now. */
    fun screen(): ScreenMessage.ScreenInfo

    /** Advertises [ad] on [port] (replacing what was advertised); null stops. */
    fun advertise(ad: ScreenAd?, port: Int)

    /** Looks for hosts for [ms], then gives what it found, on any thread. */
    fun discover(ms: Long, found: (List<FoundScreenHost>) -> Unit)

    /** The link's state changed (to show it). */
    fun changed() {}
}

/** What the link hands on for screens sent over it ([ScreenShare]): on the link's own threads. */
interface ScreenMedia {
    /** A message that isn't the link's own (video, touches, streams, focus). */
    fun received(message: ScreenMessage)

    /** The link ended. */
    fun unlinked()
}

/** How many wrong codes stop a pairing: each code takes one try, then the host shows a new one. */
const val MAX_WRONG_CODES = 3
private const val HANDSHAKE_MS = 10_000
private const val CONNECT_MS = 4_000
private const val LAST_WORD_MS = 2_000L

/**
 * The extended screen's link (roadmap "Extended screen"; work log "Next"
 * 0a): a host (phone A) listens on `screen-port` while `screen-host` is
 * `auto` and a screen is paired or a pairing waits, and advertises
 * itself over mDNS ([ScreenPlatform.advertise]); a screen (phone B)
 * connects to it. One link at a time. Pairing ([pair] on the host,
 * [join] on the screen with the code it shows) runs [acceptScreen] /
 * [joinHost] with the code; a paired screen comes back with [connect]
 * and the pair's key. Pairs are kept in [ScreenPairs] in [privateDir]
 * (the app's files, not Debian's); [settingsFile] is Debian's
 * settings.conf, read for the screen settings and written by [place].
 * Sockets and handshakes run on threads of their own; answers go
 * through [PendingReply], which any thread may use.
 */
class ScreenLink(
    privateDir: File,
    private val settingsFile: File,
    private val name: () -> String,
    private val platform: ScreenPlatform,
    private val rng: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    private val keepAliveMs: Long = 5_000,
    private val idleMs: Int = 15_000,
    private val discoverMs: Long = 4_000,
    private val pairingMs: Long = 5 * 60_000,
) {
    val pairs = ScreenPairs(privateDir, rng)
    private val lock = Any()
    private val timer by lazy { Timer("mynx-screen", true) }

    private class Pairing(val reply: PendingReply, var code: String) {
        var wrong = 0
        var trying = false
    }

    private var pairing: Pairing? = null
    private var server: ServerSocket? = null
    private var serverPort = 0
    private var listenProblem: String? = null
    private var connection: Connection? = null
    private var lastEnd: String? = null
    private var stopped = false

    /** Gets the screens' messages ([ScreenMedia]); set once, by the app. */
    @Volatile var media: ScreenMedia? = null

    private fun settings() = loadSettings(settingsFile).settings

    private fun me() = ScreenMe(pairs.selfId, name())

    /**
     * Listens, or stops, as the settings and pairs say; call at start
     * and when the settings change. Main thread or any.
     */
    fun refresh() {
        val settings = settings()
        val (ad, port) = synchronized(lock) {
            val want = !stopped && settings.screenHost == ScreenHosting.AUTO &&
                (pairing != null || pairs.all().any { it.role == ScreenRole.SCREEN })
            if (!want || serverPort != settings.screenPort) closeServer()
            if (want && server == null) listen(settings.screenPort)
            (server?.let { screenAd(pairs.selfId, name(), pairing != null) }) to serverPort
        }
        platform.advertise(ad, port)
        platform.changed()
    }

    private fun closeServer() {
        runCatching { server?.close() }
        server = null
        serverPort = 0
    }

    private fun listen(port: Int) {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                // Every network: the screen is another phone on the Wi-Fi.
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            listenProblem = "couldn't listen on port $port: ${e.message}"
            return
        }
        listenProblem = null
        server = socket
        serverPort = port
        thread(name = "mynx-screen-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "mynx-screen-accept", isDaemon = true) { accept(client) }
            }
        }
    }

    /** Stops listening and ends the link (the app is going). */
    fun stop() {
        synchronized(lock) {
            stopped = true
            closeServer()
            pairing?.reply?.refuse("the app stopped")
            pairing = null
        }
        connection?.close("the app stopped", last = ScreenMessage.Bye("the app stopped"))
        platform.advertise(null, 0)
    }

    // --- The host ---------------------------------------------------------

    /** On the host: shows a code (stream lines) and waits for a screen to pair with it. */
    fun pair(reply: PendingReply) {
        val settings = settings()
        val p = synchronized(lock) {
            when {
                settings.screenHost == ScreenHosting.OFF ->
                    return reply.refuse("this phone doesn't take screens: screen-host is off (mynx set screen-host auto)")
                connection != null ->
                    return reply.refuse("already linked with ${connection!!.peerName} (mynx screen disconnect first)")
            }
            pairing?.reply?.refuse("another mynx screen pair took over")
            Pairing(reply, newPairingCode(rng)).also { pairing = it }
        }
        reply.onCancel { endPairing(p, null) }
        refresh()
        synchronized(lock) {
            if (server == null) {
                pairing = null
                return reply.refuse(listenProblem ?: "this phone isn't listening for screens")
            }
        }
        reply.line(codeLine(p))
        timer.schedule(pairingMs) { endPairing(p, "no phone paired in ${pairingMs / 60_000} minutes (run mynx screen pair again)") }
    }

    private fun codeLine(p: Pairing) = obj(
        "code" to json(showPairingCode(p.code)),
        "name" to json(name()),
        "port" to serverPort.toString(),
        "addresses" to array(localAddresses().map(::json)),
        "wrong" to p.wrong.toString(),
    )

    // Ends pairing [p] (if it's still the one), refusing with [why] (null: cancelled, nothing to say).
    private fun endPairing(p: Pairing, why: String?) {
        synchronized(lock) {
            if (pairing !== p) return
            pairing = null
        }
        why?.let(p.reply::refuse)
        refresh()
    }

    // A pairing try that didn't link: the code is used up either way.
    private fun codeUsed(p: Pairing) {
        val line = synchronized(lock) {
            if (pairing !== p) return
            p.trying = false
            p.wrong++
            if (p.wrong < MAX_WRONG_CODES) {
                p.code = newPairingCode(rng)
                codeLine(p)
            } else {
                null
            }
        }
        if (line != null) p.reply.line(line) else endPairing(p, "$MAX_WRONG_CODES wrong codes: pairing stopped (run mynx screen pair again)")
    }

    private fun accept(socket: Socket) {
        var tried: Pairing? = null
        try {
            socket.soTimeout = HANDSHAKE_MS
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val linked = acceptScreen(input, out, me()) { hello -> secretFor(hello).also { if (hello.mode == HelloMode.PAIR) tried = pairing } }
            socket.soTimeout = idleMs
            val p = tried
            synchronized(lock) {
                if (linked.mode == HelloMode.PAIR) {
                    pairs.save(ScreenPeer(linked.peerId, linked.peerName, ScreenRole.SCREEN, linked.pairKey, null, now()))
                    if (pairing === p) pairing = null
                } else {
                    pairs.get(linked.peerId)?.let {
                        pairs.save(ScreenPeer(it.id, linked.peerName, it.role, it.key, it.address, it.paired))
                    }
                }
            }
            connection?.close("${linked.peerName} connected again")
            val c = Connection(socket, linked, ScreenRole.HOST)
            synchronized(lock) { connection = c }
            p?.reply?.ok("paired" to peerJson(linked.peerId, linked.peerName, ScreenRole.SCREEN))
            c.start()
            refresh()
        } catch (e: IOException) {
            runCatching { socket.close() }
            tried?.let(::codeUsed)
        }
    }

    private fun secretFor(hello: ScreenMessage.Hello): ByteArray = synchronized(lock) {
        val host = name()
        if (settings().screenHost == ScreenHosting.OFF) {
            throw ScreenRefused(RefusalReason.OFF, "$host doesn't take screens (screen-host is off)")
        }
        val linked = connection
        when (hello.mode) {
            HelloMode.PAIR -> {
                val p = pairing ?: throw ScreenRefused(RefusalReason.NOT_PAIRING, "$host isn't pairing: run mynx screen pair on it first")
                if (p.trying || linked != null) throw ScreenRefused(RefusalReason.BUSY, "$host is busy pairing: try again")
                p.trying = true
                p.code.toByteArray()
            }
            HelloMode.RESUME -> {
                val peer = pairs.get(hello.id)?.takeIf { it.role == ScreenRole.SCREEN }
                    ?: throw ScreenRefused(RefusalReason.NOT_PAIRED, "$host isn't paired with this phone: pair again (mynx screen pair CODE)")
                if (linked != null && linked.peerId != hello.id) {
                    throw ScreenRefused(RefusalReason.BUSY, "$host is showing on ${linked.peerName} now")
                }
                peer.key
            }
        }
    }

    // --- The screen -------------------------------------------------------

    /** On the screen: pairs with the host showing [typed], at [address] or found over mDNS. */
    fun join(typed: String, address: String?, reply: PendingReply) {
        val code = pairingCode(typed) ?: return reply.refuse("the code is six digits: the one mynx screen pair shows on the host")
        connection?.let { return reply.refuse("already linked with ${it.peerName} (mynx screen disconnect first)") }
        if (address != null) {
            val (host, port) = screenAddress(address, settings().screenPort)
                ?: return reply.refuse("not an address: $address (an IP address or name, :PORT if it isn't ${settings().screenPort})")
            thread(name = "mynx-screen-join", isDaemon = true) { link(host, port, HelloMode.PAIR, code.toByteArray(), null, reply) }
            return
        }
        platform.discover(discoverMs) { found ->
            val hosts = found.filter { it.pairing && it.id != pairs.selfId }.distinctBy { it.id }
            when (hosts.size) {
                0 -> reply.refuse(
                    "no phone is pairing on this network: run mynx screen pair on it, or give its address " +
                        "(mynx screen pair CODE ADDRESS)",
                )
                1 -> thread(name = "mynx-screen-join", isDaemon = true) {
                    link(hosts[0].address, hosts[0].port, HelloMode.PAIR, code.toByteArray(), null, reply)
                }
                else -> reply.refuse(
                    "${hosts.size} phones are pairing (${hosts.joinToString(", ") { it.name }}): give the address (mynx screen pair CODE ADDRESS)",
                )
            }
        }
    }

    /** On the screen: links again with a paired host ([which]: its name or id; null: the only one). */
    fun connect(which: String?, reply: PendingReply) {
        val hosts = pairs.all().filter { it.role == ScreenRole.HOST }
        val peer = if (which == null) {
            hosts.singleOrNull() ?: return reply.refuse(
                if (hosts.isEmpty()) "this phone isn't paired with a host: mynx screen pair CODE pairs it"
                else "paired with ${hosts.joinToString(", ") { it.name }}: say which (mynx screen connect NAME)",
            )
        } else {
            hosts.firstOrNull { it.id == which || it.name.equals(which, ignoreCase = true) }
                ?: return reply.refuse("not paired with a host called $which (mynx screen status lists them)")
        }
        connection?.let {
            if (it.peerId == peer.id) return reply.ok("connected" to peerJson(peer.id, peer.name, peer.role), "already" to "true")
            return reply.refuse("already linked with ${it.peerName} (mynx screen disconnect first)")
        }
        val saved = peer.address?.let { screenAddress(it, SCREEN_PORT) }
        thread(name = "mynx-screen-connect", isDaemon = true) {
            if (saved != null && link(saved.first, saved.second, HelloMode.RESUME, peer.key, peer.id, reply, quietly = true)) return@thread
            platform.discover(discoverMs) { found ->
                val host = found.firstOrNull { it.id == peer.id }
                    ?: return@discover reply.refuse("couldn't find ${peer.name} on this network (is mynx running there, on the same Wi-Fi?)")
                thread(name = "mynx-screen-connect", isDaemon = true) {
                    link(host.address, host.port, HelloMode.RESUME, peer.key, peer.id, reply)
                }
            }
        }
    }

    // Connects and runs the screen's handshake; true if linked (or answered). With [quietly], a
    // host that can't be reached isn't answered (the caller tries mDNS next).
    private fun link(
        host: String,
        port: Int,
        mode: HelloMode,
        secret: ByteArray,
        hostId: String?,
        reply: PendingReply,
        quietly: Boolean = false,
    ): Boolean {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
        } catch (e: IOException) {
            runCatching { socket.close() }
            if (!quietly) reply.refuse("couldn't reach ${hostPort(host, port)}: ${e.message}")
            return false
        }
        try {
            socket.soTimeout = HANDSHAKE_MS
            socket.tcpNoDelay = true
            val linked = joinHost(socket.getInputStream(), socket.getOutputStream(), me(), mode, secret, hostId)
            socket.soTimeout = idleMs
            val old = pairs.get(linked.peerId)
            pairs.save(
                ScreenPeer(linked.peerId, linked.peerName, ScreenRole.HOST, linked.pairKey, hostPort(host, port), old?.paired ?: now()),
            )
            val c = Connection(socket, linked, ScreenRole.SCREEN)
            synchronized(lock) {
                connection?.close("replaced")
                connection = c
            }
            reply.ok((if (mode == HelloMode.PAIR) "paired" else "connected") to peerJson(linked.peerId, linked.peerName, ScreenRole.HOST))
            c.start()
            platform.changed()
        } catch (e: ScreenRefused) {
            runCatching { socket.close() }
            reply.refuse(e.message ?: "refused")
        } catch (e: IOException) {
            runCatching { socket.close() }
            reply.refuse("the link to ${hostPort(host, port)} failed: ${e.message}")
        }
        return true
    }

    // --- Either -----------------------------------------------------------

    /** Sends [message] on the link (its writer's queue); false if there's none. Any thread. */
    fun send(message: ScreenMessage): Boolean = connection?.send(message) ?: false

    /** The other phone while linked: its name, this phone's role, its screen once it said. */
    fun peer(): LinkedPeer? = connection?.let { LinkedPeer(it.peerName, it.myRole, it.peerScreen) }

    /** Ends the link; false if there was none. */
    fun disconnect(): Boolean {
        val c = connection ?: return false
        c.close("disconnected here", last = ScreenMessage.Bye("disconnected"))
        return true
    }

    /** Forgets the pairs [which] names (a name or id, or `all`), telling a linked one; their names. */
    fun unpair(which: String): List<String>? {
        val gone = if (which == "all") pairs.all() else listOfNotNull(pairs.find(which))
        if (gone.isEmpty()) return null
        for (peer in gone) {
            connection?.takeIf { it.peerId == peer.id }?.let {
                it.close("unpaired here", last = ScreenMessage.Unpaired)
            }
            pairs.forget(peer.id)
        }
        refresh()
        return gone.map { it.name }
    }

    /**
     * Where the screen sits: on the host (or unlinked) it's this phone's
     * `screen-place`, sent to a linked screen; on a linked screen it asks
     * the host, which has the final word. Returns whether it was only asked.
     */
    fun place(side: ScreenSide): Boolean {
        val c = connection
        if (c != null && c.myRole == ScreenRole.SCREEN) {
            c.send(ScreenMessage.Layout(side, ScreenAlign.START))
            return true
        }
        writePlace(side)
        c?.sendLayout()
        return false
    }

    private fun writePlace(side: ScreenSide) {
        val text = setSetting(settingsFile.takeIf { it.isFile }?.readText(), "screen-place", side.word).getOrThrow()
        settingsFile.parentFile.mkdirs()
        val tmp = File(settingsFile.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(settingsFile)) error("couldn't write the settings")
    }

    /** The state for `mynx screen status`, as JSON fields. */
    fun status(): Array<Pair<String, String>> {
        val settings = settings()
        return synchronized(lock) {
            val c = connection
            arrayOf(
                "id" to json(pairs.selfId),
                "name" to json(name()),
                "hosting" to json(settings.screenHost.word),
                "listening" to (if (server != null) serverPort.toString() else "null"),
                "problem" to (listenProblem?.let(::json) ?: "null"),
                "pairing" to (pairing?.let { json(showPairingCode(it.code)) } ?: "null"),
                "place" to json(settings.screenPlace.word),
                "connected" to (c?.json() ?: "null"),
                "lastEnd" to (lastEnd?.let(::json) ?: "null"),
                "paired" to array(
                    pairs.all().map { obj("id" to json(it.id), "name" to json(it.name), "role" to json(it.role.word), "since" to it.paired.toString()) },
                ),
            )
        }
    }

    private fun peerJson(id: String, name: String, role: ScreenRole) =
        obj("id" to json(id), "name" to json(name), "role" to json(role.word))

    /** A link up: a reader thread, and keep-alives so a gone phone is noticed within [idleMs]. */
    private inner class Connection(private val socket: Socket, linked: ScreenLinked, val myRole: ScreenRole) {
        val peerId = linked.peerId
        val peerName = linked.peerName
        private val channel = linked.channel
        private val input = BufferedInputStream(socket.getInputStream())
        private val out = BufferedOutputStream(socket.getOutputStream())
        private val since = now()

        @Volatile var peerScreen: ScreenMessage.ScreenInfo? = null
            private set

        @Volatile private var layout: ScreenMessage.Layout? = null

        @Volatile private var closed = false

        // What to send, written by a thread of its own: callers may be on Android's main thread,
        // which mustn't touch the network. Idle, it sends a keep-alive.
        private val outbox = LinkedBlockingQueue<ScreenMessage>()

        fun start() {
            send(platform.screen())
            if (myRole == ScreenRole.HOST) sendLayout()
            thread(name = "mynx-screen-read", isDaemon = true) { read() }
            thread(name = "mynx-screen-write", isDaemon = true) { write() }
        }

        fun sendLayout() {
            val l = ScreenMessage.Layout(settings().screenPlace, ScreenAlign.START)
            layout = l
            send(l)
        }

        fun send(message: ScreenMessage): Boolean {
            if (closed) return false
            outbox.offer(message)
            return true
        }

        private fun write() {
            var said = false
            try {
                while (true) {
                    val m = outbox.poll(keepAliveMs, TimeUnit.MILLISECONDS) ?: ScreenMessage.KeepAlive
                    if (m === CLOSE) break
                    if (closed && m !is ScreenMessage.Bye && m !is ScreenMessage.Unpaired) continue
                    channel.write(out, m)
                    if (closed) {
                        said = true
                        break
                    }
                }
            } catch (e: IOException) {
                close("the link to $peerName broke: ${e.message}")
            } catch (e: InterruptedException) {
                // Going.
            }
            if (said) {
                // Closing at once could reset the connection before the other phone reads the last
                // message: end our side and let it close, or close in a moment.
                runCatching { socket.shutdownOutput() }
                timer.schedule(LAST_WORD_MS) { runCatching { socket.close() } }
            } else {
                runCatching { socket.close() }
            }
        }

        private fun read() {
            try {
                // Once closed, read on until the other side closes: closing with its messages
                // unread would reset the connection, losing our last one (a Bye) on its way.
                while (true) {
                    val m = channel.read(input)
                    if (!closed) handle(m)
                }
            } catch (e: SocketTimeoutException) {
                close("$peerName didn't answer for ${idleMs / 1000} s")
            } catch (e: IOException) {
                close(if (closed) "" else "$peerName left (${e.message ?: "the connection closed"})")
            }
            if (closed) runCatching { socket.close() }
        }

        private fun handle(m: ScreenMessage) {
            when (m) {
                is ScreenMessage.ScreenInfo -> peerScreen = m
                is ScreenMessage.Layout -> if (myRole == ScreenRole.HOST) {
                    writePlace(m.side)
                    sendLayout()
                } else {
                    layout = m
                }
                is ScreenMessage.Bye -> close("$peerName ended the link (${m.reason})")
                is ScreenMessage.Unpaired -> {
                    pairs.forget(peerId)
                    close("$peerName unpaired")
                    refresh()
                }
                ScreenMessage.KeepAlive, is ScreenMessage.Unknown -> {}
                // Video, touches, streams: the screens', many a second, and the link's state is the same.
                else -> return media?.received(m) ?: Unit
            }
            platform.changed()
        }

        /** Ends the link; [last] (a Bye, Unpaired) is sent first, then the writer closes the socket. */
        fun close(why: String, last: ScreenMessage? = null) {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            outbox.offer(last ?: CLOSE)
            if (last == null) runCatching { socket.close() }
            val wasCurrent = synchronized(lock) {
                val current = connection === this
                if (current) connection = null
                if (why.isNotEmpty()) lastEnd = why
                current
            }
            if (wasCurrent) media?.unlinked()
            platform.changed()
        }

        fun json(): String {
            val s = peerScreen
            return obj(
                "id" to json(peerId),
                "name" to json(peerName),
                "role" to json((if (myRole == ScreenRole.HOST) ScreenRole.SCREEN else ScreenRole.HOST).word),
                "since" to since.toString(),
                "screen" to (s?.let { obj("width" to "${it.width}", "height" to "${it.height}", "density" to "${it.density}", "rotation" to "${it.rotation}") } ?: "null"),
                "place" to (layout?.let { json(it.side.word) } ?: "null"),
            )
        }
    }
}

// Tells a link's writer to stop.
private val CLOSE: ScreenMessage = ScreenMessage.Unknown(0, ByteArray(0))

private fun hostPort(host: String, port: Int) = if (':' in host) "[$host]:$port" else "$host:$port"

/** This phone's IPv4 addresses on its networks (to type on the screen when mDNS finds nothing). */
fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
}.getOrDefault(emptyList())

/** The `screen-*` requests (`mynx screen`), answered by [link] and, for the screens sent, [share]. */
fun screenRequests(link: ScreenLink, share: ScreenShare? = null): Map<String, Later> = mapOf(
    "screen-status" to Later(5) { _, reply -> reply.ok(*link.status(), *(share?.status() ?: emptyArray())) },
    "screen-send" to Later(10) { args, reply ->
        val kind = args.getOrNull(0)
        val name = args.getOrNull(1)?.trim().orEmpty()
        if (kind !in setOf("app", "tab", "desktop") || (kind == "app" && name.isEmpty())) {
            return@Later reply.refuse("send what? mynx screen send app NAME, tab [N|new] or desktop")
        }
        if (share == null) return@Later reply.refuse("this phone can't send screens")
        if (kind == "desktop") {
            return@Later share.send(ScreenTarget.Desktop).fold(
                { sent ->
                    val what = obj("id" to "${sent.id}", "kind" to json("desktop"), "title" to json(sent.title), "name" to json("desktop"))
                    reply.ok("screen" to what, *link.status(), *share.status())
                },
                { reply.refuse(it.message ?: "couldn't send the desktop") },
            )
        }
        if (kind == "tab") {
            return@Later share.sendTerm(ScreenTarget.Terminal(name.ifEmpty { "new" })).fold(
                { sent ->
                    val what = obj("id" to "${sent.id}", "kind" to json("terminal"), "title" to json(sent.title), "name" to json(sent.target.name))
                    reply.ok("screen" to what, *link.status(), *share.status())
                },
                { reply.refuse(it.message ?: "couldn't send the tab") },
            )
        }
        share.send(ScreenTarget.App(name)).fold(
            { sent ->
                val what = obj("id" to "${sent.id}", "kind" to json("app"), "title" to json(sent.title), "name" to json(name))
                reply.ok("screen" to what, *link.status(), *share.status())
            },
            { reply.refuse(it.message ?: "couldn't send $name") },
        )
    },
    "screen-take" to Later(10) { args, reply ->
        val which = args.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "all"
        val taken = share?.take(which).orEmpty()
        if (taken.isEmpty()) {
            return@Later reply.refuse(
                if (which == "all") "nothing sent to take back" else "nothing sent called $which (mynx screen status lists them)",
            )
        }
        reply.ok("taken" to array(taken.map { obj("id" to "${it.id}", "title" to json(it.title)) }), *link.status(), *share!!.status())
    },
    // The host's pairing: the code (and each new one after a wrong try) as stream lines.
    "screen-pair" to Later(null) { _, reply -> link.pair(reply) },
    "screen-join" to Later(30) { args, reply ->
        val code = args.getOrNull(0)?.trim().orEmpty()
        link.join(code, args.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }, reply)
    },
    "screen-connect" to Later(30) { args, reply -> link.connect(args.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }, reply) },
    "screen-disconnect" to Later(5) { _, reply -> reply.ok("disconnected" to link.disconnect().toString(), *link.status()) },
    "screen-unpair" to Later(5) { args, reply ->
        val which = args.getOrNull(0)?.trim().orEmpty()
        if (which.isEmpty()) return@Later reply.refuse("unpair needs a phone's name, or all")
        val gone = link.unpair(which) ?: return@Later reply.refuse("not paired with $which (mynx screen status lists the pairs)")
        reply.ok("unpaired" to array(gone.map(::json)), *link.status())
    },
    "screen-place" to Later(5, change = { args -> "set screen-place ${args.firstOrNull().orEmpty()}" }) { args, reply ->
        val side = args.getOrNull(0)?.trim()?.let(::screenSide)
            ?: return@Later reply.refuse("place is left, right, above or below")
        val asked = link.place(side)
        reply.ok("asked" to asked.toString(), *link.status())
    },
)
