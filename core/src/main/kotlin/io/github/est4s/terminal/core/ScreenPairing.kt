package io.github.est4s.terminal.core

/** Settings' page for pairing this phone as the host: its code, big. */
const val SCREEN_PAIR_HOST_MENU = "screen-pair:host"

/** Settings' page for pairing with a host: the hosts found, a code to type. */
const val SCREEN_PAIR_JOIN_MENU = "screen-pair:join"

/** A paired phone's page, `screen-peer:ID`: Connect, Disconnect, Unpair. */
const val SCREEN_PEER_MENU = "screen-peer:"

/** A pair as settings lists it: [role] is the other phone's. */
data class ScreenPairRow(val id: String, val name: String, val role: ScreenRole)

/**
 * The link as settings shows it ([ScreenLink.state]): this phone's [name],
 * `screen-host`, the code of a pairing that waits (from settings or
 * `mynx screen pair`), the phone linked, why the last link ended, why it
 * can't listen, and the pairs.
 */
data class ScreenLinkState(
    val name: String,
    val hosting: ScreenHosting,
    val pairingCode: String?,
    val linkedId: String?,
    val linkedName: String?,
    val lastEnd: String?,
    val problem: String?,
    val pairs: List<ScreenPairRow>,
)

/** Pairing this phone as the host, from settings. */
sealed interface HostPairing {
    data object Idle : HostPairing
    data object Starting : HostPairing
    /** Showing [code] (`123 456`); [addresses] are `IP:PORT`; [wrong] tries so far. */
    data class Waiting(val code: String, val name: String, val addresses: List<String>, val wrong: Int) : HostPairing
    data class Paired(val peer: String) : HostPairing
    data class Stopped(val why: String) : HostPairing
}

/** Pairing with a host, from settings (on the screen phone). */
sealed interface JoinPairing {
    data object Idle : JoinPairing
    data class Joining(val host: String) : JoinPairing
    data class Paired(val host: String) : JoinPairing
    data class Failed(val why: String) : JoinPairing
}

/** Everything settings' pairing pages show, at one moment ([screenPairingItems] draws it). */
data class ScreenPairingShown(
    val link: ScreenLinkState,
    val host: HostPairing = HostPairing.Idle,
    val join: JoinPairing = JoinPairing.Idle,
    val searching: Boolean = false,
    val found: List<FoundScreenHost> = emptyList(),
    val typed: String? = null,
    val connecting: String? = null,
    val problem: String? = null,
)

/**
 * Touch mode's pairing (Settings → Second screen; maintainer's request,
 * 2026-11-27: pairing without the terminal, on both phones). It runs the
 * same `screen-*` requests `mynx screen` sends, through [call] (the app's
 * [MynxRequests.call]), so the protocol is the link's alone; this keeps
 * what the pages show and calls [changed] when that changes, on any
 * thread. [act] takes Home's [HomeAction.Config]s named `screen-pair-*`
 * (main thread) and returns a refusal to show, or null.
 */
