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
    fun `the reported bar names fall back to the shell's`() {
        assertEquals(listOf("nnn"), keyBarNames("nnn\n"))
        assertEquals(listOf("my-tool_2"), keyBarNames("my-tool_2"))
        assertEquals(listOf("shell"), keyBarNames(""))
        assertEquals(listOf("shell"), keyBarNames(null))
        assertEquals(listOf("shell"), keyBarNames("../../etc/passwd"))
        assertEquals(listOf("shell"), keyBarNames("two words"))
    }

    @Test
    fun `a report can list fallbacks, and bad names are dropped`() {
        assertEquals(listOf("my-game", "game"), keyBarNames("my-game,game"))
        assertEquals(listOf("game"), keyBarNames("../x, game"))
    }

    @Test
    fun `the first bar that exists wins`() {
        File(dir, "mine.conf").writeText("A = a\n")

        assertEquals("mine", loadKeyBar(listOf("nope", "mine", "game"), dir).name)
        assertEquals("game", loadKeyBar(listOf("nope", "game"), dir).name)
        assertEquals("shell", loadKeyBar(listOf("nope", "nada"), dir).name)
    }

    @Test
    fun `arrows, paging and deleting keys repeat when held`() {
        val parsed = parseKeyBar(
            listOf("Up", "Down", "Left", "Right", "PgUp", "PgDn", "Backspace", "Delete")
                .joinToString("\n") { "$it = $it" }
        )

        assertTrue(parsed.buttons.all { it.repeat })
    }

    @Test
    fun `other buttons repeat only when asked`() {
        val parsed = parseKeyBar("Open = l\nBrake = Down Repeat\nFire = repeat Space\nTwo = Up Up\nGo = \"go\" Enter")

        assertEquals(emptyList(), parsed.problems)
        assertEquals(listOf(false, true, true, false, false), parsed.buttons.map { it.repeat })
        assertEquals(listOf(Key("Down")), parsed.buttons[1].strokes)
        assertEquals(listOf(Key("Space")), parsed.buttons[2].strokes)
    }

    @Test
    fun `Repeat alone is not a key`() {
        assertEquals(listOf("line 1: no keys"), parseKeyBar("X = Repeat").problems)
    }

    @Test
    fun `built-in bars are clean and have the agreed buttons`() {
        val labels = { name: String ->
            val loaded = loadKeyBar(name, dir)
            assertEquals(emptyList(), loaded.problems, name)
            loaded.buttons.map { it.label }
        }

        // Laid out in two rows, first half on top (see keyBarPages).
        assertEquals(listOf("Esc", "Tab", "Ctrl", "Files", "Menu", "←", "↓", "↑", "→"), labels("shell"))
        assertEquals(
            listOf("↑", "↓", "Open", "Back", "Select", "Search", "Copy", "Move", "Rename", "Delete", "Places", "Quit"),
            labels("nnn"),
        )
        assertEquals(listOf("↑", "↓", "Select", "Back", "Quit"), labels("menu"))
        assertEquals(
            listOf("←", "↓", "↑", "→", "Wait", "Explore", "Potion", "Scroll", "Bomb", "Stairs", "Info", "Quit",
                "Help", "Continue", "New", "Scores", "Rest"),
            labels("neon-rogue"),
        )
        assertEquals(listOf("←", "Brake", "Nitro", "→", "Pause", "Again", "Quit"), labels("neon-drive"))
        assertEquals(listOf("Flap", "Quit"), labels("neon-flap"))
        assertEquals(listOf("←", "↓", "↑", "→", "Space", "Enter", "Esc", "y", "n", "q"), labels("game"))
    }

    @Test
    fun `game bars repeat the keys you hold to move`() {
        fun repeating(name: String) = loadKeyBar(name, dir).buttons.filter { it.repeat }.map { it.label }

        assertEquals(listOf("←", "↓", "↑", "→"), repeating("neon-rogue"))
        assertEquals(listOf("←", "Brake", "Nitro", "→"), repeating("neon-drive"))
        assertEquals(emptyList(), repeating("neon-flap"))
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
    fun `buttons split into two rows, first half on top`() {
        assertEquals(listOf(listOf(listOf(0, 1, 2), listOf(3, 4))), keyBarPages(count = 5, perRow = 6))
        assertEquals(listOf(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 10, 11))), keyBarPages(12, 6))
    }

    @Test
    fun `a single button still gets two rows, the second empty`() {
        assertEquals(listOf(listOf(listOf(0), emptyList())), keyBarPages(1, 6))
    }

    @Test
    fun `buttons that don't fit in two rows go on more pages`() {
        assertEquals(
            listOf(
                listOf(listOf(0, 1, 2), listOf(3, 4, 5)),
                listOf(listOf(6, 7), listOf(8)),
            ),
            keyBarPages(count = 9, perRow = 3),
        )
    }

    @Test
    fun `no buttons, no pages, and at least one per row`() {
        assertEquals(emptyList(), keyBarPages(0, 6))
        assertEquals(listOf(listOf(listOf(0), listOf(1)), listOf(listOf(2), emptyList())), keyBarPages(3, 0))
    }

    @Test
    fun `built-in bar files explain the format`() {
        assertTrue(builtInKeyBarText("shell")!!.startsWith("#"))
    }
}
