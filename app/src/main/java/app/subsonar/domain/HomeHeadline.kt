package app.subsonar.domain

import java.math.BigDecimal

/** What the top card of the list says: totals in the default currency, renewals this week, extra cost from price hikes. */
data class HomeHeadline(
    val currencyCode: String,
    val monthly: BigDecimal,
    val yearly: BigDecimal,
    /** Subscriptions (any currency) renewing today or in the next 6 days. */
    val renewThisWeek: Int,
    /** Extra yearly cost from price increases in the last 12 months in [currencyCode]; null when there are none. */
    val hikeExtraYearly: BigDecimal?,
)

object HomeHeadlineCalculator {
    /** "This week" = today plus the next 6 days (a rolling 7-day window, independent of the locale's first weekday). */
    const val WEEK_DAYS = 7L

    fun of(summary: SpendSummary?, daysUntilRenewal: List<Long>, increases: PriceIncreaseSummary): HomeHeadline? {
        val primary = summary?.primary ?: return null
        val hike = increases.totals.firstOrNull { it.currencyCode == primary.currencyCode }?.extraYearly?.takeIf { it.signum() > 0 }
        return HomeHeadline(
            currencyCode = primary.currencyCode,
            monthly = primary.monthly,
            yearly = primary.yearly,
            renewThisWeek = daysUntilRenewal.count { it in 0 until WEEK_DAYS },
            hikeExtraYearly = hike,
        )
    }
}