class ScreenPairing(
    private val link: ScreenLink,
    private val call: (String, List<String>, ReplySink) -> Call,
    private val changed: () -> Unit,
    private val searchRounds: Int = 15,
) {
    private val lock = Any()
    private var host: HostPairing = HostPairing.Idle
    private var hostTry = 0
    private var hostCall: Call? = null
    private var join: JoinPairing = JoinPairing.Idle
    private var joinTry = 0
    private var searching = false
    private var searchTry = 0
    private var found: List<FoundScreenHost> = emptyList()
    private var typed: String? = null
    private var connecting: String? = null
    private var problem: String? = null

    /** What the pages show now. Any thread. */
    fun shown(): ScreenPairingShown {
        val state = link.state()
        return synchronized(lock) { ScreenPairingShown(state, host, join, searching, found, typed, connecting, problem) }
    }

    /** The page [menu] (the settings group's own rows for `settings:screen`), empty if it isn't one of these. */
    fun items(menu: String): List<HomeItem> = screenPairingItems(shown(), menu).orEmpty()

    /** Whether [request] is one of [act]'s. */
    fun handles(request: String) = request.startsWith("screen-pair-")

    fun act(request: String, args: List<String>): String? {
        val refusal = when (request) {
            "screen-pair-host" -> startHost()
            "screen-pair-cancel" -> cancelHost()
            "screen-pair-done" -> done()
            "screen-pair-find" -> find()
            "screen-pair-search" -> search()
            "screen-pair-address" -> address(args.getOrNull(0).orEmpty())
            "screen-pair-join" -> join(args)
            "screen-pair-connect" -> connect(args.getOrNull(0).orEmpty())
            "screen-pair-disconnect" -> answered("screen-disconnect", emptyList())
            "screen-pair-unpair" -> answered("screen-unpair", listOf(args.getOrNull(0).orEmpty()))
            else -> "not a pairing request: $request"
        }
        changed()
        return refusal
    }

    private fun startHost(): String? {
        val attempt = synchronized(lock) {
            if (host is HostPairing.Waiting || host is HostPairing.Starting) return null
            host = HostPairing.Starting
            ++hostTry
        }
        val sink = Sink(onLine = { line -> hostLine(attempt, line) }) { answer -> hostAnswer(attempt, answer) }
        val c = call("screen-pair", emptyList(), sink)
        synchronized(lock) { if (hostTry == attempt && !c.done) hostCall = c }
        return null
    }

    private fun hostLine(attempt: Int, line: Map<*, *>) {
        synchronized(lock) {
            if (hostTry != attempt) return
            val port = jsonInt(line["port"])
            val addresses = (line["addresses"] as? List<*>).orEmpty().filterIsInstance<String>().map { hostPort(it, port) }
            host = HostPairing.Waiting(line["code"] as? String ?: "", line["name"] as? String ?: "", addresses, jsonInt(line["wrong"]))
        }
        changed()
    }

    private fun hostAnswer(attempt: Int, answer: Map<*, *>) {
        synchronized(lock) {
            if (hostTry != attempt) return
            hostCall = null
            host = when {
                answer["ok"] != true -> HostPairing.Stopped(screenProblemText(answer["error"] as? String ?: "pairing failed"))
                else -> ((answer["paired"] as? Map<*, *>)?.get("name") as? String)?.let(HostPairing::Paired) ?: HostPairing.Idle
            }
        }
        changed()
    }

    private fun cancelHost(): String? {
        val c = synchronized(lock) {
            hostTry++
            host = HostPairing.Idle
            hostCall.also { hostCall = null }
        }
        c?.stop()
        return null
    }

    private fun done(): String? {
        synchronized(lock) {
            if (host is HostPairing.Paired || host is HostPairing.Stopped) host = HostPairing.Idle
            if (join is JoinPairing.Paired) join = JoinPairing.Idle
        }
        return null
    }

    // The join page opens: an old result goes, and the search starts.
    private fun find(): String? {
        synchronized(lock) { if (join !is JoinPairing.Joining) join = JoinPairing.Idle }
        return search()
    }

    private fun search(): String? {
        val attempt = synchronized(lock) {
            if (searching) return null
            searching = true
            ++searchTry
        }
        round(attempt, 1, emptyList())
        return null
    }

    // One round of mDNS, then the next while the search lasts.
    private fun round(attempt: Int, n: Int, last: List<FoundScreenHost>) {
        link.findHosts { now ->
            val more = synchronized(lock) {
                if (searchTry != attempt) {
                    false
                } else {
                    // A host one round missed stays a round: mDNS answers come and go.
                    found = now + last.filter { old -> now.none { it.id == old.id } }
                    val go = n < searchRounds && join !is JoinPairing.Joining
                    if (!go) searching = false
                    go
                }
            }
            changed()
            if (more) round(attempt, n + 1, now)
        }
    }

    private fun address(text: String): String? {
        val typed = text.trim()
        screenAddress(typed, SCREEN_PORT)
            ?: return "Not an address: $typed. Type the address the host shows under its code, like 192.168.1.20."
        synchronized(lock) { this.typed = typed }
        return null
    }

    private fun join(args: List<String>): String? {
        val code = pairingCode(args.getOrNull(0).orEmpty()) ?: return "The code is six digits."
        val address = args.getOrNull(1).orEmpty()
        val name = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: address
        val attempt = synchronized(lock) {
            if (join is JoinPairing.Joining) return "Pairing with ${(join as JoinPairing.Joining).host} already."
            join = JoinPairing.Joining(name)
            searching = false
            searchTry++
            ++joinTry
        }
        call("screen-join", listOf(code, address), Sink { answer ->
            synchronized(lock) {
                if (joinTry == attempt) {
                    join = if (answer["ok"] == true) {
                        JoinPairing.Paired(((answer["paired"] as? Map<*, *>)?.get("name") as? String) ?: name)
                    } else {
                        JoinPairing.Failed(screenProblemText(answer["error"] as? String ?: "pairing failed"))
                    }
                }
            }
            changed()
        })
        return null
    }

    private fun connect(id: String): String? {
        synchronized(lock) {
            if (connecting != null) return null
            connecting = id
            problem = null
        }
        call("screen-connect", listOf(id), Sink { answer ->
            synchronized(lock) {
                connecting = null
                problem = if (answer["ok"] == true) null else screenProblemText(answer["error"] as? String ?: "connecting failed")
            }
            changed()
        })
        return null
    }

    // A request answered at once (disconnect, unpair): its refusal, as settings says it.
    private fun answered(name: String, args: List<String>): String? {
        var answer: Map<*, *>? = null
        call(name, args, Sink { answer = it })
        synchronized(lock) { problem = null }
        val error = answer?.takeIf { it["ok"] != true }?.let { it["error"] as? String ?: "$name failed" } ?: return null
        return screenProblemText(error)
    }

    private class Sink(private val onLine: (Map<*, *>) -> Unit = {}, private val onAnswer: (Map<*, *>) -> Unit) : ReplySink {
        override fun waiting(seconds: Int?) {}
        override fun line(json: String) {
            (runCatching { parseJson(json) }.getOrNull() as? Map<*, *>)?.let(onLine)
        }
        override fun answer(json: String) {
            onAnswer(runCatching { parseJson(json) }.getOrNull() as? Map<*, *> ?: mapOf("ok" to false, "error" to json))
        }
        override fun discard() {}
    }
}

