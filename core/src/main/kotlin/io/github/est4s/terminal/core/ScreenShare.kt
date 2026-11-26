package io.github.est4s.terminal.core

import java.io.File

/** What the host can send to the other phone: an app (a web app or a game: `mynx app`) or a terminal tab. */
sealed interface ScreenTarget {
    val kind: String
    val name: String

    data class App(override val name: String) : ScreenTarget {
        override val kind get() = "app"
    }

    /**
     * A terminal tab: [name] is its place in the strip (`1` is the first),
     * `shell:ID` the tab whose shell has `MYNX_SHELL=ID`, or `new` for a
     * new one started for the other phone.
     */
    data class Terminal(override val name: String) : ScreenTarget {
        override val kind get() = "terminal"
    }
}

/** A terminal tab of the host's that's on the other phone: [id] (of the streams' series), at [columns] x [rows]. */
data class SentTerm(val id: Int, val target: ScreenTarget.Terminal, val title: String, val columns: Int, val rows: Int)

/** A terminal tab the host's app took off its strip to send: its title and size now. */
data class TermOpened(val title: String, val columns: Int, val rows: Int)

/** A screen of the host's that's on the other phone: stream [id], drawn at [width] x [height]. */
data class SentScreen(
    val id: Int,
    val target: ScreenTarget,
    val title: String,
    val width: Int,
    val height: Int,
    val watched: Boolean = false,
)

/** A stream the host sent this phone (the screen). */
data class ShownStream(val id: Int, val title: String, val width: Int, val height: Int)

/** The other phone, while linked: its name, this phone's [role], and its screen once it said. */
data class LinkedPeer(val name: String, val role: ScreenRole, val screen: ScreenMessage.ScreenInfo?)

/**
 * The host's app (Android): the private display a sent screen draws on,
 * its encoder and its views. Called on the main thread, but [keyFrame],
 * which the link's and the encoder's threads call too.
 */
interface ScreenCasting {
    /** The title of [target], or why it can't be sent (no such app). */
    fun describe(target: ScreenTarget): Result<String>

    /** The size to draw and encode a screen for a [width] x [height] screen at: what the encoder takes ([encoderSizes]). */
    fun streamSize(width: Int, height: Int): Pair<Int, Int> = Pair(width, height)

    /** Moves [screen] to a display of its own, at its size and [density]; null, or why not. */
    fun open(screen: SentScreen, density: Int): String?

    /** Closes [screen]'s display; [back]: and shows it on this phone again. */
    fun close(screen: SentScreen, back: Boolean)

    /** Starts ([on]) or stops [screen]'s encoder; the frames come back through [ScreenShare.encoded]. */
    fun encode(screen: SentScreen, on: Boolean, fps: Int, kbps: Int)

    /** Asks [screen]'s encoder for a key frame now. Any thread. */
    fun keyFrame(screen: SentScreen)

    /** A touch on the other phone, in [screen]'s pixels. */
    fun touch(screen: SentScreen, touch: ScreenMessage.Touch)

    /** Typing on this phone goes to [screen] now (null: to this phone's own screens). */
    fun typingTo(screen: SentScreen?)

    /**
     * Takes [target]'s tab off this phone's strip, as terminal [id] (its
     * session runs on, its output going to [ScreenShare.termOutput]):
     * its title and size, or why not.
     */
    fun openTerm(id: Int, target: ScreenTarget.Terminal): Result<TermOpened> =
        Result.failure(Exception("this phone can't send terminals"))

    /** Everything on terminal [id] now, or null if it's gone. */
    fun termScreen(id: Int): TermScreen? = null

    /** Terminal [id] is [columns] x [rows] from now on (its pseudo-terminal too). */
    fun resizeTerm(id: Int, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {}

    /** Typed on the other phone for terminal [id]: to its programs. */
    fun writeTerm(id: Int, data: ByteArray) {}

    /** Terminal [id] stops going there; [back]: its tab comes back to this phone's strip. */
    fun closeTerm(id: Int, back: Boolean) {}

    /** Typing on this phone goes to [term] now (null: no longer to a sent terminal). */
    fun typingToTerm(term: SentTerm?) {}
}

/** The screen's app (Android): shows the streams. Main thread, but [video]. */
interface ScreenShowing {
    fun started(stream: ShownStream)

