package io.github.est4s.terminal.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

private const val RESOLV_CONF = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
private const val HOSTS = "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n"

/**
 * Unpacks the Debian rootfs (a `.tar.xz`) into `<baseDir>/debian`, once.
 *
 * It unpacks into `debian.partial` and renames that only when everything
 * succeeded, so an interrupted install never looks installed and is simply
 * redone. An installed Debian is never touched again.
 */
class RootfsInstaller(baseDir: File) {
    val rootfs = File(baseDir, "debian")
    private val partial = File(baseDir, "debian.partial").toPath()

    val isInstalled: Boolean get() = rootfs.isDirectory

    fun install(archive: InputStream, archiveSize: Long, onProgress: (Int) -> Unit = {}) {
        check(!isInstalled) { "Debian is already installed at $rootfs" }
        deleteTree(partial)
        Files.createDirectories(partial)

        val counted = CountingInputStream(archive)
        var percent = -1
        TarArchiveInputStream(XZCompressorInputStream(counted.buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                unpack(entry, tar)
                val now = (counted.count * 100 / archiveSize.coerceAtLeast(1)).toInt().coerceAtMost(99)
                if (now > percent) onProgress(now).also { percent = now }
            }
        }

        writeConfig("etc/resolv.conf", RESOLV_CONF)
        writeConfig("etc/hosts", HOSTS)
        Files.move(partial, rootfs.toPath(), ATOMIC_MOVE)
        onProgress(100)
    }

    private fun unpack(entry: TarArchiveEntry, tar: InputStream) {
        val target = resolve(entry.name)
        when {
            entry.isDirectory -> {
                if (Files.isSymbolicLink(target)) throw IOException("Rootfs archive writes through a symlink: ${entry.name}")
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

    /** Path inside the rootfs for [name]; never outside it, and never through a symlink. */
    private fun resolve(name: String): Path {
        val path = partial.resolve(name).normalize()
        if (!path.startsWith(partial)) {
            throw IOException("Unsafe path in rootfs archive: $name")
        }
        var dir = partial
        for (part in partial.relativize(path.parent ?: partial)) {
            dir = dir.resolve(part)
            if (Files.isSymbolicLink(dir)) throw IOException("Rootfs archive writes through a symlink: $name")
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

    // Replaces docker's empty placeholders; Android has no resolv.conf to share.
    private fun writeConfig(name: String, content: String) {
        val path = resolve(name)
        Files.deleteIfExists(path)
        Files.write(path, content.toByteArray())
    }
}

/** Deletes [root] and everything below it, removing symlinks without following them. */
internal fun deleteTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}
