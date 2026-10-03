package io.github.est4s.terminal.core

/** Where the Debian path [guest] lives on the host, or null if it isn't absolute. */
fun hostPath(guest: String, rootfs: String): String? = when {
    !guest.startsWith("/") -> null
    HOST_BINDS.any { guest == it || guest.startsWith("$it/") } -> guest
    guest == "/" -> rootfs
    else -> rootfs + guest
}
