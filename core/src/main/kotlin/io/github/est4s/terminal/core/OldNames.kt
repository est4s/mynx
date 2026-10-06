package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths

// Before the rename to PC-26 (2026-10-06) these had other names.
private val RENAMED_DIRS = listOf(
    ".config/pocket-terminal" to ".config/pc26",
    ".local/state/pocket-terminal" to ".local/state/pc26",
)
private const val OLD_EDITORS_BAR = "keybars/pocket-edit.conf"
private const val EDITORS_BAR = "keybars/pc26-edit.conf"

/**
 * Moves root's config and state folders in [rootfs] from their names
 * before the rename to the new ones, leaving the old names as links so
 * the user's own scripts keep working. A folder already under the new
 * name wins: then both are left alone. Safe to run at every start; each
 * step is tried even if one before it failed, and the first failure is
 * thrown at the end.
 */
fun migrateOldNames(rootfs: File) {
    val home = File(rootfs, "root")
    val failures = buildList {
        for ((old, new) in RENAMED_DIRS) {
            runCatching { moveAndLink(File(home, old), File(home, new)) }.exceptionOrNull()?.let(::add)
        }
        runCatching {
            val config = File(home, RENAMED_DIRS.first().second)
            val oldBar = File(config, OLD_EDITORS_BAR)
            val bar = File(config, EDITORS_BAR)
            if (oldBar.isFile && !bar.exists()) Files.move(oldBar.toPath(), bar.toPath())
        }.exceptionOrNull()?.let(::add)
    }
    failures.firstOrNull()?.let { throw it }
}

private fun moveAndLink(old: File, new: File) {
    val oldPath = old.toPath()
    if (Files.isSymbolicLink(oldPath) || !old.isDirectory) return
    if (Files.exists(new.toPath(), NOFOLLOW_LINKS)) return
    Files.move(oldPath, new.toPath())
    // Relative: the same inside Debian and on the host.
    Files.createSymbolicLink(oldPath, Paths.get(new.name))
}
