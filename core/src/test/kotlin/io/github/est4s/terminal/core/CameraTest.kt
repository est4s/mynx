package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraRequestTest {
    private val base = createTempDirectory("camera").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val rootfs = File(base, "debian")
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val asked = mutableListOf<Pair<PhotoQuery, PhotoReport>>()
    private val requests = Pc26Requests(
        dir, home,
        later = cameraRequests(home) { query, report -> asked += query to report },
        alive = { true },
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `the camera app gets 10 minutes, a quick shot 30 s`() {
        send("7-1", "camera", "/root/a.jpg")
        send("7-2", "camera-quick", "/root/b.jpg", "front")
        assertEquals(
            listOf(
                PhotoQuery(File(home, "a.jpg"), "/root/a.jpg", quick = null),
                PhotoQuery(File(home, "b.jpg"), "/root/b.jpg", quick = Facing.FRONT),
            ),
            asked.map { it.first },
        )
        assertEquals("600", file("7-1.wait").readText())
        assertEquals("30", file("7-2.wait").readText())
    }

    @Test
    fun `the photo taken is moved to the file, replacing it`() {
        File(home, "a.jpg").writeText("old")
        send("7-1", "camera", "/root/a.jpg")
        val photo = File(base, "shot.tmp").apply { writeText("jpeg bytes") }
        asked.single().second.taken(photo)
        assertEquals("jpeg bytes", File(home, "a.jpg").readText())
        assertFalse(photo.exists())
        assertEquals("""{"ok":true,"file":"/root/a.jpg","bytes":10}""", file("7-1.reply").readText())
    }

    @Test
    fun `no photo leaves the file alone`() {
        File(home, "a.jpg").writeText("old")
        send("7-1", "camera", "/root/a.jpg")
        asked.single().second.fail("no photo was taken")
        assertEquals("old", File(home, "a.jpg").readText())
        assertEquals("""{"ok":false,"error":"no photo was taken"}""", file("7-1.reply").readText())
    }

    @Test
    fun `a photo after pc26 stopped waiting is thrown away`() {
        send("7-1", "camera", "/root/a.jpg")
        var stopped = false
        asked.single().second.onCancel { stopped = true }
        file("7-1.cancel").writeText("")
        requests.sweep()
        assertTrue(stopped)

        val photo = File(base, "shot.tmp").apply { writeText("jpeg bytes") }
        asked.single().second.taken(photo)
        assertFalse(File(home, "a.jpg").exists())
        assertFalse(photo.exists())
    }

    @Test
    fun `refuses paths it can't save to and cameras it doesn't know`() {
        File(home, "pics").mkdirs()
        send("7-1", "camera")
        send("7-2", "camera", "a.jpg")
        send("7-3", "camera", "/root/pics")
        send("7-4", "camera", "/root/nowhere/a.jpg")
        send("7-5", "camera-quick", "/root/a.jpg", "side")
        assertTrue(asked.isEmpty())
        assertEquals("""{"ok":false,"error":"camera needs a file to save the photo to"}""", file("7-1.reply").readText())
        assertEquals("""{"ok":false,"error":"the path must start with /: a.jpg"}""", file("7-2.reply").readText())
        assertEquals("""{"ok":false,"error":"/root/pics is a folder"}""", file("7-3.reply").readText())
        assertEquals("""{"ok":false,"error":"no such folder: /root/nowhere"}""", file("7-4.reply").readText())
        assertEquals("""{"ok":false,"error":"the camera is front or back, not 'side'"}""", file("7-5.reply").readText())
    }

    @Test
    fun `no photos while android-camera is off`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("android-camera = off\n")
        send("7-1", "camera", "/root/a.jpg")
        send("7-2", "camera-quick", "/root/a.jpg", "back")
        assertTrue(asked.isEmpty())
        val off = """{"ok":false,"error":"the camera is off (pc26 set android-camera on)"}"""
        assertEquals(off, file("7-1.reply").readText())
        assertEquals(off, file("7-2.reply").readText())
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
}

class TorchRequestTest {
    private val base = createTempDirectory("torch").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val asked = mutableListOf<Pair<Boolean, Int?>>()
    private var refusal: String? = null
    private val requests = Pc26Requests(dir, home, torch = { on, percent -> asked += on to percent; refusal })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `turns the torch on, at a strength, and off`() {
        send("7-1", "torch", "on")
        send("7-2", "torch", "on", "40")
        send("7-3", "torch", "off")
        assertEquals(listOf(true to null, true to 40, false to null), asked)
        listOf("7-1", "7-2", "7-3").forEach { assertEquals("""{"ok":true}""", file("$it.reply").readText()) }
    }

    @Test
    fun `refuses what it doesn't know, and says why the app couldn't`() {
        send("7-1", "torch", "bright")
        send("7-2", "torch", "on", "0")
        send("7-3", "torch", "on", "101")
        send("7-4", "torch", "off", "50")
        assertTrue(asked.isEmpty())
        assertEquals("""{"ok":false,"error":"torch is on or off"}""", file("7-1.reply").readText())
        val strength = """{"ok":false,"error":"the strength is 1 to 100 %"}"""
        assertEquals(strength, file("7-2.reply").readText())
        assertEquals(strength, file("7-3.reply").readText())
        assertEquals("""{"ok":false,"error":"torch is on or off"}""", file("7-4.reply").readText())

        refusal = "the phone has no flashlight"
        send("7-5", "torch", "on")
        assertEquals("""{"ok":false,"error":"the phone has no flashlight"}""", file("7-5.reply").readText())
    }

    private fun send(id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun file(name: String) = File(dir, name)
}

class CameraMathTest {
    @Test
    fun `photos come out upright whichever way the screen is turned`() {
        // Pixel-style back camera (sensor at 90°) and front camera (270°).
        assertEquals(90, jpegOrientation(90, Facing.BACK, displayRotation = 0))
        assertEquals(0, jpegOrientation(90, Facing.BACK, displayRotation = 90))
        assertEquals(180, jpegOrientation(90, Facing.BACK, displayRotation = 270))
        assertEquals(270, jpegOrientation(270, Facing.FRONT, displayRotation = 0))
        assertEquals(0, jpegOrientation(270, Facing.FRONT, displayRotation = 90))
        assertEquals(180, jpegOrientation(270, Facing.FRONT, displayRotation = 270))
    }

    @Test
    fun `a shot waits for exposure and focus, but not forever`() {
        val converged = 2
        val searching = 1
        val focused = 2
        val scanning = 1
        assertFalse(shotReady(converged, focused, elapsedMs = 100))
        assertTrue(shotReady(converged, focused, elapsedMs = 400))
        assertTrue(shotReady(converged, af = null, elapsedMs = 400))
        assertTrue(shotReady(ae = null, af = null, elapsedMs = 400))
        assertFalse(shotReady(searching, focused, elapsedMs = 1000))
        assertFalse(shotReady(converged, scanning, elapsedMs = 1000))
        assertTrue(shotReady(searching, scanning, elapsedMs = 3000))
    }

    @Test
    fun `torch strength in percent maps onto the phone's levels`() {
        assertEquals(1, torchLevel(1, max = 5))
        assertEquals(3, torchLevel(50, max = 5))
        assertEquals(5, torchLevel(100, max = 5))
        assertEquals(1, torchLevel(1, max = 1))
        assertEquals(45, torchLevel(100, max = 45))
    }
}