    fun ended(stream: Int)

    /** A part of a stream's video, on the link's thread: straight to the decoder. */
    fun video(message: ScreenMessage.Video)

    /** Whether typing on the host comes here (the screen touched last), to show a mark. */
    fun focus(typing: Boolean)

    /** The host sent terminal [id], [title], [columns] x [rows]: a tab for it. */
    fun termStarted(id: Int, title: String, columns: Int, rows: Int) {}

    /** Draw terminal [id] afresh at [columns] x [rows]: reset it to that size, then feed it [data]. */
    fun termState(id: Int, columns: Int, rows: Int, data: ByteArray) {}

    /** Feed terminal [id] what its programs wrote. */
    fun termOutput(id: Int, data: ByteArray) {}

    fun termTitle(id: Int, title: String) {}

    /** The host has terminal [id] back: its tab goes. */
    fun termEnded(id: Int) {}
}

private const val MAX_TITLE = 80

/**
 * Screens and terminals sent over the link (work log "Next" 0a; roadmap
 * "Extended screen"), on either phone. **On the host**, [send] moves
 * one of its screens to a display of its own ([ScreenCasting.open]) at
 * the other phone's size and announces it ([ScreenMessage.StreamStart]);
 * the encoder runs only while the screen [ScreenMessage.Watch]es it, at
 * `screen-fps` and `screen-bitrate`, its frames paced by a [VideoPacer]
 * each. [sendTerm] takes a terminal tab off this phone's strip
 * ([ScreenCasting.openTerm]; its session runs on) and sends it as text:
 * its state ([termSnapshot]), then its output ([termOutput]) paced by a
 * [TermPacer]; the screen's size becomes its size, and a new state
 * follows each change. Touches go to the screen they're on, and a finger
 * down (on a terminal, [ScreenMessage.TermTouched]) makes it the one
 * typed to ([typingTo], [typingToTerm], [ScreenMessage.Focus]) until
 * this phone is touched ([hostTouched]). [take] (or the screen's
 * [ScreenMessage.TakeBack]) brings one back; the link ending brings them
 * all back. **On the screen**, it keeps the streams announced
 * ([shown]), the one [watching], and acks what the decoder got; and the
 * terminals ([shownTerms], each a [ShownTerm]), whose states and output
 * go to [ScreenShowing] in order.
 *
 * [send] and [peer] are the link's ([ScreenLink.send],
 * [ScreenLink.peer]); [received] and [unlinked] come from it on its own
 * threads, and go to the main thread through [post], but video and its
 * acks, which don't wait.
 */
class ScreenShare(
    private val send: (ScreenMessage) -> Boolean,
    private val peer: () -> LinkedPeer?,
    private val casting: ScreenCasting,
    private val showing: ScreenShowing,
    private val settingsFile: File,
    private val post: (() -> Unit) -> Unit,
) : ScreenMedia {
    private val lock = Any()
    private var nextId = 1
    private val sentScreens = LinkedHashMap<Int, SentScreen>()
    private val pacers = HashMap<Int, VideoPacer>()
    private val sentTerms = LinkedHashMap<Int, SentTerm>()
    private val termPacers = HashMap<Int, TermPacer>()
    private var typing: Int? = null
    private var typingTerm = false
    private val shownStreams = LinkedHashMap<Int, ShownStream>()
    private val shownTermsById = LinkedHashMap<Int, ShownTerm>()

    /** On the host: the screens on the other phone. */
    val sent: List<SentScreen> get() = synchronized(lock) { sentScreens.values.toList() }

    /** On the host: the terminal tabs on the other phone. */
    val terms: List<SentTerm> get() = synchronized(lock) { sentTerms.values.toList() }

    /** On the host: the screen typing goes to, null for this phone's own (or a terminal's). */
    val typingTo: SentScreen? get() = synchronized(lock) { typing?.let(sentScreens::get) }

    /** On the host: the terminal typing goes to, null if none. */
    val typingToTerm: SentTerm? get() = synchronized(lock) { typing?.let(sentTerms::get) }

    /** On the screen: the streams the host sent. */
    val shown: List<ShownStream> get() = synchronized(lock) { shownStreams.values.toList() }

    /** On the screen: the terminals the host sent. */
    val shownTerms: List<ShownTerm> get() = synchronized(lock) { shownTermsById.values.toList() }

    /** On the screen: the stream shown now. */
    @Volatile var watching: Int? = null
        private set

    /** On the screen: the host types to it (it was touched last). */
    @Volatile var typedTo: Boolean = false
        private set

    // --- The host ---------------------------------------------------------

    // The linked screen's, or why there's none to send to.
    private fun screenPeer(): Result<LinkedPeer> {
        val other = peer() ?: return Result.failure(Exception("no screen is linked (mynx screen connect on the other phone links it)"))
        if (other.role != ScreenRole.HOST) {
            return Result.failure(Exception("this phone is ${other.name}'s screen: send from ${other.name}"))
        }
        return Result.success(other)
    }

    /** Sends [target] to the linked screen; the one sent already if it is. Main thread. */
    fun send(target: ScreenTarget): Result<SentScreen> {
        val other = screenPeer().getOrElse { return Result.failure(it) }
        synchronized(lock) { sentScreens.values.firstOrNull { it.target == target } }?.let { return Result.success(it) }
        val info = other.screen ?: return failure("${other.name} hasn't said its screen's size yet: try again")
        val title = casting.describe(target).getOrElse { return Result.failure(it) }.take(MAX_TITLE)
        val (width, height) = casting.streamSize(info.width, info.height)
        val screen = synchronized(lock) { SentScreen(nextId++, target, title, width, height) }
        casting.open(screen, info.density)?.let { return failure("couldn't show $title there: $it") }
        synchronized(lock) {
            sentScreens[screen.id] = screen
            pacers[screen.id] = VideoPacer()
        }
        send(ScreenMessage.StreamStart(screen.id, title, screen.width, screen.height))
        return Result.success(screen)
    }

    private fun failure(why: String): Result<SentScreen> = Result.failure(Exception(why))

    /** Sends terminal tab [target] to the linked screen, as text. Main thread. */
    fun sendTerm(target: ScreenTarget.Terminal): Result<SentTerm> {
        screenPeer().onFailure { return Result.failure(it) }
        val id = synchronized(lock) { nextId++ }
        val opened = casting.openTerm(id, target).getOrElse { return Result.failure(it) }
        val term = SentTerm(id, target, opened.title.take(MAX_TITLE), opened.columns, opened.rows)
        val pacer = TermPacer()
        synchronized(lock) {
            sentTerms[id] = term
            termPacers[id] = pacer
        }
        send(ScreenMessage.TermStart(id, term.title, term.columns, term.rows))
        sendState(id, pacer.epoch)
        return Result.success(term)
    }

    // Everything on terminal [id] now, starting [epoch].
    private fun sendState(id: Int, epoch: Int) {
        val screen = casting.termScreen(id) ?: return
        synchronized(lock) { sentTerms[id]?.let { sentTerms[id] = it.copy(columns = screen.columns, rows = screen.rows) } }
        send(ScreenMessage.TermState(id, epoch, screen.columns, screen.rows, termSnapshot(screen)))
    }

    private fun resync(id: Int) {
        val pacer = synchronized(lock) { termPacers[id] } ?: return
        sendState(id, pacer.resync())
    }

    /** What terminal [id]'s programs wrote (its session's bytes, as its emulator gets them). Main thread. */
    fun termOutput(id: Int, data: ByteArray) {
        val pacer = synchronized(lock) { termPacers[id] } ?: return
        if (pacer.output(data.size)) send(ScreenMessage.TermOutput(id, pacer.epoch, data))
    }

    /** Terminal [id]'s tab is called [title] now. Main thread. */
    fun termTitle(id: Int, title: String) {
        val cut = title.take(MAX_TITLE)
        synchronized(lock) {
            val term = sentTerms[id] ?: return
            if (term.title == cut) return
            sentTerms[id] = term.copy(title = cut)
        }
        send(ScreenMessage.TermTitle(id, cut))
    }

    /**
     * [which] screens and terminals: an id, a name ([ScreenTarget.name]),
     * a title, or `all`. On the host it takes them back; on the screen it
     * asks the host to. Main thread. What it found.
     */
    fun take(which: String): List<ScreenRef> {
        val role = peer()?.role
        fun named(id: Int, title: String) = which == "all" || "$id" == which || title.equals(which, ignoreCase = true)
        if (role == ScreenRole.SCREEN) {
            val found = shown.filter { named(it.id, it.title) }.map { ScreenRef(it.id, it.title) } +
                shownTerms.filter { named(it.id, it.title) }.map { ScreenRef(it.id, it.title) }
            found.forEach { send(ScreenMessage.TakeBack(it.id)) }
            return found
        }
        val screens = sent.filter { which == "all" || "${it.id}" == which || it.target.name == which }
        screens.forEach { end(it, back = true, tell = true) }
        val terminals = terms.filter { named(it.id, it.title) }
        terminals.forEach { endTerm(it, back = true, tell = true) }
        return screens.map { ScreenRef(it.id, it.title) } + terminals.map { ScreenRef(it.id, it.title) }
    }

    /**
     * The host's screen or terminal [id] closed here (its app went, its
     * shell ended): it ends there too; a terminal comes [back] to this
     * phone's strip if it should stay. Main thread.
     */
    fun closedHere(id: Int, back: Boolean = false) {
        synchronized(lock) { sentTerms[id] }?.let { return endTerm(it, back = back, tell = true) }
        val screen = synchronized(lock) { sentScreens[id] } ?: return
        end(screen, back = false, tell = true)
    }

    private fun end(screen: SentScreen, back: Boolean, tell: Boolean) {
        synchronized(lock) {
            if (sentScreens.remove(screen.id) == null) return
            pacers.remove(screen.id)
        }
        if (screen.watched) casting.encode(screen, false, 0, 0)
        if (synchronized(lock) { typing == screen.id }) typeHere(tell)
        casting.close(screen, back)
        if (tell) send(ScreenMessage.StreamEnd(screen.id))
    }

    private fun endTerm(term: SentTerm, back: Boolean, tell: Boolean) {
        synchronized(lock) {
            if (sentTerms.remove(term.id) == null) return
            termPacers.remove(term.id)
        }
        if (synchronized(lock) { typing == term.id }) typeHere(tell)
        casting.closeTerm(term.id, back)
        if (tell) send(ScreenMessage.TermEnd(term.id))
    }

    // Typing goes to [id], a sent screen or terminal, or (null) this phone; whoever had it is told.
    private fun typeTo(id: Int?): Boolean {
        // Whether it was a terminal's: it may be gone from the lists already (ending).
        val (was, wasTerm) = synchronized(lock) {
            if (typing == id) return false
            val old = typing to typingTerm
            typing = id
            typingTerm = id != null && id in sentTerms
            old
        }
        if (was != null || id == null) {
            if (wasTerm) casting.typingToTerm(null) else casting.typingTo(null)
        }
        val term = id?.let { synchronized(lock) { sentTerms[it] } }
        val screen = id?.let { synchronized(lock) { sentScreens[it] } }
        if (term != null) casting.typingToTerm(term)
        if (screen != null) casting.typingTo(screen)
        return true
    }

    private fun typeHere(tell: Boolean) {
        if (typeTo(null) && tell) send(ScreenMessage.Focus(ScreenRole.HOST))
    }

    /** This phone's own screen was touched: typing goes to it again. Main thread. */
    fun hostTouched() = typeHere(tell = true)

    /** An encoded frame of [id] (encoder thread): sent if the pacer says so. */
    fun encoded(id: Int, flags: Int, pts: Long, data: ByteArray) {
        val (pacer, screen) = synchronized(lock) { (pacers[id] ?: return) to (sentScreens[id] ?: return) }
        val step = pacer.frame(flags, pts)
        if (step.send) send(ScreenMessage.Video(id, flags, pts, data))
        if (step.askKey) casting.keyFrame(screen)
    }

    private fun hosted(id: Int): Pair<SentScreen, VideoPacer>? = synchronized(lock) {
        val screen = sentScreens[id] ?: return null
        screen to (pacers[id] ?: return null)
    }

    private fun watch(id: Int, on: Boolean) {
        val settings = loadSettings(settingsFile).settings
        if (on) sent.filter { it.watched && it.id != id }.forEach { watch(it.id, false) }
        val (screen, pacer) = hosted(id) ?: return
        if (screen.watched == on) {
            if (on && pacer.keyWanted()) casting.keyFrame(screen)
            return
        }
        val now = screen.copy(watched = on)
        synchronized(lock) { sentScreens[id] = now }
        pacer.watch(on)
        casting.encode(now, on, settings.screenFps, settings.screenBitrate * 1000)
    }

    private fun touched(touch: ScreenMessage.Touch) {
        val screen = synchronized(lock) { sentScreens[touch.stream] } ?: return
        casting.touch(screen, touch)
        if (touch.action != ACTION_DOWN) return
        if (typeTo(screen.id)) send(ScreenMessage.Focus(ScreenRole.SCREEN))
    }

    private fun touchedTerm(id: Int) {
        if (synchronized(lock) { id !in sentTerms }) return
        if (typeTo(id)) send(ScreenMessage.Focus(ScreenRole.SCREEN))
    }

    private fun termSized(m: ScreenMessage.TermSize) {
        if (synchronized(lock) { m.term !in sentTerms }) return
        if (m.columns < 1 || m.rows < 1) return
        casting.resizeTerm(m.term, m.columns, m.rows, m.cellWidth, m.cellHeight)
        resync(m.term)
    }

    // --- The screen -------------------------------------------------------

    /** Shows stream [id] here (and stops the one shown); false if there's none. Main thread. */
    fun watch(id: Int): Boolean {
        if (synchronized(lock) { id !in shownStreams }) return false
        val old = watching
        if (old == id) return true
        if (old != null) send(ScreenMessage.Watch(old, false))
        watching = id
        send(ScreenMessage.Watch(id, true))
        return true
    }

    /** Stops showing the stream shown. Main thread. */
    fun stopWatching() {
        val old = watching ?: return
        watching = null
        send(ScreenMessage.Watch(old, false))
    }

    /** The decoder got [stream]'s part [pts]. Any thread. */
    fun decoded(stream: Int, pts: Long) {
        send(ScreenMessage.VideoAck(stream, pts))
    }

    /** The decoder starts over (a new surface): it needs a key frame. Any thread. */
    fun askKeyFrame(stream: Int) {
        send(ScreenMessage.KeyFrame(stream))
    }

    /** Sends [touch] (a [TouchTrack]'s) to the host. Any thread. */
    fun touch(touch: ScreenMessage.Touch) {
        send(touch)
    }

    /** This phone's terminal view fits terminal [id] in [columns] x [rows]: asks the host for that size. Main thread. */
    fun fitTerm(id: Int, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val term = synchronized(lock) { shownTermsById[id] } ?: return
        term.fit(columns, rows, cellWidth, cellHeight)?.let(send)
    }

    /** Typed here for terminal [id] (keys, the key bar, mouse, paste): to the host's programs. Any thread. */
    fun termInput(id: Int, data: ByteArray) {
        if (data.isNotEmpty()) send(ScreenMessage.TermInput(id, data))
    }

    /** Terminal [id] was touched here: the host types to it. Any thread. */
    fun termTouched(id: Int) {
        send(ScreenMessage.TermTouched(id))
    }

    // --- From the link ----------------------------------------------------

    /** A message for screens, on the link's thread. */
    override fun received(message: ScreenMessage) {
        val m = message
        when (m) {
            is ScreenMessage.Video -> showing.video(m)
            is ScreenMessage.VideoAck -> {
                val (screen, pacer) = hosted(m.stream) ?: return
                if (pacer.acked(m.pts).askKey) casting.keyFrame(screen)
            }
            is ScreenMessage.KeyFrame -> {
                val (screen, pacer) = hosted(m.stream) ?: return
                if (pacer.keyWanted()) casting.keyFrame(screen)
            }
            is ScreenMessage.TermAck -> {
                val pacer = synchronized(lock) { termPacers[m.term] } ?: return
                if (pacer.acked(m.epoch, m.bytes)) post { resync(m.term) }
            }
            else -> post { handle(m) }
        }
    }

    private fun handle(m: ScreenMessage) {
        when (m) {
            is ScreenMessage.Watch -> watch(m.stream, m.on)
            is ScreenMessage.Touch -> touched(m)
            is ScreenMessage.TakeBack -> {
                synchronized(lock) { sentScreens[m.stream] }?.let { end(it, back = true, tell = true) }
                synchronized(lock) { sentTerms[m.stream] }?.let { endTerm(it, back = true, tell = true) }
            }
            is ScreenMessage.TermSize -> termSized(m)
            is ScreenMessage.TermInput -> if (synchronized(lock) { m.term in sentTerms }) casting.writeTerm(m.term, m.data)
            is ScreenMessage.TermTouched -> touchedTerm(m.term)
            is ScreenMessage.StreamStart -> {
                val stream = ShownStream(m.stream, m.title, m.width, m.height)
                synchronized(lock) { shownStreams[m.stream] = stream }
                showing.started(stream)
            }
            is ScreenMessage.StreamEnd -> endShown(m.stream)
            is ScreenMessage.Focus -> {
                typedTo = m.to == ScreenRole.SCREEN
                showing.focus(typedTo)
            }
            is ScreenMessage.TermStart -> {
                synchronized(lock) { shownTermsById[m.term] = ShownTerm(m.term, m.title, m.columns, m.rows) }
                showing.termStarted(m.term, m.title, m.columns, m.rows)
            }
            is ScreenMessage.TermState -> {
                val term = synchronized(lock) { shownTermsById[m.term] } ?: return
                val step = term.state(m.epoch, m.columns, m.rows)
                showing.termState(m.term, m.columns, m.rows, m.data)
                step.ask?.let(send)
            }
            is ScreenMessage.TermOutput -> {
                val term = synchronized(lock) { shownTermsById[m.term] } ?: return
                val step = term.output(m.epoch, m.data.size)
                if (step.apply) showing.termOutput(m.term, m.data)
                step.ack?.let(send)
            }
            is ScreenMessage.TermTitle -> {
                val term = synchronized(lock) { shownTermsById[m.term] } ?: return
                term.title = m.title
                showing.termTitle(m.term, m.title)
            }
            is ScreenMessage.TermEnd -> endShownTerm(m.term)
            else -> {}
        }
    }

    private fun endShown(id: Int) {
        synchronized(lock) { shownStreams.remove(id) } ?: return
        if (watching == id) watching = null
        showing.ended(id)
    }

    private fun endShownTerm(id: Int) {
        synchronized(lock) { shownTermsById.remove(id) } ?: return
        showing.termEnded(id)
    }

    /** The link ended (link's thread): the host's screens and terminals come back, the screen forgets its own. */
    override fun unlinked() = post {
        sent.forEach { end(it, back = true, tell = false) }
        terms.forEach { endTerm(it, back = true, tell = false) }
        shown.forEach { endShown(it.id) }
        shownTerms.forEach { endShownTerm(it.id) }
        typedTo = false
    }

    /** The screens for `mynx screen status`, as JSON fields. */
    fun status(): Array<Pair<String, String>> = arrayOf(
        "sent" to array(
            sent.map {
                obj(
                    "id" to "${it.id}", "kind" to json(it.target.kind), "name" to json(it.target.name),
                    "title" to json(it.title), "width" to "${it.width}", "height" to "${it.height}", "watched" to "${it.watched}",
                )
            } + terms.map {
                obj(
                    "id" to "${it.id}", "kind" to json(it.target.kind), "name" to json(it.target.name),
                    "title" to json(it.title), "columns" to "${it.columns}", "rows" to "${it.rows}",
                )
            },
        ),
        "shown" to array(
            shown.map { obj("id" to "${it.id}", "kind" to json("app"), "title" to json(it.title), "watching" to "${it.id == watching}") } +
                shownTerms.map { obj("id" to "${it.id}", "kind" to json("terminal"), "title" to json(it.title)) },
        ),
        "typing" to "${typing != null || typedTo}",
    )
}

/** A screen [take] found: its stream's id and title. */
data class ScreenRef(val id: Int, val title: String)
