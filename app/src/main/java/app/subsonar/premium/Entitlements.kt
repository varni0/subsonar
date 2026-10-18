package app.subsonar.premium

import kotlinx.coroutines.flow.StateFlow

/**
 * Features that are candidates for a paid tier. In v1 they are all unlocked.
 * Only features marked (exists) are implemented today; the rest are roadmap items
 * that should be built behind [Entitlements.isUnlocked] from day one.
 */
enum class PremiumFeature {
    /** More than [FeatureFlags.freeSubscriptionLimit] tracked subscriptions. (exists) */
    UNLIMITED_SUBSCRIPTIONS,
    /** CSV export of all data. (exists — candidate to keep free as a trust feature) */
    CSV_EXPORT,
    /** Encrypted cloud backup / multi-device sync (needs INTERNET + backend). (roadmap) */
    CLOUD_BACKUP,
    /** Home-screen widget with upcoming renewals / monthly total. (roadmap) */
    HOME_SCREEN_WIDGET,
    /** Extra colour themes, AMOLED black, custom accent. (roadmap) */
    CUSTOM_THEMES,
    /** Per-subscription reminder times, multiple reminders, trial-ending alerts. (roadmap) */
    ADVANCED_REMINDERS,
    /**
     * Import of bank statements (CSV / CAMT.053 / MT940) with recurring-charge detection. (exists)
     * Candidate for the paid tier: it is the most differentiating feature. Deliberately unlocked for now;
     * gate it by leaving it out of PremiumManager.FREE_TIER (the UI already asks [Entitlements.isUnlocked]).
     */
    BANK_IMPORT,
    /** Spending history charts, currency conversion, forecasts. (roadmap) */
    ADVANCED_INSIGHTS,
}

data class EntitlementState(
    /** User has an active paid subscription / lifetime unlock. */
    val isPremium: Boolean,
    /** Whether the app is in monetized mode at all (false = early-access, all free). */
    val monetizationActive: Boolean,
    val unlockedFeatures: Set<PremiumFeature>,
    /** Show banner/native ads (only ever true for non-premium users in monetized mode). */
    val showAds: Boolean,
    /** Max subscriptions allowed; Int.MAX_VALUE = unlimited. */
    val subscriptionLimit: Int,
)

/**
 * The only API UI code should use to ask "may the user do X?" / "should I show an ad?".
 * Swap the implementation (see [PremiumManager]) when Google Play Billing is added.
 */
interface Entitlements {
    val state: StateFlow<EntitlementState>

    fun isUnlocked(feature: PremiumFeature): Boolean = feature in state.value.unlockedFeatures

    fun canAddSubscription(currentCount: Int): Boolean = currentCount < state.value.subscriptionLimit
}
