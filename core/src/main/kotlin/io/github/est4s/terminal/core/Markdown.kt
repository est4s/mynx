package io.github.est4s.terminal.core

/** Text and what's styled in it; ranges are offsets into [text] and may nest or overlap. */
data class MdStyled(val text: String, val styles: List<MdStyle> = emptyList())

enum class MdStyleKind { BOLD, ITALIC, STRIKE, CODE, LINK }

/** [url] is set for a [MdStyleKind.LINK], and always passes [mdLinkAllowed]. */
data class MdStyle(val start: Int, val end: Int, val kind: MdStyleKind, val url: String? = null)

enum class MdAlign { LEFT, CENTER, RIGHT }

sealed interface MdBlock {
    data class Paragraph(val text: MdStyled) : MdBlock
    data class Heading(val level: Int, val text: MdStyled) : MdBlock
    data class Code(val language: String?, val text: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ItemList(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data object Rule : MdBlock

    /** Rows may have more or fewer cells than [header]: none are dropped. */
    data class Table(val aligns: List<MdAlign>, val header: List<MdStyled>, val rows: List<List<MdStyled>>) : MdBlock
}

/**
 * One thing a screen draws, in order: lists and quotes flattened, nesting
 * kept as [indent] (list levels) and [quote] (quote levels).
 */
sealed interface MdRow {
    val indent: Int
    val quote: Int

    /** [heading] is 1-6, or 0 for body text; [marker] is a list item's bullet or number. */
    data class Text(val text: MdStyled, val heading: Int = 0, val marker: String? = null, override val indent: Int = 0, override val quote: Int = 0) : MdRow
    data class Code(val language: String?, val text: String, override val indent: Int = 0, override val quote: Int = 0) : MdRow
    data class Table(val table: MdBlock.Table, override val indent: Int = 0, override val quote: Int = 0) : MdRow
    data class Rule(override val indent: Int = 0, override val quote: Int = 0) : MdRow
}

/** A whole `http(s)://…` address, the only links a reply may hold. */
fun mdLinkAllowed(url: String): Boolean {
    val prefix = listOf("http://", "https://").firstOrNull { url.startsWith(it, ignoreCase = true) } ?: return false
    return url.length > prefix.length && url.none { it.isWhitespace() || it.isISOControl() }
}

/**
 * The Markdown of an agent's reply: CommonMark's blocks and GitHub's tables
 * and strikethrough, no HTML, images (shown as links) or reference links.
 *
 * Replies stream in, so it takes any text and never throws. A fence that
 * isn't closed runs to the end. Inline markers (`**`, `` ` ``, `[`) only
 * count once closed and show as typed until then: a stray one stays right
 * in the finished text (`**kwargs`, `2**32`), at the price of a marker
 * showing for a moment while it streams. No text is lost, and nothing
 * inside code is interpreted. Work is linear in the text, and nesting
 * stops at [MAX_DEPTH] (what's deeper is text).
 */
fun parseMarkdown(text: String): List<MdBlock> = parseBlocks(splitLines(text), 0).map { it.block }

/**
 * [parseMarkdown] for text that grows: only the end is parsed again, and
 * blocks before it stay the same objects. Only the last two blocks can
 * change. A complete line never changes, so a block with another after it
 * can only end differently through the last, growing line (`---` becoming
 * `---x` joins the paragraph before it).
 */
class IncrementalMarkdown {
    private var text = ""
    private var blocks: List<MdBlock> = emptyList()
    private var starts: List<Int> = emptyList()

    /** How many characters the last [update] parsed, for tests. */
    internal var reparsed = 0
        private set

    fun update(new: String): List<MdBlock> {
        if (new == text) return blocks
        val keep = if (new.startsWith(text)) (blocks.size - 2).coerceAtLeast(0) else 0
        val from = if (keep == 0) 0 else starts[keep]
        val part = new.substring(from)
        val offsets = lineOffsets(part)
        val parsed = parseBlocks(splitLines(part), 0)
        blocks = blocks.take(keep) + parsed.map { it.block }
        starts = starts.take(keep) + parsed.map { from + offsets[it.line] }
        text = new
        reparsed = part.length
        return blocks
    }
}

/** What [ChatView] draws, top to bottom. */
fun mdRows(blocks: List<MdBlock>): List<MdRow> = ArrayList<MdRow>().also { addRows(blocks, 0, 0, 0, it) }

private val BULLETS = listOf("•", "◦", "▪")

private fun addRows(blocks: List<MdBlock>, indent: Int, quote: Int, depth: Int, out: MutableList<MdRow>) {
    for (block in blocks) {
        when (block) {
            is MdBlock.Paragraph -> out.add(MdRow.Text(block.text, indent = indent, quote = quote))
            is MdBlock.Heading -> out.add(MdRow.Text(block.text, heading = block.level, indent = indent, quote = quote))
            is MdBlock.Code -> out.add(MdRow.Code(block.language, block.text, indent, quote))
            is MdBlock.Table -> out.add(MdRow.Table(block, indent, quote))
            MdBlock.Rule -> out.add(MdRow.Rule(indent, quote))
            is MdBlock.Quote -> addRows(block.blocks, indent, quote + 1, depth, out)
            is MdBlock.ItemList -> block.items.forEachIndexed { number, item ->
                val marker = if (block.ordered) "${block.start + number}." else BULLETS[minOf(depth, BULLETS.lastIndex)]
                val first = item.firstOrNull()
                if (first is MdBlock.Paragraph) {
                    out.add(MdRow.Text(first.text, marker = marker, indent = indent, quote = quote))
                    addRows(item.drop(1), indent + 1, quote, depth + 1, out)
                } else {
                    out.add(MdRow.Text(MdStyled(""), marker = marker, indent = indent, quote = quote))
                    addRows(item, indent + 1, quote, depth + 1, out)
                }
            }
        }
    }
}

// Blocks

private const val MAX_DEPTH = 16

private class Parsed(val block: MdBlock, val line: Int)
private class Step(val block: MdBlock, val next: Int)

private fun splitLines(text: String): List<String> = text.split('\n').map { it.removeSuffix("\r") }

private fun lineOffsets(text: String): List<Int> {
    val offsets = arrayListOf(0)
    text.forEachIndexed { i, c -> if (c == '\n') offsets.add(i + 1) }
    return offsets
}

private fun parseBlocks(lines: List<String>, depth: Int): List<Parsed> {
    val out = ArrayList<Parsed>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        val step = when {
            fenceAt(line) != null -> parseFence(lines, i)
            indentOf(line) >= 4 -> parseIndentedCode(lines, i)
            headingAt(line) != null -> Step(headingAt(line)!!, i + 1)
            isRule(line) -> Step(MdBlock.Rule, i + 1)
            depth < MAX_DEPTH && isQuote(line) -> parseQuote(lines, i, depth)
            depth < MAX_DEPTH && markerAt(line) != null -> parseList(lines, i, depth)
            isTableStart(lines, i) -> parseTable(lines, i)
            else -> parseParagraph(lines, i, depth)
        }
        out.add(Parsed(step.block, i))
        i = step.next
    }
    return out
}

private fun indentOf(line: String): Int {
    var column = 0
    for (c in line) {
        when (c) {
            ' ' -> column++
            '\t' -> column += 4 - column % 4
            else -> return column
        }
    }
    return column
}

private fun dedent(line: String, columns: Int): String {
    var column = 0
    var i = 0
    while (i < line.length && column < columns) {
        when (line[i]) {
            ' ' -> column++
            '\t' -> column += 4 - column % 4
            else -> return line.substring(i)
        }
        i++
    }
    return " ".repeat((column - columns).coerceAtLeast(0)) + line.substring(i)
}

private class Fence(val char: Char, val length: Int, val indent: Int, val language: String?)

private fun fenceAt(line: String): Fence? {
    val indent = indentOf(line)
    if (indent > 3) return null
    val body = line.trimStart()
    val char = body.firstOrNull() ?: return null
    if (char != '`' && char != '~') return null
    val length = body.indexOfFirst { it != char }.let { if (it < 0) body.length else it }
    if (length < 3) return null
    val info = body.substring(length).trim()
    if (char == '`' && '`' in info) return null
    val language = info.takeWhile { !it.isWhitespace() }
    return Fence(char, length, indent, language.ifEmpty { null })
}

private fun closesFence(line: String, fence: Fence): Boolean {
    if (indentOf(line) > 3) return false
    val body = line.trim()
    return body.length >= fence.length && body.all { it == fence.char }
}

private fun parseFence(lines: List<String>, from: Int): Step {
    val fence = fenceAt(lines[from])!!
    val code = ArrayList<String>()
    var i = from + 1
    while (i < lines.size) {
        if (closesFence(lines[i], fence)) return Step(MdBlock.Code(fence.language, code.joinToString("\n")), i + 1)
        code.add(dedent(lines[i], fence.indent))
        i++
    }
    // Still streaming: the empty "line" after a final newline isn't code yet.
    if (code.lastOrNull() == "") code.removeAt(code.lastIndex)
    return Step(MdBlock.Code(fence.language, code.joinToString("\n")), i)
}

private fun parseIndentedCode(lines: List<String>, from: Int): Step {
    val code = ArrayList<String>()
    var i = from
    while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) >= 4)) {
        code.add(dedent(lines[i], 4))
        i++
    }
    while (code.last().isBlank()) code.removeAt(code.lastIndex)
    return Step(MdBlock.Code(null, code.joinToString("\n")), i)
}

