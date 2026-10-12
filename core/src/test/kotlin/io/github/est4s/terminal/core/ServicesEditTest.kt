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
}