private fun jsonInt(value: Any?): Int = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0

private fun hostPort(host: String, port: Int) = if (':' in host) "[$host]:$port" else "$host:$port"

private fun row(label: String, action: HomeAction = HomeAction.None, detail: String? = null) = HomeItem(label, action, detail = detail)
private fun section(label: String) = HomeItem(label, HomeAction.None, kind = HomeRow.SECTION)
private fun text(label: String) = HomeItem(label, HomeAction.None, kind = HomeRow.TEXT)
private fun info(label: String, detail: String) = HomeItem(label, HomeAction.None, detail = detail, kind = HomeRow.INFO)
private fun error(label: String) = HomeItem(label, HomeAction.None, kind = HomeRow.ERROR)
private fun pairAction(request: String, vararg args: String, back: Boolean = false) = HomeAction.Config(request, args.toList(), back = back)

/** Settings' pairing rows for [menu] (the Second screen group's, or a pairing page), or null when it isn't one. */
fun screenPairingItems(shown: ScreenPairingShown, menu: String): List<HomeItem>? = when {
    menu == "settings:screen" -> groupItems(shown)
    menu == SCREEN_PAIR_HOST_MENU -> hostItems(shown.host)
    menu == SCREEN_PAIR_JOIN_MENU -> joinItems(shown)
    menu.startsWith(SCREEN_PEER_MENU) -> peerItems(shown, menu.removePrefix(SCREEN_PEER_MENU))
    else -> null
}

