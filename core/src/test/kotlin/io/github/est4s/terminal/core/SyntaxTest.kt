package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SyntaxTest {
    // Each span as "text:KIND", to read the expectations easily.
    private fun spans(text: String, language: String): List<String> {
        val spans = highlight(text, language)
        checkSpans(text, spans, language)
        return spans.map { "${text.substring(it.start, it.end)}:${it.kind}" }
    }

    private fun checkSpans(text: String, spans: List<SyntaxSpan>, language: String) {
        var last = 0
        for (span in spans) {
            if (span.start < last || span.end <= span.start || span.end > text.length) {
                fail("$language: bad span $span after $last in ${text.length} chars")
            }
            last = span.end
        }
    }

    private fun kindOf(text: String, language: String, token: String, from: Int = 0): SyntaxKind? {
        val at = text.indexOf(token, from)
        assertTrue(at >= 0, "'$token' isn't in the text")
        return highlight(text, language).firstOrNull { it.start == at && it.end == at + token.length }?.kind
    }

    @Test
    fun `python keywords, strings, comments, numbers, functions and constants`() {
        assertEquals(
            listOf(
                "def:KEYWORD", "main:FUNCTION", "return:KEYWORD", "0x1F:NUMBER", "1_000.5e3:NUMBER",
                "'it':STRING", "'s':STRING", "None:CONSTANT", "# done:COMMENT",
            ),
            spans("def main(x):\n    return 0x1F + 1_000.5e3, 'it''s', None  # done\n", "python"),
        )
    }

    @Test
    fun `python triple quotes span lines, and prefixes start strings`() {
        val text = "x = r\"\"\"a\n\\\"b\"\"\" + f'{y}' + b\"z\"\nprint(x)"
        assertEquals(
            listOf("r\"\"\"a\n\\\"b\"\"\":STRING", "f'{y}':STRING", "b\"z\":STRING", "print:FUNCTION"),
            spans(text, "python"),
        )
    }

    @Test
    fun `an escaped quote doesn't end a string`() {
        assertEquals(listOf("\"a\\\"b\":STRING", "1:NUMBER"), spans("s = \"a\\\"b\" + 1", "javascript"))
    }

    @Test
    fun `unterminated strings end at the line, block comments at the text's end`() {
        assertEquals(listOf("\"open:STRING", "val:KEYWORD"), spans("x = \"open\nval y", "kotlin"))
        assertEquals(listOf("val:KEYWORD", "/* never\nclosed:COMMENT"), spans("val a /* never\nclosed", "kotlin"))
        assertEquals(listOf("\"\"\"still:STRING"), spans("\"\"\"still", "python"))
    }

    @Test
    fun `kotlin types, annotations and raw strings`() {
        val text = "@JvmStatic fun f(s: String): Int = \"\"\"a\"b\"\"\".length"
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(text, "kotlin", "@JvmStatic"))
        assertEquals(SyntaxKind.KEYWORD, kindOf(text, "kotlin", "fun"))
        assertEquals(SyntaxKind.FUNCTION, kindOf(text, "kotlin", "f", text.indexOf("f(")))
        assertEquals(SyntaxKind.TYPE, kindOf(text, "kotlin", "String"))
        assertEquals(SyntaxKind.TYPE, kindOf(text, "kotlin", "Int"))
        assertEquals(SyntaxKind.STRING, kindOf(text, "kotlin", "\"\"\"a\"b\"\"\""))
    }

    @Test
    fun `shell comments need a word break, and variables stand out`() {
        val text = "if [ \"\$x\" ]; then echo \$HOME a#b # note\nfi"
        assertEquals(SyntaxKind.KEYWORD, kindOf(text, "shell", "if"))
        assertEquals(SyntaxKind.KEYWORD, kindOf(text, "shell", "then"))
        assertEquals(SyntaxKind.CONSTANT, kindOf(text, "shell", "\$HOME"))
        assertEquals(SyntaxKind.COMMENT, kindOf(text, "shell", "# note"))
        assertEquals(null, kindOf(text, "shell", "#b"))
        assertEquals(SyntaxKind.KEYWORD, kindOf(text, "bash", "fi"), "an alias")
        assertEquals(SyntaxKind.KEYWORD, kindOf("apt-get install -y do-it; done", "sh", "done"))
        assertEquals(null, kindOf("apt-get install -y do-it; done", "sh", "do"), "part of a word")
    }

    @Test
    fun `sql keywords ignore case`() {
        assertEquals(listOf("select:KEYWORD", "FROM:KEYWORD", "Where:KEYWORD", "'x':STRING", "-- c:COMMENT"), spans("select a FROM t Where b = 'x' -- c", "sql"))
    }

    @Test
    fun `c preprocessor lines and rust raw strings and lifetimes`() {
        assertEquals(SyntaxKind.KEYWORD, kindOf("#include <stdio.h>\nint main() {}", "c", "#include"))
        assertEquals(SyntaxKind.TYPE, kindOf("#include <stdio.h>\nint main() {}", "c", "int"))
        val rust = "fn f<'a>(s: &'a str) -> char { println!(r#\"a\"b\"#); 'x' }"
        assertEquals(SyntaxKind.STRING, kindOf(rust, "rust", "r#\"a\"b\"#"))
        assertEquals(SyntaxKind.STRING, kindOf(rust, "rust", "'x'"))
        assertEquals(SyntaxKind.FUNCTION, kindOf(rust, "rust", "println!"))
        assertEquals(SyntaxKind.TYPE, kindOf(rust, "rust", "str"))
    }

    @Test
    fun `go backquotes and lua long strings and comments`() {
        assertEquals(SyntaxKind.STRING, kindOf("s := `a\nb`", "go", "`a\nb`"))
        assertEquals(SyntaxKind.COMMENT, kindOf("--[[ a\nb ]] x = [[s]] -- c", "lua", "--[[ a\nb ]]"))
        assertEquals(SyntaxKind.STRING, kindOf("--[[ a\nb ]] x = [[s]] -- c", "lua", "[[s]]"))
        assertEquals(SyntaxKind.COMMENT, kindOf("--[[ a\nb ]] x = [[s]] -- c", "lua", "-- c"))
    }

    @Test
    fun `json keys are attributes and values strings`() {
        assertEquals(
            listOf("\"name\":ATTRIBUTE", "\"mynx\":STRING", "\"n\":ATTRIBUTE", "1.5:NUMBER", "\"ok\":ATTRIBUTE", "true:CONSTANT"),
            spans("{\"name\": \"mynx\", \"n\" : -1.5, \"ok\": true}", "json"),
        )
    }

    @Test
    fun `yaml, toml and ini keys and sections`() {
        val yaml = "# c\nname: mynx\nlist:\n  - key: 'v' # x\nurl: http://a#b\n"
        assertEquals(SyntaxKind.COMMENT, kindOf(yaml, "yaml", "# c"))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(yaml, "yaml", "name"))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(yaml, "yml", "key"))
        assertEquals(SyntaxKind.STRING, kindOf(yaml, "yaml", "'v'"))
        assertEquals(SyntaxKind.COMMENT, kindOf(yaml, "yaml", "# x"))
        assertEquals(null, kindOf(yaml, "yaml", "#b"))
        val toml = "[tool.x]\nname = \"a\"\nn = 3\n"
        assertEquals(SyntaxKind.HEADING, kindOf(toml, "toml", "[tool.x]"))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(toml, "toml", "name"))
        assertEquals(SyntaxKind.NUMBER, kindOf(toml, "toml", "3"))
        val ini = "; c\n[main]\nkey=value\n"
        assertEquals(SyntaxKind.COMMENT, kindOf(ini, "ini", "; c"))
        assertEquals(SyntaxKind.HEADING, kindOf(ini, "conf", "[main]"))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(ini, "ini", "key"))
    }

    @Test
    fun `html tags, attributes, values, comments and embedded script`() {
        val html = "<!-- hi -->\n<a href=\"/x\" hidden>t &amp; u</a><script>let x = 1</script>"
        assertEquals(SyntaxKind.COMMENT, kindOf(html, "html", "<!-- hi -->"))
        assertEquals(SyntaxKind.TAG, kindOf(html, "html", "<a"))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(html, "html", "href"))
        assertEquals(SyntaxKind.STRING, kindOf(html, "html", "\"/x\""))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(html, "html", "hidden"))
        assertEquals(SyntaxKind.CONSTANT, kindOf(html, "html", "&amp;"))
        assertEquals(SyntaxKind.TAG, kindOf(html, "html", "</a"))
        assertEquals(SyntaxKind.KEYWORD, kindOf(html, "html", "let"))
        assertEquals(SyntaxKind.TAG, kindOf(html, "html", "</script"))
        assertEquals(SyntaxKind.KEYWORD, kindOf("<?xml version=\"1.0\"?><r/>", "xml", "<?xml version=\"1.0\"?>"))
        assertEquals(SyntaxKind.TAG, kindOf("<?xml version=\"1.0\"?><r/>", "xml", "<r"))
        assertEquals(SyntaxKind.TAG, kindOf("<?xml version=\"1.0\"?><r/>", "xml", "/>"))
        spans("<a href=\"open", "html")
    }

    @Test
    fun `css properties, selectors, colours and at-rules`() {
        val css = "@media screen { .a > p { color: #fff; margin: 4px; } } /* c */"
        assertEquals(SyntaxKind.KEYWORD, kindOf(css, "css", "@media"))
        assertEquals(SyntaxKind.TAG, kindOf(css, "css", ".a"))
        assertEquals(SyntaxKind.TAG, kindOf(css, "css", "p"))
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(css, "css", "color"))
        assertEquals(SyntaxKind.NUMBER, kindOf(css, "css", "#fff"))
        assertEquals(SyntaxKind.NUMBER, kindOf(css, "css", "4px"))
        assertEquals(SyntaxKind.COMMENT, kindOf(css, "css", "/* c */"))
    }

    @Test
    fun `dockerfile instructions and makefile targets`() {
        val docker = "FROM debian\nRUN apt-get install from # x\n"
        assertEquals(SyntaxKind.KEYWORD, kindOf(docker, "dockerfile", "FROM"))
        assertEquals(SyntaxKind.KEYWORD, kindOf(docker, "dockerfile", "RUN"))
        assertEquals(null, kindOf(docker, "dockerfile", "from"))
        val make = "CC := gcc\n.PHONY: all\nall: main.o\n\t\$(CC) -o x \$@ # c\n"
        assertEquals(SyntaxKind.ATTRIBUTE, kindOf(make, "makefile", "CC"))
        assertEquals(SyntaxKind.FUNCTION, kindOf(make, "makefile", "all", make.indexOf("\nall") + 1))
        assertEquals(SyntaxKind.KEYWORD, kindOf(make, "makefile", ".PHONY"))
        assertEquals(SyntaxKind.CONSTANT, kindOf(make, "makefile", "\$(CC)"))
        assertEquals(SyntaxKind.CONSTANT, kindOf(make, "makefile", "\$@"))
        assertEquals(SyntaxKind.COMMENT, kindOf(make, "makefile", "# c"))
    }

    @Test
    fun `markdown headings, code and fences`() {
        val md = "# Title\nSome `code` here\n- item\n```python\ndef f(): pass\n```\n> quote\n"
        assertEquals(SyntaxKind.HEADING, kindOf(md, "markdown", "# Title"))
        assertEquals(SyntaxKind.STRING, kindOf(md, "markdown", "`code`"))
        assertEquals(SyntaxKind.OPERATOR, kindOf(md, "markdown", "-"))
        assertEquals(SyntaxKind.COMMENT, kindOf(md, "markdown", "```python"))
        assertEquals(SyntaxKind.KEYWORD, kindOf(md, "md", "def"))
        assertEquals(SyntaxKind.OPERATOR, kindOf(md, "markdown", ">"))
        spans("```\nnever closed", "markdown")
    }

    @Test
    fun `diff lines`() {
        val diff = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@ fun\n a\n-b\n+c\n"
        assertEquals(
            listOf(
                "diff --git a/x b/x:KEYWORD", "--- a/x:KEYWORD", "+++ b/x:KEYWORD", "@@ -1,2 +1,2 @@ fun:HEADING",
                "-b:REMOVED", "+c:ADDED",
            ),
            spans(diff, "diff"),
        )
    }

    @Test
    fun `unknown languages give nothing, and every language survives odd text`() {
        assertEquals(emptyList(), highlight("def x", "klingon"))
        val odd = listOf("", "\"", "'", "/*", "<!--", "<", "\\", "0x", "1e", "@", "$", "\${", "$(", "#", "[", "r#\"", "```", "-", "--[[", "'\\", "\n\n", "a\"\nb")
        for (language in SYNTAX_LANGUAGES) for (text in odd) spans(text, language)
    }

    @Test
    fun `aliases name the languages`() {
        assertEquals("python", syntaxLanguage("py"))
        assertEquals("shell", syntaxLanguage("Bash"))
        assertEquals("cpp", syntaxLanguage("c++"))
        assertEquals("typescript", syntaxLanguage("ts"))
        assertEquals(null, syntaxLanguage("klingon"))
        assertTrue(SYNTAX_LANGUAGES.containsAll(listOf("python", "html", "xml", "markdown", "diff", "makefile", "dockerfile")))
    }

    @Test
    fun `a megabyte of python highlights fast`() {
        val chunk = "def f(x, y=1.5):\n    \"\"\"Doc.\"\"\"\n    s = 'a' + str(x)  # comment\n    return [i * 0x10 for i in range(y)]\n\n"
        val text = buildString { while (length < 1_000_000) append(chunk) }
        highlight(text, "python")
        val started = System.nanoTime()
        val spans = highlight(text, "python")
        val ms = (System.nanoTime() - started) / 1_000_000
        checkSpans(text, spans, "python")
        assertTrue(ms < 500, "took $ms ms")
    }

    @Test
    fun `colours come from the theme and are readable on every built-in one`() {
        assertTrue(contrast(NEON.palette[5]!!, NEON.background) >= 3)
        assertEquals(NEON.palette[5], syntaxColor(SyntaxKind.KEYWORD, NEON), "neon's own magenta")
        for (name in BUILT_IN_THEMES) {
            val scheme = parseColorScheme(builtInThemeText(name)!!, NEON).scheme
            for (kind in SyntaxKind.entries) {
                val color = syntaxColor(kind, scheme)
                assertTrue(contrast(color, scheme.background) >= 3.0, "$name $kind: ${contrast(color, scheme.background)}")
            }
            val green = scheme.palette[2]!!
            if (contrast(green, scheme.background) >= 3) assertEquals(green, syntaxColor(SyntaxKind.STRING, scheme), "$name's green")
        }
        val unreadable = ColorScheme(foreground = 0xff000000.toInt(), background = 0xff000000.toInt(), cursor = null, palette = emptyMap())
        assertTrue(contrast(syntaxColor(SyntaxKind.COMMENT, unreadable), unreadable.background) >= 3.0, "falls back to white on black")
    }
}
