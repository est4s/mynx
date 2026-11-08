package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Part A adds the tests of the file, asking and the requests here,
// with FakeBridgeHost (BridgeTest.kt) for the dialogs.
class AppPermissionsTest {
    @Test
    fun `permissions are known by their words`() {
        assertEquals(
            listOf("location", "camera", "microphone", "clipboard", "notifications", "bluetooth", "serial"),
            AppPermission.entries.map { it.word },
        )
        assertEquals(AppPermission.CLIPBOARD, AppPermission.of("clipboard"))
        assertNull(AppPermission.of("services"))
    }

    @Test
    fun `answers are allow, deny or ask`() {
        assertEquals(PermissionAnswer.DENY, PermissionAnswer.of("deny"))
        assertNull(PermissionAnswer.of("yes"))
    }
}
