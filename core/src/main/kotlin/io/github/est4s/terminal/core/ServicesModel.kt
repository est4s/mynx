package io.github.est4s.terminal.core

/** Where a service is: [word] is how `mynx service list` says it. */
enum class ServiceState(val word: String) {
    RUNNING("running"),
    /** Asked to stop, not ended yet. */
    STOPPING("stopping"),
    /** Not running: never started, ended, or waiting to restart ([Service.restartPending]). */
    STOPPED("stopped"),
    /** Ended too often in a row: given up on. */
    FAILED("failed"),
}

/**
 * One background service. [saved] is false for an adopted tab (or one
 * whose block left the file while it ran): it isn't in `services.conf`
 * and is never restarted. [session] stays after it ends, for its log,
 * until it starts again. [restarts] counts the restarts since it was
 * last started by hand; [open] says it's shown as a tab.
 */
class Service<S> internal constructor(def: ServiceDef, saved: Boolean) {
    var def: ServiceDef = def
        internal set
    var saved: Boolean = saved
        internal set
    val name: String get() = def.name
    var state = ServiceState.STOPPED
        internal set
    var session: S? = null
        internal set
    var pid: Int? = null
        internal set
    var started: Long? = null
        internal set
    var exitCode: Int? = null
        internal set
    var restarts = 0
        internal set
    var open = false
        internal set
    /** Ended, and to start again once its delay is up ([Services.restartDue]). */
    var restartPending = false
        internal set

    internal var policy = policyFor(def, saved)
    internal var stopAsked = false

    val isRunning get() = state == ServiceState.RUNNING || state == ServiceState.STOPPING
}

private fun policyFor(def: ServiceDef, saved: Boolean) =
    if (saved) RestartPolicy.of(def) else RestartPolicy(Restart.NO)

/**
 * The background services and where each one is. Generic over the
 * session type so it stays free of Android, like [Tabs]. The app tells
 * it what happened ([started], [exited], [stopAsked]); it says when to
 * restart. Not thread-safe: use it from one thread.
 */
class Services<S>(private val now: () -> Long = System::currentTimeMillis) {
    private val list = mutableListOf<Service<S>>()

    val all: List<Service<S>> get() = list

    /** Running, or asked to stop and not ended yet. */
    val running: List<Service<S>> get() = list.filter { it.isRunning }

    operator fun get(name: String): Service<S>? = list.find { it.name == name }

    fun find(session: S): Service<S>? = list.find { it.session == session }

    /**
     * Takes in `services.conf`'s [defs]: new ones are added stopped, changed
     * ones get their new settings. One whose block went is removed, or, while
     * it runs, kept as unsaved until it ends.
     */
    fun sync(defs: List<ServiceDef>) {
        val names = defs.map { it.name }.toSet()
        list.removeAll { service ->
            if (!service.saved || service.name in names) return@removeAll false
            if (service.isRunning) {
                service.saved = false
                service.policy = policyFor(service.def, saved = false)
                false
            } else true
        }
        for (def in defs) {
            val service = get(def.name)
            if (service == null) {
                list += Service(def, saved = true)
            } else if (!service.saved || service.def != def) {
                service.def = def
                service.saved = true
                service.policy = policyFor(def, saved = true)
            }
        }
        val order = defs.withIndex().associate { it.value.name to it.index }
        val sorted = list.sortedBy { if (it.saved) order.getValue(it.name) else Int.MAX_VALUE }
        list.clear()
        list += sorted
    }

    /** A tab's [session] becomes the running, unsaved service [name]. */
    fun adopt(name: String, command: String, cwd: String, session: S, pid: Int?, open: Boolean): Service<S> {
        val service = Service<S>(ServiceDef(name, command, cwd, restart = Restart.NO), saved = false)
        service.state = ServiceState.RUNNING
        service.session = session
        service.pid = pid
        service.started = now()
        service.open = open
        list += service
        return service
    }

    /** An unsaved service is now in `services.conf` as [def]. */
    fun save(def: ServiceDef) {
        val service = get(def.name) ?: return
        service.def = def
        service.saved = true
        service.policy = policyFor(def, saved = true)
    }

    fun remove(name: String) {
        list.removeAll { it.name == name }
    }

    /** [name]'s new [session] runs; [byHand] (not a restart) starts the restart count over. */
    fun started(name: String, session: S, pid: Int?, byHand: Boolean) {
        val service = get(name) ?: return
        if (byHand) {
            service.policy = policyFor(service.def, service.saved)
            service.restarts = 0
        } else {
            service.restarts++
        }
        service.state = ServiceState.RUNNING
        service.session = session
        service.pid = pid
        service.started = now()
        service.restartPending = false
        service.stopAsked = false
    }

    /** [name]'s session couldn't be started: given up on. */
    fun startFailed(name: String) {
        val service = get(name) ?: return
        service.state = ServiceState.FAILED
        service.restartPending = false
    }

    /**
     * A stop was asked for: it won't restart. True if there's a running
     * session to stop; a restart that was waiting is called off.
     */
    fun stopAsked(name: String): Boolean {
        val service = get(name) ?: return false
        service.restartPending = false
        if (!service.isRunning) return false
        service.state = ServiceState.STOPPING
        service.stopAsked = true
        return true
    }

    /**
     * [session] ended with [exitCode] (null: unknown, as a failure).
     * Returns the wait in ms before it should start again, or null.
     */
    fun exited(session: S, exitCode: Int?): Long? {
        val service = find(session)?.takeIf { it.isRunning } ?: return null
        service.exitCode = exitCode
        service.pid = null
        if (service.stopAsked) {
            service.stopAsked = false
            service.state = ServiceState.STOPPED
            return null
        }
        val ran = now() - (service.started ?: now())
        val delay = service.policy.after(exitCode, ran)
        service.state = if (service.policy.gaveUp) ServiceState.FAILED else ServiceState.STOPPED
        service.restartPending = delay != null
        return delay
    }

    /** Whether [name]'s restart is still wanted when its delay is up. */
    fun restartDue(name: String): Boolean {
        val service = get(name) ?: return false
        if (!service.restartPending) return false
        service.restartPending = false
        return true
    }

    fun setOpen(name: String, open: Boolean) {
        get(name)?.open = open
    }

    /** What to start when the app starts: the autostart ones and those [saved] as running, in file order. */
    fun toStart(saved: SavedServices?): List<String> {
        val wasRunning = saved?.running.orEmpty().toSet()
        return list.filter { it.saved && !it.isRunning && (it.def.autostart || it.name in wasRunning) }.map { it.name }
    }

    /** The saved services that run, or will restart, to start again after Android kills the app. */
    fun snapshot(): SavedServices =
        SavedServices(list.filter { it.saved && (it.state == ServiceState.RUNNING || it.restartPending) }.map { it.name })
}

/** The services that were running when the app last saved its state. */
data class SavedServices(val running: List<String>)

private const val SAVED_SERVICES_VERSION = "1"

/** Plain text, one `running = NAME` line each, so people and agents can read it. */
fun SavedServices.serialize(): String = buildString {
    append("# Services that were running, written by the app so it can start\n")
    append("# them again after Android kills it.\n")
    append("version = $SAVED_SERVICES_VERSION\n")
    running.forEach { append("running = $it\n") }
}

/** Null if the text isn't a state this version understands. */
fun parseSavedServices(text: String): SavedServices? {
    var version: String? = null
    val running = mutableListOf<String>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || '=' !in line) continue
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        when (key) {
            "version" -> version = value
            "running" -> if (isServiceName(value)) running += value
        }
    }
    return if (version == SAVED_SERVICES_VERSION) SavedServices(running) else null
}
