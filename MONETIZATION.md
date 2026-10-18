# Monetization plan

v1 is free, ad-free and offline to grow users and reviews. The code already has the seams needed
to turn on a paid tier and/or ads later without restructuring:

| Seam | Where | What it does today |
|---|---|---|
| `FeatureFlags` | `premium/FeatureFlags.kt`, values from `buildConfigField`s in `app/build.gradle.kts` | `MONETIZATION_ENABLED=false`, `ADS_ENABLED=false`, `FREE_SUBSCRIPTION_LIMIT=10` |
| `Entitlements` interface | `premium/Entitlements.kt` | Single API for “is feature X unlocked?”, “can add another item?”, “show ads?” |
| `PremiumManager` | `premium/PremiumManager.kt` | Implements `Entitlements`; everything unlocked while monetization is off. `setPremium(true/false)` is the hook for billing |
| `PremiumFeature` enum | same file | Premium candidates: `UNLIMITED_SUBSCRIPTIONS`, `CLOUD_BACKUP`, `HOME_SCREEN_WIDGET`, `CUSTOM_THEMES`, `ADVANCED_REMINDERS`, `ADVANCED_INSIGHTS` (`CSV_EXPORT` stays free) |
| `AdSlot` composable | `premium/AdSlot.kt` | Already placed in list + insights screens; renders nothing until ads are on |
| Item limit + paywall hook | `EditViewModel.save()` / `EditScreen` “limit reached” dialog | Never triggers while monetization is off |
| Plan row | Settings → About → Plan | Shows “Early access: every feature is free”; becomes the paywall entry point |

## Recommended model
**Freemium subscription, ads optional and only for free users.**
- Free: up to 10 subscriptions, reminders, totals, CSV export.
- **Subsonar Pro** — suggested €1.99/month or €9.99/year (+ optional €24.99 lifetime one-time product):
  unlimited items, home-screen widget, cloud backup/sync, extra themes, multiple reminders/trial alerts, history charts.
- Grandfather early users: anyone who installed before monetization keeps unlimited items (store a
  `firstInstallVersionCode` / install date in DataStore before shipping the paid version and check it in `PremiumManager`).
  This avoids 1-star “bait-and-switch” reviews.
- Ads: a single small banner on list/insights for free users only, never on the edit screen. A tracker app
  earns little from ads; treat them as a nudge toward Pro, not the main income.

Ship Pro features first (widget + backup), then turn on the limit. Never take away something users already have.

## Step 1 — Google Play Billing (subscription)
1. Play Console → your app → **Monetize → Products → Subscriptions** → create `subsonar_pro` with base plans
   `monthly` (auto-renewing, 1 month) and `yearly` (1 year). Optionally add a 7-day free-trial offer.
   (Products can only be created after an AAB containing the billing permission has been uploaded to any track.)
2. Play Console → **Setup → License testing**: add your Gmail so test purchases are free.
3. Add the dependency (check latest at developer.android.com/google/play/billing/release-notes; 9.1.0 as of Oct 2026):
   ```kotlin
   implementation("com.android.billingclient:billing-ktx:9.1.0")
   ```
   The library adds the `com.android.vending.BILLING` permission automatically (and INTERNET).
4. Create `premium/BillingRepository.kt`:
   - Build `BillingClient` with `enablePendingPurchases(...)` and a `PurchasesUpdatedListener`; connect on app start
     (from `AppContainer`), reconnect on disconnect.
   - `queryProductDetails` for `subsonar_pro` → expose prices/offers to a `PaywallScreen`.
   - `launchBillingFlow(activity, params)` from the paywall.
   - On purchase `PURCHASED`: **acknowledge within 3 days** (`acknowledgePurchase`) or Google refunds it.
   - On every start and on resume: `queryPurchasesAsync(SUBS)` → `premiumManager.setPremium(hasActivePro)`.
   - Cache the last known state in DataStore so Pro works offline.
5. Add `PaywallScreen` (Compose) listing benefits + monthly/yearly buttons + “Restore purchases” (= re-query) and a
   link to Play's subscription management page. Open it from: Settings → Plan row, the limit dialog in `EditScreen`,
   and any locked feature.
6. Flip `MONETIZATION_ENABLED` to `true` in `app/build.gradle.kts`, bump `versionCode`.
7. Test with license testers on an internal-testing track (test cards: approve, decline, slow). Test cancel,
   resubscribe, grace period, account hold and refunds.
8. Optional but recommended later: server-side verification with Real-time Developer Notifications. Not required
   for a small app; client-side verification is acceptable to start.

