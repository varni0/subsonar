package app.subsonar.domain.recurring

import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PriceStepsTest {
    private val today = LocalDate.of(2026, 1, 15)
    private fun detect(rows: List<app.subsonar.domain.importer.ParsedTransaction>) = RecurringDetector.detect(rows, today, "EUR")

    @Test fun singleHikeReportsDateAndStep() {
        val rows = monthly("2025-01", 8, 3, "-11.99", "STREAMFLIX.COM 4412") + monthly("2025-09", 6, 3, "-13.99", "STREAMFLIX.COM 8801")
        val c = detect(rows).find("streamflix")!!
        assertEquals(1, c.priceSteps.size)
        val step = c.priceSteps.single()
        assertEquals(BigDecimal("11.99"), step.from); assertEquals(BigDecimal("13.99"), step.to)
        assertEquals(2025, step.date.year); assertEquals(9, step.date.monthValue)
        assertEquals(step.date, c.priceChangeDate)
        assertEquals(c.priceChange, step.from to step.to)
    }

    @Test fun twoHikesAreBothReported() {
        val rows = monthly("2024-01", 8, 5, "-9.99", "MUSICBOX PREMIUM") + monthly("2024-09", 8, 5, "-10.99", "MUSICBOX PREMIUM") + monthly("2025-05", 8, 5, "-12.99", "MUSICBOX PREMIUM")
        val c = detect(rows).find("musicbox")!!
        assertEquals(listOf(BigDecimal("9.99") to BigDecimal("10.99"), BigDecimal("10.99") to BigDecimal("12.99")), c.priceSteps.map { it.from to it.to })
        assertTrue(c.priceSteps[0].date.isBefore(c.priceSteps[1].date))
        assertEquals(c.priceSteps.last().date, c.priceChangeDate)
        assertEquals(BigDecimal("10.99") to BigDecimal("12.99"), c.priceChange)
    }

    @Test fun steadyPriceHasNoSteps() {
        val c = detect(monthly("2025-01", 12, 3, "-8.00", "FOCUSAPP PRO")).find("focusapp")!!
        assertTrue(c.priceSteps.isEmpty()); assertNull(c.priceChange); assertNull(c.priceChangeDate)
    }

    // ---------- offers for subscriptions that are already tracked ----------

    private fun candidate(key: String, name: String, from: String, to: String, date: String = "2025-09-03", cycle: BillingCycle = BillingCycle.MONTHLY, cur: String = "EUR", variable: Boolean = false) =
        RecurringCandidate(
            key, name, CandidateKind.SUBSCRIPTION, 0.9, ConfidenceLevel.HIGH, false, cycle, BigDecimal(to), cur, variable, 14,
            LocalDate.of(2025, 1, 3), LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 3), false, BigDecimal(from) to BigDecimal(to), Category.STREAMING, emptyList(),
            LocalDate.parse(date), listOf(PriceStep(LocalDate.parse(date), BigDecimal(from), BigDecimal(to))),
        )

    private fun tracked(price: String? = "11.99", cur: String? = "EUR", cycle: BillingCycle? = BillingCycle.MONTHLY, id: Long = 7, name: String = "Streamflix HD", key: String? = null) =
        ExistingSubscription(name, key, id, price?.let(::BigDecimal), cur, cycle)

    @Test fun offerWhenStoredPriceIsStale() {
        val offer = Suggestions.priceUpdateOffer(candidate("streamflix", "Streamflix", "11.99", "13.99"), listOf(tracked()))!!
        assertEquals(7L, offer.subscriptionId)
        assertEquals(BigDecimal("11.99"), offer.oldPrice); assertEquals(BigDecimal("13.99"), offer.newPrice)
        assertEquals(LocalDate.of(2025, 9, 3), offer.effectiveFrom)
        assertEquals("EUR", offer.currency); assertEquals(BillingCycle.MONTHLY, offer.cycle)
    }

    @Test fun offerUsesTheStoredPriceAsOldPrice() {
        assertEquals(BigDecimal("9.99"), Suggestions.priceUpdateOffer(candidate("streamflix", "Streamflix", "11.99", "13.99"), listOf(tracked(price = "9.99")))!!.oldPrice)
    }

    @Test fun offerForDecreasesToo() {
        assertEquals(BigDecimal("9.99"), Suggestions.priceUpdateOffer(candidate("streamflix", "Streamflix", "13.99", "9.99"), listOf(tracked(price = "13.99")))!!.newPrice)
    }

    @Test fun noOfferWhenStoredPriceAlreadyMatches() {
        assertNull(Suggestions.priceUpdateOffer(candidate("streamflix", "Streamflix", "11.99", "13.99"), listOf(tracked(price = "13.990"))))
    }

    @Test fun noOfferWithoutPriceStepOrWhenAmountVaries() {
        val noStep = candidate("streamflix", "Streamflix", "11.99", "13.99").copy(priceChange = null, priceChangeDate = null, priceSteps = emptyList())
        assertNull(Suggestions.priceUpdateOffer(noStep, listOf(tracked())))
        assertNull(Suggestions.priceUpdateOffer(candidate("streamflix", "Streamflix", "11.99", "13.99", variable = true), listOf(tracked())))
    }

    @Test fun noOfferWhenCurrencyOrCycleDiffer() {
        val c = candidate("streamflix", "Streamflix", "11.99", "13.99")
        assertNull(Suggestions.priceUpdateOffer(c, listOf(tracked(cur = "NOK"))))
        assertNull(Suggestions.priceUpdateOffer(c, listOf(tracked(cycle = BillingCycle.YEARLY))))
        assertNull(Suggestions.priceUpdateOffer(c, listOf(tracked(cur = null))))
    }

    @Test fun noOfferWhenNotTrackedOrNoDetails() {
        val c = candidate("streamflix", "Streamflix", "11.99", "13.99")
        assertNull(Suggestions.priceUpdateOffer(c, listOf(tracked(name = "Spotify"))))
        assertNull(Suggestions.priceUpdateOffer(c, listOf(tracked(price = null))))
        assertNull(Suggestions.priceUpdateOffer(c, listOf(ExistingSubscription("Streamflix HD"))))
        assertNull(Suggestions.priceUpdateOffer(c, emptyList()))
    }

    @Test fun importKeyMatchWinsOverNameMatch() {
        val offer = Suggestions.priceUpdateOffer(
            candidate("streamflix", "Streamflix", "11.99", "13.99"),
            listOf(tracked(id = 1, name = "Streamflix Kids"), tracked(id = 2, name = "My TV", key = "streamflix")),
        )!!
        assertEquals(2L, offer.subscriptionId)
    }
}
