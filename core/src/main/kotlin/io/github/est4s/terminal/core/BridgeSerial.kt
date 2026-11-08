package io.github.est4s.terminal.core

import java.util.Base64

/** How long `serial-open` waits for the port's line to come up (a Bluetooth link takes seconds). */
const val SERIAL_OPEN_SECONDS = 30
private const val SERIAL_QUICK_SECONDS = 10
// bt-list asks for the permission unless told not to; a page never gets that dialog.
private const val BT_NO_ASK = "--no-ask"
private const val DEFAULT_BAUD = 115200
private const val NAMES_THEM = "(mynx.serial.list() names them)"

private val PARITY_WORDS = mapOf(
    "none" to Parity.NONE, "even" to Parity.EVEN, "odd" to Parity.ODD, "mark" to Parity.MARK, "space" to Parity.SPACE,
)
private val STOP_WORDS = mapOf("1" to StopBits.ONE, "1.5" to StopBits.ONE_POINT_FIVE, "2" to StopBits.TWO)

/** A port as `serial-list` gives it, with what opening it needs ([tcpPort], [error]). */
private data class BridgePort(
    val name: String,
    val kind: String,
    val path: String,
    val url: String?,
    val tcpPort: Int?,
    val inUse: Boolean,
    val device: String,
    val error: String?,
) {
    fun json() = obj(
        "name" to json(name), "kind" to json(kind), "path" to json(path), "url" to (url?.let(::json) ?: "null"),
        "in_use" to inUse.toString(), "device" to json(device),
    )
}

private class SerialRefused(message: String) : Exception(message)

/** A sink for the requests the serial calls run themselves: the answer back on the main thread. */
private class ListSink(private val host: BridgeHost, private val done: (Map<*, *>) -> Unit) : ReplySink {
    override fun waiting(seconds: Int?) {}
    override fun line(json: String) {}
    override fun answer(json: String) {
        val parsed = runCatching { parseJson(json) as? Map<*, *> }.getOrNull() ?: emptyMap<String, Any?>()
        host.schedule(0) { done(parsed) }
    }
    override fun discard() {}
}

/**
 * Part D's serial calls for one session of [app] (see THE SERIAL CALLS
 * in `Bridge.kt`): the bridge is an RFC 2217 client ([Rfc2217Client]) of
 * the port's own server, through [BridgeHost.connectSerial], so a port
 * has one user at a time as from Debian. The ports come from [calls]'
 * `usb-list` and `bt-list --no-ask`. Everything runs on the main thread:
 * the host's callbacks come back through [BridgeHost.schedule].
 */
@Suppress("UNUSED_PARAMETER") // The contract's shape: ports aren't per app (the `serial` permission is).
fun bridgeSerial(app: WebApp, calls: MynxRequests, host: BridgeHost): BridgeSerial = SerialPorts(calls, host)

private class SerialPorts(private val calls: MynxRequests, private val host: BridgeHost) : BridgeSerial {
    private var handles = 0L
    private val opening = mutableSetOf<OpenPort>()
    private val open = linkedMapOf<Long, OpenPort>()
    private var closed = false

    override fun calls(): Map<String, Later> = mapOf(
        "serial-list" to guarded(SERIAL_QUICK_SECONDS) { _, reply ->
            ports { result -> result.fold({ list -> reply.ok("ports" to array(list.map { it.json() })) }, { reply.refuse(it.message!!) }) }
        },
        "serial-open" to guarded(null) { args, reply -> open(args, reply) },
        "serial-write" to guarded(SERIAL_QUICK_SECONDS) { args, reply ->
            val port = portOf(args.getOrNull(0))
            val data = try {
                Base64.getDecoder().decode(args.getOrNull(1).orEmpty())
            } catch (e: IllegalArgumentException) {
                throw SerialRefused("serial-write's data isn't base64")
            }
            port.write(data)
            reply.ok("bytes" to data.size.toString())
        },
        "serial-set" to guarded(SERIAL_QUICK_SECONDS) { args, reply ->
            val port = portOf(args.getOrNull(0))
            var settings = port.client.settings
            var dtr: Boolean? = null
            var rts: Boolean? = null
            for (option in args.drop(1)) {
                when (option.substringBefore('=')) {
                    "dtr" -> dtr = line(option)
                    "rts" -> rts = line(option)
                    else -> settings = setting(settings, option, "serial-set")
                }
            }
            port.client.configure(settings)
            port.client.setLines(dtr, rts)
            reply.ok()
        },
    )