private fun headingAt(line: String): MdBlock.Heading? {
    if (indentOf(line) > 3) return null
    val body = line.trim()
    val level = body.indexOfFirst { it != '#' }.let { if (it < 0) body.length else it }
    if (level !in 1..6 || level == body.length || !body[level].isWhitespace()) return null
    var content = body.substring(level).trim()
    val open = content.trimEnd('#')
    if (open.isEmpty() || open.last().isWhitespace()) content = open.trimEnd()
    return MdBlock.Heading(level, parseInline(content))
}

private fun isRule(line: String): Boolean {
    if (indentOf(line) > 3) return false
    val body = line.filterNot { it.isWhitespace() }
    return body.length >= 3 && body[0] in "-*_" && body.all { it == body[0] }
}

private fun isQuote(line: String): Boolean = indentOf(line) <= 3 && line.trimStart().startsWith('>')

private fun parseQuote(lines: List<String>, from: Int, depth: Int): Step {
    val body = ArrayList<String>()
    var i = from
    while (i < lines.size && isQuote(lines[i])) {
        body.add(lines[i].trimStart().substring(1).removePrefix(" "))
        i++
    }
    return Step(MdBlock.Quote(parseBlocks(body, depth + 1).map { it.block }), i)
}

private class Marker(val ordered: Boolean, val kind: Char, val start: Int, val indent: Int, val content: Int, val rest: String)

