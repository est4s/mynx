package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun `no file means the defaults`() {
        assertEquals(ParsedSettings(Settings(), emptyList()), parseSettings(""))
        assertEquals(Settings(fontSize = 12, font = "default", cursorStyle = "block", cursorBlink = false), Settings())
    }

    @Test
    fun `reads key = value lines, ignoring comments and blank lines`() {
        val parsed = parseSettings(
            """
            # my settings
            font-size = 16
            cursor-style=bar
              cursor-blink = on
            font = /usr/share/fonts/truetype/hack/Hack-Regular.ttf
            """.trimIndent()
        )

        assertEquals(emptyList(), parsed.problems)
        assertEquals(Settings(16, "/usr/share/fonts/truetype/hack/Hack-Regular.ttf", "bar", true), parsed.settings)
    }

    @Test
    fun `bad lines are reported and keep the default`() {
        val parsed = parseSettings(
            """
            font-size = huge
            font-size = 99
            cursor-style = blob
            cursor-blink = maybe
            colour = red
            nonsense
            font = Hack.ttf
            """.trimIndent()
        )

        assertEquals(Settings(), parsed.settings)
        assertEquals(
            listOf(
                "line 1: font-size must be a whole number from 6 to 40",
                "line 2: font-size must be a whole number from 6 to 40",
                "line 3: cursor-style must be one of: block, underline, bar",
                "line 4: cursor-blink must be one of: on, off",
                "line 5: unknown setting 'colour'",
                "line 6: expected key = value",
                "line 7: font must be 'default' or the full path of a .ttf or .otf file",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `every setting is described, with its default`() {
        assertEquals(
            listOf("font-size", "font", "cursor-style", "cursor-blink", "agent-notify", "agent-notify-after", "undo-keep",
                "android-clipboard", "android-share", "android-location", "share-folder"),
            SETTINGS.map { it.key },
        )
        SETTINGS.forEach { assertTrue(it.description.isNotBlank(), it.key) }
        assertEquals(listOf("block", "underline", "bar"), SETTINGS.single { it.key == "cursor-style" }.choices)
        assertEquals("12", SETTINGS.single { it.key == "font-size" }.default)
    }

    @Test
    fun `values reads each setting as text`() {
        assertEquals(
            mapOf(
                "font-size" to "14", "font" to "default", "cursor-style" to "block", "cursor-blink" to "on",
                "agent-notify" to "off", "agent-notify-after" to "45", "undo-keep" to "3",
                "android-clipboard" to "off", "android-share" to "off", "android-location" to "off",
                "share-folder" to "/srv/in",
            ),
            Settings(fontSize = 14, cursorBlink = true, agentNotify = false, agentNotifyAfter = 45, undoKeep = 3,
                androidClipboard = false, androidShare = false, androidLocation = false, shareFolder = "/srv/in").values(),
        )
    }

    @Test
    fun `setting a value replaces its line and keeps everything else`() {
        val text = "# mine\nfont-size = 12  \ncursor-style = bar\n"

        assertEquals("# mine\nfont-size = 16\ncursor-style = bar\n", setSetting(text, "font-size", "16").getOrThrow())
    }

    @Test
    fun `setting a value missing from the file adds it at the end`() {
        assertEquals("# mine\ncursor-blink = on\n", setSetting("# mine", "cursor-blink", "on").getOrThrow())
    }

    @Test
    fun `setting a value in a new file starts it with a header`() {
        val text = setSetting(null, "font-size", "14").getOrThrow()

        assertTrue(text.startsWith("# "), text)
        assertTrue(text.endsWith("\nfont-size = 14\n"), text)
        assertEquals(Settings(fontSize = 14), parseSettings(text).settings)
    }

    @Test
    fun `setting a duplicated key keeps only the new value`() {
        assertEquals("font-size = 8\n", setSetting("font-size = 12\nfont-size = 20\n", "font-size", "8").getOrThrow())
    }

    @Test
    fun `refuses unknown keys and bad values, with the reason`() {
        assertEquals("unknown setting 'colour' (pocket settings lists them)",
            setSetting("", "colour", "red").exceptionOrNull()?.message)
        assertEquals("font-size must be a whole number from 6 to 40",
            setSetting("", "font-size", "100").exceptionOrNull()?.message)
    }

    @Test
    fun `unsetting removes a setting's lines and keeps the rest`() {
        assertEquals("# mine\ncursor-style = bar\n",
            unsetSetting("# mine\nfont-size = 16\ncursor-style = bar\nfont-size = 8\n", "font-size").getOrThrow())
        assertEquals("font-size = 16\n", unsetSetting("font-size = 16\n", "cursor-blink").getOrThrow())
        assertEquals("unknown setting 'x' (pocket settings lists them)", unsetSetting("", "x").exceptionOrNull()?.message)
    }

    @Test
    fun `agent notifications are on by default, for turns of 30 seconds or more`() {
        assertEquals(true, Settings().agentNotify)
        assertEquals(30, Settings().agentNotifyAfter)
    }

    @Test
    fun `reads and checks the agent notification settings`() {
        val parsed = parseSettings("agent-notify = off\nagent-notify-after = 0\nagent-notify-after = soon\nagent-notify-after = 9999\n")

        assertEquals(Settings(agentNotify = false, agentNotifyAfter = 0), parsed.settings)
        assertEquals(
            listOf(
                "line 3: agent-notify-after must be a whole number of seconds from 0 to 3600",
                "line 4: agent-notify-after must be a whole number of seconds from 0 to 3600",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `undo keeps one step by default, up to 20`() {
        assertEquals(1, Settings().undoKeep)
        val parsed = parseSettings("undo-keep = 0\nundo-keep = 21\n")
        assertEquals(0, parsed.settings.undoKeep)
        assertEquals(listOf("line 2: undo-keep must be a whole number from 0 to 20"), parsed.problems)
    }

    @Test
    fun `clipboard access is on by default and can be turned off`() {
        assertTrue(Settings().androidClipboard)
        val parsed = parseSettings("android-clipboard = off\nandroid-clipboard = maybe\n")
        assertFalse(parsed.settings.androidClipboard)
        assertEquals(listOf("line 2: android-clipboard must be one of: on, off"), parsed.problems)
    }

    @Test
    fun `sharing is on by default and can be turned off`() {
        assertTrue(Settings().androidShare)
        val parsed = parseSettings("android-share = off\nandroid-share = yes\n")
        assertFalse(parsed.settings.androidShare)
        assertEquals(listOf("line 2: android-share must be one of: on, off"), parsed.problems)
    }

    @Test
    fun `location is on by default and can be turned off`() {
        assertTrue(Settings().androidLocation)
        val parsed = parseSettings("android-location = off\nandroid-location = gps\n")
        assertFalse(parsed.settings.androidLocation)
        assertEquals(listOf("line 2: android-location must be one of: on, off"), parsed.problems)
    }

    @Test
    fun `shared files go to ~ Shared unless another folder is set`() {
        assertEquals("~/Shared", Settings().shareFolder)
        val parsed = parseSettings("share-folder = /srv/in\nshare-folder = ~/a b\nshare-folder = Shared\nshare-folder = ~\n")
        assertEquals("~/a b", parsed.settings.shareFolder)
        val error = "share-folder must be a full path or start with ~/"
        assertEquals(listOf("line 3: $error", "line 4: $error"), parsed.problems)
    }
}
