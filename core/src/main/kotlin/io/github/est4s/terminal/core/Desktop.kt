package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** The X display desktop mode's server takes. */
const val DESKTOP_DISPLAY = ":0"
/** Where the X server listens, in Debian: its TMPDIR is Debian's /tmp. */
const val X11_SOCKET = "/tmp/.X11-unix/X0"
/** The lock an X server writes its pid in, for display :0. */
const val X11_LOCK = "/tmp/.X0-lock"
/** The X server's and the session's logs, in Debian. */
const val DESKTOP_DIR = "/tmp/.mynx/desktop"
const val X11_LOG = "$DESKTOP_DIR/x11.log"
const val DESKTOP_SESSION_LOG = "$DESKTOP_DIR/session.log"
/** The X server's process name (app_process's `--nice-name`): how a leftover one of ours is known. */
const val X11_PROCESS_NAME = "mynx-x11"
private const val X11_ENTRY = "com.termux.x11.CmdEntryPoint"
private const val XKB = "/usr/share/X11/xkb"
private const val X11_FONTS = "/usr/share/fonts/X11"
private val X11_FONT_DIRS = listOf("misc", "TTF", "OTF", "Type1", "100dpi", "75dpi")
private const val SIGKILL = 9
private const val SIGTERM = 15
private const val LEFTOVER_CHECK_MS = 100L
private const val LEFTOVER_CHECKS = 20

/**
 * This build's X server (Termux:X11, a separate GPL program): its native
 * library and the jar of its Java entry point (host paths), and the app's
 * package, which the server's start broadcast goes to.
 */
data class X11Server(val library: String, val jar: String, val packageName: String)

/**
 * The X server, run outside proot by app_process (the jar isn't in the
 * app's own classes). Its TMPDIR is Debian's /tmp, so its socket is
 * Debian's [X11_SOCKET]; it finds xkb from `dirname(TMPDIR)`. Its fonts
 * are [x11FontPath]'s: by itself it would take Debian's `/etc/X11/fonts`
 * first, which holds only xfonts-base's alias file. `-ac` lets any client
 * in, so it listens only on that socket, not on the abstract one
 * (`-nolisten local`), which every app could reach. Android's linker
 * must not see the app's LD_* variables.
 */
fun x11Launch(server: X11Server, rootfs: String): Launch = Launch(
    argv = listOf(
        "/system/bin/app_process", "-Xnoimage-dex2oat", "/", "--nice-name=$X11_PROCESS_NAME",
        X11_ENTRY, DESKTOP_DISPLAY, "-ac", "-nolisten", "local", "-fp", x11FontPath(rootfs),
    ),
    env = mapOf(
        "CLASSPATH" to server.jar,
        "TMPDIR" to "$rootfs/tmp",
        "XKB_CONFIG_ROOT" to "$rootfs$XKB",
        "MYNX_X11_PACKAGE" to server.packageName,
        "MYNX_X11_LIB" to server.library,
    ),
    unset = listOf("LD_PRELOAD", "LD_LIBRARY_PATH"),
)

/**
 * The X server's font path (host paths): Debian's core font folders that
 * have a catalogue (`fonts.dir`, which xfonts-utils writes), then the
 * server's built-in fonts. Read at each start: fonts installed while it
 * runs need `xset fp rehash`, or a restart.
 */
fun x11FontPath(rootfs: String): String =
    (X11_FONT_DIRS.map { "$rootfs$X11_FONTS/$it" }.filter { File(it, "fonts.dir").isFile } + "built-ins").joinToString(",")

/**
 * The command that runs [session] in Debian (a login bash, as a tab's),
 * or null for none. It waits for the X server's socket: the server and
 * the session start together. XFCE wants a session bus.
 */
fun desktopSessionCommand(session: DesktopSession): List<String>? = when (session) {
    DesktopSession.NONE -> null
    DesktopSession.XFCE -> listOf(
        "/bin/bash", "--login", "-c",
        "export DISPLAY=$DESKTOP_DISPLAY; for _ in {1..100}; do [ -S $X11_SOCKET ] && break; sleep 0.1; done; " +
            "exec dbus-launch --exit-with-session startxfce4",
    )
}

/** What [Desktop] needs from the app: running its two processes. */
interface DesktopHost {
    /** This build's X server, or null: it has none. */
    val server: X11Server?

    /** Starts [launch] outside proot in [workDir], its output written afresh to [log]; null when started, else why not. */
    fun startServer(launch: Launch, workDir: File, log: File): String?
    fun serverRunning(): Boolean
    fun stopServer()

