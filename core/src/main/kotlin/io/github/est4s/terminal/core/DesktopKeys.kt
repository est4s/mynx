package io.github.est4s.terminal.core

// Linux (evdev) key codes, from input-event-codes.h.
private const val KEY_ESC = 1
private const val KEY_BACKSPACE = 14
private const val KEY_TAB = 15
private const val KEY_ENTER = 28
private const val KEY_LEFTCTRL = 29
private const val KEY_LEFTSHIFT = 42
private const val KEY_LEFTALT = 56
private const val KEY_LEFTMETA = 125
// Android's KeyEvent.KEYCODE_BACK.
private const val KEYCODE_BACK = 4

/** What a sticky modifier holds down, in the order they're pressed. */
private val LATCH_KEYS = linkedMapOf<KeyStroke, Int>(
    KeyStroke.CtrlLatch to KEY_LEFTCTRL, KeyStroke.AltLatch to KEY_LEFTALT, KeyStroke.SuperLatch to KEY_LEFTMETA,
)

private val MODIFIER_KEYS = setOf(KEY_LEFTCTRL, 97, KEY_LEFTSHIFT, 54, KEY_LEFTALT, 100, KEY_LEFTMETA, 126)

/** Android key codes (KeyEvent.KEYCODE_*) of keys that type no character, to Linux ones. */
private val ANDROID_KEYS: Map<Int, Int> = mapOf(
    66 to KEY_ENTER, 67 to KEY_BACKSPACE, 112 to 111, 61 to KEY_TAB, 111 to KEY_ESC, 62 to 57, // Enter Del ForwardDel Tab Esc Space
    19 to 103, 20 to 108, 21 to 105, 22 to 106, // arrows
    122 to 102, 123 to 107, 92 to 104, 93 to 109, 124 to 110, // Home End PgUp PgDn Insert
    113 to KEY_LEFTCTRL, 114 to 97, 59 to KEY_LEFTSHIFT, 60 to 54, 57 to KEY_LEFTALT, 58 to 100, 117 to KEY_LEFTMETA, 118 to 126,
    115 to 58, 143 to 69, 116 to 70, 120 to 99, 121 to 119, 82 to 127, // Caps Num Scroll SysRq Break Menu(Compose)
) + (0..9).associate { 131 + it to 59 + it } + mapOf(141 to 87, 142 to 88) // F1-F12

/** The phone's own keys: Home, Back, volume, power, app switch, media and brightness keys. */
private val PHONE_KEYS = setOf(3, KEYCODE_BACK, 24, 25, 26, 164, 187, 223, 224, 220, 221, 79) + (85..91) + (126..130)

// The US layout: a character's key, and whether it needs Shift.
private val US_KEYS: Map<Char, Pair<Int, Boolean>> = buildMap {
    fun row(plain: String, shifted: String, codes: List<Int>) = plain.indices.forEach {
        put(plain[it], codes[it] to false)
        put(shifted[it], codes[it] to true)
    }
    row("`1234567890-=", "~!@#$%^&*()_+", listOf(41) + (2..13))
    row("qwertyuiop[]\\", "QWERTYUIOP{}|", (16..27) + 43)
    row("asdfghjkl;'", "ASDFGHJKL:\"", (30..40).toList())
    row("zxcvbnm,./", "ZXCVBNM<>?", (44..53).toList())
    put(' ', 57 to false)
}

// Android's letter and digit keys, for when a held modifier leaves them no character.
private val ANDROID_LETTERS: Map<Int, Int> =
    ('a'..'z').associate { 29 + (it - 'a') to US_KEYS.getValue(it).first } +
        ('0'..'9').associate { 7 + (it - '0') to US_KEYS.getValue(it).first }

/** A key bar's named keys (KEY_NAMES) to Linux key codes. */
private val NAMED_KEYS: Map<String, Int> = mapOf(
    "Enter" to KEY_ENTER, "Esc" to KEY_ESC, "Tab" to KEY_TAB, "Space" to 57, "Backspace" to KEY_BACKSPACE,
    "Delete" to 111, "Insert" to 110, "Up" to 103, "Down" to 108, "Left" to 105, "Right" to 106,
    "Home" to 102, "End" to 107, "PgUp" to 104, "PgDn" to 109,
) + (1..10).associate { "F$it" to 58 + it } + mapOf("F11" to 87, "F12" to 88)

/** Ctrl+Shift+D: from the desktop to the terminal, and back. */
fun desktopShortcut(press: KeyPress): Boolean = press.key == "D" && press.ctrl && press.shift && !press.alt

/**
 * The desktop's keyboard: Android's key events, the soft keyboard's
 * text and the key bar's buttons as X key events. A hardware key goes by
 * its scan code (Linux's key code, so the X server's layout applies);
 * a soft keyboard's characters as characters. The bar's sticky Ctrl,
 * Alt and Super hold for the next key, character, click or scroll. It
 * keeps which keys are down, so leaving the desktop lets go of them
 * ([releaseAll]). Main thread.
 */
class DesktopKeyboard {
    private val on = LinkedHashSet<KeyStroke>()
    private val held = LinkedHashSet<Int>()
    // The sticky modifiers held for a mouse button that's down.
    private var heldForButton = emptyList<Int>()

    /** The sticky modifiers that are on, for the bar to show. */
    val latched: Set<KeyStroke> get() = on.toSet()

