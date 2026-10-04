package io.github.est4s.terminal.core

private val LINK = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
private val WEB_LINK = Regex("""https?://[^\s/?#]+\S*""")

/** Whether [url] is a link the app may hand to the browser. */
fun isWebLink(url: String) = WEB_LINK.matches(url)

/**
 * The link under a tap at [row], [col] of terminal [rows] (each row's
 * text), or null. A link can go on over several rows: the terminal
 * wrapped it ([wraps]: row i continues on row i+1), or a program broke
 * it at the edge itself (a row filled to [width]).
 */
fun linkAt(rows: List<String>, wraps: List<Boolean>, row: Int, col: Int, width: Int): String? {
    if (row !in rows.indices) return null
    fun joinsNext(i: Int) = i + 1 < rows.size && (wraps.getOrElse(i) { false } || rows[i].trimEnd().length >= width)
    var first = row
    while (first > 0 && joinsNext(first - 1)) first--
    var last = row
    while (joinsNext(last)) last++

    val line = StringBuilder()
    var tapAt = -1
    for (i in first..last) {
        // A program's own line break may come with indentation; the terminal's wrap doesn't.
        val text = if (i > first && !wraps.getOrElse(i - 1) { false }) rows[i].trimStart() else rows[i]
        val skipped = rows[i].length - text.length
        if (i == row) tapAt = line.length + col - skipped
        line.append(if (i == last) text else text.trimEnd().takeIf { !wraps.getOrElse(i) { false } } ?: text)
    }
    val match = LINK.findAll(line).firstOrNull { tapAt in it.range } ?: return null
    return trimEnd(match.value).takeIf { tapAt < match.range.first + it.length }
}

// Punctuation after a link belongs to the sentence; a ")" only if its "(" isn't in the link.
private fun trimEnd(url: String): String {
    var end = url.length
    while (end > 0) {
        val c = url[end - 1]
        val unbalanced = c == ')' && url.take(end).count { it == '(' } < url.take(end).count { it == ')' }
        if (c in ".,;:!?'\"" || unbalanced) end-- else break
    }
    return url.take(end)
}
