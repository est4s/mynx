package io.github.est4s.terminal.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission

/** Unpacks `.tar.xz` archives into [root], refusing anything that would land outside it. */
internal class TarUnpacker(private val root: Path) {
    /** Unpacks every entry of [archive], calling [afterEntry] after each. */
    fun unpackTarXz(archive: InputStream, afterEntry: () -> Unit = {}) {
        TarArchiveInputStream(XZCompressorInputStream(archive.buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                unpack(entry, tar)
                afterEntry()
            }
        }
    }

    private fun unpack(entry: TarArchiveEntry, tar: InputStream) {
        val target = resolve(entry.name)
        when {
            entry.isDirectory -> {
                if (Files.isSymbolicLink(target)) throw IOException("Archive writes through a symlink: ${entry.name}")
                Files.createDirectories(target)
                setMode(target, entry.mode, directory = true)
            }
            entry.isSymbolicLink -> {
                Files.deleteIfExists(target)
                Files.createSymbolicLink(target, Paths.get(entry.linkName))
            }
            // Android doesn't let apps create hard links: copy instead.
            entry.isLink -> {
                Files.copy(resolve(entry.linkName), target, REPLACE_EXISTING, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
            }
            entry.isFile -> {
                Files.copy(tar, target, REPLACE_EXISTING)
                setMode(target, entry.mode, directory = false)
            }
            // Device nodes and FIFOs can't be created by apps; Debian gets /dev from the host.
            else -> {}
        }
    }

    /** Path inside [root] for [name]; never outside it, and never through a symlink. */
    fun resolve(name: String): Path {
        val path = root.resolve(name).normalize()
        if (!path.startsWith(root)) {
            throw IOException("Unsafe path in archive: $name")
        }
        var dir = root
        for (part in root.relativize(path.parent ?: root)) {
            dir = dir.resolve(part)
            if (Files.isSymbolicLink(dir)) throw IOException("Archive writes through a symlink: $name")
            if (!Files.exists(dir, NOFOLLOW_LINKS)) Files.createDirectory(dir)
        }
        return path
    }

    // We aren't really root, so the owner must keep read/write (and dir
    // search) access, or apt couldn't replace these files later.
    private fun setMode(path: Path, mode: Int, directory: Boolean) {
        val perms = PosixFilePermission.entries.filterIndexed { i, _ -> mode and (0x100 shr i) != 0 }.toMutableSet()
        perms += PosixFilePermission.OWNER_READ
        perms += PosixFilePermission.OWNER_WRITE
        if (directory) perms += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(path, perms)
    }
}
