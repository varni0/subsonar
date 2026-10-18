package app.subsonar.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumManagerTest {
    @Test fun growthModeUnlocksEverythingWithoutAds() {
        val pm = PremiumManager(FeatureFlags(monetizationEnabled = false, adsEnabled = true, freeSubscriptionLimit = 3))
        PremiumFeature.entries.forEach { assertTrue(pm.isUnlocked(it)) }
        assertFalse(pm.state.value.showAds)
        assertTrue(pm.canAddSubscription(10_000))
    }

    @Test fun monetizedFreeUserIsLimitedAndSeesAds() {
        val pm = PremiumManager(FeatureFlags(monetizationEnabled = true, adsEnabled = true, freeSubscriptionLimit = 3))
        assertFalse(pm.isUnlocked(PremiumFeature.CLOUD_BACKUP))
        assertTrue(pm.isUnlocked(PremiumFeature.CSV_EXPORT))
        assertTrue(pm.state.value.showAds)
        assertTrue(pm.canAddSubscription(2))
        assertFalse(pm.canAddSubscription(3))
    }

    @Test fun premiumUserUnlocksAllAndHidesAds() {
        val pm = PremiumManager(FeatureFlags(monetizationEnabled = true, adsEnabled = true, freeSubscriptionLimit = 3))
        pm.setPremium(true)
        assertTrue(pm.isUnlocked(PremiumFeature.CLOUD_BACKUP))
        assertFalse(pm.state.value.showAds)
        assertEquals(Int.MAX_VALUE, pm.state.value.subscriptionLimit)
    }
}
