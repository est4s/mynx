package io.github.est4s.terminal.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatUsageTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    // Tuesday 10 November 2026, 08.53 there.
    private val now = 1_794_290_000_000L
    private val plan = listOf(
        PlanWindow("five_hour", 0.35, 1_794_309_000, "allowed"),
        PlanWindow("seven_day", 0.67, 1_794_369_600, "allowed"),
    )

    @Test
    fun `the ring is the context's percent, when the window is known`() {
        assertEquals(15, usageRing(ChatUsage(ContextUse(148_400, 1_000_000))))
        assertEquals(2, usageRing(ChatUsage(ContextUse(21_461, 1_000_000))))
        assertEquals(100, usageRing(ChatUsage(ContextUse(250_000, 200_000))), "never past full")
        assertNull(usageRing(ChatUsage(ContextUse(21_461, null))))
        assertNull(usageRing(ChatUsage()))
    }

    @Test
    fun `the sheet's rows are the context, then the plan's windows with their resets`() {
        assertEquals(
            listOf(
                UsageRow("Context", "Context window", 15, "148.4k of 1M tokens"),
                UsageRow("Plan usage", "Current session", 35, "Resets at 14.10"),
                UsageRow("Plan usage", "Weekly", 67, "Resets Wed 07.00"),
            ),
            usageRows(ChatUsage(ContextUse(148_400, 1_000_000), plan, now - 1000), now, zone),
        )
    }

    @Test
    fun `unknown windows are titled from their key`() {
        val rows = usageRows(ChatUsage(plan = listOf(PlanWindow("seven_day_opus", 0.004, null, null))), now, zone)
        assertEquals(UsageRow("Plan usage", "Seven day opus", 0, ""), rows.last())
    }

    @Test
    fun `before a reply, each section says when it shows`() {
        assertEquals(
            listOf(
                UsageRow("Context", "Context window", null, CHAT_USAGE_LATER),
                UsageRow("Plan usage", "Plan usage", null, CHAT_USAGE_LATER),
            ),
            usageRows(ChatUsage(), now, zone),
        )
        assertEquals(
            UsageRow("Context", "Context window", null, "21.5k tokens"),
            usageRows(ChatUsage(ContextUse(21_461, null)), now, zone).first(),
        )
    }

    @Test
    fun `token counts read short`() {
        assertEquals(listOf("999", "1k", "12.3k", "200k", "1M", "1.5M"),
            listOf(999L, 1_000L, 12_345L, 200_000L, 1_000_000L, 1_500_000L).map(::tokenCount))
    }
}
