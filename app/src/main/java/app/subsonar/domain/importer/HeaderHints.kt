package app.subsonar.domain.importer

import java.text.Normalizer

/** Multilingual header vocabulary (en, no/da/sv, de, nl, fr, es, it, pt, pl, fi, cs, hu, ...). */
object HeaderHints {
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ø', 'o').replace('æ', 'a').replace('ł', 'l').replace('ß', 's')
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun hits(h: String, words: List<String>) = words.any { w ->
        if (w.length <= 3) Regex("(^| )${Regex.escape(w)}( |$)").containsMatchIn(h) else h.contains(w)
    }

    private val DATE = listOf(
        "date", "dato", "datum", "data", "fecha", "fecha operacion", "paiva", "pvm", "buchungstag", "booking", "bogfor", "bokfor",
        "boekdatum", "transaktionsdag", "tranzakcio", "kirjauspaiva", "completed", "started", "posted", "created", "datumzauctovani",
        "zauctovani", "buchungsdatum", "transactiedatum", "operation", "kirjaus", "tag", "dag", "dia",
    )
    private val VALUE_DATE = listOf("value", "valuta", "valuer", "wert", "rente", "valeur", "ertek", "interest", "valor", "valutadatum", "zpracovani")
    private val AMOUNT = listOf(
        "amount", "belop", "belob", "beloeb", "bedrag", "betrag", "importe", "montant", "importo", "kwota", "maara", "summa", "suma",
        "castka", "osszeg", "umsatz", "sum", "valor", "total", "gross", "brutto", "net", "netto", "transactiebedrag", "tranzakcio osszege", "mnozstvi",
    )
    private val BALANCE = listOf("balance", "saldo", "solde", "egyenleg", "kontostand", "zustand", "stand", "disponible", "available", "zustatek")
    private val DEBIT = listOf(
        "debit", "debet", "soll", "udgift", "utgift", "uitgaven", "money out", "paid out", "withdrawal", "ausgaben", "cargo", "addebit",
        "retrait", "wyplata", "kiadas", "uttag", "belast", "ut av", "out", "afschrijving", "menos", "charge", "spent", "kosten", "odchozi",
    )
    private val CREDIT = listOf(
        "credit", "kredit", "haben", "indtaegt", "inntekt", "inntekter", "money in", "paid in", "deposit", "einnahmen", "abono",
        "accredit", "wplata", "bevetel", "insattning", "bijschrijving", "income", "receipt", "creditor", "prichozi", "in",
    )
    private val DIRECTION = listOf("d k", "af bij", "db cr", "dc", "cr dr", "debit credit", "soll haben", "s h", "indicator", "type of transaction", "direction", "d c")
    private val DESCRIPTION = listOf(
        "description", "descripcion", "descrizione", "text", "tekst", "beschreibung", "verwendungszweck", "buchungstext", "omschrijving",
        "mededelingen", "forklaring", "beskrivelse", "selite", "libelle", "concepto", "opis", "popis", "details", "detail", "memo", "note",
        "narrative", "payee", "merchant", "counterparty", "counter party", "name", "navn", "naam", "empfanger", "beguenstigter",
        "begunstigde", "partner", "beneficiary", "mottaker", "modtager", "mottagare", "saaja", "nombre", "nom", "nome", "nazwa",
        "gavejas", "paaiskinimai", "kozlemeny", "zprava", "protistrana", "message", "meddelande", "viesti", "title", "tytul", "transaction", "reference",
        "verwendung", "auftraggeber", "zahlungsempfanger",
    )
    private val PAYEE = listOf(
        "payee", "merchant", "counterparty", "counter party", "name", "navn", "naam", "empfanger", "beguenstigter", "partner", "beneficiary",
        "begunstigde", "mottaker", "modtager", "mottagare", "saaja", "nombre", "nazwa", "gavejas", "protistrana", "auftraggeber", "zahlungsempfanger",
    )
    private val NOT_DESCRIPTION = listOf(
        "iban", "bic", "swift", "account", "konto", "rekening", "compte", "cuenta", "conto", "numer", "nummer", "number", "code", "kode",
        "type", "typ", "category", "kategorie", "kategori", "status", "balance", "saldo", "mandat", "glaubiger", "creditor id", "valuta",
        "date", "dato", "datum", "currency", "valuuta", "waehrung", "wahrung", "devizanem", "exchange", "rate", "wechselkurs", "kurs",
        "id", "tegenrekening", "bank", "emoji", "address", "receipt", "location", "land", "country",
    )
    private val CURRENCY = listOf("currency", "valuta", "valuuta", "waehrung", "wahrung", "devise", "moneda", "valuta", "waluta", "mena", "penznem", "devizanem")
    private val FOREIGN = listOf("original", "foreign", "fremd", "local", "orig", "exchange", "wechselkurs", "kurs", "rate", "fee", "gebuhr", "provision")

