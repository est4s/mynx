package io.github.est4s.terminal.core

/** A permission mode Chat's Mode… menu offers: [value] is Claude's name for it. */
data class ChatMode(val value: String, val name: String)

/**
 * What Chat offers. Never `bypassPermissions`: a tap on a phone shouldn't
 * let the agent do anything without asking. One Claude reports itself (the
 * user's own settings) is still shown.
 */
val CHAT_MODES: List<ChatMode> = listOf(
    ChatMode("auto", "Auto"),
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

/** Model…'s entries, and the [AgentModel.value] it ticks. */
data class ChatModelMenu(val models: List<AgentModel>, val ticked: String)

/**
 * Model… for [choice], the model picked, else the one the agent [reported]
 * (an id like `claude-haiku-5-5`): `--resume` restores a chat's model, so
 * the menu ticks what runs, matched by value or by its alias (`haiku`), else
 * as an extra entry. Only a pick is ever a launch flag.
 */
fun chatModelMenu(choice: String?, reported: String?, models: List<AgentModel>): ChatModelMenu {
    val ticked = choice ?: reported?.takeIf { it != "default" }?.let { id ->
        models.firstOrNull { it.value == id }?.value
            ?: modelAlias(id)?.let { alias -> models.firstOrNull { it.value == alias }?.value }
            ?: id
    } ?: "default"
    if (models.any { it.value == ticked }) return ChatModelMenu(models, ticked)
    return ChatModelMenu(models + AgentModel(ticked, modelName(ticked), null), ticked)
}

/** The line under Chat's title: the model (blank when not known), and the mode, [highlighted] when it isn't the default. */
data class ChatStatusLine(val model: String, val mode: String, val highlighted: Boolean) {
    val text: String get() = if (model.isEmpty()) mode else "$model · $mode"
}

private const val DEFAULT_MODEL = "Default model"
private val MODEL_ID = Regex("claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-\\d{8})?(\\[1m])?")

/**
 * [choice] is the model picked ([AgentModel.value]), [reported] the one the
 * agent says it runs (an id like `claude-haiku-5-5`, or the value it was
 * given), [mode] the permission mode; null for each when not known. A
 * [pastChat] runs the model it ran before, so with neither known the
 * model is left blank rather than called the default.
 */
fun chatStatusLine(choice: String?, reported: String?, mode: String?, models: List<AgentModel>, pastChat: Boolean = false): ChatStatusLine {
    val model = when {
        choice != null && choice != "default" ->
            models.firstOrNull { it.value == choice }?.name?.let(::plainName) ?: modelName(choice)
        reported != null && reported != "default" -> modelName(reported)
        pastChat && choice == null -> ""
        else -> DEFAULT_MODEL
    }
    val current = mode ?: "default"
    return ChatStatusLine(model, chatModeName(current), highlighted = current != "default")
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

// `claude-opus-5-5[1m]` → `opus[1m]`, as `--model` takes it.
private fun modelAlias(id: String): String? =
    MODEL_ID.matchEntire(id)?.destructured?.let { (family, _, _, wide) -> family + wide }

/** A permission mode as the header's small line names it. */
fun chatModeName(mode: String): String = when (mode) {
    "auto" -> "Auto"
    "default" -> "Asks first"
    "plan" -> "Plan"
    "acceptEdits" -> "Accept edits"
    "bypassPermissions" -> "Bypass permissions"
    else -> mode
}
