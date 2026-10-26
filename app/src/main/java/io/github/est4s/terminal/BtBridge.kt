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
import io.github.est4s.terminal.core.BluetoothHost
import io.github.est4s.terminal.core.BtDevice
import io.github.est4s.terminal.core.BtPortState
import io.github.est4s.terminal.core.BtType
import io.github.est4s.terminal.core.FIRST_BLUETOOTH_TCP_PORT
import io.github.est4s.terminal.core.loadBluetooth
import java.io.File

/**
 * The Bluetooth serial bridge: serves each port `bluetooth.conf` binds
 * ([conf]) as [ServedPort]s over a [BtLine], which links to its device
 * only while a client uses the port. Ports are served while the setting
 * ([settingOn]), the permission and Bluetooth itself allow; [changed] gets
 * the served ports' names whenever they change. Call from [helpers]'
 * handler's (the main) thread.
 */
class BtBridge(
    private val context: Context,
    private val helpers: PortHelpers,
    private val conf: File,
    private val settingOn: () -> Boolean,
    private val askPermissions: (Array<String>, (Boolean) -> Unit) -> Boolean,
    private val showSettings: () -> String?,
    private val changed: (List<String>) -> Unit,
) : BluetoothHost {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val served = linkedMapOf<String, Port>()
    private var started = false

    private class Port(val address: String, val line: BtLine, val served: ServedPort)

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
            loadBluetooth(conf).bindings.associate { it.port to it.address }
        } else {
            emptyMap()
        }
        val before = served.keys.toSet()
        served.filter { (name, port) -> wanted[name] != port.address }.keys.forEach(::close)
        for ((name, address) in wanted) {
            if (name in served) continue
            runCatching { serve(name, address) }
        }
        if (served.keys != before) changed(served.keys.toList())
    }

    private fun serve(name: String, address: String) {
        val adapter = adapter ?: return
        val line = BtLine(adapter, address, name)
        val server = newSerialServer(line, served.values.map { it.served.server.port }.toSet(), FIRST_BLUETOOTH_TCP_PORT)
        line.server = server
        val port = ServedPort(name, server, helpers)
        served[name] = Port(address, line, port)
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

    @SuppressLint("MissingPermission") // core asks only once allowed
    override fun bonded(): List<BtDevice> = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
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

    override fun ports(): Map<String, BtPortState> = served.mapValues { (_, port) ->
        val info = port.served.info()
        BtPortState(
            tcpPort = info.tcpPort,
            linked = port.line.linked,
            inUse = info.inUse,
            waiting = info.waiting,
            error = port.line.error,
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
