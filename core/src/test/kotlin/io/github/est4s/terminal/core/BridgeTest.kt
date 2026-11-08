package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [ReplySink] that keeps what it was given. */
class FakeReplySink : ReplySink {
    var waited: Int? = null
    var waitedCalled = false
    val lines = mutableListOf<String>()
    val answers = mutableListOf<String>()
    var discarded = false

    override fun waiting(seconds: Int?) {
        waitedCalled = true
        waited = seconds
    }

    override fun line(json: String) {
        lines += json
    }

    override fun answer(json: String) {
        answers += json
    }

    override fun discard() {
        discarded = true
    }
}

/** A [BridgeSocket] that keeps what was sent. */
class FakeBridgeSocket : BridgeSocket {
    val sent = mutableListOf<ByteArray>()
    var closed = false

    override fun send(bytes: ByteArray) {
        sent += bytes
    }

    override fun close() {
        closed = true
    }
}

/**
 * A [BridgeHost] the tests drive by hand: they see what was asked,
 * answer dialogs and serial connections, and run scheduled blocks.
 */
class FakeBridgeHost : BridgeHost {
    /** The apps whose screen is in front. */
    val front = mutableSetOf<String>()
    /** Open dialogs: the app, the permission, and how to answer it. */
    val asked = mutableListOf<Triple<String, AppPermission, (Boolean?) -> Unit>>()
    var dismissed = 0
    /** Log lines, `NAME LEVEL MESSAGE`. */
    val logged = mutableListOf<String>()
    val notices = mutableListOf<String>()
    var notifyFails: String? = null
    /** Each app's held orientation; a null value: freed. */
    val rotations = mutableListOf<Pair<String, Orientation?>>()
    override var cameraApp = false
    val running = mutableSetOf<String>()
    /** Serial connections asked for, with their callbacks. */
    val serialConnects = mutableListOf<SerialConnect>()
    val scheduled = mutableListOf<Scheduled>()

    class SerialConnect(
        val tcpPort: Int,
        val received: (ByteArray) -> Unit,
        val closed: (String?) -> Unit,
        val ready: (Result<BridgeSocket>) -> Unit,
    )

    class Scheduled(val delayMs: Long, val block: () -> Unit) {
        var cancelled = false
    }

    override fun inFront(app: String) = app in front

    override fun ask(app: WebApp, permission: AppPermission, answer: (Boolean?) -> Unit): () -> Unit {
        val entry = Triple(app.name, permission, answer)
        asked += entry
        return {
            if (asked.remove(entry)) dismissed++
        }
    }

    /** Answers the oldest open dialog. */
    fun answer(allow: Boolean?) {
        asked.removeAt(0).third(allow)
    }

    override fun log(app: String, level: WebAppLogLevel, message: String) {
        logged += "$app ${level.word} $message"
    }

    override fun notify(app: WebApp, title: String, text: String, ifAway: Boolean): String? {
        notifyFails?.let { return it }
        notices += "${app.name}: $title: $text${if (ifAway) " (if away)" else ""}"
        return null
    }

    override fun rotation(app: String, orientation: Orientation?): String? {
        rotations += app to orientation
        return null
    }

    override fun serviceRunning(name: String) = name in running

    override fun connectSerial(
        tcpPort: Int,
        received: (ByteArray) -> Unit,
        closed: (String?) -> Unit,
        ready: (Result<BridgeSocket>) -> Unit,
    ) {
        serialConnects += SerialConnect(tcpPort, received, closed, ready)
    }

    override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
        val entry = Scheduled(delayMs, block)
        scheduled += entry
        return { entry.cancelled = true }
    }

    /** Runs every scheduled block not cancelled, oldest first (blocks scheduled meanwhile wait for the next call). */
    fun runScheduled() {
        val due = scheduled.toList()
        scheduled.clear()
        due.filter { !it.cancelled }.forEach { it.block() }
    }
}

class BridgeTest {
    @Test
    fun `each call is offered once`() {
        val names = BRIDGE_CALLS.map { it.call }
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `no config request is offered`() {
        val config = listOf("set", "reset", "theme-set", "keybar-edit", "undo", "check", "bt-bind", "ble-bind",
            "ble-unbind", "service-add", "service-remove", "service-set", "usb-allow", "app-permission-set")
        assertTrue(BRIDGE_CALLS.none { it.call in config || it.request in config })
    }

    @Test
    fun `every private permission guards some call`() {
        val used = BRIDGE_CALLS.mapNotNull { it.permission }.toSet()
        assertEquals(AppPermission.entries.toSet(), used)
    }

    @Test
    fun `every serial call is offered and needs the serial permission`() {
        for (name in BRIDGE_SERIAL_CALLS) {
            assertEquals(AppPermission.SERIAL, BRIDGE_CALLS.single { it.call == name }.permission, name)
        }
    }
}
