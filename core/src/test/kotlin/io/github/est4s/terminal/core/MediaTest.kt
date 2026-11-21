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
}
