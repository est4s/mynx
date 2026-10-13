package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ServicesEditTest {
    @Test
    fun `adding to no file starts one with a header`() {
        val text = addService(null, ServiceDef("web", "python3 -m http.server 8080")).getOrThrow()

        assertEquals(
            SERVICES_HEADER + "\n[web]\ncommand = python3 -m http.server 8080\n",
            text,
        )
        assertEquals(listOf(ServiceDef("web", "python3 -m http.server 8080")), parseServices(text).services)
    }

    @Test
    fun `a block writes the keys that aren't the default`() {
        val def = ServiceDef("db", "postgres", "/srv", autostart = true, restart = Restart.ALWAYS, restartLimit = 0, restartDelay = 2, stopTimeout = 30)
        val text = addService("# mine\n[web]\ncommand = run", def).getOrThrow()

        assertEquals(
            "# mine\n[web]\ncommand = run\n\n[db]\ncommand = postgres\ncwd = /srv\nautostart = yes\n" +
                "restart = always\nrestart-limit = 0\nrestart-delay = 2\nstop-timeout = 30\n",
            text,
        )
        assertEquals(def, parseServices(text).services.last())
    }

    @Test
    fun `adding refuses a name that's taken, a bad name or a command on two lines`() {
        val text = "[web]\ncommand = run\n"
        assertEquals("there's already a service named web", addService(text, ServiceDef("web", "x")).exceptionOrNull()?.message)
        assertEquals(badServiceName("Web"), addService(text, ServiceDef("Web", "x")).exceptionOrNull()?.message)
        assertEquals("command must be one line", addService(text, ServiceDef("db", "a\nb")).exceptionOrNull()?.message)
        assertEquals("command must not be empty", addService(text, ServiceDef("db", " ")).exceptionOrNull()?.message)
    }

    @Test
    fun `removing a block keeps every other line and the next block's comments`() {
        val text = """
            # My services
            [web]
            # serves the folder
            command = run
            cwd = /srv

            # the database, below
            [db]
            command = postgres
        """.trimIndent() + "\n"

        assertEquals(
            "# My services\n\n# the database, below\n[db]\ncommand = postgres\n",
            removeService(text, "web").getOrThrow(),
        )
        assertEquals(
            "# My services\n[web]\n# serves the folder\ncommand = run\ncwd = /srv\n",
            removeService(text, "db").getOrThrow(),
        )
    }

    @Test
    fun `removing what was added gives the file back`() {
        val text = "# mine\n[web]\ncommand = run\n"
        assertEquals(text, removeService(addService(text, ServiceDef("db", "x")).getOrThrow(), "db").getOrThrow())
    }

    @Test
    fun `removing a name that isn't there refuses`() {
        assertEquals("no service named db in ~/.config/mynx/services.conf",
            removeService("[web]\ncommand = run\n", "db").exceptionOrNull()?.message)
    }

    @Test
    fun `setting a key changes its line and keeps the rest`() {
        val text = """
            # My services
            [web]
            # serves the folder
            command = run
            autostart = yes

            [db]
            command = postgres
            autostart = yes
        """.trimIndent() + "\n"

        assertEquals(
            text.replaceFirst("autostart = yes", "autostart = no"),
            setServiceKey(text, "web", "autostart", "no").getOrThrow(),
        )
    }

    @Test
    fun `setting a key the block doesn't have adds it after the block's last line`() {
        val text = "[web]\ncommand = run\ncwd = /srv\n\n# the database\n[db]\ncommand = postgres\n"

        val set = setServiceKey(text, "web", "autostart", "yes").getOrThrow()

        assertEquals("[web]\ncommand = run\ncwd = /srv\nautostart = yes\n\n# the database\n[db]\ncommand = postgres\n", set)
        assertEquals(listOf(true, false), parseServices(set).services.map { it.autostart })
        assertEquals(
            "[web]\ncommand = run\n\n[db]\ncommand = postgres\nautostart = yes\n",
            setServiceKey("[web]\ncommand = run\n\n[db]\ncommand = postgres", "db", "autostart", "yes").getOrThrow(),
        )
    }

    @Test
    fun `setting a key the block has twice changes both`() {
        val set = setServiceKey("[web]\ncommand = run\nautostart = yes\nautostart=yes\n", "web", "autostart", "no").getOrThrow()

        assertEquals("[web]\ncommand = run\nautostart = no\nautostart = no\n", set)
    }

    @Test
    fun `setting refuses a service that isn't there, an unknown key or a bad value`() {
        val text = "[web]\ncommand = run\n"
        assertEquals("no service named db in ~/.config/mynx/services.conf",
            setServiceKey(text, "db", "autostart", "yes").exceptionOrNull()?.message)
        assertEquals("unknown key 'colour'", setServiceKey(text, "web", "colour", "red").exceptionOrNull()?.message)
        assertEquals("autostart must be yes or no", setServiceKey(text, "web", "autostart", "on").exceptionOrNull()?.message)
    }
}
