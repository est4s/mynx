package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val NORD = ScreenMessage.ScreenInfo(1080, 2400, 440, 0)

/** The host's app as the tests see it: what it was asked to do, in order. */
internal class FakeCasting : ScreenCasting {
    val did = mutableListOf<String>()
    var refuse: String? = null
    val titles = mutableMapOf("pixel-hero" to "Pixel Hero", "notes" to "Notes")

    override fun describe(target: ScreenTarget): Result<String> {
        if (target == ScreenTarget.Desktop) return Result.success("Linux desktop")
        val name = (target as ScreenTarget.App).name
        return titles[name]?.let { Result.success(it) } ?: Result.failure(Exception("no app named $name"))
    }
    var encoderTakes: Pair<Int, Int>? = null
    override fun streamSize(width: Int, height: Int): Pair<Int, Int> = encoderTakes ?: Pair(width, height)
    override fun open(screen: SentScreen, density: Int): String? {
        did += "open ${screen.id} ${screen.title} ${screen.width}x${screen.height} @$density"
        return refuse
    }
    override fun close(screen: SentScreen, back: Boolean) { did += "close ${screen.id} back=$back" }
    override fun encode(screen: SentScreen, on: Boolean, fps: Int, kbps: Int) {
        did += if (on) "encode ${screen.id} $fps fps $kbps kbit/s" else "stop ${screen.id}"
    }
    override fun keyFrame(screen: SentScreen) { did += "key ${screen.id}" }
    override fun touch(screen: SentScreen, touch: ScreenMessage.Touch) { did += "touch ${screen.id} ${touch.action}" }
    override fun typingTo(screen: SentScreen?) { did += "typing ${screen?.id}" }

    // Terminals: the tabs this phone has, by what `mynx screen send tab` names.
    val tabs = mutableMapOf("1" to "bash", "2" to "htop", "new" to "Tab 3")
    val screens = mutableMapOf<Int, TermScreen>()
    var termSize = Pair(56, 40)
    override fun openTerm(id: Int, target: ScreenTarget.Terminal): Result<TermOpened> {
        val title = tabs.remove(target.name) ?: return Result.failure(Exception("no tab ${target.name}"))
        did += "open term $id $title"
        screens[id] = TermScreen(termSize.first, termSize.second, listOf(TermLine(listOf(TermCell("$", 1, TERM_NORMAL)))))
        return Result.success(TermOpened(title, termSize.first, termSize.second))
    }
    override fun termScreen(id: Int): TermScreen? = screens[id]
    override fun resizeTerm(id: Int, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        did += "resize $id ${columns}x$rows ${cellWidth}x$cellHeight"
        screens[id] = screens.getValue(id).copy(columns = columns, rows = rows)
    }
    override fun writeTerm(id: Int, data: ByteArray) { did += "write $id ${String(data)}" }
    override fun closeTerm(id: Int, back: Boolean) {
        did += "close term $id back=$back"
        screens.remove(id)
    }
    override fun typingToTerm(term: SentTerm?) { did += "typing term ${term?.id}" }
}

internal class FakeShowing : ScreenShowing {
    val did = mutableListOf<String>()
    override fun started(stream: ShownStream) { did += "started ${stream.id} ${stream.title} ${stream.width}x${stream.height}" }
    override fun ended(stream: Int) { did += "ended $stream" }
    override fun video(message: ScreenMessage.Video) { did += "video ${message.stream} ${message.pts}" }
    override fun focus(typing: Boolean) { did += "focus $typing" }
    override fun pointer(stream: Int, x: Float, y: Float, shown: Boolean) { did += "pointer $stream $x $y $shown" }
    override fun termStarted(id: Int, title: String, columns: Int, rows: Int) { did += "term $id $title ${columns}x$rows" }
    override fun termState(id: Int, columns: Int, rows: Int, data: ByteArray) { did += "state $id ${columns}x$rows" }
    override fun termOutput(id: Int, data: ByteArray) { did += "output $id ${String(data)}" }
    override fun termTitle(id: Int, title: String) { did += "title $id $title" }
    override fun termEnded(id: Int) { did += "term ended $id" }
}

