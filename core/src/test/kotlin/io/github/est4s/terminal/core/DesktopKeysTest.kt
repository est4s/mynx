package io.github.est4s.terminal.core

import io.github.est4s.terminal.core.KeyStroke.AltLatch
import io.github.est4s.terminal.core.KeyStroke.CtrlLatch
import io.github.est4s.terminal.core.KeyStroke.SuperLatch
import io.github.est4s.terminal.core.X11Input.Button
import io.github.est4s.terminal.core.X11Input.Key
import io.github.est4s.terminal.core.X11Input.Scroll
import io.github.est4s.terminal.core.X11Input.Unicode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Android's key codes (KeyEvent.KEYCODE_*) and Linux's (input-event-codes.h).
private const val KEYCODE_BACK = 4
private const val KEYCODE_VOLUME_UP = 24
private const val KEYCODE_A = 29
private const val KEYCODE_C = 31
private const val KEYCODE_ENTER = 66
private const val KEYCODE_DEL = 67
private const val KEYCODE_CTRL_LEFT = 113
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_UNKNOWN = 0
private const val KEY_ESC = 1
private const val KEY_BACKSPACE = 14
private const val KEY_TAB = 15
private const val KEY_ENTER = 28
private const val KEY_LEFTCTRL = 29
private const val KEY_A = 30
private const val KEY_LEFTSHIFT = 42
private const val KEY_C = 46
private const val KEY_LEFTALT = 56
private const val KEY_UP = 103
private const val KEY_LEFT = 105
private const val KEY_LEFTMETA = 125

private fun tap(code: Int) = listOf(Key(code, true), Key(code, false))
private fun button(label: String, vararg strokes: KeyStroke) = KeyButton(label, strokes.toList())

class DesktopKeysTest {
    private val keys = DesktopKeyboard()

    @Test
    fun `a hardware key goes by its scan code, which is the Linux key code`() {
        assertEquals(listOf(Key(KEY_A, true)), keys.key(KEYCODE_A, scanCode = KEY_A, unicode = 'a'.code, down = true))
        assertEquals(listOf(Key(KEY_A, false)), keys.key(KEYCODE_A, scanCode = KEY_A, unicode = 'a'.code, down = false))
    }

    @Test
    fun `a soft keyboard's keys without a scan code go by their Android key`() {
        assertEquals(listOf(Key(KEY_ENTER, true)), keys.key(KEYCODE_ENTER, 0, '\n'.code, down = true))
        assertEquals(listOf(Key(KEY_BACKSPACE, false)), keys.key(KEYCODE_DEL, 0, 0, down = false))
        assertEquals(listOf(Key(KEY_LEFT, true)), keys.key(KEYCODE_DPAD_LEFT, 0, 0, down = true))
        assertEquals(listOf(Key(KEY_LEFTCTRL, true)), keys.key(KEYCODE_CTRL_LEFT, 0, 0, down = true))
    }

    @Test
    fun `other characters are typed as characters, once`() {
        assertEquals(listOf(Unicode('é'.code)), keys.key(KEYCODE_UNKNOWN, 0, 'é'.code, down = true))
        assertEquals(emptyList(), keys.key(KEYCODE_UNKNOWN, 0, 'é'.code, down = false))
    }

    @Test
    fun `a letter with no character, as with Ctrl held, goes by its key`() {
        assertEquals(listOf(Key(KEY_C, true)), keys.key(KEYCODE_C, 0, 0, down = true))
    }

    @Test
    fun `Back and the volume keys stay the phone's, unless Back is a keyboard's Esc`() {
        assertNull(keys.key(KEYCODE_BACK, 0, 0, down = true))
        assertNull(keys.key(KEYCODE_VOLUME_UP, 115, 0, down = true))
        assertEquals(listOf(Key(KEY_ESC, true)), keys.key(KEYCODE_BACK, scanCode = KEY_ESC, unicode = 0, down = true))
    }

    @Test
    fun `a key nobody knows is left to Android`() {
        assertNull(keys.key(KEYCODE_UNKNOWN, 0, 0, down = true))
    }

