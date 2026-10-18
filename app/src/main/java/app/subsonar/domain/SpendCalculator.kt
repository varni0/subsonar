package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.data.Subscription
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class CurrencyTotal(val currencyCode: String, val monthly: BigDecimal, val yearly: BigDecimal, val count: Int)

data class CategoryShare(val category: Category, val monthly: BigDecimal, val fraction: Float)

data class SpendSummary(
    /** Totals per currency, the primary currency first, then by monthly spend. */
    val totals: List<CurrencyTotal>,
    val primaryCurrency: String,
    /** Per-category monthly spend in [primaryCurrency], biggest first. */
    val categories: List<CategoryShare>,
    val dueNext30Days: Int,
) {
    val primary: CurrencyTotal? get() = totals.firstOrNull { it.currencyCode == primaryCurrency }
}

object SpendCalculator {
    private val TWELVE = BigDecimal(12)

    /** Exact cost per year for one subscription. */
    fun yearlyCost(price: BigDecimal, cycle: BillingCycle): BigDecimal = when (cycle) {
        BillingCycle.WEEKLY -> price.multiply(BigDecimal(52))
        BillingCycle.MONTHLY -> price.multiply(TWELVE)
        BillingCycle.QUARTERLY -> price.multiply(BigDecimal(4))
        BillingCycle.YEARLY -> price
    }

    /** Average cost per month (yearly / 12), rounded to cents. */
    fun monthlyCost(price: BigDecimal, cycle: BillingCycle): BigDecimal =
        yearlyCost(price, cycle).divide(TWELVE, 2, RoundingMode.HALF_EVEN)

    fun summarize(items: List<Subscription>, preferredCurrency: String, today: LocalDate): SpendSummary {
        val byCurrency = items.groupBy { it.currencyCode }
        val totals = byCurrency.map { (code, subs) ->
            val yearly = subs.fold(BigDecimal.ZERO) { acc, s -> acc + yearlyCost(s.price, s.cycle) }
            CurrencyTotal(
                currencyCode = code,
                monthly = yearly.divide(TWELVE, 2, RoundingMode.HALF_EVEN),
                yearly = yearly.setScale(2, RoundingMode.HALF_EVEN),
                count = subs.size,
            )
        }
        // Primary = user's preferred currency if used, otherwise the most used one.
        val primary = when {
            byCurrency.containsKey(preferredCurrency) || items.isEmpty() -> preferredCurrency
            else -> byCurrency.maxBy { it.value.size }.key
        }
        val sortedTotals = totals.sortedWith(
            compareByDescending<CurrencyTotal> { it.currencyCode == primary }.thenByDescending { it.monthly }
        )

        val primaryItems = byCurrency[primary].orEmpty()
        val primaryMonthly = primaryItems.fold(BigDecimal.ZERO) { acc, s -> acc + monthlyCost(s.price, s.cycle) }
        val categories = primaryItems.groupBy { it.category }
            .map { (cat, subs) ->
                val m = subs.fold(BigDecimal.ZERO) { acc, s -> acc + monthlyCost(s.price, s.cycle) }
                val fraction = if (primaryMonthly.signum() == 0) 0f
                else m.divide(primaryMonthly, 4, RoundingMode.HALF_EVEN).toFloat()
                CategoryShare(cat, m, fraction)
            }
            .sortedByDescending { it.monthly }

        val due30 = items.count {
            RenewalCalculator.daysUntil(RenewalCalculator.nextRenewal(it.renewalAnchor, it.cycle, today), today) <= 30
        }
        return SpendSummary(sortedTotals, primary, categories, due30)
    }
}
