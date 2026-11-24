package io.github.est4s.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.view.Surface
import io.github.est4s.terminal.core.RootfsInstaller
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SPIKE (desktop-spike, throwaway): Termux:X11's X server as a separate
 * program. Its code (GPL-3.0) is built by scripts/build-x11.sh into
 * libXlorie.so and an asset jar that app_process runs in a process of its
 * own; nothing of it is in this app's classes. The server finds us by a
 * broadcast carrying its binder; we talk to it through that binder (by hand,
 * below: its interface's descriptor and transaction numbers) and a socket
 * of 24-byte events it hands us.
 */
object X11Spike {
    private const val ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START"
    private const val DESCRIPTOR = "com.termux.x11.ICmdEntryInterface"
    private const val GET_X_CONNECTION = IBinder.FIRST_CALL_TRANSACTION
    private const val WINDOW_CHANGED = IBinder.FIRST_CALL_TRANSACTION + 3

    // The server's event types (an enum in its lorie.h, in this order).
    private const val EVENT_SCREEN_SIZE = 4
    private const val EVENT_MOUSE = 6
    private const val EVENT_KEY = 7
    private const val EVENT_UNICODE = 10
    private const val EVENT_SIZE = 24

    const val DISPLAY = ":0"

    interface Listener {
        fun changed()
    }

    private val main = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    var listener: Listener? = null
    private var process: Process? = null
    private var server: IBinder? = null
    private var events: FileOutputStream? = null
    private var surface: Surface? = null
    private var receiver: BroadcastReceiver? = null
    var status = "not started"
        private set
    val connected get() = events != null

    fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 300) logLines.removeFirst()
        }
        main.post { listener?.changed() }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun setStatus(s: String) {
        status = s
        log("mynx: $s")
    }

    val running get() = process?.isAlive == true

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        try {
            listen(app)
            val rootfs = RootfsInstaller(app.filesDir).rootfs
            val tmp = File(rootfs, "tmp").apply { mkdirs() }
            val xkb = File(rootfs, "usr/share/X11/xkb")
            if (!xkb.isDirectory) {
                setStatus("no keyboard data: run `apt install xkb-data` in a tab, then tap Start")
                return
            }
            killStale(tmp)
            val jar = File(app.filesDir, "x11/termux-x11.jar")
            jar.parentFile!!.mkdirs()
            jar.delete()
            app.assets.open("x11/termux-x11.jar").use { input -> jar.outputStream().use { input.copyTo(it) } }
            jar.setReadOnly()
            val lib = File(app.applicationInfo.nativeLibraryDir, "libXlorie.so")
            val pb = ProcessBuilder(
                "/system/bin/app_process", "-Xnoimage-dex2oat", "/", "--nice-name=mynx-x11",
                "com.termux.x11.CmdEntryPoint", DISPLAY, "-ac",
            ).directory(tmp).redirectErrorStream(true)
            pb.environment().apply {
                remove("LD_PRELOAD"); remove("LD_LIBRARY_PATH")
                put("CLASSPATH", jar.absolutePath)
                put("TMPDIR", tmp.absolutePath)
                put("XKB_CONFIG_ROOT", xkb.absolutePath)
                put("MYNX_X11_PACKAGE", app.packageName)
                put("MYNX_X11_LIB", lib.absolutePath)
            }
            setStatus("starting: ${pb.command().joinToString(" ")}")
            val p = pb.start()
            process = p
            Thread {
                p.inputStream.bufferedReader().forEachLine { log(it) }
                val code = p.waitFor()
                main.post {
                    if (process === p) {
                        process = null
                        disconnect()
                        setStatus("X server exited with code $code")
                    }
                }
            }.start()
        } catch (e: Throwable) {
            setStatus("start failed: ${e.stackTraceToString()}")
        }
    }

    fun stop() {
        process?.let { setStatus("stopping"); it.destroy() }
    }

    // A server left over from an earlier run of the app holds display 0.
    private fun killStale(tmp: File) {
        val lock = File(tmp, ".X${DISPLAY.drop(1)}-lock")
        val pid = runCatching { lock.readText().trim().toInt() }.getOrNull() ?: return
        if (File("/proc/$pid").exists()) {
            log("mynx: stopping a leftover X server (pid $pid)")
            runCatching { Os.kill(pid, 15) }
            repeat(20) { if (File("/proc/$pid").exists()) Thread.sleep(100) }
        }
    }

    private fun listen(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val binder = intent.getBundleExtra(null)?.getBinder(null) ?: return log("mynx: a start broadcast without a binder")
                if (binder == server && connected) return
                connect(binder)
            }
        }
        val filter = IntentFilter(ACTION_START)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, filter)
        receiver = r
    }

    private fun connect(binder: IBinder) {
        try {
            disconnect()
            server = binder
            binder.linkToDeath({ main.post { if (server === binder) { disconnect(); setStatus("the X server's binder died") } } }, 0)
            val pfd = transact(binder, GET_X_CONNECTION, {}) { reply ->
                if (reply.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(reply) else null
            } ?: return setStatus("the X server gave no connection")
            events = FileOutputStream(pfd.fileDescriptor)
            // Drain what the server sends (heartbeats, clipboard): unread, it would block on a full socket.
            Thread {
                val buf = ByteArray(4096)
                runCatching { FileInputStream(pfd.fileDescriptor).use { while (it.read(buf) >= 0) Unit } }
                main.post { if (server === binder) { disconnect(); setStatus("the X server closed the connection") } }
            }.start()
            setStatus("connected to the X server")
            surface?.let { windowChanged(it) }
            main.post { listener?.changed() }
        } catch (e: Throwable) {
            setStatus("connect failed: ${e.stackTraceToString()}")
        }
    }

    private fun disconnect() {
        runCatching { events?.close() }
        events = null
        server = null
    }

    private fun <T> transact(binder: IBinder, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun windowChanged(s: Surface?) {
        val binder = server ?: return
        try {
            transact(binder, WINDOW_CHANGED, { data ->
                if (s != null) { data.writeInt(1); s.writeToParcel(data, 0) } else data.writeInt(0)
            }) {}
        } catch (e: Throwable) {
            log("mynx: windowChanged failed: ${e.stackTraceToString()}")
        }
    }

    /** The view's surface (null when it's gone), and the X screen's size for it. */
    fun surface(s: Surface?, width: Int, height: Int, framerate: Int) {
        surface = s
        if (!connected) return
        if (s != null) screenSize(width, height, framerate)
        windowChanged(s)
    }

    private fun send(fill: (ByteBuffer) -> Unit) {
        val out = events ?: return
        val b = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fill(b)
        try {
            out.write(b.array())
        } catch (e: Throwable) {
            log("mynx: sending an event failed: $e")
        }
    }

    private fun screenSize(width: Int, height: Int, framerate: Int) {
        log("mynx: screen ${width}x$height at $framerate Hz")
        send { it.put(0, EVENT_SCREEN_SIZE.toByte()).putShort(2, width.toShort()).putShort(4, height.toShort()).putShort(6, framerate.toShort()) }
    }

    /** [button] 0 moves the pointer to x, y; 1-3 press or release that button. */
    fun mouse(x: Float, y: Float, button: Int, down: Boolean) = send {
        it.put(0, EVENT_MOUSE.toByte()).putFloat(4, x).putFloat(8, y).put(12, button.toByte()).put(13, if (down) 1 else 0).put(14, 0)
    }

    /** A Linux key code (evdev), pressed or released. */
    fun key(linuxCode: Int, down: Boolean) = send {
        it.put(0, EVENT_KEY.toByte()).putShort(2, (linuxCode + 8).toShort()).put(4, if (down) 1 else 0)
    }

    /** A character typed (pressed and released by the server). */
    fun unicode(codePoint: Int) = send { it.put(0, EVENT_UNICODE.toByte()).putInt(4, codePoint) }
}