    @Test
    fun `Android's repeats of a held key are dropped, the X server repeats it`() {
        assertEquals(emptyList(), keys.key(KEYCODE_A, KEY_A, 'a'.code, down = true, repeat = 1))
        assertEquals(listOf(Unicode('é'.code)), keys.key(KEYCODE_UNKNOWN, 0, 'é'.code, down = true, repeat = 1))
    }

    @Test
    fun `typed text is characters, newlines and tabs are keys`() {
        // The emoji is one code point, not its two UTF-16 halves.
        assertEquals(
            listOf(Unicode('h'.code), Unicode(0x1F600)) + tap(KEY_ENTER) + tap(KEY_TAB),
            keys.typed("h\uD83D\uDE00\n\t"),
        )
    }

    @Test
    fun `deleting is Backspace`() {
        assertEquals(tap(KEY_BACKSPACE) + tap(KEY_BACKSPACE), keys.deleted(2))
        assertEquals(tap(KEY_BACKSPACE), keys.deleted(0))
    }

    @Test
    fun `sticky modifiers are toggled by their buttons and show as latched`() {
        assertEquals(emptyList(), keys.bar(button("Ctrl", CtrlLatch)))
        assertEquals(setOf<KeyStroke>(CtrlLatch), keys.latched)
        keys.bar(button("Ctrl", CtrlLatch))
        assertEquals(emptySet(), keys.latched)
    }

    @Test
    fun `a sticky Ctrl holds for the next character typed, by its key`() {
        keys.bar(button("Ctrl", CtrlLatch))
        assertEquals(
            listOf(Key(KEY_LEFTCTRL, true)) + tap(KEY_C) + Key(KEY_LEFTCTRL, false) + Unicode('c'.code),
            keys.typed("cc"),
        )
        assertTrue(keys.latched.isEmpty())
    }

    @Test
    fun `a capital needs Shift too`() {
        keys.bar(button("Ctrl", CtrlLatch))
        keys.bar(button("Alt", AltLatch))
        assertEquals(
            listOf(Key(KEY_LEFTCTRL, true), Key(KEY_LEFTALT, true), Key(KEY_LEFTSHIFT, true)) + tap(20) +
                listOf(Key(KEY_LEFTSHIFT, false), Key(KEY_LEFTALT, false), Key(KEY_LEFTCTRL, false)),
            keys.typed("T"),
        )
    }

    @Test
    fun `a sticky modifier holds for the next key pressed`() {
        keys.bar(button("Super", SuperLatch))
        assertEquals(
            listOf(Key(KEY_LEFTMETA, true), Key(KEY_A, true), Key(KEY_LEFTMETA, false)),
            keys.key(KEYCODE_A, KEY_A, 'a'.code, down = true),
        )
        assertEquals(listOf(Key(KEY_A, false)), keys.key(KEYCODE_A, KEY_A, 'a'.code, down = false))
        assertTrue(keys.latched.isEmpty())
    }

    @Test
    fun `a bar key is pressed and released with its modifiers and the sticky ones`() {
        keys.bar(button("Alt", AltLatch))
        assertEquals(
            listOf(Key(KEY_LEFTCTRL, true), Key(KEY_LEFTALT, true), Key(KEY_LEFTMETA, true)) + tap(KEY_UP) +
                listOf(Key(KEY_LEFTMETA, false), Key(KEY_LEFTALT, false), Key(KEY_LEFTCTRL, false)),
            keys.bar(button("Max", KeyStroke.Key("Up", ctrl = true, superKey = true))),
        )
        assertTrue(keys.latched.isEmpty())
    }

    @Test
    fun `a bar character key is the key that types it`() {
        assertEquals(
            listOf(Key(KEY_LEFTCTRL, true)) + tap(KEY_C) + Key(KEY_LEFTCTRL, false),
            keys.bar(button("Copy", KeyStroke.Key("c", ctrl = true))),
        )
        assertEquals(listOf(Unicode('é'.code)), keys.bar(button("é", KeyStroke.Key("é"))))
    }

    @Test
    fun `a bar's text is typed as is, and leaves the sticky keys on`() {
        keys.bar(button("Ctrl", CtrlLatch))
        assertEquals(listOf(Unicode('l'.code), Unicode('s'.code)) + tap(KEY_ENTER), keys.bar(button("ls", KeyStroke.Text("ls\n"))))
        assertEquals(setOf<KeyStroke>(CtrlLatch), keys.latched)
    }

