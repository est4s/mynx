package io.github.est4s.terminal.core

/*
 * Syntax highlighting for the text viewer and chat's code blocks
 * (docs/TOUCH-DESIGN.md, "Viewers"). Most languages are a table ([Lang]:
 * words, comments, strings, numbers) run by one scanner; HTML/XML, CSS,
 * Markdown, Makefiles and diffs have small lexers of their own. It never
 * throws on odd or unfinished text: an unclosed string ends at its line, an
 * unclosed block comment at the text's end. One pass, so 1 MB takes a
 * fraction of a second.
 */

enum class SyntaxKind { KEYWORD, TYPE, STRING, COMMENT, NUMBER, CONSTANT, FUNCTION, TAG, ATTRIBUTE, OPERATOR, HEADING, ADDED, REMOVED }

/** [kind] for the text from [start] to [end] (exclusive). */
data class SyntaxSpan(val start: Int, val end: Int, val kind: SyntaxKind)

/**
 * [text]'s spans in [language] (a name from [SYNTAX_LANGUAGES] or an alias,
 * see [syntaxLanguage]): sorted, not overlapping, inside the text. Text in
 * no span is drawn plain. An unknown language has none.
 */
fun highlight(text: String, language: String): List<SyntaxSpan> {
    val name = syntaxLanguage(language) ?: return emptyList()
    val out = Spans()
    when (name) {
        "html", "xml" -> markup(text, out)
        "css" -> css(text, out)
        "markdown" -> markdown(text, out)
        "diff" -> diff(text, out)
        "makefile" -> makefile(text, out)
        else -> scan(text, 0, text.length, LANGS.getValue(name), out)
    }
    return out.list
}

private val ALIASES = mapOf(
    "py" to "python", "python3" to "python", "pyw" to "python",
    "sh" to "shell", "bash" to "shell", "zsh" to "shell", "ksh" to "shell", "dash" to "shell", "console" to "shell",
    "shellscript" to "shell", "kt" to "kotlin", "kts" to "kotlin",
    "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "node" to "javascript",
    "ts" to "typescript", "tsx" to "typescript", "mts" to "typescript", "cts" to "typescript",
    "h" to "c", "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp", "hxx" to "cpp",
    "rs" to "rust", "golang" to "go", "rb" to "ruby", "mysql" to "sql", "postgresql" to "sql", "sqlite" to "sql",
    "jsonc" to "json", "json5" to "json", "yml" to "yaml", "conf" to "ini", "cfg" to "ini", "properties" to "ini",
    "htm" to "html", "xhtml" to "html", "svg" to "xml", "plist" to "xml",
    "md" to "markdown", "mkd" to "markdown", "patch" to "diff", "docker" to "dockerfile",
    "make" to "makefile", "mk" to "makefile", "mak" to "makefile",
)

/** The languages [highlight] knows, by their own names. */
val SYNTAX_LANGUAGES: Set<String> = setOf(
    "python", "shell", "kotlin", "java", "javascript", "typescript", "c", "cpp", "rust", "go", "lua", "ruby",
    "php", "sql", "json", "yaml", "toml", "ini", "css", "dockerfile", "makefile", "html", "xml", "markdown", "diff",
)

/** [name] (a language or an alias such as `py`, `bash`, `c++`; case ignored) as one of [SYNTAX_LANGUAGES], or null. */
fun syntaxLanguage(name: String): String? {
    val key = name.trim().lowercase()
    return if (key in SYNTAX_LANGUAGES) key else ALIASES[key]
}

// Collects spans, dropping empty ones and any that would go back.
private class Spans {
    val list = ArrayList<SyntaxSpan>()
    private var last = 0

    fun add(start: Int, end: Int, kind: SyntaxKind) {
        if (end <= start || start < last) return
        list += SyntaxSpan(start, end, kind)
        last = end
    }

    /** [text]'s own spans in [language], moved by [offset]. */
    fun embed(text: String, offset: Int, language: String) {
        for (span in highlight(text, language)) add(span.start + offset, span.end + offset, span.kind)
    }
}

private class Quote(val open: String, val close: String = open, val escape: Boolean = true, val multiline: Boolean = false)

private enum class Keys { NONE, JSON, YAML, EQUALS }

