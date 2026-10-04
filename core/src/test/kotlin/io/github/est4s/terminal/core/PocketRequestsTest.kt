package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PocketRequestsTest {
    private val base = createTempDirectory("requests").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val requests = PocketRequests(dir, home)

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `answers a check with the config problems`() {
        File(home, ".config/pocket-terminal").mkdirs()
        File(home, ".config/pocket-terminal/colors.properties").writeText("oops\n")
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertEquals(
            """{"ok":true,"problems":[{"file":"~/.config/pocket-terminal/colors.properties","problems":["line 1: expected key=value"]}]}""",
            File(dir, "a1.reply").readText(),
        )
    }

    @Test
    fun `a check with nothing wrong has no problems`() {
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertEquals("""{"ok":true,"problems":[]}""", File(dir, "a1.reply").readText())
    }

    @Test
    fun `removes the request once answered`() {
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertFalse(File(dir, "a1.req").exists())
    }

    @Test
    fun `says which requests it handled, so the app knows to reload`() {
        File(dir, "a1.req").writeText("check\n")
        File(dir, "b2.req").writeText("check\n")

        assertEquals(listOf("check", "check"), requests.processPending().map { it.name })
        assertEquals(emptyList(), requests.processPending())
    }

    @Test
    fun `answers an unknown request with an error`() {
        File(dir, "a1.req").writeText("make-coffee\n")

        requests.processPending()

        assertEquals("""{"ok":false,"error":"unknown request 'make-coffee'"}""", File(dir, "a1.reply").readText())
    }

    @Test
    fun `ignores files that aren't finished requests`() {
        File(dir, "a1.tmp").writeText("check\n")
        File(dir, "../escape.req").writeText("check\n")
        File(dir, "a1.reply").writeText("old")

        requests.processPending()

        assertTrue(File(dir, "a1.tmp").exists())
        assertEquals("old", File(dir, "a1.reply").readText())
    }

    @Test
    fun `escapes text in replies`() {
        File(home, ".config/pocket-terminal").mkdirs()
        File(home, ".config/pocket-terminal/colors.properties").writeText("\"quoted\\\"\n")
        File(dir, "a1.req").writeText("check\n")

        requests.processPending()

        assertTrue(File(dir, "a1.reply").readText().contains("""line 1: expected key=value"""))
        File(dir, "x.req").writeText("say \"hi\"\\\n")
        requests.processPending()
        assertEquals("""{"ok":false,"error":"unknown request 'say \"hi\"\\'"}""", File(dir, "x.reply").readText())
    }

    // --- arguments ---------------------------------------------------------

    @Test
    fun `passes the lines after the name as arguments`() {
        File(dir, "a1.req").writeText("set\nfont-size\n14\n")

        assertEquals(listOf(PocketRequest("set", listOf("font-size", "14"))), requests.processPending())
    }

    @Test
    fun `missing arguments are an error, not a crash`() {
        File(dir, "a1.req").writeText("set\nfont-size\n")

        requests.processPending()

        assertEquals("""{"ok":false,"error":"set needs 2 arguments"}""", reply("a1"))
    }

    // --- settings ----------------------------------------------------------

    @Test
    fun `lists every setting with its value, default, description and choices`() {
        config("settings.conf", "font-size = 16\n")

        val reply = ask("settings")

        assertTrue(reply.startsWith("""{"ok":true,"settings":[{"key":"font-size","value":"16","default":"12","description":"""), reply)
        assertTrue(""""key":"cursor-style","value":"block","default":"block",""" in reply, reply)
        assertTrue(""""choices":["block","underline","bar"]""" in reply, reply)
        assertTrue(""""key":"font","value":"default","default":"default","description":""" in reply, reply)
        assertTrue(reply.endsWith(""""problems":[]}"""), reply)
    }

    @Test
    fun `set writes the settings file`() {
        assertEquals("""{"ok":true,"key":"font-size","value":"14"}""", ask("set", "font-size", "14"))

        assertEquals(Settings(fontSize = 14), loadSettings(File(home, "$CONFIG_DIR/settings.conf")).settings)
    }

    @Test
    fun `set refuses bad values and leaves the file alone`() {
        config("settings.conf", "font-size = 16\n")

        assertEquals("""{"ok":false,"error":"font-size must be a whole number from 6 to 40"}""", ask("set", "font-size", "99"))
        assertEquals("font-size = 16\n", File(home, "$CONFIG_DIR/settings.conf").readText())
    }

    @Test
    fun `check covers the settings file`() {
        config("settings.conf", "font-size = big\n")

        assertTrue("settings.conf" in ask("check") && "font-size must be" in ask("check"))
    }

    @Test
    fun `reset puts one setting back to its default, keeping the others`() {
        config("settings.conf", "# mine\nfont-size = 16\ncursor-style = bar\n")

        assertEquals("""{"ok":true,"key":"font-size","value":"12"}""", ask("reset", "font-size"))
        assertEquals("# mine\ncursor-style = bar\n", File(home, "$CONFIG_DIR/settings.conf").readText())
        assertEquals("""{"ok":false,"error":"unknown setting 'colour' (pocket settings lists them)"}""", ask("reset", "colour"))
    }

    @Test
    fun `reset all puts every setting back`() {
        config("settings.conf", "font-size = 16\n")

        assertEquals("""{"ok":true,"key":"all"}""", ask("reset", "all"))
        assertFalse(File(home, "$CONFIG_DIR/settings.conf").exists())
        assertEquals("""{"ok":true,"key":"all"}""", ask("reset", "all"))
    }

    // --- themes ------------------------------------------------------------

    @Test
    fun `lists built-in and user themes, and which one is in use`() {
        config("themes/mine.colors.properties", "background=#000000\n")
        ask("theme-set", "dracula")

        val reply = ask("themes")

        assertTrue(reply.startsWith("""{"ok":true,"current":"dracula","themes":[{"name":"neon","source":"built-in"},"""), reply)
        assertTrue("""{"name":"mine","source":"~/.config/pocket-terminal/themes/mine.colors.properties"}""" in reply, reply)
    }

    @Test
    fun `no current theme when the colours file wasn't set from one`() {
        config("colors.properties", "background=#000000\n")

        assertTrue(ask("themes").startsWith("""{"ok":true,"current":null,"""))
    }

    @Test
    fun `theme-set writes the theme into the colours file, marked with its name`() {
        ask("theme-set", "nord")

        val colors = File(home, "$CONFIG_DIR/colors.properties").readText()
        assertTrue(colors.startsWith("# theme: nord\n"), colors)
        assertEquals(builtInThemeText("nord")!!.let { parseColorScheme(it, NEON).scheme }, loadColorScheme(File(home, "$CONFIG_DIR/colors.properties")).scheme)
    }

    @Test
    fun `a user theme wins over a built-in one with the same name`() {
        config("themes/nord.colors.properties", "background=#123456\n")

        ask("theme-set", "nord")

        assertEquals(0xff123456.toInt(), loadColorScheme(File(home, "$CONFIG_DIR/colors.properties")).scheme.background)
    }

    @Test
    fun `theme-show and theme-set refuse unknown themes`() {
        assertEquals("""{"ok":false,"error":"no theme 'nope' (pocket theme list shows them)"}""", ask("theme-set", "nope"))
        assertEquals("""{"ok":false,"error":"no theme 'nope' (pocket theme list shows them)"}""", ask("theme-show", "nope"))
        assertEquals("""{"ok":false,"error":"no theme '../x' (pocket theme list shows them)"}""", ask("theme-show", "../x"))
    }

    @Test
    fun `theme-show gives a theme's file`() {
        assertEquals("""{"ok":true,"name":"neon","source":"built-in","text":${json(builtInThemeText("neon")!!)}}""", ask("theme-show", "neon"))
    }

    @Test
    fun `preview-colors checks the colours it's given`() {
        assertEquals("""{"ok":true,"problems":["line 2: expected key=value"]}""", ask("preview-colors", "background=#000000", "oops"))
        assertEquals("""{"ok":true}""", ask("preview-end"))
    }

    @Test
    fun `check covers user themes`() {
        config("themes/mine.colors.properties", "oops\n")

        assertTrue("themes/mine.colors.properties" in ask("check"))
    }

    @Test
    fun `theme-reset goes back to the default theme`() {
        ask("theme-set", "nord")

        assertEquals("""{"ok":true,"name":"neon"}""", ask("theme-reset"))
        assertFalse(File(home, "$CONFIG_DIR/colors.properties").exists())
        assertTrue(ask("themes").startsWith("""{"ok":true,"current":"neon","""))
    }

    // --- key bars ----------------------------------------------------------

    @Test
    fun `lists built-in and user key bars`() {
        config("keybars/nnn.conf", "Quit = q\n")
        config("keybars/htop.conf", "Quit = q\n")

        val reply = ask("keybars")

        assertTrue("""{"name":"htop","builtIn":false,"file":"~/.config/pocket-terminal/keybars/htop.conf"}""" in reply, reply)
        assertTrue("""{"name":"nnn","builtIn":true,"file":"~/.config/pocket-terminal/keybars/nnn.conf"}""" in reply, reply)
        assertTrue("""{"name":"shell","builtIn":true,"file":null}""" in reply, reply)
        assertTrue(reply.indexOf("\"htop\"") < reply.indexOf("\"nnn\""), reply)
    }

    @Test
    fun `keybar-show gives the bar in use, the user's copy first`() {
        assertEquals("""{"ok":true,"name":"nnn","file":null,"text":${json(builtInKeyBarText("nnn")!!)}}""", ask("keybar-show", "nnn"))
        config("keybars/nnn.conf", "Quit = q\n")
        assertEquals("""{"ok":true,"name":"nnn","file":"~/.config/pocket-terminal/keybars/nnn.conf","text":"Quit = q\n"}""", ask("keybar-show", "nnn"))
        assertEquals("""{"ok":false,"error":"no key bar 'nope' (pocket keybar list shows them)"}""", ask("keybar-show", "nope"))
    }

    @Test
    fun `keybar-edit copies a built-in bar for the user to edit, once`() {
        assertEquals("""{"ok":true,"file":"~/.config/pocket-terminal/keybars/nnn.conf"}""", ask("keybar-edit", "nnn"))
        val copy = File(home, "$CONFIG_DIR/keybars/nnn.conf")
        assertEquals(builtInKeyBarText("nnn"), copy.readText())

        copy.writeText("Quit = q\n")
        ask("keybar-edit", "nnn")
        assertEquals("Quit = q\n", copy.readText())
    }

    @Test
    fun `keybar-edit starts a new bar from a short example`() {
        ask("keybar-edit", "htop")

        val text = File(home, "$CONFIG_DIR/keybars/htop.conf").readText()
        assertTrue(text.startsWith("# "), text)
        assertEquals(emptyList(), parseKeyBar(text).problems)
        assertEquals("""{"ok":false,"error":"'my bar' can't be a bar name: use letters, digits, - and _"}""", ask("keybar-edit", "my bar"))
    }

    @Test
    fun `keybar-reset removes the user's copy of a built-in bar`() {
        config("keybars/nnn.conf", "Quit = q\n")

        assertEquals("""{"ok":true}""", ask("keybar-reset", "nnn"))
        assertFalse(File(home, "$CONFIG_DIR/keybars/nnn.conf").exists())
        assertEquals("""{"ok":false,"error":"nnn is already the built-in bar"}""", ask("keybar-reset", "nnn"))
    }

    @Test
    fun `keybar-reset won't delete a bar that has no built-in`() {
        config("keybars/htop.conf", "Quit = q\n")

        assertEquals(
            """{"ok":false,"error":"htop has no built-in bar to go back to; delete ~/.config/pocket-terminal/keybars/htop.conf to remove it"}""",
            ask("keybar-reset", "htop"),
        )
        assertTrue(File(home, "$CONFIG_DIR/keybars/htop.conf").exists())
    }

    private fun config(path: String, text: String) =
        File(home, "$CONFIG_DIR/$path").apply { parentFile.mkdirs() }.writeText(text)

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return reply("q")
    }

    private fun reply(id: String) = File(dir, "$id.reply").readText()
}