private fun markerAt(line: String): Marker? {
    val indent = indentOf(line)
    if (indent > 3) return null
    val body = line.trimStart()
    val first = body.firstOrNull() ?: return null
    var width = 1
    var start = 0
    var ordered = false
    var kind = first
    if (first !in "-+*") {
        val digits = body.indexOfFirst { !it.isAsciiDigit() }
        if (digits !in 1..9) return null
        start = body.substring(0, digits).toInt()
        kind = body[digits]
        if (kind != '.' && kind != ')') return null
        ordered = true
        width = digits + 1
    }
    if (width == body.length) return null
    val after = body.substring(width)
    if (!after[0].isWhitespace()) return null
    if (after.isBlank()) return Marker(ordered, kind, start, indent, indent + width + 1, "")
    val pad = indentOf(after).let { if (it >= 5) 1 else it }
    return Marker(ordered, kind, start, indent, indent + width + pad, dedent(after, pad))
}

private fun Char.isAsciiDigit() = this in '0'..'9'

private fun parseList(lines: List<String>, from: Int, depth: Int): Step {
    val first = markerAt(lines[from])!!
    val items = ArrayList<List<MdBlock>>()
    var i = from
    while (i < lines.size) {
        val marker = markerAt(lines[i]) ?: break
        if (marker.ordered != first.ordered || marker.kind != first.kind || isRule(lines[i])) break
        val body = arrayListOf(marker.rest)
        i++
        while (i < lines.size) {
            val line = lines[i]
            val indent = indentOf(line)
            when {
                line.isBlank() -> body.add("")
                indent >= marker.content -> body.add(dedent(line, marker.content))
                // Some replies nest with less indent than the marker's width.
                indent > marker.indent && markerAt(line.trimStart()) != null -> body.add(line.trimStart())
                else -> break
            }
            i++
        }
        items.add(parseBlocks(body, depth + 1).map { it.block })
    }
    return Step(MdBlock.ItemList(first.ordered, first.start, items), i)
}

