package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaTest {
    @Test
    fun `times are minutes and seconds, hours when there are any`() {
        assertEquals("0:00", mediaTime(0))
        assertEquals("0:07", mediaTime(7_999))
        assertEquals("1:02", mediaTime(62_000))
        assertEquals("59:59", mediaTime(3_599_000))
        assertEquals("1:02:03", mediaTime(3_723_000))
        assertEquals("0:00", mediaTime(-5))
    }

    @Test
    fun `the queue is the folder's audio files by name, ignoring case`() {
        assertEquals(
            listOf("a.mp3", "B.ogg", "c.flac"),
            mediaQueue(listOf("c.flac", "notes.txt", "B.ogg", "cover.jpg", "a.mp3", "clip.mp4"), "B.ogg"),
        )
    }

    @Test
    fun `the file playing is in the queue even when its name isn't audio`() {
        assertEquals(listOf("a.mp3", "track", "z.wav"), mediaQueue(listOf("z.wav", "track", "a.mp3"), "track"))
        assertEquals(listOf("a.mp3", "new.mp3"), mediaQueue(listOf("a.mp3"), "new.mp3"))
    }

    @Test
    fun `speeds go round 1, 1·25, 1·5, 2, 0·75`() {
        assertEquals(1.25f, nextSpeed(1f))
        assertEquals(1.5f, nextSpeed(1.25f))
        assertEquals(2f, nextSpeed(1.5f))
        assertEquals(0.75f, nextSpeed(2f))
        assertEquals(1f, nextSpeed(0.75f))
        assertEquals(1f, nextSpeed(3f))
    }

    @Test
    fun `seeking stays within the media`() {
        assertEquals(20_000L, seekBy(10_000, 10_000, 60_000))
        assertEquals(0L, seekBy(4_000, -10_000, 60_000))
        assertEquals(60_000L, seekBy(55_000, 10_000, 60_000))
        assertEquals(15_000L, seekBy(5_000, 10_000, -1))
        assertEquals(0L, seekBy(5_000, -10_000, 0))
    }

    @Test
    fun `the folder's queue is Debian paths, from the host folder's names`() {
        assertEquals(
            listOf("/root/Music/a.mp3", "/root/Music/b.ogg"),
            mediaFolderQueue(listOf("b.ogg", "cover.jpg", "a.mp3"), "b.ogg", "/root/Music/b.ogg"),
        )
    }

    @Test
    fun `a file reached through a link plays alone`() {
        // Its host folder is the link's target's, not the Debian folder.
        assertEquals(
            listOf("/root/song.mp3"),
            mediaFolderQueue(listOf("x.mp3", "track-1.mp3"), "track-1.mp3", "/root/song.mp3"),
        )
    }

    @Test
    fun `previous and next stop at the ends`() {
        val queue = listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3")
        assertEquals("/m/c.mp3", mediaStep(queue, "/m/b.mp3", 1))
        assertEquals("/m/a.mp3", mediaStep(queue, "/m/b.mp3", -1))
        assertEquals(null, mediaStep(queue, "/m/c.mp3", 1))
        assertEquals(null, mediaStep(queue, "/m/a.mp3", -1))
        assertEquals(null, mediaStep(queue, "/m/gone.mp3", 1))
    }

    @Test
    fun `previous restarts the track after its first 3 seconds`() {
        assertEquals(false, previousRestarts(3_000))
        assertEquals(true, previousRestarts(3_001))
    }

    @Test
    fun `a double tap on the left half goes back 10 seconds, on the right forward`() {
        assertEquals(-10_000L, doubleTapSeek(100f, 1080f))
        assertEquals(-10_000L, doubleTapSeek(539f, 1080f))
        assertEquals(10_000L, doubleTapSeek(540f, 1080f))
        assertEquals(10_000L, doubleTapSeek(1000f, 1080f))
    }

    @Test
    fun `speeds read as the button shows them`() {
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("0.75×", speedLabel(0.75f))
    }

    @Test
    fun `the folder under the name is home-relative`() {
        assertEquals("~/Music", mediaFolder("/root/Music/a.mp3"))
        assertEquals("~", mediaFolder("/root/a.mp3"))
        assertEquals("/sdcard/Music", mediaFolder("/sdcard/Music/a.mp3"))
        assertEquals("/", mediaFolder("/a.mp3"))
    }

    @Test
    fun `a video fits the screen, its shape kept`() {
        assertEquals(1080 to 607, videoFit(1080, 2400, 1920, 1080))
        assertEquals(1350 to 1080, videoFit(2400, 1080, 1280, 1024))
        assertEquals(1080 to 1920, videoFit(1080, 2400, 720, 1280))
        assertEquals(1080 to 2400, videoFit(1080, 2400, 0, 0))
    }
}
