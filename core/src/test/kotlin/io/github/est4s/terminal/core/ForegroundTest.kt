package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundTest {
    private val proc = createTempDirectory("proc").toFile()

    @AfterTest
    fun cleanup() {
        proc.deleteRecursively()
    }

    // A process: traced by [tracer], child of [parent], in group [group], the terminal's foreground group [tpgid].
    private fun process(pid: Int, tracer: Int, parent: Int, group: Int, tpgid: Int, comm: String, vararg argv: String) {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/status").writeText("Name:\t$comm\nTracerPid:\t$tracer\nPPid:\t$parent\n")
        File(proc, "$pid/stat").writeText("$pid ($comm) S $parent $group 4000 34816 $tpgid 4194560 120 0 0 0\n")
        File(proc, "$pid/cmdline").writeBytes(argv.joinToString("") { "$it\u0000" }.toByteArray())
    }

    @Test
    fun `the command is the foreground group leader's command line`() {
        process(5000, tracer = 0, parent = 1, group = 5000, tpgid = 0, comm = "libproot.so", "proot")
        process(5010, tracer = 5000, parent = 5000, group = 5010, tpgid = 5020, comm = "bash", "/bin/bash", "--login")
        process(5020, tracer = 5000, parent = 5010, group = 5020, tpgid = 5020, comm = "python3", "python3", "-m", "http.server", "8080")
        process(5021, tracer = 5000, parent = 5020, group = 5020, tpgid = 5020, comm = "sh", "sh", "-c", "x")

        assertEquals("python3 -m http.server 8080", foregroundCommand(5000, proc))
    }

    @Test
    fun `words that need quoting are quoted`() {
        process(5000, 0, 1, 5000, 0, "libproot.so", "proot")
        process(5010, 5000, 5000, 5010, 5020, "bash", "bash")
        process(5020, 5000, 5010, 5020, 5020, "grep", "grep", "-r", "two words", "it's", "a=b", "/srv/x.log")

        assertEquals("""grep -r 'two words' 'it'\''s' a=b /srv/x.log""", foregroundCommand(5000, proc))
    }

    @Test
    fun `just the shell in the foreground is no command`() {
        process(5000, 0, 1, 5000, 0, "libproot.so", "proot")
        process(5010, 5000, 5000, 5010, 5010, "bash", "/bin/bash", "--login")

        assertNull(foregroundCommand(5000, proc))
    }

    @Test
    fun `a name with spaces and parentheses doesn't confuse the fields`() {
        process(5000, 0, 1, 5000, 0, "libproot.so", "proot")
        process(5010, 5000, 5000, 5010, 5020, "my (odd) sh", "sh")
        process(5020, 5000, 5010, 5020, 5020, "vim", "vim", "notes.txt")

        assertEquals("vim notes.txt", foregroundCommand(5000, proc))
    }

    @Test
    fun `nothing is known when proot runs nothing or the leader has gone`() {
        assertNull(foregroundCommand(5000, proc))
        process(5000, 0, 1, 5000, 0, "libproot.so", "proot")
        process(5010, 5000, 5000, 5010, 5099, "bash", "bash")
        assertNull(foregroundCommand(5000, proc))
    }

    @Test
    fun `a name for a service is the command's first word, if it makes one`() {
        assertEquals("python3", suggestServiceName("python3 -m http.server"))
        assertEquals("sshd", suggestServiceName("/usr/sbin/sshd -D -p 8022"))
        assertEquals("node", suggestServiceName("Node server.js"))
        assertEquals("", suggestServiceName(null))
        assertEquals("", suggestServiceName("'two words' x"))
        assertEquals("", suggestServiceName("a-very-long-program-name-indeed"))
    }
}
