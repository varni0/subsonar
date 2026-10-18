package app.subsonar.premium

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Placeholder where a banner ad will go. Renders nothing in v1.
 * When AdMob is added, render an AdView here only when `entitlements.state.showAds` is true.
 * Call sites already exist in the list and insights screens, so no layout work is needed later.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun AdSlot(showAds: Boolean, modifier: Modifier = Modifier) {
    if (!showAds) return
    // TODO(monetization): AndroidView { AdView(it).apply { adUnitId = ...; setAdSize(...); loadAd(...) } }
}
