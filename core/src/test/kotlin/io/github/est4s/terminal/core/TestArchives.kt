package io.github.est4s.terminal.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream

/** Builds a small `.tar.xz` like the one in the APK. */
internal fun archive(build: TarBuilder.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    TarArchiveOutputStream(XZCompressorOutputStream(bytes)).use { TarBuilder(it).build() }
    return bytes.toByteArray()
}

internal class TarBuilder(private val tar: TarArchiveOutputStream) {
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
