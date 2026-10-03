package io.github.est4s.terminal.core

/*
 * A shell's working directory as Android sees it (/proc/<pid>/cwd) is a host
 * path; proot shows the shell the Debian one. These map between the two.
 */

/** The Debian path of [host], or null if it's outside Debian and every bind. */
fun guestPath(host: String, rootfs: String): String? = when {
    host == rootfs -> "/"
    host.startsWith("$rootfs/") -> host.removePrefix(rootfs)
    HOST_BINDS.any { host == it || host.startsWith("$it/") } -> host
    else -> null
}

/** Where the Debian path [guest] lives on the host, or null if it isn't absolute. */
fun hostPath(guest: String, rootfs: String): String? = when {
    !guest.startsWith("/") -> null
    HOST_BINDS.any { guest == it || guest.startsWith("$it/") } -> guest
    guest == "/" -> rootfs
    else -> rootfs + guest
}

/** The parent pid from a `/proc/<pid>/stat` line. The command name may hold spaces and parentheses. */
fun parentPid(stat: String): Int? =
    stat.substringAfterLast(") ", "").split(' ').getOrNull(1)?.toIntOrNull()

/** The first child of [pid], given `/proc/<pid>/stat` lines by pid. proot's child is the shell. */
fun childPid(pid: Int, stats: Map<Int, String>): Int? =
    stats.entries.filter { parentPid(it.value) == pid }.minOfOrNull { it.key }