private fun groupItems(shown: ScreenPairingShown): List<HomeItem> {
    val link = shown.link
    val status = when {
        link.linkedName != null -> "Linked with ${link.linkedName}"
        link.pairingCode != null -> "Pairing, code ${link.pairingCode}"
        else -> "Not linked"
    }
    val waiting = (shown.host as? HostPairing.Waiting)?.code?.let { "code $it" }
    val pairs = link.pairs.map { pair ->
        val linked = if (pair.id == link.linkedId) ", linked" else ""
        row(pair.name, HomeAction.Menu(SCREEN_PEER_MENU + pair.id), detail = pair.role.word + linked)
    }.ifEmpty { listOf(row("(none yet)")) }
    return listOfNotNull(
        section("Pairing"),
        info("Status", status),
        link.lastEnd?.takeIf { link.linkedName == null }?.let { text("Last: $it") },
        link.problem?.let { error(screenProblemText(it)) },
        HomeItem(
            "Pair this phone as the host",
            HomeAction.Config("screen-pair-host", emptyList(), open = SCREEN_PAIR_HOST_MENU),
            detail = waiting,
        ),
        HomeItem("Pair with a host", HomeAction.Config("screen-pair-find", emptyList(), open = SCREEN_PAIR_JOIN_MENU)),
        section("Paired phones"),
    ) + pairs
}

private fun hostItems(host: HostPairing): List<HomeItem> = when (host) {
    HostPairing.Idle -> listOf(
        text("This phone runs everything; the other phone shows what it sends. Pairing shows a code to type there."),
        row("Start pairing", pairAction("screen-pair-host")),
    )
    HostPairing.Starting -> listOf(text("Starting…"))
    is HostPairing.Waiting -> listOfNotNull(
        text("On the other phone: Settings → Second screen → Pair with a host, pick ${host.name}, then type this code."),
        HomeItem(host.code, HomeAction.None, kind = HomeRow.CODE),
        if (host.wrong > 0) error("Wrong code (${host.wrong} of $MAX_WRONG_CODES): this is a new one.") else null,
        info("This phone", host.name),
        info("Address", host.addresses.joinToString("\n").ifEmpty { "(not on a network)" }),
        text(
            "Not listed there? Type the address instead. Each code takes one try; pairing stops after " +
                "$MAX_WRONG_CODES wrong ones, or in 5 minutes.",
        ),
        row("Cancel", pairAction("screen-pair-cancel", back = true)),
    )
    is HostPairing.Paired -> listOf(
        text("Paired with ${host.peer}, and linked."),
        text("Home's first row, Send to ${host.peer}, sends it apps, terminals or the desktop."),
        row("Done", pairAction("screen-pair-done", back = true)),
    )
    is HostPairing.Stopped -> listOf(error(host.why), row("Try again", pairAction("screen-pair-host")))
}

private fun codeInput(name: String, address: String) = HomeAction.Input(
    "Code from $name",
    "",
    "The six digits $name shows. Each code takes one try: after a wrong one, $name shows a new code.",
    "screen-join:$address $name",
    numeric = true,
    button = "Pair",
)

private fun joinItems(shown: ScreenPairingShown): List<HomeItem> {
    when (val join = shown.join) {
        is JoinPairing.Joining -> return listOf(text("Pairing with ${join.host}…"))
        is JoinPairing.Paired -> return listOf(
            text("Paired with ${join.host}, and linked: it can send its apps, terminals and desktop here."),
            row("Done", pairAction("screen-pair-done", back = true)),
        )
        else -> {}
    }
    val failed = (shown.join as? JoinPairing.Failed)?.let { listOf(error(it.why)) }.orEmpty()
    val found = shown.found.map { host ->
        val address = hostPort(host.address, host.port)
        row(host.name, codeInput(host.name, address), detail = address)
    }
    val looking = when {
        shown.searching -> row("Looking…")
        found.isEmpty() -> row("None found yet.")
        else -> null
    }
    return failed + listOfNotNull(
        text("On the host: Settings → Second screen → Pair this phone as the host. Then pick it here and type the code it shows."),
        section("Pairing on this Wi-Fi"),
    ) + found + listOfNotNull(
        looking,
        if (shown.searching) null else row("Search again", pairAction("screen-pair-search")),
        section("Not listed?"),
        row(
            "Type its address",
            HomeAction.Input(
                "Host's address",
                shown.typed.orEmpty(),
                "The address the host shows under its code, like 192.168.1.20 (with :PORT if it isn't $SCREEN_PORT).",
                "screen-address",
            ),
        ),
        shown.typed?.let { row("At $it", codeInput(it, it)) },
    )
}

