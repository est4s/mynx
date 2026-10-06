package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProotLaunchTest {
    private val paths = ProotPaths(
        proot = "/app/lib/libproot.so",
        loader = "/app/lib/libproot-loader.so",
        rootfs = "/data/files/debian",
        tmpDir = "/data/cache/proot",
    )

    @Test
    fun `runs the proot binary against the rootfs as fake root`() {
        val argv = prootLaunch(paths).argv

        assertEquals("/app/lib/libproot.so", argv.first())
        assertTrue("-0" in argv)
        assertEquals("/data/files/debian", argv.valueAfter("-r"))
    }

    @Test
    fun `kills child processes on exit and converts hard links`() {
        val argv = prootLaunch(paths).argv

        assertTrue("--kill-on-exit" in argv)
        assertTrue("--link2symlink" in argv)
    }

    @Test
    fun `binds the host directories Debian needs`() {
        val binds = prootLaunch(paths).argv.valuesAfter("-b")

        assertEquals(listOf("/dev", "/proc", "/sys", "/storage"), binds)
    }

    @Test
    fun `starts a login bash in root's home by default`() {
        val argv = prootLaunch(paths).argv

        assertEquals("/root", argv.valueAfter("-w"))
        assertEquals(listOf("/bin/bash", "--login"), argv.takeLast(2))
    }

    @Test
    fun `can start in another working directory`() {
        val argv = prootLaunch(paths, workDir = "/root/projects").argv

        assertEquals("/root/projects", argv.valueAfter("-w"))
    }

    @Test
    fun `gives the shell a clean Debian environment instead of Android's`() {
        val argv = prootLaunch(paths).argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.size)

        assertEquals("-i", shellEnv[1])
        assertTrue("HOME=/root" in shellEnv)
        assertTrue("TERM=xterm-256color" in shellEnv)
        assertTrue("LANG=C.UTF-8" in shellEnv)
        assertTrue(shellEnv.any { it.startsWith("PATH=") && "/usr/bin" in it })
    }

    @Test
    fun `points proot at its loader and temp dir`() {
        val env = prootLaunch(paths).env

        assertEquals("/app/lib/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("/data/cache/proot", env["PROOT_TMP_DIR"])
    }

    @Test
    fun `binds fake files over proc files Android blocks`() {
        val binds = prootLaunch(paths, fakeProc = mapOf("/proc/stat" to "/data/files/fake-proc/stat")).argv.valuesAfter("-b")

        assertEquals(listOf("/dev", "/proc", "/sys", "/storage", "/data/files/fake-proc/stat:/proc/stat"), binds)
    }

    @Test
    fun `has the shell write its folder to a file after each prompt`() {
        val argv = prootLaunch(paths, cwdFile = "/tmp/.pc26/cwd-3").argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue(
            "PROMPT_COMMAND={ printf '%s' \"\$PWD\" > /tmp/.pc26/cwd-3; } 2>/dev/null" in shellEnv,
            shellEnv.toString(),
        )
    }

    @Test
    fun `sets no prompt command unless asked`() {
        assertTrue(prootLaunch(paths).argv.none { it.startsWith("PROMPT_COMMAND=") })
    }

    @Test
    fun `asks the shell to open the launcher menu`() {
        val argv = prootLaunch(paths, openMenu = true).argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("PC26_MENU=1" in shellEnv, shellEnv.toString())
        // A .bashrc from before the rename checks the old name.
        assertTrue("POCKET_MENU=1" in shellEnv, shellEnv.toString())
    }

    @Test
    fun `opens no menu unless asked`() {
        assertTrue(prootLaunch(paths).argv.none { it.startsWith("PC26_MENU=") || it.startsWith("POCKET_MENU=") })
    }

    @Test
    fun `tells the shell where to report its key bar`() {
        val argv = prootLaunch(paths, keyBarFile = "/tmp/.pc26/keybar-3").argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("PC26_KEYBAR_FILE=/tmp/.pc26/keybar-3" in shellEnv, shellEnv.toString())
    }

    @Test
    fun `sets no key bar file unless asked`() {
        assertTrue(prootLaunch(paths).argv.none { it.startsWith("PC26_KEYBAR_FILE=") })
    }

    @Test
    fun `mounts the app's tools and puts their commands on the PATH`() {
        val argv = prootLaunch(paths, toolsDir = "/data/files/tools").argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("/data/files/tools:/opt/pc26" in argv.valuesAfter("-b"))
        val path = shellEnv.single { it.startsWith("PATH=") }.removePrefix("PATH=").split(':')
        assertEquals("/opt/pc26/bin", path.first())
    }

    @Test
    fun `also mounts the tools at their path from before the rename`() {
        // Users' own notes and agent hooks name /opt/pocket-terminal.
        val argv = prootLaunch(paths, toolsDir = "/data/files/tools").argv

        assertTrue("/data/files/tools:/opt/pocket-terminal" in argv.valuesAfter("-b"))
        assertTrue(prootLaunch(paths).argv.none { it.endsWith(":/opt/pocket-terminal") })
    }

    @Test
    fun `tells programs where to send requests to the app`() {
        val argv = prootLaunch(paths, requestDir = "/tmp/.pc26/requests").argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("PC26_REQUESTS=/tmp/.pc26/requests" in shellEnv, shellEnv.toString())
    }

    @Test
    fun `programs open links with the app's xdg-open`() {
        val argv = prootLaunch(paths, toolsDir = "/data/files/tools").argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("BROWSER=/opt/pc26/bin/xdg-open" in shellEnv, shellEnv.toString())
    }

    @Test
    fun `tells programs which tab they run in`() {
        val argv = prootLaunch(paths, shellId = 7).argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("PC26_SHELL=7" in shellEnv, shellEnv.toString())
        assertTrue(prootLaunch(paths).argv.none { it.startsWith("PC26_SHELL=") })
    }

    @Test
    fun `can run a command instead of a login shell`() {
        val argv = prootLaunch(paths, command = listOf("/bin/sh", "/opt/pc26/lib/sound-server")).argv

        assertEquals(listOf("/bin/sh", "/opt/pc26/lib/sound-server"), argv.takeLast(2))
        assertTrue("/bin/bash" !in argv)
        assertEquals("-i", argv[argv.indexOf("/usr/bin/env") + 1])
    }

    @Test
    fun `a tab can run a program first, then its login shell`() {
        assertEquals(
            listOf("/bin/bash", "--login", "-c", "trap : INT; pc26 edit; exec /bin/bash --login"),
            runThenShell("pc26 edit"),
        )
    }

    @Test
    fun `the settings tab runs the settings editors`() {
        assertEquals(runThenShell("pc26 edit"), SETTINGS_TAB_COMMAND)
    }

    @Test
    fun `tells programs where the sound server is`() {
        val argv = prootLaunch(paths, soundSocket = "/tmp/.pc26/sound/native").argv
        val shellEnv = argv.subList(argv.indexOf("/usr/bin/env"), argv.indexOf("/bin/bash"))

        assertTrue("PULSE_SERVER=unix:/tmp/.pc26/sound/native" in shellEnv, shellEnv.toString())
        assertTrue(prootLaunch(paths).argv.none { it.startsWith("PULSE_SERVER=") })
    }

    private fun List<String>.valueAfter(flag: String) = this[indexOf(flag) + 1]

    private fun List<String>.valuesAfter(flag: String) =
        indices.filter { this[it] == flag }.map { this[it + 1] }
}
