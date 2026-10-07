package io.github.est4s.terminal.core

import java.io.File

/** How a rotation lock holds the screen: as it is, or turned to one side. */
enum class Orientation(val word: String) {
    CURRENT("current"), PORTRAIT("portrait"), LANDSCAPE("landscape"),
}

/**
 * The screen's rotation locks (`mynx rotation lock`), each held by a
 * process: the lock ends when it does, so a program that crashes can't
 * leave the screen stuck. A process is its pid and start time, read from
 * [proc], since an ended process's pid can be given to another. Debian's
 * pids are Android's: proot doesn't hide them.
 */
class RotationLocks(private val proc: File = File("/proc")) {
    private data class Holder(val pid: Int, val started: String, val orientation: Orientation)

    private val holders = mutableListOf<Holder>()

    /** The newest lock's orientation; null while unlocked. */
    val orientation: Orientation? get() = synchronized(holders) { holders.lastOrNull()?.orientation }

    /** Null when locked, else why not. */
    fun lock(pid: Int, orientation: Orientation): String? {
        val started = startTime(pid) ?: return "no process $pid"
        synchronized(holders) {
            holders.removeAll { it.pid == pid }
            holders += Holder(pid, started, orientation)
        }
        return null
    }

    /** Ends every lock; whether there was one. */
    fun unlock(): Boolean = synchronized(holders) {
        holders.isNotEmpty().also { holders.clear() }
    }

    /** Drops the locks whose process has ended; whether any went. */
    fun sweep(): Boolean = synchronized(holders) {
        holders.removeAll { startTime(it.pid) != it.started }
    }

    // Field 22 of /proc/PID/stat, counted after the name in brackets
    // (the name may hold spaces and brackets itself).
    private fun startTime(pid: Int): String? {
        val stat = runCatching { File(proc, "$pid/stat").readText() }.getOrNull() ?: return null
        return stat.substringAfterLast(')').trim().split(' ').getOrNull(19)
    }
}
