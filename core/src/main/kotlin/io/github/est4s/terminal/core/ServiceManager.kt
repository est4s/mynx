package io.github.est4s.terminal.core

import java.io.File

/** The most lines `mynx service log` gives. */
const val MAX_LOG_LINES = 10_000
private const val DEFAULT_LOG_LINES = 100
// How long `mynx` waits for a stop: the longest stop-timeout, then the force.
private const val STOP_WAIT_SECONDS = MAX_STOP_TIMEOUT + 30
private const val QUICK_SECONDS = 10

/** A tab taken over as a service: its [command] in the foreground (null: just the shell) and folder. */
data class AdoptedTab<S>(val session: S, val pid: Int?, val command: String?, val cwd: String, val open: Boolean)

/** What runs in a session's foreground now, and in which folder; null where unknown. */
data class Foreground(val command: String?, val cwd: String?)

/**
 * What the app does for [ServiceManager]: all of it on the thread the
 * manager is used from (the main thread), none of it blocking.
 */
interface ServiceHost<S> {
    /**
     * Starts a hidden session running [def]'s command through the
     * wrapper (`service-run NAME`, which reads `services.conf`; an
     * unsaved one is given [ServiceDef.command]) in [ServiceDef.cwd],
     * with `MYNX_SERVICE=NAME`. Throws, with a message for people, if
     * it can't.
     */
    fun start(def: ServiceDef, saved: Boolean): S

    /** The session's process id, if it has one. */
    fun pid(session: S): Int?

    /**
     * Stops the session: TERM to the process [group] (null: none known),
     * up to [timeoutSeconds] for it to end, then the session's proot is
     * stopped. Its end comes back through [ServiceManager.exited].
     */
    fun stop(session: S, group: Int?, timeoutSeconds: Int)

    /** Everything the session's terminal holds, scrollback included. */
    fun transcript(session: S): String

    /** Shows the session as a tab; null, or why it can't. */
    fun open(name: String, session: S): String?

    /** Service [name] runs [session] now (started or restarted): a tab that shows it follows. */
    fun started(name: String, session: S)

    /** Makes the tab whose `MYNX_SHELL` is [shell] service [name]'s, or fails saying why. */
    fun adopt(name: String, shell: Int): Result<AdoptedTab<S>>

    /** What [session] runs in the foreground, for saving an adopted tab. */
    fun foreground(session: S): Foreground?

    /** Runs [block] after [delayMs], on this thread. */
    fun schedule(delayMs: Long, block: () -> Unit)

    /** [name] ended too often in a row and won't be restarted: tell the phone. */
    fun gaveUp(name: String, exitCode: Int?)

    /** Something about the services changed: the notification, wakelock and state file follow. */
    fun changed()
}

private class ServiceRefused(message: String) : Exception(message)

/**
 * The background services: `services.conf` in [home] (root's home), the
 * wrapper's files in [dir] (the host path of [SERVICES_DIR]), the
 * [Services] model, and the [ServiceHost] that runs them. The app calls
 * [launch] once, [exited] whenever a session ends, [setOpen] when a
 * service's tab is hidden, saves [stateText] on [ServiceHost.changed],
 * and passes [requests] to [MynxRequests].
 */