    fun isDate(header: String) = hits(normalize(header), DATE)
    fun isValueDate(header: String) = hits(normalize(header), VALUE_DATE)
    fun isAmount(header: String) = hits(normalize(header), AMOUNT)
    fun isBalance(header: String) = hits(normalize(header), BALANCE)
    fun isDebit(header: String): Boolean {
        val h = normalize(header)
        return hits(h, DEBIT) && !hits(h, listOf("credit", "kredit", "haben"))
    }
    fun isCredit(header: String): Boolean {
        val h = normalize(header)
        return hits(h, CREDIT) && !isDebit(header)
    }
    fun isDirection(header: String) = hits(normalize(header), DIRECTION)
    fun isDescription(header: String) = hits(normalize(header), DESCRIPTION)
    fun isPayee(header: String) = hits(normalize(header), PAYEE)
    fun isNotDescription(header: String): Boolean {
        val h = normalize(header)
        // "Account Name" is not a payee; but "Naam / Omschrijving" is a description.
        return hits(h, NOT_DESCRIPTION) && !hits(h, listOf("omschrijving", "description", "beschreibung", "verwendungszweck", "forklaring"))
    }
    fun isCurrency(header: String) = hits(normalize(header), CURRENCY)
    private val FEE = listOf("fee", "gebuhr", "provision", "commission", "avgift", "gebyr", "poplatek", "opłata", "oplata", "kosten", "cost", "tax", "steuer")
    fun isFee(header: String) = hits(normalize(header), FEE)
    fun isForeign(header: String) = hits(normalize(header), FOREIGN)

    /** Words in a direction column meaning money out / in (lower-case, accents stripped). */
    val DEBIT_TOKENS = setOf("d", "db", "deb", "debit", "debet", "debt", "dbt", "af", "s", "soll", "dr", "dt", "udgift", "utgift", "out", "withdrawal", "-", "belastung", "lastschrift", "uitgave", "sortie")
    val CREDIT_TOKENS = setOf("c", "cr", "crd", "credit", "kredit", "k", "bij", "h", "haben", "in", "k", "indtaegt", "inntekt", "deposit", "+", "gutschrift", "entree", "bijschrijving", "indbetaling")

    /** Status values (accents stripped, lower-case) of rows that are not settled bookings. */
    val SKIP_STATUS_WORDS = setOf(
        "pending", "vorgemerkt", "reserviert", "reservert", "reserveret", "reserved", "venter", "ventende", "vantar", "pendiente", "pendente",
        "in attesa", "en attente", "in behandeling", "declined", "failed", "rejected", "cancelled", "canceled", "deleted", "slettet",
        "storniert", "abgelehnt", "geannuleerd", "afgewezen", "rechazado", "annullato", "reverted", "reversed", "void", "voided",
        "avvist", "avbrutt", "afvist", "annulleret", "odmitnuto", "zrusene", "folyamatban", "elutasitva", "pending authorisation",
        "pending authorization", "uncleared", "in progress", "odotettaa", "vireilla",
    )
    private val STATUS = listOf("status", "state", "tila", "stav", "stato", "estado", "statut", "staat", "allapot", "tilanne")
    fun isStatus(header: String) = hits(normalize(header), STATUS)
}
