#!/usr/bin/env python3
"""Builds app/src/test/resources/csv-samples/expectations.json.

Ground truth comes from /workspace/csv-samples/INDEX.csv (delimiter, date format, decimal separator, column names),
from synthetic/matrix/MANIFEST.json, and from an INDEPENDENT row counter (python csv module + regexes), not from the
Kotlin importer.  Column choices per file were annotated by hand from the INDEX column lists (see SPECS).
"""
import csv, json, re, os, sys

SRC = os.environ.get("CSV_SAMPLES", "/workspace/csv-samples")
OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/test/resources/csv-samples/expectations.json"
held = set(l.strip() for l in open(f"{SRC}/../subpilot/app/src/test/resources/csv-samples/HELDOUT.txt")) if False else \
    set(l.strip() for l in open("app/src/test/resources/csv-samples/HELDOUT.txt"))
index = {r["file_or_spec"]: r for r in csv.DictReader(open(f"{SRC}/INDEX.csv")) if r["kind"] == "file"}

# path: (date column names/indices, amount spec, optional dir column name)
# amount spec: "Name" | ("split", debitName, creditName) | int index
SPECS = {
 "AT/ing-diba-at/ing-diba-at.csv": (["Datum"], ("split", "Soll", "Haben"), None),
 "AT/livebank-bgld/livebank.csv": (["Buchungsdatum", "Valutadatum"], "Betrag", None),
 "AT/oberbank/oberbank.csv": (["Buchungsdatum", "Wertstellung"], "Betrag", None),
 "AT/raiffeisen/raiffeisen-elba-2013.csv": ([0, 2], 3, None),
 "AT/raiffeisen/raiffeisen-elba-headerless-2017.csv": ([0, 2], 3, None),
 "AT/raiffeisen/raiffeisen-elba-tab-2017.tsv": ([0, 2], ("split", 4, 3), None),
 "AT/easybank/easybank-creditcard.csv": ([2, 3], 4, None),
 "AT/easybank/easybank-giro.csv": ([2, 3], 4, None),
 "BE/kbc/kbc-checking-2018.csv": (["Datum", "Valuta"], "Bedrag", None),
 "BE/kbc/kbc-creditcard-2020.csv": (["datum verrichting", "Datum verrekening"], ["bedrag", "bedrag in EUR"], None),
 "CH/ubs/ubs-ch-fr-trimmed.csv": (["Date de valeur"], ("split", "Débit", "Crédit"), None),
 "CZ/ceska-sporitelna/csas-page1-en-headers-2018.csv": (None, None, None),
 "CZ/ceska-sporitelna/csas-short-en-2018.csv": (None, None, None),
 "CZ/ceska-sporitelna/csas-strana1-cs-headers-2018.csv": (None, None, None),
 "CZ/raiffeisenbank/rb-cz-movements-2018.csv": ([0, 1], 13, None),
 "DE/commerzbank/commerzbank-umsaetze-2018.csv": (["Buchungstag", "Wertstellung"], "Betrag", None),
 "DE/dkb/dkb-new-2023-bankcsvrs.csv": (["Buchungsdatum", "Wertstellung"], "Betrag (€)", None),
 "DE/dkb/dkb-new-2023-format.csv": (["Buchungsdatum", "Wertstellung"], "Betrag (€)", None),
 "DE/dkb/dkb-old-pre2023.csv": (["Buchungstag", "Wertstellung"], "Betrag (EUR)", None),
 "DE/gls-bank/gls-bank.csv": (["Buchungstag", "Wertstellung"], "Betrag", None),
 "DE/n26/n26-current.csv": (["Booking Date", "Value Date"], "Amount (EUR)", None),
 "DE/n26/n26-new-bankcsvrs.csv": (["Booking Date", "Value Date"], "Amount (EUR)", None),
 "DE/n26/n26-legacy.csv": (["Date"], "Amount (EUR)", None),
 "DE/n26/n26-legacy-bankcsvrs.csv": (["Date"], "Amount (EUR)", None),
 "DE/n26/n26-legacy-de-headers-2017.csv": (["Datum"], "Betrag (EUR)", None),
 "FR/n26/n26-fr-csv2ofx.csv": (["Booking Date", "Value Date"], "Amount (EUR)", None),
 "DE/ostseesparkasse-creditcard/ostseesparkasse-cc-2018.csv": (["Belegdatum", "Buchungsdatum"], ["Buchungsbetrag", "Originalbetrag"], None),
 "DE/outbank-app/outbank-app-export.csv": (["Date", "Value Date"], "Amount", None),
 "DE/sparkasse/sparkasse-csv-camt-style-2018.csv": (["Buchungstag", "Valutadatum"], "Betrag", None),
 "DE/sparkasse/sparkasse-csv-camt-v8-style.csv": (["Buchungstag", "Valutadatum"], "Betrag", None),
 "DK/nordea/nordea-dk-2016.csv": (["Bogført", "Rentedato"], "Beløb", None),
 "GB/barclaycard/barclaycard-statement-2018.csv": ([0], 6, None),
 "GB/first-direct/first-direct-2018.csv": (["Date"], "Amount", None),
 "GB/john-lewis-card-or-m-and-s-pre2022/jl-partnership-card-pre2022.csv": (["Date Processed"], "Amount", None),
 "GB/monzo/monzo-export-2018.csv": (["created"], "amount", None),
 "GB/starling/starling-2018.csv": (["Date"], "Amount (GBP)", None),
 "HU/erste-bank/erste-hu-2019.csv": (["Tranzakció dátuma", "Értéknap"], "Tranzakció összege", None),
 "IE/bank-of-ireland/boi-transaction-export-2017.csv": (["Date"], ("split", "Debit", "Credit"), None),
 "LT/swedbank-baltic/swedbank-lt-2020-lt-headers.csv": (["Data"], "Suma", "D/K"),
 "LT/swedbank-baltic/swedbank-lt-statement-2014.csv": (["Date"], "Amount", "D/K"),
 "NL/asn-bank/asn-transactie-historie.csv": (None, None, None),
 "NL/bunq/bunq-en-headers-2018.csv": (["Date"], "Amount", None),
 "NL/bunq/bunq-nl-headers-2018.csv": (["Datum"], "Bedrag", None),
 "NL/ing/ing-nl-basic.csv": (["Datum"], "Bedrag (EUR)", "Af Bij"),
 "NL/ing/ing-nl-mutatiesoort.csv": (["Datum"], "Bedrag (EUR)", "Af Bij"),
 "NL/knab/knab-transactieoverzicht.csv": (["Transactiedatum", "Valutadatum", "Boekdatum"], "Bedrag", "CreditDebet"),
 "NL/rabobank/rabobank-csv-a-2018.csv": (["Datum", "Rentedatum"], "Bedrag", None),
 "PL/pekao/pekao-lista-operacji-2024.csv": (["Data księgowania", "Data waluty"], "Kwota operacji", None),
 "PL/pko-bp/pko-bp-sample.csv": (["Data operacji", "Data waluty"], "Kwota", None),
 "SE/lansforsakringar/lansforsakringar-from-tests.csv": (["Bokföringsdatum", "Transaktionsdatum"], "Belopp", None),
 "_generic/unknown-bank-preamble/generic-preamble-semicolon-moneyin-moneyout.csv": (["Trans Date", "Posting Date"], ("split", "Money Out", "Money In"), None),
 "_multi/paypal/paypal-current-39col.csv": (["Date"], ["Gross", "Net"], None),
 "_multi/paypal/paypal-old-description-column.csv": (["Date"], ["Gross", "Net"], None),
 "_multi/paypal/paypal-with-subject-note.csv": (["Date"], ["Gross", "Net"], None),
 "_multi/revolut/revolut-2021.csv": (["Started Date", "Completed Date"], "Amount", None),
 "_multi/revolut/revolut-2022-pending.csv": (["Started Date", "Completed Date"], "Amount", None),
 "_multi/revolut/revolut-personal.csv": (["Started Date", "Completed Date"], "Amount", None),
 "_multi/wise/wise-statement.csv": (["Date"], "Amount", None),
}
# date-format / decimal overrides where INDEX text is not machine-readable
DATE_OVERRIDE = {
 "GB/john-lewis-card-or-m-and-s-pre2022/jl-partnership-card-pre2022.csv": "dd-MMM-yyyy",
 "GB/barclaycard/barclaycard-statement-2018.csv": "dd MMM yy",
 "GB/monzo/monzo-export-2018.csv": "yyyy-MM-dd",
 "_multi/revolut/revolut-2021.csv": "yyyy-MM-dd", "_multi/revolut/revolut-2022-pending.csv": "yyyy-MM-dd",
 "_multi/revolut/revolut-personal.csv": "yyyy-MM-dd",
 "HU/erste-bank/erste-hu-2019.csv": "yyyy.MM.dd.",
 "CZ/ceska-sporitelna/csas-page1-en-headers-2018.csv": "yyyy/MM/dd",
 "CZ/ceska-sporitelna/csas-short-en-2018.csv": "yyyy/MM/dd",
 "CZ/ceska-sporitelna/csas-strana1-cs-headers-2018.csv": "yyyy/MM/dd",
 "DE/outbank-app/outbank-app-export.csv": "MM/dd/yy",
 "_multi/paypal/paypal-current-39col.csv": "dd/MM/yyyy",
 "_multi/paypal/paypal-with-subject-note.csv": "dd/MM/yyyy",
 "_multi/paypal/paypal-old-description-column.csv": "MM/dd/yyyy",
}
DECIMAL_OVERRIDE = {
 "NL/bunq/bunq-en-headers-2018.csv": ",", "NL/bunq/bunq-nl-headers-2018.csv": ",", "NL/rabobank/rabobank-csv-a-2018.csv": ",",
 "AT/easybank/easybank-giro.csv": ",", "GB/john-lewis-card-or-m-and-s-pre2022/jl-partnership-card-pre2022.csv": ".",
 "CH/ubs/ubs-ch-fr-trimmed.csv": ".", "DE/dkb/dkb-new-2023-format.csv": ",", "DE/gls-bank/gls-bank.csv": ",",
 "SE/lansforsakringar/lansforsakringar-from-tests.csv": ",", "NL/knab/knab-transactieoverzicht.csv": ",",
 "CZ/ceska-sporitelna/csas-page1-en-headers-2018.csv": ",", "CZ/ceska-sporitelna/csas-short-en-2018.csv": ",",
 "CZ/ceska-sporitelna/csas-strana1-cs-headers-2018.csv": ",", "CZ/raiffeisenbank/rb-cz-movements-2018.csv": ",",
 "AT/raiffeisen/raiffeisen-elba-headerless-2017.csv": ",",
}
NO_HEADER = {"AT/raiffeisen/raiffeisen-elba-2013.csv", "AT/raiffeisen/raiffeisen-elba-headerless-2017.csv",
             "AT/raiffeisen/raiffeisen-elba-tab-2017.tsv", "AT/easybank/easybank-creditcard.csv", "AT/easybank/easybank-giro.csv",
             "GB/barclaycard/barclaycard-statement-2018.csv", "NL/asn-bank/asn-transactie-historie.csv", "AT/raiffeisen/raiffeisen-rcm-kta-ei.dat",
             "synthetic/bank-like/DK/lan-og-spar-modelled-bom-headerless.csv"}

