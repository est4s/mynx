package io.github.est4s.terminal.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootfsInstallerTest {
    private val base = createTempDirectory("rootfs-test").toFile()
    private val installer = RootfsInstaller(base)
    private val rootfs = File(base, "debian")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `unpacks directories and files`() {
        install(archive {
            dir("etc/")
            file("etc/os-release", "ID=debian\n")
        })

        assertEquals("ID=debian\n", File(rootfs, "etc/os-release").readText())
    }

    @Test
    fun `is installed only after an install finished`() {
        assertFalse(installer.isInstalled)

        install(archive { file("etc/os-release", "ID=debian\n") })

        assertTrue(installer.isInstalled)
    }

    @Test
    fun `keeps the executable bit`() {
        install(archive {
            file("usr/bin/tool", "#!/bin/sh\n", mode = "755")
            file("etc/motd", "hi\n", mode = "644")
        })

        assertTrue(OWNER_EXECUTE in perms("usr/bin/tool"))
        assertFalse(OWNER_EXECUTE in perms("etc/motd"))
    }

    @Test
    fun `owner can always read and write what was unpacked`() {
        // apt must be able to replace these later; we're not really root.
        install(archive {
            dir("usr/share/locked/", mode = "555")
            file("usr/share/locked/file", "x", mode = "000")
        })

        assertTrue(perms("usr/share/locked").containsAll(listOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)))
        assertTrue(perms("usr/share/locked/file").containsAll(listOf(OWNER_READ, OWNER_WRITE)))
    }

    @Test
    fun `creates symlinks without resolving them`() {
        install(archive {
            file("usr/bin/bash", "elf")
            symlink("bin", "usr/bin")
            symlink("usr/bin/sh", "/usr/bin/bash")
        })

        assertEquals("usr/bin", Files.readSymbolicLink(File(rootfs, "bin").toPath()).toString())
        assertEquals("/usr/bin/bash", Files.readSymbolicLink(File(rootfs, "usr/bin/sh").toPath()).toString())
    }

    @Test
    fun `turns hard links into copies`() {
        // Android doesn't let apps create hard links.
        install(archive {
            file("usr/bin/perl", "perl", mode = "755")
            hardlink("usr/bin/perl5.40", "usr/bin/perl")
        })

        val copy = File(rootfs, "usr/bin/perl5.40")
        assertFalse(Files.isSymbolicLink(copy.toPath()))
        assertEquals("perl", copy.readText())
        assertTrue(OWNER_EXECUTE in perms("usr/bin/perl5.40"))
    }

    @Test
    fun `refuses entries that escape the rootfs`() {
        val error = assertFailsWith<IOException> {
            install(archive { file("../escaped", "x") })
        }

        assertContains(error.message!!, "../escaped")
        assertFalse(File(base, "escaped").exists())
        assertFalse(installer.isInstalled)
    }

    @Test
    fun `refuses to write through a symlink that leaves the rootfs`() {
        val outside = createTempDirectory("outside").toFile()
        try {
            assertFailsWith<IOException> {
                install(archive {
                    symlink("sneaky", outside.absolutePath)
                    file("sneaky/planted", "x")
                })
            }

            assertFalse(File(outside, "planted").exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `never overwrites an installed Debian`() {
        install(archive { file("root/notes.txt", "mine") })

        assertFailsWith<IllegalStateException> {
            install(archive { file("root/notes.txt", "fresh") })
        }

        assertEquals("mine", File(rootfs, "root/notes.txt").readText())
    }

    @Test
    fun `a failed install leaves nothing installed and can be retried`() {
        val full = archive {
            file("etc/os-release", "ID=debian\n")
            file("usr/lib/big", "x".repeat(100_000))
        }
        assertFailsWith<IOException> { install(full.copyOf(full.size / 2)) }
        assertFalse(installer.isInstalled)

        install(full)

        assertTrue(installer.isInstalled)
        assertEquals("ID=debian\n", File(rootfs, "etc/os-release").readText())
    }

    @Test
    fun `clearing a failed install doesn't follow symlinks out of it`() {
        val outside = createTempDirectory("outside").toFile()
        try {
            File(outside, "keep").writeText("precious")
            val partial = File(base, "debian.partial").apply { mkdirs() }
            Files.createSymbolicLink(File(partial, "storage").toPath(), outside.toPath())

            install(archive { file("etc/os-release", "ID=debian\n") })

            assertEquals("precious", File(outside, "keep").readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `writes DNS and hosts config, which Android doesn't provide`() {
        install(archive {
            file("etc/resolv.conf", "")
            file("etc/hosts", "")
        })

        val resolv = File(rootfs, "etc/resolv.conf").readText()
        assertContains(resolv, "nameserver 1.1.1.1")
        assertContains(resolv, "nameserver 8.8.8.8")
        assertContains(File(rootfs, "etc/hosts").readText(), "127.0.0.1\tlocalhost")
    }

    @Test
    fun `reports progress up to 100 percent`() {
        val progress = mutableListOf<Int>()

        install(archive { file("usr/lib/big", "x".repeat(500_000)) }) { progress += it }

        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    private fun install(bytes: ByteArray, onProgress: (Int) -> Unit = {}) =
        installer.install(ByteArrayInputStream(bytes), bytes.size.toLong(), onProgress)

    private fun perms(path: String) = Files.getPosixFilePermissions(File(rootfs, path).toPath())
}

/** Builds a small `.tar.xz` like the one in the APK. */
private fun archive(build: TarBuilder.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    TarArchiveOutputStream(XZCompressorOutputStream(bytes)).use { TarBuilder(it).build() }
    return bytes.toByteArray()
}

private class TarBuilder(private val tar: TarArchiveOutputStream) {
    fun dir(name: String, mode: String = "755") = put(TarArchiveEntry(name).apply { this.mode = mode.toInt(8) or 0x4000 })

    fun file(name: String, content: String, mode: String = "644") {
        val data = content.toByteArray()
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); this.mode = mode.toInt(8) or 0x8000 })
        tar.write(data)
        tar.closeArchiveEntry()
    }

    fun symlink(name: String, target: String) =
        put(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })

    fun hardlink(name: String, target: String) =
        put(TarArchiveEntry(name, TarConstants.LF_LINK).apply { linkName = target })

    private fun put(entry: TarArchiveEntry) {
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }
}