    @Test
    fun `a sticky modifier holds for the next click, and a drag, until the button is let go`() {
        keys.bar(button("Ctrl", CtrlLatch))
        assertEquals(
            listOf(Key(KEY_LEFTCTRL, true), Button(1, true)),
            keys.pointer(listOf(Button(1, true))),
        )
        assertTrue(keys.latched.isEmpty())
        assertEquals(listOf(X11Input.Move(1f, 2f)), keys.pointer(listOf(X11Input.Move(1f, 2f))))
        assertEquals(listOf(Button(1, false), Key(KEY_LEFTCTRL, false)), keys.pointer(listOf(Button(1, false))))
    }

    @Test
    fun `and for the next scroll, so Ctrl and scrolling zooms`() {
        keys.bar(button("Ctrl", CtrlLatch))
        assertEquals(
            listOf(Key(KEY_LEFTCTRL, true), Scroll(0f, 1f), Key(KEY_LEFTCTRL, false)),
            keys.pointer(listOf(Scroll(0f, 1f))),
        )
    }

    @Test
    fun `leaving the desktop lets go of every key still held`() {
        keys.key(KEYCODE_CTRL_LEFT, KEY_LEFTCTRL, 0, down = true)
        keys.key(KEYCODE_A, KEY_A, 'a'.code, down = true)
        assertEquals(listOf(Key(KEY_A, false), Key(KEY_LEFTCTRL, false)), keys.releaseAll())
        assertEquals(emptyList(), keys.releaseAll())
    }

    @Test
    fun `Ctrl+Shift+D switches between the desktop and the terminal`() {
        assertTrue(desktopShortcut(KeyPress("D", ctrl = true, shift = true)))
        assertFalse(desktopShortcut(KeyPress("D", ctrl = true)))
        assertFalse(desktopShortcut(KeyPress("D", ctrl = true, shift = true, alt = true)))
        assertFalse(desktopShortcut(KeyPress("T", ctrl = true, shift = true)))
    }

    @Test
    fun `Ctrl+Shift+1, 2 and 3 pick the switcher's views, in its order`() {
        assertEquals(listOf(AppView.TERMINAL, AppView.TOUCH, AppView.DESKTOP), VIEW_SWITCHER.map { it.first })
        assertEquals(listOf("Terminal", "Touch", "Desktop"), VIEW_SWITCHER.map { it.second })
        assertEquals(AppView.TERMINAL, viewShortcut(KeyPress("1", ctrl = true, shift = true)))
        assertEquals(AppView.TOUCH, viewShortcut(KeyPress("2", ctrl = true, shift = true)))
        assertEquals(AppView.DESKTOP, viewShortcut(KeyPress("3", ctrl = true, shift = true)))
        assertNull(viewShortcut(KeyPress("4", ctrl = true, shift = true)))
        assertNull(viewShortcut(KeyPress("1", ctrl = true)))
        assertNull(viewShortcut(KeyPress("1", ctrl = true, shift = true, alt = true)))
    }

    @Test
    fun `Back on the desktop goes to the view it came from, unless the desktop is the app's view`() {
        assertEquals(AppView.TOUCH, desktopBack(desktopView = false, cameFrom = AppView.TOUCH))
        assertEquals(AppView.TERMINAL, desktopBack(desktopView = false, cameFrom = AppView.TERMINAL))
        assertEquals(AppView.TERMINAL, desktopBack(desktopView = false, cameFrom = null))
        // The app's start: Back leaves (the terminal's Back comes to the desktop).
        assertNull(desktopBack(desktopView = true, cameFrom = AppView.TERMINAL))
        assertNull(desktopBack(desktopView = true, cameFrom = null))
    }

    @Test
    fun `Ctrl+Shift+D on the desktop goes where it came from, the terminal at the start`() {
        assertEquals(AppView.TOUCH, desktopShortcutTarget(AppView.TOUCH))
        assertEquals(AppView.TERMINAL, desktopShortcutTarget(null))
    }
}
