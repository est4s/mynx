package io.github.est4s.terminal.core

import java.io.File

/** The services file, in [CONFIG_DIR]. */
const val SERVICES_CONF = "services.conf"
const val MAX_SERVICE_NAME = 20
const val MAX_RESTART_LIMIT = 100
const val MAX_RESTART_DELAY = 60
const val MAX_STOP_TIMEOUT = 300

/** When a service that ended starts again by itself. */
enum class Restart(val word: String) {
    NO("no"),
    ON_FAILURE("on-failure"),
    ALWAYS("always"),
}

/**
 * One `[name]` block of `services.conf`. [command] runs with
 * `bash --login -c` in [cwd] (a full path, or `~`-relative).
 * [restartLimit] is how many restarts in a row after quick failures
 * before giving up (0: never), [restartDelay] the first wait in
 * seconds, [stopTimeout] how long a stop waits before forcing.
 */
data class ServiceDef(
    val name: String,
    val command: String,
    val cwd: String = "~",
    val autostart: Boolean = false,
    val restart: Restart = Restart.ON_FAILURE,
    val restartLimit: Int = 5,
    val restartDelay: Int = 1,
    val stopTimeout: Int = 10,
)

data class ParsedServices(val services: List<ServiceDef>, val problems: List<String>)

private val SERVICE_NAME = Regex("[a-z0-9-]{1,$MAX_SERVICE_NAME}")

fun isServiceName(name: String) = SERVICE_NAME.matches(name)

internal fun badServiceName(name: String) =
    "'$name' can't be a service name: use a-z, 0-9 and -, up to $MAX_SERVICE_NAME"

private class ServiceKey(val key: String, val check: (String) -> String?, val apply: ServiceDef.(String) -> ServiceDef)

private fun wholeNumber(key: String, range: IntRange, seconds: Boolean): (String) -> String? = {
    if (it.toIntOrNull() in range) null
    else "$key must be a whole number${if (seconds) " of seconds" else ""} from ${range.first} to ${range.last}"
}

private val SERVICE_KEYS = listOf(
    ServiceKey("command", { if (it.isEmpty()) "command must not be empty" else null }, { copy(command = it) }),
    ServiceKey(
        "cwd",
        { if (it == "~" || it.startsWith("~/") || it.startsWith("/")) null else "cwd must be a full path or start with ~" },
        { copy(cwd = it) },
    ),
    ServiceKey(
        "autostart",
        { if (it == "yes" || it == "no") null else "autostart must be yes or no" },
        { copy(autostart = it == "yes") },
    ),
    ServiceKey(
        "restart",
        { v -> if (Restart.entries.any { it.word == v }) null else "restart must be one of: ${Restart.entries.joinToString(", ") { it.word }}" },
        { v -> copy(restart = Restart.entries.first { it.word == v }) },
    ),
    ServiceKey("restart-limit", wholeNumber("restart-limit", 0..MAX_RESTART_LIMIT, seconds = false), { copy(restartLimit = it.toInt()) }),
    ServiceKey("restart-delay", wholeNumber("restart-delay", 1..MAX_RESTART_DELAY, seconds = true), { copy(restartDelay = it.toInt()) }),
    ServiceKey("stop-timeout", wholeNumber("stop-timeout", 1..MAX_STOP_TIMEOUT, seconds = true), { copy(stopTimeout = it.toInt()) }),
).associateBy { it.key }

/** The keys a service block takes, in the order they're written. */
val SERVICE_KEY_NAMES: List<String> = SERVICE_KEYS.keys.toList()

/** Why [value] can't be [key]'s, or null if it can. */
internal fun checkServiceValue(key: String, value: String): String? {
    val def = SERVICE_KEYS[key] ?: return "unknown key '$key'"
    return def.check(value)
}

internal fun ServiceDef.with(key: String, value: String): ServiceDef = SERVICE_KEYS.getValue(key).apply(this, value)

/** Reads `services.conf`; bad lines are reported and skipped, a block without a command too. */
fun parseServices(text: String): ParsedServices {
    val services = mutableListOf<ServiceDef>()
    val problems = mutableListOf<String>()
    val seen = mutableMapOf<String, Int>()
    // The block being read: null while outside one, or in one that's skipped.
    var current: ServiceDef? = null
    var currentLine = 0
    var inBlock = false

    fun finish() {
        val block = current ?: return
        if (block.command.isEmpty()) problems += "line $currentLine: [${block.name}] has no command"
        else services += block
        current = null
    }

    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        val number = i + 1
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        if (line.startsWith("[") && line.endsWith("]")) {
            finish()
            inBlock = true
            val name = line.substring(1, line.length - 1).trim()
            val earlier = seen[name]
            when {
                !isServiceName(name) -> problems += "line $number: ${badServiceName(name)}"
                earlier != null -> problems += "line $number: [$name] is already defined on line $earlier"
                else -> {
                    seen[name] = number
                    current = ServiceDef(name, "")
                    currentLine = number
                }
            }
            return@forEachIndexed
        }
        if ('=' !in line) {
            problems += "line $number: expected key = value"
            return@forEachIndexed
        }
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        if (!inBlock) {
            problems += "line $number: $key = … must come after a [name] line"
            return@forEachIndexed
        }
        val block = current ?: return@forEachIndexed
        val error = checkServiceValue(key, value)
        if (error != null) problems += "line $number: $error" else current = block.with(key, value)
    }
    finish()
    return ParsedServices(services, problems)
}

fun loadServices(file: File): ParsedServices =
    if (file.isFile) parseServices(file.readText()) else ParsedServices(emptyList(), emptyList())
