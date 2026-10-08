package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How long after a successful check the next one is due. */
const val CHECK_EVERY_MS = 24 * 3_600_000L

/** How long after a failed check to try again. */
const val RETRY_AFTER_MS = 3_600_000L

/**
 * What update checks found, kept between runs. Times are ms since 1970;
 * [latest] is the version of GitHub's latest release at the last
 * successful check (null: none yet), [notified] the version the user was
 * last told about, [error] why the last check failed, if it did.
 */
data class UpdateState(
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val latest: String? = null,
    val notified: String? = null,
    val error: String? = null,
)

fun UpdateState.serialize(): String = buildString {
    append("# Update checks, written by the app.\n")
    lastAttempt?.let { append("last-attempt = $it\n") }
    lastSuccess?.let { append("last-success = $it\n") }
    latest?.let { append("latest = ${it.oneLine()}\n") }
    notified?.let { append("notified = ${it.oneLine()}\n") }
    error?.let { append("error = ${it.oneLine()}\n") }
}

/** Lines it doesn't understand are skipped: the worst a broken file does is an early check. */
fun parseUpdateState(text: String): UpdateState {
    var state = UpdateState()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("#") || "=" !in line) continue
        val key = line.substringBefore("=").trim()
        val value = line.substringAfter("=").trim().ifEmpty { null } ?: continue
        state = when (key) {
            "last-attempt" -> value.toLongOrNull()?.let { state.copy(lastAttempt = it) } ?: state
            "last-success" -> value.toLongOrNull()?.let { state.copy(lastSuccess = it) } ?: state
            "latest" -> state.copy(latest = value)
            "notified" -> state.copy(notified = value)
            "error" -> state.copy(error = value)
            else -> state
        }
    }
    return state
}

fun readUpdateState(file: File): UpdateState =
    runCatching { parseUpdateState(file.readText()) }.getOrDefault(UpdateState())

/** Written to a temp file and renamed, so a crash mid-write can't leave half a file. */
fun writeUpdateState(file: File, state: UpdateState) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, ".${file.name}.tmp")
    temp.writeText(state.serialize())
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun UpdateState.lastFailed() = lastAttempt != null && (lastSuccess == null || lastAttempt > lastSuccess)

/** When the next check is due (0: now). */
fun nextCheckAt(state: UpdateState): Long = when {
    state.lastFailed() -> state.lastAttempt!! + RETRY_AFTER_MS
    state.lastSuccess != null -> state.lastSuccess + CHECK_EVERY_MS
    else -> 0
}

/** A clock set back (before the last attempt) makes a check due rather than waiting for it to catch up. */
fun checkDue(state: UpdateState, now: Long): Boolean =
    now >= nextCheckAt(state) || now < (state.lastAttempt ?: 0)

/** The state after a check at [now] found [check]; a failure keeps what the last success found. */
fun UpdateState.afterCheck(check: ReleaseCheck, now: Long): UpdateState = when (check) {
    is ReleaseCheck.Found -> copy(lastAttempt = now, lastSuccess = now, latest = check.release.version.toString(), error = null)
    ReleaseCheck.NoRelease -> copy(lastAttempt = now, lastSuccess = now, latest = null, error = null)
    is ReleaseCheck.Failed -> copy(lastAttempt = now, error = check.message)
    is ReleaseCheck.BadData -> copy(lastAttempt = now, error = check.message)
}

/** The version to update to from [current], or null if there's none newer. */
fun updateAvailable(current: String, state: UpdateState): String? =
    state.latest?.takeIf { isNewer(it, current) }

/** Whether to tell the user about the available update: once per version. */
fun shouldNotify(current: String, state: UpdateState): Boolean =
    updateAvailable(current, state)?.let { it != state.notified } ?: false

fun UpdateState.notified(version: String): UpdateState = copy(notified = version)

private fun String.oneLine() = replace(Regex("[\r\n]+"), " ")
