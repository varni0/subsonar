package app.subsonar.premium

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Computes entitlements from [FeatureFlags] + purchase state.
 *
 * v1: no billing library. [setPremium] is the hook a future BillingRepository (Google Play
 * Billing Library) calls after querying/acknowledging purchases. See MONETIZATION.md.
 */
class PremiumManager(private val flags: FeatureFlags = FeatureFlags()) : Entitlements {

    private val _state = MutableStateFlow(compute(isPremium = false))
    override val state: StateFlow<EntitlementState> = _state.asStateFlow()

    /** Called by the (future) billing layer whenever purchase status changes. */
    fun setPremium(isPremium: Boolean) {
        _state.value = compute(isPremium)
    }

    private fun compute(isPremium: Boolean): EntitlementState {
        if (!flags.monetizationEnabled || isPremium) {
            return EntitlementState(
                isPremium = isPremium,
                monetizationActive = flags.monetizationEnabled,
                unlockedFeatures = PremiumFeature.entries.toSet(),
                showAds = false,
                subscriptionLimit = Int.MAX_VALUE,
            )
        }
        // Monetized, free user. Decide here what stays free.
        return EntitlementState(
            isPremium = false,
            monetizationActive = true,
            unlockedFeatures = FREE_TIER,
            showAds = flags.adsEnabled,
            subscriptionLimit = flags.freeSubscriptionLimit,
        )
    }

    companion object {
        /** Features that remain free after monetization launches. */
        val FREE_TIER: Set<PremiumFeature> = setOf(PremiumFeature.CSV_EXPORT)
    }
}
