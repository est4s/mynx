package io.github.est4s.terminal.core

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/** A [BleHost] the tests drive by hand: they see what was asked and answer it. */
class FakeBleHost : BleHost {
    override var available = true
    override var allowed = true
    override var enabled = true
    override var inFront = true
    var devices = listOf<BtDevice>()
    val asks = mutableListOf<(String?) -> Unit>()

    class Scan(val filter: BleScanFilter, val found: (BleAdvert) -> Unit, val failed: (String) -> Unit) {
        var stopped = false
    }
    val scans = mutableListOf<Scan>()

    class Connect(
        val address: String,
        val randomAddress: Boolean,
        val ready: (Result<BleLink>) -> Unit,
        val dropped: (String) -> Unit,
    )
    val connects = mutableListOf<Connect>()

    override fun bonded() = devices.also { check(allowed && enabled) { "bonded() needs the permission and Bluetooth on" } }
    override fun askPermission(done: (String?) -> Unit) { asks += done }
    override fun scan(filter: BleScanFilter, found: (BleAdvert) -> Unit, failed: (String) -> Unit): BleStop {
        val scan = Scan(filter, found, failed).also { scans += it }
        return BleStop { scan.stopped = true }
    }
    override fun connect(address: String, randomAddress: Boolean, ready: (Result<BleLink>) -> Unit, dropped: (String) -> Unit) {
        connects += Connect(address, randomAddress, ready, dropped)
    }
}

/** A [BleLink] with [services]; operations wait in [pending] until a test answers them. */
class FakeBleLink(override val address: String, override val services: List<BleService>, override var mtu: Int = 23) : BleLink {
    val pending = mutableListOf<Pair<String, Any>>()
    var closed = false

    override fun read(char: BleChar, done: (Result<ByteArray>) -> Unit) { pending += "read ${char.uuid}" to done }
    override fun write(char: BleChar, value: ByteArray, withResponse: Boolean, done: (Result<Unit>) -> Unit) {
        pending += "write ${char.uuid} ${value.joinToString(" ") { "%02x".format(it) }} $withResponse" to done
    }
    override fun subscribe(char: BleChar, value: (ByteArray, Long) -> Unit, done: (Result<Unit>) -> Unit): BleStop {
        pending += "subscribe ${char.uuid}" to (value to done)
        return BleStop { pending += "unsubscribe ${char.uuid}" to Unit }
    }
    override fun close() { closed = true }
}

class BleTest {
    @Test
    fun `answers only the planned requests`() {
        val home = createTempDirectory("ble").toFile()
        try {
            assertTrue(BLE_REQUEST_NAMES.containsAll(bleRequests(home, FakeBleHost()).keys))
        } finally {
            home.deleteRecursively()
        }
    }
}
