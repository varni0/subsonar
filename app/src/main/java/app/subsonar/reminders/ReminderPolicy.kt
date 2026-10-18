package app.subsonar.reminders

import app.subsonar.data.Subscription
import app.subsonar.domain.RenewalCalculator
import java.time.LocalDate

/** Pure decision logic for which reminders to send (unit tested). */
object ReminderPolicy {
    /**
     * Returns subscriptions whose next renewal is within [leadDays] days (inclusive, today counts)
     * and that have not yet been reminded for that renewal. Catches up if a daily run was missed.
     */
    fun dueReminders(items: List<Subscription>, leadDays: Int, today: LocalDate): List<Pair<Subscription, LocalDate>> =
        items.filter { it.remindersEnabled }.mapNotNull { sub ->
            val next = RenewalCalculator.nextRenewal(sub.renewalAnchor, sub.cycle, today)
            val days = RenewalCalculator.daysUntil(next, today)
            if (days in 0..leadDays && sub.lastRemindedFor != next) sub to next else null
        }
}