Google's service fee: 15% on auto-renewing subscriptions; for one-time products 15% on the first US$1M/year of
earnings (after enrolling in the 15% tier), 30% above that. Check Play's current fee page before pricing. Set up a payments profile + tax info in Play Console before selling.

## Step 2 — AdMob (optional, free users only)
1. Create an AdMob account (admob.google.com), add the app (after it's on Play), create one **Banner** ad unit.
2. Dependencies:
   ```kotlin
   implementation("com.google.android.gms:play-services-ads:25.5.0")
   implementation("com.google.android.ump:user-messaging-platform:4.0.0") // GDPR/consent (required in EEA/UK — you are in Norway)
   ```
3. `AndroidManifest.xml`: add `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="ca-app-pub-XXXX~YYYY"/>`.
4. On start: request consent with UMP (`ConsentInformation.requestConsentInfoUpdate` → `loadAndShowConsentFormIfRequired`),
   then `MobileAds.initialize(context)` only if `entitlements.state.showAds`.
5. Implement `AdSlot` with `AndroidView { AdView(it).apply { adUnitId = ...; setAdSize(adaptive banner); loadAd(AdRequest.Builder().build()) } }`.
   Use Google's **test ad unit IDs** during development; never click your own live ads.
6. Set `ADS_ENABLED=true`. Ads are automatically hidden for Pro users by `PremiumManager`.
7. Add `app-ads.txt` to a website listed as your developer website in Play Console.

## Step 3 — Store/policy updates when monetizing
- Update the **privacy policy** (AdMob collects device identifiers/advertising ID; billing handled by Google).
- Update **Data safety** form (ads: device IDs, app interactions; diagnostics) and **Ads** declaration = “Contains ads”.
- Add `com.google.android.gms.permission.AD_ID` (play-services-ads adds it) and declare advertising ID use.
- Listing: Play shows “Contains ads” / “In-app purchases” badges automatically from the declarations.

## Play Store launch checklist (v1, free)
- [ ] **Google Play developer account**: one-time **US$25** fee, identity verification (ID + possibly address proof).
      Personal accounts created after Nov 2023 must run a **closed test with ≥12 testers for 14 consecutive days**
      before production access — recruit friends/family early (they just need a Google account and to opt in).
      An organisation account (needs a D-U-N-S number) skips this.
- [x] Final name chosen: **Subsonar**, `applicationId` `app.subsonar` (set before the first upload; it can never change afterwards).
- [ ] Quick trademark check for “Subsonar” (EUIPO/WIPO/Patentstyret) before launch.
- [ ] Back up `/workspace/subpilot-keystore/` (the upload key; still valid after the rename, see README there). Enrol in Play App Signing when uploading.
- [ ] Host the privacy policy (`docs/PRIVACY_POLICY.md`) at a public URL — free options: GitHub Pages, Google Sites,
      a Notion public page. Fill in your contact email first.
- [ ] Store listing (`docs/PLAY_STORE_LISTING.md`): title, short + full description, category **Finance**
      (alternative: Productivity), contact email.
- [ ] Graphics:
  - App icon **512×512 PNG** (32-bit, ≤1 MB) — export from the adaptive icon or have one designed.
  - Feature graphic **1024×500** PNG/JPG.
  - Phone screenshots: **2–8**, 16:9 or 9:16, min 320 px, max 3840 px side (recommended 1080×1920 or 1080×2400).
    Suggested set: (1) list with summary card, (2) add/edit form, (3) insights by category, (4) renewal notification,
    (5) dark mode, (6) settings/privacy. Add short captions (“Never miss a renewal”, “See your monthly total”…).
  - Optional: 7" and 10" tablet screenshots (improves tablet visibility).
- [ ] App content forms: Privacy policy URL · Ads = **No** · App access = **All functionality available without login** ·
      Content rating questionnaire (→ Everyone/PEGI 3) · Target audience **18+** (avoids Families policy) ·
      Data safety = **No data collected, no data shared** (true for v1: no network access; CSV export is user-initiated
      local file) · Financial features = **none** (it's a tracker, not a financial service) · Government app = No ·
      Health = none.
- [ ] Release: Testing → Closed testing → upload `app-release.aab`, add testers, release notes “First release”.
      After 14 days with 12 testers → apply for production → roll out (start at 20% staged rollout).
- [ ] After launch: reply to reviews, watch Android vitals (crashes/ANRs) in Play Console, ask happy users for
      reviews (later: Play In-App Review API).