    /** Starts [command] in Debian with what a tab has, its output written afresh to [log]; null when started, else why not. */
    fun startSession(command: List<String>, log: File): String?
    fun sessionRunning(): Boolean
    fun stopSession()
}

/**
 * What `mynx desktop status` reports. [problem] is why it can't start,
 * with how to fix it; [install] the [desktopPackages] not installed.
 */
data class DesktopStatus(
    val running: Boolean,
    val session: DesktopSession,
    val sessionRunning: Boolean,
    val problem: String?,
    val install: List<String> = emptyList(),
) {
    val display: String? get() = DESKTOP_DISPLAY.takeIf { running }

    internal fun fields(): Array<Pair<String, String>> = arrayOf(
        "running" to running.toString(),
        "display" to (display?.let(::json) ?: "null"),
        "session" to json(session.word),
        "sessionRunning" to sessionRunning.toString(),
        "socket" to json(X11_SOCKET),
        "log" to json(X11_LOG),
        "problem" to (problem?.let(::json) ?: "null"),
        "install" to array(install.map(::json)),
    )
}

/**
 * The Debian packages [session] needs (`mynx desktop install` installs
 * them without their recommends: xfce4's would bring Xorg's own server).
 * xkb-data for the X server's keyboard, xfonts-base for programs that
 * draw with the server's fonts (xterm); for XFCE a terminal, dbus-launch
 * for its session bus, and the icons its default theme names.
 */
fun desktopPackages(session: DesktopSession): List<String> = listOf("xkb-data", "xfonts-base") + when (session) {
    DesktopSession.NONE -> emptyList()
    DesktopSession.XFCE -> listOf("xfce4", "xfce4-terminal", "dbus-x11", "tango-icon-theme")
}

/** The packages dpkg lists as installed in the Debian at [rootfs]. */
fun installedPackages(rootfs: File): Set<String> {
    val text = runCatching { File(rootfs, "var/lib/dpkg/status").readText() }.getOrNull() ?: return emptySet()
    return text.split(Regex("\n\n+")).mapNotNull { block ->
        val fields = block.lines()
        val name = fields.firstOrNull { it.startsWith("Package: ") }?.removePrefix("Package: ")?.trim()
        name.takeIf { fields.any { it.startsWith("Status: ") && it.trim().endsWith(" installed") } }
    }.toSet()
}

/** The tab the desktop's Install opens: `mynx desktop install`, then a shell. */
val DESKTOP_INSTALL_TAB_COMMAND: List<String> = runThenShell("mynx desktop install")

/**
 * Desktop mode's X server and session, for the Debian at [rootfs]
 * (`mynx desktop`, [desktopRequests]); [host] runs them. Before a start,
 * display :0 is cleared: a lock or socket left by a server that has gone
 * is removed, and a leftover server of ours stopped ([signal], checking
 * through [later]); another program's X server is left alone. Main
 * thread. [processName] names a running process, null when it's gone.
 */
