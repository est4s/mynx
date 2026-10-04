package io.github.est4s.terminal.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsInstallerTest {
    private val base = createTempDirectory("tools-test").toFile()
    private val installer = ToolsInstaller(base)
    private val tools = File(base, "tools")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `unpacks the tools with their executable bits`() {
        update("1", archive {
            file("bin/pocket", "#!/usr/bin/python3\n", mode = "755")
            file("themes/neon.colors.properties", "background=#000000\n")
        })

        assertEquals("background=#000000\n", File(tools, "themes/neon.colors.properties").readText())
        assertTrue(OWNER_EXECUTE in Files.getPosixFilePermissions(File(tools, "bin/pocket").toPath()))
    }

    @Test
    fun `records the version it installed, readable from Debian`() {
        update("42", archive { file("bin/pocket", "") })

        assertEquals("42\n", File(tools, ".version").readText())
    }

    @Test
    fun `leaves the tools alone while the version is the same`() {
        update("1", archive { file("bin/pocket", "old") })
        var opened = false

        installer.update("1") { opened = true; ByteArrayInputStream(archive { file("bin/pocket", "new") }) }

        assertFalse(opened)
        assertEquals("old", File(tools, "bin/pocket").readText())
    }

    @Test
    fun `a new version replaces the tools completely`() {
        update("1", archive {
            file("bin/pocket", "old")
            file("bin/gone", "removed in version 2")
        })

        update("2", archive { file("bin/pocket", "new") })

        assertEquals("new", File(tools, "bin/pocket").readText())
        assertFalse(File(tools, "bin/gone").exists())
    }

    @Test
    fun `a failed update keeps the old tools and is retried next time`() {
        update("1", archive { file("bin/pocket", "old") })

        assertFailsWith<IOException> { update("2", "not an archive".toByteArray()) }

        assertEquals("old", File(tools, "bin/pocket").readText())
        update("2", archive { file("bin/pocket", "new") })
        assertEquals("new", File(tools, "bin/pocket").readText())
    }

    @Test
    fun `puts the tools first on the PATH of login shells, after Debian's profile resets it`() {
        val rootfs = File(base, "debian").apply { mkdirs() }

        writeToolsProfile(rootfs)

        val script = File(rootfs, "etc/profile.d/pocket-terminal.sh")
        // First: the tools replace older copies of the same commands left in the rootfs.
        assertEquals("/opt/pocket-terminal/bin:/usr/bin:/bin\n", sourced(script, "/usr/bin:/bin"))
        // Sourcing it twice (a nested login shell) adds nothing.
        assertEquals("/usr/bin:/opt/pocket-terminal/bin\n", sourced(script, "/usr/bin:/opt/pocket-terminal/bin"))
    }

    @Test
    fun `loads the tools' bash setup in bash only`() {
        val rootfs = File(base, "debian").apply { mkdirs() }

        writeToolsProfile(rootfs)

        val text = File(rootfs, "etc/profile.d/pocket-terminal.sh").readText()
        assertTrue("""if [ -n "${'$'}{BASH_VERSION-}" ] && [ -r /opt/pocket-terminal/shell.bash ]; then
            |    . /opt/pocket-terminal/shell.bash
            |fi""".trimMargin() in text, text)
    }

    @Test
    fun `rewrites the profile script only when it changed`() {
        val rootfs = File(base, "debian").apply { mkdirs() }
        writeToolsProfile(rootfs)
        val script = File(rootfs, "etc/profile.d/pocket-terminal.sh")
        script.setLastModified(1000)

        writeToolsProfile(rootfs)
        assertEquals(1000, script.lastModified())

        script.writeText("stale")
        writeToolsProfile(rootfs)
        assertTrue("/opt/pocket-terminal/bin" in script.readText())
    }

    private fun sourced(script: File, path: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", ". \"$1\"; printf '%s\\n' \"\$PATH\"", "sh", script.path)
            .apply { environment()["PATH"] = path }
            .start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    private fun update(version: String, bytes: ByteArray) = installer.update(version) { ByteArrayInputStream(bytes) }
}
