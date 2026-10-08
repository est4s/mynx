package io.github.est4s.terminal.core

import java.io.File

/** The phone, as a bug report (`mynx report`) names it. */
data class Phone(val android: String, val sdk: Int, val maker: String, val model: String)

/** A crash's stack trace and when it happened (ms since 1970). */
data class Crash(val text: String, val time: Long)

/** The most of a crash's text a report carries: it goes into a link. */
const val MAX_REPORT_CRASH = 4000

/**
 * The app's last crash, kept in [dir]. It's shown once on the next
 * start ([unseen], then [seen]) and stays for bug reports ([last]) until
 * the next crash replaces it.
 */
class Crashes(private val dir: File) {
    private val new = File(dir, "last-crash.txt")
    private val old = File(dir, "seen-crash.txt")

    fun save(text: String, time: Long = System.currentTimeMillis()) {
        old.delete()
        new.writeText(text)
        new.setLastModified(time)
    }

    fun unseen(): Crash? = read(new)

    fun seen() {
        if (new.isFile) new.renameTo(old)
    }

    fun last(): Crash? = read(new) ?: read(old)

    private fun read(file: File) =
        runCatching { if (file.isFile) Crash(file.readText(), file.lastModified()) else null }.getOrNull()
}

internal fun reportInfo(phone: Phone?, crash: Crash?): String {
    val text = crash?.text?.let { if (it.length <= MAX_REPORT_CRASH) it else it.take(MAX_REPORT_CRASH - 1) + "…" }
    return ok(
        "android" to (phone?.let { json(it.android) } ?: "null"),
        "sdk" to (phone?.sdk?.toString() ?: "null"),
        "maker" to (phone?.let { json(it.maker) } ?: "null"),
        "model" to (phone?.let { json(it.model) } ?: "null"),
        "crash" to (text?.let(::json) ?: "null"),
        "crash_time" to (crash?.time?.toString() ?: "null"),
    )
}
