package io.github.est4s.terminal.core

import java.io.IOException

/*
 * BLE UART ports (`nus0`): a Bluetooth LE device with a UART service
 * (Nordic UART, or an HM-10 module's) served as a serial port, as a
 * bound Classic device is `rfcomm0` (work log, "Next", 0). The contract
 * the parts share; change the shapes here first.
 *
 * - **Config:** `bluetooth.conf` takes `nusN = ADDRESS` lines beside
 *   `rfcommN = ADDRESS`, with ` random` after the address when the
 *   device's address is random (a scan said so): `nus0 = 1C:DB:D4:C5:CF:36`.
 *   [BtBinding.random] holds it; `rfcomm` lines never have it. Names
 *   `nus0` to `nus255` ([isNusName]); `mynx check` and undo cover them
 *   as they do `rfcomm` lines. One address is bound once, whatever the
 *   kind.
 * - **Requests** (in [bleRequests], which also gets the
 *   [BluetoothHost] for `ports()` and `bindingsChanged()`):
 *   - `ble-bind DEVICE [nusN]`: DEVICE as the other `ble-*` requests
 *     take it (an address, a paired device's or a scanned one's name).
 *     Connects (or uses a link `ble-connect` holds) to check
 *     [bleUart] finds a UART, refused otherwise ("mynx-ble has no
 *     Nordic UART or HM-10 service"); then writes the line (the lowest
 *     free `nusN` unless given), calls `bindingsChanged()` and answers
 *     `{"ok":true,"port":PORT,"kind":"nordic-uart"|"hm-10"}`, PORT as
 *     [btPortJson]. Already bound: answers its port (refused if a
 *     different name was asked). Undo's change text: `ble bind DEVICE`.
 *   - `ble-unbind PORT|DEVICE`: removes `nus` lines as `bt-unbind`
 *     removes `rfcomm` ones; `{"ok":true,"removed":[NAME…]}`.
 *   - `bt-list` lists `nus` ports in `ports` too; `bt-bind` refuses a
 *     `nusN` name ("use mynx ble bind"); `bt-unbind` removes either
 *     kind.
 * - **Serving** (the app's `BtBridge`): each `nusN` binding is a
 *   [ServedPort]-style port, `/dev/serial/nusN`, RFC 2217 on localhost
 *   from [FIRST_NUS_TCP_PORT], its line a [BleUartLine] over the app's
 *   [BleHost]; served while the setting, the permission and Bluetooth
 *   allow, as `rfcomm` ports are. `BluetoothHost.ports()` gives its
 *   [BtPortState] (`linked` and `error` from the line). Debian also
 *   gets `/dev/nusN` and a virtual tty in `/sys/class/tty` (no
 *   `device`), as `rfcommN` (`writeSerialNames`); no uevents (Linux
 *   has no such tty, so nothing listens for one).
 */

/** BLE UART ports' first TCP port (`rfc2217://localhost:2417`), past Bluetooth serial's ([FIRST_BLUETOOTH_TCP_PORT]). */
const val FIRST_NUS_TCP_PORT = 2417
private const val MAX_NUS = 255
private val NUS_NAME = Regex("nus(0|[1-9][0-9]{0,2})")

/** Whether [name] is a BLE UART port's name: `nus0` to `nus255`. */
fun isNusName(name: String): Boolean =
    NUS_NAME.matchEntire(name)?.groupValues?.get(1)?.toInt()?.let { it <= MAX_NUS } == true

/**
 * The UART services a port speaks, in the order a device's services are
 * tried: [rx] is written to, [tx] notifies (HM-10 modules use one
 * characteristic for both). Full UUIDs in lower case.
 */
enum class BleUartKind(val word: String, val service: String, val rx: String, val tx: String) {
    NORDIC(
        "nordic-uart",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
        "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
    ),
    HM10(
        "hm-10",
        "0000ffe0-0000-1000-8000-00805f9b34fb",
        "0000ffe1-0000-1000-8000-00805f9b34fb",
        "0000ffe1-0000-1000-8000-00805f9b34fb",
    ),
}

/**
 * A device's UART: the characteristic written ([rx]) and the one that
 * notifies or indicates ([tx]). [withResponse] when [rx] has no
 * write-no-response (then each chunk waits for the device's answer).
 */
data class BleUart(val kind: BleUartKind, val rx: BleChar, val tx: BleChar, val withResponse: Boolean)

/**
 * The first UART in [services], by [BleUartKind]'s order, whose [rx]
 * can be written and whose [tx] notifies or indicates; null if none.
 */
fun bleUart(services: List<BleService>): BleUart? = BleUartKind.entries.firstNotNullOfOrNull { kind ->
    val chars = services.filter { it.uuid == kind.service }.flatMap { it.characteristics }
    val rx = chars.firstOrNull { it.uuid == kind.rx && (BleProp.WRITE in it.properties || BleProp.WRITE_NO_RESPONSE in it.properties) }
    val tx = chars.firstOrNull { it.uuid == kind.tx && (BleProp.NOTIFY in it.properties || BleProp.INDICATE in it.properties) }
    if (rx == null || tx == null) null else BleUart(kind, rx, tx, withResponse = BleProp.WRITE_NO_RESPONSE !in rx.properties)
}

