package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPacerTest {
    private var clock = 0L
    private val pacer = VideoPacer(maxInFlight = 3, keyRetryMs = 1000, now = { clock })

    @Test
    fun `nothing is sent while the screen doesn't watch`() {
        assertFalse(pacer.frame(VIDEO_CONFIG, 0).send)
        assertFalse(pacer.frame(VIDEO_KEY, 1).send)
        assertFalse(pacer.frame(0, 2).send)
    }

    @Test
    fun `watching starts at a key frame, asking the encoder for one`() {
        assertTrue(pacer.watch(true))
        assertTrue(pacer.frame(VIDEO_CONFIG, 0).send)
        assertFalse(pacer.frame(0, 1).send)
        assertTrue(pacer.frame(VIDEO_KEY, 2).send)
        assertTrue(pacer.frame(0, 3).send)
    }

    @Test
    fun `frames go until too many wait for their ack, then wait for a key frame`() {
        pacer.watch(true)
        assertTrue(pacer.frame(VIDEO_KEY, 10).send)
        assertTrue(pacer.frame(0, 20).send)
        assertTrue(pacer.frame(0, 30).send)
        // Three unacked: the screen lags, so this one goes, and the frames after it
        // would decode wrong without it.
        val dropped = pacer.frame(0, 40)
        assertFalse(dropped.send)
        assertFalse(dropped.askKey, "the pipe is full: a key frame now would only wait too")
        assertEquals(3, pacer.inFlight)
        // Acks drain it: then a key frame is asked for, once.
        assertTrue(pacer.acked(20).askKey)
        assertEquals(1, pacer.inFlight)
        assertFalse(pacer.frame(0, 50).send)
        assertFalse(pacer.frame(0, 60).askKey)
        assertTrue(pacer.frame(VIDEO_KEY, 70).send)
        assertTrue(pacer.frame(0, 80).send)
    }

    @Test
    fun `an ack covers the frames before it`() {
        pacer.watch(true)
        pacer.frame(VIDEO_KEY, 10)
        pacer.frame(0, 20)
        pacer.frame(0, 30)
        pacer.acked(30)
        assertEquals(0, pacer.inFlight)
        // An ack of something long gone changes nothing.
        pacer.acked(5)
        assertEquals(0, pacer.inFlight)
    }

    @Test
    fun `a key frame that doesn't come is asked for again`() {
        pacer.watch(true)
        assertFalse(pacer.frame(0, 1).askKey)
        clock = 999
        assertFalse(pacer.frame(0, 2).askKey)
        clock = 1000
        assertTrue(pacer.frame(0, 3).askKey)
        assertFalse(pacer.frame(0, 4).askKey)
    }

    @Test
    fun `the screen asking for a key frame drops frames until one comes`() {
        pacer.watch(true)
        pacer.frame(VIDEO_KEY, 1)
        assertTrue(pacer.frame(0, 2).send)
        assertTrue(pacer.keyWanted())
        assertFalse(pacer.frame(0, 3).send)
        assertTrue(pacer.frame(VIDEO_KEY, 4).send)
    }

    @Test
    fun `not watching forgets what was in flight`() {
        pacer.watch(true)
        pacer.frame(VIDEO_KEY, 1)
        pacer.frame(0, 2)
        assertFalse(pacer.watch(false))
        assertEquals(0, pacer.inFlight)
        assertFalse(pacer.frame(VIDEO_KEY, 3).send)
    }
}

class ScreenVideoGeometryTest {
    @Test
    fun `the video fits the view, centred, keeping its shape`() {
        // A 1080x2400 stream on a 1080x2400 view: the whole view.
        assertEquals(StreamFit(0f, 0f, 1f), streamFit(1080, 2400, 1080, 2400))
        // Half the size: scaled up twice.
        assertEquals(StreamFit(0f, 0f, 2f), streamFit(1080, 2400, 540, 1200))
        // A wider view: bars at the sides.
        assertEquals(StreamFit(240f, 0f, 1f), streamFit(1560, 2400, 1080, 2400))
        // A taller view: bars above and below.
        assertEquals(StreamFit(0f, 100f, 1f), streamFit(1080, 2600, 1080, 2400))
    }

    @Test
    fun `nothing to fit gives no fit`() {
        assertNull(streamFit(0, 2400, 1080, 2400))
        assertNull(streamFit(1080, 2400, 1080, 0))
    }

    @Test
    fun `a touch on the view lands on the video's pixels`() {
        val fit = streamFit(1560, 2400, 540, 1200)!!
        // Scale 2 (2400 / 1200), 1080 wide, so 240 of bar each side.
        assertEquals(Pair(0f, 0f), fit.toVideo(240f, 0f))
        assertEquals(Pair(270f, 600f), fit.toVideo(780f, 1200f))
        assertTrue(fit.inside(240f, 0f, 540, 1200))
        assertFalse(fit.inside(100f, 100f, 540, 1200))
        assertFalse(fit.inside(1400f, 100f, 540, 1200))
    }

    @Test
    fun `the encoder gets sizes to try, from the screen's own down`() {
        // Even sizes as they are, then multiples of 16, then smaller.
        val sizes = encoderSizes(1080, 2400)
        assertEquals(Pair(1080, 2400), sizes.first())
        assertEquals(Pair(1072, 2400), sizes[1])
        assertTrue(sizes.all { (w, h) -> w % 2 == 0 && h % 2 == 0 && w <= 1080 && h <= 2400 })
        assertTrue(sizes.drop(1).all { (w, h) -> w % 16 == 0 && h % 16 == 0 })
        assertEquals(sizes.size, sizes.toSet().size)
        // An odd size is evened first.
        assertEquals(Pair(1080, 2412), encoderSizes(1081, 2413).first())
        // Smaller ones keep the shape, roughly.
        val (w, h) = sizes.last()
        assertTrue(w >= 160 && kotlin.math.abs(h.toFloat() / w - 2400f / 1080) < 0.1f, "$w x $h")
    }
}

class TouchTrackTest {
    private fun down(x: Float, y: Float) = listOf(TouchPoint(0, x, y))

    @Test
    fun `a gesture that starts on the video goes, mapped`() {
        val track = TouchTrack()
        val fit = streamFit(1560, 2400, 1080, 2400)!!
        val first = track.map(7, ACTION_DOWN, 0, down(300f, 50f), fit, 1080, 2400)
        assertEquals(ScreenMessage.Touch(7, ACTION_DOWN, 0, down(60f, 50f)), first)
        // Moving off the side: kept at the edge, so the gesture still ends where it should.
        val off = track.map(7, ACTION_MOVE, 0, down(10f, 50f), fit, 1080, 2400)
        assertEquals(ScreenMessage.Touch(7, ACTION_MOVE, 0, down(0f, 50f)), off)
        assertEquals(ScreenMessage.Touch(7, ACTION_UP, 0, down(0f, 50f)), track.map(7, ACTION_UP, 0, down(10f, 50f), fit, 1080, 2400))
    }

    @Test
    fun `a gesture that starts on the bars is left out, all of it`() {
        val track = TouchTrack()
        val fit = streamFit(1560, 2400, 1080, 2400)!!
        assertNull(track.map(7, ACTION_DOWN, 0, down(100f, 50f), fit, 1080, 2400))
        assertNull(track.map(7, ACTION_MOVE, 0, down(400f, 50f), fit, 1080, 2400))
        assertNull(track.map(7, ACTION_UP, 0, down(400f, 50f), fit, 1080, 2400))
        // The next one is new.
        assertTrue(track.map(7, ACTION_DOWN, 0, down(400f, 50f), fit, 1080, 2400) != null)
    }
}
