package io.github.est4s.terminal.core

/** How long Shut down's "Tap again" waits for the second tap. */
const val SHUT_DOWN_CONFIRM_MS = 3000L

/** Ctrl+Shift+M on the desktop: its ⋮ menu, opened or closed (Ctrl+Shift+D's neighbour; M for menu). */
fun desktopMenuShortcut(press: KeyPress): Boolean = press.key == "M" && press.ctrl && press.shift && !press.alt

/** What a row of the desktop's ⋮ menu does. */
enum class DesktopMenuAction { TOUCH, LOG, RESTART, SHUT_DOWN }

/** What the menu's rows depend on. */
data class DesktopMenuState(
    val touch: DesktopTouch,
    /** The X server or the session runs: there's something to restart or shut down. */
    val running: Boolean,
    val logShown: Boolean,
)

/**
 * A row: [label], and for a choice row (Touch) its [choices] with the one
 * that's on, [chosen]. [confirming]: Shut down waits for its second tap
 * (shown pink, [label] saying so).
 */
data class DesktopMenuItem(
    val action: DesktopMenuAction,
    val label: String,
    val choices: List<String> = emptyList(),
    val chosen: Int = -1,
    val confirming: Boolean = false,
)

/** A group of rows under [title] (null: the first group, no heading). */
data class DesktopMenuSection(val title: String?, val items: List<DesktopMenuItem>)

/** What the view does for a row. */
sealed interface DesktopMenuCommand {
    /** Sets `desktop-touch` (as `mynx set` does) and applies it at once. */
    data class Touch(val touch: DesktopTouch) : DesktopMenuCommand
    data object ToggleLog : DesktopMenuCommand
    /** As `mynx desktop stop`, then `start`. */
    data object Restart : DesktopMenuCommand
    /** As `mynx desktop stop`, then back where the desktop was opened from. */
    data object ShutDown : DesktopMenuCommand
}

/** The keys the open menu takes ([desktopMenuKey]). */
enum class DesktopMenuKey { UP, DOWN, LEFT, RIGHT, ENTER, ESCAPE }

// Android's KeyEvent.KEYCODE_*.
private const val KEYCODE_BACK = 4
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22
private const val KEYCODE_DPAD_CENTER = 23
private const val KEYCODE_TAB = 61
private const val KEYCODE_SPACE = 62
private const val KEYCODE_ENTER = 66
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_NUMPAD_ENTER = 160

/** An Android key code as a menu key; null: the menu ignores it. Tab moves down, Shift+Tab up. */
fun desktopMenuKey(keyCode: Int, shift: Boolean): DesktopMenuKey? = when (keyCode) {
    KEYCODE_DPAD_UP -> DesktopMenuKey.UP
    KEYCODE_DPAD_DOWN -> DesktopMenuKey.DOWN
    KEYCODE_TAB -> if (shift) DesktopMenuKey.UP else DesktopMenuKey.DOWN
    KEYCODE_DPAD_LEFT -> DesktopMenuKey.LEFT
    KEYCODE_DPAD_RIGHT -> DesktopMenuKey.RIGHT
    KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_DPAD_CENTER, KEYCODE_SPACE -> DesktopMenuKey.ENTER
    KEYCODE_ESCAPE, KEYCODE_BACK -> DesktopMenuKey.ESCAPE
    else -> null
}

/**
 * The desktop's ⋮ menu (the bar's ⋮, Ctrl+Shift+M): which rows show
 * ([sections], built here only, so a new group is one more section),
 * the row the keyboard is on, and Shut down's two taps: the first turns
 * it into "Tap again to shut down" for [confirmMs], the second shuts
 * down; anything else in between takes it back. Taps and keys return
 * what the view must do. Touch's choices keep the menu open (the change
 * shows at once); the other rows close it. Main thread.
 */
class DesktopMenu(private val confirmMs: Long = SHUT_DOWN_CONFIRM_MS) {
    var isOpen = false
        private set

    /** The keyboard's row, an index into [sections]' rows in order; -1: none (opened by a tap). */
    var selected = -1
        private set

    private var confirmUntil: Long? = null
    private var confirmByKey = false

    /** When Shut down's confirmation lapses (the view redraws then); null: it isn't waiting. */
    val confirmDeadline: Long? get() = confirmUntil

