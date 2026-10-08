package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun `reads plain values`() {
        assertEquals("hi", parseJson("\"hi\""))
        assertEquals(42L, parseJson("42"))
        assertEquals(-7L, parseJson(" -7 "))
        assertEquals(1.5, parseJson("1.5"))
        assertEquals(-2.5e3, parseJson("-2.5E3"))
        assertEquals(1e2, parseJson("1e2"))
        assertEquals(true, parseJson("true"))
        assertEquals(false, parseJson("false"))
        assertNull(parseJson("null"))
    }

    @Test
    fun `numbers too big for a Long are Doubles`() {
        assertEquals(1e20, parseJson("100000000000000000000"))
    }

    @Test
    fun `reads objects and arrays, keeping key order`() {
        val value = parseJson("""{"b": [1, "two", {"c": null}], "a": {}, "e": []}""")
        assertEquals(mapOf("b" to listOf(1L, "two", mapOf("c" to null)), "a" to emptyMap<String, Any?>(), "e" to emptyList<Any?>()), value)
        assertEquals(listOf("b", "a", "e"), (value as Map<*, *>).keys.toList())
    }

    @Test
    fun `reads every string escape`() {
        assertEquals("\" \\ / \b \u000c \n \r \t", parseJson(""""\" \\ \/ \b \f \n \r \t""""))
        assertEquals("é€", parseJson("\"\\u00e9\\u20AC\""))
        assertEquals("😀", parseJson("\"\\ud83d\\ude00\""))
        assertEquals("ü raw 😀", parseJson("\"ü raw 😀\""))
    }

    @Test
    fun `a later duplicate key wins`() {
        assertEquals(mapOf("a" to 2L), parseJson("""{"a":1,"a":2}"""))
    }

    @Test
    fun `bad input says what and where`() {
        val bad = listOf(
            "", "{", "[1,]", "{\"a\" 1}", "{a:1}", "\"open", "tru", "01", "1.", "-", "1e",
            "\"\\x\"", "\"\\u12\"", "\"\\ud83d\"", "\"\\ude00\"", "\"tab\there\"", "1 2", "[1 2]", "{\"a\":1,}", "nul",
        )
        for (text in bad) {
            val e = assertFailsWith<JsonException>("accepted: $text") { parseJson(text) }
            assertTrue(e.message!!.contains("at "), "no position for $text: ${e.message}")
        }
    }

    @Test
    fun `deep nesting is an error, not a stack overflow`() {
        assertFailsWith<JsonException> { parseJson("[".repeat(100_000)) }
    }
}
