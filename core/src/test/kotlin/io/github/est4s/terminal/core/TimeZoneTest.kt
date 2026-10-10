package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeZoneTest {
    private val known = setOf("Europe/Istanbul", "America/St_Johns", "Asia/Kolkata", "UTC")

    @Test
    fun `a zone Debian knows is passed by name`() {
        assertEquals("Europe/Istanbul", debianTimeZone("Europe/Istanbul", 3 * 3600) { it in known })
    }

    @Test
    fun `a zone Debian doesn't know becomes a fixed offset, east of UTC negative`() {
        assertEquals("<+03>-3", debianTimeZone("Europe/Nowhere", 3 * 3600) { false })
        assertEquals("<-05>5", debianTimeZone("America/Nowhere", -5 * 3600) { false })
        assertEquals("<+00>0", debianTimeZone("Etc/Nowhere", 0) { false })
    }

    @Test
    fun `fixed offsets keep their minutes`() {
        assertEquals("<+0530>-5:30", debianTimeZone("Asia/Nowhere", 5 * 3600 + 30 * 60) { false })
        assertEquals("<-0330>3:30", debianTimeZone("America/Nowhere", -(3 * 3600 + 30 * 60)) { false })
    }

    @Test
    fun `names that could leave the zoneinfo folder are never looked up`() {
        val asked = mutableListOf<String>()
        listOf("../../etc/passwd", "/etc/localtime", "", "Europe/ Istanbul").forEach {
            assertEquals("<+03>-3", debianTimeZone(it, 3 * 3600) { name -> asked += name; true })
        }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `the zoneinfo file is looked up under the rootfs`() {
        assertEquals("/data/files/debian/usr/share/zoneinfo/Asia/Kolkata", zoneInfoPath("/data/files/debian", "Asia/Kolkata"))
    }
}