private fun parseParagraph(lines: List<String>, from: Int, depth: Int): Step {
    var i = from + 1
    while (i < lines.size && !lines[i].isBlank() && !interruptsParagraph(lines, i, depth)) i++
    val text = (from until i).joinToString("\n") { lines[it].trim() }
    return Step(MdBlock.Paragraph(parseInline(text)), i)
}

private fun interruptsParagraph(lines: List<String>, i: Int, depth: Int): Boolean {
    val line = lines[i]
    if (fenceAt(line) != null || headingAt(line) != null || isRule(line) || isTableStart(lines, i)) return true
    if (depth >= MAX_DEPTH) return false
    if (isQuote(line)) return true
    // As CommonMark: "…in\n1986. A year" isn't a list.
    val marker = markerAt(line) ?: return false
    return marker.rest.isNotBlank() && (!marker.ordered || marker.start == 1)
}

// Tables

private fun isTableStart(lines: List<String>, i: Int): Boolean {
    if (i + 1 >= lines.size || '|' !in lines[i] || indentOf(lines[i]) > 3) return false
    val aligns = alignsOf(lines[i + 1]) ?: return false
    return splitCells(lines[i]).size == aligns.size
}

private val DELIMITER_CELL = Regex(":?-+:?")

private fun alignsOf(line: String): List<MdAlign>? {
    if ('-' !in line || indentOf(line) > 3) return null
    val cells = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
    if (cells.any { !DELIMITER_CELL.matches(it) }) return null
    return cells.map {
        when {
            it.startsWith(':') && it.endsWith(':') -> MdAlign.CENTER
            it.endsWith(':') -> MdAlign.RIGHT
            else -> MdAlign.LEFT
        }
    }
}

// A pipe inside a code span doesn't end its cell; `\|` is a pipe, in code too.
private fun splitCells(line: String): List<String> {
    val text = line.trim()
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var endedOnPipe = false
    var i = if (text.startsWith('|')) 1 else 0
    while (i < text.length) {
        val c = text[i]
        endedOnPipe = false
        when {
            c == '\\' && i + 1 < text.length -> {
                cell.append(c).append(text[i + 1])
                i += 2
            }
            c == '`' -> {
                val run = backtickRun(text, i)
                val close = findBacktickRun(text, i + run, run)
                val end = if (close < 0) i + run else close + run
                cell.append(text.substring(i, end).replace("\\|", "|"))
                i = end
            }
            c == '|' -> {
                cells.add(cell.toString().trim())
                cell.setLength(0)
                endedOnPipe = true
                i++
            }
            else -> {
                cell.append(c)
                i++
            }
        }
    }
    if (!endedOnPipe) cells.add(cell.toString().trim())
    return cells
}

private fun parseTable(lines: List<String>, from: Int): Step {
    val rows = ArrayList<List<MdStyled>>()
    var i = from + 2
    while (i < lines.size && '|' in lines[i] && !endsTable(lines[i])) {
        rows.add(splitCells(lines[i]).map(::parseInline))
        i++
    }
    return Step(MdBlock.Table(alignsOf(lines[from + 1])!!, splitCells(lines[from]).map(::parseInline), rows), i)
}

private fun endsTable(line: String): Boolean = fenceAt(line) != null || headingAt(line) != null || isQuote(line)

// Inline

private fun backtickRun(text: String, from: Int): Int {
    var end = from
    while (end < text.length && text[end] == '`') end++
    return end - from
}

// When this fails there's no run of that length ahead, so no opener of that
// length is left to ask again: repeated searches stay cheap.
private fun findBacktickRun(text: String, from: Int, length: Int): Int {
    var i = text.indexOf('`', from)
    while (i >= 0) {
        val run = backtickRun(text, i)
        if (run == length) return i
        i = text.indexOf('`', i + run)
    }
    return -1
}

private class Tok(val text: String, val delim: Char = ' ', var count: Int = 0, val canOpen: Boolean = false, val canClose: Boolean = false) {
    var outStart = 0
    var outEnd = 0
    val visible: String get() = if (delim == ' ') text else delim.toString().repeat(count)
}

/** With [to] null, the whole of [from]; else what's between the two. */
private class Mark(val from: Tok, val to: Tok?, val kind: MdStyleKind, val url: String? = null) {
    var dropped = false
}

