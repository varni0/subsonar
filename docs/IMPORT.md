# Bank import

## What it does
1. **Pick files** with the system picker (multiple CSVs, or a zip; CAMT.053 XML and MT940 are recognised by content).
   Each file is analysed independently; one bad file never blocks the others and gets its own error message.
2. **Detect the format** (pure Kotlin, `domain/importer`): text encoding (UTF-8 ± BOM, UTF-16, Windows-1252 / ISO-8859-1 /
   Windows-1250, mixed), delimiter (`; , tab |`), quoted fields, ragged rows, preamble/footer lines, header row.
3. **Infer columns** from the values (date column + format with file-wide disambiguation of dd/MM vs MM/dd, amount incl.
   split debit/credit, direction columns, credit-card “+ means payment”, decimal/thousands separators `1.234,56` `1,234.56`
   `1 234,56` `1'234.56`, currency, text columns, pending/declined rows) with multilingual header hints as a tie-breaker.
4. **Preview**: first rows with the detected mapping; the user can change columns, date format, decimal separator and sign.
   The confirmed mapping is stored under a *format fingerprint* (normalised header + delimiter) and reused next time.
5. **Store** (Room, `history.db`): transactions with a stable id `sha256(date|amount|normalised text|occurrence index)`,
   import records (time, file, rows, date range), fingerprints. Overlapping files / re-imports are de-duplicated.
6. **Detect recurring charges** (`domain/recurring`) on the whole stored history and show a **review** screen grouped into
   likely subscriptions / bills / not sure, with evidence lines, already-tracked detection and guidance about history length.
   Nothing is added without the user ticking it.

## Privacy
No INTERNET permission. Files are read through the picker and never copied; only parsed rows are stored.
`history.db` is omitted from `backup_rules.xml` and `data_extraction_rules.xml` (include lists are allow-lists; a unit test
checks this). Settings -> “Delete imported history” removes transactions and import records (remembered column mappings
contain no personal data and are kept).

## Test results (reproduce with `./gradlew testDebugUnitTest`; reports in `app/build/reports/csv-eval/`)
Format detection over the 90-file corpus (78 in the repo, 12 GPL files only from the external corpus), checks per file:
delimiter, encoding, date column+format, amount column(s), decimal separator, header presence, parsed row count.

| set | files | files with every check passing |
|---|---|---|
| development | 69 | 69 (100 %) |
| held-out | 21 | 21 (100 %) |
| all | 90 | 90 (100 %) |

**Caveat on the held-out number**: the held-out files were not looked at while the first heuristics were written, but two of
them failed on the first run and I fixed the code (not the expectation) afterwards, so they are no longer fully unseen.
The numbers are 100 % because the corpus is small and was used to fix every failure; they do not predict 100 % on real
bank files. Generated dialects (600 random ledgers rendered with random delimiter/encoding/date/number/header/preamble/
split-column layouts, written independently of the corpus but by the same author): 600/600 exact round-trips.

Recurring detector vs `synthetic/ground_truth.json` (258 rows, 18 months, 8 expected recurring merchants, traps: weekly
groceries, café/kiosk/bakery/pizza habits, a mortgage-like bill, a fixed-price lunch habit, a refund, salary):

| history used | candidates | recall | precision |
|---|---|---|---|
| full ledger, every one of the 24 file dialects | 8 true + 1 optional | 1.00 | 1.00 |
| first 1 month | 3 (all “unconfirmed”) | 0.50 | 1.00 |
| first 2 months | 6 | 1.00 | 1.00 |
| first 3 months | 7 | 0.88 | 1.00 |
| first 6 / 12 months | 8 | 1.00 | 1.00 |

Subscription vs bill classification was correct for 8/8 on the ledger (rent = bill, phone with variable amount = bill).
The detector was *not* tuned on a second independent ledger, so these numbers are optimistic for real data; 25 further
hand-written scenarios in `RecurringDetectorTest` cover weekend shifts, price hikes, missing months, multi-currency,
one-month inputs, multilingual keywords, store penalties and the trap cases.

Other test suites: tokenizer / number / date unit tests, encoding tests, fuzz tests (random bytes, truncated/mutated real
files, 2 MB single lines, 300 000-row file, 40 MB zip bomb, XXE payload), dedupe and overlap tests, multi-file + zip,
saved-mapping reuse, Room repository tests, Room migration test (`AppDatabase` 1->2, validated against exported schemas),
backup-rule test, and end-to-end view-model tests with real databases.

## Known limitations
- Never run against a real, unmodified bank export: the corpus is anonymised open-source test data plus synthetic files, and
  there are almost no real Norwegian, Finnish, French, Spanish, Italian or Portuguese samples. Expect some files to need a manual fix in the preview.
- Banks that put several transactions in one multi-line cell, or that export spreadsheets only (xlsx/PDF), are not supported (reported as such).
- Detection quality depends on history: yearly subscriptions need >= 2 years (or a keyword such as “annual”), one month only yields unconfirmed guesses.
- Merchant names are grouped by their first meaningful word(s); two different merchants with a very generic shared first word may be merged or a rename of a merchant looks like a new one.
- Detector vocabulary (known services, store words, keywords) is a finite list; it is multilingual but far from complete.
- “Dismissed” suggestions are not remembered; a declined suggestion appears again on the next review.
- UI strings are in `strings.xml` (English only); other languages only need new `values-xx` files (plus `locales_config.xml`).
- Preview/review were verified through Robolectric renders and view-model tests, not on a physical device or emulator.
