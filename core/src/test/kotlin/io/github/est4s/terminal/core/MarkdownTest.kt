package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownTest {
    private fun dump(styled: MdStyled): String {
        val tag = mapOf(MdStyleKind.BOLD to "b", MdStyleKind.ITALIC to "i", MdStyleKind.STRIKE to "s", MdStyleKind.CODE to "c", MdStyleKind.LINK to "a")
        val out = StringBuilder()
        val ordered = styled.styles.sortedWith(compareBy({ it.start }, { -it.end }, { it.kind.ordinal }))
        for (i in 0..styled.text.length) {
            ordered.filter { it.end == i && it.start != i }.reversed().forEach { out.append("</${tag[it.kind]}>") }
            ordered.filter { it.start == i }.forEach { out.append("<${tag[it.kind]}${it.url?.let { u -> " $u" } ?: ""}>") }
            if (i < styled.text.length) out.append(styled.text[i])
        }
        return out.toString()
    }

    private fun dump(blocks: List<MdBlock>): String = blocks.joinToString(" / ") { dump(it) }

    private fun dump(block: MdBlock): String = when (block) {
        is MdBlock.Paragraph -> "p:" + dump(block.text)
        is MdBlock.Heading -> "h${block.level}:" + dump(block.text)
        is MdBlock.Code -> "code(${block.language ?: ""}):${block.text}"
        is MdBlock.Quote -> "q{" + block.blocks.joinToString(" ; ") { dump(it) } + "}"
        is MdBlock.ItemList ->
            (if (block.ordered) "ol${block.start}" else "ul") + block.items.joinToString("") { item -> "{" + item.joinToString(" ; ") { dump(it) } + "}" }
        MdBlock.Rule -> "hr"
        is MdBlock.Table ->
            "table[" + block.aligns.joinToString(",") { it.name.take(1) } + "]" +
                (listOf(block.header) + block.rows).joinToString("") { row -> "(" + row.joinToString("|") { dump(it) } + ")" }
    }

    private fun check(vararg cases: Pair<String, String>) {
        for ((input, expected) in cases) assertEquals(expected, dump(parseMarkdown(input)), "input: <$input>")
    }

    /** All the text the blocks show, with link targets a label hides. */
    private fun shown(blocks: List<MdBlock>, out: StringBuilder = StringBuilder()): StringBuilder {
        fun styled(text: MdStyled) {
            var at = 0
            for (link in text.styles.filter { it.kind == MdStyleKind.LINK }.sortedBy { it.start }) {
                out.append(text.text, at, link.end)
                at = link.end
                if (text.text.substring(link.start, link.end) != link.url) out.append(link.url)
            }
            out.append(text.text, at, text.text.length)
        }
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> styled(block.text)
                is MdBlock.Heading -> styled(block.text)
                is MdBlock.Code -> out.append(block.language.orEmpty()).append(block.text)
                is MdBlock.Quote -> shown(block.blocks, out)
                is MdBlock.ItemList -> block.items.forEachIndexed { i, item ->
                    if (block.ordered) out.append(block.start + i)
                    shown(item, out)
                }
                MdBlock.Rule -> Unit
                is MdBlock.Table -> (listOf(block.header) + block.rows).forEach { row -> row.forEach(::styled) }
            }
        }
        return out
    }

    private fun words(text: CharSequence) = text.filter { it.isLetterOrDigit() }.toString()

    private val sample = """
        # Title **bold**

        A paragraph with *italic*, `code **not bold**`, ~~gone~~, a [link](https://a.example/x_y) and https://b.example/q.
        Second line \* escaped.

        - one
          - nested **two**
            - three
        - four

        1. first:
           ```sh
           ls **/*.kt
           ```
        2. second

        > quoted *text*
        > - in a list

        ---

        | Name | Size |
        |:-----|-----:|
        | `a|b` | 12 |
        | [c](http://c.example) | 34 |

            indented **code**

        ```kotlin
        val a = "# not a heading"
        - not a list
        [x](http://not.a.link)
        ```

        Last paragraph, then an unclosed fence.

        ```python
        print("still **streaming**")
    """.trimIndent()

    @Test
    fun `blocks`() = check(
        "" to "",
        "\n\n" to "",
        "hello" to "p:hello",
        "a\nb" to "p:a\nb",
        "a\r\nb" to "p:a\nb",
        "a\n\nb" to "p:a / p:b",
        "para\n    not code" to "p:para\nnot code",
    )

    @Test
    fun `headings`() = check(
        "# T" to "h1:T",
        "###### T ##" to "h6:T",
        "## **b**" to "h2:<b>b</b>",
        "####### T" to "p:####### T",
        "#nope" to "p:#nope",
        "#" to "p:#",
        "a\n# b\nc" to "p:a / h1:b / p:c",
    )

    @Test
    fun `rules`() = check(
        "---" to "hr",
        "***" to "hr",
        "___" to "hr",
        "- - -" to "hr",
        "a\n---\nb" to "p:a / hr / p:b",
        "--" to "p:--",
    )

    @Test
    fun `code blocks`() = check(
        "```kt\nval a\n```" to "code(kt):val a",
        "```\nx" to "code():x",
        "```\n" to "code():",
        "```\nx\n" to "code():x",
        "```kt" to "code(kt):",
        "~~~py title\nx\n~~~" to "code(py):x",
        "````\n```\nx\n````" to "code():```\nx",
        "```\na\n\n  b\n```\nafter" to "code():a\n\n  b / p:after",
        "  ```\n  x\n ```" to "code():x",
        "a\n```\nb\n```" to "p:a / code():b",
        "``` a`b\nx" to "p:``` a`b\nx",
        "    code\n      more\n\nafter" to "code():code\n  more / p:after",
        "\tcode" to "code():code",
    )

    @Test
    fun `nothing inside code is interpreted`() = check(
        "```\n# h\n- l\n> q\n**b** [a](http://x)\n|a|\n|-|\n```" to "code():# h\n- l\n> q\n**b** [a](http://x)\n|a|\n|-|",
        "    # h **b**" to "code():# h **b**",
        "`a **b** c`" to "p:<c>a **b** c</c>",
        "`[x](http://y)`" to "p:<c>[x](http://y)</c>",
        "`http://x`" to "p:<c>http://x</c>",
        "`\\*`" to "p:<c>\\*</c>",
        "`` a`b ``" to "p:<c>a`b</c>",
    )

    @Test
    fun `lists`() = check(
        "- a\n- b" to "ul{p:a}{p:b}",
        "* a\n+ b" to "ul{p:a} / ul{p:b}",
        "1. a\n2. b" to "ol1{p:a}{p:b}",
        "3) a" to "ol3{p:a}",
        "- a\n  - b\n    - c\n- d" to "ul{p:a ; ul{p:b ; ul{p:c}}}{p:d}",
        "1. a\n   - b" to "ol1{p:a ; ul{p:b}}",
        "1. a\n  - b" to "ol1{p:a ; ul{p:b}}",
        "- a\n\n  b\n\n- c" to "ul{p:a ; p:b}{p:c}",
        "1. run:\n   ```sh\n   ls *\n   ```\n2. done" to "ol1{p:run: ; code(sh):ls *}{p:done}",
        "text\n- a" to "p:text / ul{p:a}",
        "text\n2. a" to "p:text\n2. a",
        "-" to "p:-",
        "- " to "ul{}",
        "-nope" to "p:-nope",
        "-\ta" to "ul{p:a}",
        "- a\nb" to "ul{p:a} / p:b",
    )

    @Test
    fun `quotes`() = check(
        "> a\n> b" to "q{p:a\nb}",
        "> a\n\n> b" to "q{p:a} / q{p:b}",
        ">> a" to "q{q{p:a}}",
        "> - a\n> ```\n> x" to "q{ul{p:a} ; code():x}",
        ">" to "q{}",
    )

    @Test
    fun `tables`() = check(
        "| a | b |\n|---|:-:|\n| c | d |" to "table[L,C](a|b)(c|d)",
        "a | b\n--- | ---:\nc | d\n\nafter" to "table[L,R](a|b)(c|d) / p:after",
        "| a |\n|---|\n| b | c |" to "table[L](a)(b|c)",
        "| a | b |\n|---|" to "p:| a | b |\n|---|",
        "| a | b |" to "p:| a | b |",
        "text\n| a |\n|---|" to "p:text / table[L](a)",
        "|a|\n|-|\n|`x|y`|" to "table[L](a)(<c>x|y</c>)",
        "|a|\n|-|\n|x\\|y|" to "table[L](a)(x|y)",
        "|a|\n|-|\n|`x\\|y`|" to "table[L](a)(<c>x|y</c>)",
        "|a|\n|-|\n|**b**|" to "table[L](a)(<b>b</b>)",
        "|a|\n|-|\nno pipe" to "table[L](a) / p:no pipe",
    )

    @Test
    fun `emphasis`() = check(
        "**b**" to "p:<b>b</b>",
        "__b__" to "p:<b>b</b>",
        "*i*" to "p:<i>i</i>",
        "_i_" to "p:<i>i</i>",
        "~~s~~" to "p:<s>s</s>",
        "***x***" to "p:<b><i>x</i></b>",
        "**a *b* c**" to "p:<b>a <i>b</i> c</b>",
        "**a**b" to "p:<b>a</b>b",
        "a**b**c" to "p:a<b>b</b>c",
        "**a\nb**" to "p:<b>a\nb</b>",
    )

    @Test
    fun `what isn't closed stays as written`() = check(
        "**b" to "p:**b",
        "*i" to "p:*i",
        "~~s" to "p:~~s",
        "`c" to "p:`c",
        "[a" to "p:[a",
        "[a](" to "p:[a](",
        "[a](http://x" to "p:[a](<a http://x>http://x</a>",
        "2 * 3 * 4" to "p:2 * 3 * 4",
        "a ** b" to "p:a ** b",
        "2**32" to "p:2**32",
        "snake_case_name" to "p:snake_case_name",
        "**kwargs and *args" to "p:**kwargs and *args",
        "a ~ b ~ c" to "p:a ~ b ~ c",
    )

    @Test
    fun `escapes`() = check(
        "\\*a\\*" to "p:*a*",
        "\\" to "p:\\",
        "a\\b" to "p:a\\b",
        "\\[a\\](http://x)" to "p:[a](<a http://x>http://x</a>)",
    )

    @Test
    fun `links`() = check(
        "[a](https://x.y/z)" to "p:<a https://x.y/z>a</a>",
        "[**a**](http://x)" to "p:<b><a http://x>a</a></b>",
        "[a](HTTP://X)" to "p:<a HTTP://X>a</a>",
        "[a](http://x \"title\")" to "p:<a http://x>a</a>",
        "[a](<http://x>)" to "p:<a http://x>a</a>",
        "[a](http://x/(b)c)" to "p:<a http://x/(b)c>a</a>",
        "[http://a](http://b)" to "p:<a http://b>http://a</a>",
    )

    @Test
    fun `only web links are links`() = check(
        "[a](javascript:alert(1))" to "p:[a](javascript:alert(1))",
        "[a](file.md)" to "p:[a](file.md)",
        "[a](mailto:a@b)" to "p:[a](mailto:a@b)",
        "[a](intent://x)" to "p:[a](intent://x)",
        "ftp://x" to "p:ftp://x",
        "http://" to "p:http://",
    )

    @Test
    fun `mdLinkAllowed takes http and https, whole`() {
        for (good in listOf("http://a", "https://a.b/c?d#e", "HTTPS://A")) assertTrue(mdLinkAllowed(good), good)
        for (bad in listOf("", "http://", "javascript:alert(1)", "file:///etc/passwd", "content://x", "intent://x", "httpx://a", " http://a", "http://a b", "http://a\nb")) {
            assertFalse(mdLinkAllowed(bad), bad)
        }
    }

    @Test
    fun `bare links`() = check(
        "bare http://x.y/z." to "p:bare <a http://x.y/z>http://x.y/z</a>.",
        "(https://x.y)" to "p:(<a https://x.y>https://x.y</a>)",
        "https://x.y/(a)" to "p:<a https://x.y/(a)>https://x.y/(a)</a>",
        "see https://x.y/a_b_c*d" to "p:see <a https://x.y/a_b_c*d>https://x.y/a_b_c*d</a>",
        "xhttp://a" to "p:xhttp://a",
        "<https://x.y>" to "p:<a https://x.y>https://x.y</a>",
    )

    @Test
    fun `the sample parses into what it says`() {
        val kinds = parseMarkdown(sample).map { it::class.simpleName }
        assertEquals(
            listOf("Heading", "Paragraph", "ItemList", "ItemList", "Quote", "Rule", "Table", "Code", "Code", "Paragraph", "Code"),
            kinds,
        )
    }

    @Test
    fun `every prefix of the sample parses and loses no text`() {
        for (end in 0..sample.length) {
            val prefix = sample.substring(0, end)
            assertEquals(words(prefix), words(shown(parseMarkdown(prefix))), "prefix of $end: <$prefix>")
        }
    }

    @Test
    fun `incremental parsing gives what a whole parse gives, at every prefix`() {
        for (step in listOf(1, 2, 3, 7, 40)) {
            val stream = IncrementalMarkdown()
            for (end in 0..sample.length step step) {
                val prefix = sample.substring(0, end)
                assertEquals(parseMarkdown(prefix), stream.update(prefix), "step $step, prefix of $end: <$prefix>")
            }
        }
    }

    @Test
    fun `a line that stops being a block start is reparsed with the block before`() {
        val stream = IncrementalMarkdown()
        for (text in listOf("a\n---", "a\n---x", "- a\n\n-", "- a\n\n- b", "| a |\n|-|", "| a |\n|-|x")) {
            assertEquals(parseMarkdown(text), stream.update(text), text)
        }
    }

    @Test
    fun `text that isn't an extension is parsed anew`() {
        val stream = IncrementalMarkdown()
        stream.update("# one\n\ntwo")
        assertEquals(parseMarkdown("three"), stream.update("three"))
    }

    @Test
    fun `appending to a long message reparses only its end, and keeps earlier blocks`() {
        val long = (1..5000).joinToString("\n\n") { "Paragraph **$it** of the message." }
        val stream = IncrementalMarkdown()
        val before = stream.update(long)
        val after = stream.update(long + " more")
        assertTrue(stream.reparsed < 100, "reparsed ${stream.reparsed}")
        assertEquals(before.size, after.size)
        for (i in 0 until before.size - 2) assertSame(before[i], after[i])
    }

    @Test
    fun `hostile input neither throws nor loses text`() {
        val cases = listOf(
            "> ".repeat(50000) + "x",
            "- ".repeat(50000) + "x",
            "1. ".repeat(20000) + "x",
            "*a ".repeat(50000),
            "**a ".repeat(30000),
            "_a_ *".repeat(20000),
            "[".repeat(50000),
            "]".repeat(50000),
            "[a](".repeat(20000),
            "`".repeat(50000),
            "` `` ``` ".repeat(5000),
            "|".repeat(50000),
            "|\n|-|\n".repeat(5000),
            "```\n".repeat(5001),
            "\\".repeat(50000),
            "http://".repeat(20000),
        )
        for (input in cases) assertEquals(words(input), words(shown(parseMarkdown(input))), input.take(12))
    }

    @Test
    fun `rows flatten blocks, keeping nesting as indent`() {
        fun text(s: String) = MdStyled(s)
        assertEquals(
            listOf(
                MdRow.Text(text("T"), heading = 2),
                MdRow.Text(text("a"), marker = "•"),
                MdRow.Text(text("b"), marker = "◦", indent = 1),
                MdRow.Text(text("c"), marker = "▪", indent = 2),
                MdRow.Text(text("more"), indent = 1),
                MdRow.Text(text("d"), marker = "•"),
            ),
            mdRows(parseMarkdown("## T\n\n- a\n  - b\n    - c\n\n  more\n- d")),
        )
    }

    @Test
    fun `ordered rows are numbered from the list's start, and code in an item is indented`() {
        assertEquals(
            listOf(
                MdRow.Text(MdStyled("x"), marker = "3."),
                MdRow.Code("sh", "ls", indent = 1),
                MdRow.Text(MdStyled("y"), marker = "4."),
            ),
            mdRows(parseMarkdown("3. x\n   ```sh\n   ls\n   ```\n4. y")),
        )
    }

    @Test
    fun `an item with no text first still shows its marker`() {
        assertEquals(listOf(MdRow.Text(MdStyled(""), marker = "•")), mdRows(parseMarkdown("- ")))
        assertEquals(
            listOf(MdRow.Text(MdStyled(""), marker = "•"), MdRow.Code(null, "x", indent = 1)),
            mdRows(parseMarkdown("- ```\n  x\n  ```")),
        )
    }

    @Test
    fun `quoted rows count their depth`() {
        assertEquals(
            listOf(
                MdRow.Text(MdStyled("a"), quote = 1),
                MdRow.Text(MdStyled("b"), quote = 2),
                MdRow.Code(null, "c", quote = 1),
                MdRow.Rule(quote = 1),
            ),
            mdRows(parseMarkdown("> a\n>\n> > b\n>\n> ```\n> c\n> ```\n>\n> ---")),
        )
    }

    @Test
    fun `tables and rules are rows`() {
        val table = parseMarkdown("|a|\n|-|")[0] as MdBlock.Table
        assertEquals(listOf(MdRow.Table(table), MdRow.Rule()), mdRows(parseMarkdown("|a|\n|-|\n\n---")))
    }
}
