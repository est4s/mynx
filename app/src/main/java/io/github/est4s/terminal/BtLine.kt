package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import io.github.est4s.terminal.core.FlowControl
import io.github.est4s.terminal.core.LineSettings
import io.github.est4s.terminal.core.SerialLine
import io.github.est4s.terminal.core.SerialServer
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

// The Serial Port Profile's service class, which every SPP device offers.
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
// CTS, DSR and CD as RFC 2217's modem-state bits: on while linked.
private const val LINKED_MODEM_STATE = 0x10 or 0x20 or 0x80
// The wait after the first failed link; it doubles with each one after.
private const val RETRY_FIRST_MS = 2000L
private const val RETRY_MAX_MS = 30_000L

/**
 * A Bluetooth serial port (SPP) to the paired device at [address], as the
 * RFC 2217 server drives it. Linked only while a client is connected, as
 * Linux's `rfcomm bind`: [clientArrived] opens the RFCOMM link, [clientLeft]
 * closes it. A link that drops ends the client: a URL client sees the
 * port close; the pty helper connects again while its program holds the
 * port, so after a failed link new ones are refused at once for a while,
 * longer after each failure, rather than paging a board that's off every
 * second. Baud, DTR, RTS and break mean nothing over SPP.
 */
class BtLine(
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val name: String,
) : SerialLine {
    /** Set before the server starts. */
    lateinit var server: SerialServer
    @Volatile private var socket: BluetoothSocket? = null
    // The one being linked, and whether its client left meanwhile: the server can end a client mid-link.
    @Volatile private var connecting: BluetoothSocket? = null
    @Volatile private var left = false
    @Volatile private var failures = 0
    @Volatile private var retryAt = 0L
    val linked: Boolean get() = socket != null
    /** Why the last link failed or dropped; null once one opens. */
    @Volatile var error: String? = null
        private set

    // The bridge serves ports only with the permission.
    @SuppressLint("MissingPermission")
    override fun clientArrived() {
        left = false
        if (SystemClock.elapsedRealtime() < retryAt) throw IOException(error ?: "waiting to link again")
        if (!adapter.isEnabled) throw failed("Bluetooth is off")
        // No cancelDiscovery(): it needs the scan permission, and nothing here scans.
        val linked = try {
            link(adapter.getRemoteDevice(address))
        } catch (e: SecurityException) {
            throw failed("Bluetooth isn't allowed any more (mynx bt asks again)")
        }
        // Taken before looking at left: a clientLeft() between the two then still closes it.
        socket = linked
        if (left) {
            unlink()
            throw IOException("the client left while linking")
        }
        error = null
        failures = 0
        thread(name = "bt-read-$name", isDaemon = true) { readLoop(linked) }
    }

    @SuppressLint("MissingPermission")
    private fun link(device: BluetoothDevice): BluetoothSocket = try {
        connect(device.createRfcommSocketToServiceRecord(SPP_UUID))
    } catch (e: IOException) {
        // Let go while linking: no error to show.
        if (left) throw e
        // Some modules (old HC-05s) only take an unauthenticated link.
        try {
            connect(device.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
        } catch (e2: IOException) {
            if (left) throw e2
            throw failed("couldn't link to $address: ${e.message ?: "no answer"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(socket: BluetoothSocket): BluetoothSocket {
        connecting = socket
        try {
            if (left) throw IOException("the client left")
            socket.connect()
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        } finally {
            connecting = null
        }
        return socket
    }

    private fun failed(why: String): IOException {
        error = why
        retryAt = SystemClock.elapsedRealtime() + minOf(RETRY_FIRST_MS shl minOf(failures, 4), RETRY_MAX_MS)
        failures++
        return IOException(why)
    }

    private fun readLoop(linked: BluetoothSocket) {
        val buffer = ByteArray(1024)
        val input = runCatching { linked.inputStream }.getOrNull()
        while (input != null) {
            val n = try {
                input.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (n < 0) break
            if (n > 0) server.fromLine(buffer, n)
        }
        // Still the current link: it dropped rather than being let go.
        if (socket === linked) {
            error = "the link dropped"
            unlink()
            server.endClient()
        }
    }

    override fun clientLeft() {
        left = true
        // Ends a connect() still waiting.
        connecting?.let { runCatching { it.close() } }
        unlink()
    }

    private fun unlink() {
        val linked = socket ?: return
        socket = null
        runCatching { linked.close() }
    }

    override fun write(data: ByteArray) {
        val linked = socket ?: return
        try {
            linked.outputStream.write(data)
        } catch (_: IOException) {
            // The read loop sees the drop too, and ends the client.
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
