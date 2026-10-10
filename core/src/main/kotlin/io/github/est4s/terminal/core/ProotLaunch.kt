package io.github.est4s.terminal.core

import java.io.File

/** Where the app keeps proot and the Debian rootfs on the device. */
data class ProotPaths(
    /** `libproot.so` in the app's native library dir (the only place Android lets us exec from). */
    val proot: String,
    val loader: String,
    val rootfs: String,
    val tmpDir: String,
)

/** A process to start: [argv] and the extra [env] it needs. */
data class Launch(val argv: List<String>, val env: Map<String, String>)

internal val HOST_BINDS = listOf("/dev", "/proc", "/sys", "/storage")

private const val DEBIAN_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

/**
 * Command that starts a login bash inside Debian. [fakeProc] maps `/proc`
 * files Android blocks to stand-ins (see [writeFakeProc]). With [cwdFile]
 * (a Debian path), the shell writes its folder there after each prompt:
 * proot tracks the folder itself, so the host can't see it in /proc.
 * [openMenu] has the shell open the launcher menu first. [keyBarFile] (a
 * Debian path) is where programs report the key bar they want.
 * [toolsDir] (a host path) is mounted at [TOOLS_MOUNT], its `bin` first
 * on the PATH. [requestDir] (a Debian path) is where `mynx` sends requests.
 * [shellId] tells programs which tab they run in (`mynx notify`).
 * [soundSocket] (a Debian path) is the sound server's socket. [timeZone]
 * is the phone's, as [debianTimeZone] gives it. [command] runs instead
 * of the login bash.
 */
fun prootLaunch(
    paths: ProotPaths,
    workDir: String = "/root",
    fakeProc: Map<String, String> = emptyMap(),
    cwdFile: String? = null,
    openMenu: Boolean = false,
    keyBarFile: String? = null,
    toolsDir: String? = null,
    requestDir: String? = null,
    shellId: Int? = null,
    soundSocket: String? = null,
    timeZone: String? = null,
    command: List<String> = listOf("/bin/bash", "--login"),
): Launch {
    val argv = buildList {
        add(paths.proot)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-0")
        add("-r"); add(paths.rootfs)
        add("-w"); add(workDir)
        HOST_BINDS.forEach { add("-b"); add(it) }
        fakeProc.forEach { (procPath, fake) -> add("-b"); add("$fake:$procPath") }
        toolsDir?.let {
            add("-b"); add("$it:$TOOLS_MOUNT")
        }
        // env -i: the shell must not inherit Android's environment (PATH, LD_*, ANDROID_*).
        addAll(listOf("/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8"))
        add("PATH=" + (if (toolsDir != null) "$TOOLS_MOUNT/bin:" else "") + DEBIAN_PATH)
        // Programs that open a browser (agents signing in) use this; it asks the app.
        if (toolsDir != null) add("BROWSER=$TOOLS_MOUNT/bin/xdg-open")
        // Silent if the file can't be written (e.g. its folder was deleted).
        cwdFile?.let { add("PROMPT_COMMAND={ printf '%s' \"\$PWD\" > $it; } 2>/dev/null") }
        // Root's .bashrc opens the launcher menu when this is set.
        if (openMenu) add("MYNX_MENU=1")
        // The keybar command writes the bar to show here (a Debian path).
        keyBarFile?.let { add("MYNX_KEYBAR_FILE=$it") }
        requestDir?.let { add("MYNX_REQUESTS=$it") }
        shellId?.let { add("MYNX_SHELL=$it") }
        soundSocket?.let { add("PULSE_SERVER=unix:$it") }
        // Debian's own zone is UTC; a TZ set in .bashrc still wins.
        timeZone?.let { add("TZ=$it") }
        addAll(command)
    }
    val env = mapOf(
        "PROOT_LOADER" to paths.loader,
        "PROOT_TMP_DIR" to paths.tmpDir,
    )
    return Launch(argv, env)
}

/**
 * A tab's [prootLaunch] command that runs [command] in a login bash, then
 * the tab's usual login shell. bash ignores Ctrl+C while [command] runs
 * (the program still gets it), so stopping it leaves the shell.
 */
fun runThenShell(command: String): List<String> =
    listOf("/bin/bash", "--login", "-c", "trap : INT; $command; exec /bin/bash --login")

/** The tab the launcher shortcut "Settings" opens: the settings editors, then a shell. */
val SETTINGS_TAB_COMMAND: List<String> = runThenShell("mynx edit")

/** The tab the "update available" notification opens: `mynx update`, then a shell. */
val UPDATE_TAB_COMMAND: List<String> = runThenShell("mynx update")

/** How long a proot gets to stop what it runs before it's killed outright. */
const val PROOT_STOP_GRACE_MS = 2000L
private const val SIGQUIT = 3

/**
 * Stops proot [pid] and everything it runs. proot ignores TERM and HUP,
 * and KILL leaves its programs running untraced, where every system call
 * it would handle fails and they spin at full CPU. QUIT makes it kill
 * them all, then exit. [force] (KILL) follows after a grace, through
 * [later], in case it hasn't; it should do nothing to an ended process.
 */
fun stopProot(pid: Int?, signal: (Int, Int) -> Unit, later: (Long, () -> Unit) -> Unit, force: () -> Unit) {
    if (pid != null && pid > 0) runCatching { signal(pid, SIGQUIT) }
    later(PROOT_STOP_GRACE_MS, force)
}

/** The pid in Android's `Process[pid=N, …]`: its Process has no pid(), and destroyForcibly() only sends TERM. */
fun processPid(description: String): Int? =
    Regex("""pid=(\d+)""").find(description)?.groupValues?.get(1)?.toInt()

/** The programs proot [pid] traces, from `TracerPid` in [proc]: their parent may be gone. */
fun prootTracees(pid: Int, proc: File = File("/proc")): List<Int> =
    proc.listFiles().orEmpty().mapNotNull { dir ->
        val tracee = dir.name.toIntOrNull() ?: return@mapNotNull null
        val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return@mapNotNull null
        val tracer = Regex("""^TracerPid:\s*(\d+)""", RegexOption.MULTILINE).find(status)?.groupValues?.get(1)
        tracee.takeIf { tracer == pid.toString() }
    }

/**
 * Kills proot [pid] outright, after what it traces: a hung proot can
 * block QUIT while a program it traces sits stopped, and a program
 * outliving its proot runs untraced and spins (see [stopProot]).
 */
fun killProot(pid: Int, proc: File = File("/proc"), kill: (Int) -> Unit) {
    prootTracees(pid, proc).forEach { runCatching { kill(it) } }
    runCatching { kill(pid) }
}
