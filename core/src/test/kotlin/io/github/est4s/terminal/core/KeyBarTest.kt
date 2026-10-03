package io.github.est4s.terminal.core

import io.github.est4s.terminal.core.KeyStroke.CtrlLatch
import io.github.est4s.terminal.core.KeyStroke.Key
import io.github.est4s.terminal.core.KeyStroke.Text
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyBarTest {
    private val dir = createTempDirectory("keybars").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `reads one button per line`() {
        val parsed = parseKeyBar(
            """
            # nnn
            Open   = l
            Select = Space

            Rename = Ctrl+R
            """.trimIndent()
        )

        assertEquals(emptyList(), parsed.problems)
        assertEquals(
            listOf(
                KeyButton("Open", listOf(Key("l"))),
                KeyButton("Select", listOf(Key("Space"))),
                KeyButton("Rename", listOf(Key("R", ctrl = true))),
            ),
            parsed.buttons,
        )
    }

    @Test
    fun `key names ignore case and come out in their usual spelling`() {
        val parsed = parseKeyBar("a = enter ESC pgup f12 ctrl+alt+left")

        assertEquals(
            listOf(Key("Enter"), Key("Esc"), Key("PgUp"), Key("F12"), Key("Left", ctrl = true, alt = true)),
            parsed.buttons.single().strokes,
        )
    }

    @Test
    fun `knows the keys a terminal needs`() {
        val names = "Enter Esc Tab Space Backspace Delete Insert Up Down Left Right Home End PgUp PgDn " +
            (1..12).joinToString(" ") { "F$it" }
        val parsed = parseKeyBar("all = $names")

        assertEquals(emptyList(), parsed.problems)
        assertEquals(names.split(" ").map { Key(it) }, parsed.buttons.single().strokes)
    }

    @Test
    fun `quoted text is typed as is and runs before or after keys`() {
        val parsed = parseKeyBar("""Files = "files" Enter""" + "\n" + """Say = "a \"b\" c\\" """)

        assertEquals(listOf(Text("files"), Key("Enter")), parsed.buttons[0].strokes)
        assertEquals(listOf(Text("a \"b\" c\\")), parsed.buttons[1].strokes)
    }

    @Test
    fun `single characters are keys, including = and +`() {
        val parsed = parseKeyBar("Launch = =\nAdd = +\nSearch = /\nArrow = →")

        assertEquals(emptyList(), parsed.problems)
        assertEquals(listOf("=", "+", "/", "→"), parsed.buttons.map { (it.strokes.single() as Key).key })
    }

    @Test
    fun `Ctrl alone is the sticky modifier`() {
        assertEquals(listOf(CtrlLatch), parseKeyBar("Ctrl = Ctrl").buttons.single().strokes)
    }

    @Test
    fun `labels can be symbols`() {
        assertEquals("←", parseKeyBar("← = Left").buttons.single().label)
    }

    @Test
    fun `reports bad lines with their line numbers and skips them`() {
        val parsed = parseKeyBar(
            """
            Open = l
            no equals sign
             = l
            Empty =
            Bad = Foo
            Quote = "open
            Mixed = Ctrl l
            Combo = Ctrl+Foo
            Back = h
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "line 2: expected label = keys",
                "line 3: no label",
                "line 4: no keys",
                "line 5: unknown key 'Foo'",
                "line 6: missing closing quote",
                "line 7: Ctrl on its own must be the only key",
                "line 8: unknown key 'Ctrl+Foo'",
            ),
            parsed.problems,
        )
        assertEquals(listOf("Open", "Back"), parsed.buttons.map { it.label })
    }

    @Test
    fun `the reported bar name falls back to the shell's`() {
        assertEquals("nnn", keyBarName("nnn\n"))
        assertEquals("my-tool_2", keyBarName("my-tool_2"))
        assertEquals("shell", keyBarName(""))
        assertEquals("shell", keyBarName(null))
        assertEquals("shell", keyBarName("../../etc/passwd"))
        assertEquals("shell", keyBarName("two words"))
    }

    @Test
    fun `built-in bars are clean and have the agreed buttons`() {
        val labels = { name: String ->
            val loaded = loadKeyBar(name, dir)
            assertEquals(emptyList(), loaded.problems, name)
            loaded.buttons.map { it.label }
        }

        assertEquals(listOf("Esc", "Tab", "Ctrl", "←", "↓", "↑", "→", "Files", "Menu"), labels("shell"))
        assertEquals(
            listOf("Open", "Back", "Select", "Copy", "Move", "Rename", "Delete", "Search", "Places", "Quit"),
            labels("nnn"),
        )
        assertEquals(listOf("↑", "↓", "Select", "Back", "Quit"), labels("menu"))
    }

    @Test
    fun `a user file replaces a built-in bar`() {
        File(dir, "nnn.conf").writeText("Open = Enter\n")

        val loaded = loadKeyBar("nnn", dir)

        assertEquals(listOf(KeyButton("Open", listOf(Key("Enter")))), loaded.buttons)
        assertEquals(File(dir, "nnn.conf").path, loaded.source)
    }

    @Test
    fun `a user file adds a new bar, with its problems`() {
        File(dir, "htop.conf").writeText("Quit = q\noops\n")

        val loaded = loadKeyBar("htop", dir)

        assertEquals(listOf("Quit"), loaded.buttons.map { it.label })
        assertEquals(listOf("line 2: expected label = keys"), loaded.problems)
    }

    @Test
    fun `an unknown bar shows the shell's`() {
        val loaded = loadKeyBar("nothing-here", dir)

        assertEquals(loadKeyBar("shell", dir).buttons, loaded.buttons)
        assertEquals(emptyList(), loaded.problems)
    }

    @Test
    fun `built-in bar files explain the format`() {
        assertTrue(builtInKeyBarText("shell")!!.startsWith("#"))
    }
}