    fun open(byKeyboard: Boolean) {
        isOpen = true
        selected = if (byKeyboard) 0 else -1
        confirmUntil = null
    }

    fun close() {
        isOpen = false
        selected = -1
        confirmUntil = null
    }

    /** The rows for [state] at [now] (a lapsed confirmation is dropped, the selection kept on a row). */
    fun sections(state: DesktopMenuState, now: Long): List<DesktopMenuSection> {
        if (confirmUntil?.let { now >= it } == true) confirmUntil = null
        val confirming = confirmUntil != null
        val desktop = buildList {
            add(DesktopMenuItem(
                DesktopMenuAction.TOUCH, "Touch",
                choices = DesktopTouch.entries.map { it.word.replaceFirstChar(Char::uppercaseChar) },
                chosen = DesktopTouch.entries.indexOf(state.touch),
            ))
            add(DesktopMenuItem(DesktopMenuAction.LOG, if (state.logShown) "Hide log" else "Log"))
            if (state.running) {
                add(DesktopMenuItem(DesktopMenuAction.RESTART, "Restart desktop"))
                add(DesktopMenuItem(
                    DesktopMenuAction.SHUT_DOWN,
                    when {
                        !confirming -> "Shut down"
                        confirmByKey -> "Enter again to shut down"
                        else -> "Tap again to shut down"
                    },
                    confirming = confirming,
                ))
            }
        }
        val sections = listOf(DesktopMenuSection(null, desktop))
        val count = sections.sumOf { it.items.size }
        if (selected >= count) selected = count - 1
        return sections
    }

    private fun rows(state: DesktopMenuState, now: Long) = sections(state, now).flatMap { it.items }

    /** A tap on [action]'s row ([choice]: which of its choices; -1 for a plain row). */
    fun tap(action: DesktopMenuAction, choice: Int = -1, now: Long): DesktopMenuCommand? =
        activate(action, choice, byKey = false, now)

    /** A key while the menu is open: moves the selection, or does its row. */
    fun key(key: DesktopMenuKey, state: DesktopMenuState, now: Long): DesktopMenuCommand? {
        val rows = rows(state, now)
        val row = rows.getOrNull(selected)
        return when (key) {
            DesktopMenuKey.ESCAPE -> {
                close()
                null
            }
            DesktopMenuKey.UP, DesktopMenuKey.DOWN -> {
                val n = rows.size
                if (n > 0) selected = when {
                    selected < 0 -> if (key == DesktopMenuKey.DOWN) 0 else n - 1
                    key == DesktopMenuKey.DOWN -> (selected + 1) % n
                    else -> (selected - 1 + n) % n
                }
                confirmUntil = null
                null
            }
            DesktopMenuKey.LEFT, DesktopMenuKey.RIGHT -> {
                if (row == null || row.choices.isEmpty()) return null
                val next = (row.chosen + if (key == DesktopMenuKey.LEFT) -1 else 1).coerceIn(0, row.choices.size - 1)
                if (next == row.chosen) null else activate(row.action, next, byKey = true, now)
            }
            DesktopMenuKey.ENTER -> when {
                row == null -> {
                    close()
                    null
                }
                row.choices.isNotEmpty() -> activate(row.action, (row.chosen + 1) % row.choices.size, byKey = true, now)
                else -> activate(row.action, -1, byKey = true, now)
            }
        }
    }

    private fun activate(action: DesktopMenuAction, choice: Int, byKey: Boolean, now: Long): DesktopMenuCommand? {
        if (action == DesktopMenuAction.SHUT_DOWN) {
            if (confirmUntil?.let { now < it } == true) {
                close()
                return DesktopMenuCommand.ShutDown
            }
            confirmUntil = now + confirmMs
            confirmByKey = byKey
            return null
        }
        confirmUntil = null
        return when (action) {
            DesktopMenuAction.TOUCH -> DesktopTouch.entries.getOrNull(choice)?.let { DesktopMenuCommand.Touch(it) }
            DesktopMenuAction.LOG -> DesktopMenuCommand.ToggleLog.also { close() }
            DesktopMenuAction.RESTART -> DesktopMenuCommand.Restart.also { close() }
            DesktopMenuAction.SHUT_DOWN -> null
        }
    }
}
