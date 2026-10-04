package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigCheckTest {
    private val home = createTempDirectory("home").toFile()
    private val config = File(home, ".config/pocket-terminal").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
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
            listOf(ConfigProblems("~/.config/pocket-terminal/colors.properties",
                listOf("line 1: 'black' is not a #rrggbb colour"))),
            checkConfig(home),
        )
    }

    @Test
    fun `reports each key bar file's problems, in name order`() {
        write("keybars/shell.conf", "oops\n")
        write("keybars/nnn.conf", "Quit = q\nbad\n")

        assertEquals(
            listOf("~/.config/pocket-terminal/keybars/nnn.conf", "~/.config/pocket-terminal/keybars/shell.conf"),
            checkConfig(home).map { it.file },
        )
        assertEquals(1, checkConfig(home).first().problems.size)
    }

    @Test
    fun `a key bar file whose name can't be a bar is a problem`() {
        write("keybars/my bar.conf", "Quit = q\n")

        assertEquals(
            listOf(ConfigProblems("~/.config/pocket-terminal/keybars/my bar.conf",
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

    private fun write(path: String, text: String) =
        File(config, path).apply { parentFile.mkdirs() }.writeText(text)
}
