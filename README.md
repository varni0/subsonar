# Subsonar — subscription & bill tracker for Android

Subsonar tracks recurring payments (streaming, apps, gym, phone, insurance…), shows what they
cost per month/year, and reminds you before each one renews. Fully offline: no internet
permission, no account, no ads, no analytics.

Package / applicationId: `app.subsonar` (v1.1.0, versionCode 2). Formerly “SubPilot” (`com.subpilot.app`, never
published); the project folder is still called `subpilot`. The database file is `subsonar.db`.

## Features
- Add / edit / delete subscriptions: name, price, currency, cycle (weekly / monthly / quarterly / yearly),
  next renewal date, category, notes, per-item reminder toggle
- List sorted by next renewal with “renews in N days” (highlighted when ≤ 3 days)
- Monthly + yearly totals, per-currency totals, per-category breakdown, “due in next 30 days”
- Renewal reminders: daily WorkManager job at ~09:00, 1/2/3/5/7 days ahead, each renewal notified once,
  catches up if a day was missed; Android 13+ notification permission prompt + “blocked” hint in Settings
- Own visual identity (v3): calm Nordic palette — fjord blue, snow white, soft slate, cloudberry accent — as custom
  Material 3 light + dark schemes with tuned typography and shapes (`ui/theme/Theme.kt`, `Brand`). Wallpaper (dynamic)
  colours are **off by default** and still optional in Settings. Light/dark/system theme.
- Name-independent app icon: a bank statement with one highlighted recurring charge on a fjord-blue background with low
  ridges; adaptive (background + foreground) + monochrome themed icon (`res/drawable/ic_launcher_*.xml`). No text in the
  icon or brand mark, so a rename only changes strings.
- Per-subscription look: pick one of 12 palette colours and one of 31 own line icons (streaming, music, gym, cloud, phone,
  news, gaming, insurance, home, car, …) or an emoji. Smart default from name keywords in several languages
  (e.g. “Fitnessclub” → gym, “Innboforsikring” → insurance) then the category. No brand logos. Room v4 (`color_key`, `avatar`).
- Top card: yearly total (big) + monthly, “N renew this week”, “+X/yr from price hikes” (default currency, pluralized).
- Empty state leads with bank import (“Find your subscriptions from a bank export”), manual add secondary, and a
  “Nothing leaves this phone · No internet access” badge (also, subtly, in Settings and the import intro).
- CSV export via the system file picker (no storage permission; formula-injection safe)
- Android auto-backup of the subscriptions database/settings (Google account backup + device transfer)
- **Bank import** (on-device only): pick one or many bank exports (CSV, zip of CSVs, CAMT.053 XML, MT940), check/adjust
  the detected columns, and let Subsonar suggest the subscriptions and bills it finds. See [docs/IMPORT.md](docs/IMPORT.md).
- **Price-hike tracking**: every price edit is stored in a per-subscription price history (with an "applies from" date);
  the edit screen shows a timeline with total change (% and per year), the list shows a “Price up X%” badge for hikes in
  the last 12 months, Insights has a “Price increases” section (extra yearly cost, biggest hikes), and bank imports offer
  to record detected price changes. Optional price-increase notifications (Settings). Room schema v3 with tested migration.
- **19 languages** (en, de, fr, es, it, pt-PT, pt-BR, pl, nl, nb, sv, da, fi, cs, ro, el, hu, uk, ru, tr) with an in-app
  language picker (per-app language API, “System default” option, Android 13+ system “App languages” supported).
  Numbers, currencies, dates and notifications follow the chosen language. Translations are machine-generated and need native
  review — see [docs/LOCALIZATION.md](docs/LOCALIZATION.md).

## Tech
Kotlin · Jetpack Compose · Material 3 · Room (schema exported to `app/schemas`) · DataStore · WorkManager ·
MVVM with a tiny manual DI container (`AppContainer`) · minSdk 26 · compile/targetSdk 37 (Android 17) ·
AGP 9.4.1 · Gradle 9.8.1 · JDK 17.

