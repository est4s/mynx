package io.github.est4s.terminal.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessAgentTransportTest {
    private class Recorder : AgentTransport.Listener {
        val lines = CopyOnWriteArrayList<String>()
        val exits = CopyOnWriteArrayList<Pair<Int?, String>>()
        private val exited = CountDownLatch(1)

        override fun onLine(line: String) {
            lines += line
        }

        override fun onExit(exitCode: Int?, detail: String) {
            exits += exitCode to detail
            exited.countDown()
        }

        fun awaitExit() = assertTrue(exited.await(20, TimeUnit.SECONDS), "no exit; lines so far: $lines")
    }

    private fun shell(script: String): () -> Process = { ProcessBuilder("/bin/sh", "-c", script).start() }
    private fun transport(script: String, stop: (Process) -> Unit = { it.destroy() }) = ProcessAgentTransport(shell(script), stop)

    @Test
    fun `lines arrive in order, then one exit with the code`() {
        val rec = Recorder()
        transport("echo one; echo two; exit 7").start(rec)
        rec.awaitExit()
        assertEquals(listOf("one", "two"), rec.lines)
        assertEquals(listOf<Pair<Int?, String>>(7 to ""), rec.exits)
    }

    @Test
    fun `written lines reach stdin whole and in order, multi-byte text too`() {
        val rec = Recorder()
        val agent = transport("""while read line; do echo "${'$'}line"; done""")
        agent.start(rec)
        val sent = (1..200).map { "línea $it" }
        sent.forEach(agent::writeLine)
        val deadline = System.currentTimeMillis() + 15_000
        while (rec.lines.size < sent.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        agent.close()
        rec.awaitExit()
        assertEquals(sent, rec.lines.toList())
    }

    @Test
    fun `stderr's tail is the detail, and a flood can't fill the pipe`() {
        val rec = Recorder()
        transport("i=0; while [ \$i -lt 2000 ]; do echo 'lots of output on stderr, line after line, again' >&2; i=\$((i+1)); done; echo last >&2; exit 2").start(rec)
        rec.awaitExit()
        val (code, detail) = rec.exits.single()
        assertEquals(2, code)
        assertTrue(detail.endsWith("last\n") || detail.endsWith("last"), detail.takeLast(40))
        assertTrue(detail.length <= AGENT_STDERR_TAIL, "kept ${detail.length}")
    }

    @Test
    fun `a process that can't start reports it once and doesn't throw`() {
        val rec = Recorder()
        val agent = ProcessAgentTransport({ throw IOException("no such proot") }, { it.destroy() })
        agent.start(rec)
        agent.writeLine("ignored")
        rec.awaitExit()
        assertEquals(listOf<Pair<Int?, String>>(null to "no such proot"), rec.exits)
        agent.close()
    }

    @Test
    fun `a program left holding stdout can't hold up the exit`() {
        val rec = Recorder()
        val started = System.currentTimeMillis()
        transport("echo hi; sleep 8 & exit 0").start(rec)
        rec.awaitExit()
        assertTrue(System.currentTimeMillis() - started < 6000, "waited for the program left behind")
        assertEquals(listOf("hi"), rec.lines.toList())
    }

    @Test
    fun `close returns at once however long stopping takes, and stops once`() {
        val rec = Recorder()
        val stops = CopyOnWriteArrayList<Process>()
        val release = CountDownLatch(1)
        val agent = transport("cat", stop = {
            stops += it
            release.await()
            it.destroy()
        })
        agent.start(rec)
        Thread.sleep(200)
        val started = System.currentTimeMillis()
        agent.close()
        agent.close()
        assertTrue(System.currentTimeMillis() - started < 500, "close blocked")
        release.countDown()
        rec.awaitExit()
        assertEquals(1, stops.size)
    }

    @Test
    fun `a close before the process exists stops it once it does`() {
        val rec = Recorder()
        val spawned = CountDownLatch(1)
        val stops = CopyOnWriteArrayList<Process>()
        val agent = ProcessAgentTransport({
            spawned.await()
            ProcessBuilder("cat").start()
        }, {
            stops += it
            it.destroy()
        })
        agent.start(rec)
        agent.close()
        spawned.countDown()
        rec.awaitExit()
        assertEquals(1, stops.size)
    }

    @Test
    fun `the sign-in check reads the status and closes a stuck one`() {
        fun check(lines: List<String>, code: Int?): ChatAccess {
            val transport = FakeTransport()
            var got: ChatAccess? = null
            claudeAccessCheck(transport, { _, _ -> }, { got = it })
            lines.forEach { transport.listener!!.onLine(it) }
            transport.listener!!.onExit(code, "")
            return got!!
        }
        assertEquals(ChatAccess.READY, check(listOf("""{"loggedIn": true}"""), 0))
        assertEquals(ChatAccess.NOT_SIGNED_IN, check(listOf("{", """  "loggedIn": false""", "}"), 1))
        assertEquals(ChatAccess.READY, check(listOf("garbage"), null), "unknown goes ahead")

        val stuck = FakeTransport()
        var timeout: (() -> Unit)? = null
        var answers = 0
        claudeAccessCheck(stuck, { _, action -> timeout = action }, { answers++ })
        timeout!!()
        assertEquals(1, stuck.closed)
        assertEquals(1, answers)
        stuck.listener!!.onExit(143, "")
        assertEquals(1, answers, "answered once")
    }

    @Test
    fun `a process that has died accepts nothing, even before its exit is reported`() {
        val rec = Recorder()
        val agent = transport("sleep 4 & exit 0")
        agent.start(rec)
        val limit = System.currentTimeMillis() + 1800
        while (agent.accepting && System.currentTimeMillis() < limit) Thread.sleep(20)
        assertTrue(!agent.accepting, "still accepting after the process ended")
        assertTrue(rec.exits.isEmpty(), "the exit came first, so this proves nothing")
        rec.awaitExit()
    }

    @Test
    fun `a running process accepts lines, and a closing one doesn't`() {
        val rec = Recorder()
        val agent = transport("cat")
        assertTrue(agent.accepting, "before it starts, lines wait in the queue")
        agent.start(rec)
        Thread.sleep(200)
        assertTrue(agent.accepting)
        agent.close()
        assertTrue(!agent.accepting)
        rec.awaitExit()
    }
}
