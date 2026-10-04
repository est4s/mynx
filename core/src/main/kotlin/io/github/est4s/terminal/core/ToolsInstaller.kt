package io.github.est4s.terminal.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Where Debian sees the app's tools (`pocket`, editors, themes). */
const val TOOLS_MOUNT = "/opt/pocket-terminal"

/**
 * Keeps the app's own tools (a `.tar.xz` in the APK) in `<baseDir>/tools`,
 * which Debian sees at [TOOLS_MOUNT]. Unlike the rootfs these belong to the
 * app, so every new app version replaces them whole: that's how fixes reach
 * an installed Debian without touching the user's files.
 */
class ToolsInstaller(baseDir: File) {
    val tools = File(baseDir, "tools")
    private val partial = File(baseDir, "tools.partial").toPath()
    private val old = File(baseDir, "tools.old").toPath()
    private val versionFile = File(tools, ".version")

    /** Installs the tools from [open] unless [version] is already installed. */
    fun update(version: String, open: () -> InputStream) {
        if (runCatching { versionFile.readText().trim() }.getOrNull() == version) return
        deleteTree(partial)
        Files.createDirectories(partial)
        open().use { TarUnpacker(partial).unpackTarXz(it) }
        Files.write(partial.resolve(".version"), "$version\n".toByteArray())
        // The old tools stay until the new ones are complete.
        deleteTree(old)
        if (tools.exists()) Files.move(tools.toPath(), old, ATOMIC_MOVE)
        Files.move(partial, tools.toPath(), ATOMIC_MOVE)
        deleteTree(old)
    }
}