def pat_from_index(s):
    s = s.strip()
    m = {"YYYY-MM-DD": "yyyy-MM-dd", "DD.MM.YYYY": "dd.MM.yyyy", "DD.MM.YY": "dd.MM.yy", "DD/MM/YYYY": "dd/MM/yyyy",
         "DD-MM-YYYY": "dd-MM-yyyy", "YYYYMMDD": "yyyyMMdd"}
    return m.get(s)

def date_regex(pat):
    p = re.escape(pat)
    p = p.replace("yyyy", r"\d{4}").replace("MMM", r"[A-Za-z]{3,9}").replace("yy", r"\d{2}").replace("dd", r"\d{1,2}").replace("MM", r"\d{1,2}")
    return re.compile("^\\s*" + p + r"(\s.*|T.*)?$")

def read_text(path):
    b = open(path, "rb").read()
    if b[:2] in (b"\xff\xfe", b"\xfe\xff"): return b.decode("utf-16"), "utf16"
    if b[:3] == b"\xef\xbb\xbf": return b[3:].decode("utf-8"), "utf8"
    if all(c < 128 for c in b): return b.decode("ascii"), "ascii"
    try: return b.decode("utf-8"), "utf8"
    except UnicodeDecodeError: return b.decode("cp1252", "replace"), "single"

