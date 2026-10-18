package app.subsonar.domain.recurring

import app.subsonar.data.BillingCycle
import app.subsonar.domain.importer.ParsedTransaction
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class RecurringDetectorTest {
    private val today = LocalDate.of(2026, 7, 1)
    private fun detect(tx: List<ParsedTransaction>, unlikely: Boolean = false) = RecurringDetector.detect(tx, today, "EUR", unlikely)

    // ---------- traps: things that repeat but are not subscriptions ----------

    @Test fun weeklyGroceriesAtKnownStoreAreNotSubscriptions() {
        val rows = (0 until 40).map { tx(LocalDate.of(2026, 1, 3).plusWeeks(it.toLong()).toString(), "-54.90", "REMA 1000 STORE 4412 OSLO") }
        assertNull(detect(rows).find("rema"))
    }

    @Test fun weeklyGroceriesWithUnknownNameAndIdenticalAmountAreNotSubscriptions() {
        val rows = (0 until 30).map { tx(LocalDate.of(2026, 1, 3).plusWeeks(it.toLong()).toString(), "-32.00", "FRISK MAT AS 7712") }
        val c = detect(rows, unlikely = true).find("frisk")
        assertTrue(c == null || c.kind == CandidateKind.UNLIKELY)
    }

    @Test fun fixedPriceLunchHabitIsNotASubscription() {
        val rnd = java.util.Random(7)
        val rows = ArrayList<ParsedTransaction>()
        var d = LocalDate.of(2026, 1, 5)
        while (d.isBefore(LocalDate.of(2026, 4, 1))) {
            if (d.dayOfWeek.value <= 5 && rnd.nextInt(10) < 6) rows.add(tx(d.toString(), "-12.50", "DELI LUNCH BAR"))
            d = d.plusDays(1)
        }
        val c = detect(rows, unlikely = true).find("deli")
        assertTrue("lunch habit must not be a candidate: $c", c == null || c.kind == CandidateKind.UNLIKELY)
    }

    @Test fun lunchHabitAtUnknownPlaceWithSameAmountEveryWeekdayIsNotASubscription() {
        val rows = ArrayList<ParsedTransaction>()
        var d = LocalDate.of(2026, 1, 5)
        while (d.isBefore(LocalDate.of(2026, 4, 1))) { if (d.dayOfWeek.value <= 5) rows.add(tx(d.toString(), "-9.00", "HJORTEN SPISESTED")); d = d.plusDays(1) }
        val c = detect(rows, unlikely = true).find("hjorten")
        assertTrue(c == null || c.kind == CandidateKind.UNLIKELY)
    }

    @Test fun irregularVariableOnlineShoppingIsNotACandidate() {
        val dates = listOf("2026-01-04", "2026-01-19", "2026-02-27", "2026-03-02", "2026-04-18", "2026-05-30")
        val amts = listOf("-23.10", "-89.00", "-12.99", "-45.50", "-210.00", "-8.40")
        val rows = dates.zip(amts).map { (d, a) -> tx(d, a, "AMAZON MKTPLACE EU") }
        assertNull(detect(rows).find("amazon"))
    }

    @Test fun incomeIsIgnored() {
        val rows = monthly("2026-01", 6, 25, "3200.00", "SALARY ACME CORP")
        assertTrue(detect(rows, unlikely = true).isEmpty())
    }

    // ---------- bills ----------

    @Test fun mortgageIsABillNotASubscription() {
        val rows = monthly("2025-07", 12, 1, "-1234.50", "HOME LOAN 2231 NORDEA MORTGAGE")
        val c = detect(rows).find("home")!!
        assertEquals(CandidateKind.BILL, c.kind)
        assertEquals(BillingCycle.MONTHLY, c.cycle)
    }

    @Test fun rentWithoutKeywordButLargeFixedAmountIsABill() {
        val c = detect(monthly("2025-07", 12, 1, "-1200.00", "OSLO EIENDOM AS 8812")).find("oslo")!!
        assertEquals(CandidateKind.BILL, c.kind)
        assertTrue(c.evidence.any { it.type == EvidenceType.LARGE_FIXED_AMOUNT })
    }

    @Test fun norwegianKronerThresholdIsScaled() {
        // 400 NOK/month is a normal subscription-sized charge, not a "large fixed" bill.
        val c = detect(monthly("2025-07", 8, 5, "-399.00", "TRENING PLUSS", cur = "NOK")).find("trening")!!
        assertEquals(CandidateKind.SUBSCRIPTION, c.kind)
    }

    @Test fun insuranceKeywordInSeveralLanguagesIsABill() {
        for (word in listOf("FORSIKRING", "VERSICHERUNG", "VERZEKERING", "SEGURO", "ASSICURAZIONE", "INSURANCE")) {
            val c = detect(monthly("2025-10", 8, 15, "-45.00", "$word FOLKSAM 9988")).find("")!!
            assertEquals("$word", CandidateKind.BILL, c.kind)
        }
    }

    // ---------- genuine subscriptions ----------

    @Test fun weekendShiftedMonthlyChargesAreRecognised() {
        val c = detect(monthly("2024-01", 30, 1, "-29.00", "GYM CITY MEMBERSHIP")).find("gym")!!
        assertEquals(BillingCycle.MONTHLY, c.cycle)
        assertEquals(CandidateKind.SUBSCRIPTION, c.kind)
        assertEquals(ConfidenceLevel.HIGH, c.confidence)
        assertFalse(c.unconfirmed)
    }

    @Test fun priceHikeKeepsTheSeriesAndReportsTheChange() {
        val rows = monthly("2025-01", 8, 3, "-11.99", "STREAMFLIX.COM 4412") + monthly("2025-09", 6, 3, "-13.99", "STREAMFLIX.COM 8801")
        val c = detect(rows).find("streamflix")!!
        assertEquals(BigDecimal("13.99"), c.amount)
        assertEquals(BigDecimal("11.99") to BigDecimal("13.99"), c.priceChange)
        assertEquals(14, c.occurrences)
    }

    @Test fun aMissedMonthDoesNotBreakDetection() {
        val rows = monthly("2025-01", 10, 10, "-7.99", "FOCUSAPP PRO").filterIndexed { i, _ -> i != 4 }
        val c = detect(rows).find("focusapp")!!
        assertEquals(BillingCycle.MONTHLY, c.cycle)
        assertTrue(c.score > 0.7)
    }

    @Test fun quarterlyAndYearlyCyclesAreDetected() {
        val q = (0 until 6).map { tx(LocalDate.of(2025, 1, 10).plusMonths(3L * it).toString(), "-24.00", "NEWSDAILY") }
        assertEquals(BillingCycle.QUARTERLY, detect(q).find("newsdaily")!!.cycle)
        val y = listOf(tx("2024-03-12", "-99.00", "CLOUDSTORE ANNUAL PLAN"), tx("2025-03-12", "-99.00", "CLOUDSTORE ANNUAL PLAN"), tx("2026-03-12", "-99.00", "CLOUDSTORE ANNUAL PLAN"))
        val c = detect(y).find("cloudstore")!!
        assertEquals(BillingCycle.YEARLY, c.cycle)
        assertEquals(LocalDate.of(2027, 3, 12), c.nextRenewal)
    }

    @Test fun oddFxOneOffDoesNotDisturbTheSeries() {
        val rows = listOf(tx("2025-03-12", "-99.00", "CLOUDSTORE ANNUAL PLAN"), tx("2025-06-18", "-9.19", "CLOUDSTORE USD 9.99 (FX 0.92)"), tx("2026-03-12", "-99.00", "CLOUDSTORE ANNUAL PLAN"))
        val c = detect(rows).find("cloudstore")!!
        assertEquals(BillingCycle.YEARLY, c.cycle)
        assertEquals(BigDecimal("99.00"), c.amount)
    }

    @Test fun nextRenewalIsInTheFutureAndOnTheCycle() {
        val c = detect(monthly("2025-01", 18, 20, "-9.99", "MUSICBOX PREMIUM", weekendShift = false)).find("musicbox")!!
        assertFalse(c.nextRenewal.isBefore(today))
        assertTrue(ChronoUnit.DAYS.between(today, c.nextRenewal) <= 31)
        assertEquals(20, c.nextRenewal.dayOfMonth)
    }

    @Test fun endedSubscriptionIsFlaggedAndScoredLower() {
        val ended = monthly("2025-01", 5, 5, "-9.99", "OLDSERVICE PRO") // stops in May, data continues via another merchant
        val other = monthly("2025-01", 18, 5, "-29.00", "GYM CITY MEMBERSHIP")
        val c = detect(ended + other, unlikely = true).find("oldservice")!!
        assertTrue(c.possiblyEnded)
        assertTrue(c.evidence.any { it.type == EvidenceType.ENDED })
    }

    // ---------- little history ----------

    @Test fun oneMonthKnownServiceIsUnconfirmedButSuggested() {
        val rows = listOf(tx("2026-06-03", "-12.99", "NETFLIX.COM 8812"), tx("2026-06-05", "-34.50", "REMA 1000 4412"), tx("2026-06-11", "-8.00", "KIOSK 11"))
        val c = detect(rows)
        val n = c.find("netflix")!!
        assertTrue(n.unconfirmed)
        assertTrue(n.score < 0.7)
        assertEquals(1, n.occurrences)
        assertTrue(n.evidence.any { it.type == EvidenceType.KNOWN_SERVICE })
        assertNull(c.find("rema"))
    }

    @Test fun oneMonthKeywordInManyLanguagesIsSuggested() {
        for (w in listOf("ABONNEMENT", "MITGLIEDSBEITRAG", "ABBONAMENTO", "SUBSCRIPTION", "MEDLEMSKAP", "SUSCRIPCION", "LIDMAATSCHAP")) {
            val c = detect(listOf(tx("2026-06-03", "-19.00", "ACMEKLUBB $w"))).find("acmeklubb")
            assertNotNull(w, c)
            assertTrue(w, c!!.unconfirmed)
        }
    }

    @Test fun oneMonthPlainPurchaseIsNotSuggested() {
        assertTrue(detect(listOf(tx("2026-06-03", "-19.00", "HARDWARE STORE NYC"), tx("2026-06-04", "-45.00", "BOOKSHOP LTD"))).isEmpty())
    }

    @Test fun twoMonthsFixedAmountIsUnconfirmedAndCapped() {
        val c = detect(monthly("2026-05", 2, 5, "-29.00", "FITZONE")).find("fitzone")
        assertNotNull(c)
        assertTrue(c!!.unconfirmed)
        assertTrue(c.score <= 0.6)
    }

    @Test fun moreMonthsRaiseConfidence() {
        val s2 = detect(monthly("2026-05", 2, 5, "-29.00", "FITZONE")).find("fitzone")!!.score
        val s3 = detect(monthly("2026-04", 3, 5, "-29.00", "FITZONE")).find("fitzone")!!.score
        val s6 = detect(monthly("2026-01", 6, 5, "-29.00", "FITZONE")).find("fitzone")!!.score
        assertTrue("$s2 < $s3 < $s6", s2 < s3 && s3 < s6)
    }

    @Test fun mandateAndCreditorIdAreWeakSignals() {
        val c = detect(listOf(tx("2026-06-03", "-19.00", "LASTSCHRIFT FITZONE GLAEUBIGER-ID DE98ZZZ09999999999 MANDAT 12345"))).find("fitzone")
        assertNotNull(c)
        assertTrue(c!!.score < 0.5)
        assertTrue(c.evidence.any { it.type == EvidenceType.MANDATE_ID })
    }

    @Test fun differentCurrenciesAreNotMixed() {
        val rows = monthly("2025-10", 6, 5, "-9.99", "SOMESAAS PRO", cur = "USD") + listOf(tx("2026-02-14", "-4.00", "SOMESAAS PRO", "EUR"))
        val c = detect(rows).find("somesaas")!!
        assertEquals("USD", c.currency)
    }

    @Test fun emptyAndTinyInputsDoNotCrash() {
        assertTrue(detect(emptyList()).isEmpty())
        assertTrue(detect(listOf(tx("2026-01-01", "0.00", "TRIAL"))).isEmpty())
        detect(listOf(tx("2026-01-01", "-1.00", "")))
        detect(listOf(tx("2026-01-01", "-1.00", "12345 67 89")))
    }
}