```
app/src/main/java/app/subsonar/
  data/        Room entity/DAO/DB, SettingsRepository (DataStore)
  data/imports Separate Room database (history.db): imported transactions, import records, format fingerprints
  domain/      RenewalCalculator, SpendCalculator, PriceHistoryCalculator, CsvExporter   (pure, unit-tested)
  domain/importer   Encoding/delimiter/header detection, date+number parsing, column inference, CAMT.053, MT940, dedupe
  domain/recurring  Recurring-charge detector (merchant grouping, cycle/amount scoring, vocabulary), suggestion grouping
  ui/imports   Import flow: file picker -> preview/adjust columns -> review suggestions
  premium/     Entitlements, PremiumManager, FeatureFlags, AdSlot (monetization seam)
  reminders/   ReminderScheduler, ReminderWorker, ReminderPolicy, ReminderNotifier
  ui/          MainActivity, Navigation, ViewModels, screens (list, edit, insights, settings), theme
```

Renewal dates are stored as an *anchor* and the upcoming date is always computed from it, so month-end
dates never drift (Jan 31 → Feb 28 → Mar 31) and nothing needs rewriting in the background.

## Build
Toolchain on the box: `/opt/tools/jdk-17.0.20.1+1`, `/opt/tools/android-sdk` (env vars are in `~/.bashrc`).

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk (id app.subsonar.debug)
./gradlew testDebugUnitTest lint   # ~300 unit tests (incl. Robolectric) + Android lint
./gradlew assembleRelease          # signed, R8-minified APK
./gradlew bundleRelease            # signed AAB for Google Play
```

Release signing reads `../subsonar-keystore/keystore.properties`, falling back to the pre-rename `../subpilot-keystore/keystore.properties` (same upload key; outside the repo, git-ignored). Override with `-PsubsonarKeystoreProps=/path`.
Without it, release builds are produced unsigned.

## Releasing an update
1. Bump `versionCode` (+1 every upload) and `versionName` in `app/build.gradle.kts`.
2. If you changed a Room entity: bump that DB's `version` and add a migration (see `AppDatabase.kt`; `AppDatabase` is at v4, `ImportDatabase` at v1) and a test in `DatabaseMigrationTest`.
3. `./gradlew testDebugUnitTest lint bundleRelease` → upload `app-release.aab` in Play Console.

## Brand and store assets
- `tools/brand/line_icons.py` regenerates the 31 `ic_sub_*.xml` line icons (keys must match `SubscriptionVisuals.ICONS`; a test checks it).
- `tools/brand/vd2svg.py` renders the app's vector drawables (used so the Play icon is drawn from the launcher icon itself).
- Play Store assets (framed 1080×1920 screenshots in en/nb/de, 512×512 icon, 1024×500 feature graphics):
  ```bash
  ./gradlew testDebugUnitTest --tests '*StoreScreenshotsTest*' -Pstore.shots=/workspace/store-assets-v2/raw -Pstore.name=Subsonar
  python3 tools/store-assets/generate.py --name Subsonar --raw /workspace/store-assets-v2/raw --out /workspace/store-assets-v2
  ```
  `StoreScreenshotsTest` is opt-in (skipped without `-Pstore.shots`) and uses fictional services and prices. The in-app
  wordmark reads `app_name`; renders can override it with `-Pstore.name` (via `LocalProductName`). Needs `pip install cairosvg`.

## Monetization
Everything is free and ad-free now. See [MONETIZATION.md](MONETIZATION.md) for the switch-over plan and
[docs/PLAY_STORE_LISTING.md](docs/PLAY_STORE_LISTING.md) / [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)
for launch material.

## Bank import (summary)
Everything runs on the phone; there is still no INTERNET permission. Imported history lives in its own database
(`history.db`) which is **not** in the Android backup / device-transfer rules, while subscriptions stay backed up.
Settings has “Delete imported history” and shows which months are covered. The feature is registered as
`PremiumFeature.BANK_IMPORT` (a paid-tier candidate) and is unlocked while `MONETIZATION_ENABLED=false`.
Details, test results and known limitations: [docs/IMPORT.md](docs/IMPORT.md).

Test corpus: `app/src/test/resources/csv-samples` holds the permissively licensed and synthetic sample files
(sources/licences in `SOURCES.csv` and `_licenses/`). GPL-licensed samples are **not** in the repo; if you have the full
corpus, point `-PcsvSamples.dir=/path/to/csv-samples` at it (default `/workspace/csv-samples`), otherwise those tests are
skipped. Reports are written to `app/build/reports/csv-eval/`. Import screenshots (Robolectric, no emulator):
`./gradlew testDebugUnitTest --tests '*ImportScreenshotsTest*' -Pshots.out=/some/dir`.
