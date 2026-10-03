package io.github.est4s.terminal.core

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
 */
fun prootLaunch(
    paths: ProotPaths,
    workDir: String = "/root",
    fakeProc: Map<String, String> = emptyMap(),
    cwdFile: String? = null,
    openMenu: Boolean = false,
    keyBarFile: String? = null,
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
        // env -i: the shell must not inherit Android's environment (PATH, LD_*, ANDROID_*).
        addAll(listOf("/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8", "PATH=$DEBIAN_PATH"))
        // Silent if the file can't be written (e.g. its folder was deleted).
        cwdFile?.let { add("PROMPT_COMMAND={ printf '%s' \"\$PWD\" > $it; } 2>/dev/null") }
        // Root's .bashrc opens the launcher menu when this is set.
        if (openMenu) add("POCKET_MENU=1")
        // The keybar command writes the bar to show here (a Debian path).
        keyBarFile?.let { add("POCKET_KEYBAR_FILE=$it") }
        addAll(listOf("/bin/bash", "--login"))
    }
    val env = mapOf(
        "PROOT_LOADER" to paths.loader,
        "PROOT_TMP_DIR" to paths.tmpDir,
    )
    return Launch(argv, env)
}
