package io.github.est4s.terminal.core

import io.github.est4s.terminal.core.DesktopMenuAction.LOG
import io.github.est4s.terminal.core.DesktopMenuAction.RESTART
import io.github.est4s.terminal.core.DesktopMenuAction.SHUT_DOWN
import io.github.est4s.terminal.core.DesktopMenuAction.TOUCH
import io.github.est4s.terminal.core.DesktopMenuKey.DOWN
import io.github.est4s.terminal.core.DesktopMenuKey.ENTER
import io.github.est4s.terminal.core.DesktopMenuKey.ESCAPE
import io.github.est4s.terminal.core.DesktopMenuKey.LEFT
import io.github.est4s.terminal.core.DesktopMenuKey.RIGHT
import io.github.est4s.terminal.core.DesktopMenuKey.UP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Android's key codes (KeyEvent.KEYCODE_*).
private const val KEYCODE_BACK = 4
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22
private const val KEYCODE_DPAD_CENTER = 23
private const val KEYCODE_A = 29
private const val KEYCODE_TAB = 61
private const val KEYCODE_SPACE = 62
private const val KEYCODE_ENTER = 66
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_NUMPAD_ENTER = 160

private val RUNNING = DesktopMenuState(touch = DesktopTouch.TOUCHPAD, running = true, logShown = false)
private val STOPPED = RUNNING.copy(running = false)

class DesktopMenuTest {
    private fun DesktopMenu.rows(state: DesktopMenuState = RUNNING, now: Long = 0) = sections(state, now).flatMap { it.items }

    @Test
    fun `Ctrl+Shift+M opens the menu, and nothing else does`() {
        assertTrue(desktopMenuShortcut(KeyPress("M", ctrl = true, shift = true)))
        assertFalse(desktopMenuShortcut(KeyPress("M", ctrl = true)))
        assertFalse(desktopMenuShortcut(KeyPress("M", ctrl = true, shift = true, alt = true)))
        assertFalse(desktopMenuShortcut(KeyPress("D", ctrl = true, shift = true)))
    }

    @Test
    fun `a running desktop's menu has Touch, Log, Restart and Shut down, in one section`() {
        val sections = DesktopMenu().sections(RUNNING, 0)
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        assertEquals(listOf(TOUCH, LOG, RESTART, SHUT_DOWN), sections[0].items.map { it.action })
        assertEquals(listOf("Touch", "Log", "Restart desktop", "Shut down"), sections[0].items.map { it.label })
    }

    @Test
    fun `with nothing running there's nothing to restart or shut down`() {
        assertEquals(listOf(TOUCH, LOG), DesktopMenu().rows(STOPPED).map { it.action })
    }

    @Test
    fun `Touch shows its two choices and which is on`() {
        val touchpad = DesktopMenu().rows().first { it.action == TOUCH }
        assertEquals(listOf("Touchpad", "Direct"), touchpad.choices)
        assertEquals(0, touchpad.chosen)
        val direct = DesktopMenu().rows(RUNNING.copy(touch = DesktopTouch.DIRECT)).first { it.action == TOUCH }
        assertEquals(1, direct.chosen)
    }

    @Test
    fun `Log says Hide log while the log shows`() {
        assertEquals("Hide log", DesktopMenu().rows(RUNNING.copy(logShown = true))[1].label)
    }

    @Test
    fun `tapping a touch choice sets it and keeps the menu open`() {
        val menu = DesktopMenu().apply { open(byKeyboard = false) }
        assertEquals(DesktopMenuCommand.Touch(DesktopTouch.DIRECT), menu.tap(TOUCH, choice = 1, now = 0))
        assertTrue(menu.isOpen)
    }

    @Test
    fun `tapping Log or Restart does it and closes the menu`() {
        val menu = DesktopMenu().apply { open(byKeyboard = false) }
        assertEquals(DesktopMenuCommand.ToggleLog, menu.tap(LOG, now = 0))
        assertFalse(menu.isOpen)
        menu.open(byKeyboard = false)
        assertEquals(DesktopMenuCommand.Restart, menu.tap(RESTART, now = 0))
        assertFalse(menu.isOpen)
    }

    @Test
    fun `Shut down needs a second tap, which turns the row pink first`() {
        val menu = DesktopMenu().apply { open(byKeyboard = false) }
        assertNull(menu.tap(SHUT_DOWN, now = 1000))
        assertTrue(menu.isOpen)
        val row = menu.rows(now = 1500).last()
        assertTrue(row.confirming)
        assertEquals("Tap again to shut down", row.label)
        assertEquals(1000 + SHUT_DOWN_CONFIRM_MS, menu.confirmDeadline)
        assertEquals(DesktopMenuCommand.ShutDown, menu.tap(SHUT_DOWN, now = 3500))
        assertFalse(menu.isOpen)
    }

    @Test
    fun `the confirmation lapses after 3 s, and a tap then asks again`() {
        val menu = DesktopMenu().apply { open(byKeyboard = false) }
        menu.tap(SHUT_DOWN, now = 1000)
        val later = 1000 + SHUT_DOWN_CONFIRM_MS
        val row = menu.rows(now = later).last()
        assertFalse(row.confirming)
        assertEquals("Shut down", row.label)
        assertNull(menu.confirmDeadline)
        assertNull(menu.tap(SHUT_DOWN, now = later + 1))
        assertTrue(menu.rows(now = later + 2).last().confirming)
    }

    @Test
    fun `another row's tap, or closing, takes the confirmation back`() {
        val menu = DesktopMenu().apply { open(byKeyboard = false) }
        menu.tap(SHUT_DOWN, now = 0)
        menu.tap(TOUCH, choice = 0, now = 10)
        assertFalse(menu.rows(now = 20).last().confirming)
        menu.tap(SHUT_DOWN, now = 30)
        menu.close()
        menu.open(byKeyboard = false)
        assertFalse(menu.rows(now = 40).last().confirming)
        assertNull(menu.tap(SHUT_DOWN, now = 50))
    }

