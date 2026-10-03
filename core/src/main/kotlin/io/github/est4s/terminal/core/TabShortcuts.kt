package io.github.est4s.terminal.core

/**
 * A key with its modifiers. [key] is the key's name as Android spells it
 * without the `KEYCODE_` prefix: "T", "TAB", "PAGE_UP", "1", …
 */
data class KeyPress(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** What a tab shortcut does, whether it comes from a hardware or the in-app keyboard. */
sealed interface TabAction {
    /** Opens a tab with a new shell. */
    data object New : TabAction

    /** Closes the selected tab and kills its shell. */
    data object Close : TabAction

    /** Actions that only rearrange or select tabs, so the model can apply them itself. */
    sealed interface Navigate : TabAction {
        fun <S> applyTo(tabs: Tabs<S>)
    }

    data object Next : Navigate {
        override fun <S> applyTo(tabs: Tabs<S>) = tabs.next()
    }

    data object Previous : Navigate {
        override fun <S> applyTo(tabs: Tabs<S>) = tabs.previous()
    }

    data object MoveLeft : Navigate {
        override fun <S> applyTo(tabs: Tabs<S>) = tabs.moveLeft()
    }

    data object MoveRight : Navigate {
        override fun <S> applyTo(tabs: Tabs<S>) = tabs.moveRight()
    }

    data class GoTo(val index: Int) : Navigate {
        override fun <S> applyTo(tabs: Tabs<S>) = tabs.select(index)
    }
}

/** Windows Terminal's default tab shortcuts. Null: the key goes to the terminal. */
fun tabShortcut(press: KeyPress): TabAction? {
    val (key, ctrl, shift, alt) = press
    if (!ctrl) return null
    return when {
        shift && !alt -> when (key) {
            "T" -> TabAction.New
            "W" -> TabAction.Close
            "TAB" -> TabAction.Previous
            "PAGE_UP" -> TabAction.MoveLeft
            "PAGE_DOWN" -> TabAction.MoveRight
            else -> null
        }
        alt && !shift -> key.toIntOrNull()?.takeIf { it in 1..9 }?.let { TabAction.GoTo(it - 1) }
        !alt && !shift && key == "TAB" -> TabAction.Next
        else -> null
    }
}
