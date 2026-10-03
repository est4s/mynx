package io.github.est4s.terminal.core

/** A tab as saved between runs: the user's name for it and its shell's folder. */
data class SavedTab(val name: String? = null, val cwd: String? = null)

/** The open tabs and the selected one (an index into [tabs]). */
data class SavedTabs(val tabs: List<SavedTab>, val selected: Int)

private const val VERSION = "1"

/** Plain text, one `[tab]` block per tab, so people and agents can read it. */
fun SavedTabs.serialize(): String = buildString {
    append("# Open tabs, written by the app so it can bring them back after\n")
    append("# Android kills it. Each tab restarts with a fresh shell in its folder.\n")
    append("version = $VERSION\n")
    append("selected = ${selected + 1}\n")
    tabs.forEach { tab ->
        append("\n[tab]\n")
        tab.name?.let { append("name = ${it.oneLine()}\n") }
        tab.cwd?.let { append("cwd = ${it.oneLine()}\n") }
    }
}

/** Null if the text isn't a tab list this version understands: the app then starts fresh. */
fun parseSavedTabs(text: String): SavedTabs? {
    var version: String? = null
    var selected: Int? = null
    val tabs = mutableListOf<SavedTab>()
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() || line.startsWith("#") -> continue
            line == "[tab]" -> tabs += SavedTab()
            "=" in line -> {
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (tabs.isEmpty()) {
                    if (key == "version") version = value
                    if (key == "selected") selected = value.toIntOrNull()?.minus(1)
                } else {
                    val last = tabs.last()
                    if (key == "name") tabs[tabs.lastIndex] = last.copy(name = value)
                    if (key == "cwd") tabs[tabs.lastIndex] = last.copy(cwd = value)
                }
            }
        }
    }
    if (version != VERSION || tabs.isEmpty()) return null
    return SavedTabs(tabs, selected?.takeIf { it in tabs.indices } ?: 0)
}

/** What to save for these tabs. [cwdOf] gives a session's Debian folder, if known. */
fun <S> Tabs<S>.snapshot(cwdOf: (S) -> String?): SavedTabs =
    SavedTabs(tabs.map { SavedTab(it.rename, cwdOf(it.session)) }, selectedIndex.coerceAtLeast(0))

/** Opens the saved tabs in order, starting each session with [start] in its folder. */
fun <S> Tabs<S>.restore(saved: SavedTabs, start: (cwd: String?) -> S) {
    saved.tabs.forEach { tab ->
        val session = start(tab.cwd)
        open(session)
        rename(session, tab.name)
    }
    select(saved.selected)
}

private fun String.oneLine() = replace(Regex("[\r\n]+"), " ")