private class Lang(
    keywords: String,
    types: String = "",
    constants: String = "",
    builtins: String = "",
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    quotes: List<Quote> = emptyList(),
    val ignoreCase: Boolean = false,
    val identChars: String = "_",
    /** Prefixes that start a string (`r"…"`); those with an `r` are raw. */
    val stringPrefixes: Set<String> = emptySet(),
    /** `@name`: an annotation or decorator. */
    val annotations: Boolean = false,
    /** A capitalised name is a type (Kotlin, Java, Rust…). */
    val capitalTypes: Boolean = false,
    /** `#` and the like start a comment only after a space or at a line's start (shell, YAML). */
    val commentAfterSpace: Boolean = false,
    /** `$name`, `${…}` (and Make's `$(…)`) are variables. */
    val variables: Boolean = false,
    /** `#word` at a line's start (C's preprocessor). */
    val preprocessor: Boolean = false,
    /** Rust: `'a'` is a char but `'a` a lifetime; `r#"…"#`; `name!` a macro. */
    val rust: Boolean = false,
    val keys: Keys = Keys.NONE,
    /** `[section]` lines (TOML, INI). */
    val sections: Boolean = false,
    /** Keywords count only as a line's first word (Dockerfile). */
    val keywordsFirst: Boolean = false,
    val operators: String = "",
) {
    private fun words(list: String) = list.split(' ').filter { it.isNotEmpty() }.map { if (ignoreCase) it.lowercase() else it }.toHashSet()

    val keywords = words(keywords)
    val types = words(types)
    val constants = words(constants)
    val builtins = words(builtins)

    // Longest first, so `"""` wins over `"`.
    val quotes = quotes.sortedByDescending { it.open.length }
    val quoteStarts = this.quotes.map { it.open[0] }.toSet()
}

private val C_COMMENTS = listOf("/*" to "*/")
private val DQ = Quote("\"")
private val SQ = Quote("'")

private val C_TYPES = "void char short int long float double signed unsigned bool _Bool size_t ssize_t " +
    "int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t FILE struct union enum"
private val C_KEYWORDS = "auto break case const continue default do else extern for goto if inline register restrict " +
    "return sizeof static switch typedef volatile while"
private val JS_KEYWORDS = "async await break case catch class const continue debugger default delete do else export " +
    "extends finally for from function get if import in instanceof let new of return set static super switch " +
    "this throw try typeof var void while with yield"