private const val MAX_URL = 2048
private const val NO_URL_END = " \t\n<>\"`]"
private const val URL_TAIL = ".,;:!?'*_~"

private class InlineParser(private val src: String) {
    private val toks = ArrayList<Tok>()
    private val plain = StringBuilder()
    private val marks = ArrayList<Mark>()
    private val openers = ArrayList<Tok>()
    private val openCounts = HashMap<Char, Int>()
    private val brackets = ArrayList<Pair<Tok, Int>>()
    // Bare links, with where they are, so a link around one can take it back.
    private val autolinks = ArrayList<Pair<Mark, Int>>()

    private fun add(tok: Tok): Tok {
        flush()
        toks.add(tok)
        return tok
    }

    private fun flush() {
        if (plain.isEmpty()) return
        toks.add(Tok(plain.toString()))
        plain.setLength(0)
    }

    fun parse(): MdStyled {
        var i = 0
        while (i < src.length) {
            i = when (src[i]) {
                '\\' -> backslash(i)
                '`' -> codeSpan(i)
                '*', '_', '~' -> delimiterRun(i)
                '[' -> openBracket(i)
                ']' -> closeBracket(i)
                '<' -> angleLink(i)
                'h', 'H' -> bareLink(i)
                else -> {
                    plain.append(src[i])
                    i + 1
                }
            }
        }
        flush()
        return build()
    }

    private fun backslash(i: Int): Int {
        val next = src.getOrNull(i + 1)
        return when {
            next == '\n' -> {
                plain.append('\n')
                i + 2
            }
            next != null && next.isAsciiPunctuation() -> {
                plain.append(next)
                i + 2
            }
            else -> {
                plain.append('\\')
                i + 1
            }
        }
    }

    private fun Char.isAsciiPunctuation() = this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'

    private fun codeSpan(i: Int): Int {
        val run = backtickRun(src, i)
        val close = findBacktickRun(src, i + run, run)
        if (close < 0) {
            plain.append(src, i, i + run)
            return i + run
        }
        var code = src.substring(i + run, close).replace('\n', ' ')
        if (code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.isNotBlank()) code = code.substring(1, code.length - 1)
        marks.add(Mark(add(Tok(code)), null, MdStyleKind.CODE))
        return close + run
    }

    private fun delimiterRun(i: Int): Int {
        val c = src[i]
        var end = i
        while (end < src.length && src[end] == c) end++
        val count = end - i
        if (c == '~' && count != 2) {
            plain.append(src, i, end)
            return end
        }
        val before = src.getOrNull(i - 1)
        val after = src.getOrNull(end)
        val beforeSpace = before == null || before.isWhitespace()
        val afterSpace = after == null || after.isWhitespace()
        val beforeMark = before != null && !before.isWhitespace() && !before.isLetterOrDigit()
        val afterMark = after != null && !after.isWhitespace() && !after.isLetterOrDigit()
        val left = !afterSpace && (!afterMark || beforeSpace || beforeMark)
        val right = !beforeSpace && (!beforeMark || afterSpace || afterMark)
        val canOpen = if (c == '_') left && (!right || beforeMark) else left
        val canClose = if (c == '_') right && (!left || afterMark) else right
        val tok = add(Tok("", c, count, canOpen, canClose))
        if (tok.canClose) closeAgainst(tok)
        if (tok.count > 0 && tok.canOpen) {
            openers.add(tok)
            openCounts[c] = (openCounts[c] ?: 0) + 1
        }
        return end
    }

    // Openers it passes over can never close, so each is looked at once.
    private fun closeAgainst(closer: Tok) {
        val c = closer.delim
        while (closer.count > 0 && (openCounts[c] ?: 0) > 0) {
            while (openers.last().delim != c) {
                val dropped = openers.removeAt(openers.lastIndex)
                openCounts[dropped.delim] = openCounts.getValue(dropped.delim) - 1
            }
            val opener = openers.last()
            val use = if (c != '~' && (opener.count < 2 || closer.count < 2)) 1 else 2
            opener.count -= use
            closer.count -= use
            val kind = if (c == '~') MdStyleKind.STRIKE else if (use == 2) MdStyleKind.BOLD else MdStyleKind.ITALIC
            marks.add(Mark(opener, closer, kind))
            if (opener.count == 0) {
                openers.removeAt(openers.lastIndex)
                openCounts[c] = openCounts.getValue(c) - 1
            }
        }
    }

