package io.github.est4s.terminal.core

import java.io.File

private val REQUEST_FILE = Regex("[A-Za-z0-9_-]{1,64}\\.req")

/**
 * Answers the `pocket` command. It writes a request to [dir] as `ID.req`
 * (renamed into place when complete): the request name on the first line.
 * The answer goes to `ID.reply` as JSON, also renamed into place, and the
 * request is removed. [home] is root's home in Debian.
 */
class PocketRequests(private val dir: File, private val home: File) {
    /** Answers every waiting request; returns the names of those handled. */
    fun processPending(): List<String> {
        val pending = dir.listFiles { f -> f.isFile && REQUEST_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }
        return pending.map { request ->
            val name = runCatching { request.readText().lineSequence().first().trim() }.getOrDefault("")
            val reply = answer(name)
            val id = request.name.removeSuffix(".req")
            val tmp = File(dir, "$id.reply.tmp")
            tmp.writeText(reply)
            tmp.renameTo(File(dir, "$id.reply"))
            request.delete()
            name
        }
    }

    private fun answer(name: String): String = when (name) {
        "check" -> {
            val problems = checkConfig(home).joinToString(",") { file ->
                """{"file":${json(file.file)},"problems":[${file.problems.joinToString(",") { json(it) }}]}"""
            }
            """{"ok":true,"problems":[$problems]}"""
        }
        else -> """{"ok":false,"error":${json("unknown request '$name'")}}"""
    }
}

internal fun json(text: String): String = buildString {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}