private val LANGS: Map<String, Lang> = mapOf(
    "python" to Lang(
        "and as assert async await break class continue def del elif else except finally for from global if import " +
            "in is lambda nonlocal not or pass raise return try while with yield match case",
        types = "int float str bytes bool list dict set tuple object type complex frozenset bytearray",
        constants = "True False None self cls __name__ __file__",
        builtins = "print len range open isinstance super",
        lineComments = listOf("#"),
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true), DQ, SQ),
        stringPrefixes = setOf("r", "b", "f", "u", "rb", "br", "fr", "rf"),
        annotations = true,
    ),
    "shell" to Lang(
        "if then else elif fi for while until do done case esac in function select time return local export " +
            "readonly declare unset break continue",
        constants = "true false",
        builtins = "echo printf cd exit source set shift trap eval exec read test alias",
        lineComments = listOf("#"),
        quotes = listOf(Quote("\"", multiline = true), Quote("'", escape = false, multiline = true)),
        identChars = "_-.",
        commentAfterSpace = true,
        variables = true,
        operators = "|&;<>",
    ),
    "kotlin" to Lang(
        "package import class interface object fun val var if else when for while do return break continue " +
            "throw try catch finally is in as typealias constructor init companion data sealed enum open abstract " +
            "override private protected internal public lateinit inline suspend operator infix const by where " +
            "get set out vararg reified annotation inner value expect actual",
        types = "Int Long Short Byte Double Float Boolean Char String Unit Any Nothing List Map Set Array",
        constants = "true false null this super it",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(Quote("\"\"\"", escape = false, multiline = true), DQ, SQ),
        annotations = true,
        capitalTypes = true,
    ),
    "java" to Lang(
        "abstract assert break case catch class const continue default do else enum extends final finally for " +
            "goto if implements import instanceof interface native new package private protected public return " +
            "static strictfp super switch synchronized this throw throws transient try volatile while var record " +
            "sealed permits yield",
        types = "boolean byte char short int long float double void String Object",
        constants = "true false null",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(Quote("\"\"\"", multiline = true), DQ, SQ),
        annotations = true,
        capitalTypes = true,
    ),
    "javascript" to Lang(
        JS_KEYWORDS,
        constants = "true false null undefined NaN Infinity",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(DQ, SQ, Quote("`", multiline = true)),
        identChars = "_$",
        annotations = true,
    ),
    "typescript" to Lang(
        "$JS_KEYWORDS type interface enum implements private protected public readonly declare namespace module " +
            "abstract as is keyof infer satisfies",
        types = "string number boolean any unknown never object symbol bigint void",
        constants = "true false null undefined NaN Infinity",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(DQ, SQ, Quote("`", multiline = true)),
        identChars = "_$",
        annotations = true,
        capitalTypes = true,
    ),
    "c" to Lang(
        C_KEYWORDS,
        types = C_TYPES,
        constants = "NULL true false",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(DQ, SQ),
        preprocessor = true,
    ),
    "cpp" to Lang(
        "$C_KEYWORDS class namespace template typename public private protected virtual override final new delete " +
            "operator using try catch throw friend explicit constexpr noexcept static_cast dynamic_cast " +
            "reinterpret_cast const_cast decltype mutable",
        types = "$C_TYPES auto std string vector map wchar_t char16_t char32_t",
        constants = "nullptr NULL true false this",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(DQ, SQ),
        preprocessor = true,
    ),
    "rust" to Lang(
        "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move " +
            "mut pub ref return static struct super trait type unsafe use where while",
        types = "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str",
        constants = "true false self Self None Some Ok Err",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(Quote("\"", multiline = true), SQ),
        stringPrefixes = setOf("b"),
        capitalTypes = true,
        rust = true,
    ),
    "go" to Lang(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map " +
            "package range return select struct switch type var",
        types = "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string " +
            "uint uint8 uint16 uint32 uint64 uintptr any",
        constants = "true false nil iota",
        builtins = "append cap close copy delete len make new panic print println recover",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(DQ, SQ, Quote("`", escape = false, multiline = true)),
    ),
    "lua" to Lang(
        "and break do else elseif end for function goto if in local not or repeat return then until while",
        constants = "true false nil self",
        lineComments = listOf("--"),
        blockComments = listOf("--[[" to "]]"),
        quotes = listOf(DQ, SQ, Quote("[[", "]]", escape = false, multiline = true)),
    ),
    "ruby" to Lang(
        "alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo " +
            "rescue retry return super then undef unless until when while yield require attr_accessor attr_reader",
        constants = "true false nil self",
        lineComments = listOf("#"),
        blockComments = listOf("=begin" to "=end"),
        quotes = listOf(Quote("\"", multiline = true), Quote("'", multiline = true), Quote("`")),
        identChars = "_?!",
        annotations = true,
        capitalTypes = true,
    ),
    "php" to Lang(
        "abstract and as break case catch class clone const continue declare default do echo else elseif empty " +
            "enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function " +
            "global goto if implements include include_once instanceof insteadof interface isset list match " +
            "namespace new or print private protected public readonly require require_once return static switch " +
            "throw trait try unset use var while yield",
        types = "int float string bool array object callable iterable void mixed",
        constants = "true false null TRUE FALSE NULL",
        lineComments = listOf("//", "#"),
        blockComments = C_COMMENTS,
        quotes = listOf(Quote("\"", multiline = true), Quote("'", multiline = true)),
        identChars = "_$",
    ),
    "sql" to Lang(
        "select from where insert into values update set delete create table drop alter add column index view " +
            "primary key foreign references join inner left right outer full on as and or not null is in between " +
            "like order by group having limit offset distinct union all case when then else end begin commit " +
            "rollback transaction if exists default unique check constraint with returning asc desc",
        types = "int integer bigint smallint real double float numeric decimal text varchar char boolean date " +
            "timestamp blob",
        constants = "true false",
        ignoreCase = true,
        lineComments = listOf("--"),
        blockComments = C_COMMENTS,
        quotes = listOf(Quote("'", escape = false, multiline = true), Quote("\"", escape = false)),
    ),
    "json" to Lang(
        "",
        constants = "true false null",
        lineComments = listOf("//"),
        blockComments = C_COMMENTS,
        quotes = listOf(DQ),
        keys = Keys.JSON,
    ),
    "yaml" to Lang(
        "",
        constants = "true false null yes no on off True False Null ~",
        lineComments = listOf("#"),
        quotes = listOf(DQ, Quote("'", escape = false)),
        identChars = "_-.",
        commentAfterSpace = true,
        keys = Keys.YAML,
    ),
    "toml" to Lang(
        "",
        constants = "true false inf nan",
        lineComments = listOf("#"),
        quotes = listOf(
            Quote("\"\"\"", multiline = true), Quote("'''", escape = false, multiline = true), DQ, Quote("'", escape = false),
        ),
        identChars = "_-",
        keys = Keys.EQUALS,
        sections = true,
    ),
    "ini" to Lang(
        "",
        constants = "true false yes no on off",
        lineComments = listOf(";", "#"),
        quotes = listOf(DQ),
        identChars = "_-.",
        commentAfterSpace = true,
        keys = Keys.EQUALS,
        sections = true,
    ),
    "dockerfile" to Lang(
        "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal " +
            "healthcheck shell as",
        ignoreCase = true,
        lineComments = listOf("#"),
        quotes = listOf(DQ, Quote("'", escape = false)),
        identChars = "_-.",
        commentAfterSpace = true,
        variables = true,
        keywordsFirst = true,
    ),
)

private fun isIdentStart(c: Char) = c == '_' || Character.isLetter(c)

private fun isIdentPart(c: Char, extra: String) = Character.isLetterOrDigit(c) || extra.indexOf(c) >= 0

private fun lineEnd(text: String, from: Int, to: Int): Int {
    val at = text.indexOf('\n', from)
    return if (at < 0 || at > to) to else at
}

