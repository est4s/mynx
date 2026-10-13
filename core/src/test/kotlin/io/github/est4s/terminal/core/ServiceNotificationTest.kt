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

    @Test
    fun `says a service was given up on, with its exit code`() {
        assertEquals("web stopped (code 1), not restarting", serviceGaveUpText("web", 1))
        assertEquals("web stopped, not restarting", serviceGaveUpText("web", null))
    }

    @Test
    fun `counts the services after the terminals`() {
        assertEquals("2 terminals · 1 service", runningTerminalsText(2, services = 1))
        assertEquals("1 terminal · 3 services", runningTerminalsText(1, services = 3))
        assertEquals("No terminals · 1 service", runningTerminalsText(0, services = 1))
        assertEquals("1 terminal · 2 services · wakelock held", runningTerminalsText(1, wakelock = true, services = 2))
    }

    @Test
    fun `the strip counts the active services, and shows nothing without any`() {
        assertEquals("● 2", servicesMark(2))
        assertEquals("● 1", servicesMark(1))
        assertEquals(null, servicesMark(0))
    }
}
