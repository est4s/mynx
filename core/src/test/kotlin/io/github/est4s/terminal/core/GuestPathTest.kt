package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuestPathTest {
    private val rootfs = "/data/user/0/app/files/debian"

    // Android reaches app data as both /data/user/0/… and /data/data/…, so the
    // app decides by file identity, not by spelling.
    private val isRootfs = { path: String ->
        path == rootfs || path == "/data/data/app/files/debian"
    }

    @Test
    fun `maps a folder inside the rootfs to its Debian path`() {
        assertEquals("/root/src", guestPath("$rootfs/root/src", isRootfs))
        assertEquals("/", guestPath(rootfs, isRootfs))
    }

    @Test
    fun `recognises the rootfs under another spelling`() {
        assertEquals("/etc", guestPath("/data/data/app/files/debian/etc", isRootfs))
    }

    @Test
    fun `keeps folders in directories bound from Android`() {
        assertEquals("/storage/emulated/0/Download", guestPath("/storage/emulated/0/Download", isRootfs))
    }

    @Test
    fun `doesn't know other host folders`() {
        assertNull(guestPath("/data/user/0/app/files/debianx/root", isRootfs))
        assertNull(guestPath("/data/local/tmp", isRootfs))
        assertNull(guestPath("relative/path", isRootfs))
        assertNull(guestPath("/", isRootfs))
    }

    @Test
    fun `maps a Debian path back to the host`() {
        assertEquals("$rootfs/root/src", hostPath("/root/src", rootfs))
        assertEquals("/storage/emulated/0", hostPath("/storage/emulated/0", rootfs))
        assertNull(hostPath("root/src", rootfs))
    }

    @Test
    fun `reads the parent pid from a proc stat line`() {
        assertEquals(1234, parentPid("5678 (bash) S 1234 5678 5678 0 -1"))
        assertEquals(42, parentPid("7 (odd) name) (x) R 42 7 7 0"))
        assertNull(parentPid("garbage"))
    }

    @Test
    fun `finds the child of a process`() {
        val stats = mapOf(
            100 to "100 (libproot.so) S 1 100 100",
            101 to "101 (bash) S 100 101 101",
            102 to "102 (top) S 101 102 101",
        )

        assertEquals(101, childPid(100, stats))
        assertNull(childPid(102, stats))
    }
}
