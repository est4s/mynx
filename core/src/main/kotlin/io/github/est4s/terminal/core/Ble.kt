package io.github.est4s.terminal.core

/*
 * Bluetooth LE (`mynx ble`): the contract the parts share (work log,
 * "Next", 0). Core answers the requests below through [bleRequests];
 * the app only scans, connects and runs one GATT operation at a time
 * behind [BleHost]. Change the shapes here first, then everything else.
 *
 * Requests (lines after the name: positional arguments, then options
 * as `key=value` or a bare `key`, as the sensor requests take them):
 *
 * - `ble-scan [seconds=N] [name=TEXT] [service=UUID] [all]`: scans N s
 *   (5 by default, 1 to 60) and answers `{"ok":true,"devices":[ADVERT…]}`,
 *   one per device (its latest advertisement), strongest first. `name`
 *   matches a part of the name in any case, `service` an advertised
 *   service; without `all`, devices with no name and no services are
 *   left out.
 * - `ble-scan-stream [name=TEXT] [service=UUID] [all]`: a stream of
 *   ADVERT lines, each device at most once a second, until cancelled.
 * - `ble-services DEVICE`: `{"ok":true,"device":DEV,"mtu":N,"services":[
 *   {"uuid","name","characteristics":[{"uuid","name","properties":[…],
 *   "descriptors":[UUID…]}]}]}`; `properties` are [BleProp.word]s.
 * - `ble-read DEVICE CHAR [as=FORMAT]`: `{"ok":true,"device":DEV,
 *   "char":UUID,"name","as":FORMAT,"value":…,"hex":"0a 1b"}`.
 * - `ble-write DEVICE CHAR VALUE [as=FORMAT] [no-response]`:
 *   `{"ok":true,"device":DEV,"char":UUID,"name","bytes":N}`.
 * - `ble-notify DEVICE CHAR [as=FORMAT]`: a stream of `{"char":UUID,
 *   "name","as","value","hex","time"}` lines (notify, else indicate),
 *   until cancelled (it unsubscribes) or the link drops (answered as a
 *   refusal with the reason).
 * - `ble-bind DEVICE [nusN]` and `ble-unbind PORT|DEVICE`: BLE UART
 *   ports, see `BleUart.kt`.
 * - `ble-connect DEVICE`: holds a link while the stream runs; lines
 *   `{"state":"connected","device":DEV,"mtu":N}` (and
 *   `"disconnected"` with `"error"` before a drop's refusal). While it
 *   holds one, the other requests on DEVICE use that link; without it
 *   each connects, does its job and disconnects.
 *
 * ADVERT: `{"address","address_type":"public"|"random","name","rssi",
 * "services":[UUID…],"manufacturer":[{"company":N,"hex"}],
 * "connectable","time"}`. DEV: `{"address","name"}`. UUIDs are full and
 * in lower case; `name` is a known name (`battery-level`) or null; `hex`
 * is bytes as two digits each, spaced; `time` is ms since 1970.
 *
 * DEVICE: an address (any case), or a name: a paired device's or one
 * the last scan saw, exact, else in any case. CHAR: a full or 16-bit
 * UUID (`2a19`), a known name, or `SERVICE/CHAR` when two services
 * have it. FORMAT: `hex` (read's and notify's default, unless a known
 * characteristic has its own: `battery-level` is `u8`), `text`
 * (UTF-8), `u8`, `u16`, `u32`, `i8`, `i16`, `i32` (little-endian, as
 * GATT), `float32`; `value` is a number for those, else a string.
 * `write`'s FORMAT is `hex` unless given; `hex` takes `0a1b` or
 * `0a 1b`. A write longer than the MTU less 3 is refused with the
 * limit.
 *
 * Refused, with the reason: `android-bluetooth` off, no Bluetooth LE,
 * Bluetooth off, a 6th scan within 30 s (Android would silently scan
 * nothing), an unknown device or characteristic, a property the
 * characteristic hasn't got. The permission is asked first, as the
 * `bt-*` requests do.
 */

/** Every request [bleRequests] answers once it's built. */
val BLE_REQUEST_NAMES = listOf(
    "ble-scan", "ble-scan-stream", "ble-services", "ble-read", "ble-write", "ble-notify", "ble-connect",
    "ble-bind", "ble-unbind",
)

/** A characteristic's properties, as GATT has them; [word] is how JSON and `mynx` write each. */
enum class BleProp(val word: String) {
    READ("read"), WRITE("write"), WRITE_NO_RESPONSE("write-no-response"), NOTIFY("notify"), INDICATE("indicate"),
}

