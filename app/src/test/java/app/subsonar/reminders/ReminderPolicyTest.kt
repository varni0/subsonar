package app.subsonar.reminders

import app.subsonar.data.BillingCycle
import app.subsonar.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderPolicyTest {
    private val today = LocalDate.parse("2026-10-09")
    private fun sub(id: Long, anchor: String, reminded: String? = null, enabled: Boolean = true) = Subscription(
        id = id, name = "s$id", price = BigDecimal.ONE, currencyCode = "EUR", cycle = BillingCycle.MONTHLY,
        renewalAnchor = LocalDate.parse(anchor), remindersEnabled = enabled, lastRemindedFor = reminded?.let(LocalDate::parse),
    )

    @Test fun picksOnlyDueAndNotYetReminded() {
        val items = listOf(
            sub(1, "2026-10-10"),                          // due tomorrow -> remind
            sub(2, "2026-10-09"),                          // due today -> remind
            sub(3, "2026-10-20"),                          // too far
            sub(4, "2026-10-11", reminded = "2026-10-11"), // already reminded
            sub(5, "2026-10-10", enabled = false),         // reminders off
            sub(6, "2026-09-11", reminded = "2026-09-11"), // rolled to 10-11, new occurrence -> remind
        )
        val due = ReminderPolicy.dueReminders(items, leadDays = 2, today = today).map { it.first.id }
        assertEquals(listOf(1L, 2L, 6L), due)
    }

    @Test fun nextRunIsNineOClock() {
        assertEquals(60L, ReminderScheduler.delayUntilNextRun(LocalDateTime.parse("2026-10-09T08:00")).toMinutes())
        assertEquals(23L * 60, ReminderScheduler.delayUntilNextRun(LocalDateTime.parse("2026-10-09T10:00")).toMinutes())
    }
}
