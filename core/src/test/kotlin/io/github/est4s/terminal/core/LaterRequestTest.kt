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
    private val requests = MynxRequests(
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
    fun `a stream sends lines until mynx cancels it`() {
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
    fun `a stream whose mynx has gone stops and leaves nothing behind`() {
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

/** `mynx`'s files, byte for byte as before the seam. */
class FileReplySinkTest {
    private val dir = createTempDirectory("sink").toFile()
    private val sink = FileReplySink(dir, "12-1")

    @AfterTest
    fun cleanup() {
        deleteTree(dir)
    }

    private fun names() = dir.list()!!.sorted()

    @Test
    fun `waiting writes the seconds, or stream, renamed into place`() {
        sink.waiting(30)
        assertEquals("30", File(dir, "12-1.wait").readText())
        sink.waiting(null)
        assertEquals("stream", File(dir, "12-1.wait").readText())
        assertEquals(listOf("12-1.wait"), names())
    }

    @Test
    fun `lines are appended one per line`() {
        sink.line("""{"n":1}""")
        sink.line("""{"n":2}""")
        assertEquals("{\"n\":1}\n{\"n\":2}\n", File(dir, "12-1.stream").readText())
    }

    @Test
    fun `the answer goes in the reply and takes the wait and cancel away`() {
        sink.waiting(5)
        File(dir, "12-1.cancel").writeText("")
        File(dir, "12-10.wait").writeText("5")
        sink.answer("""{"ok":true}""")
        assertEquals("""{"ok":true}""", File(dir, "12-1.reply").readText())
        assertEquals(listOf("12-1.reply", "12-10.wait"), names())
    }

    @Test
    fun `discarding leaves nothing of the request behind, and only of it`() {
        sink.waiting(null)
        sink.line("{}")
        File(dir, "12-1.cancel").writeText("")
        File(dir, "12-10.wait").writeText("5")
        sink.discard()
        assertEquals(listOf("12-10.wait"), names())
    }
}

/** [MynxRequests.call]: requests run in-process, answered through a sink. */
class RequestCallTest {
    private val base = createTempDirectory("call").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val started = mutableListOf<Pair<List<String>, PendingReply>>()
    private val requests = MynxRequests(
        dir, home,
        vibrate = { _, _ -> null },
        later = mapOf(
            "slow" to Later(seconds = 30) { args, reply -> started += args to reply },
            "feed" to Later(seconds = null) { args, reply -> started += args to reply },
            "picky" to Later(seconds = 5) { _, reply -> reply.refuse("picky needs a reason") },
            "conf" to Later(seconds = 5, change = { "conf ${it.joinToString(" ")}" }) { args, reply ->
                File(home, "$CONFIG_DIR/x.conf").apply { parentFile.mkdirs() }.writeText(args.joinToString())
                reply.ok()
            },
        ),
        alive = { false },
    )

    @AfterTest
    fun cleanup() {
        deleteTree(base)
    }

    @Test
    fun `a later request waits, answers through the sink and leaves no files`() {
        val sink = FakeReplySink()
        val call = requests.call("slow", listOf("a"), sink)
        assertEquals(30, call.seconds)
        assertTrue(sink.waitedCalled)
        assertEquals(30, sink.waited)
        assertFalse(call.done)
        assertTrue(requests.hasOpen())
        val (args, reply) = started.single()
        assertEquals(listOf("a"), args)
        reply.ok("v" to "1")
        reply.ok("v" to "2")
        assertEquals(listOf("""{"ok":true,"v":1}"""), sink.answers)
        assertTrue(call.done)
        assertFalse(requests.hasOpen())
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `a stream's lines go to the sink, and stopping answers`() {
        val sink = FakeReplySink()
        val call = requests.call("feed", emptyList(), sink)
        assertEquals(null, call.seconds)
        assertTrue(sink.waitedCalled)
        assertEquals(null, sink.waited)
        val reply = started.single().second
        var stopped = 0
        reply.onCancel { stopped++ }
        reply.line("""{"n":1}""")
        call.stop()
        call.stop()
        call.cancel()
        reply.line("""{"n":2}""")
        assertEquals(1, stopped)
        assertEquals(listOf("""{"n":1}"""), sink.lines)
        assertEquals(listOf("""{"ok":true}"""), sink.answers)
        assertFalse(sink.discarded)
    }

    @Test
    fun `cancelling discards, and nothing is sent`() {
        val sink = FakeReplySink()
        val call = requests.call("feed", emptyList(), sink)
        val reply = started.single().second
        var stopped = false
        reply.onCancel { stopped = true }
        call.cancel()
        reply.ok()
        assertTrue(stopped)
        assertTrue(sink.discarded)
        assertEquals(emptyList(), sink.answers)
        assertTrue(call.done)
        assertFalse(requests.hasOpen())
    }

    @Test
    fun `sweep never cancels a call, as its id has no process`() {
        val sink = FakeReplySink()
        requests.call("feed", emptyList(), sink)
        requests.sweep()
        assertTrue(requests.hasOpen())
        assertFalse(sink.discarded)
    }

    @Test
    fun `a refusal at once is done before call returns`() {
        val sink = FakeReplySink()
        val call = requests.call("picky", emptyList(), sink)
        assertTrue(call.done)
        assertEquals(listOf("""{"ok":false,"error":"picky needs a reason"}"""), sink.answers)
    }

    @Test
    fun `a plain request is answered at once, unknown ones refused as from mynx`() {
        val sink = FakeReplySink()
        val call = requests.call("vibrate", listOf("200"), sink)
        assertTrue(call.done)
        assertEquals(0, call.seconds)
        assertFalse(sink.waitedCalled)
        assertEquals(listOf("""{"ok":true}"""), sink.answers)

        val other = FakeReplySink()
        requests.call("nope", emptyList(), other)
        assertEquals(listOf("""{"ok":false,"error":"unknown request 'nope'"}"""), other.answers)
    }

    @Test
    fun `a handler of the bridge's own runs the same way`() {
        val sink = FakeReplySink()
        val call = requests.call(Later(7) { args, reply -> reply.ok("n" to args.size.toString()) }, listOf("x", "y"), sink)
        assertTrue(sink.waitedCalled)
        assertEquals(7, sink.waited)
        assertEquals(7, call.seconds)
        assertTrue(call.done)
        assertEquals(listOf("""{"ok":true,"n":2}"""), sink.answers)
    }

    @Test
    fun `a change made through a call is an undo step, and so is one recorded by hand`() {
        File(home, "$CONFIG_DIR/settings.conf").apply { parentFile.mkdirs() }.writeText("undo-keep = 5\n")
        requests.start()
        requests.call("conf", listOf("a"), FakeReplySink())
        File(home, "$CONFIG_DIR/x.conf").writeText("by dialog")
        requests.recordChange("app permission x location allow (asked)")
        val steps = undoSteps()
        assertEquals(listOf("app permission x location allow (asked)", "conf a"), steps)
    }

    private fun undoSteps(): List<String> {
        val sink = File(dir, "u.req")
        sink.writeText("undo-list\n")
        requests.processPending()
        val reply = parseJson(File(dir, "u.reply").readText()) as Map<*, *>
        return (reply["steps"] as List<*>).map { (it as Map<*, *>)["reason"] as String }
    }
}
