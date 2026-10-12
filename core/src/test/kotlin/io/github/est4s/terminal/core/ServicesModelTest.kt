package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServicesModelTest {
    private var time = 1_000_000L
    private val services = Services<String>(now = { time })
    private val web = ServiceDef("web", "python3 -m http.server")
    private val db = ServiceDef("db", "postgres", autostart = true, restart = Restart.ALWAYS, restartLimit = 1)

    @Test
    fun `services from the file start stopped, in its order`() {
        services.sync(listOf(web, db))

        assertEquals(listOf("web", "db"), services.all.map { it.name })
        val first = services["web"]!!
        assertEquals(ServiceState.STOPPED, first.state)
        assertEquals(web, first.def)
        assertTrue(first.saved)
        assertNull(first.session)
        assertNull(first.started)
        assertNull(first.exitCode)
        assertEquals(0, first.restarts)
        assertFalse(first.open)
    }

    @Test
    fun `starting and a clean exit`() {
        services.sync(listOf(web))
        services.started("web", "s1", pid = 42, byHand = true)

        val service = services["web"]!!
        assertEquals(ServiceState.RUNNING, service.state)
        assertEquals("s1", service.session)
        assertEquals(42, service.pid)
        assertEquals(time, service.started)
        assertEquals(service, services.find("s1"))

        time += 5000
        assertNull(services.exited("s1", 0))
        assertEquals(ServiceState.STOPPED, service.state)
        assertEquals(0, service.exitCode)
        assertNull(service.pid)
        assertEquals("s1", service.session, "kept for its log")
    }

    @Test
    fun `a failure restarts after the delay, and counts`() {
        services.sync(listOf(web))
        services.started("web", "s1", 42, byHand = true)

        assertEquals(1000L, services.exited("s1", 1))
        val service = services["web"]!!
        assertEquals(ServiceState.STOPPED, service.state)
        assertTrue(service.restartPending)
        assertEquals(1, service.exitCode)

        assertTrue(services.restartDue("web"))
        services.started("web", "s2", 43, byHand = false)
        assertEquals(1, service.restarts)
        assertFalse(service.restartPending)
        assertEquals(1, service.exitCode, "the last exit code stays")
    }

    @Test
    fun `gives up after the limit and fails`() {
        services.sync(listOf(db))
        services.started("db", "s1", 1, byHand = true)
        assertEquals(1000L, services.exited("s1", 0))
        services.restartDue("db")
        services.started("db", "s2", 2, byHand = false)

        assertNull(services.exited("s2", 3))
        assertEquals(ServiceState.FAILED, services["db"]!!.state)
    }

    @Test
    fun `a start by hand starts the count over`() {
        services.sync(listOf(db))
        services.started("db", "s1", 1, byHand = true)
        services.exited("s1", 1)
        services.restartDue("db")
        services.started("db", "s2", 2, byHand = false)
        services.exited("s2", 1)

        services.started("db", "s3", 3, byHand = true)
        assertEquals(0, services["db"]!!.restarts)
        assertEquals(1000L, services.exited("s3", 1))
    }

    @Test
    fun `a stop that was asked for is never restarted`() {
        services.sync(listOf(db))
        services.started("db", "s1", 1, byHand = true)

        assertTrue(services.stopAsked("db"))
        assertEquals(ServiceState.STOPPING, services["db"]!!.state)
        assertNull(services.exited("s1", 143))
        assertEquals(ServiceState.STOPPED, services["db"]!!.state)
        assertEquals(143, services["db"]!!.exitCode)
    }

    @Test
    fun `stopping one waiting to restart cancels the restart`() {
        services.sync(listOf(web))
        services.started("web", "s1", 1, byHand = true)
        services.exited("s1", 1)

        assertFalse(services.stopAsked("web"), "nothing running to stop")
        assertFalse(services["web"]!!.restartPending)
        assertFalse(services.restartDue("web"))
    }

    @Test
    fun `stopping one that isn't running has nothing to stop`() {
        services.sync(listOf(web))
        assertFalse(services.stopAsked("web"))
        assertFalse(services.stopAsked("nope"))
    }

    @Test
    fun `an old session's exit changes nothing`() {
        services.sync(listOf(web))
        services.started("web", "s1", 1, byHand = true)
        services.started("web", "s2", 2, byHand = true)

        assertNull(services.exited("s1", 1))
        assertEquals(ServiceState.RUNNING, services["web"]!!.state)
        assertNull(services.exited("other", 1))
    }

    @Test
    fun `an unknown exit code is a failure`() {
        services.sync(listOf(web))
        services.started("web", "s1", 1, byHand = true)
        assertEquals(1000L, services.exited("s1", null))
        assertNull(services["web"]!!.exitCode)
    }

    @Test
    fun `a long run starts the backoff over`() {
        services.sync(listOf(web))
        services.started("web", "s1", 1, byHand = true)
        services.exited("s1", 1)
        services.restartDue("web")
        services.started("web", "s2", 2, byHand = false)
        time += 60_000
        assertEquals(1000L, services.exited("s2", 1))
    }

    @Test
    fun `a changed file updates the services and its restart rule`() {
        services.sync(listOf(web))
        services.started("web", "s1", 1, byHand = true)
        services.sync(listOf(web.copy(restart = Restart.NO), db))

        assertEquals(listOf("web", "db"), services.all.map { it.name })
        assertEquals(Restart.NO, services["web"]!!.def.restart)
        assertNull(services.exited("s1", 1))
    }

    @Test
    fun `a block removed from the file goes, or loses its file while it runs`() {
        services.sync(listOf(web, db))
        services.started("db", "s1", 1, byHand = true)
        services.sync(emptyList())

        assertEquals(listOf("db"), services.all.map { it.name })
        val running = services["db"]!!
        assertFalse(running.saved)
        assertNull(services.exited("s1", 1), "no restart without its block")
        assertEquals(ServiceState.STOPPED, running.state)
    }

    @Test
    fun `an adopted tab is an unsaved running service that isn't restarted`() {
        services.sync(listOf(web))
        val adopted = services.adopt("vim", "vim notes.txt", "~/notes", "tab", pid = 9, open = true)

        assertEquals(listOf("web", "vim"), services.all.map { it.name })
        assertFalse(adopted.saved)
        assertEquals(ServiceState.RUNNING, adopted.state)
        assertEquals(ServiceDef("vim", "vim notes.txt", "~/notes", restart = Restart.NO), adopted.def)
        assertEquals(9, adopted.pid)
        assertTrue(adopted.open)
        assertEquals(time, adopted.started)

        services.sync(listOf(web))
        assertEquals(listOf("web", "vim"), services.all.map { it.name }, "sync keeps it")
        assertNull(services.exited("tab", 1))
    }

    @Test
    fun `saving an adopted one makes it a service from the file`() {
        services.adopt("vim", "vim", "~", "tab", 9, open = false)
        services.save(ServiceDef("vim", "vim x"))

        val saved = services["vim"]!!
        assertTrue(saved.saved)
        assertEquals("vim x", saved.def.command)
        assertEquals(ServiceState.RUNNING, saved.state)
        assertEquals(1000L, services.exited("tab", 1), "restarts on failure now")
    }

    @Test
    fun `removing one`() {
        services.sync(listOf(web, db))
        services.remove("web")
        assertEquals(listOf("db"), services.all.map { it.name })
    }

    @Test
    fun `open as a tab, and hidden again`() {
        services.sync(listOf(web))
        services.setOpen("web", true)
        assertTrue(services["web"]!!.open)
        services.setOpen("web", false)
        assertFalse(services["web"]!!.open)
    }

    @Test
    fun `at app start, the autostart ones and those that were running`() {
        services.sync(listOf(web, db, ServiceDef("ssh", "sshd")))

        assertEquals(listOf("db"), services.toStart(null))
        assertEquals(listOf("web", "db"), services.toStart(SavedServices(listOf("web", "gone"))))
    }

    @Test
    fun `the state records the saved ones that run or will restart`() {
        services.sync(listOf(web, db, ServiceDef("ssh", "sshd")))
        services.started("web", "s1", 1, byHand = true)
        services.started("db", "s2", 2, byHand = true)
        services.exited("s2", 1)
        services.started("ssh", "s3", 3, byHand = true)
        services.stopAsked("ssh")
        services.adopt("vim", "vim", "~", "tab", 9, open = false)

        assertEquals(SavedServices(listOf("web", "db")), services.snapshot())
        assertEquals(1, services.all.count { it.state == ServiceState.RUNNING && it.saved })
        assertEquals(listOf("web", "ssh", "vim"), services.running.map { it.name })
    }

    @Test
    fun `the state file round-trips as plain text`() {
        val saved = SavedServices(listOf("web", "db"))
        val text = saved.serialize()

        assertTrue(text.startsWith("#"))
        assertTrue("running = web" in text)
        assertEquals(saved, parseSavedServices(text))
        assertEquals(SavedServices(emptyList()), parseSavedServices(SavedServices(emptyList()).serialize()))
        assertNull(parseSavedServices("version = 9\nrunning = web\n"))
        assertNull(parseSavedServices(""))
    }
}
