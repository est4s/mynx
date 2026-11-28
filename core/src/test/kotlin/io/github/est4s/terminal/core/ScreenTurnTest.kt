package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The other phone turning (or its screen changing otherwise): what's sent there follows its new shape. */
class ScreenTurnTest {
    private val root = Files.createTempDirectory("turn").toFile()
    private val settings = File(root, "settings.conf").also { it.writeText("screen-fps = 30\nscreen-bitrate = 4\n") }
    private val sent = mutableListOf<ScreenMessage>()
    private val landscape = ScreenMessage.ScreenInfo(2400, 1080, 440, 1, 409f, 409f)
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

    private fun turn(to: ScreenMessage.ScreenInfo) {
        peer = peer!!.copy(screen = to)
        share.peerScreenChanged(to)
    }

    @Test
    fun `a watched screen there is drawn at the new shape, its stream told and its encoder started again`() {
        val s = share.send(ScreenTarget.Desktop).getOrThrow()
        share.received(ScreenMessage.Watch(s.id, true))
        casting.did.clear()
        sent.clear()
        turn(landscape)
        // The old encoder stops before the screen is resized; the new one starts at the new size.
        assertEquals(listOf("stop ${s.id}", "resize ${s.id} 2400x1080 @440", "encode ${s.id} 30 fps 4000 kbit/s"), casting.did)
        assertEquals(listOf<ScreenMessage>(ScreenMessage.StreamStart(s.id, "Linux desktop", 2400, 1080)), sent)
        val now = share.sent.single()
        assertEquals(Pair(2400, 1080), Pair(now.width, now.height))
        assertTrue(now.watched)
    }

    @Test
    fun `the new encoder's first frame waits for a key frame, the old one's acks are forgotten`() {
        val s = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()
        share.received(ScreenMessage.Watch(s.id, true))
        share.encoded(s.id, VIDEO_KEY, 1, ByteArray(1))
        repeat(5) { share.encoded(s.id, 0, 2L + it, ByteArray(1)) }
        turn(landscape)
        sent.clear()
        // Six frames were in flight; after the turn the pipe is empty and a key frame goes first.
        share.encoded(s.id, 0, 100, ByteArray(1))
        assertTrue(sent.isEmpty())
        share.encoded(s.id, VIDEO_KEY, 101, ByteArray(1))
        assertEquals(101L, (sent.single() as ScreenMessage.Video).pts)
    }

    @Test
    fun `a screen there that isn't watched (its own Home on screen) is resized and told, no encoder started`() {
        val s = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()
        casting.did.clear()
        sent.clear()
        turn(landscape)
        assertEquals(listOf("resize ${s.id} 2400x1080 @440"), casting.did)
        assertEquals(listOf<ScreenMessage>(ScreenMessage.StreamStart(s.id, "Pixel Hero", 2400, 1080)), sent)
        // Watching it later starts the encoder at the new size.
        share.received(ScreenMessage.Watch(s.id, true))
        assertEquals("encode ${s.id} 30 fps 4000 kbit/s", casting.did.last())
        assertEquals(2400, share.sent.single().width)
    }

    @Test
    fun `the stream's size is what the encoder takes for the new shape`() {
        val s = share.send(ScreenTarget.App("pixel-hero")).getOrThrow()
        casting.encoderTakes = Pair(2400, 1072)
        sent.clear()
        turn(landscape)
        assertEquals(ScreenMessage.StreamStart(s.id, "Pixel Hero", 2400, 1072), sent.single())
    }

    @Test
    fun `turned upside down (the same size) the stream goes on, but the desktop's layout hears it`() {
        val s = share.send(ScreenTarget.Desktop).getOrThrow()
        share.received(ScreenMessage.Watch(s.id, true))
        casting.did.clear()
        sent.clear()
        turn(NORD.copy(rotation = 2))
        assertEquals(listOf("resize ${s.id} 1080x2400 @440"), casting.did)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `quick turns each say their size, and the last one wins`() {
        val s = share.send(ScreenTarget.Desktop).getOrThrow()
        share.received(ScreenMessage.Watch(s.id, true))
        sent.clear()
        turn(landscape)
        turn(NORD)
        turn(landscape)
        assertEquals(listOf(2400, 1080, 2400), sent.filterIsInstance<ScreenMessage.StreamStart>().map { it.width })
        assertEquals(Pair(2400, 1080), share.sent.single().let { Pair(it.width, it.height) })
        // Told the size it has already, the stream goes on.
        casting.did.clear()
        sent.clear()
        share.peerScreenChanged(landscape)
        assertTrue(sent.isEmpty())
        assertEquals(listOf("resize ${s.id} 2400x1080 @440"), casting.did)
    }

    @Test
    fun `its size said before anything is sent is the one the next send uses`() {
        turn(landscape)
        assertTrue(casting.did.isEmpty() && sent.isEmpty())
        val s = share.send(ScreenTarget.Desktop).getOrThrow()
        assertEquals(Pair(2400, 1080), Pair(s.width, s.height))
    }

    @Test
    fun `terminals there are left alone, as the screen asks for its own size`() {
        share.sendTerm(ScreenTarget.Terminal("1")).getOrThrow()
        casting.did.clear()
        sent.clear()
        turn(landscape)
        assertTrue(casting.did.isEmpty() && sent.isEmpty())
    }

    @Test
    fun `a screen saying no size changes nothing`() {
        share.send(ScreenTarget.Desktop).getOrThrow()
        casting.did.clear()
        sent.clear()
        turn(ScreenMessage.ScreenInfo(0, 0, 0, 0))
        assertTrue(casting.did.isEmpty() && sent.isEmpty())
    }

    @Test
    fun `on the screen, a stream announced again at a new size is resized, not shown anew`() {
        peer = LinkedPeer("Pixel 10", ScreenRole.SCREEN, ScreenMessage.ScreenInfo(1080, 2424, 420, 0))
        share.received(ScreenMessage.StreamStart(3, "Linux desktop", 1080, 2400))
        share.watch(3)
        share.received(ScreenMessage.StreamStart(3, "Linux desktop", 2400, 1080))
        assertEquals(listOf("started 3 Linux desktop 1080x2400", "resized 3 2400x1080"), showing.did)
        assertEquals(listOf(ShownStream(3, "Linux desktop", 2400, 1080)), share.shown)
        assertEquals(3, share.watching)
        // The same again: nothing to do.
        share.received(ScreenMessage.StreamStart(3, "Linux desktop", 2400, 1080))
        assertEquals(2, showing.did.size)
    }
}
