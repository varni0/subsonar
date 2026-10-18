package app.subsonar.domain.recurring

import app.subsonar.data.BillingCycle
import app.subsonar.domain.importer.HeaderHints
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A subscription the user already tracks (used to avoid suggesting it again). [id], [price], [currency] and [cycle] are only
 * needed for price-change offers; leave them out and no offer is made.
 */
data class ExistingSubscription(
    val name: String,
    val importKey: String? = null,
    val id: Long = 0,
    val price: BigDecimal? = null,
    val currency: String? = null,
    val cycle: BillingCycle? = null,
)

/** "Your price for [subscriptionName] is [oldPrice], the bank statements show [newPrice] since [effectiveFrom]." */
data class PriceUpdateOffer(
    val subscriptionId: Long,
    val subscriptionName: String,
    val oldPrice: BigDecimal,
    val newPrice: BigDecimal,
    val currency: String,
    val cycle: BillingCycle,
    val effectiveFrom: LocalDate,
)

data class SuggestionGroups(
    /** Probably subscriptions, best first. */
    val likely: List<RecurringCandidate>,
    /** Mortgage, rent, loans, insurance and other fixed bills. */
    val bills: List<RecurringCandidate>,
    /** Weak evidence; shown last and never pre-selected. */
    val low: List<RecurringCandidate>,
)

/** Turns detector output into what the review screen shows. Pure Kotlin. */
object Suggestions {
    const val LIKELY_MIN_SCORE = 0.5

    fun group(candidates: List<RecurringCandidate>): SuggestionGroups {
        val shown = candidates.filter { it.kind != CandidateKind.UNLIKELY }
        return SuggestionGroups(
            likely = shown.filter { it.kind == CandidateKind.SUBSCRIPTION && it.score >= LIKELY_MIN_SCORE }.sortedByDescending { it.score },
            bills = shown.filter { it.kind == CandidateKind.BILL }.sortedByDescending { it.score },
            low = shown.filter { it.kind == CandidateKind.SUBSCRIPTION && it.score < LIKELY_MIN_SCORE }.sortedByDescending { it.score },
        )
    }

    private fun firstWord(s: String) = HeaderHints.normalize(s).split(' ', '-', '.').firstOrNull { it.isNotBlank() }.orEmpty()

    /** The tracked subscription that [c] (very probably) is: same import key first, then same name, then same first word. */
    fun matchExisting(c: RecurringCandidate, existing: List<ExistingSubscription>): ExistingSubscription? {
        val candName = HeaderHints.normalize(c.displayName).replace(" ", "")
        val candFirst = firstWord(c.displayName)
        return existing.firstOrNull { it.importKey == c.key }
            ?: existing.firstOrNull { HeaderHints.normalize(it.name).replace(" ", "") == candName }
            ?: existing.firstOrNull { candFirst.length >= 3 && firstWord(it.name) == candFirst }
    }

    /** True when [c] is (very probably) already in [existing]: same import key, same name, or same first word. */
    fun isDuplicate(c: RecurringCandidate, existing: List<ExistingSubscription>): Boolean = matchExisting(c, existing) != null

    /**
     * Offer to record a price change found in the bank data for a subscription that is already tracked: the candidate has a
     * (steady) price step, the tracked subscription has the same currency and billing cycle, and its stored price differs from the
     * price now being charged. The offer is made for increases and decreases alike.
     */
    fun priceUpdateOffer(c: RecurringCandidate, existing: List<ExistingSubscription>): PriceUpdateOffer? {
        val date = c.priceChangeDate ?: return null
        if (c.priceChange == null || c.variableAmount) return null
        val e = matchExisting(c, existing) ?: return null
        val stored = e.price ?: return null
        if (e.id == 0L || e.cycle != c.cycle || !c.currency.equals(e.currency, ignoreCase = true)) return null
        if (stored.compareTo(c.amount) == 0) return null
        return PriceUpdateOffer(e.id, e.name, stored, c.amount, c.currency.uppercase(), c.cycle, date)
    }

    /** Pre-ticked in the review: confident subscriptions that are not already tracked. */
    fun preselected(c: RecurringCandidate, existing: List<ExistingSubscription>): Boolean =
        c.kind == CandidateKind.SUBSCRIPTION && c.score >= LIKELY_MIN_SCORE && !isDuplicate(c, existing)
}
