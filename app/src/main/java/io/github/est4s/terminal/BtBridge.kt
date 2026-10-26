package io.github.est4s.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.est4s.terminal.core.BleHost
import io.github.est4s.terminal.core.BleUartLine
import io.github.est4s.terminal.core.BluetoothHost
import io.github.est4s.terminal.core.BtBinding
import io.github.est4s.terminal.core.BtDevice
import io.github.est4s.terminal.core.BtPortState
import io.github.est4s.terminal.core.BtType
import io.github.est4s.terminal.core.FIRST_BLUETOOTH_TCP_PORT
import io.github.est4s.terminal.core.FIRST_NUS_TCP_PORT
import io.github.est4s.terminal.core.isNusName
import io.github.est4s.terminal.core.loadBluetooth
import java.io.File

/**
 * The Bluetooth serial bridge: serves each port `bluetooth.conf` binds
 * ([conf]) as [ServedPort]s, `rfcommN` over a [BtLine] and `nusN` over
 * core's [BleUartLine] through [ble], each linking to its device only
 * while a client uses the port. Ports are served while the setting
 * ([settingOn]), the permission and Bluetooth itself allow; [changed] gets
 * the served ports' names whenever they change. Call from [helpers]'
 * handler's (the main) thread.
 */
class BtBridge(
    private val context: Context,
    private val helpers: PortHelpers,
    private val conf: File,
    private val ble: BleHost,
    private val settingOn: () -> Boolean,
    private val askPermissions: (Array<String>, (Boolean) -> Unit) -> Boolean,
    private val showSettings: () -> String?,
    private val changed: (List<String>) -> Unit,
) : BluetoothHost {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val served = linkedMapOf<String, Port>()
    private var started = false

    private class Port(
        val binding: BtBinding,
        val served: ServedPort,
        val linked: () -> Boolean,
        val error: () -> String?,
    )

    // Bluetooth turned on or off: serve or stop the ports.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, helpers.handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, helpers.handler)
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        served.keys.toList().forEach(::close)
        changed(emptyList())
    }

    /** Serves what `bluetooth.conf` binds now, if allowed; cheap enough to call whenever the app comes on screen. */
    fun refresh() {
        if (!started) return
        val wanted = if (settingOn() && available && allowed && enabled) {
            loadBluetooth(conf).bindings.associateBy { it.port }
        } else {
            emptyMap()
        }
        val before = served.keys.toSet()
        served.filter { (name, port) -> wanted[name] != port.binding }.keys.forEach(::close)
        for ((name, binding) in wanted) {
            if (name in served) continue
            runCatching { if (isNusName(name)) serveNus(binding) else serve(binding) }
        }
        if (served.keys != before) changed(served.keys.toList())
    }

    private fun takenTcpPorts() = served.values.map { it.served.server.port }.toSet()

    private fun serve(binding: BtBinding) {
        val adapter = adapter ?: return
        val line = BtLine(adapter, binding.address, binding.port)
        val server = newSerialServer(line, takenTcpPorts(), FIRST_BLUETOOTH_TCP_PORT)
        line.server = server
        val port = ServedPort(binding.port, server, helpers)
        served[binding.port] = Port(binding, port, { line.linked }, { line.error })
        port.start()
    }

    // A BLE UART: Android shares the device's connection with mynx ble's requests, but this link is the port's own.
    private fun serveNus(binding: BtBinding) {
        val line = BleUartLine(ble, binding.address, binding.random)
        val server = newSerialServer(line, takenTcpPorts(), FIRST_NUS_TCP_PORT)
        line.received = { server.fromLine(it, it.size) }
        line.dropped = { server.endClient() }
        val port = ServedPort(binding.port, server, helpers)
        served[binding.port] = Port(binding, port, { line.linked }, { line.error })
        port.start()
    }

    private fun close(name: String) {
        served.remove(name)?.served?.close()
    }

    override val available: Boolean get() = adapter != null

    override val allowed: Boolean
        get() = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override val enabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    override fun bonded(): List<BtDevice> = bondedDevices(adapter)

    override fun ports(): Map<String, BtPortState> = served.mapValues { (_, port) ->
        val info = port.served.info()
        BtPortState(
            tcpPort = info.tcpPort,
            linked = port.linked(),
            inUse = info.inUse,
            waiting = info.waiting,
            error = port.error(),
        )
    }

    override fun askPermission(done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) return done(null)
        val asked = askPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) { granted ->
            if (granted) {
                refresh()
                done(null)
            } else {
                done("the permission was refused: allow \"Nearby devices\" in Android's settings for mynx (Apps → mynx → Permissions)")
            }
        }
        if (!asked) done("Android asks for it with the app on screen: open mynx and try again")
    }

    override fun openSettings(): String? = showSettings()

    override fun bindingsChanged() = refresh()
}

/** The paired devices, by name; for `mynx bt` and `mynx ble`. */
@SuppressLint("MissingPermission") // core asks only once allowed
fun bondedDevices(adapter: BluetoothAdapter?): List<BtDevice> =
    runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        .map { device ->
            val type = when (runCatching { device.type }.getOrNull()) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtType.CLASSIC
                BluetoothDevice.DEVICE_TYPE_LE -> BtType.LE
                BluetoothDevice.DEVICE_TYPE_DUAL -> BtType.DUAL
                else -> BtType.UNKNOWN
            }
            BtDevice(runCatching { device.name }.getOrNull(), device.address, type)
        }
        .sortedBy { it.name ?: it.address }
