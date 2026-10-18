# Localization

Subsonar is available in **English (default) and 19 more languages**:

| Language | Resource folder | In-app picker / `locales_config.xml` tag |
|---|---|---|
| English (default) | `values/` | `en` |
| German | `values-de/` | `de` |
| French | `values-fr/` | `fr` |
| Spanish | `values-es/` | `es` |
| Italian | `values-it/` | `it` |
| Portuguese (Portugal) | `values-pt/` | `pt-PT` |
| Portuguese (Brazil) | `values-pt-rBR/` | `pt-BR` |
| Polish | `values-pl/` | `pl` |
| Dutch | `values-nl/` | `nl` |
| Norwegian Bokmål | `values-nb/` | `nb` |
| Swedish | `values-sv/` | `sv` |
| Danish | `values-da/` | `da` |
| Finnish | `values-fi/` | `fi` |
| Czech | `values-cs/` | `cs` |
| Romanian | `values-ro/` | `ro` |
| Greek | `values-el/` | `el` |
| Hungarian | `values-hu/` | `hu` |
| Ukrainian | `values-uk/` | `uk` |
| Russian | `values-ru/` | `ru` |
| Turkish | `values-tr/` | `tr` |

> **The translations are machine-generated.** They were written without native review, so wording, tone and
> terminology (for example “subscription” vs. “plan”, how a bank statement is called, formal vs. informal “you”) should
> be checked by a native speaker before or shortly after launch. The same applies to the localized Play Store texts in
> [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md). Reports and fixes are welcome: edit the `strings.xml` of the language
> and run the tests (see below).

## How language selection works

* **Default:** the app follows the device language. If the device language is not supported, English is used.
* **Android 13+ (API 33):** *Settings → System → Languages → App languages* lists Subsonar with the languages from
  [`res/xml/locales_config.xml`](../app/src/main/res/xml/locales_config.xml) (referenced from the manifest with
  `android:localeConfig`).
* **In-app picker:** *Settings → Language* in Subsonar lists the languages by their own name (Deutsch, Ελληνικά, …) plus
  **System default**. It uses `AppCompatDelegate.setApplicationLocales`, which on Android 13+ calls the platform per-app
  language API and on older versions stores the choice itself (via `AppLocalesMetadataHolderService`, declared in the
  manifest). Changing the language recreates the activity immediately; no restart is needed.
* **Bundles:** `bundle { language { enableSplit = false } }` keeps all languages in the app bundle, otherwise Play would
  only deliver the device language and the in-app picker could not switch. `res/resources.properties` declares English as
  the default resource locale so that Play lists the languages correctly.
* The activity is an `AppCompatActivity` with an AppCompat theme parent; this is required for the AndroidX per-app
  language support.

### Numbers, currencies, dates

Formatting always goes through the user's locale (`ui/Formatting.kt`): `NumberFormat.getCurrencyInstance(locale)` for
money, `getPercentInstance` for percentages, `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` for dates. For
example `1.234,50 €` (de), `1 234,50 €` (fr, with narrow no-break space), `€1,234.50` (en-US). `MainActivity` sets
`Locale.setDefault` to the resolved UI locale, so the amounts also follow the *app* language and not only the device
language. Entering prices accepts both `,` and `.` as decimal separator and groups like `1 299,50` / `1.299,50`.

### Notifications

Notification texts (renewal reminders and price-increase alerts) are created by background workers, which have no
activity. They use `AppLanguages.localizedContext`, a context configured with the chosen app language, and format
amounts and dates with that locale. Notification channel names and descriptions are localized as well (they appear in
Android's notification settings; they are re-registered whenever the language changes).

## Adding or changing a string

1. Add the English text to `app/src/main/res/values/strings.xml`. Use positional placeholders (`%1$s`, `%2$d`) whenever
   a string has more than one, so translators can reorder them. Use `<plurals>` for anything with a count.
2. Add the key to **every** `values-*/strings.xml`. A missing translation fails the build (see below).
3. Plural categories per language (CLDR) must be exactly: `one, other` for en/de/nl/nb/sv/da/fi/el/hu/tr;
   `one, many, other` for fr/es/it/pt; `one, few, other` for ro; `one, few, many, other` for pl/cs/uk/ru. For
   Romance languages `many` is the form for large numbers such as “1 million of days” (`%d de dias`).
4. Escape apostrophes (`\'`) or use the typographic `’`; `&` must be `&amp;`.
5. Run `./gradlew testDebugUnitTest lint`.

Do not translate `app_name` (`translatable="false"`).

## Adding a language

1. Create `values-<code>/strings.xml` with all keys.
2. Add the tag to `AppLanguages.SUPPORTED` (`ui/AppLanguages.kt`), add its native name if the platform does not know it,
   and add `<locale android:name="…" />` to `res/xml/locales_config.xml`.
3. Add the plural categories to `LocalizationTest.quantities` and the `folder` mapping if it is not `values-<tag>`.

## Automated checks

| Check | Where | What it catches |
|---|---|---|
| `LocalizationTest` (JVM unit test) | `app/src/test/.../ui/LocalizationTest.kt` | every language has exactly the keys of the default (no missing / extra strings or plurals); placeholders (`%1$s`, `%d`, …) match the default in type and position; correct plural categories per language; multiple placeholders are positional; no empty / padded values; at most 12 % of a language equal to English (catches copy-pasted English); `locales_config.xml` == `AppLanguages.SUPPORTED` == the `values-*` folders; manifest references the locale config |
| Android Lint, severity **error** | `app/build.gradle.kts` (`lint { error += … }`) | `MissingTranslation`, `ExtraTranslation`, `StringFormatInvalid`, `StringFormatCount`, `StringFormatMatches`, `MissingQuantity`, `UnusedQuantity` abort `./gradlew lint` and `assembleRelease` |
| `FormattingTest` | `app/src/test/.../ui/FormattingTest.kt` | locale-aware money, percent, date and price parsing (`1.234,50` / `1,234.50` / `1 299,50`) |
| `LocalizedScreenshotsTest` (Robolectric) | `app/src/test/.../ui/LocalizedScreenshotsTest.kt` | renders list, insights, edit (price history), settings and the import screens in German, Finnish, Greek, Ukrainian (and English) at 411 dp width and 1.3× font scale; fails when a text is ellipsized or a screen is blank |

The screenshots are written to `app/build/reports/l10n-shots/<language>/NN_screen.png` (override with
`-Dshots.out=DIR`). The longest languages are used on purpose: German (long compound words), Finnish (long words and
case endings), Greek and Ukrainian (non-Latin scripts, wide glyphs).

## Notes for reviewers

* “Abo” (de), “abonnement” (fr, nl, da, nb), “suscripción” (es), “subskrypcja” (pl) … is used consistently for
  *subscription*; European Portuguese uses “subscrição”, Brazilian Portuguese “assinatura”.
* Address of the user: **informal** “you” in German (du), Spanish (tú), Italian (tu), Dutch (je), Norwegian, Swedish,
  Danish, Finnish, Polish, Romanian, Hungarian and Turkish; **formal** in French (vous), Portuguese (PT and BR),
  Czech (vy), Greek, Ukrainian and Russian (formal plural). Tell us if your market expects something else.
* Bank statement terms (*Umsätze*, *relevé*, *extracto*, *estratto conto*, *kontoutskrifter*, *výpis*, …) are the ones
  banks typically use in that country's online banking.
