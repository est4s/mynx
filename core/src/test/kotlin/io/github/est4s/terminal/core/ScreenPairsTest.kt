package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenPairsTest {
    private val dir = Files.createTempDirectory("pairs").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun peer(id: String = "ab".repeat(16), name: String = "Nord 2") =
        ScreenPeer(id, name, ScreenRole.SCREEN, ByteArray(32) { it.toByte() }, "192.168.1.20:7391", 1000L)

    @Test
    fun `this phone's id is made once and kept`() {
        val id = ScreenPairs(dir).selfId
        assertTrue(SCREEN_ID.matches(id))
        assertEquals(id, ScreenPairs(dir).selfId)
        assertNotEquals(id, ScreenPairs(Files.createTempDirectory("other").toFile()).selfId)
    }

    @Test
    fun `a pair is saved, read back and forgotten`() {
        val pairs = ScreenPairs(dir)
        pairs.save(peer())
        val back = ScreenPairs(dir).get("ab".repeat(16))!!
        assertEquals("Nord 2", back.name)
        assertEquals(ScreenRole.SCREEN, back.role)
        assertContentEquals(ByteArray(32) { it.toByte() }, back.key)
        assertEquals("192.168.1.20:7391", back.address)
        assertEquals(1000L, back.paired)
        assertTrue(pairs.forget("ab".repeat(16)))
        assertFalse(pairs.forget("ab".repeat(16)))
        assertTrue(pairs.all().isEmpty())
    }

    @Test
    fun `pairs are found by id or by name, ignoring case`() {
        val pairs = ScreenPairs(dir)
        pairs.save(peer())
        pairs.save(peer("cd".repeat(16), "Pixel 10"))
        assertEquals("Pixel 10", pairs.find("pixel 10")?.name)
        assertEquals("Nord 2", pairs.find("ab".repeat(16))?.name)
        assertNull(pairs.find("Galaxy"))
        assertEquals(listOf("Nord 2", "Pixel 10"), pairs.all().map { it.name })
    }

    @Test
    fun `files that aren't pairs are skipped, and ids can't name other files`() {
        val pairs = ScreenPairs(dir)
        File(dir, "peers").mkdirs()
        File(dir, "peers/${"ef".repeat(16)}.conf").writeText("name = broken\n")
        assertTrue(pairs.all().isEmpty())
        assertNull(pairs.get("../self"))
        assertFalse(pairs.forget("../self"))
    }

    @Test
    fun `pairing codes are six digits, typed with or without spaces`() {
        val code = newPairingCode(SecureRandom())
        assertTrue(Regex("[0-9]{6}").matches(code))
        assertEquals("123456", pairingCode("123 456"))
        assertEquals("123456", pairingCode("123-456"))
        assertNull(pairingCode("12345"))
        assertNull(pairingCode("12345a"))
        assertEquals("123 456", showPairingCode("123456"))
    }
}

class ScreenDiscoveryTest {
    @Test
    fun `the host advertises its id, version and whether it's pairing`() {
        val ad = screenAd("ab".repeat(16), "Pixel 10", pairing = true)
        assertEquals("_mynx-screen._tcp", SCREEN_SERVICE_TYPE)
        assertEquals("mynx Pixel 10", ad.name)
        assertEquals(mapOf("v" to "$SCREEN_PROTOCOL", "id" to "ab".repeat(16), "pairing" to "1"), ad.txt)
    }

    @Test
    fun `a long name is cut to what mDNS allows`() {
        assertTrue(screenAd("ab".repeat(16), "x".repeat(100), pairing = false).name.toByteArray().size <= 63)
    }

    @Test
    fun `found services become hosts, others are skipped`() {
        val txt = mapOf("v" to "$SCREEN_PROTOCOL", "id" to "ab".repeat(16), "pairing" to "0")
        val host = foundScreenHost("mynx Pixel 10", txt, "192.168.1.10", 7391)!!
        assertEquals(FoundScreenHost("ab".repeat(16), "Pixel 10", "192.168.1.10", 7391, pairing = false), host)
        assertNull(foundScreenHost("mynx Pixel 10", txt - "id", "192.168.1.10", 7391))
        assertNull(foundScreenHost("mynx Pixel 10", txt + ("id" to "nope"), "192.168.1.10", 7391))
        // Another version is still a host, so the screen can say to update.
        assertEquals(9, foundScreenHost("mynx Pixel 10", txt + ("v" to "9"), "192.168.1.10", 7391)!!.version)
    }

    @Test
    fun `typed addresses, with or without a port`() {
        assertEquals("192.168.1.10" to 7391, screenAddress("192.168.1.10", 7391))
        assertEquals("192.168.1.10" to 8000, screenAddress("192.168.1.10:8000", 7391))
        assertEquals("fe80::1" to 8000, screenAddress("[fe80::1]:8000", 7391))
        assertEquals("fe80::1" to 7391, screenAddress("fe80::1", 7391))
        assertEquals("pixel.local" to 7391, screenAddress("pixel.local", 7391))
        assertNull(screenAddress("192.168.1.10:99999", 7391))
        assertNull(screenAddress("", 7391))
        assertNull(screenAddress("a b", 7391))
    }
}
