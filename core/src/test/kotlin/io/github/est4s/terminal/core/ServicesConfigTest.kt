package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicesConfigTest {
    @Test
    fun `a block with only a command takes the defaults`() {
        val parsed = parseServices("[web]\ncommand = python3 -m http.server 8080\n")

        assertEquals(emptyList(), parsed.problems)
        assertEquals(
            listOf(
                ServiceDef(
                    name = "web", command = "python3 -m http.server 8080", cwd = "~", autostart = false,
                    restart = Restart.ON_FAILURE, restartLimit = 5, restartDelay = 1, stopTimeout = 10,
                ),
            ),
            parsed.services,
        )
    }

    @Test
    fun `every key, comments and blank lines`() {
        val parsed = parseServices(
            """
            # My services
            [db]
            # the database
            command = postgres -D /var/lib/pg -c 'a=b'
            cwd = /var/lib/pg
            autostart = yes
            restart = always
            restart-limit = 0
            restart-delay = 3
            stop-timeout = 30

            [ssh-2]
            command = /usr/sbin/sshd -D -p 8022
            restart = no
            """.trimIndent(),
        )

        assertEquals(emptyList(), parsed.problems)
        assertEquals(
            listOf(
                ServiceDef("db", "postgres -D /var/lib/pg -c 'a=b'", "/var/lib/pg", true, Restart.ALWAYS, 0, 3, 30),
                ServiceDef("ssh-2", "/usr/sbin/sshd -D -p 8022", restart = Restart.NO),
            ),
            parsed.services,
        )
    }

    @Test
    fun `bad values are reported by line and keep their default`() {
        val parsed = parseServices(
            """
            [web]
            command = run
            cwd = somewhere
            autostart = maybe
            restart = sometimes
            restart-limit = -1
            restart-delay = 0
            stop-timeout = forever
            colour = blue
            no equals sign
            """.trimIndent(),
        )

        assertEquals(listOf(ServiceDef("web", "run")), parsed.services)
        assertEquals(
            listOf(
                "line 3: cwd must be a full path or start with ~",
                "line 4: autostart must be yes or no",
                "line 5: restart must be one of: no, on-failure, always",
                "line 6: restart-limit must be a whole number from 0 to $MAX_RESTART_LIMIT",
                "line 7: restart-delay must be a whole number of seconds from 1 to $MAX_RESTART_DELAY",
                "line 8: stop-timeout must be a whole number of seconds from 1 to $MAX_STOP_TIMEOUT",
                "line 9: unknown key 'colour'",
                "line 10: expected key = value",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `bad names, missing commands, repeats and keys outside a block`() {
        val parsed = parseServices(
            """
            command = stray
            [Web Server]
            command = skipped
            [this-name-is-far-too-long]
            command = skipped too
            [empty]
            [ok]
            command = one
            [ok]
            command = two
            [also-ok]
            command =
            """.trimIndent(),
        )

        assertEquals(listOf(ServiceDef("ok", "one")), parsed.services)
        assertEquals(
            listOf(
                "line 1: command = … must come after a [name] line",
                "line 2: 'Web Server' can't be a service name: use a-z, 0-9 and -, up to $MAX_SERVICE_NAME",
                "line 4: 'this-name-is-far-too-long' can't be a service name: use a-z, 0-9 and -, up to $MAX_SERVICE_NAME",
                "line 6: [empty] has no command",
                "line 9: [ok] is already defined on line 7",
                "line 12: command must not be empty",
                "line 11: [also-ok] has no command",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `service names`() {
        assertTrue(isServiceName("web"))
        assertTrue(isServiceName("a-1"))
        assertTrue(isServiceName("x".repeat(MAX_SERVICE_NAME)))
        assertFalse(isServiceName("x".repeat(MAX_SERVICE_NAME + 1)))
        assertFalse(isServiceName(""))
        assertFalse(isServiceName("Web"))
        assertFalse(isServiceName("a_b"))
        assertFalse(isServiceName("a.b"))
    }
}
