package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OldNamesTest {
    private val rootfs = createTempDirectory("old-names-test").toFile()
    private val home = File(rootfs, "root")
    private val oldConfig = File(home, ".config/pocket-terminal")
    private val newConfig = File(home, ".config/pc26")
    private val oldState = File(home, ".local/state/pocket-terminal")
    private val newState = File(home, ".local/state/pc26")

    @AfterTest
    fun cleanup() {
        rootfs.deleteRecursively()
    }

    @Test
    fun `moves the old config folder to the new name and links the old name to it`() {
        write(oldConfig, "settings.conf", "font-size = 14\n")

        migrateOldNames(rootfs)

        assertEquals("font-size = 14\n", File(newConfig, "settings.conf").readText())
        assertTrue(Files.isSymbolicLink(oldConfig.toPath()))
        // Relative, so it points to the same place inside Debian and on the host.
        assertEquals("pc26", Files.readSymbolicLink(oldConfig.toPath()).toString())
        assertEquals("font-size = 14\n", File(oldConfig, "settings.conf").readText())
    }

    @Test
    fun `moves the old state folder too, with undo copies and the menu state`() {
        write(oldState, "menu", "welcome-shown\n")
        write(oldState, "undo/last/settings.conf", "x\n")

        migrateOldNames(rootfs)

        assertEquals("welcome-shown\n", File(newState, "menu").readText())
        assertEquals("x\n", File(newState, "undo/last/settings.conf").readText())
        assertEquals("pc26", Files.readSymbolicLink(oldState.toPath()).toString())
    }

    @Test
    fun `renames the settings editors' key bar`() {
        write(oldConfig, "keybars/pocket-edit.conf", "Quit = q\n")

        migrateOldNames(rootfs)

        assertEquals("Quit = q\n", File(newConfig, "keybars/pc26-edit.conf").readText())
        assertFalse(File(newConfig, "keybars/pocket-edit.conf").exists())
    }

    @Test
    fun `keeps a key bar already under the new name`() {
        write(oldConfig, "keybars/pocket-edit.conf", "old\n")
        write(oldConfig, "keybars/pc26-edit.conf", "new\n")

        migrateOldNames(rootfs)

        assertEquals("new\n", File(newConfig, "keybars/pc26-edit.conf").readText())
        assertEquals("old\n", File(newConfig, "keybars/pocket-edit.conf").readText())
    }

    @Test
    fun `leaves both alone when both names exist`() {
        write(oldConfig, "settings.conf", "old\n")
        write(newConfig, "settings.conf", "new\n")

        migrateOldNames(rootfs)

        assertFalse(Files.isSymbolicLink(oldConfig.toPath()))
        assertEquals("old\n", File(oldConfig, "settings.conf").readText())
        assertEquals("new\n", File(newConfig, "settings.conf").readText())
    }

    @Test
    fun `does nothing on a Debian with only the new names`() {
        write(newConfig, "settings.conf", "new\n")

        migrateOldNames(rootfs)

        assertFalse(oldConfig.exists() || Files.isSymbolicLink(oldConfig.toPath()))
        assertFalse(oldState.exists() || Files.isSymbolicLink(oldState.toPath()))
        assertEquals("new\n", File(newConfig, "settings.conf").readText())
    }

    @Test
    fun `can run again and again`() {
        write(oldConfig, "settings.conf", "font-size = 14\n")
        write(oldState, "menu", "m\n")

        repeat(3) { migrateOldNames(rootfs) }

        assertEquals("font-size = 14\n", File(newConfig, "settings.conf").readText())
        assertEquals("pc26", Files.readSymbolicLink(oldConfig.toPath()).toString())
        assertEquals("m\n", File(newState, "menu").readText())
    }

    @Test
    fun `still moves the state when the config can't be moved`() {
        write(oldState, "menu", "m\n")
        // A file where the config folder would be: nothing to move.
        File(home, ".config").mkdirs()
        oldConfig.writeText("not a folder")

        runCatching { migrateOldNames(rootfs) }

        assertEquals("m\n", File(newState, "menu").readText())
    }

    private fun write(dir: File, path: String, text: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
    }
}
