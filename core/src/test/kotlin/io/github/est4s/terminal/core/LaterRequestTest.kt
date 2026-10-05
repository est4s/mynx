package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaterRequestTest {
    private val base = createTempDirectory("later").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val started = mutableListOf<Pair<List<String>, PendingReply>>()
    private val gone = mutableSetOf<Int>()
    private val requests = PocketRequests(
        dir, home,
        later = mapOf(
            "slow" to Later(seconds = 30) { args, reply -> started += args to reply },
            "feed" to Later(seconds = null) { args, reply -> started += args to reply },
            "picky" to Later(seconds = 5) { _, reply -> reply.refuse("picky needs a reason") },
        ),
        alive = { pid -> pid !in gone },
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `a slow request says how long to wait, then answers when it's ready`() {
        send("12-1", "slow", "a", "b")
        assertEquals("30", file("12-1.wait").readText())
        assertFalse(file("12-1.req").exists())
        assertFalse(file("12-1.reply").exists())
        val (args, reply) = started.single()
        assertEquals(listOf("a", "b"), args)

        reply.ok("value" to json("x"))
        assertEquals("""{"ok":true,"value":"x"}""", file("12-1.reply").readText())
        assertFalse(file("12-1.wait").exists())
        assertFalse(requests.hasOpen())
    }

    @Test
    fun `a refusal answers it too, and only the first answer counts`() {
        send("12-1", "slow")
        val reply = started.single().second
        reply.refuse("no fix yet")
        reply.ok()
        assertEquals("""{"ok":false,"error":"no fix yet"}""", file("12-1.reply").readText())
    }

    @Test
    fun `a request refused at once is answered at once`() {
        send("12-1", "picky")
        assertEquals("""{"ok":false,"error":"picky needs a reason"}""", file("12-1.reply").readText())
        assertFalse(file("12-1.wait").exists())
        assertFalse(requests.hasOpen())
    }

    @Test
    fun `a stream sends lines until pocket cancels it`() {
        send("12-1", "feed")
        assertEquals("stream", file("12-1.wait").readText())
        val reply = started.single().second
        var stopped = 0
        reply.onCancel { stopped++ }
        reply.line("""{"n":1}""")
        reply.line("""{"n":2}""")
        assertEquals("{\"n\":1}\n{\"n\":2}\n", file("12-1.stream").readText())
        assertTrue(requests.hasOpen())

        requests.sweep()
        assertEquals(0, stopped)
        file("12-1.cancel").writeText("")
        requests.sweep()
        assertEquals(1, stopped)
        assertEquals("""{"ok":true}""", file("12-1.reply").readText())
        assertFalse(file("12-1.cancel").exists())
        assertFalse(requests.hasOpen())

        reply.line("""{"n":3}""")
        requests.sweep()
        assertEquals("{\"n\":1}\n{\"n\":2}\n", file("12-1.stream").readText())
        assertEquals(1, stopped)
    }

    @Test
    fun `a stream whose pocket has gone stops and leaves nothing behind`() {
        send("77-5", "feed")
        send("78-5", "feed")
        val reply = started.first().second
        var stopped = false
        reply.onCancel { stopped = true }
        reply.line("""{"n":1}""")
        gone += 77
        requests.sweep()
        assertTrue(stopped)
        assertEquals(emptyList(), dir.list()!!.filter { it.startsWith("77-5.") })
        assertTrue(requests.hasOpen())  // 78 is still there
    }

    @Test
    fun `stopping may answer with what it has, as for a recording`() {
        send("12-1", "feed")
        val reply = started.single().second
        reply.onCancel { reply.ok("seconds" to "4") }
        file("12-1.cancel").writeText("")
        requests.sweep()
        assertEquals("""{"ok":true,"seconds":4}""", file("12-1.reply").readText())
        assertFalse(file("12-1.wait").exists())
        assertFalse(file("12-1.cancel").exists())
        assertFalse(requests.hasOpen())

        send("77-5", "feed")
        val second = started.last().second
        second.onCancel { second.ok("seconds" to "4") }
        gone += 77
        requests.sweep()
        assertEquals(emptyList(), dir.list()!!.filter { it.startsWith("77-5.") })
        assertFalse(requests.hasOpen())
    }

    @Test
    fun `a stream that ends by itself answers like any request`() {
        send("12-1", "feed")
        val reply = started.single().second
        var stopped = false
        reply.onCancel { stopped = true }
        reply.refuse("the sensor went away")
        assertEquals("""{"ok":false,"error":"the sensor went away"}""", file("12-1.reply").readText())
        requests.sweep()
        assertFalse(stopped)
    }

    @Test
    fun `a request whose id has no process number counts as alive`() {
        gone += 0
        send("q", "feed")
        requests.sweep()
        assertTrue(requests.hasOpen())
    }

    private fun file(name: String) = File(dir, name)

    private fun send(id: String, vararg lines: String) {
        file("$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }
}