private fun peerItems(shown: ScreenPairingShown, id: String): List<HomeItem> {
    val pair = shown.link.pairs.firstOrNull { it.id == id } ?: return listOf(row("(not paired with this phone any more)"))
    val linked = shown.link.linkedId == id
    val connecting = shown.connecting == id
    return listOfNotNull(
        info("Role", if (pair.role == ScreenRole.SCREEN) "The screen: shows what this phone sends" else "The host: sends what shows here"),
        info("Link", if (linked) "Linked" else if (connecting) "Connecting…" else "Not linked"),
        shown.problem?.let(::error),
        when {
            linked -> row("Disconnect", pairAction("screen-pair-disconnect"))
            pair.role == ScreenRole.HOST && !connecting -> row("Connect", pairAction("screen-pair-connect", id))
            pair.role == ScreenRole.SCREEN -> text("${pair.name} connects from its own settings: Second screen, ${shown.link.name}, Connect.")
            else -> null
        },
        HomeItem(
            "Unpair",
            HomeAction.Config(
                "screen-pair-unpair", listOf(id), back = true,
                confirm = "Unpair ${pair.name}? Both phones forget the pair; pairing again takes a new code.",
            ),
        ),
    )
}

private val MYNX_HINT = Regex("\\s*\\((?:run )?mynx [^)]*\\)")

/** The link's refusal [error] (written for `mynx screen`) as settings says it: no terminal commands, a sentence. */
fun screenProblemText(error: String): String {
    val e = error.trim()
    Regex("^couldn't reach (\\S+): (.*)$").find(e)?.let {
        return "Couldn't reach ${it.groupValues[1]}. Is mynx open there, on the same Wi-Fi? (${it.groupValues[2]})"
    }
    Regex("^the link to (\\S+) failed: .*timed out.*$", RegexOption.IGNORE_CASE).find(e)?.let {
        return "${it.groupValues[1]} didn't answer in time."
    }
    Regex("^couldn't find (.+) on this network").find(e)?.let {
        return "Couldn't find ${it.groupValues[1]} on this network. Is mynx open there, on the same Wi-Fi?"
    }
    Regex("^(\\d+) wrong codes").find(e)?.let {
        val n = it.groupValues[1]
        return "${if (n == "3") "Three" else n} wrong codes: pairing stopped. Start it again for a new code."
    }
    Regex("^no phone paired in (\\d+ minutes?)").find(e)?.let { return "No phone paired in ${it.groupValues[1]}: pairing stopped." }
    Regex("^already linked with (.+?) \\(").find(e)?.let { return "Already linked with ${it.groupValues[1]}: disconnect it first." }
    Regex("^(.+) doesn't take screens").find(e)?.let {
        return if (it.groupValues[1] == "this phone") {
            "This phone doesn't take screens: set “Take paired screens” to While paired, below."
        } else {
            "${it.groupValues[1]} doesn't take screens: set “Take paired screens” to While paired on it."
        }
    }
    when {
        e.startsWith("wrong code") -> return "Wrong code. Check the six digits on the host: it shows a new code after each try."
        e.startsWith("no phone is pairing on this network") ->
            return "No phone is pairing on this Wi-Fi. Start pairing on the host first, or type its address."
        e.contains("took over") -> return "Pairing started again somewhere else (mynx screen pair in a terminal)."
    }
    val plain = MYNX_HINT.replace(e, "").replace("run mynx screen pair on it first", "start pairing on it first").trim()
    val sentence = plain.replaceFirstChar { it.uppercase() }
    return if (sentence.endsWith(".") || sentence.endsWith("?")) sentence else "$sentence."
}