/** The table-driven scanner, over [from] until [to]. */
private fun scan(text: String, from: Int, to: Int, lang: Lang, out: Spans) {
    var i = from
    var lineStart = true
    var firstWord = true
    var lastSignificant = ' '
    while (i < to) {
        val c = text[i]
        if (c == '\n') {
            lineStart = true
            firstWord = true
            i++
            continue
        }
        if (c == ' ' || c == '\t' || c == '\r') {
            i++
            continue
        }
        val start = i
        i = token(text, i, to, lang, out, lineStart, firstWord, lastSignificant)
        if (i <= start) i = start + 1
        // A YAML list item's dash leaves the line's key still to come.
        val dash = lang.keys == Keys.YAML && c == '-' && i == start + 1
        if (!dash) lineStart = false
        if (!dash && (Character.isLetterOrDigit(c) || c == '_')) firstWord = false
        lastSignificant = text[i - 1]
    }
}

// Reads one token at [i] (not a space), adds its span if any, and returns where it ends.
private fun token(text: String, i: Int, to: Int, lang: Lang, out: Spans, lineStart: Boolean, firstWord: Boolean, before: Char): Int {
    val c = text[i]
    val boundary = i == 0 || text[i - 1].isWhitespace()
    for ((open, close) in lang.blockComments) {
        if (text.startsWith(open, i) && (!lang.commentAfterSpace || boundary)) {
            val at = text.indexOf(close, i + open.length)
            val end = if (at < 0 || at + close.length > to) to else at + close.length
            out.add(i, end, SyntaxKind.COMMENT)
            return end
        }
    }
    for (open in lang.lineComments) {
        if (text.startsWith(open, i) && (!lang.commentAfterSpace || boundary)) {
            val end = lineEnd(text, i, to)
            out.add(i, end, SyntaxKind.COMMENT)
            return end
        }
    }
    if (lineStart && lang.sections && c == '[') {
        val eol = lineEnd(text, i, to)
        val close = text.indexOf(']', i)
        val end = if (close in i until eol) close + 1 else eol
        out.add(i, end, SyntaxKind.HEADING)
        return end
    }
    if (lineStart && lang.keys == Keys.YAML && c == '-' && i + 1 < to && (text[i + 1] == ' ' || text[i + 1] == '\n')) {
        out.add(i, i + 1, SyntaxKind.OPERATOR)
        return i + 1
    }
    if (lineStart && (lang.keys == Keys.YAML || lang.keys == Keys.EQUALS) && c != '"' && c != '\'' && c != '[' && c != '{') {
        val key = lineKey(text, i, to, lang.keys)
        if (key > i) {
            out.add(i, key, SyntaxKind.ATTRIBUTE)
            return key
        }
    }
    if (lang.preprocessor && lineStart && c == '#') {
        var j = i + 1
        while (j < to && (text[j] == ' ' || text[j] == '\t')) j++
        while (j < to && isIdentPart(text[j], "_")) j++
        out.add(i, j, SyntaxKind.KEYWORD)
        return j
    }
    if (c in lang.quoteStarts) {
        for (quote in lang.quotes) {
            if (text.startsWith(quote.open, i)) {
                if (lang.rust && c == '\'' && !rustChar(text, i, to)) return i + 1
                return string(text, i, i, to, quote, quote.escape, lang, out)
            }
        }
    }
    if (lang.annotations && c == '@' && i + 1 < to && isIdentStart(text[i + 1])) {
        var j = i + 1
        while (j < to && (isIdentPart(text[j], lang.identChars) || text[j] == '.')) j++
        out.add(i, j, SyntaxKind.ATTRIBUTE)
        return j
    }
    if (lang.variables && c == '$' && i + 1 < to) {
        val end = variable(text, i, to)
        if (end > i + 1) {
            out.add(i, end, SyntaxKind.CONSTANT)
            return end
        }
        return i + 1
    }
    if (c.isDigit() || (c == '.' && i + 1 < to && text[i + 1].isDigit() && !isIdentPart(before, lang.identChars))) {
        val end = number(text, i, to)
        out.add(i, end, SyntaxKind.NUMBER)
        return end
    }
    if (isIdentStart(c)) {
        var j = i + 1
        while (j < to && isIdentPart(text[j], lang.identChars)) j++
        val word = text.substring(i, j)
        val key = if (lang.ignoreCase) word.lowercase() else word
        if (j < to && (key in lang.stringPrefixes || (lang.rust && (word == "r" || word == "br")))) {
            if (lang.rust && word.endsWith("r") && (text[j] == '#' || text[j] == '"')) return rustRaw(text, i, j, to, out)
            for (quote in lang.quotes) {
                if (text.startsWith(quote.open, j)) return string(text, i, j, to, quote, quote.escape && 'r' !in key, lang, out)
            }
        }
        val kind = when {
            key in lang.keywords && (!lang.keywordsFirst || firstWord) -> SyntaxKind.KEYWORD
            key in lang.types -> SyntaxKind.TYPE
            key in lang.constants -> SyntaxKind.CONSTANT
            lang.rust && j < to && text[j] == '!' && (j + 1 >= to || text[j + 1] != '=') -> {
                out.add(i, j + 1, SyntaxKind.FUNCTION)
                return j + 1
            }
            j < to && text[j] == '(' -> SyntaxKind.FUNCTION
            key in lang.builtins -> SyntaxKind.FUNCTION
            lang.capitalTypes && word[0].isUpperCase() && word.any { it.isLowerCase() } -> SyntaxKind.TYPE
            else -> null
        }
        if (kind != null) out.add(i, j, kind)
        return j
    }
    if (lang.operators.isNotEmpty() && lang.operators.indexOf(c) >= 0) {
        var j = i + 1
        while (j < to && lang.operators.indexOf(text[j]) >= 0) j++
        out.add(i, j, SyntaxKind.OPERATOR)
        return j
    }
    return i + 1
}