/** One advertisement a scan saw. [address] in upper case; [services] full UUIDs in lower case; [manufacturer] by company ID. */
class BleAdvert(
    val address: String,
    val randomAddress: Boolean,
    val name: String?,
    val rssi: Int,
    val services: List<String>,
    val manufacturer: Map<Int, ByteArray>,
    val connectable: Boolean,
    val time: Long,
)

/** What the app filters a scan by, so Android scans for it with the screen off too ([BleHost.scan]). */
data class BleScanFilter(val name: String? = null, val service: String? = null)

/**
 * A characteristic of a connected device. [service] and [uuid] are full
 * UUIDs in lower case; [instance] tells two with the same UUIDs apart
 * (Android's instance id); [descriptors] are UUIDs.
 */
data class BleChar(
    val service: String,
    val uuid: String,
    val instance: Int,
    val properties: Set<BleProp>,
    val descriptors: List<String>,
)

/** A service of a connected device, with its characteristics, in the device's order. */
data class BleService(val uuid: String, val characteristics: List<BleChar>)

/**
 * A stop handle for something the host keeps running (a scan, a
 * subscription); calling it twice is harmless.
 */
fun interface BleStop {
    fun stop()
}

/**
 * A connected device. The host runs one GATT operation at a time (it
 * queues them), and calls each callback exactly once, on any thread,
 * within its timeout (10 s), failing with a reason a person can read
 * ("timed out", "not permitted", "link lost").
 */
interface BleLink {
    val address: String
    /** The services, discovered before the link is handed out. */
    val services: List<BleService>
    /** The negotiated MTU (23 until the device agrees to more). */
    val mtu: Int
    fun read(char: BleChar, done: (Result<ByteArray>) -> Unit)
    fun write(char: BleChar, value: ByteArray, withResponse: Boolean, done: (Result<Unit>) -> Unit)
    /**
     * Subscribes (writes the CCCD: notify if it has it, else indicate);
     * [value] gets each value with the time it came (ms since 1970).
     * [done] says whether subscribing worked; the [BleStop] unsubscribes.
     */
    fun subscribe(char: BleChar, value: (ByteArray, Long) -> Unit, done: (Result<Unit>) -> Unit): BleStop
    /** Disconnects; nothing is called back after it. */
    fun close()
}

/**
 * What the `ble-*` requests need from the app. Any of it may be called
 * on the thread that answers requests or on a callback's thread.
 */
interface BleHost {
    /** Whether the phone has Bluetooth LE at all. */
    val available: Boolean
    /** Whether the app may scan and connect (Android 12+: BLUETOOTH_SCAN and _CONNECT; below: location, and it's on). */
    val allowed: Boolean
    /** Whether Bluetooth is on; only asked once [allowed]. */
    val enabled: Boolean
    /** Whether the screen is on with the app in front: Android only finds filtered devices otherwise. */
    val inFront: Boolean
    /** The paired devices (for names); only called when [allowed] and [enabled]. */
    fun bonded(): List<BtDevice>
    /** As [BluetoothHost.askPermission], for the BLE permissions. */
    fun askPermission(done: (String?) -> Unit)
    /**
     * Starts a scan: [found] gets every advertisement until the
     * [BleStop]; [failed] (once, then nothing more) why Android
     * stopped or refused it.
     */
    fun scan(filter: BleScanFilter, found: (BleAdvert) -> Unit, failed: (String) -> Unit): BleStop
    /**
     * Connects to [address] ([randomAddress] as a scan saw it) and
     * discovers its services: [ready] gets the link, or why not (within
     * 30 s). [dropped] gets why, when a link handed out is lost
     * (not after [BleLink.close]).
     */
    fun connect(address: String, randomAddress: Boolean, ready: (Result<BleLink>) -> Unit, dropped: (String) -> Unit)
}

/**
 * The `ble-*` requests ([BLE_REQUEST_NAMES]), for [MynxRequests]'s
 * `later`. [bluetooth] is the `bt-*` requests' host: `ble-bind` and
 * `ble-unbind` read its `ports()` and call its `bindingsChanged()`.
 */
fun bleRequests(home: java.io.File, host: BleHost, bluetooth: BluetoothHost): Map<String, Later> =
    bleRequests(home, host, bluetooth, System::currentTimeMillis, ::bleSchedule)