NUM = re.compile(r"^[\s+\-£€$]*[\d][\d.,' \u00a0]*(\s*[A-Za-z€£$]{0,5})?[\s\-]*$")

# rows of unsettled bookings (pending/declined/deleted) are deliberately not counted
SKIP = {"pending", "vorgemerkt", "reserviert", "reservert", "reserveret", "reserved", "venter", "ventende", "pendiente", "declined", "failed",
        "rejected", "cancelled", "canceled", "deleted", "slettet", "storniert", "abgelehnt", "reverted", "reversed", "void"}

def count_rows(text, delim, pat, date_idx, amt_idxs, header_names=None):
    rx = date_regex(pat)
    n = 0
    for row in csv.reader(text.splitlines(keepends=True) if False else __import__("io").StringIO(text, newline=""), delimiter=delim):
        if date_idx >= len(row) or not rx.match(row[date_idx].strip().strip('"')): continue
        if any(c.strip().lower() in SKIP for c in row): continue
        if any(i < len(row) and row[i].strip() and NUM.match(row[i].strip().strip('"')) for i in amt_idxs): n += 1
    return n

def header_of(text, delim, names, idx0):
    # header row = first row containing the first date column name at some index
    for row in csv.reader(__import__("io").StringIO(text, newline=""), delimiter=delim):
        cells = [c.strip().strip('"') for c in row]
        if names[0] in cells: return cells
    return None

