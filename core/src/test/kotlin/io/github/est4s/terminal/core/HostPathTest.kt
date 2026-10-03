package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostPathTest {
    private val rootfs = "/data/user/0/app/files/debian"

    @Test
    fun `maps a Debian path to the host`() {
        assertEquals("$rootfs/root/src", hostPath("/root/src", rootfs))
        assertEquals(rootfs, hostPath("/", rootfs))
    }

    @Test
    fun `keeps paths in directories bound from Android`() {
        assertEquals("/storage/emulated/0", hostPath("/storage/emulated/0", rootfs))
    }

    @Test
    fun `refuses relative paths`() {
        assertNull(hostPath("root/src", rootfs))
    }
}
