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

// Debian's /etc/profile sets root's PATH from scratch, dropping what the
// app passed in, and then sources /etc/profile.d.
private val TOOLS_PROFILE = """
    |# Written by the app at every start: puts its tools (pocket, menu,
    |# the editors) first on the PATH. Changes here are overwritten.
    |case ":${'$'}PATH:" in
    |    *:$TOOLS_MOUNT/bin:*) ;;
    |    *) PATH="$TOOLS_MOUNT/bin:${'$'}PATH" ;;
    |esac
    |""".trimMargin()

/**
 * Writes `/etc/profile.d/pocket-terminal.sh` into [rootfs] unless it's
 * already current. Apart from runtime files in /tmp, the only file the
 * app writes in an installed Debian, and it's the app's own.
 */
fun writeToolsProfile(rootfs: File) {
    val script = File(rootfs, "etc/profile.d/pocket-terminal.sh")
    if (runCatching { script.readText() }.getOrNull() == TOOLS_PROFILE) return
    script.parentFile.mkdirs()
    script.writeText(TOOLS_PROFILE)
}