    private fun openBracket(i: Int): Int {
        flush()
        brackets.add(Tok("[") to toks.size)
        toks.add(brackets.last().first)
        return i + 1
    }

    private fun closeBracket(i: Int): Int {
        val open = brackets.removeLastOrNull()
        val target = if (open != null && src.getOrNull(i + 1) == '(') linkTarget(i + 1) else null
        if (open == null || target == null) {
            plain.append(']')
            return i + 1
        }
        flush()
        while (autolinks.isNotEmpty() && autolinks.last().second > open.second) autolinks.removeAt(autolinks.lastIndex).first.dropped = true
        val label = Tok("")
        toks[open.second] = label
        val close = add(Tok(""))
        marks.add(Mark(label, close, MdStyleKind.LINK, target.first))
        return target.second
    }

    private fun linkTarget(open: Int): Pair<String, Int>? {
        var p = open + 1
        fun skipSpaces() {
            while (p < src.length && (src[p] == ' ' || src[p] == '\t')) p++
        }
        skipSpaces()
        val url: String
        if (src.getOrNull(p) == '<') {
            val close = src.indexOf('>', p)
            if (close < 0 || close - p > MAX_URL) return null
            url = src.substring(p + 1, close)
            p = close + 1
        } else {
            val start = p
            var depth = 0
            while (p < src.length && !src[p].isWhitespace()) {
                if (p - start > MAX_URL) return null
                if (src[p] == '(') depth++
                if (src[p] == ')' && depth-- == 0) break
                p++
            }
            url = src.substring(start, p)
        }
        skipSpaces()
        val quote = src.getOrNull(p)
        if (quote == '"' || quote == '\'') {
            val close = src.indexOf(quote, p + 1)
            if (close < 0) return null
            p = close + 1
            skipSpaces()
        }
        if (src.getOrNull(p) != ')' || !mdLinkAllowed(url)) return null
        return url to p + 1
    }

    private fun startsLink(i: Int) = src.startsWith("http://", i, ignoreCase = true) || src.startsWith("https://", i, ignoreCase = true)

    private fun angleLink(i: Int): Int {
        if (startsLink(i + 1)) {
            var end = i + 1
            while (end < src.length && src[end] !in NO_URL_END) end++
            if (src.getOrNull(end) == '>') {
                val url = src.substring(i + 1, end)
                if (mdLinkAllowed(url)) {
                    addAutolink(url)
                    return end + 1
                }
            }
        }
        plain.append('<')
        return i + 1
    }

    private fun bareLink(i: Int): Int {
        val boundary = i == 0 || !src[i - 1].isLetterOrDigit()
        if (boundary && startsLink(i)) {
            var end = i
            while (end < src.length && src[end] !in NO_URL_END) end++
            val opens = (i until end).count { src[it] == '(' }
            var closes = (i until end).count { src[it] == ')' }
            while (end > i) {
                val last = src[end - 1]
                if (last == ')' && closes > opens) closes-- else if (last !in URL_TAIL) break
                end--
            }
            val url = src.substring(i, end)
            if (mdLinkAllowed(url)) {
                addAutolink(url)
                return end
            }
        }
        plain.append(src[i])
        return i + 1
    }

    private fun addAutolink(url: String) {
        val tok = add(Tok(url))
        val mark = Mark(tok, null, MdStyleKind.LINK, url)
        marks.add(mark)
        autolinks.add(mark to toks.lastIndex)
    }

    private fun build(): MdStyled {
        val out = StringBuilder()
        for (tok in toks) {
            tok.outStart = out.length
            out.append(tok.visible)
            tok.outEnd = out.length
        }
        val styles = marks.filterNot { it.dropped }.mapNotNull { mark ->
            val start = if (mark.to == null) mark.from.outStart else mark.from.outEnd
            val end = (mark.to ?: mark.from).let { if (mark.to == null) it.outEnd else it.outStart }
            if (start < end) MdStyle(start, end, mark.kind, mark.url) else null
        }
        return MdStyled(out.toString(), styles.sortedWith(compareBy({ it.start }, { it.kind.ordinal })))
    }
}

private fun parseInline(text: String): MdStyled = InlineParser(text).parse()