out = []
for path, (dnames, aspec, dirname) in SPECS.items():
    r = index[path]
    full = f"{SRC}/{path}"
    text, encclass = read_text(full)
    delim = {"tab": "\t", "pipe": "|"}.get(r["delimiter"], r["delimiter"])
    pat = DATE_OVERRIDE.get(path) or pat_from_index(r["date_format"])
    dec = DECIMAL_OVERRIDE.get(path) or (r["decimal"][:1] if r["decimal"][:1] in ",." else None)
    hasHeader = path not in NO_HEADER
    rec = {"path": path, "heldOut": path in held, "gpl": r["license_class"].startswith("copyleft"), "delimiter": delim, "datePattern": pat,
           "decimal": dec, "hasHeader": hasHeader, "encodingClass": encclass}
    if dnames is not None:
        hdr = None
        if hasHeader:
            hdr = header_of(text, delim, [n for n in dnames if isinstance(n, str)][:1] or [""], 0)
            # header names from INDEX columns as a fallback
        def idx_of(n):
            if isinstance(n, int): return n
            cols = hdr if hdr else [c.strip() for c in r["columns"].split("|")]
            if n in cols: return cols.index(n)
            # corrupted-encoding fallback: position in the INDEX column list
            ic = [c.strip() for c in r["columns"].split("|")]
            return ic.index(n)
        dates = [idx_of(n) for n in dnames]
        rec["dateCols"] = dates
        if isinstance(aspec, tuple):
            rec["debitCol"], rec["creditCol"] = idx_of(aspec[1]), idx_of(aspec[2])
            amts = [rec["debitCol"], rec["creditCol"]]
        elif isinstance(aspec, list):
            rec["amountCols"] = [idx_of(n) for n in aspec]; amts = rec["amountCols"]
        else:
            rec["amountCols"] = [idx_of(aspec)]; amts = rec["amountCols"]
        if dirname: rec["dirCol"] = idx_of(dirname)
        if pat: rec["rows"] = count_rows(text, delim, pat, dates[0], amts)
    if path.startswith("CZ/ceska-sporitelna/"):
        rec["maskedDates"] = True   # sample dates are placeholders (1111/11/11): only delimiter/encoding are checkable
        rec["datePattern"] = None
    if path.startswith("BE/kbc/"):
        # signed 'bedrag' column and the credit/debet split pair are equivalent readings
        rec["altSplit"] = [idx_of("debet"), idx_of("credit")] if path.endswith("2020.csv") else [idx_of("debet"), idx_of("credit")]
    out.append(rec)

