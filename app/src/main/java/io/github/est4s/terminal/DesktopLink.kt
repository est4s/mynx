package io.github.est4s.terminal

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.view.Surface
import io.github.est4s.terminal.core.DesktopClipboard
import io.github.est4s.terminal.core.DesktopGeometry
import io.github.est4s.terminal.core.DesktopOther
import io.github.est4s.terminal.core.DesktopPointer
import io.github.est4s.terminal.core.DesktopScreens
import io.github.est4s.terminal.core.DesktopSurface
import io.github.est4s.terminal.core.PixelRect
import io.github.est4s.terminal.core.ScreenDesktopMode
import io.github.est4s.terminal.core.ScreenSide
import io.github.est4s.terminal.core.X11Events
import io.github.est4s.terminal.core.X11Input
import io.github.est4s.terminal.core.X11ServerEvent
import io.github.est4s.terminal.core.X11_GET_X_CONNECTION
import io.github.est4s.terminal.core.X11_INTERFACE
import io.github.est4s.terminal.core.X11_SECOND_WINDOW_CHANGED
import io.github.est4s.terminal.core.X11_START_ACTION
import io.github.est4s.terminal.core.X11_WINDOW_CHANGED
import io.github.est4s.terminal.core.desktopScale
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val LOG_LINES = 200

/** What the desktop's view needs from the X server: [DesktopLink], or a stand-in for the screenshots. */
interface DesktopScreenLink {
    /** Whether the X server has taken our connection, so it's running and gets what's sent. */
    val connected: Boolean

    /** Called on the main thread when [connected] changes or [log] grows. */
    var listener: (() -> Unit)?

    /** What happened, newest last: connections, failures. */
    fun log(): String

    fun send(events: List<X11Input>)

    /** The view's [surface], [width] × [height] X pixels at [framerate]; [fresh]: a new one (created since the last call). */
    fun surface(surface: Surface, width: Int, height: Int, framerate: Int, fresh: Boolean)

    /** The view's surface is going: the server must stop drawing into it first. */
    fun surfaceGone()

    /** Android's clipboard as text (null: none, or not readable), read while the desktop shows. */
    fun androidClipboard(text: String?)

    /** The X server's one pointer, shared with the other phone's gestures (the extended screen); null: the view's own. */
    val pointer: DesktopPointer? get() = null

    /** This phone's part of the X screen, once the view gave its size; null: all of it. */
    val hostMonitor: PixelRect? get() = null

    /** [watcher] hears the pointer move from elsewhere (the other phone), and the monitors change; null stops. */
    fun watch(watcher: (() -> Unit)?) {}
}

/**
 * mynx's side of Termux:X11's X server (the facts of its interface are
 * core's X11Input.kt). The server, a process of its own, broadcasts its
 * binder to this app every second until a connection is taken; we take
 * it as soon as it comes, whatever the view is doing, so a server that
 * was running when the app came back is found again. Through the binder
 * the view's surface is handed over (`windowChanged`); through the socket
 * it gives (`getXConnection`) the input is written, and what the server
 * sends back is read: X's clipboard goes to Android's, and a paste in X
 * of what Android had is answered (core's DesktopClipboard); the rest
 * (heartbeats, its state) is dropped. Lives in the service; main thread.
 */
class DesktopLink(private val context: Context, private val main: Handler) : DesktopScreenLink {
    override var listener: (() -> Unit)? = null
    private val lines = ArrayDeque<String>()
    private var receiver: BroadcastReceiver? = null
    private var server: IBinder? = null
    private var connection: ParcelFileDescriptor? = null
    private var events: FileOutputStream? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var framerate = 0
    private val screen = DesktopSurface()
    private val clipboard = DesktopClipboard()
    // The other phone's surface (an encoder's input), while the desktop is sent there.
    private var second: Surface? = null
    private var hostKnown = false
    private var watcher: (() -> Unit)? = null

    /** Where the desktop is on each phone (the extended screen); [other] changes it. */
    val screens = DesktopScreens()

    override val pointer = DesktopPointer().apply { listener = { pointerMoved() } }

    /** The other phone's side hears the pointer and the monitors change: [DesktopCaster]. */
    var otherWatcher: (() -> Unit)? = null

    /** Called when a connection to the X server ends (it stopped). */
    var onGone: (() -> Unit)? = null

    override val hostMonitor: PixelRect? get() = if (hostKnown) screens.hostRect else null

    override fun watch(watcher: (() -> Unit)?) {
        this.watcher = watcher
    }

    private fun pointerMoved() {
        watcher?.invoke()
        otherWatcher?.invoke()
    }

    override val connected: Boolean get() = events != null

    override fun log(): String = lines.joinToString("\n")

    fun note(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        lines.addLast("$time $line")
        while (lines.size > LOG_LINES) lines.removeFirst()
        listener?.invoke()
    }

