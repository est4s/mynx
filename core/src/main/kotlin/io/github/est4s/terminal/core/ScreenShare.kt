package io.github.est4s.terminal.core

import java.io.File

/** What the host can send to the other phone: for now an app (a web app or a game: `mynx app`). */
sealed interface ScreenTarget {
    val kind: String
    val name: String

    data class App(override val name: String) : ScreenTarget {
        override val kind get() = "app"
    }
}

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
}

/** The screen's app (Android): shows the streams. Main thread, but [video]. */
interface ScreenShowing {
    fun started(stream: ShownStream)

    fun ended(stream: Int)

    /** A part of a stream's video, on the link's thread: straight to the decoder. */
    fun video(message: ScreenMessage.Video)

    /** Whether typing on the host comes here (the screen touched last), to show a mark. */
    fun focus(typing: Boolean)
}

private const val MAX_TITLE = 80

/**
 * Screens sent over the link (work log "Next" 0a, step 3a; roadmap
 * "Extended screen"), on either phone. **On the host**, [send] moves
 * one of its screens to a display of its own ([ScreenCasting.open]) at
 * the other phone's size and announces it ([ScreenMessage.StreamStart]);
 * the encoder runs only while the screen [ScreenMessage.Watch]es it, at
 * `screen-fps` and `screen-bitrate`, its frames paced by a [VideoPacer]
 * each. Touches go to the screen they're on, and a finger down makes it
 * the one typed to ([typingTo], [ScreenMessage.Focus]) until this phone
 * is touched ([hostTouched]). [take] (or the screen's
 * [ScreenMessage.TakeBack]) brings one back; the link ending brings them
 * all back. **On the screen**, it keeps the streams announced
 * ([shown]), the one [watching], and acks what the decoder got.
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
    private var typing: Int? = null
    private val shownStreams = LinkedHashMap<Int, ShownStream>()

    /** On the host: the screens on the other phone. */
    val sent: List<SentScreen> get() = synchronized(lock) { sentScreens.values.toList() }

    /** On the host: the screen typing goes to, null for this phone's own. */
    val typingTo: SentScreen? get() = synchronized(lock) { typing?.let(sentScreens::get) }

    /** On the screen: the streams the host sent. */
    val shown: List<ShownStream> get() = synchronized(lock) { shownStreams.values.toList() }

    /** On the screen: the stream shown now. */
    @Volatile var watching: Int? = null
        private set

    /** On the screen: the host types to it (it was touched last). */
    @Volatile var typedTo: Boolean = false
        private set

    // --- The host ---------------------------------------------------------

    /** Sends [target] to the linked screen; the one sent already if it is. Main thread. */
    fun send(target: ScreenTarget): Result<SentScreen> {
        val other = peer() ?: return failure("no screen is linked (mynx screen connect on the other phone links it)")
        if (other.role != ScreenRole.HOST) {
            return failure("this phone is ${other.name}'s screen: send from ${other.name}")
        }
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

    /**
     * [which] screens: an id, a name ([ScreenTarget.name]) or `all`. On
     * the host it takes them back; on the screen it asks the host to.
     * Main thread. What it found.
     */
    fun take(which: String): List<ScreenRef> {
        val role = peer()?.role
        if (role == ScreenRole.SCREEN) {
            val found = shown.filter { which == "all" || "${it.id}" == which || it.title.equals(which, ignoreCase = true) }
            found.forEach { send(ScreenMessage.TakeBack(it.id)) }
            return found.map { ScreenRef(it.id, it.title) }
        }
        val found = sent.filter { which == "all" || "${it.id}" == which || it.target.name == which }
        found.forEach { end(it, back = true, tell = true) }
        return found.map { ScreenRef(it.id, it.title) }
    }

    /** The host's screen [id] closed here (its app went): it ends there too. Main thread. */
    fun closedHere(id: Int) {
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

    private fun typeHere(tell: Boolean) {
        synchronized(lock) {
            if (typing == null) return
            typing = null
        }
        casting.typingTo(null)
        if (tell) send(ScreenMessage.Focus(ScreenRole.HOST))
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
        val changed = synchronized(lock) {
            (typing != screen.id).also { typing = screen.id }
        }
        if (changed) {
            casting.typingTo(screen)
            send(ScreenMessage.Focus(ScreenRole.SCREEN))
        }
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
            else -> post { handle(m) }
        }
    }

    private fun handle(m: ScreenMessage) {
        when (m) {
            is ScreenMessage.Watch -> watch(m.stream, m.on)
            is ScreenMessage.Touch -> touched(m)
            is ScreenMessage.TakeBack -> synchronized(lock) { sentScreens[m.stream] }?.let { end(it, back = true, tell = true) }
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
            else -> {}
        }
    }

    private fun endShown(id: Int) {
        synchronized(lock) { shownStreams.remove(id) } ?: return
        if (watching == id) watching = null
        showing.ended(id)
    }

    /** The link ended (link's thread): the host's screens come back, the screen forgets the streams. */
    override fun unlinked() = post {
        sent.forEach { end(it, back = true, tell = false) }
        shown.forEach { endShown(it.id) }
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
            },
        ),
        "shown" to array(
            shown.map { obj("id" to "${it.id}", "title" to json(it.title), "watching" to "${it.id == watching}") },
        ),
        "typing" to "${typingTo != null || typedTo}",
    )
}

/** A screen [take] found: its stream's id and title. */
data class ScreenRef(val id: Int, val title: String)