class ServiceManager<S>(
    private val home: File,
    private val dir: File,
    private val host: ServiceHost<S>,
    now: () -> Long = System::currentTimeMillis,
) {
    val services = Services<S>(now)
    private val file = File(home, "$CONFIG_DIR/$SERVICES_CONF")
    // What to do once a service's session has ended: answer a stop, start it again, forget it.
    private val whenEnded = mutableMapOf<String, MutableList<() -> Unit>>()

    /** Reads `services.conf` again. */
    fun reload() {
        services.sync(loadServices(file).services)
    }

    /** At app start: starts the autostart services and those [saved] as running. */
    fun launch(saved: SavedServices?) {
        reload()
        for (name in services.toStart(saved)) runCatching { start(name, byHand = true) }
        host.changed()
    }

    /** The state to save, so [launch] can bring the running services back. */
    fun stateText(): String = services.snapshot().serialize()

    /** A session ended; false if it isn't a service's (it's the app's to handle). */
    fun exited(session: S): Boolean {
        val service = services.find(session) ?: return false
        val name = service.name
        val wasRunning = service.isRunning
        val delay = services.exited(session, readServiceExit(dir, name))
        if (wasRunning) {
            whenEnded.remove(name)?.forEach { runCatching(it) }
            when {
                delay != null -> host.schedule(delay) {
                    if (services.restartDue(name)) runCatching { start(name, byHand = false) }
                    host.changed()
                }
                service.state == ServiceState.FAILED -> host.gaveUp(name, service.exitCode)
            }
        }
        host.changed()
        return true
    }

    /** How many services run, or wait to restart: while any does, the app stays. */
    val active: Int get() = services.all.count { it.isRunning || it.restartPending }

    /** Stops every service and calls off waiting restarts (the app exits). */
    fun stopAll() {
        for (service in services.all.toList()) {
            if (!stopThen(service) {}) services.stopAsked(service.name)
        }
        host.changed()
    }

    /** A service's tab was shown or hidden. */
    fun setOpen(name: String, open: Boolean) {
        services.setOpen(name, open)
        host.changed()
    }

    /** The `service-*` requests, for [MynxRequests]'s `later`. */
    fun requests(): Map<String, Later> {
        fun now(change: ((List<String>) -> String)? = null, answer: (List<String>) -> String) =
            Later(QUICK_SECONDS, change) { args, reply ->
                try {
                    reply.answer(answer(args.map { it.trim() }))
                } catch (e: ServiceRefused) {
                    reply.refuse(e.message!!)
                }
            }
        fun waiting(change: ((List<String>) -> String)? = null, start: (List<String>, PendingReply) -> Unit) =
            Later(STOP_WAIT_SECONDS, change) { args, reply ->
                try {
                    start(args.map { it.trim() }, reply)
                } catch (e: ServiceRefused) {
                    reply.refuse(e.message!!)
                }
            }
        return mapOf(
            "service-list" to now { list() },
            "service-start" to now { args ->
                val service = named(args, "service-start")
                startByHand(service)
                ok("service" to serviceJson(service))
            },
            "service-stop" to waiting { args, reply ->
                val service = named(args, "service-stop")
                stop(service) { reply.answer(ok("service" to serviceJson(service))) }
            },
            "service-restart" to waiting { args, reply ->
                val service = named(args, "service-restart")
                restart(service) { error ->
                    if (error == null) reply.answer(ok("service" to serviceJson(service))) else reply.refuse(error)
                }
            },
            "service-log" to now { args -> log(args) },
            "service-open" to now { args -> open(named(args, "service-open")) },
            "service-add" to now(change = { "service add ${it.firstOrNull()?.trim().orEmpty()}" }) { args -> add(args) },
            "service-remove" to waiting(change = { "service remove ${it.firstOrNull()?.trim().orEmpty()}" }) { args, reply ->
                remove(args, reply)
            },
            "service-adopt" to now { args -> adopt(args) },
            "service-save" to now(change = { "service save ${it.firstOrNull()?.trim().orEmpty()}" }) { args -> save(args) },
        )
    }

    private fun list(): String {
        reload()
        return ok("services" to array(services.all.map(::serviceJson)))
    }

    // The service the request names, from the file as it is now.
    private fun named(args: List<String>, request: String): Service<S> {
        val name = args.firstOrNull().orEmpty()
        if (name.isEmpty()) throw ServiceRefused("$request needs a name")
        reload()
        return services[name] ?: throw ServiceRefused(noService(name))
    }

    /** Starts [name] (the app's Start); null, or why not. */
    fun start(name: String): String? = refusal { startByHand(named(listOf(name), "start")) }

    /** Stops [name] (a service tab's Stop); null once asked, or why not. */
    fun stop(name: String): String? = refusal { stop(named(listOf(name), "stop")) {} }

    /** Restarts [name] (a service tab's Restart); null once asked, or why not. */
    fun restart(name: String): String? = refusal { restart(named(listOf(name), "restart")) {} }

    /** Makes the tab of shell [shell] the unsaved service [name] (the tab's "Run as service"); null, or why not. */
    fun adopt(name: String, shell: Int): String? = refusal { adopt(listOf(name, shell.toString())) }

    private fun refusal(action: () -> Unit): String? = try {
        action()
        null
    } catch (e: ServiceRefused) {
        e.message
    }

    /** The names of the services there are, for the tabs of removed ones to close. */
    val names: Set<String> get() = services.all.map { it.name }.toSet()

    /** The title of [name]'s tab: null while it runs (the plain mark), else how it is. */
    fun tabTitle(name: String): String? {
        val service = services[name] ?: return null
        return when {
            service.state == ServiceState.RUNNING -> null
            service.state == ServiceState.STOPPING -> "● $name: stopping"
            service.restartPending -> "○ $name: restarting"
            service.state == ServiceState.FAILED ->
                "○ $name: failed" + (service.exitCode?.let { " (exit $it)" } ?: "")
            service.stoppedByHand -> "○ $name: stopped"
            service.exitCode != null -> "○ $name: exit ${service.exitCode}"
            else -> "○ $name: not running"
        }
    }

    private fun startByHand(service: Service<S>) {
        when (service.state) {
            ServiceState.RUNNING -> throw ServiceRefused("${service.name} is already running")
            ServiceState.STOPPING -> throw ServiceRefused("${service.name} is stopping")
            else -> {}
        }
        start(service.name, byHand = true)
        host.changed()
    }

    private fun start(name: String, byHand: Boolean) {
        val service = services[name] ?: return
        clearServiceFiles(dir, name)
        val session = try {
            host.start(service.def, service.saved)
        } catch (e: Exception) {
            services.startFailed(name)
            host.changed()
            throw ServiceRefused("couldn't start $name: ${e.message ?: e.javaClass.simpleName}")
        }
        services.started(name, session, host.pid(session), byHand)
        host.started(name, session)
    }

    // Asks [service] to stop; [then] runs once it has. False if it wasn't running.
    private fun stopThen(service: Service<S>, then: () -> Unit): Boolean {
        val wasStopping = service.state == ServiceState.STOPPING
        if (!services.stopAsked(service.name)) return false
        whenEnded.getOrPut(service.name) { mutableListOf() } += then
        if (!wasStopping) {
            host.stop(service.session!!, readServicePid(dir, service.name), service.def.stopTimeout)
        }
        host.changed()
        return true
    }

    // [stopped] runs once it has stopped.
    private fun stop(service: Service<S>, stopped: () -> Unit) {
        val pending = service.restartPending
        if (stopThen(service, stopped)) return
        if (!pending) throw ServiceRefused("${service.name} isn't running")
        host.changed()
        stopped()
    }

    // [done] gets null once it runs again, else why not. An unsaved one
    // starts again with what runs in it now, if that's known.
    private fun restart(service: Service<S>, done: (String?) -> Unit) {
        if (!service.saved) {
            service.session?.let(host::foreground)?.let { now ->
                services.update(service.name, now.command?.takeIf { it.isNotBlank() }, now.cwd?.takeIf { it.isNotBlank() })
            }
        }
        val again = {
            try {
                start(service.name, byHand = true)
                host.changed()
                done(null)
            } catch (e: ServiceRefused) {
                done(e.message!!)
            }
        }
        if (!stopThen(service, again)) again()
    }

    private fun log(args: List<String>): String {
        val service = named(args, "service-log")
        val lines = args.getOrNull(1)?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LOG_LINES }
                ?: throw ServiceRefused("the number of lines must be a whole number from 1 to $MAX_LOG_LINES")
        } ?: DEFAULT_LOG_LINES
        val session = service.session ?: throw ServiceRefused("${service.name} hasn't run yet")
        val text = host.transcript(session).lines().dropLastWhile { it.isBlank() }.takeLast(lines)
        return ok("log" to json(text.joinToString("\n")))
    }

    private fun open(service: Service<S>): String {
        val session = service.session ?: throw ServiceRefused("${service.name} hasn't run yet")
        host.open(service.name, session)?.let { throw ServiceRefused(it) }
        services.setOpen(service.name, true)
        host.changed()
        return ok()
    }

    private fun add(args: List<String>): String {
        val name = args.firstOrNull().orEmpty()
        if (!isServiceName(name)) throw ServiceRefused(badServiceName(name))
        reload()
        if (services[name] != null) throw ServiceRefused("there's already a service named $name")
        val values = keyValues(args.drop(1))
        if ("command" !in values) throw ServiceRefused("service-add needs command=…")
        val def = values.entries.fold(ServiceDef(name, "")) { def, (key, value) -> def.with(key, value) }
        writeFile(addService(file.takeIf { it.isFile }?.readText(), def).getOrElse { throw ServiceRefused(it.message!!) })
        reload()
        host.changed()
        return ok("service" to serviceJson(services[name]!!))
    }

    private fun remove(args: List<String>, reply: PendingReply) {
        val service = named(args, "service-remove")
        val name = service.name
        if (service.saved) {
            writeFile(removeService(file.readText(), name).getOrElse { throw ServiceRefused(it.message!!) })
            reload()
        }
        val forget = {
            services.remove(name)
            host.changed()
            reply.ok()
        }
        if (!stopThen(service, forget)) forget()
    }

    private fun adopt(args: List<String>): String {
        val name = args.firstOrNull().orEmpty()
        if (!isServiceName(name)) throw ServiceRefused(badServiceName(name))
        val shell = args.getOrNull(1)?.toIntOrNull()
            ?: throw ServiceRefused("service-adopt needs a name and a tab's MYNX_SHELL number")
        reload()
        if (services[name] != null) throw ServiceRefused("there's already a service named $name")
        val tab = host.adopt(name, shell).getOrElse { throw ServiceRefused(it.message ?: "couldn't take that tab") }
        // Adopted from inside the tab, `mynx service adopt` itself is what runs.
        val command = tab.command?.takeUnless(::runsMynx) ?: "bash"
        val service = services.adopt(name, command, tab.cwd, tab.session, tab.pid, tab.open)
        host.changed()
        return ok("service" to serviceJson(service))
    }

    private fun save(args: List<String>): String {
        val service = named(args, "service-save")
        if (service.saved) throw ServiceRefused("${service.name} is already in ~/$CONFIG_DIR/$SERVICES_CONF")
        val values = keyValues(args.drop(1))
        val now = service.session?.let(host::foreground)
        val start = ServiceDef(
            service.name,
            now?.command?.takeIf { it.isNotBlank() } ?: service.def.command,
            now?.cwd?.takeIf { it.isNotBlank() } ?: service.def.cwd,
        )
        val def = values.entries.fold(start) { def, (key, value) -> def.with(key, value) }
        writeFile(addService(file.takeIf { it.isFile }?.readText(), def).getOrElse { throw ServiceRefused(it.message!!) })
        services.save(def)
        reload()
        host.changed()
        return ok("service" to serviceJson(service))
    }

    // KEY=VALUE lines, each checked.
    private fun keyValues(lines: List<String>): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (line in lines.filter { it.isNotEmpty() }) {
            if ('=' !in line) throw ServiceRefused("expected KEY=VALUE: $line")
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            checkServiceValue(key, value)?.let { throw ServiceRefused(it) }
            values[key] = value
        }
        return values
    }

    private fun writeFile(text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw ServiceRefused("couldn't write ~/$CONFIG_DIR/$SERVICES_CONF")
    }

    private fun serviceJson(service: Service<S>): String {
        val def = service.def
        val running = service.isRunning
        val pid = if (running) readServicePid(dir, service.name) ?: service.pid else null
        return obj(
            "name" to json(service.name),
            "saved" to service.saved.toString(),
            "command" to json(def.command),
            "cwd" to json(def.cwd),
            "autostart" to def.autostart.toString(),
            "restart" to json(def.restart.word),
            "state" to json(service.state.word),
            "pid" to (pid?.toString() ?: "null"),
            "started" to (service.started?.takeIf { running }?.toString() ?: "null"),
            "exit_code" to (service.exitCode?.toString() ?: "null"),
            "restarts" to service.restarts.toString(),
            "open" to service.open.toString(),
        )
    }

    private fun noService(name: String) = "no service named $name (mynx service list shows them)"
}

// `mynx …`, or its script under an interpreter (`python3 /opt/mynx/bin/mynx …`, as /proc shows it).
private fun runsMynx(command: String): Boolean =
    command.split(' ').take(2).any { it.substringAfterLast('/') == "mynx" }

/** The `service-*` requests, for [MynxRequests]'s `later`. */
fun <S> serviceRequests(manager: ServiceManager<S>): Map<String, Later> = manager.requests()
