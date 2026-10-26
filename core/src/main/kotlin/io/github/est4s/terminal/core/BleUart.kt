package io.github.est4s.terminal.core

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
 * Part A.
 */
fun bleUart(services: List<BleService>): BleUart? = TODO("part A")

/**
 * A BLE UART port to [address], as the RFC 2217 server drives it, like
 * the app's `BtLine` for Classic ports (part A builds it in core, so
 * it's tested against fakes):
 *
 * - [clientArrived] connects through [host] ([BleHost.connect] with
 *   [randomAddress]), finds the UART ([bleUart]) and subscribes to its
 *   tx, blocking until all of that is done; otherwise it closes what it
 *   opened and throws an IOException with the reason ("Bluetooth is
 *   off", "mynx-ble has no Nordic UART or HM-10 service", the host's).
 *   A link already up is reused.
 * - Each tx value goes to [received], in order.
 * - [write] splits data into chunks of the MTU less 3 and writes them
 *   one after another (no-response unless [BleUart.withResponse]),
 *   blocking until each is done, so a fast writer is held back; a
 *   failed write drops the link (as below).
 * - A link that drops (the host's `dropped`, a failed write) records
 *   [error], closes it and calls [dropped] (the bridge ends the
 *   client: `SerialServer.endClient()`).
 * - [clientLeft] unsubscribes and closes the link.
 * - After a failed or dropped link, [clientArrived] refuses at once
 *   (with [error]) for 2 s, doubling up to 30 s, by [now]; a link that
 *   works resets it.
 * - Baud, data bits, DTR, RTS, break and flow control are accepted and
 *   change nothing; purge does nothing; [modemState] is CTS, DSR and
 *   CD while linked, else 0.
 */
class BleUartLine(
    private val host: BleHost,
    val address: String,
    private val randomAddress: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : SerialLine {
    /** Gets the device's bytes; set before the server starts. */
    @Volatile var received: (ByteArray) -> Unit = {}
    /** Called once per link that drops; set before the server starts. */
    @Volatile var dropped: () -> Unit = {}
    /** Whether the link is up. */
    val linked: Boolean get() = TODO("part A")
    /** Why the last link failed or dropped; null once one works. */
    val error: String? get() = TODO("part A")

    override fun clientArrived(): Unit = TODO("part A")
    override fun clientLeft(): Unit = TODO("part A")
    override fun write(data: ByteArray): Unit = TODO("part A")
    override fun configure(settings: LineSettings): Unit = TODO("part A")
    override fun setDtr(on: Boolean): Unit = TODO("part A")
    override fun setRts(on: Boolean): Unit = TODO("part A")
    override fun setBreak(on: Boolean): Unit = TODO("part A")
    override fun setFlowControl(flow: FlowControl): Boolean = TODO("part A")
    override fun purge(input: Boolean, output: Boolean): Unit = TODO("part A")
    override fun modemState(): Int = TODO("part A")
}
