package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.domain.importer.Coverage
import app.subsonar.domain.recurring.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class CoverageAndSuggestionsTest {
    private fun c(key: String, name: String, kind: CandidateKind, score: Double) = RecurringCandidate(
        key, name, kind, score, ConfidenceLevel.MEDIUM, false, BillingCycle.MONTHLY, BigDecimal("9.99"), "EUR", false, 6,
        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 1), LocalDate.of(2025, 7, 1), false, null, Category.OTHER, emptyList(),
    )

    @Test fun rangesGroupConsecutiveMonths() {
        val cov = Coverage.of(listOf("2025-01-03", "2025-02-10", "2025-03-31", "2025-06-01", "2025-06-30", "2026-01-01").map(LocalDate::parse))
        assertEquals(listOf(YearMonth.of(2025, 1)..YearMonth.of(2025, 3), YearMonth.of(2025, 6)..YearMonth.of(2025, 6), YearMonth.of(2026, 1)..YearMonth.of(2026, 1)), cov.ranges)
        assertEquals(8, cov.gaps.size)
        assertEquals(YearMonth.of(2025, 4), cov.gaps.first())
    }
    @Test fun emptyCoverage() { assertTrue(Coverage.EMPTY.isEmpty); assertTrue(Coverage.EMPTY.ranges.isEmpty()); assertTrue(Coverage.EMPTY.gaps.isEmpty()) }

    @Test fun groupingPutsLikelySubscriptionsFirstBillsSeparateAndLowLast() {
        val g = Suggestions.group(listOf(
            c("a", "A", CandidateKind.SUBSCRIPTION, 0.9), c("b", "B", CandidateKind.SUBSCRIPTION, 0.95), c("rent", "Rent", CandidateKind.BILL, 0.8),
            c("weak", "Weak", CandidateKind.SUBSCRIPTION, 0.35), c("no", "No", CandidateKind.UNLIKELY, 0.0),
        ))
        assertEquals(listOf("b", "a"), g.likely.map { it.key }); assertEquals(listOf("rent"), g.bills.map { it.key }); assertEquals(listOf("weak"), g.low.map { it.key })
    }
    @Test fun duplicatesOfExistingSubscriptionsAreRecognised() {
        val netflix = c("netflix", "Netflix", CandidateKind.SUBSCRIPTION, 0.9)
        assertTrue(Suggestions.isDuplicate(netflix, listOf(ExistingSubscription("NETFLIX Premium"))))
        assertTrue(Suggestions.isDuplicate(netflix, listOf(ExistingSubscription("something", "netflix"))))
        assertTrue(Suggestions.isDuplicate(c("gym city", "Gym City", CandidateKind.SUBSCRIPTION, .9), listOf(ExistingSubscription("gym city"))))
        assertFalse(Suggestions.isDuplicate(netflix, listOf(ExistingSubscription("Spotify"), ExistingSubscription("Net"))))
        assertFalse(Suggestions.isDuplicate(netflix, emptyList()))
        assertFalse(Suggestions.preselected(netflix, listOf(ExistingSubscription("Netflix"))))
        assertTrue(Suggestions.preselected(netflix, emptyList()))
        assertFalse(Suggestions.preselected(c("rent", "Rent", CandidateKind.BILL, .9), emptyList()))
        assertFalse(Suggestions.preselected(c("weak", "Weak", CandidateKind.SUBSCRIPTION, .3), emptyList()))
    }
}
