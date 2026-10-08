package io.github.est4s.terminal.core

/** Bad JSON: what was wrong, and at which character. */
class JsonException(message: String) : Exception(message)

/** Nesting deeper than this is refused, so bad input can't overflow the stack. */
private const val MAX_JSON_DEPTH = 512

/**
 * Reads JSON into plain values: Map<String, Any?> (keys in order),
 * List<Any?>, String, Long (whole numbers that fit) or Double, Boolean
 * and null. Core has no JSON library, and GitHub's answers need no more.
 */
fun parseJson(text: String): Any? {
    val reader = JsonReader(text)
    val value = reader.value(0)
    reader.end()
    return value
}

private class JsonReader(private val text: String) {
    private var at = 0

    fun end() {
        space()
        if (at < text.length) fail("expected the end")
    }

    fun value(depth: Int): Any? {
        space()
        if (at >= text.length) fail("expected a value")
        return when (val c = text[at]) {
            '{' -> obj(depth + 1)
            '[' -> array(depth + 1)
            '"' -> string()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        if (depth > MAX_JSON_DEPTH) fail("nested too deep")
        at++
        val map = LinkedHashMap<String, Any?>()
        space()
        if (peek() == '}') { at++; return map }
        while (true) {
            space()
            if (peek() != '"') fail("expected a key")
            val key = string()
            space()
            expect(':')
            map[key] = value(depth)
            space()
            when (peek()) {
                ',' -> at++
                '}' -> { at++; return map }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun array(depth: Int): List<Any?> {
        if (depth > MAX_JSON_DEPTH) fail("nested too deep")
        at++
        val list = ArrayList<Any?>()
        space()
        if (peek() == ']') { at++; return list }
        while (true) {
            list.add(value(depth))
            space()
            when (peek()) {
                ',' -> at++
                ']' -> { at++; return list }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (true) {
            if (at >= text.length) fail("unterminated string")
            val c = text[at++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> escape(out)
                c < ' ' -> { at--; fail("control character in a string") }
                else -> out.append(c)
            }
        }
    }

    private fun escape(out: StringBuilder) {
        if (at >= text.length) fail("unterminated string")
        when (val c = text[at++]) {
            '"', '\\', '/' -> out.append(c)
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                val unit = hex4()
                when {
                    unit.isHighSurrogate() -> {
                        if (!text.startsWith("\\u", at)) fail("lone surrogate")
                        at += 2
                        val low = hex4()
                        if (!low.isLowSurrogate()) fail("lone surrogate")
                        out.append(unit).append(low)
                    }
                    unit.isLowSurrogate() -> fail("lone surrogate")
                    else -> out.append(unit)
                }
            }
            else -> { at--; fail("bad escape '\\$c'") }
        }
    }

    private fun hex4(): Char {
        if (at + 4 > text.length) fail("bad \\u escape")
        val code = text.substring(at, at + 4).toIntOrNull(16)
        if (code == null || text.substring(at, at + 4).any { it == '+' || it == '-' }) fail("bad \\u escape")
        at += 4
        return code.toChar()
    }

    private fun number(): Any {
        val start = at
        if (peek() == '-') at++
        when {
            peek() == '0' -> at++
            peek() in '1'..'9' -> digits()
            else -> fail("bad number")
        }
        var whole = true
        if (peek() == '.') {
            at++
            if (peek() !in '0'..'9') fail("bad number")
            digits()
            whole = false
        }
        if (peek() == 'e' || peek() == 'E') {
            at++
            if (peek() == '+' || peek() == '-') at++
            if (peek() !in '0'..'9') fail("bad number")
            digits()
            whole = false
        }
        val literal = text.substring(start, at)
        return (if (whole) literal.toLongOrNull() else null) ?: literal.toDouble()
    }

    private fun digits() {
        while (peek() in '0'..'9') at++
    }

    private fun word(word: String, value: Any?): Any? {
        if (!text.startsWith(word, at)) fail("unexpected '${text[at]}'")
        at += word.length
        return value
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        at++
    }

    private fun peek(): Char? = if (at < text.length) text[at] else null

    private fun space() {
        while (at < text.length && text[at] in " \t\n\r") at++
    }

    private fun fail(what: String): Nothing = throw JsonException("$what at ${at + 1}")
}
