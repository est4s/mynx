package io.github.est4s.terminal.core

/*
 * A shell's working directory as Android sees it (/proc/<pid>/cwd) is a host
 * path; proot shows the shell the Debian one. These map between the two.
 */

/**
 * The Debian path of [host], or null if it's outside Debian and every bind.
 * [isRootfs] says whether a host folder is the rootfs; the same folder can
 * be spelled several ways (/data/user/0/… and /data/data/…).
 */
fun guestPath(host: String, isRootfs: (String) -> Boolean): String? {
    if (!host.startsWith("/")) return null
    if (HOST_BINDS.any { host == it || host.startsWith("$it/") }) return host
    val parts = host.split('/').filter { it.isNotEmpty() }
    for (i in 1..parts.size) {
        if (isRootfs("/" + parts.take(i).joinToString("/"))) return "/" + parts.drop(i).joinToString("/")
    }
    return null
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