// CTS, DSR and CD as RFC 2217's modem-state bits: on while linked.
private const val LINKED_MODEM_STATE = 0x10 or 0x20 or 0x80
// The wait after the first failed link; it doubles with each one after.
private const val RETRY_FIRST_MS = 2000L
private const val RETRY_MAX_MS = 30_000L
// Past the host's own limits (30 s to connect, 10 s an operation), so only a broken host hits them.
private const val CONNECT_WAIT_MS = 45_000L
private const val OP_WAIT_MS = 20_000L

/**
 * A BLE UART port to [address], as the RFC 2217 server drives it, like
 * the app's `BtLine` for Classic ports (built in core, so it's tested
 * against fakes):
 *
 * - [clientArrived] connects through [host] ([BleHost.connect] with
 *   [randomAddress]), finds the UART ([bleUart]) and subscribes to its
 *   tx, blocking until all of that is done; otherwise it closes what it
 *   opened and throws an IOException with the reason ("Bluetooth is
 *   off", "mynx-ble has no Nordic UART or HM-10 service", the host's).
 *   A link already up is reused. Messages say [name] (the address
 *   unless the bridge gives the device's name).
 * - Each tx value goes to [received], in order.
 * - [write] splits data into chunks of the MTU less 3 and writes them
 *   one after another (no-response unless [BleUart.withResponse]),
 *   blocking until each is done, so a fast writer is held back; a
 *   failed write drops the link (as below). With no link it does
 *   nothing.
 * - A link that drops (the host's `dropped`, a failed write) records
 *   [error], closes it and calls [dropped] (the bridge ends the
 *   client: `SerialServer.endClient()`), once per link.
 * - [clientLeft] unsubscribes and closes the link. While [clientArrived]
 *   is still linking, it stops that (no [error]: leaving isn't a
 *   failure), and a link that comes after is closed.
 * - After a failed or dropped link, [clientArrived] refuses at once
 *   (with [error]) for 2 s, doubling up to 30 s, by [now]; a link that
 *   works resets it.
 * - The host promises an answer within 30 s for a connect and 10 s for
 *   an operation; past 45 s and 20 s the line gives up anyway ("timed
 *   out"), so a broken host can't hang the client's thread.
 * - Baud, data bits, DTR, RTS, break and flow control are accepted and
 *   change nothing (only no flow control is "done"); purge does
 *   nothing; [modemState] is CTS, DSR and CD while linked, else 0.
 *
 * Callbacks may come on any thread; [write] comes from the server's
 * client thread.
 */
