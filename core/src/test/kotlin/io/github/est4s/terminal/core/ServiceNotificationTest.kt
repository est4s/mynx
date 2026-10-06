package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceNotificationTest {
    @Test
    fun `counts one terminal in the singular`() {
        assertEquals("1 terminal running", runningTerminalsText(1))
    }

    @Test
    fun `counts several terminals in the plural`() {
        assertEquals("3 terminals running", runningTerminalsText(3))
    }

    @Test
    fun `says so when nothing runs`() {
        assertEquals("No terminals running", runningTerminalsText(0))
    }

    @Test
    fun `says when the wakelock is held`() {
        assertEquals("2 terminals running · wakelock held", runningTerminalsText(2, wakelock = true))
    }
}