    @Test
    fun `opened by a tap no row is selected, by the keyboard the first is`() {
        val menu = DesktopMenu()
        menu.open(byKeyboard = false)
        assertEquals(-1, menu.selected)
        menu.close()
        menu.open(byKeyboard = true)
        assertEquals(0, menu.selected)
    }

    @Test
    fun `the arrows move through the rows, round at the ends`() {
        val menu = DesktopMenu().apply { open(byKeyboard = true) }
        menu.key(DOWN, RUNNING, 0)
        assertEquals(1, menu.selected)
        menu.key(UP, RUNNING, 0)
        menu.key(UP, RUNNING, 0)
        assertEquals(3, menu.selected)
        menu.key(DOWN, RUNNING, 0)
        assertEquals(0, menu.selected)
        menu.key(UP, STOPPED, 0)
        assertEquals(1, menu.selected)
    }

    @Test
    fun `the first arrow after a tap's opening selects an end row`() {
        val down = DesktopMenu().apply { open(byKeyboard = false) }
        down.key(DOWN, RUNNING, 0)
        assertEquals(0, down.selected)
        val up = DesktopMenu().apply { open(byKeyboard = false) }
        up.key(UP, RUNNING, 0)
        assertEquals(3, up.selected)
    }

    @Test
    fun `Left and Right pick Touch's choice, Enter switches it, the menu stays`() {
        val menu = DesktopMenu().apply { open(byKeyboard = true) }
        assertEquals(DesktopMenuCommand.Touch(DesktopTouch.DIRECT), menu.key(RIGHT, RUNNING, 0))
        val direct = RUNNING.copy(touch = DesktopTouch.DIRECT)
        assertEquals(DesktopMenuCommand.Touch(DesktopTouch.TOUCHPAD), menu.key(LEFT, direct, 0))
        assertEquals(DesktopMenuCommand.Touch(DesktopTouch.TOUCHPAD), menu.key(ENTER, direct, 0))
        assertEquals(DesktopMenuCommand.Touch(DesktopTouch.DIRECT), menu.key(ENTER, RUNNING, 0))
        assertTrue(menu.isOpen)
    }

    @Test
    fun `Left and Right do nothing on other rows`() {
        val menu = DesktopMenu().apply { open(byKeyboard = true) }
        menu.key(DOWN, RUNNING, 0)
        assertNull(menu.key(RIGHT, RUNNING, 0))
        assertEquals(1, menu.selected)
    }

    @Test
    fun `Enter does the selected row, and twice shuts down`() {
        val menu = DesktopMenu().apply { open(byKeyboard = true) }
        menu.key(UP, RUNNING, 0)
        assertNull(menu.key(ENTER, RUNNING, 100))
        val row = menu.rows(now = 200).last()
        assertTrue(row.confirming)
        assertEquals("Enter again to shut down", row.label)
        assertEquals(DesktopMenuCommand.ShutDown, menu.key(ENTER, RUNNING, 300))
        assertFalse(menu.isOpen)
    }

    @Test
    fun `moving off Shut down takes its confirmation back`() {
        val menu = DesktopMenu().apply { open(byKeyboard = true) }
        menu.key(UP, RUNNING, 0)
        menu.key(ENTER, RUNNING, 0)
        menu.key(UP, RUNNING, 10)
        menu.key(DOWN, RUNNING, 20)
        assertFalse(menu.rows(now = 30).last().confirming)
        assertNull(menu.key(ENTER, RUNNING, 40))
    }

    @Test
    fun `Enter with no row selected, or Escape, closes the menu`() {
        val menu = DesktopMenu().apply { open(byKeyboard = false) }
        assertNull(menu.key(ENTER, RUNNING, 0))
        assertFalse(menu.isOpen)
        menu.open(byKeyboard = true)
        assertNull(menu.key(ESCAPE, RUNNING, 0))
        assertFalse(menu.isOpen)
    }

    @Test
    fun `a row that's gone moves the selection back into the list`() {
        val menu = DesktopMenu().apply { open(byKeyboard = true) }
        menu.key(UP, RUNNING, 0)
        assertEquals(3, menu.selected)
        menu.rows(STOPPED)
        assertEquals(1, menu.selected)
        assertEquals(DesktopMenuCommand.ToggleLog, menu.key(ENTER, STOPPED, 0))
    }

    @Test
    fun `Android's keys for the menu`() {
        assertEquals(UP, desktopMenuKey(KEYCODE_DPAD_UP, shift = false))
        assertEquals(DOWN, desktopMenuKey(KEYCODE_DPAD_DOWN, shift = false))
        assertEquals(LEFT, desktopMenuKey(KEYCODE_DPAD_LEFT, shift = false))
        assertEquals(RIGHT, desktopMenuKey(KEYCODE_DPAD_RIGHT, shift = false))
        assertEquals(DOWN, desktopMenuKey(KEYCODE_TAB, shift = false))
        assertEquals(UP, desktopMenuKey(KEYCODE_TAB, shift = true))
        for (enter in listOf(KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_DPAD_CENTER, KEYCODE_SPACE)) {
            assertEquals(ENTER, desktopMenuKey(enter, shift = false))
        }
        assertEquals(ESCAPE, desktopMenuKey(KEYCODE_ESCAPE, shift = false))
        assertEquals(ESCAPE, desktopMenuKey(KEYCODE_BACK, shift = false))
        assertNull(desktopMenuKey(KEYCODE_A, shift = false))
    }
}
