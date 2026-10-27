package io.github.est4s.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import io.github.est4s.terminal.core.BleAdvert
import io.github.est4s.terminal.core.BleChar
import io.github.est4s.terminal.core.BleHost
import io.github.est4s.terminal.core.BleLink
import io.github.est4s.terminal.core.BleProp
import io.github.est4s.terminal.core.BleScanFilter
import io.github.est4s.terminal.core.BleService
import io.github.est4s.terminal.core.BleStop
import io.github.est4s.terminal.core.BtDevice
import java.util.Collections
import java.util.UUID

private const val CONNECT_TIMEOUT_MS = 30_000L
private const val OP_TIMEOUT_MS = 10_000L
// Some devices never answer an MTU request; services are discovered anyway after this.
private const val MTU_WAIT_MS = 5_000L
private const val WANTED_MTU = 517
private const val DEFAULT_MTU = 23
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Bluetooth LE for the `ble-*` requests: scans, and connections that run
 * one GATT operation at a time. Every connection's callbacks and state
 * live on one thread of its own ([handler]), so nothing races; the
 * methods here may be called from any thread. Core decides everything
 * else (filtering, names, formats, refusals).
 */
class BleCentral(
    private val context: Context,
    private val mainHandler: Handler,
    private val onScreen: () -> Boolean,
    private val askPermissions: (Array<String>, (Boolean) -> Unit) -> Boolean,
) : BleHost {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = lazy { HandlerThread("ble").apply { start() } }
    private val handler by lazy { Handler(thread.value.looper) }
    private val scans: MutableSet<ScanCallback> = Collections.synchronizedSet(mutableSetOf())
    // Touched only on handler's thread.
    private val connections = mutableSetOf<Connection>()

    override val available: Boolean
        get() = adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override val allowed: Boolean
        get() = if (Build.VERSION.SDK_INT >= 31) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) && locationOn()
        }

    override val enabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    override val inFront: Boolean get() = onScreen()

    override fun bonded(): List<BtDevice> = bondedDevices(adapter)

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // Below Android 12, scans find nothing while location is off, even with the permission.
    private fun locationOn(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            manager.isLocationEnabled
        } else {
            runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    override fun askPermission(done: (String?) -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissions.all(::granted)) return done(whyNotAllowed())
        onMain {
            val asked = askPermissions(permissions) { done(whyNotAllowed()) }
            if (!asked) done("Android asks for it with the app on screen: open mynx and try again")
        }
    }

    private fun whyNotAllowed(): String? = when {
        allowed -> null
        Build.VERSION.SDK_INT >= 31 ->
            "the permission was refused: allow \"Nearby devices\" in Android's settings for mynx (Apps → mynx → Permissions)"
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) ->
            "the permission was refused: below Android 12, Bluetooth LE needs precise location; allow it in Android's settings for mynx (Apps → mynx → Permissions)"
        else -> "location is off: below Android 12, Android finds no Bluetooth LE devices without it; turn it on"
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Android matches a scan filter's name exactly, while core matches a
     * part of it in any case: so the name becomes a filter only with the
     * app away (where an unfiltered scan finds nothing at all) and core
     * filters what comes. A service is filtered by Android always.
     */
    @SuppressLint("MissingPermission") // core scans only once allowed
    override fun scan(filter: BleScanFilter, found: (BleAdvert) -> Unit, failed: (String) -> Unit): BleStop {
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            failed("Bluetooth is off")
            return BleStop {}
        }
        val builder = ScanFilter.Builder()
        var filtered = false
        filter.service?.let { service ->
            runCatching { ParcelUuid.fromString(service) }.getOrNull()?.let {
                builder.setServiceUuid(it)
                filtered = true
            }
        }
        if (filter.name != null && !inFront) {
            builder.setDeviceName(filter.name)
            filtered = true
        }
        val filters = if (filtered) listOf(builder.build()) else emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            @Volatile var over = false

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!over) found(advert(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (!over) results.forEach { found(advert(it)) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (over) return
                over = true
                scans.remove(this)
                failed(scanFailure(errorCode))
            }
        }
        try {
            scans += callback
            scanner.startScan(filters, settings, callback)
        } catch (e: Exception) {
            callback.over = true
            scans.remove(callback)
            failed(if (e is SecurityException) "Bluetooth LE isn't allowed any more (mynx ble asks again)" else "couldn't scan: ${e.message ?: e}")
            return BleStop {}
        }
        return BleStop {
            if (!callback.over) {
                callback.over = true
                scans.remove(callback)
                stopScan(callback)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(callback: ScanCallback) {
        // Throws or refuses once Bluetooth is off, when Android has stopped it anyway.
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun advert(result: ScanResult): BleAdvert {
        val device = result.device
        val record = result.scanRecord
        val random = Build.VERSION.SDK_INT >= 35 &&
            runCatching { device.addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM }.getOrDefault(false)
        val manufacturer = mutableMapOf<Int, ByteArray>()
        record?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) manufacturer[data.keyAt(i)] = data.valueAt(i) ?: ByteArray(0)
        }
        // Result times count from boot; core wants the clock's.
        val ago = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
        return BleAdvert(
            address = device.address.uppercase(),
            randomAddress = random,
            name = record?.deviceName ?: runCatching { device.name }.getOrNull(),
            rssi = result.rssi,
            services = record?.serviceUuids.orEmpty().map { it.uuid.toString().lowercase() },
            manufacturer = manufacturer,
            connectable = result.isConnectable,
            time = System.currentTimeMillis() - ago,
        )
    }

    /**
     * Below Android 13 (no getRemoteLeDevice) and for public addresses,
     * Android looks the address up among the devices it has seen, so a
     * random one connects only after a scan saw it.
     */
    @SuppressLint("MissingPermission")
    override fun connect(address: String, randomAddress: Boolean, ready: (Result<BleLink>) -> Unit, dropped: (String) -> Unit) {
        handler.post {
            val adapter = adapter ?: return@post ready(Result.failure(Exception("no Bluetooth")))
            val device = try {
                val upper = address.uppercase()
                if (randomAddress && Build.VERSION.SDK_INT >= 33) {
                    adapter.getRemoteLeDevice(upper, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                } else {
                    adapter.getRemoteDevice(upper)
                }
            } catch (e: IllegalArgumentException) {
                return@post ready(Result.failure(Exception("not a Bluetooth address: $address")))
            }
            Connection(device, ready, dropped).start()
        }
    }

    /** Ends every scan and link, calling nothing back: the service is stopping. */
    fun closeAll() {
        scans.toList().forEach { stopScan(it) }
        scans.clear()
        if (!thread.isInitialized()) return
        handler.post {
            connections.toList().forEach { it.close() }
            thread.value.quitSafely()
        }
    }

    private enum class Stage { CONNECTING, MTU, DISCOVERING, READY, CLOSED }

    private enum class Kind { READ, WRITE, DESCRIPTOR }

    /** One GATT operation: [start] returns why it couldn't start, or null once it's in flight. */
    private class Op(val kind: Kind, val start: () -> String?, val done: (Result<ByteArray>) -> Unit)

    /** A connection and, once ready, its [BleLink]. Everything in it runs on handler's thread. */
    @SuppressLint("MissingPermission") // only connected once allowed
    private inner class Connection(
        private val device: BluetoothDevice,
        private val ready: (Result<BleLink>) -> Unit,
        private val dropped: (String) -> Unit,
    ) : BluetoothGattCallback(), BleLink {
        private var gatt: BluetoothGatt? = null
        private var stage = Stage.CONNECTING
        private val queue = ArrayDeque<Op>()
        private var current: Op? = null
        private var closedByUs = false
        private val listeners = mutableMapOf<BluetoothGattCharacteristic, (ByteArray, Long) -> Unit>()
        private val connectTimeout = Runnable { fail("no answer from ${device.address} within 30 s") }
        private val mtuTimeout = Runnable { if (stage == Stage.MTU) discover() }
        private val opTimeout = Runnable { finish(Result.failure(Exception("timed out"))) }

        override val address: String = device.address.uppercase()
        @Volatile override var services: List<BleService> = emptyList()
        @Volatile override var mtu: Int = DEFAULT_MTU

        fun start() {
            connections += this
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            gatt = try {
                device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            } catch (e: SecurityException) {
                return fail("Bluetooth LE isn't allowed any more (mynx ble asks again)")
            }
            if (gatt == null) fail("Android couldn't start connecting to ${device.address}")
        }

        // Before the link is handed out.
        private fun fail(why: String) {
            if (stage == Stage.READY || stage == Stage.CLOSED) return
            shut()
            ready(Result.failure(Exception(why)))
        }

        private fun shut() {
            stage = Stage.CLOSED
            handler.removeCallbacks(connectTimeout)
            handler.removeCallbacks(mtuTimeout)
            handler.removeCallbacks(opTimeout)
            connections.remove(this)
            listeners.clear()
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (stage == Stage.CLOSED) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (stage != Stage.CONNECTING) return
                stage = Stage.MTU
                if (gatt.requestMtu(WANTED_MTU)) handler.postDelayed(mtuTimeout, MTU_WAIT_MS) else discover()
                return
            }
            val why = linkStatus(status)
            if (stage != Stage.READY) return fail("couldn't connect to ${device.address}: $why")
            // A drop: whatever waits learns it, then core.
            val waiting = listOfNotNull(current) + queue
            current = null
            queue.clear()
            shut()
            waiting.forEach { it.done(Result.failure(Exception("link lost"))) }
            dropped(why)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            if (stage == Stage.MTU) discover()
        }

        private fun discover() {
            handler.removeCallbacks(mtuTimeout)
            stage = Stage.DISCOVERING
            if (gatt?.discoverServices() != true) fail("couldn't ask ${device.address} for its services")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (stage != Stage.DISCOVERING) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("couldn't read ${device.address}'s services: ${opStatus(status)}")
            services = gatt.services.map { service ->
                BleService(
                    service.uuid.toString().lowercase(),
                    service.characteristics.map { char ->
                        BleChar(
                            service = service.uuid.toString().lowercase(),
                            uuid = char.uuid.toString().lowercase(),
                            instance = char.instanceId,
                            properties = props(char.properties),
                            descriptors = char.descriptors.map { it.uuid.toString().lowercase() },
                        )
                    },
                )
            }
            stage = Stage.READY
            handler.removeCallbacks(connectTimeout)
            ready(Result.success(this))
        }

        private fun find(char: BleChar): BluetoothGattCharacteristic? =
            gatt?.services.orEmpty()
                .filter { it.uuid.toString().equals(char.service, ignoreCase = true) }
                .flatMap { it.characteristics }
                .firstOrNull { it.uuid.toString().equals(char.uuid, ignoreCase = true) && it.instanceId == char.instance }

        private fun enqueue(kind: Kind, done: (Result<ByteArray>) -> Unit, start: () -> String?) {
            handler.post {
                if (stage != Stage.READY) {
                    // After close() nothing is called back; after a drop, say so.
                    if (!closedByUs) done(Result.failure(Exception("link lost")))
                    return@post
                }
                queue.addLast(Op(kind, start, done))
                next()
            }
        }

        private fun next() {
            while (current == null && stage == Stage.READY) {
                val op = queue.removeFirstOrNull() ?: return
                val why = try {
                    op.start()
                } catch (e: SecurityException) {
                    "Bluetooth LE isn't allowed any more (mynx ble asks again)"
                }
                if (why != null) {
                    op.done(Result.failure(Exception(why)))
                    continue
                }
                current = op
                handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
            }
        }

        private fun finish(result: Result<ByteArray>, kind: Kind? = null) {
            val op = current ?: return
            if (kind != null && op.kind != kind) return
            current = null
            handler.removeCallbacks(opTimeout)
            op.done(result)
            next()
        }

        private fun answer(status: Int, value: ByteArray, kind: Kind) = finish(
            if (status == BluetoothGatt.GATT_SUCCESS) Result.success(value) else Result.failure(Exception(opStatus(status))),
            kind,
        )

        override fun read(char: BleChar, done: (Result<ByteArray>) -> Unit) = enqueue(Kind.READ, done) {
            val target = find(char) ?: return@enqueue "no such characteristic"
            if (gatt?.readCharacteristic(target) == true) null else "Android refused to read it"
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            answer(status, value, Kind.READ)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            answer(status, characteristic.value?.copyOf() ?: ByteArray(0), Kind.READ)

        override fun write(char: BleChar, value: ByteArray, withResponse: Boolean, done: (Result<Unit>) -> Unit) =
            enqueue(Kind.WRITE, { result -> done(result.map { }) }) {
                val target = find(char) ?: return@enqueue "no such characteristic"
                val type = if (withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                writeCharacteristic(target, value, type)
            }

        @Suppress("DEPRECATION")
        private fun writeCharacteristic(target: BluetoothGattCharacteristic, value: ByteArray, type: Int): String? {
            val gatt = gatt ?: return "link lost"
            if (Build.VERSION.SDK_INT >= 33) {
                val code = gatt.writeCharacteristic(target, value, type)
                return if (code == BluetoothStatusCodes.SUCCESS) null else "Android refused to write it (${writeRefusal(code)})"
            }
            target.writeType = type
            target.value = value
            return if (gatt.writeCharacteristic(target)) null else "Android refused to write it"
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            answer(status, ByteArray(0), Kind.WRITE)

        @Suppress("DEPRECATION")
        private fun writeDescriptor(target: BluetoothGattDescriptor, value: ByteArray): String? {
            val gatt = gatt ?: return "link lost"
            if (Build.VERSION.SDK_INT >= 33) {
                val code = gatt.writeDescriptor(target, value)
                return if (code == BluetoothStatusCodes.SUCCESS) null else "Android refused to subscribe (${writeRefusal(code)})"
            }
            target.value = value
            return if (gatt.writeDescriptor(target)) null else "Android refused to subscribe"
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            answer(status, ByteArray(0), Kind.DESCRIPTOR)

        override fun subscribe(char: BleChar, value: (ByteArray, Long) -> Unit, done: (Result<Unit>) -> Unit): BleStop {
            var target: BluetoothGattCharacteristic? = null
            var stopped = false
            enqueue(Kind.DESCRIPTOR, { result ->
                if (result.isFailure) target?.let { listeners.remove(it) }
                done(result.map { })
            }) {
                val found = find(char) ?: return@enqueue "no such characteristic"
                if (stopped) return@enqueue "stopped"
                target = found
                if (gatt?.setCharacteristicNotification(found, true) != true) return@enqueue "Android refused to subscribe"
                listeners[found] = value
                val cccd = found.getDescriptor(CCCD)
                if (cccd == null) {
                    // Nothing to write: the device sends without being asked.
                    handler.post { finish(Result.success(ByteArray(0)), Kind.DESCRIPTOR) }
                    return@enqueue null
                }
                val enable = if (found.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                writeDescriptor(cccd, enable)
            }
            return BleStop {
                handler.post {
                    if (stopped) return@post
                    stopped = true
                    val subscribed = target ?: return@post
                    listeners.remove(subscribed)
                    if (stage != Stage.READY) return@post
                    // The descriptor is the device's, not this link's: another link to it (a nus port's) still listening keeps it on.
                    if (connections.any { it !== this@Connection && it.address == address && it.listensTo(subscribed.uuid) }) {
                        gatt?.setCharacteristicNotification(subscribed, false)
                        return@post
                    }
                    // Its answer goes nowhere: core has moved on.
                    val unsubscribe: () -> String? = start@{
                        gatt?.setCharacteristicNotification(subscribed, false)
                        val cccd = subscribed.getDescriptor(CCCD) ?: return@start "no descriptor"
                        writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                    }
                    queue.addLast(Op(Kind.DESCRIPTOR, unsubscribe) {})
                    next()
                }
            }
        }

        fun listensTo(uuid: UUID): Boolean = listeners.keys.any { it.uuid == uuid }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            listeners[characteristic]?.invoke(value, System.currentTimeMillis())
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Android reuses the array for the next value.
            val value = characteristic.value?.copyOf() ?: return
            listeners[characteristic]?.invoke(value, System.currentTimeMillis())
        }

        override fun close() {
            handler.post {
                if (stage == Stage.CLOSED) return@post
                closedByUs = true
                current = null
                queue.clear()
                shut()
            }
        }
    }
}

private fun props(bits: Int): Set<BleProp> = buildSet {
    if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) add(BleProp.READ)
    if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add(BleProp.WRITE)
    if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add(BleProp.WRITE_NO_RESPONSE)
    if (bits and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add(BleProp.NOTIFY)
    if (bits and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add(BleProp.INDICATE)
}

private fun scanFailure(code: Int): String = when (code) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Android says a scan is already running"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
        "Android couldn't register the scan (too many scans lately? turning Bluetooth off and on helps)"
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android's Bluetooth stack failed to scan"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "the phone can't scan this way"
    // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES and _SCANNING_TOO_FREQUENTLY, public only from Android 13.
    5 -> "the Bluetooth chip has no room for another scan"
    6 -> "Android refused: too many scans within 30 s"
    else -> "Android stopped the scan (error $code)"
}

// The reason a link closed or didn't open: HCI codes, and Android's catch-all 133.
// A C3 unplugged mid-notify came back as 0, not 8, on the Pixel 10.
private fun linkStatus(status: Int): String = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "the device went away or closed the link"
    0x08 -> "the device went out of range (no answer)"
    0x13 -> "the device disconnected"
    0x16 -> "Android closed the link"
    0x22 -> "the device stopped answering"
    0x3E -> "couldn't establish the link (out of range, or not advertising)"
    0x85 -> "Android's Bluetooth stack failed (error 133: the device may be out of range, busy or not connectable)"
    else -> "link lost (status $status)"
}

// The reason a GATT operation failed, as the device or Android said.
private fun opStatus(status: Int): String = when (status) {
    BluetoothGatt.GATT_READ_NOT_PERMITTED -> "not permitted (the device doesn't allow reading it)"
    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "not permitted (the device doesn't allow writing it)"
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "the device needs pairing first"
    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "the device doesn't support that request"
    BluetoothGatt.GATT_INVALID_OFFSET -> "invalid offset"
    0x08 -> "the device needs authorization"
    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "the device refused the value's length"
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "the device needs an encrypted link (pair it first)"
    BluetoothGatt.GATT_CONNECTION_CONGESTED -> "the link is congested"
    0x85 -> "Android's Bluetooth stack failed (error 133)"
    else -> "failed (status $status)"
}

@TargetApi(33) // only called there
private fun writeRefusal(code: Int): String = when (code) {
    BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "busy"
    BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED -> "not allowed"
    BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> "no permission"
    BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> "Bluetooth isn't ready"
    else -> "error $code"
}