    /** Listens for the server's broadcast; until [stop]. */
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val binder = intent.getBundleExtra(null)?.getBinder(null)
                if (binder == null) {
                    note("a start broadcast without the server's binder")
                    return
                }
                if (binder == server && connected) return
                connect(binder)
            }
        }
        // Only this app's own processes (the X server runs as its uid) can reach it.
        val filter = IntentFilter(X11_START_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, null, main, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(r, filter, null, main)
        }
        receiver = r
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        disconnect()
    }

    private fun connect(binder: IBinder) {
        disconnect()
        try {
            server = binder
            binder.linkToDeath({ main.post { if (server === binder) gone("the X server stopped") } }, 0)
            val pfd = call(binder, X11_GET_X_CONNECTION, {}) { reply ->
                if (reply.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(reply) else null
            }
            if (pfd == null) {
                server = null
                note("the X server gave no connection")
                return
            }
            connection = pfd
            events = FileOutputStream(pfd.fileDescriptor)
            read(binder, pfd)
            screen.reconnected()
            send(clipboard.connected())
            note("connected to the X server")
            surface?.let { handOver(it, fresh = true) }
            // A server from before (or restarted) knows nothing of the other phone's part, or still has it.
            if (hostKnown) sendLayout()
            second?.let { windowChanged(it, X11_SECOND_WINDOW_CHANGED) }
        } catch (e: Exception) {
            disconnect()
            note("connecting to the X server failed: ${e.stackTraceToString()}")
        }
    }

    // Always read: unread, the socket would fill and the server would block writing to it.
    // The stream isn't closed: the descriptor is the ParcelFileDescriptor's.
    private fun read(binder: IBinder, pfd: ParcelFileDescriptor) {
        thread(name = "x11-events", isDaemon = true) {
            val events = X11Events(FileInputStream(pfd.fileDescriptor))
            runCatching {
                while (true) {
                    val event = events.next() ?: break
                    if (event is X11ServerEvent.Other) continue
                    main.post { if (server === binder) serverSent(event) }
                }
            }
            main.post { if (server === binder) gone("the X server closed the connection") }
        }
    }

    private fun serverSent(event: X11ServerEvent) {
        when (event) {
            is X11ServerEvent.Clipboard -> clipboard.fromX(event.text)?.let { text ->
                runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("desktop", text))
                }.onFailure { note("copying X's clipboard to Android's failed: $it") }
            }
            is X11ServerEvent.ClipboardTooBig -> note("X's clipboard (${event.bytes} bytes) is too big for Android's")
            X11ServerEvent.ClipboardRequest -> send(clipboard.request())
            is X11ServerEvent.MonitorsShown -> {
                screens.shown(event)
                monitorsChanged()
            }
            is X11ServerEvent.Other -> Unit
        }
    }

    override fun androidClipboard(text: String?) {
        if (connected) send(clipboard.fromAndroid(text))
    }

    private fun gone(why: String) {
        disconnect()
        note(why)
    }

    private fun disconnect() {
        val wasConnected = connected
        runCatching { connection?.close() }
        connection = null
        events = null
        server = null
        if (wasConnected) {
            listener?.invoke()
            onGone?.invoke()
        }
    }

    private fun <T> call(binder: IBinder, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(X11_INTERFACE)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // The server waits until its renderer has let go of the old surface, so this returns after.
    private fun windowChanged(s: Surface?, code: Int = X11_WINDOW_CHANGED) {
        val binder = server ?: return
        try {
            call(binder, code, { data ->
                if (s != null) {
                    data.writeInt(1)
                    s.writeToParcel(data, 0)
                } else {
                    data.writeInt(0)
                }
            }) {}
        } catch (e: Exception) {
            note("handing the screen to the X server failed: $e")
        }
    }

    override fun surface(surface: Surface, width: Int, height: Int, framerate: Int, fresh: Boolean) {
        this.surface = surface
        this.width = width
        this.height = height
        this.framerate = framerate
        hostKnown = true
        val moved = screens.host(width, height)
        if (connected) handOver(surface, fresh)
        if (moved) {
            if (screens.monitors != null) sendLayout()
            monitorsChanged()
        }
    }

    private fun handOver(s: Surface, fresh: Boolean) {
        windowChanged(s)
        send(screen.handover(width, height, framerate, fresh))
    }

    /**
     * The other phone's part of the desktop ([to], null: none), on its
     * [side], as [mode] says; laid out at once. Before this phone's view
     * ever gave its size, its monitor is the phone's whole screen.
     */
    fun other(to: DesktopOther?, side: ScreenSide, mode: ScreenDesktopMode) {
        if (!hostKnown && to != null) {
            val metrics = context.resources.displayMetrics
            val g = DesktopGeometry(metrics.widthPixels, metrics.heightPixels, desktopScale(metrics.density))
            screens.host(g.width, g.height)
            hostKnown = true
        }
        if (!screens.other(to, side, mode)) return
        note(screens.monitors?.let { "the desktop's monitors: this phone ${it.host}, the other ${it.screen} (${it.mode.word}, ${side.word})" } ?: "the desktop is this phone's alone")
        sendLayout()
        monitorsChanged()
    }

    private fun sendLayout() {
        if (connected) send(listOf(screens.event()))
    }

    // The monitors moved: the pointer is kept on them, and both phones' gestures follow.
    private fun monitorsChanged() {
        pointer.areas = screens.areas
        watcher?.invoke()
        otherWatcher?.invoke()
    }

    /** The other phone's [surface] (its encoder's input), for the X server to draw its part into; null takes it away. */
    fun secondSurface(surface: Surface?) {
        if (surface === second) return
        second = surface
        if (!connected) return note("the other phone's surface waits for the X server")
        windowChanged(surface, X11_SECOND_WINDOW_CHANGED)
        note(if (surface != null) "the other phone's surface handed to the X server" else "the other phone's surface taken from the X server")
    }

    override fun surfaceGone() {
        surface = null
        if (connected) windowChanged(null)
    }

    override fun send(events: List<X11Input>) {
        val out = this.events ?: return
        try {
            for (e in events) out.write(e.encode())
        } catch (e: Exception) {
            gone("sending to the X server failed: $e")
        }
    }
}
