package io.github.est4s.terminal.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** How much of the model's context the chat's last call used: [windowTokens] null when not known. */
data class ContextUse(val usedTokens: Long, val windowTokens: Long?)

/**
 * One of the plan's usage windows, from Claude's `rate_limit_event`:
 * [kind] is its key (`five_hour`, `seven_day`, or one to come),
 * [utilization] 0 to 1, [resetsAtSec] in seconds since 1970.
 */
data class PlanWindow(val kind: String, val utilization: Double, val resetsAtSec: Long?, val status: String?)

/** The Usage sheet's numbers: [context] this chat's, [plan] the latest any chat heard, at [planSeenMs]. */
data class ChatUsage(val context: ContextUse? = null, val plan: List<PlanWindow> = emptyList(), val planSeenMs: Long? = null)

/** One card of the Usage sheet: [percent] used, null when not known. */
data class UsageRow(val section: String, val label: String, val percent: Int?, val detail: String)

const val CHAT_USAGE_LATER = "Shows after Claude's next reply."

/** The header's ring: the context's percent used, 0 to 100; null until the window is known. */
fun usageRing(usage: ChatUsage): Int? {
    val context = usage.context ?: return null
    val window = context.windowTokens?.takeIf { it > 0 } ?: return null
    return percent(context.usedTokens.toDouble() / window)
}

private val PLAN_LABELS = mapOf("five_hour" to "Current session", "seven_day" to "Weekly")
private val CLOCK = DateTimeFormatter.ofPattern("HH.mm", Locale.ENGLISH)
private val DAY_CLOCK = DateTimeFormatter.ofPattern("EEE HH.mm", Locale.ENGLISH)

/**
 * The Usage sheet: "Context" (this chat's window), then "Plan usage", a
 * row for each window in the order Claude gave them. A reset today shows
 * its time only, a later one its weekday too, in [zone].
 */
fun usageRows(usage: ChatUsage, nowMs: Long, zone: ZoneId): List<UsageRow> {
    val context = usage.context
    val window = context?.windowTokens?.takeIf { it > 0 }
    val contextRow = when {
        context == null -> UsageRow("Context", "Context window", null, CHAT_USAGE_LATER)
        window == null -> UsageRow("Context", "Context window", null, "${tokenCount(context.usedTokens)} tokens")
        else -> UsageRow("Context", "Context window", usageRing(usage), "${tokenCount(context.usedTokens)} of ${tokenCount(window)} tokens")
    }
    if (usage.plan.isEmpty()) return listOf(contextRow, UsageRow("Plan usage", "Plan usage", null, CHAT_USAGE_LATER))
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return listOf(contextRow) + usage.plan.map { plan ->
        val label = PLAN_LABELS[plan.kind] ?: plan.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val detail = plan.resetsAtSec?.let { Instant.ofEpochSecond(it).atZone(zone) }?.let { at ->
            if (at.toLocalDate() == today) "Resets at ${CLOCK.format(at)}" else "Resets ${DAY_CLOCK.format(at)}"
        } ?: ""
        UsageRow("Plan usage", label, percent(plan.utilization), detail)
    }
}

private fun percent(fraction: Double): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

/** 999, 1k, 12.3k, 1M, 1.5M. */
internal fun tokenCount(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 999_950 -> short(tokens / 1_000.0) + "k"
    else -> short(tokens / 1_000_000.0) + "M"
}

private fun short(value: Double): String {
    val text = String.format(Locale.ROOT, "%.1f", value)
    return text.removeSuffix(".0")
}

/** The context a call used, from its `usage`: input, cache read and cache written; null with none of them. */
internal fun contextTokens(usage: Map<*, *>): Long? {
    val parts = listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
        .map { (usage[it] as? Number)?.toLong() }
    if (parts.all { it == null }) return null
    return parts.sumOf { it ?: 0L }
}

/** A `rate_limit_event`'s `unifiedWindows`, in its order; a window with no utilization is left out. */
internal fun planWindows(info: Map<*, *>): List<PlanWindow> =
    (info["unifiedWindows"] as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
        val window = value as? Map<*, *> ?: return@mapNotNull null
        val utilization = (window["utilization"] as? Number)?.toDouble() ?: return@mapNotNull null
        PlanWindow(key.toString(), utilization, (window["resetsAt"] as? Number)?.toLong(), window["status"] as? String)
    }
