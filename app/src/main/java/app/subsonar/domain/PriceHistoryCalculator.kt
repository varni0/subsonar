package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.PriceChange
import app.subsonar.data.Subscription
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Result of [PriceHistoryCalculator.summarize]: everything the price-history timeline shows. */
data class PriceHistorySummary(
    /** Usable entries, oldest first. */
    val timeline: List<PriceChange>,
    /** Price before the first recorded change. */
    val startPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val currencyCode: String,
    val cycle: BillingCycle,
    /** Total change since [startPrice] in percent (one decimal); null when [startPrice] is zero. */
    val totalPercent: BigDecimal?,
    /** Change of the yearly cost since [startPrice] (negative when the price fell). */
    val yearlyDelta: BigDecimal,
)

/** A net price increase of one subscription inside the look-back window. */
data class PriceHike(
    val subscriptionId: Long,
    val name: String,
    val currencyCode: String,
    val cycle: BillingCycle,
    val fromPrice: BigDecimal,
    val toPrice: BigDecimal,
    /** Percent increase (one decimal); null when [fromPrice] is zero (e.g. free trial that became paid). */
    val percent: BigDecimal?,
    /** Extra cost per year caused by the increase, in [currencyCode]. */
    val extraYearly: BigDecimal,
    /** Date of the latest recorded change in the window. */
    val lastChange: LocalDate,
)

data class CurrencyIncrease(val currencyCode: String, val extraYearly: BigDecimal, val count: Int)

data class PriceIncreaseSummary(
    /** Extra yearly cost per currency; [primaryCurrency] first, then by amount. */
    val totals: List<CurrencyIncrease>,
    /** Biggest relative increases first (currency-neutral). */
    val hikes: List<PriceHike>,
) {
    val isEmpty get() = hikes.isEmpty()

    companion object { val EMPTY = PriceIncreaseSummary(emptyList(), emptyList()) }
}

/**
 * Pure maths for the price history. A subscription's current price lives on [Subscription]; the history only holds the steps.
 * Entries recorded in another currency or billing cycle than the subscription has now are ignored ([relevant]) because
 * their amounts are not comparable.
 */
object PriceHistoryCalculator {
    /** Look-back window of the "price up" badge and the Insights section. */
    const val RECENT_MONTHS = 12L

    private val HUNDRED = BigDecimal(100)

    /** Percent change from [old] to [new], one decimal, half-up. Null if [old] is zero or negative. */
    fun percentChange(old: BigDecimal, new: BigDecimal): BigDecimal? {
        if (old.signum() <= 0) return null
        return new.subtract(old).multiply(HUNDRED).divide(old, 1, RoundingMode.HALF_UP)
    }

    /** Entries comparable with the current [currencyCode]/[cycle], real changes only, oldest first. */
    fun relevant(history: List<PriceChange>, currencyCode: String, cycle: BillingCycle): List<PriceChange> =
        history
            .filter { it.currencyCode.equals(currencyCode, ignoreCase = true) && it.cycle == cycle && it.oldPrice.compareTo(it.newPrice) != 0 }
            .sortedWith(compareBy<PriceChange> { it.effectiveDate }.thenBy { it.id })

    /** The price that was in effect on [date]: before the first entry the first entry's old price, otherwise the latest new price. */
    fun priceOn(date: LocalDate, currentPrice: BigDecimal, relevant: List<PriceChange>): BigDecimal {
        if (relevant.isEmpty()) return currentPrice
        val last = relevant.lastOrNull { !it.effectiveDate.isAfter(date) } ?: return relevant.first().oldPrice
        return last.newPrice
    }

    fun summarize(history: List<PriceChange>, currentPrice: BigDecimal, currencyCode: String, cycle: BillingCycle): PriceHistorySummary? {
        val timeline = relevant(history, currencyCode, cycle)
        if (timeline.isEmpty()) return null
        val start = timeline.first().oldPrice
        return PriceHistorySummary(
            timeline = timeline,
            startPrice = start,
            currentPrice = currentPrice,
            currencyCode = currencyCode,
            cycle = cycle,
            totalPercent = percentChange(start, currentPrice),
            yearlyDelta = yearlyDelta(start, currentPrice, cycle),
        )
    }

    /** Change of the yearly cost when the price goes from [from] to [to] with billing [cycle]. */
    fun yearlyDelta(from: BigDecimal, to: BigDecimal, cycle: BillingCycle): BigDecimal =
        SpendCalculator.yearlyCost(to, cycle).subtract(SpendCalculator.yearlyCost(from, cycle)).setScale(2, RoundingMode.HALF_EVEN)

    /**
     * Net increase of one subscription over the last [months] months (price now vs. price [months] months ago), or null when
     * the price did not rise (unchanged, fell, or rose and fell back).
     */
    fun recentHike(
        sub: Subscription,
        history: List<PriceChange>,
        today: LocalDate,
        months: Long = RECENT_MONTHS,
    ): PriceHike? {
        val steps = relevant(history, sub.currencyCode, sub.cycle).filter { !it.effectiveDate.isAfter(today) }
        if (steps.isEmpty()) return null
        val windowStart = today.minusMonths(months)
        val inWindow = steps.filter { it.effectiveDate.isAfter(windowStart) }
        if (inWindow.isEmpty()) return null
        val from = priceOn(windowStart, sub.price, steps)
        val to = sub.price
        if (to.compareTo(from) <= 0) return null
        return PriceHike(
            subscriptionId = sub.id, name = sub.name, currencyCode = sub.currencyCode, cycle = sub.cycle,
            fromPrice = from, toPrice = to, percent = percentChange(from, to),
            extraYearly = yearlyDelta(from, to, sub.cycle), lastChange = inWindow.last().effectiveDate,
        )
    }

    /** Percent for the list badge, or null when there is nothing to show. */
    fun badgePercent(sub: Subscription, history: List<PriceChange>, today: LocalDate): BigDecimal? =
        recentHike(sub, history, today)?.percent?.takeIf { it.signum() > 0 }

    /** Insights: all net increases of the last 12 months. [preferredCurrency] is listed first in the totals. */
    fun increaseSummary(
        subs: List<Subscription>,
        history: List<PriceChange>,
        today: LocalDate,
        preferredCurrency: String,
    ): PriceIncreaseSummary {
        val byId = history.groupBy { it.subscriptionId }
        val hikes = subs.mapNotNull { s -> recentHike(s, byId[s.id].orEmpty(), today) }
        if (hikes.isEmpty()) return PriceIncreaseSummary.EMPTY
        val totals = hikes.groupBy { it.currencyCode }.map { (code, list) ->
            CurrencyIncrease(code, list.fold(BigDecimal.ZERO) { a, h -> a + h.extraYearly }, list.size)
        }.sortedWith(compareByDescending<CurrencyIncrease> { it.currencyCode == preferredCurrency }.thenByDescending { it.extraYearly })
        // Unknown percent (from a zero price) counts as the biggest increase.
        val sorted = hikes.sortedWith(compareByDescending<PriceHike> { it.percent ?: BigDecimal("1000000") }.thenByDescending { it.extraYearly })
        return PriceIncreaseSummary(totals, sorted)
    }
}