class Desktop(
    private val rootfs: File,
    private val host: DesktopHost,
    private val processName: (Int) -> String? = { runningProcessName(it) },
    private val signal: (Int, Int) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
) {
    private val lock = File(rootfs, X11_LOCK.removePrefix("/"))
    private val socket = File(rootfs, X11_SOCKET.removePrefix("/"))
    private val logs = File(rootfs, DESKTOP_DIR.removePrefix("/"))

    /** `DISPLAY` for new tabs: set while the X server runs. */
    val display: String? get() = DESKTOP_DISPLAY.takeIf { host.serverRunning() }

    private fun session() = loadSettings(File(rootfs, "root/$CONFIG_DIR/settings.conf")).settings.desktop

    fun status(): DesktopStatus {
        val session = session()
        val installed = installedPackages(rootfs)
        return DesktopStatus(
            host.serverRunning(), session, host.sessionRunning(), problem(session),
            desktopPackages(session).filter { it !in installed },
        )
    }

    // What stops a start, and how to fix it.
    private fun problem(session: DesktopSession): String? = when {
        host.server == null -> "this build of mynx has no X server yet"
        !File(rootfs, XKB.removePrefix("/")).isDirectory ->
            "the X server needs Debian's keyboard layouts (xkb-data): mynx desktop install installs them"
        session == DesktopSession.XFCE && !present("/usr/bin/startxfce4") ->
            "XFCE isn't installed: mynx desktop install installs it (or mynx set desktop none runs the X server alone)"
        session == DesktopSession.XFCE && !present("/usr/bin/dbus-launch") ->
            "XFCE needs dbus-launch (dbus-x11): mynx desktop install installs it"
        else -> null
    }

    // Debian's links are absolute: they don't resolve from the host.
    private fun present(path: String) = Files.exists(File(rootfs, path.removePrefix("/")).toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Starts the X server and the session, or just a session that has
     * ended; [done] gets whether the server started now, or fails with why not.
     */
    fun start(done: (Result<Boolean>) -> Unit) {
        val session = session()
        problem(session)?.let { return done(Result.failure(Exception(it))) }
        if (host.serverRunning()) {
            if (!host.sessionRunning()) startSession(session)?.let { return done(Result.failure(Exception(it))) }
            return done(Result.success(false))
        }
        clearDisplay { taken ->
            if (taken != null) return@clearDisplay done(Result.failure(Exception(taken)))
            val server = host.server ?: return@clearDisplay done(Result.failure(Exception("this build of mynx has no X server yet")))
            logs.mkdirs()
            host.startServer(x11Launch(server, rootfs.path), File(rootfs, "tmp"), File(logs, "x11.log"))?.let {
                return@clearDisplay done(Result.failure(Exception("the X server didn't start: $it")))
            }
            done(startSession(session)?.let { Result.failure(Exception(it)) } ?: Result.success(true))
        }
    }

    private fun startSession(session: DesktopSession): String? {
        val command = desktopSessionCommand(session) ?: return null
        logs.mkdirs()
        return host.startSession(command, File(logs, "session.log"))?.let { "the ${session.word} session didn't start: $it" }
    }

    /** Stops the session and the X server; true if anything ran. */
    fun stop(): Boolean {
        val ran = host.serverRunning() || host.sessionRunning()
        host.stopSession()
        host.stopServer()
        val leftover = leftover()
        leftover?.let { runCatching { signal(it, SIGTERM) } }
        return ran || leftover != null
    }

    private fun lockPid(): Int? = runCatching { lock.readText().trim().toInt() }.getOrNull()

    private fun leftover(): Int? = lockPid()?.takeIf { processName(it) == X11_PROCESS_NAME }

    // Null when the display is free (after stopping a leftover of ours), else why not.
    private fun clearDisplay(then: (String?) -> Unit) {
        val pid = lockPid()
        val name = pid?.let(processName)
        when {
            pid == null || name == null -> {
                removeLeftovers()
                then(null)
            }
            name == X11_PROCESS_NAME -> {
                runCatching { signal(pid, SIGTERM) }
                awaitGone(pid, LEFTOVER_CHECKS, then)
            }
            else -> then("display $DESKTOP_DISPLAY is taken by another X server ($name, pid $pid): stop it first")
        }
    }

    private fun awaitGone(pid: Int, checks: Int, then: (String?) -> Unit) {
        later(LEFTOVER_CHECK_MS) {
            when {
                processName(pid) == null -> {
                    removeLeftovers()
                    then(null)
                }
                checks > 1 -> awaitGone(pid, checks - 1, then)
                else -> {
                    runCatching { signal(pid, SIGKILL) }
                    removeLeftovers()
                    then(null)
                }
            }
        }
    }

    private fun removeLeftovers() {
        lock.delete()
        socket.delete()
    }
}

/** The name of process [pid]: its command's file name (app_process's `--nice-name`), else its `comm`; null when it's gone or hidden. */
fun runningProcessName(pid: Int, proc: File = File("/proc")): String? {
    val cmdline = runCatching { File(proc, "$pid/cmdline").readBytes() }.getOrNull() ?: return null
    val command = String(cmdline).substringBefore('\u0000').substringAfterLast('/')
    return command.ifEmpty { runCatching { File(proc, "$pid/comm").readText().trim() }.getOrNull() }
}

/** The `desktop-*` requests (`mynx desktop`). */
fun desktopRequests(desktop: Desktop): Map<String, Later> = mapOf(
    "desktop-status" to Later(5) { _, reply -> reply.ok(*desktop.status().fields()) },
    // A leftover server gets 2 s to stop.
    "desktop-start" to Later(15) { _, reply ->
        desktop.start { result ->
            result.fold(
                { started -> reply.ok("started" to started.toString(), *desktop.status().fields()) },
                { reply.refuse(it.message ?: "the desktop didn't start") },
            )
        }
    },
    "desktop-stop" to Later(10) { _, reply ->
        val stopped = desktop.stop()
        reply.ok("stopped" to stopped.toString(), *desktop.status().fields())
    },
)