out.append({"path": "AT/raiffeisen/raiffeisen-rcm-kta-ei.dat", "heldOut": "AT/raiffeisen/raiffeisen-rcm-kta-ei.dat" in held, "gpl": False,
            "delimiter": "|", "hasHeader": False, "encodingClass": read_text(f"{SRC}/AT/raiffeisen/raiffeisen-rcm-kta-ei.dat")[1]})

# --- bank-like synthetic: ledger of 258 rows (257 representable when debit/credit are split) ---
BANKLIKE = {  # path: (delim, datePattern, dateCols, amountCols | ("split", d, c), hasHeader)
 "synthetic/bank-like/FI/op-osuuspankki-modelled.csv": (";", "dd.MM.yyyy", [0, 1], [2], True),
 "synthetic/bank-like/FI/nordea-legacy-tsv-modelled.txt": ("\t", "dd.MM.yyyy", [0, 1, 2], [3], True),
 "synthetic/bank-like/NO/dnb-modelled-latin1.csv": (";", "dd.MM.yyyy", [0, 2], ("split", 3, 4), True),
 "synthetic/bank-like/DK/lan-og-spar-modelled-bom-headerless.csv": (";", "dd-MM-yyyy", [7, 8, 9, 10], [4], False),
 "synthetic/bank-like/SE/handelsbanken-html-disguised-as-xls.xls": (None, "yyyy-MM-dd", [0, 1], [3], False),
}
for path in sorted(p for p in open("app/src/test/resources/csv-samples/ALLFILES.txt").read().split() if p.startswith("synthetic/bank-like")):
    rec = {"path": path, "heldOut": path in held, "gpl": False, "synthetic": True, "bankLike": True, "rowsRange": [257, 258]}
    if path in BANKLIKE:
        d, pat, dc, am, hh = BANKLIKE[path]
        if d: rec["delimiter"] = d
        rec.update({"datePattern": pat, "dateCols": dc, "hasHeader": hh, "decimal": ","})
        if isinstance(am, tuple): rec["debitCol"], rec["creditCol"] = am[1], am[2]
        else: rec["amountCols"] = am
        rec["encodingClass"] = read_text(f"{SRC}/{path}")[1]
    out.append(rec)

# --- synthetic matrix ---
man = json.load(open(f"{SRC}/synthetic/matrix/MANIFEST.json"))
for name, v in man.items():
    d = v["dialect"]
    path = f"synthetic/matrix/{name}"
    delim = d.get("delim", ";"); dec = d.get("dec", ",")
    pf = {"%Y-%m-%d": "yyyy-MM-dd", "%d.%m.%Y": "dd.MM.yyyy", "%d.%m.%y": "dd.MM.yy", "%d/%m/%Y": "dd/MM/yyyy", "%m/%d/%Y": "MM/dd/yyyy",
          "%d-%m-%Y": "dd-MM-yyyy", "%Y%m%d": "yyyyMMdd", "%d-%b-%Y": "dd-MMM-yyyy", "%Y/%m/%d": "yyyy/MM/dd"}[d.get("datef", "%d.%m.%Y")]
    enc = d.get("enc", "utf-8").lower()
    full = f"{SRC}/{path}"
    _, ec = read_text(full)
    rec = {"path": path, "heldOut": path in held, "gpl": False, "synthetic": True, "matrix": True, "delimiter": delim, "datePattern": pf,
           "decimal": dec, "hasHeader": d.get("header", True), "encodingClass": ec, "dateCols": [0], "rows": 258, "descCols": [1]}
    sign = d.get("sign", "signed")
    if sign == "split": rec["debitCol"], rec["creditCol"] = 2, 3; rec["rows"] = 257  # the 0.00 trial row has no debit/credit cell
    else: rec["amountCols"] = [2]
    if sign == "dcflag": rec["dirCol"] = 3
    out.append(rec)

json.dump(out, open(OUT, "w"), indent=1, ensure_ascii=False)
print(len(out), "records;", sum(1 for r in out if r.get("rows") is not None), "with row counts")
