package io.github.est4s.terminal.core

/** A permission mode Chat's Mode… menu offers: [value] is Claude's name for it. */
data class ChatMode(val value: String, val name: String)

/**
 * What Chat offers. Never `bypassPermissions`: a tap on a phone shouldn't
 * let the agent do anything without asking. One Claude reports itself (the
 * user's own settings) is still shown.
 */
val CHAT_MODES: List<ChatMode> = listOf(
    ChatMode("default", "Default (asks)"),
    ChatMode("plan", "Plan (plan first)"),
    ChatMode("acceptEdits", "Accept edits"),
)

fun chatModeOffered(mode: String): Boolean = CHAT_MODES.any { it.value == mode }

/**
 * Before the agent has listed its models (no process yet): Default and the
 * aliases `--model` takes, as the `initialize` answer's own values are
 * (`default`, `opus`).
 */
private val FALLBACK_MODELS = listOf(
    AgentModel("default", "Default (recommended)", null),
    AgentModel("opus", "Opus", null),
    AgentModel("sonnet", "Sonnet", null),
    AgentModel("haiku", "Haiku", null),
)

fun chatModelChoices(reported: List<AgentModel>): List<AgentModel> = reported.ifEmpty { FALLBACK_MODELS }

/** The line under Chat's title: the model, and the mode, [highlighted] when it isn't the default. */
data class ChatStatusLine(val model: String, val mode: String, val highlighted: Boolean) {
    val text: String get() = "$model · $mode"
}

private const val DEFAULT_MODEL = "Default model"
private val MODEL_ID = Regex("claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-\\d{8})?(\\[1m])?")

/**
 * [choice] is the model picked ([AgentModel.value]), [reported] the one the
 * agent says it runs (an id like `claude-haiku-5-5`, or the value it was
 * given), [mode] the permission mode; null for each when not known.
 */
fun chatStatusLine(choice: String?, reported: String?, mode: String?, models: List<AgentModel>): ChatStatusLine {
    val model = when {
        choice != null && choice != "default" ->
            models.firstOrNull { it.value == choice }?.name?.let(::plainName) ?: modelName(choice)
        reported != null && reported != "default" -> modelName(reported)
        else -> DEFAULT_MODEL
    }
    val current = mode ?: "default"
    return ChatStatusLine(model, modeName(current), highlighted = current != "default")
}

// "Default (recommended)" → "Default".
private fun plainName(name: String): String = name.substringBefore(" (").ifBlank { name }

private fun modelName(id: String): String {
    MODEL_ID.matchEntire(id)?.let { match ->
        val (family, major, minor, wide) = match.destructured
        val version = if (minor.isEmpty()) major else "$major.$minor"
        return family.replaceFirstChar { it.uppercase() } + " " + version + if (wide.isEmpty()) "" else " (1M)"
    }
    if (id.all { it in 'a'..'z' }) return id.replaceFirstChar { it.uppercase() }
    return id
}

private fun modeName(mode: String): String = when (mode) {
    "default" -> "Asks first"
    "plan" -> "Plan"
    "acceptEdits" -> "Accept edits"
    "bypassPermissions" -> "Bypass permissions"
    else -> mode
}
