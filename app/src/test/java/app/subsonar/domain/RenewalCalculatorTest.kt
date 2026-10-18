package app.subsonar.domain

import app.subsonar.data.BillingCycle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RenewalCalculatorTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun futureAnchorIsReturnedAsIs() {
        assertEquals(d("2026-12-01"), RenewalCalculator.nextRenewal(d("2026-12-01"), BillingCycle.MONTHLY, d("2026-10-09")))
    }

    @Test fun anchorTodayCountsAsToday() {
        assertEquals(d("2026-10-09"), RenewalCalculator.nextRenewal(d("2026-10-09"), BillingCycle.WEEKLY, d("2026-10-09")))
    }

    @Test fun weeklyRollsForward() {
        // 2026-10-01 + 7 = 10-08 (past), + 14 = 10-15
        assertEquals(d("2026-10-15"), RenewalCalculator.nextRenewal(d("2026-10-01"), BillingCycle.WEEKLY, d("2026-10-09")))
    }

    @Test fun monthlyKeepsMonthEndWithoutDrift() {
        val anchor = d("2026-01-31")
        assertEquals(d("2026-02-28"), RenewalCalculator.nextRenewal(anchor, BillingCycle.MONTHLY, d("2026-02-10")))
        assertEquals(d("2026-03-31"), RenewalCalculator.nextRenewal(anchor, BillingCycle.MONTHLY, d("2026-03-01")))
    }

    @Test fun quarterlyAndYearly() {
        assertEquals(d("2026-10-15"), RenewalCalculator.nextRenewal(d("2026-01-15"), BillingCycle.QUARTERLY, d("2026-10-09")))
        assertEquals(d("2028-02-29"), RenewalCalculator.nextRenewal(d("2024-02-29"), BillingCycle.YEARLY, d("2027-03-01")))
    }

    @Test fun veryOldAnchor() {
        assertEquals(d("2026-10-20"), RenewalCalculator.nextRenewal(d("2001-10-20"), BillingCycle.MONTHLY, d("2026-10-09")))
    }

    @Test fun daysUntil() {
        assertEquals(3, RenewalCalculator.daysUntil(d("2026-10-12"), d("2026-10-09")))
    }
}
