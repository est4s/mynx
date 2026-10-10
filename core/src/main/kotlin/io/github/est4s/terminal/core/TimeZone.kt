package io.github.est4s.terminal.core

import kotlin.math.abs

private val ZONE_NAME = Regex("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")

/**
 * Debian's `TZ` for the phone's zone [id] (Android's IANA name), now
 * [offsetSeconds] east of UTC. By name when [known] (its zoneinfo file
 * exists in Debian), so daylight saving follows; else a fixed offset in
 * POSIX form, where east of UTC is negative.
 */
fun debianTimeZone(id: String, offsetSeconds: Int, known: (String) -> Boolean): String {
    if (ZONE_NAME.matches(id) && ".." !in id.split("/") && known(id)) return id
    val east = offsetSeconds >= 0
    val hours = abs(offsetSeconds) / 3600
    val minutes = abs(offsetSeconds) / 60 % 60
    val name = (if (east) "+" else "-") + "%02d".format(hours) + (if (minutes > 0) "%02d".format(minutes) else "")
    val posix = (if (east && offsetSeconds != 0) "-" else "") + hours + (if (minutes > 0) ":%02d".format(minutes) else "")
    return "<$name>$posix"
}

/** Where zone [id]'s file is in the Debian at [rootfs] (a host path). */
fun zoneInfoPath(rootfs: String, id: String): String = "$rootfs/usr/share/zoneinfo/$id"
