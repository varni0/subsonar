package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.Subscription
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class HomeHeadlineTest {
    private val today = LocalDate.of(2026, 10, 18)
    private fun sub(price: String, cur: String = "NOK") =
        Subscription(name = "x", price = BigDecimal(price), currencyCode = cur, cycle = BillingCycle.MONTHLY, renewalAnchor = today)

    @Test fun totalsWeekCountAndHikes() {
        val summary = SpendCalculator.summarize(listOf(sub("100"), sub("60.92"), sub("10", "EUR")), "NOK", today)
        val inc = PriceIncreaseSummary(listOf(CurrencyIncrease("EUR", BigDecimal("12"), 1), CurrencyIncrease("NOK", BigDecimal("72"), 2)), emptyList())
        val h = HomeHeadlineCalculator.of(summary, listOf(0, 3, 6, 7, 30), inc)!!
        assertEquals("NOK", h.currencyCode)
        assertEquals(BigDecimal("1931.04"), h.yearly)
        assertEquals(BigDecimal("160.92"), h.monthly)
        assertEquals(3, h.renewThisWeek) // 0, 3 and 6 days; 7 days is next week
        assertEquals(BigDecimal("72"), h.hikeExtraYearly)
    }

    @Test fun noHikeInPrimaryCurrencyGivesNull() {
        val summary = SpendCalculator.summarize(listOf(sub("100")), "NOK", today)
        val inc = PriceIncreaseSummary(listOf(CurrencyIncrease("EUR", BigDecimal("12"), 1)), emptyList())
        assertNull(HomeHeadlineCalculator.of(summary, emptyList(), inc)!!.hikeExtraYearly)
        assertNull(HomeHeadlineCalculator.of(summary, emptyList(), PriceIncreaseSummary.EMPTY)!!.hikeExtraYearly)
        assertEquals(0, HomeHeadlineCalculator.of(summary, emptyList(), PriceIncreaseSummary.EMPTY)!!.renewThisWeek)
    }

    @Test fun emptySummaryGivesNoHeadline() {
        assertNull(HomeHeadlineCalculator.of(null, emptyList(), PriceIncreaseSummary.EMPTY))
    }
}
