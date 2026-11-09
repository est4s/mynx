package io.github.est4s.terminal.core

import java.util.TreeSet

/** How much of a tool's text a card shows. */
const val CHAT_TOOL_BODY_MAX = 4000

/**
 * What to do to the rows drawn for [old] to show [new]. [Conversation] only
 * appends rows and swaps rows in place, keeping the rest the same objects,
 * so [ChatRowChanges.changed] is cheap to find (indices past [old]'s end
 * are new rows); anything else (New) is a [ChatRowChanges.rebuild].
 */
data class ChatRowChanges(val rebuild: Boolean, val changed: List<Int>)

fun chatRowChanges(old: List<ChatItem>, new: List<ChatItem>): ChatRowChanges {
    if (new.size < old.size) return ChatRowChanges(true, emptyList())
    val changed = ArrayList<Int>()
    new.forEachIndexed { i, item ->
        val before = old.getOrNull(i)
        if (before != null && before.id != item.id) return ChatRowChanges(true, emptyList())
        if (item !== before) changed.add(i)
    }
    return ChatRowChanges(false, changed)
}

/** What an answered card says it was. */
fun chatOutcomeText(outcome: RequestOutcome): String = when (outcome) {
    is RequestOutcome.Selected -> when (outcome.kind) {
        PermissionOptionKind.ALLOW_ONCE -> "Allowed"
        PermissionOptionKind.ALLOW_ALWAYS -> "Always allowed"
        PermissionOptionKind.REJECT_ONCE, PermissionOptionKind.REJECT_ALWAYS -> "Denied"
    }
    is RequestOutcome.Answered -> "Answered: " + outcome.answers.values.joinToString("; ")
    RequestOutcome.Declined -> "Declined"
    is RequestOutcome.Plan -> if (outcome.approved) "Approved" else "Kept planning"
    RequestOutcome.Withdrawn -> "No longer needed"
}

/** A tool card's second line: `command · running`. */
fun chatToolStatus(call: ToolCallInfo): String {
    val kind = when (call.kind) {
        ToolKind.EXECUTE -> "command"
        ToolKind.OTHER -> call.name
        else -> call.kind.name.lowercase()
    }
    val status = when (call.status) {
        ToolStatus.PENDING -> "waiting"
        ToolStatus.IN_PROGRESS -> "running"
        ToolStatus.COMPLETED -> "done"
        ToolStatus.FAILED -> "failed"
    }
    return "$kind · $status"
}

/** What an expanded tool card shows: the description, then the output, cut to [max] characters. */
fun chatToolBody(call: ToolCallInfo, max: Int = CHAT_TOOL_BODY_MAX): String {
    val text = listOfNotNull(call.detail, call.content.takeIf { it.isNotEmpty() }).joinToString("\n\n")
    return if (text.length <= max) text else text.take(max) + "\n… ${text.length - max} more characters"
}

/** The choices made so far on a question card, before they are sent. */
class QuestionPicks(val questions: List<AgentQuestion>) {
    private val picks = questions.map { TreeSet<Int>() }

    /** One question with one choice: tapping it is the answer. */
    val answersOnTap: Boolean get() = questions.size == 1 && !questions[0].multiSelect

    val complete: Boolean get() = picks.all { it.isNotEmpty() }

    fun toggle(question: Int, option: Int) {
        val picked = picks[question]
        if (questions[question].multiSelect) {
            if (!picked.remove(option)) picked.add(option)
        } else {
            picked.clear()
            picked.add(option)
        }
    }

    fun isPicked(question: Int, option: Int): Boolean = option in picks[question]

    /** What [AgentSession.answerQuestion] takes. */
    fun answers(): Map<String, String> = questions.indices.associate { q ->
        questions[q].question to picks[q].joinToString(", ") { questions[q].options[it].label }
    }
}
