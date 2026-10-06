package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigCheckTest {
    private val rootfs = createTempDirectory("rootfs").toFile()
    private val home = File(rootfs, "root")
    private val config = File(home, ".config/pc26").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        rootfs.deleteRecursively()
    }

    @Test
    fun `no config files, no problems`() {
        assertEquals(emptyList(), checkConfig(home))
    }

    @Test
    fun `good files have no problems`() {
        File(config, "colors.properties").writeText("background=#000000\n")
        write("keybars/nnn.conf", "Quit = q\n")

        assertEquals(emptyList(), checkConfig(home))
    }

    @Test
    fun `reports the colours file's problems by its Debian path`() {
        File(config, "colors.properties").writeText("background=black\n")

        assertEquals(
            listOf(ConfigProblems("~/.config/pc26/colors.properties",
                listOf("line 1: 'black' is not a #rrggbb colour"))),
            checkConfig(home),
        )
    }

    @Test
    fun `reports each key bar file's problems, in name order`() {
        write("keybars/shell.conf", "oops\n")
        write("keybars/nnn.conf", "Quit = q\nbad\n")

        assertEquals(
            listOf("~/.config/pc26/keybars/nnn.conf", "~/.config/pc26/keybars/shell.conf"),
            checkConfig(home).map { it.file },
        )
        assertEquals(1, checkConfig(home).first().problems.size)
    }

    @Test
    fun `a key bar file whose name can't be a bar is a problem`() {
        write("keybars/my bar.conf", "Quit = q\n")

        assertEquals(
            listOf(ConfigProblems("~/.config/pc26/keybars/my bar.conf",
                listOf("not a usable bar name: use letters, digits, - and _"))),
            checkConfig(home),
        )
    }

    @Test
    fun `ignores other files in the key bar folder`() {
        write("keybars/notes.txt", "anything")
        write("keybars/nnn.conf~", "editor backup")

        assertEquals(emptyList(), checkConfig(home))
    }

    @Test
    fun `a font that isn't there is a problem`() {
        File(config, "settings.conf").writeText("font = /usr/share/fonts/Hack.ttf\n")

        assertEquals(
            listOf(ConfigProblems("~/.config/pc26/settings.conf", listOf("font: no such file /usr/share/fonts/Hack.ttf"))),
            checkConfig(home),
        )
        File(home.parentFile, "usr/share/fonts").mkdirs()
        File(home.parentFile, "usr/share/fonts/Hack.ttf").writeText("")
        assertEquals(emptyList(), checkConfig(home))
    }

    private fun write(path: String, text: String) =
        File(config, path).apply { parentFile.mkdirs() }.writeText(text)
}
