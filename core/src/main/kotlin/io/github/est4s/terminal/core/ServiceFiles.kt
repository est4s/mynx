package io.github.est4s.terminal.core

import java.io.File

/**
 * Where a service's wrapper (`service-run NAME`) and the app meet, in
 * Debian's /tmp: `NAME.pid` holds the command's process group, written
 * when it starts, and `NAME.exit` its exit code (128+N for signal N),
 * written when it ends. The app takes the exit code from there, never
 * from the session: proot reports a death by signal as 0.
 */
const val SERVICES_DIR = "/tmp/.mynx/services"

/** NAME's exit code from [dir] (the host path of [SERVICES_DIR]), or null if it left none. */
fun readServiceExit(dir: File, name: String): Int? = readNumber(File(dir, "$name.exit"))

/** NAME's process group id, from [dir], or null. */
fun readServicePid(dir: File, name: String): Int? = readNumber(File(dir, "$name.pid"))?.takeIf { it > 0 }

/** Deletes what NAME's last run left in [dir]; call before starting it. */
fun clearServiceFiles(dir: File, name: String) {
    File(dir, "$name.exit").delete()
    File(dir, "$name.pid").delete()
}

private fun readNumber(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()
