package app.subsonar.domain

import app.subsonar.data.BillingCycle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object RenewalCalculator {

    /** Date of the [k]-th renewal after [anchor] (k = 0 is the anchor itself). */
    fun occurrence(anchor: LocalDate, cycle: BillingCycle, k: Long): LocalDate = when (cycle) {
        BillingCycle.WEEKLY -> anchor.plusWeeks(k)
        BillingCycle.MONTHLY -> anchor.plusMonths(k)
        BillingCycle.QUARTERLY -> anchor.plusMonths(3 * k)
        BillingCycle.YEARLY -> anchor.plusYears(k)
    }

    /**
     * First renewal on or after [today]. Always computed from the original anchor, so month-end
     * dates do not drift (Jan 31 -> Feb 28 -> Mar 31).
     */
    fun nextRenewal(anchor: LocalDate, cycle: BillingCycle, today: LocalDate): LocalDate {
        if (!anchor.isBefore(today)) return anchor
        // Estimate the number of periods elapsed, then step forward until we reach today.
        var k = when (cycle) {
            BillingCycle.WEEKLY -> ChronoUnit.WEEKS.between(anchor, today)
            BillingCycle.MONTHLY -> ChronoUnit.MONTHS.between(anchor, today)
            BillingCycle.QUARTERLY -> ChronoUnit.MONTHS.between(anchor, today) / 3
            BillingCycle.YEARLY -> ChronoUnit.YEARS.between(anchor, today)
        }.coerceAtLeast(0)
        var date = occurrence(anchor, cycle, k)
        while (date.isBefore(today)) {
            k++
            date = occurrence(anchor, cycle, k)
        }
        return date
    }

    fun daysUntil(date: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)
}
