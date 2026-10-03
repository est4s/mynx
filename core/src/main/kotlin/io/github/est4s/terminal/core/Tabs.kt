package io.github.est4s.terminal.core

/** One terminal tab. [number] names it while neither the shell nor the user has. */
class Tab<S> internal constructor(session: S, val number: Int) {
    var session: S = session
        internal set
    var shellTitle: String = ""
        internal set
    /** The user's name for the tab; overrides [shellTitle] until cleared. */
    var rename: String? = null
        internal set
    /** Printed output while in the background. */
    var activity = false
        internal set
    /** Rang the bell while in the background. */
    var bell = false
        internal set

    val title: String get() = rename ?: shellTitle.ifBlank { "Tab $number" }
}

/**
 * The open tabs, in strip order, and which one is selected. Generic over the
 * session type so it stays free of Android; tabs are looked up by session.
 * [onChange] runs after every change the strip should show.
 */
class Tabs<S>(private val onChange: () -> Unit = {}) {
    private val list = mutableListOf<Tab<S>>()

    val tabs: List<Tab<S>> get() = list
    var selectedIndex = -1
        private set
    val selected: Tab<S>? get() = list.getOrNull(selectedIndex)
    val isEmpty get() = list.isEmpty()

    fun find(session: S): Tab<S>? = list.find { it.session == session }

    /** Opens a tab right after the selected one and selects it. */
    fun open(session: S): Tab<S> {
        val tab = Tab(session, freeNumber())
        selectedIndex += 1
        list.add(selectedIndex, tab)
        onChange()
        return tab
    }

    /** Closes the tab; if it was selected, selects its right neighbour, else its left. */
    fun close(session: S) {
        val i = list.indexOfFirst { it.session == session }
        if (i < 0) return
        list.removeAt(i)
        if (i < selectedIndex || selectedIndex == list.size) selectedIndex -= 1
        selected?.clearMarks()
        onChange()
    }

    fun select(index: Int) {
        if (index !in list.indices) return
        selectedIndex = index
        list[index].clearMarks()
        onChange()
    }

    fun next() = select(if (selectedIndex + 1 < list.size) selectedIndex + 1 else 0)

    fun previous() = select(if (selectedIndex > 0) selectedIndex - 1 else list.size - 1)

    fun moveLeft() = moveSelected(-1)

    fun moveRight() = moveSelected(+1)

    fun setShellTitle(session: S, title: String) = update(session) { it.shellTitle = title }

    /** A null or blank [name] clears the rename, so the shell title shows again. */
    fun rename(session: S, name: String?) = update(session) { it.rename = name?.takeIf { n -> n.isNotBlank() } }

    /** What a rename box should start with: the name the tab shows now. */
    fun renameInput(session: S): String? = find(session)?.title

    /**
     * Applies what the user typed in a rename box. Blank goes back to the
     * automatic title; leaving the shown title unchanged doesn't pin it, so
     * the tab keeps following the shell.
     */
    fun applyRenameInput(session: S, input: String) {
        val tab = find(session) ?: return
        val name = input.trim()
        if (tab.rename == null && name == tab.title) return
        rename(session, name)
    }

    /** Swaps in a fresh session (a restarted shell), keeping the tab as it is. */
    fun replaceSession(old: S, new: S) = update(old) { it.session = new }

    fun onOutput(session: S) {
        val tab = find(session) ?: return
        if (tab === selected || tab.activity) return
        tab.activity = true
        onChange()
    }

    fun onBell(session: S) {
        val tab = find(session) ?: return
        if (tab === selected || tab.bell) return
        tab.bell = true
        onChange()
    }

    private fun moveSelected(by: Int) {
        val to = selectedIndex + by
        if (selectedIndex < 0 || to !in list.indices) return
        list.add(to, list.removeAt(selectedIndex))
        selectedIndex = to
        onChange()
    }

    private fun update(session: S, change: (Tab<S>) -> Unit) {
        val tab = find(session) ?: return
        change(tab)
        onChange()
    }

    private fun freeNumber(): Int {
        val used = list.map { it.number }.toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    private fun Tab<S>.clearMarks() {
        activity = false
        bell = false
    }
}

/**
 * Windows Terminal's "graceful" rule: a shell that exits with 0 closes its
 * tab; any other code (negative = killed by a signal) keeps it open so the
 * user can read the output and press Enter to restart.
 */
fun closesOnExit(exitCode: Int): Boolean = exitCode == 0