// A string from [start] (its prefix) whose quote opens at [open].
private fun string(text: String, start: Int, open: Int, to: Int, quote: Quote, escape: Boolean, lang: Lang, out: Spans): Int {
    var j = open + quote.open.length
    var end = to
    while (j < to) {
        val ch = text[j]
        if (escape && ch == '\\') {
            j += 2
            continue
        }
        if (text.startsWith(quote.close, j)) {
            end = j + quote.close.length
            break
        }
        if (ch == '\n' && !quote.multiline) {
            end = j
            break
        }
        j++
    }
    end = minOf(end, to)
    var kind = SyntaxKind.STRING
    if (lang.keys == Keys.JSON) {
        var k = end
        while (k < to && (text[k] == ' ' || text[k] == '\t')) k++
        if (k < to && text[k] == ':') kind = SyntaxKind.ATTRIBUTE
    }
    out.add(start, end, kind)
    return end
}

// Rust's `'x'`, `'\n'`, `'\u{1F600}'` are chars; `'a` (a lifetime) isn't.
private fun rustChar(text: String, i: Int, to: Int): Boolean {
    if (i + 2 >= to) return false
    if (text[i + 1] == '\\') {
        val close = text.indexOf('\'', i + 2)
        return close in i + 3..minOf(i + 12, to - 1)
    }
    val next = if (Character.isHighSurrogate(text[i + 1])) i + 3 else i + 2
    return next < to && text[next] == '\''
}

// Rust's `r"…"` and `r#"…"#` (word from [start] to [quote]).
private fun rustRaw(text: String, start: Int, quote: Int, to: Int, out: Spans): Int {
    var j = quote
    while (j < to && text[j] == '#') j++
    if (j >= to || text[j] != '"') return quote
    val close = "\"" + "#".repeat(j - quote)
    val at = text.indexOf(close, j + 1)
    val end = if (at < 0 || at + close.length > to) to else at + close.length
    out.add(start, end, SyntaxKind.STRING)
    return end
}

// `$name`, `$1`, `$@`, `${…}`, `$(…)` (the last for Make; shell's `$(` is no variable).
private fun variable(text: String, i: Int, to: Int): Int {
    val c = text[i + 1]
    val eol = lineEnd(text, i, to)
    return when {
        c == '{' -> text.indexOf('}', i).let { if (it in i until eol) it + 1 else eol }
        isIdentStart(c) -> {
            var j = i + 1
            while (j < to && isIdentPart(text[j], "_")) j++
            j
        }
        c.isDigit() || c in "@?#*!$<^+%-" -> i + 2
        else -> i + 1
    }
}

