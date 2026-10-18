package app.subsonar.premium

import app.subsonar.BuildConfig

/**
 * Single place that decides whether the app is in "growth" mode (everything free, no ads)
 * or "monetized" mode. Values come from BuildConfig fields in app/build.gradle.kts, so flipping
 * monetization on is a one-line change per flag. Later these can be backed by Remote Config
 * (requires adding INTERNET permission) without touching call sites.
 */
data class FeatureFlags(
    val monetizationEnabled: Boolean = BuildConfig.MONETIZATION_ENABLED,
    val adsEnabled: Boolean = BuildConfig.ADS_ENABLED,
    val freeSubscriptionLimit: Int = BuildConfig.FREE_SUBSCRIPTION_LIMIT,
)