    override fun close() {
        closed = true
        (opening + open.values).toList().forEach { it.shut() }
        opening.clear()
        open.clear()
    }

    private fun guarded(seconds: Int?, start: (List<String>, PendingReply) -> Unit) = Later(seconds) { raw, reply ->
        try {
            start(raw.map { it.trim() }.filter { it.isNotEmpty() }, reply)
        } catch (e: SerialRefused) {
            reply.refuse(e.message!!)
        } catch (e: Exception) {
            reply.refuse("serial ports failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun portOf(handle: String?): OpenPort =
        handle?.toLongOrNull()?.let { open[it] } ?: throw SerialRefused("no open port ${handle.orEmpty()}")

    private fun line(option: String): Boolean = when (option.substringAfter('=')) {
        "1" -> true
        "0" -> false
        else -> throw SerialRefused("${option.substringBefore('=')}= takes 0 or 1")
    }

    private fun setting(settings: LineSettings, option: String, call: String): LineSettings {
        val value = option.substringAfter('=', "")
        return when (option.substringBefore('=')) {
            "baud" -> settings.copy(baud = value.toIntOrNull()?.takeIf { it >= 1 } ?: throw SerialRefused("baud= takes a number from 1"))
            "data" -> settings.copy(dataBits = value.toIntOrNull()?.takeIf { it in 5..8 } ?: throw SerialRefused("data= takes 5, 6, 7 or 8"))
            "parity" -> settings.copy(parity = PARITY_WORDS[value] ?: throw SerialRefused("parity= takes none, even, odd, mark or space"))
            "stop" -> settings.copy(stopBits = STOP_WORDS[value] ?: throw SerialRefused("stop= takes 1, 1.5 or 2"))
            else -> throw SerialRefused("unknown $call option '$option'")
        }
    }

    // usb-list's ports, then bt-list's (left out when Bluetooth can't be asked).
    private fun ports(done: (Result<List<BridgePort>>) -> Unit) {
        calls.call("usb-list", emptyList(), ListSink(host) { usb ->
            if (usb["ok"] != true) return@ListSink done(Result.failure(SerialRefused(usb["error"]?.toString() ?: "usb-list failed")))
            val usbPorts = (usb["devices"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().flatMap { device ->
                val name = (device["product"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (device["manufacturer"] as? String)?.takeIf { it.isNotBlank() }
                    ?: device["ids"]?.toString().orEmpty()
                (device["ports"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { port ->
                    BridgePort(
                        port["name"].toString(), "usb", port["path"].toString(), port["url"] as? String,
                        (port["tcp_port"] as? Long)?.toInt(), port["in_use"] == true, name, device["error"] as? String,
                    )
                }
            }
            calls.call("bt-list", listOf(BT_NO_ASK), ListSink(host) { bt ->
                val btPorts = if (bt["ok"] != true) emptyList() else (bt["ports"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { port ->
                    val name = port["name"].toString()
                    BridgePort(
                        name, if (name.startsWith("nus")) "ble-uart" else "bluetooth", port["path"].toString(), port["url"] as? String,
                        (port["tcp_port"] as? Long)?.toInt(), port["in_use"] == true,
                        (port["device"] as? String) ?: port["address"].toString(), port["error"] as? String,
                    )
                }
                done(Result.success(usbPorts + btPorts))
            })
        })
    }

    private fun open(args: List<String>, reply: PendingReply) {
        if (closed) throw SerialRefused("the page has gone")
        val name = args.firstOrNull()?.takeIf { '=' !in it && it != "lower" }
            ?: throw SerialRefused("serial-open needs a port $NAMES_THEM")
        var settings = LineSettings(baud = DEFAULT_BAUD)
        var lower = false
        for (option in args.drop(1)) {
            if (option == "lower") lower = true else settings = setting(settings, option, "serial-open")
        }
        val port = OpenPort(name, reply, settings, lower)
        opening += port
        reply.onCancel { port.shut() }
        ports { result ->
            if (port.shut) return@ports
            val found = result.getOrElse { return@ports port.fail(it.message!!) }.firstOrNull { it.name == name }
            when {
                found == null -> port.fail("no port $name $NAMES_THEM")
                found.tcpPort == null -> port.fail("$name isn't served now" + (found.error?.let { ": $it" } ?: ""))
                found.inUse -> port.fail("$name is busy: another program has it")
                else -> port.connect(found.tcpPort)
            }
        }
    }

    /** One `serial-open`: connecting, then open (with a handle) once the server answers. */
    private inner class OpenPort(
        val name: String,
        val reply: PendingReply,
        val settings: LineSettings,
        val lower: Boolean,
    ) {
        private var socket: BridgeSocket? = null
        private var timer: (() -> Unit)? = null
        private var handle: Long? = null
        private val early = mutableListOf<ByteArray>()
        var shut = false
            private set
        val client = Rfc2217Client { bytes -> socket?.send(bytes) }

        fun connect(tcpPort: Int) {
            host.connectSerial(
                tcpPort,
                received = { bytes -> host.schedule(0) { received(bytes) } },
                closed = { why -> host.schedule(0) { gone(why) } },
                ready = { result -> host.schedule(0) { ready(result) } },
            )
        }

        private fun ready(result: Result<BridgeSocket>) {
            val ready = result.getOrElse { return fail("$name: ${it.message ?: it.javaClass.simpleName}") }
            if (shut) return ready.close()
            socket = ready
            client.open(settings, lower)
            timer = host.schedule(SERIAL_OPEN_SECONDS * 1000L) {
                if (handle == null) fail("$name didn't open within $SERIAL_OPEN_SECONDS s")
            }
        }

        private fun received(bytes: ByteArray) {
            if (shut) return
            val data = client.fromNetwork(bytes)
            if (handle == null) {
                if (data.isNotEmpty()) early += data
                if (!client.answered) return
                opened()
            } else if (data.isNotEmpty()) {
                send(data)
            }
        }

        private fun opened() {
            val id = ++handles
            handle = id
            opening -= this
            open[id] = this
            timer?.invoke()
            reply.line(obj("open" to obj("port" to json(name), "handle" to id.toString(), "baud" to client.settings.baud.toString())))
            early.forEach(::send)
            early.clear()
        }

        private fun send(data: ByteArray) = reply.line(obj("data" to json(Base64.getEncoder().encodeToString(data))))

        fun write(data: ByteArray) = client.write(data)

        // The server closed: before greeting, the port has another user; before answering, the line didn't open.
        private fun gone(why: String?) {
            if (shut) return
            when {
                !client.greeted -> fail("$name is busy: another program has it")
                handle == null -> ports { result ->
                    val error = result.getOrNull()?.firstOrNull { it.name == name }?.error
                    fail("$name didn't open" + (error?.let { ": $it" } ?: ""))
                }
                else -> fail("$name went away (${why ?: "unplugged, or its link dropped"})")
            }
        }

        fun fail(message: String) {
            if (shut) return
            shut()
            reply.refuse(message)
        }

        /** Lets the port go: RTS, then DTR down, and the socket closed. */
        fun shut() {
            if (shut) return
            shut = true
            timer?.invoke()
            opening -= this
            handle?.let { open.remove(it) }
            socket?.let {
                if (client.greeted) runCatching { client.close() }
                runCatching { it.close() }
            }
        }
    }
}
