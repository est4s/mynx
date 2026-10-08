package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun `parses versions and tags`() {
        assertEquals(Version(0, 1, 0), parseVersion("0.1.0"))
        assertEquals(Version(1, 22, 333), parseVersion("v1.22.333"))
        assertEquals(Version(2, 0, 0, "rc.1"), parseVersion("v2.0.0-rc.1"))
        assertEquals("2.0.0-rc.1", Version(2, 0, 0, "rc.1").toString())
        assertEquals("0.1.0", Version(0, 1, 0).toString())
    }

    @Test
    fun `rejects anything else`() {
        for (text in listOf("", "v", "1", "1.2", "1.2.3.4", "1.2.x", "01.2.3", "1.2.3-", "1.2.3-a..b", "1.2.3+build", "V1.2.3", " 1.2.3", "1.2.3-ä", "-1.2.3", "99999999999.0.0")) {
            assertNull(parseVersion(text), "parsed: '$text'")
        }
    }

    @Test
    fun `orders by number, not text`() {
        assertTrue(Version(0, 10, 0) > Version(0, 9, 9))
        assertTrue(Version(1, 0, 0) > Version(0, 99, 99))
        assertTrue(Version(0, 1, 2) > Version(0, 1, 1))
    }

    @Test
    fun `a pre-release sorts before its release`() {
        val order = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1-a")
        val versions = order.map { parseVersion(it)!! }
        assertEquals(versions, versions.shuffled(java.util.Random(4)).sorted())
    }

    @Test
    fun `newer only when both parse`() {
        assertTrue(isNewer("v0.2.0", "0.1.0"))
        assertFalse(isNewer("v0.1.0", "0.1.0"))
        assertFalse(isNewer("v0.1.0", "0.2.0"))
        assertFalse(isNewer("v0.2.0-rc.1", "0.2.0"))
        assertTrue(isNewer("v0.2.0", "0.2.0-rc.1"))
        assertFalse(isNewer("latest", "0.1.0"))
        assertFalse(isNewer("v9.9.9", "0.0.1-dev+abc"))
    }
}