class ScreenShareTest {
    private val root = Files.createTempDirectory("share").toFile()
    private val settings = File(root, "settings.conf").also { it.writeText("") }
    private val sent = mutableListOf<ScreenMessage>()
    private var peer: LinkedPeer? = LinkedPeer("Nord 2", ScreenRole.HOST, NORD)
    private val casting = FakeCasting()
    private val showing = FakeShowing()
    private val share = ScreenShare(
        send = { m -> if (peer == null) false else { sent += m; true } },
        peer = { peer },
        casting = casting,
        showing = showing,
        settingsFile = settings,
        post = { it() },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun sendHero(): SentScreen = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()

    @Test
    fun `sending a screen opens it at the other phone's size and tells it`() {
        val s = sendHero()
        assertEquals("Pixel Hero", s.title)
        assertEquals(listOf("open ${s.id} Pixel Hero 1080x2400 @440"), casting.did)
        assertEquals(listOf<ScreenMessage>(ScreenMessage.StreamStart(s.id, "Pixel Hero", 1080, 2400)), sent)
        assertEquals(listOf(s), share.sent)
    }

    @Test
    fun `the stream is drawn at the size the encoder takes`() {
        casting.encoderTakes = Pair(1072, 2400)
        val s = sendHero()
        assertEquals(Pair(1072, 2400), Pair(s.width, s.height))
        assertEquals(ScreenMessage.StreamStart(s.id, "Pixel Hero", 1072, 2400), sent.single())
    }

    @Test
    fun `sending needs a linked screen, an app that is there, and a screen that opens`() {
        assertEquals("no app named nope", share.send(ScreenTarget.App("nope")).exceptionOrNull()?.message)
        casting.refuse = "no display"
        assertEquals("couldn't show Pixel Hero there: no display", share.send(ScreenTarget.App("pixel-hero")).exceptionOrNull()?.message)
        assertTrue(share.sent.isEmpty())
        peer = null
        assertTrue(share.send(ScreenTarget.App("pixel-hero")).exceptionOrNull()?.message!!.startsWith("no screen is linked"))
        peer = LinkedPeer("Pixel 10", ScreenRole.SCREEN, NORD)
        assertTrue(share.send(ScreenTarget.App("pixel-hero")).exceptionOrNull()?.message!!.contains("this phone is Pixel 10's screen"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `sending what's sent already gives the same one`() {
        val s = sendHero()
        assertEquals(s, sendHero())
        assertEquals(1, share.sent.size)
        assertEquals(1, sent.size)
    }

    @Test
    fun `the screen watching starts the encoder with the settings, and stopping stops it`() {
        settings.writeText("screen-fps = 30\nscreen-bitrate = 4\n")
        val s = sendHero()
        casting.did.clear()
        share.received(ScreenMessage.Watch(s.id, true))
        assertEquals(listOf("encode ${s.id} 30 fps 4000 kbit/s"), casting.did)
        assertTrue(share.sent.single().watched)
        share.received(ScreenMessage.Watch(s.id, false))
        assertEquals("stop ${s.id}", casting.did.last())
        assertFalse(share.sent.single().watched)
    }

    @Test
    fun `the defaults are 60 frames a second at 8 Mbit-s`() {
        val s = sendHero()
        share.received(ScreenMessage.Watch(s.id, true))
        assertEquals("encode ${s.id} 60 fps 8000 kbit/s", casting.did.last())
    }

    @Test
    fun `watching another stops the first`() {
        val hero = sendHero()
        val notes = share.send(ScreenTarget.App("notes")).getOrThrow()
        share.received(ScreenMessage.Watch(hero.id, true))
        casting.did.clear()
        share.received(ScreenMessage.Watch(notes.id, true))
        assertEquals(listOf("stop ${hero.id}", "encode ${notes.id} 60 fps 8000 kbit/s"), casting.did)
    }

    @Test
    fun `frames go out paced, and an ack that drains the pipe asks for a key frame`() {
        val s = sendHero()
        share.received(ScreenMessage.Watch(s.id, true))
        sent.clear()
        // Before watching's key frame, frames wait.
        share.encoded(s.id, 0, 1, byteArrayOf(1))
        assertTrue(sent.isEmpty())
        share.encoded(s.id, VIDEO_CONFIG, 0, byteArrayOf(9))
        share.encoded(s.id, VIDEO_KEY, 2, byteArrayOf(2))
        for (pts in 3L..7L) share.encoded(s.id, 0, pts, byteArrayOf(pts.toByte()))
        // The config, then six frames: the pipe is full.
        assertEquals(listOf(0L, 2L, 3L, 4L, 5L, 6L, 7L), sent.map { (it as ScreenMessage.Video).pts })
        share.encoded(s.id, 0, 8, byteArrayOf(8))
        assertEquals(7, sent.size)
        casting.did.clear()
        share.received(ScreenMessage.VideoAck(s.id, 7))
        assertEquals(listOf("key ${s.id}"), casting.did)
    }

    @Test
    fun `the screen asking for a key frame gets one`() {
        val s = sendHero()
        share.received(ScreenMessage.Watch(s.id, true))
        casting.did.clear()
        share.received(ScreenMessage.KeyFrame(s.id))
        assertEquals(listOf("key ${s.id}"), casting.did)
    }

    @Test
    fun `touches go to the screen they're on, and a finger down makes it the one typed to`() {
        val s = sendHero()
        casting.did.clear()
        sent.clear()
        share.received(ScreenMessage.Touch(s.id, ACTION_DOWN, 0, listOf(TouchPoint(0, 1f, 2f))))
        share.received(ScreenMessage.Touch(s.id, ACTION_UP, 0, listOf(TouchPoint(0, 1f, 2f))))
        assertEquals(listOf("touch ${s.id} 0", "typing ${s.id}", "touch ${s.id} 1"), casting.did)
        assertEquals(s, share.typingTo)
        assertEquals(listOf<ScreenMessage>(ScreenMessage.Focus(ScreenRole.SCREEN)), sent)
        // Touching this phone takes typing back.
        share.hostTouched()
        assertNull(share.typingTo)
        assertEquals("typing null", casting.did.last())
        assertEquals(ScreenMessage.Focus(ScreenRole.HOST), sent.last())
        share.hostTouched()
        assertEquals(2, sent.size)
        // A touch for a stream that's gone does nothing.
        share.received(ScreenMessage.Touch(99, ACTION_DOWN, 0, listOf(TouchPoint(0, 1f, 2f))))
        assertEquals("typing null", casting.did.last())
    }

    @Test
    fun `taking back closes it here, back on this phone, and ends the stream there`() {
        val s = sendHero()
        share.received(ScreenMessage.Watch(s.id, true))
        share.received(ScreenMessage.Touch(s.id, ACTION_DOWN, 0, listOf(TouchPoint(0, 1f, 2f))))
        casting.did.clear()
        sent.clear()
        assertEquals(listOf(s.id), share.take("${s.id}").map { it.id })
        assertEquals(listOf("stop ${s.id}", "typing null", "close ${s.id} back=true"), casting.did)
        assertEquals(listOf(ScreenMessage.Focus(ScreenRole.HOST), ScreenMessage.StreamEnd(s.id)), sent)
        assertTrue(share.sent.isEmpty())
    }

    @Test
    fun `take finds a screen by its id, its app's name or all`() {
        val hero = sendHero()
        val notes = share.send(ScreenTarget.App("notes")).getOrThrow()
        assertEquals(listOf(ScreenRef(notes.id, "Notes")), share.take("notes"))
        assertTrue(share.take("notes").isEmpty())
        val again = share.send(ScreenTarget.App("notes")).getOrThrow()
        assertTrue(again.id != notes.id)
        assertEquals(listOf(hero.id, again.id), share.take("all").map { it.id })
    }

    @Test
    fun `the screen asking to give one back takes it back`() {
        val s = sendHero()
        share.received(ScreenMessage.TakeBack(s.id))
        assertTrue(share.sent.isEmpty())
        assertEquals("close ${s.id} back=true", casting.did.last())
    }

    @Test
    fun `the link ending brings every screen back`() {
        val s = sendHero()
        share.received(ScreenMessage.Watch(s.id, true))
        casting.did.clear()
        peer = null
        share.unlinked()
        assertEquals(listOf("stop ${s.id}", "close ${s.id} back=true"), casting.did)
        assertTrue(share.sent.isEmpty())
    }

    @Test
    fun `a screen closed here (its app gone) ends its stream`() {
        val s = sendHero()
        sent.clear()
        share.closedHere(s.id)
        assertEquals(listOf<ScreenMessage>(ScreenMessage.StreamEnd(s.id)), sent)
        assertEquals("close ${s.id} back=false", casting.did.last())
        assertTrue(share.sent.isEmpty())
    }

    // --- The screen's side --------------------------------------------------

    private fun asScreen() {
        peer = LinkedPeer("Pixel 10", ScreenRole.SCREEN, ScreenMessage.ScreenInfo(1080, 2424, 420, 0))
    }

    @Test
    fun `the screen hears of a stream, watches it, and stops`() {
        asScreen()
        share.received(ScreenMessage.StreamStart(3, "Pixel Hero", 1080, 2400))
        assertEquals(listOf("started 3 Pixel Hero 1080x2400"), showing.did)
        assertEquals(listOf(ShownStream(3, "Pixel Hero", 1080, 2400)), share.shown)
        assertTrue(share.watch(3))
        assertEquals(3, share.watching)
        assertEquals(listOf<ScreenMessage>(ScreenMessage.Watch(3, true)), sent)
        share.stopWatching()
        assertNull(share.watching)
        assertEquals(ScreenMessage.Watch(3, false), sent.last())
        assertFalse(share.watch(9))
    }

    @Test
    fun `video goes straight to the screen's decoder, which acks it`() {
        asScreen()
        share.received(ScreenMessage.StreamStart(3, "Pixel Hero", 1080, 2400))
        share.watch(3)
        share.received(ScreenMessage.Video(3, VIDEO_KEY, 77, byteArrayOf(1)))
        assertEquals("video 3 77", showing.did.last())
        share.decoded(3, 77)
        assertEquals(ScreenMessage.VideoAck(3, 77), sent.last())
        share.askKeyFrame(3)
        assertEquals(ScreenMessage.KeyFrame(3), sent.last())
    }

    @Test
    fun `the stream ending stops showing it, and focus marks typing`() {
        asScreen()
        share.received(ScreenMessage.StreamStart(3, "Pixel Hero", 1080, 2400))
        share.watch(3)
        share.received(ScreenMessage.Focus(ScreenRole.SCREEN))
        assertEquals("focus true", showing.did.last())
        assertTrue(share.typedTo)
        share.received(ScreenMessage.StreamEnd(3))
        assertEquals("ended 3", showing.did.last())
        assertNull(share.watching)
        assertTrue(share.shown.isEmpty())
    }

    @Test
    fun `take on the screen asks the host`() {
        asScreen()
        share.received(ScreenMessage.StreamStart(3, "Pixel Hero", 1080, 2400))
        assertEquals(listOf(3), share.take("3").map { it.id })
        assertEquals(ScreenMessage.TakeBack(3), sent.last())
        share.received(ScreenMessage.StreamStart(4, "Notes", 1080, 2400))
        // Asked, not gone: the host's StreamEnd takes it off.
        sent.clear()
        share.take("all")
        assertEquals(listOf<ScreenMessage>(ScreenMessage.TakeBack(3), ScreenMessage.TakeBack(4)), sent)
    }

    @Test
    fun `the screen's link ending forgets the streams`() {
        asScreen()
        share.received(ScreenMessage.StreamStart(3, "Pixel Hero", 1080, 2400))
        share.watch(3)
        share.unlinked()
        assertEquals("ended 3", showing.did.last())
        assertTrue(share.shown.isEmpty())
        assertNull(share.watching)
    }

    @Test
    fun `the status lists what's sent and what's shown`() {
        val s = sendHero()
        share.received(ScreenMessage.Watch(s.id, true))
        val status = parseJson(ok(*share.status())) as Map<*, *>
        val one = (status["sent"] as List<*>).single() as Map<*, *>
        assertEquals("Pixel Hero", one["title"])
        assertEquals("app", one["kind"])
        assertEquals("pixel-hero", one["name"])
        assertEquals(true, one["watched"])
        assertEquals(emptyList<Any>(), status["shown"])
        assertEquals(false, status["typing"])
    }

    @Test
    fun `a long title is cut to fit the message`() {
        casting.titles["pixel-hero"] = "x".repeat(500)
        val s = sendHero()
        assertTrue(s.title.length <= 80)
        assertIs<ScreenMessage.StreamStart>(sent.single())
    }

    @Test
    fun `the desktop is sent like a screen, at the other phone's size`() {
        val s = share.send(ScreenTarget.Desktop).getOrThrow()
        assertEquals(ScreenTarget.Desktop, s.target)
        assertEquals("desktop", s.target.kind)
        assertEquals(listOf("open ${s.id} Linux desktop 1080x2400 @440"), casting.did)
        assertEquals(ScreenMessage.StreamStart(s.id, "Linux desktop", 1080, 2400), sent.single())
    }

    @Test
    fun `the desktop's pointer goes to the screen while it watches`() {
        val s = share.send(ScreenTarget.Desktop).getOrThrow()
        sent.clear()
        share.pointer(s.id, 10f, 20f, true)
        assertTrue(sent.isEmpty())
        share.received(ScreenMessage.Watch(s.id, true))
        share.pointer(s.id, 10f, 20f, true)
        share.pointer(s.id, 10f, 20f, true)
        share.pointer(s.id, 0f, 0f, false)
        share.pointer(99, 1f, 1f, true)
        // The same place twice is told once.
        assertEquals(listOf<ScreenMessage>(ScreenMessage.Pointer(s.id, 10f, 20f, true), ScreenMessage.Pointer(s.id, 0f, 0f, false)), sent)
    }

    @Test
    fun `the screen shows the host's pointer on the stream`() {
        share.received(ScreenMessage.StreamStart(3, "Linux desktop", 1080, 2400))
        share.received(ScreenMessage.Pointer(3, 5f, 6f, true))
        assertEquals("pointer 3 5.0 6.0 true", showing.did.last())
    }
}

class ScreenShareTermsTest {
    private val root = Files.createTempDirectory("share").toFile()
    private val settings = File(root, "settings.conf").also { it.writeText("") }
    private val sent = mutableListOf<ScreenMessage>()
    private var peer: LinkedPeer? = LinkedPeer("Nord 2", ScreenRole.HOST, NORD)
    private val casting = FakeCasting()
    private val showing = FakeShowing()
    private val share = ScreenShare(
        send = { m -> if (peer == null) false else { sent += m; true } },
        peer = { peer },
        casting = casting,
        showing = showing,
        settingsFile = settings,
        post = { it() },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun sendTab(which: String = "1"): SentTerm = share.sendTerm(ScreenTarget.Terminal(which)).getOrThrow()

    private fun states() = sent.filterIsInstance<ScreenMessage.TermState>()

    @Test
    fun `sending a tab takes it off this phone and starts it there with everything on it`() {
        val t = sendTab()
        assertEquals("bash", t.title)
        assertEquals(listOf("open term ${t.id} bash"), casting.did)
        assertEquals(ScreenMessage.TermStart(t.id, "bash", 56, 40), sent[0])
        val state = sent[1] as ScreenMessage.TermState
        assertEquals(Triple(t.id, 56, 40), Triple(state.term, state.columns, state.rows))
        assertContentEquals(termSnapshot(casting.screens.getValue(t.id)), state.data)
        assertEquals(listOf(t), share.terms)
    }

    @Test
    fun `sending needs a linked screen and a tab that is there`() {
        assertEquals("no tab 9", share.sendTerm(ScreenTarget.Terminal("9")).exceptionOrNull()?.message)
        peer = LinkedPeer("Pixel 10", ScreenRole.SCREEN, NORD)
        assertTrue(share.sendTerm(ScreenTarget.Terminal("1")).exceptionOrNull()!!.message!!.contains("this phone is Pixel 10's screen"))
        peer = null
        assertTrue(share.sendTerm(ScreenTarget.Terminal("1")).exceptionOrNull()!!.message!!.startsWith("no screen is linked"))
        assertTrue(sent.isEmpty())
        assertTrue(share.terms.isEmpty())
    }

    @Test
    fun `apps and terminals have ids of one series`() {
        val app = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()
        val t = sendTab()
        assertTrue(t.id != app.id)
    }

    @Test
    fun `its programs' output goes there in the state's epoch`() {
        val t = sendTab()
        val epoch = states().single().epoch
        sent.clear()
        share.termOutput(t.id, "hi".toByteArray())
        val out = sent.single() as ScreenMessage.TermOutput
        assertEquals(Pair(t.id, epoch), Pair(out.term, out.epoch))
        assertEquals("hi", String(out.data))
        // Output of a terminal that isn't sent goes nowhere.
        share.termOutput(99, "x".toByteArray())
        assertEquals(1, sent.size)
    }

    @Test
    fun `the screen's size becomes the terminal's, and a new state follows at it`() {
        val t = sendTab()
        sent.clear()
        share.received(ScreenMessage.TermSize(t.id, 48, 30, 22, 51))
        assertEquals("resize ${t.id} 48x30 22x51", casting.did.last())
        val state = states().single()
        assertEquals(Pair(48, 30), Pair(state.columns, state.rows))
        assertTrue(state.epoch > 1)
        assertEquals(Pair(48, 30), share.terms.single().let { Pair(it.columns, it.rows) })
    }

    @Test
    fun `a screen that falls behind gets the whole state again once it has caught up`() {
        val t = sendTab()
        val epoch = states().single().epoch
        sent.clear()
        val big = ByteArray(TERM_UNACKED_MAX)
        share.termOutput(t.id, big)
        share.termOutput(t.id, "lost".toByteArray())
        share.termOutput(t.id, "lost too".toByteArray())
        assertEquals(1, sent.size)
        share.received(ScreenMessage.TermAck(t.id, epoch, big.size.toLong()))
        val again = sent.last() as ScreenMessage.TermState
        assertTrue(again.epoch > epoch)
        share.termOutput(t.id, "on".toByteArray())
        assertEquals(again.epoch, (sent.last() as ScreenMessage.TermOutput).epoch)
    }

    @Test
    fun `what's typed there goes to the terminal's programs`() {
        val t = sendTab()
        share.received(ScreenMessage.TermInput(t.id, "ls\r".toByteArray()))
        assertEquals("write ${t.id} ls\r", casting.did.last())
        share.received(ScreenMessage.TermInput(99, "x".toByteArray()))
        assertEquals("write ${t.id} ls\r", casting.did.last())
    }

    @Test
    fun `touching it there makes this phone's typing go to it, touching here takes it back`() {
        val app = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()
        val t = sendTab()
        share.received(ScreenMessage.Touch(app.id, ACTION_DOWN, 0, listOf(TouchPoint(0, 1f, 2f))))
        casting.did.clear()
        sent.clear()
        share.received(ScreenMessage.TermTouched(t.id))
        assertEquals(listOf("typing null", "typing term ${t.id}"), casting.did)
        assertEquals(t, share.typingToTerm)
        assertNull(share.typingTo)
        // Typing went there already (to the app): the screen keeps its mark, and is told again.
        assertEquals(listOf<ScreenMessage>(ScreenMessage.Focus(ScreenRole.SCREEN)), sent)
        share.received(ScreenMessage.TermTouched(t.id))
        assertEquals(1, sent.size)
        share.hostTouched()
        assertNull(share.typingToTerm)
        assertEquals("typing term null", casting.did.last())
        assertEquals(ScreenMessage.Focus(ScreenRole.HOST), sent.last())
    }

    @Test
    fun `its title follows the tab's`() {
        val t = sendTab()
        share.termTitle(t.id, "vim notes.md")
        assertEquals(ScreenMessage.TermTitle(t.id, "vim notes.md"), sent.last())
        assertEquals("vim notes.md", share.terms.single().title)
        sent.clear()
        share.termTitle(t.id, "vim notes.md")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `taking it back puts the tab back here and ends it there`() {
        val t = sendTab()
        share.received(ScreenMessage.TermTouched(t.id))
        casting.did.clear()
        sent.clear()
        assertEquals(listOf(ScreenRef(t.id, "bash")), share.take("bash"))
        assertEquals(listOf("typing term null", "close term ${t.id} back=true"), casting.did)
        assertEquals(listOf(ScreenMessage.Focus(ScreenRole.HOST), ScreenMessage.TermEnd(t.id)), sent)
        assertTrue(share.terms.isEmpty())
    }

    @Test
    fun `take finds terminals by id or title, and all takes apps and terminals`() {
        val app = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()
        val t = sendTab("1")
        val u = sendTab("2")
        assertEquals(listOf(u.id), share.take("${u.id}").map { it.id })
        assertEquals(listOf(app.id, t.id), share.take("all").map { it.id })
    }

    @Test
    fun `the screen giving it back, the shell ending and the link ending`() {
        val t = sendTab("1")
        share.received(ScreenMessage.TakeBack(t.id))
        assertEquals("close term ${t.id} back=true", casting.did.last())
        val u = sendTab("2")
        sent.clear()
        share.closedHere(u.id)
        assertEquals("close term ${u.id} back=false", casting.did.last())
        assertEquals(listOf<ScreenMessage>(ScreenMessage.TermEnd(u.id)), sent)
        val v = sendTab("new")
        peer = null
        share.unlinked()
        assertEquals("close term ${v.id} back=true", casting.did.last())
        assertTrue(share.terms.isEmpty())
    }

    @Test
    fun `the status lists terminals sent`() {
        val t = sendTab()
        val status = parseJson(ok(*share.status())) as Map<*, *>
        val one = (status["sent"] as List<*>).single() as Map<*, *>
        assertEquals("terminal", one["kind"])
        assertEquals("bash", one["title"])
        assertEquals(t.id.toLong(), (one["id"] as Number).toLong())
        assertEquals(56L, (one["columns"] as Number).toLong())
    }

    // --- The screen's side --------------------------------------------------

    private fun asScreen() {
        peer = LinkedPeer("Pixel 10", ScreenRole.SCREEN, ScreenMessage.ScreenInfo(1080, 2424, 420, 0))
    }

    @Test
    fun `the screen shows a terminal, its state and output, and acks it`() {
        asScreen()
        share.received(ScreenMessage.TermStart(5, "bash", 56, 40))
        assertEquals("term 5 bash 56x40", showing.did.last())
        assertEquals(listOf(ScreenRef(5, "bash")), share.shownTerms.map { ScreenRef(it.id, it.title) })
        share.received(ScreenMessage.TermState(5, 1, 56, 40, "x".toByteArray()))
        assertEquals("state 5 56x40", showing.did.last())
        share.received(ScreenMessage.TermOutput(5, 1, "hi".toByteArray()))
        assertEquals("output 5 hi", showing.did.last())
        assertEquals(ScreenMessage.TermAck(5, 1, 2), sent.last())
        share.received(ScreenMessage.TermTitle(5, "vim"))
        assertEquals("title 5 vim", showing.did.last())
        assertEquals("vim", share.shownTerms.single().title)
    }

    @Test
    fun `the screen asks for the size it fits, and sends typing and touches`() {
        asScreen()
        share.received(ScreenMessage.TermStart(5, "bash", 56, 40))
        share.received(ScreenMessage.TermState(5, 1, 56, 40, ByteArray(0)))
        share.fitTerm(5, 48, 30, 22, 51)
        assertEquals(ScreenMessage.TermSize(5, 48, 30, 22, 51), sent.last())
        // Output at the old size waits for the state at the new one.
        share.received(ScreenMessage.TermOutput(5, 1, "old".toByteArray()))
        assertFalse(showing.did.last().startsWith("output"))
        share.termInput(5, "q".toByteArray())
        assertEquals("q", String((sent.last() as ScreenMessage.TermInput).data))
        share.termTouched(5)
        assertEquals(ScreenMessage.TermTouched(5), sent.last())
        share.fitTerm(9, 1, 1, 1, 1)
        assertEquals(ScreenMessage.TermTouched(5), sent.last())
    }

    @Test
    fun `the terminal ending there, take, and the link ending`() {
        asScreen()
        share.received(ScreenMessage.TermStart(5, "bash", 56, 40))
        share.received(ScreenMessage.TermStart(6, "htop", 56, 40))
        assertEquals(listOf(6), share.take("htop").map { it.id })
        assertEquals(ScreenMessage.TakeBack(6), sent.last())
        share.received(ScreenMessage.TermEnd(6))
        assertEquals("term ended 6", showing.did.last())
        share.unlinked()
        assertEquals("term ended 5", showing.did.last())
        assertTrue(share.shownTerms.isEmpty())
    }
}
