# synthetic/ - OUR generated test files (NOT real bank data)

Everything here is fictional (invented merchants, invented amounts, deterministic seed 42) and was produced by `generate.py`.
Formats are modelled on documented specs / third-party fixtures listed in `../INDEX.md`; they can be wrong in detail.
Never present these as "real bank exports".

* `matrix/A01..A24.csv` - one ledger, 24 dialects (see `matrix/MANIFEST.json`).
* `ground_truth.json` - what a recurring-charge detector should and should not flag in that ledger.
* `bank-like/` - 7 files modelled on curated specs (FI OP, FI Nordea TSV, NO DNB, NO SpareBank 1, DK Danske, DK Lån & Spar, SE Handelsbanken HTML-as-xls). Handelsbanken header text is invented; DNB decimal separator is an assumption.
* Regenerate: `python3 generate.py`.