class BleUartLine(
    private val host: BleHost,
    val address: String,
    private val randomAddress: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val name: String = address,
) : SerialLine {
    /** Gets the device's bytes; set before the server starts. */
    @Volatile var received: (ByteArray) -> Unit = {}
    /** Called once per link that drops; set before the server starts. */
    @Volatile var dropped: () -> Unit = {}

    internal var connectWaitMs = CONNECT_WAIT_MS
    internal var opWaitMs = OP_WAIT_MS

    /** One try at a link; everything in it is guarded by the line's lock. */
    private class Attempt {
        var connected: Result<BleLink>? = null
        var link: BleLink? = null
        var uart: BleUart? = null
        var subscribed: Result<Unit>? = null
        var stop: BleStop? = null
        // Why the host lost it while it was still being set up.
        var lost: String? = null
        // The client left while it was being set up.
        var abandoned = false
        // Closed (left, failed or dropped): callbacks for it are ignored from now on.
        var ended = false
    }

    private val lock = Object()
    private val writeLock = Any()
    private var current: Attempt? = null
    private var linking: Attempt? = null
    private var failures = 0
    private var retryAt = 0L
    private var lastError: String? = null

    /** Whether the link is up. */
    val linked: Boolean get() = synchronized(lock) { current != null }
    /** Why the last link failed or dropped; null once one works. */
    val error: String? get() = synchronized(lock) { lastError }

    private class Left : IOException("the client left while linking")

    // Records a failure (the wait before the next try grows) and gives the exception to throw. Under lock.
    private fun failure(why: String): IOException {
        lastError = why
        retryAt = now() + minOf(RETRY_FIRST_MS shl minOf(failures, 4), RETRY_MAX_MS)
        failures++
        return IOException(why)
    }

    private fun failed(why: String): IOException = synchronized(lock) { failure(why) }

    override fun clientArrived() {
        val attempt = Attempt()
        synchronized(lock) {
            if (current != null) return
            if (now() < retryAt) throw IOException(lastError ?: "waiting to link again")
            linking = attempt
        }
        try {
            if (!host.enabled) throw failed("Bluetooth is off")
            if (!host.allowed) throw failed("Bluetooth isn't allowed any more (mynx ble asks again)")
            host.connect(address, randomAddress, ready = { onReady(attempt, it) }, dropped = { onDropped(attempt, it) })
            val link = (awaitStep(attempt, connectWaitMs) { it.connected } ?: throw failed("couldn't link to $name: timed out"))
                .getOrElse { throw failed("couldn't link to $name: ${it.message ?: it.javaClass.simpleName}") }
            val uart = bleUart(link.services) ?: throw failed("$name has no Nordic UART or HM-10 service")
            synchronized(lock) { attempt.uart = uart }
            val stop = link.subscribe(
                uart.tx,
                value = { bytes, _ -> if (synchronized(lock) { !attempt.ended }) received(bytes) },
                done = { result ->
                    synchronized(lock) {
                        attempt.subscribed = result
                        lock.notifyAll()
                    }
                },
            )
            synchronized(lock) { attempt.stop = stop }
            (awaitStep(attempt, opWaitMs) { it.subscribed } ?: throw failed("couldn't subscribe to $name's UART: timed out"))
                .getOrElse { throw failed("couldn't subscribe to $name's UART: ${it.message ?: it.javaClass.simpleName}") }
            synchronized(lock) {
                if (attempt.abandoned) throw Left()
                attempt.lost?.let { throw failure("the link dropped: $it") }
                current = attempt
                linking = null
                lastError = null
                failures = 0
                retryAt = 0
            }
        } catch (e: IOException) {
            synchronized(lock) { if (linking === attempt) linking = null }
            end(attempt)
            throw e
        }
    }

    // Waits for [step] of [attempt] to be answered: null when it isn't within [ms].
    private fun <T> awaitStep(attempt: Attempt, ms: Long, step: (Attempt) -> T?): T? = synchronized(lock) {
        val until = System.nanoTime() + ms * 1_000_000
        while (true) {
            if (attempt.abandoned) throw Left()
            attempt.lost?.let { throw failure("the link dropped: $it") }
            step(attempt)?.let { return it }
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            lock.wait(left)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun onReady(attempt: Attempt, result: Result<BleLink>) {
        val late = synchronized(lock) {
            if (attempt.ended || attempt.abandoned) {
                result.getOrNull()
            } else {
                attempt.connected = result
                attempt.link = result.getOrNull()
                lock.notifyAll()
                null
            }
        }
        late?.let { runCatching { it.close() } }
    }

    private fun onDropped(attempt: Attempt, why: String) {
        synchronized(lock) {
            if (attempt.ended) return
            if (current !== attempt) {
                attempt.lost = why
                lock.notifyAll()
                return
            }
        }
        drop(attempt, "the link dropped: $why")
    }

    // Ends a link that was up: records why, closes it and tells the bridge, once.
    private fun drop(attempt: Attempt, why: String) {
        synchronized(lock) {
            if (attempt.ended || current !== attempt) return
            current = null
            failure(why)
        }
        end(attempt, unsubscribe = false)
        runCatching { dropped() }
    }

    // Marks [attempt] ended and closes what it opened (unsubscribing first, unless the link is gone).
    private fun end(attempt: Attempt, unsubscribe: Boolean = true) {
        val (stop, link) = synchronized(lock) {
            attempt.ended = true
            lock.notifyAll()
            attempt.stop to attempt.link
        }
        if (unsubscribe) stop?.let { runCatching { it.stop() } }
        link?.let { runCatching { it.close() } }
    }

    override fun clientLeft() {
        val up = synchronized(lock) {
            linking?.let {
                it.abandoned = true
                lock.notifyAll()
            }
            current.also { current = null }
        }
        up?.let { end(it) }
    }

    override fun write(data: ByteArray) {
        synchronized(writeLock) {
            val attempt = synchronized(lock) { current } ?: return
            val link = synchronized(lock) { attempt.link } ?: return
            val uart = synchronized(lock) { attempt.uart } ?: return
            val size = maxOf(link.mtu - 3, 1)
            var at = 0
            while (at < data.size) {
                val chunk = data.copyOfRange(at, minOf(at + size, data.size))
                var result: Result<Unit>? = null
                link.write(uart.rx, chunk, uart.withResponse) {
                    synchronized(lock) {
                        result = it
                        lock.notifyAll()
                    }
                }
                val answer = synchronized(lock) {
                    val until = System.nanoTime() + opWaitMs * 1_000_000
                    while (result == null && !attempt.ended) {
                        val left = (until - System.nanoTime()) / 1_000_000
                        if (left <= 0) break
                        lock.wait(left)
                    }
                    if (attempt.ended) return
                    result
                }
                if (answer == null || answer.isFailure) {
                    val why = answer?.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "timed out"
                    drop(attempt, "couldn't write to $name: $why")
                    return
                }
                at += chunk.size
            }
        }
    }

    override fun configure(settings: LineSettings) {}

    override fun setDtr(on: Boolean) {}

    override fun setRts(on: Boolean) {}

    override fun setBreak(on: Boolean) {}

    override fun setFlowControl(flow: FlowControl): Boolean = flow == FlowControl.NONE

    override fun purge(input: Boolean, output: Boolean) {}

    override fun modemState(): Int = if (linked) LINKED_MODEM_STATE else 0
}
