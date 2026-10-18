package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.PriceChange
import app.subsonar.data.Subscription
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PriceHistoryCalculatorTest {
    private val today = LocalDate.of(2026, 10, 10)
    private fun d(s: String) = LocalDate.parse(s)
    private fun bd(s: String) = BigDecimal(s)

    private fun sub(price: String, cycle: BillingCycle = BillingCycle.MONTHLY, cur: String = "EUR", id: Long = 1, name: String = "Streamflix") =
        Subscription(id = id, name = name, price = bd(price), currencyCode = cur, cycle = cycle, renewalAnchor = d("2026-01-05"))

    private fun ch(date: String, from: String, to: String, cur: String = "EUR", cycle: BillingCycle = BillingCycle.MONTHLY, id: Long = 0, sub: Long = 1) =
        PriceChange(id = id, subscriptionId = sub, effectiveDate = d(date), oldPrice = bd(from), newPrice = bd(to), currencyCode = cur, cycle = cycle)

    // ---------- percentChange ----------

    @Test fun percentChangeRoundsToOneDecimal() {
        assertEquals(bd("20.0"), PriceHistoryCalculator.percentChange(bd("10.00"), bd("12.00")))
        assertEquals(bd("16.7"), PriceHistoryCalculator.percentChange(bd("11.99"), bd("13.99")))
        assertEquals(bd("-25.0"), PriceHistoryCalculator.percentChange(bd("20"), bd("15")))
        assertEquals(bd("0.0"), PriceHistoryCalculator.percentChange(bd("9.99"), bd("9.99")))
        assertEquals(bd("0.1"), PriceHistoryCalculator.percentChange(bd("1000"), bd("1001")))
    }

    @Test fun percentChangeFromZeroOrNegativeIsUndefined() {
        assertNull(PriceHistoryCalculator.percentChange(BigDecimal.ZERO, bd("5")))
        assertNull(PriceHistoryCalculator.percentChange(bd("-1"), bd("5")))
    }

    // ---------- summarize ----------

    @Test fun noHistoryMeansNoSummary() {
        assertNull(PriceHistoryCalculator.summarize(emptyList(), bd("9.99"), "EUR", BillingCycle.MONTHLY))
    }

    @Test fun noChangeEntriesAreIgnored() {
        assertNull(PriceHistoryCalculator.summarize(listOf(ch("2026-01-01", "9.99", "9.990")), bd("9.99"), "EUR", BillingCycle.MONTHLY))
    }

    @Test fun singleHikeTotalsAndYearlyDeltaForMonthlyCycle() {
        val s = PriceHistoryCalculator.summarize(listOf(ch("2026-03-01", "10.00", "12.00")), bd("12.00"), "EUR", BillingCycle.MONTHLY)!!
        assertEquals(bd("10.00"), s.startPrice)
        assertEquals(bd("20.0"), s.totalPercent)
        assertEquals(bd("24.00"), s.yearlyDelta)
        assertEquals(1, s.timeline.size)
    }

    @Test fun multipleHikesAreSortedAndCompounded() {
        val s = PriceHistoryCalculator.summarize(
            listOf(ch("2026-08-01", "12.00", "15.00", id = 2), ch("2025-06-01", "10.00", "12.00", id = 1)),
            bd("15.00"), "EUR", BillingCycle.MONTHLY,
        )!!
        assertEquals(listOf(d("2025-06-01"), d("2026-08-01")), s.timeline.map { it.effectiveDate })
        assertEquals(bd("50.0"), s.totalPercent)
        assertEquals(bd("60.00"), s.yearlyDelta)
    }

    @Test fun decreaseGivesNegativeTotals() {
        val s = PriceHistoryCalculator.summarize(listOf(ch("2026-02-01", "20.00", "15.00")), bd("15.00"), "EUR", BillingCycle.MONTHLY)!!
        assertEquals(bd("-25.0"), s.totalPercent)
        assertEquals(bd("-60.00"), s.yearlyDelta)
    }

    @Test fun hikeThenFallBackToStartIsZeroNet() {
        val s = PriceHistoryCalculator.summarize(
            listOf(ch("2025-02-01", "10.00", "13.00", id = 1), ch("2026-02-01", "13.00", "10.00", id = 2)), bd("10.00"), "EUR", BillingCycle.MONTHLY,
        )!!
        assertEquals(bd("0.0"), s.totalPercent)
        assertEquals(bd("0.00"), s.yearlyDelta)
        assertEquals(2, s.timeline.size)
    }

    @Test fun yearlyDeltaConvertsEveryCycle() {
        assertEquals(bd("104.00"), PriceHistoryCalculator.yearlyDelta(bd("1.00"), bd("3.00"), BillingCycle.WEEKLY))
        assertEquals(bd("24.00"), PriceHistoryCalculator.yearlyDelta(bd("1.00"), bd("3.00"), BillingCycle.MONTHLY))
        assertEquals(bd("8.00"), PriceHistoryCalculator.yearlyDelta(bd("1.00"), bd("3.00"), BillingCycle.QUARTERLY))
        assertEquals(bd("2.00"), PriceHistoryCalculator.yearlyDelta(bd("1.00"), bd("3.00"), BillingCycle.YEARLY))
        assertEquals(bd("-8.00"), PriceHistoryCalculator.yearlyDelta(bd("3.00"), bd("1.00"), BillingCycle.QUARTERLY))
    }

    @Test fun entriesInAnotherCurrencyOrCycleAreIgnored() {
        val history = listOf(
            ch("2025-01-01", "100", "150", cur = "NOK"),
            ch("2025-02-01", "10", "20", cycle = BillingCycle.YEARLY),
            ch("2026-05-01", "8.00", "9.00", cur = "eur"),
        )
        val s = PriceHistoryCalculator.summarize(history, bd("9.00"), "EUR", BillingCycle.MONTHLY)!!
        assertEquals(1, s.timeline.size)
        assertEquals(bd("12.5"), s.totalPercent)
        assertNull(PriceHistoryCalculator.summarize(history, bd("150"), "SEK", BillingCycle.MONTHLY))
    }

    @Test fun freeToPaidHasNoPercentButHasYearlyDelta() {
        val s = PriceHistoryCalculator.summarize(listOf(ch("2026-06-01", "0", "4.99")), bd("4.99"), "EUR", BillingCycle.MONTHLY)!!
        assertNull(s.totalPercent)
        assertEquals(bd("59.88"), s.yearlyDelta)
    }

    // ---------- priceOn ----------

    @Test fun priceOnPicksTheEntryInEffect() {
        val steps = PriceHistoryCalculator.relevant(listOf(ch("2025-06-01", "10", "12", id = 1), ch("2026-03-01", "12", "15", id = 2)), "EUR", BillingCycle.MONTHLY)
        val now = bd("15")
        assertEquals(bd("10"), PriceHistoryCalculator.priceOn(d("2025-05-31"), now, steps))
        assertEquals(bd("12"), PriceHistoryCalculator.priceOn(d("2025-06-01"), now, steps))
        assertEquals(bd("12"), PriceHistoryCalculator.priceOn(d("2026-02-28"), now, steps))
        assertEquals(bd("15"), PriceHistoryCalculator.priceOn(d("2026-03-01"), now, steps))
        assertEquals(bd("7"), PriceHistoryCalculator.priceOn(d("2026-03-01"), bd("7"), emptyList()))
    }

    // ---------- recent hikes / badge ----------

    @Test fun recentHikeInsideTwelveMonths() {
        val s = sub("12.00")
        val hike = PriceHistoryCalculator.recentHike(s, listOf(ch("2026-03-01", "10.00", "12.00")), today)!!
        assertEquals(bd("10.00"), hike.fromPrice); assertEquals(bd("12.00"), hike.toPrice)
        assertEquals(bd("20.0"), hike.percent); assertEquals(bd("24.00"), hike.extraYearly)
        assertEquals(d("2026-03-01"), hike.lastChange)
        assertEquals(bd("20.0"), PriceHistoryCalculator.badgePercent(s, listOf(ch("2026-03-01", "10.00", "12.00")), today))
    }

    @Test fun hikeOlderThanTwelveMonthsIsNotRecent() {
        assertNull(PriceHistoryCalculator.recentHike(sub("12.00"), listOf(ch("2025-10-10", "10.00", "12.00")), today))
        assertNotNull(PriceHistoryCalculator.recentHike(sub("12.00"), listOf(ch("2025-10-11", "10.00", "12.00")), today))
    }

    @Test fun twoHikesInWindowAreCombinedFromThePriceTwelveMonthsAgo() {
        val h = listOf(ch("2025-06-01", "8.00", "10.00", id = 1), ch("2026-01-01", "10.00", "11.00", id = 2), ch("2026-08-01", "11.00", "12.50", id = 3))
        val hike = PriceHistoryCalculator.recentHike(sub("12.50"), h, today)!!
        assertEquals(bd("10.00"), hike.fromPrice)
        assertEquals(bd("25.0"), hike.percent)
        assertEquals(bd("30.00"), hike.extraYearly)
        assertEquals(d("2026-08-01"), hike.lastChange)
    }

    @Test fun decreasesAndNetZeroNeverShowAsHikes() {
        assertNull(PriceHistoryCalculator.recentHike(sub("8.00"), listOf(ch("2026-03-01", "10.00", "8.00")), today))
        val upDown = listOf(ch("2026-02-01", "10.00", "12.00", id = 1), ch("2026-06-01", "12.00", "10.00", id = 2))
        assertNull(PriceHistoryCalculator.recentHike(sub("10.00"), upDown, today))
        assertNull(PriceHistoryCalculator.badgePercent(sub("10.00"), upDown, today))
    }

    @Test fun hikeThatWasPartlyReversedCountsOnlyTheNetIncrease() {
        val h = listOf(ch("2026-02-01", "10.00", "14.00", id = 1), ch("2026-06-01", "14.00", "12.00", id = 2))
        assertEquals(bd("20.0"), PriceHistoryCalculator.recentHike(sub("12.00"), h, today)!!.percent)
    }

    @Test fun futureDatedEntriesAreIgnored() {
        assertNull(PriceHistoryCalculator.recentHike(sub("12.00"), listOf(ch("2026-12-01", "10.00", "12.00")), today))
    }

    @Test fun weeklyAndYearlyCyclesUseTheirOwnYearlyFactor() {
        val weekly = PriceHistoryCalculator.recentHike(sub("3.00", BillingCycle.WEEKLY), listOf(ch("2026-05-01", "2.00", "3.00", cycle = BillingCycle.WEEKLY)), today)!!
        assertEquals(bd("52.00"), weekly.extraYearly)
        val yearly = PriceHistoryCalculator.recentHike(sub("99.00", BillingCycle.YEARLY), listOf(ch("2026-05-01", "89.00", "99.00", cycle = BillingCycle.YEARLY)), today)!!
        assertEquals(bd("10.00"), yearly.extraYearly); assertEquals(bd("11.2"), yearly.percent)
        val quarterly = PriceHistoryCalculator.recentHike(sub("30.00", BillingCycle.QUARTERLY), listOf(ch("2026-05-01", "27.00", "30.00", cycle = BillingCycle.QUARTERLY)), today)!!
        assertEquals(bd("12.00"), quarterly.extraYearly)
    }

    @Test fun badgeIsHiddenWhenPercentIsUndefined() {
        val s = sub("4.99")
        assertNotNull(PriceHistoryCalculator.recentHike(s, listOf(ch("2026-06-01", "0", "4.99")), today))
        assertNull(PriceHistoryCalculator.badgePercent(s, listOf(ch("2026-06-01", "0", "4.99")), today))
    }

    // ---------- Insights summary ----------

    @Test fun increaseSummaryGroupsByCurrencyAndSortsByPercent() {
        val a = sub("12.00", id = 1, name = "A")
        val b = sub("120.00", BillingCycle.YEARLY, "EUR", id = 2, name = "B")
        val c = sub("99.00", BillingCycle.MONTHLY, "NOK", id = 3, name = "C")
        val unchanged = sub("5.00", id = 4, name = "D")
        val falling = sub("5.00", id = 5, name = "E")
        val history = listOf(
            ch("2026-03-01", "10.00", "12.00", sub = 1),
            ch("2026-04-01", "80.00", "120.00", cycle = BillingCycle.YEARLY, sub = 2),
            ch("2026-05-01", "90.00", "99.00", cur = "NOK", sub = 3),
            ch("2026-05-01", "6.00", "5.00", sub = 5),
        )
        val summary = PriceHistoryCalculator.increaseSummary(listOf(a, b, c, unchanged, falling), history, today, "EUR")
        assertEquals(listOf("B", "A", "C"), summary.hikes.map { it.name })
        assertEquals(listOf("EUR", "NOK"), summary.totals.map { it.currencyCode })
        assertEquals(bd("64.00"), summary.totals[0].extraYearly); assertEquals(2, summary.totals[0].count)
        assertEquals(bd("108.00"), summary.totals[1].extraYearly)
        assertFalse(summary.isEmpty)
    }

    @Test fun preferredCurrencyIsListedFirstEvenWhenSmaller() {
        val a = sub("12.00", id = 1, cur = "EUR"); val c = sub("999.00", id = 3, cur = "NOK")
        val history = listOf(ch("2026-03-01", "10.00", "12.00", sub = 1), ch("2026-05-01", "500.00", "999.00", cur = "NOK", sub = 3))
        assertEquals(listOf("NOK", "EUR"), PriceHistoryCalculator.increaseSummary(listOf(a, c), history, today, "NOK").totals.map { it.currencyCode })
        assertEquals(listOf("EUR", "NOK"), PriceHistoryCalculator.increaseSummary(listOf(a, c), history, today, "EUR").totals.map { it.currencyCode })
    }

    @Test fun noHikesGiveTheEmptySummary() {
        val s = PriceHistoryCalculator.increaseSummary(listOf(sub("5.00")), emptyList(), today, "EUR")
        assertTrue(s.isEmpty); assertTrue(s.totals.isEmpty())
        assertSame(PriceIncreaseSummary.EMPTY, s)
    }

    @Test fun historyOfOtherSubscriptionsDoesNotLeak() {
        val history = listOf(ch("2026-03-01", "10.00", "12.00", sub = 2))
        assertTrue(PriceHistoryCalculator.increaseSummary(listOf(sub("12.00", id = 1)), history, today, "EUR").isEmpty)
    }

    @Test fun switchingCurrencyAfterTheHikeHidesTheOldHistory() {
        assertNull(PriceHistoryCalculator.recentHike(sub("120.00", cur = "NOK"), listOf(ch("2026-03-01", "10.00", "12.00")), today))
    }
}
