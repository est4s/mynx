package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
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
        val name = (target as ScreenTarget.App).name
        return titles[name]?.let { Result.success(it) } ?: Result.failure(Exception("no app named $name"))
    }
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
}

internal class FakeShowing : ScreenShowing {
    val did = mutableListOf<String>()
    override fun started(stream: ShownStream) { did += "started ${stream.id} ${stream.title} ${stream.width}x${stream.height}" }
    override fun ended(stream: Int) { did += "ended $stream" }
    override fun video(message: ScreenMessage.Video) { did += "video ${message.stream} ${message.pts}" }
    override fun focus(typing: Boolean) { did += "focus $typing" }
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
}