    /**
     * An Android key event; null when it's not the desktop's (Back, the
     * volume keys, a key with nothing to send), so Android handles it.
     * [repeat] is the event's repeat count: the X server repeats held keys itself.
     */
    fun key(keyCode: Int, scanCode: Int, unicode: Int, down: Boolean, repeat: Int = 0): List<X11Input>? {
        val code = when {
            // A keyboard's Esc that Android takes for Back.
            keyCode == KEYCODE_BACK && scanCode == KEY_ESC -> KEY_ESC
            keyCode in PHONE_KEYS -> return null
            scanCode > 0 -> scanCode
            else -> ANDROID_KEYS[keyCode]
        }
        if (code == null && unicode != 0) {
            if (!down) return emptyList()
            return typed(String(Character.toChars(unicode)))
        }
        val key = code ?: ANDROID_LETTERS[keyCode] ?: return null
        if (!down) return listOf(release(key))
        if (repeat > 0) return emptyList()
        if (key in MODIFIER_KEYS || on.isEmpty()) return listOf(press(key))
        val mods = takeLatches()
        return mods.map(::press) + press(key) + mods.reversed().map(::release)
    }

    /** Text from the soft keyboard: each character typed; newlines and tabs are keys. */
    fun typed(text: CharSequence): List<X11Input> {
        val out = mutableListOf<X11Input>()
        text.codePoints().forEach { out += typeChar(it, latches = true) }
        return out
    }

    /** The soft keyboard deleted [count] characters: as many Backspaces, one at least. */
    fun deleted(count: Int): List<X11Input> = (1..maxOf(count, 1)).flatMap { tap(KEY_BACKSPACE) }

    /** A key bar button: sticky modifiers toggle, keys are pressed with theirs, text is typed as is. */
    fun bar(button: KeyButton): List<X11Input> {
        val out = mutableListOf<X11Input>()
        for (stroke in button.strokes) {
            when (stroke) {
                in LATCH_KEYS -> if (!on.remove(stroke)) on += stroke
                is KeyStroke.Text -> stroke.text.codePoints().forEach { out += typeChar(it, latches = false) }
                is KeyStroke.Key -> out += barKey(stroke)
                else -> {}
            }
        }
        return out
    }

    private fun barKey(stroke: KeyStroke.Key): List<X11Input> {
        val asked = listOfNotNull(
            KEY_LEFTCTRL.takeIf { stroke.ctrl }, KEY_LEFTALT.takeIf { stroke.alt }, KEY_LEFTMETA.takeIf { stroke.superKey },
        )
        val mods = (takeLatches() + asked).distinct().sortedBy { LATCH_KEYS.values.indexOf(it) } +
            listOfNotNull(KEY_LEFTSHIFT.takeIf { stroke.shift })
        val named = NAMED_KEYS[stroke.key]
        if (named != null) return chord(mods, named)
        val c = stroke.key.codePointAt(0)
        val us = if (c < 0x80) US_KEYS[c.toChar()] else null
        if (us == null) return wrap(mods, X11Input.Unicode(c))
        return chord(if (us.second) mods + KEY_LEFTSHIFT else mods, us.first)
    }

    private fun typeChar(c: Int, latches: Boolean): List<X11Input> {
        when (c) {
            '\n'.code -> return chord(if (latches) takeLatches() else emptyList(), KEY_ENTER)
            '\t'.code -> return chord(if (latches) takeLatches() else emptyList(), KEY_TAB)
        }
        if (!latches || on.isEmpty()) return listOf(X11Input.Unicode(c))
        val mods = takeLatches()
        val us = if (c < 0x80) US_KEYS[c.toChar()] else null
        return if (us == null) wrap(mods, X11Input.Unicode(c)) else chord(if (us.second) mods + KEY_LEFTSHIFT else mods, us.first)
    }

    /** Pointer events, with the sticky modifiers held around the next click (until its button is let go) or scroll. */
    fun pointer(events: List<X11Input>): List<X11Input> {
        val out = mutableListOf<X11Input>()
        for (e in events) {
            when {
                e is X11Input.Button && e.down && on.isNotEmpty() && heldForButton.isEmpty() -> {
                    heldForButton = takeLatches()
                    out += heldForButton.map(::press)
                    out += e
                }
                e is X11Input.Button && !e.down && heldForButton.isNotEmpty() -> {
                    out += e
                    out += heldForButton.reversed().map(::release)
                    heldForButton = emptyList()
                }
                e is X11Input.Scroll && on.isNotEmpty() -> out += wrap(takeLatches(), e)
                else -> out += e
            }
        }
        return out
    }

    /** Lets go of every key still down, last pressed first. */
    fun releaseAll(): List<X11Input> {
        val out = held.reversed().map { X11Input.Key(it, false) }
        held.clear()
        heldForButton = emptyList()
        return out
    }

    private fun takeLatches(): List<Int> {
        val mods = LATCH_KEYS.filterKeys { it in on }.values.toList()
        on.clear()
        return mods
    }

    private fun press(code: Int): X11Input {
        held += code
        return X11Input.Key(code, true)
    }

    private fun release(code: Int): X11Input {
        held -= code
        return X11Input.Key(code, false)
    }

    private fun tap(code: Int) = listOf(press(code), release(code))

    private fun chord(mods: List<Int>, code: Int) = mods.map(::press) + tap(code) + mods.reversed().map(::release)

    private fun wrap(mods: List<Int>, event: X11Input) = mods.map(::press) + event + mods.reversed().map(::release)
}
