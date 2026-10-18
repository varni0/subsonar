package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class SpendCalculatorTest {
    private val today = LocalDate.parse("2026-10-09")
    private fun sub(price: String, cycle: BillingCycle, cur: String = "EUR", cat: Category = Category.OTHER, anchor: String = "2026-11-01") =
        Subscription(name = "x", price = BigDecimal(price), currencyCode = cur, cycle = cycle, renewalAnchor = LocalDate.parse(anchor), category = cat)

    @Test fun normalizesCycles() {
        assertEquals(BigDecimal("10.00"), SpendCalculator.monthlyCost(BigDecimal("120"), BillingCycle.YEARLY))
        assertEquals(BigDecimal("21.67"), SpendCalculator.monthlyCost(BigDecimal("5"), BillingCycle.WEEKLY))
        assertEquals(BigDecimal("10.00"), SpendCalculator.monthlyCost(BigDecimal("30"), BillingCycle.QUARTERLY))
        assertEquals(BigDecimal("119.88"), SpendCalculator.yearlyCost(BigDecimal("9.99"), BillingCycle.MONTHLY))
    }

    @Test fun summarizesPerCurrencyAndCategory() {
        val items = listOf(
            sub("10", BillingCycle.MONTHLY, cat = Category.STREAMING),
            sub("120", BillingCycle.YEARLY, cat = Category.CLOUD),
            sub("5", BillingCycle.MONTHLY, cat = Category.STREAMING),
            sub("100", BillingCycle.MONTHLY, cur = "NOK", cat = Category.MUSIC),
        )
        val s = SpendCalculator.summarize(items, "EUR", today)
        assertEquals("EUR", s.primaryCurrency)
        assertEquals(BigDecimal("25.00"), s.primary!!.monthly)
        assertEquals(BigDecimal("300.00"), s.primary!!.yearly)
        assertEquals(2, s.totals.size)
        assertEquals(Category.STREAMING, s.categories.first().category)
        assertEquals(0.6f, s.categories.first().fraction, 0.001f)
        assertEquals(4, s.dueNext30Days)
    }

    @Test fun fallsBackToMostUsedCurrency() {
        val s = SpendCalculator.summarize(listOf(sub("1", BillingCycle.MONTHLY, cur = "USD")), "EUR", today)
        assertEquals("USD", s.primaryCurrency)
    }

    @Test fun emptyInputGivesEmptySummary() {
        val s = SpendCalculator.summarize(emptyList(), "EUR", today)
        assertEquals(null, s.primary)
        assertEquals(0, s.dueNext30Days)
    }
}