private fun number(text: String, i: Int, to: Int): Int {
    var j = i
    fun digits(ok: (Char) -> Boolean) {
        while (j < to && (ok(text[j]) || text[j] == '_')) j++
    }
    if (text[i] == '0' && i + 1 < to && text[i + 1] in "xXbBoO") {
        j = i + 2
        digits { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    } else {
        digits { it.isDigit() }
        if (j + 1 < to && text[j] == '.' && text[j + 1].isDigit()) {
            j++
            digits { it.isDigit() }
        }
        if (j < to && text[j] in "eE") {
            val k = if (j + 1 < to && text[j + 1] in "+-") j + 2 else j + 1
            if (k < to && text[k].isDigit()) {
                j = k
                digits { it.isDigit() }
            }
        }
    }
    while (j < to && Character.isLetter(text[j])) j++
    return j
}

// Where a `key:` (YAML) or `key =` (TOML, INI) line's key ends, else [i].
private fun lineKey(text: String, i: Int, to: Int, keys: Keys): Int {
    val eol = lineEnd(text, i, to)
    var j = i
    while (j < eol) {
        val c = text[j]
        if (keys == Keys.YAML && c == ':' && (j + 1 == eol || text[j + 1] == ' ' || text[j + 1] == '\t')) break
        if (keys == Keys.EQUALS && (c == '=' || c == ':')) break
        if (c == '#' || c == '"' || c == '\'' || (keys == Keys.EQUALS && c == ' ' && !restIsSeparator(text, j, eol))) return i
        j++
    }
    if (j >= eol) return i
    var end = j
    while (end > i && text[end - 1].isWhitespace()) end--
    return end
}

private fun restIsSeparator(text: String, from: Int, eol: Int): Boolean {
    var j = from
    while (j < eol && text[j] == ' ') j++
    return j < eol && (text[j] == '=' || text[j] == ':')
}

// HTML and XML: comments, `<!…>` and `<?…?>`, tags with their attributes,
// entities; `<script>` and `<style>` hold JavaScript and CSS.
private fun markup(text: String, out: Spans) {
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            text.startsWith("<!--", i) -> {
                val at = text.indexOf("-->", i + 4)
                val end = if (at < 0) n else at + 3
                out.add(i, end, SyntaxKind.COMMENT)
                i = end
            }
            text.startsWith("<![CDATA[", i) -> {
                val at = text.indexOf("]]>", i)
                val end = if (at < 0) n else at + 3
                out.add(i, end, SyntaxKind.STRING)
                i = end
            }
            c == '<' && i + 1 < n && (text[i + 1] == '!' || text[i + 1] == '?') -> {
                val at = text.indexOf('>', i)
                val end = if (at < 0) n else at + 1
                out.add(i, end, SyntaxKind.KEYWORD)
                i = end
            }
            c == '<' && i + 1 < n && (isIdentStart(text[i + 1]) || text[i + 1] == '/') -> i = tag(text, i, out)
            c == '&' -> {
                var j = i + 1
                while (j < n && j - i < 12 && (text[j].isLetterOrDigit() || text[j] == '#')) j++
                if (j < n && text[j] == ';' && j > i + 1) {
                    out.add(i, j + 1, SyntaxKind.CONSTANT)
                    i = j + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
}

private fun tag(text: String, start: Int, out: Spans): Int {
    val n = text.length
    var j = start + 1
    val closing = text[j] == '/'
    if (closing) j++
    val nameStart = j
    while (j < n && (isIdentPart(text[j], "-_:.") )) j++
    val name = text.substring(nameStart, j).lowercase()
    out.add(start, j, SyntaxKind.TAG)
    while (j < n) {
        val c = text[j]
        when {
            c == '>' -> {
                out.add(j, j + 1, SyntaxKind.TAG)
                j++
                break
            }
            c == '/' && j + 1 < n && text[j + 1] == '>' -> {
                out.add(j, j + 2, SyntaxKind.TAG)
                return j + 2
            }
            c == '"' || c == '\'' -> {
                val at = text.indexOf(c, j + 1)
                val end = if (at < 0) n else at + 1
                out.add(j, end, SyntaxKind.STRING)
                j = end
            }
            c == '=' -> {
                j++
                while (j < n && text[j].isWhitespace()) j++
                if (j < n && text[j] != '"' && text[j] != '\'' && text[j] != '>') {
                    val from = j
                    while (j < n && !text[j].isWhitespace() && text[j] != '>') j++
                    out.add(from, j, SyntaxKind.STRING)
                }
            }
            c == '<' -> return j
            isIdentStart(c) -> {
                val from = j
                while (j < n && isIdentPart(text[j], "-_:.")) j++
                out.add(from, j, SyntaxKind.ATTRIBUTE)
            }
            else -> j++
        }
    }
    if (closing || (name != "script" && name != "style")) return j
    val close = text.indexOf("</$name", j, ignoreCase = true).let { if (it < 0) n else it }
    out.embed(text.substring(j, close), j, if (name == "script") "javascript" else "css")
    return close
}

// CSS: a name is a selector (TAG) when a `{` comes before the next `;` or
// `}`, else a property (ATTRIBUTE) when a `:` follows it.
private fun css(text: String, out: Spans) {
    val n = text.length
    var i = 0
    var structural = -1
    var selector = false
    while (i < n) {
        if (i > structural) {
            structural = i
            while (structural < n && text[structural] !in "{};") {
                if (text.startsWith("/*", structural)) {
                    structural = text.indexOf("*/", structural + 2).let { if (it < 0) n else it + 1 }
                }
                structural++
            }
            selector = structural < n && text[structural] == '{'
        }
        val c = text[i]
        when {
            text.startsWith("/*", i) -> {
                val at = text.indexOf("*/", i + 2)
                val end = if (at < 0) n else at + 2
                out.add(i, end, SyntaxKind.COMMENT)
                i = end
            }
            c == '"' || c == '\'' -> {
                val eol = lineEnd(text, i, n)
                var j = i + 1
                while (j < eol && text[j] != c) j += if (text[j] == '\\') 2 else 1
                val end = minOf(if (j < eol) j + 1 else eol, n)
                out.add(i, end, SyntaxKind.STRING)
                i = end
            }
            c == '@' -> {
                var j = i + 1
                while (j < n && isIdentPart(text[j], "-_")) j++
                out.add(i, j, SyntaxKind.KEYWORD)
                i = j
            }
            selector && (isIdentStart(c) || c == '.' || c == '#' || c == ':' || c == '*') -> {
                var j = i + 1
                while (j < n && isIdentPart(text[j], "-_")) j++
                out.add(i, j, SyntaxKind.TAG)
                i = j
            }
            c == '#' -> {
                var j = i + 1
                while (j < n && text[j].isLetterOrDigit()) j++
                out.add(i, j, SyntaxKind.NUMBER)
                i = j
            }
            c.isDigit() || (c == '.' && i + 1 < n && text[i + 1].isDigit()) -> {
                var j = number(text, i, n)
                if (j < n && text[j] == '%') j++
                out.add(i, j, SyntaxKind.NUMBER)
                i = j
            }
            isIdentStart(c) || c == '-' -> {
                var j = i + 1
                while (j < n && isIdentPart(text[j], "-_")) j++
                var k = j
                while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                when {
                    k < n && text[k] == ':' -> out.add(i, j, SyntaxKind.ATTRIBUTE)
                    k < n && text[k] == '(' -> out.add(i, j, SyntaxKind.FUNCTION)
                }
                i = j
            }
            c == '!' && text.startsWith("important", i + 1) -> {
                out.add(i, i + 10, SyntaxKind.KEYWORD)
                i += 10
            }
            else -> i++
        }
    }
}

private val MAKE_DIRECTIVES = setOf(
    "include", "-include", "sinclude", "ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "define", "endef",
    "export", "unexport", "override", "private", "vpath",
)
private val MAKE_LINE = Lang("", lineComments = listOf("#"), variables = true, quotes = listOf(DQ, Quote("'", escape = false)))
private val MAKE_ASSIGN = Regex("""^([A-Za-z0-9_.\-]+)\s*(::=|:=|\?=|\+=|!=|=)""")
private val MAKE_TARGET = Regex("""^([^:#=\s][^:#=]*?)\s*::?(?!=)""")

// Makefiles: variables set, targets, directives; recipes (tab lines) and the
// rest scanned for `$(…)` variables, strings and comments.
private fun makefile(text: String, out: Spans) {
    var i = 0
    val n = text.length
    while (i <= n) {
        val eol = lineEnd(text, i, n)
        var from = i
        if (i < eol && text[i] != '\t' && text[i] != '#') {
            val line = text.substring(i, eol)
            val word = line.takeWhile { !it.isWhitespace() && it != ':' }
            val assign = MAKE_ASSIGN.find(line)
            val target = MAKE_TARGET.find(line)
            when {
                word in MAKE_DIRECTIVES -> {
                    out.add(i, i + word.length, SyntaxKind.KEYWORD)
                    from = i + word.length
                }
                assign != null -> {
                    out.add(i, i + assign.groupValues[1].length, SyntaxKind.ATTRIBUTE)
                    from = i + assign.range.last + 1
                }
                target != null && !target.groupValues[1].contains('$') -> {
                    val name = target.groupValues[1]
                    out.add(i, i + name.length, if (name.startsWith('.')) SyntaxKind.KEYWORD else SyntaxKind.FUNCTION)
                    from = i + name.length
                }
            }
        }
        makeVariables(text, from, eol, out)
        i = eol + 1
    }
}

private fun makeVariables(text: String, from: Int, to: Int, out: Spans) {
    var i = from
    var start = from
    while (i < to) {
        if (text[i] == '$' && i + 1 < to && text[i + 1] == '(') {
            scan(text, start, i, MAKE_LINE, out)
            var depth = 0
            var j = i + 1
            while (j < to) {
                if (text[j] == '(') depth++
                if (text[j] == ')' && --depth == 0) break
                j++
            }
            val end = minOf(j + 1, to)
            out.add(i, end, SyntaxKind.CONSTANT)
            i = end
            start = end
        } else {
            i++
        }
    }
    scan(text, start, to, MAKE_LINE, out)
}

private val MD_LIST = Regex("""^\s*([-*+]|\d{1,9}[.)])\s""")

// Markdown: headings, fences (their code highlighted when the language is
// known), quotes and list marks, code spans and link targets.
private fun markdown(text: String, out: Spans) {
    val n = text.length
    var i = 0
    var fence: String? = null
    var codeStart = 0
    var codeLanguage: String? = null
    while (i <= n) {
        val eol = lineEnd(text, i, n)
        val line = text.substring(i, eol)
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        if (fence != null) {
            if (trimmed.startsWith(fence) && trimmed.trimEnd().all { it == fence!![0] }) {
                if (codeLanguage != null && i > codeStart) out.embed(text.substring(codeStart, i - 1), codeStart, codeLanguage)
                out.add(i, eol, SyntaxKind.COMMENT)
                fence = null
            }
        } else if (indent < 4 && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
            fence = trimmed.takeWhile { it == trimmed[0] }
            codeLanguage = syntaxLanguage(trimmed.drop(fence.length).trim().substringBefore(' '))
            out.add(i, eol, SyntaxKind.COMMENT)
            codeStart = minOf(eol + 1, n)
        } else if (indent < 4 && trimmed.startsWith('#') && trimmed.takeWhile { it == '#' }.length <= 6 &&
            trimmed.dropWhile { it == '#' }.let { it.isEmpty() || it[0] == ' ' }
        ) {
            out.add(i + indent, eol, SyntaxKind.HEADING)
        } else {
            var j = i
            while (j < eol && (text[j] == ' ' || text[j] == '>')) {
                if (text[j] == '>') out.add(j, j + 1, SyntaxKind.OPERATOR)
                j++
            }
            MD_LIST.find(text.substring(j, eol))?.let { out.add(j + it.groups[1]!!.range.first, j + it.groups[1]!!.range.last + 1, SyntaxKind.OPERATOR) }
            markdownInline(text, j, eol, out)
        }
        i = eol + 1
    }
    if (fence != null && codeLanguage != null && codeStart < n) out.embed(text.substring(codeStart), codeStart, codeLanguage)
}

private fun markdownInline(text: String, from: Int, to: Int, out: Spans) {
    var i = from
    while (i < to) {
        val c = text[i]
        when {
            c == '`' -> {
                val ticks = text.substring(i, to).takeWhile { it == '`' }.length
                val close = text.indexOf("`".repeat(ticks), i + ticks)
                if (close in 0 until to) {
                    out.add(i, close + ticks, SyntaxKind.STRING)
                    i = close + ticks
                } else {
                    i += ticks
                }
            }
            c == ']' && i + 1 < to && text[i + 1] == '(' -> {
                val close = text.indexOf(')', i + 2)
                if (close in 0 until to) {
                    out.add(i + 2, close, SyntaxKind.ATTRIBUTE)
                    i = close + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
}

// Diffs: file headers, `@@` hunks, added and removed lines.
private fun diff(text: String, out: Spans) {
    val n = text.length
    var i = 0
    while (i < n) {
        val eol = lineEnd(text, i, n)
        val kind = when {
            text.startsWith("+++", i) || text.startsWith("---", i) || text.startsWith("diff ", i) ||
                text.startsWith("index ", i) -> SyntaxKind.KEYWORD
            text.startsWith("@@", i) -> SyntaxKind.HEADING
            text.startsWith("+", i) -> SyntaxKind.ADDED
            text.startsWith("-", i) -> SyntaxKind.REMOVED
            else -> null
        }
        if (kind != null) out.add(i, eol, kind)
        i = eol + 1
    }
}

// Palette indexes to try for each kind, in order.
private val KIND_COLORS = mapOf(
    SyntaxKind.KEYWORD to listOf(5, 13),
    SyntaxKind.TYPE to listOf(6, 14, 3),
    SyntaxKind.STRING to listOf(2, 10),
    SyntaxKind.COMMENT to listOf(8),
    SyntaxKind.NUMBER to listOf(3, 11),
    SyntaxKind.CONSTANT to listOf(1, 9, 3),
    SyntaxKind.FUNCTION to listOf(4, 12),
    SyntaxKind.TAG to listOf(4, 12),
    SyntaxKind.ATTRIBUTE to listOf(3, 11, 6),
    SyntaxKind.OPERATOR to listOf(6, 14),
    SyntaxKind.HEADING to listOf(4, 12, 5),
    SyntaxKind.ADDED to listOf(2, 10),
    SyntaxKind.REMOVED to listOf(1, 9),
)

private const val MIN_SYNTAX_CONTRAST = 3.0

/**
 * [kind]'s colour (`0xAARRGGBB`) in [scheme]: the theme's own (keywords
 * magenta, types cyan, strings green, comments its dim grey, numbers yellow,
 * constants red, functions blue…), else the next one that's readable on
 * the background (contrast at least 3), else a dimmed foreground (comments)
 * or the foreground itself.
 */
fun syntaxColor(kind: SyntaxKind, scheme: ColorScheme): Int {
    val background = scheme.background
    for (index in KIND_COLORS.getValue(kind)) {
        val color = scheme.palette[index] ?: continue
        if (contrast(color, background) >= MIN_SYNTAX_CONTRAST) return color
    }
    if (kind == SyntaxKind.COMMENT) {
        for (amount in listOf(0.45, 0.35, 0.25, 0.15)) {
            val dim = mix(scheme.foreground, background, amount)
            if (contrast(dim, background) >= MIN_SYNTAX_CONTRAST) return dim
        }
    }
    if (contrast(scheme.foreground, background) >= MIN_SYNTAX_CONTRAST) return scheme.foreground
    val white = 0xffffffff.toInt()
    val black = 0xff000000.toInt()
    return if (contrast(white, background) >= contrast(black, background)) white else black
}

// [from] moved [amount] of the way to [to].
private fun mix(from: Int, to: Int, amount: Double): Int {
    fun channel(shift: Int): Int {
        val a = from shr shift and 0xff
        val b = to shr shift and 0xff
        return (a + (b - a) * amount).toInt() shl shift
    }
    return (0xff000000.toInt()) or channel(16) or channel(8) or channel(0)
}
