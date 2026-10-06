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
            file("bin/pc26", "#!/usr/bin/python3\n", mode = "755")
            file("themes/neon.colors.properties", "background=#000000\n")
        })

        assertEquals("background=#000000\n", File(tools, "themes/neon.colors.properties").readText())
        assertTrue(OWNER_EXECUTE in Files.getPosixFilePermissions(File(tools, "bin/pc26").toPath()))
    }

    @Test
    fun `records the version it installed, readable from Debian`() {
        update("42", archive { file("bin/pc26", "") })

        assertEquals("42\n", File(tools, ".version").readText())
    }

    @Test
    fun `leaves the tools alone while the version is the same`() {
        update("1", archive { file("bin/pc26", "old") })
        var opened = false

        installer.update("1") { opened = true; ByteArrayInputStream(archive { file("bin/pc26", "new") }) }

        assertFalse(opened)
        assertEquals("old", File(tools, "bin/pc26").readText())
    }

    @Test
    fun `a new version replaces the tools completely`() {
        update("1", archive {
            file("bin/pc26", "old")
            file("bin/gone", "removed in version 2")
        })

        update("2", archive { file("bin/pc26", "new") })

        assertEquals("new", File(tools, "bin/pc26").readText())
        assertFalse(File(tools, "bin/gone").exists())
    }

    @Test
    fun `a failed update keeps the old tools and is retried next time`() {
        update("1", archive { file("bin/pc26", "old") })

        assertFailsWith<IOException> { update("2", "not an archive".toByteArray()) }

        assertEquals("old", File(tools, "bin/pc26").readText())
        update("2", archive { file("bin/pc26", "new") })
        assertEquals("new", File(tools, "bin/pc26").readText())
    }

    @Test
    fun `puts the tools first on the PATH of login shells, after Debian's profile resets it`() {
        val rootfs = File(base, "debian").apply { mkdirs() }

        writeToolsProfile(rootfs)

        val script = File(rootfs, "etc/profile.d/pc26.sh")
        // First: the tools replace older copies of the same commands left in the rootfs.
        assertEquals("/opt/pc26/bin:/usr/bin:/bin\n", sourced(script, "/usr/bin:/bin"))
        // Sourcing it twice (a nested login shell) adds nothing.
        assertEquals("/usr/bin:/opt/pc26/bin\n", sourced(script, "/usr/bin:/opt/pc26/bin"))
    }

    @Test
    fun `loads the tools' bash setup in bash only`() {
        val rootfs = File(base, "debian").apply { mkdirs() }

        writeToolsProfile(rootfs)

        val text = File(rootfs, "etc/profile.d/pc26.sh").readText()
        assertTrue("""if [ -n "${'$'}{BASH_VERSION-}" ] && [ -r /opt/pc26/shell.bash ]; then
            |    . /opt/pc26/shell.bash
            |fi""".trimMargin() in text, text)
    }

    @Test
    fun `rewrites the profile script only when it changed`() {
        val rootfs = File(base, "debian").apply { mkdirs() }
        writeToolsProfile(rootfs)
        val script = File(rootfs, "etc/profile.d/pc26.sh")
        script.setLastModified(1000)

        writeToolsProfile(rootfs)
        assertEquals(1000, script.lastModified())

        script.writeText("stale")
        writeToolsProfile(rootfs)
        assertTrue("/opt/pc26/bin" in script.readText())
    }

    private fun sourced(script: File, path: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", ". \"$1\"; printf '%s\\n' \"\$PATH\"", "sh", script.path)
            .apply { environment()["PATH"] = path }
            .start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    private fun update(version: String, bytes: ByteArray) = installer.update(version) { ByteArrayInputStream(bytes) }
}
