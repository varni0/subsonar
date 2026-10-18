package app.subsonar.domain.importer

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/** One bank transaction in normalized form. Negative [amount] = money out (debit). */
data class ParsedTransaction(
    val date: LocalDate,
    val amount: BigDecimal,
    /** ISO currency code if known; null = use the user's default. */
    val currency: String?,
    val description: String,
)

enum class SourceKind { CSV, CAMT053, MT940 }

/** How the columns of a delimited file map to transaction fields. Column indices are 0-based. */
data class ColumnMapping(
    val dateColumn: Int,
    val datePattern: String,
    val descriptionColumns: List<Int>,
    val amountColumn: Int? = null,
    val debitColumn: Int? = null,
    val creditColumn: Int? = null,
    val directionColumn: Int? = null,
    /** Lower-case values of [directionColumn] that mean "money out". */
    val debitMarkers: Set<String> = emptySet(),
    /** Lower-case values of [directionColumn] that mean "money in". */
    val creditMarkers: Set<String> = emptySet(),
    /** Flip the sign of a single signed amount column (credit-card style exports where purchases are positive). */
    val invertSign: Boolean = false,
    val decimalSeparator: Char = ',',
    val currencyColumn: Int? = null,
    val fixedCurrency: String? = null,
    /** Rows whose [statusColumn] value (normalized) is in [skipStatuses] (pending, declined, deleted...) are not imported. */
    val statusColumn: Int? = null,
    val skipStatuses: Set<String> = emptySet(),
    /** Credit-card style: unsigned amounts are purchases (money out) and an explicit leading '+' marks payments/refunds (money in). */
    val plusIsCredit: Boolean = false,
) {
    val isSplit get() = debitColumn != null && creditColumn != null
    val amountColumns: List<Int> get() = listOfNotNull(amountColumn, debitColumn, creditColumn)

    fun encode(): String = buildString {
        fun kv(k: String, v: Any?) { if (v != null) append(k).append('=').append(escape(v.toString())).append('\n') }
        kv("date", dateColumn); kv("datePattern", datePattern)
        kv("desc", descriptionColumns.joinToString(","))
        kv("amount", amountColumn); kv("debit", debitColumn); kv("credit", creditColumn)
        kv("dir", directionColumn)
        if (debitMarkers.isNotEmpty()) kv("debitMarkers", debitMarkers.joinToString("|"))
        if (creditMarkers.isNotEmpty()) kv("creditMarkers", creditMarkers.joinToString("|"))
        kv("invert", invertSign); kv("decimal", decimalSeparator)
        kv("currencyColumn", currencyColumn); kv("currency", fixedCurrency)
        kv("status", statusColumn)
        if (skipStatuses.isNotEmpty()) kv("skipStatuses", skipStatuses.joinToString("|"))
        if (plusIsCredit) kv("plusIsCredit", true)
    }

    companion object {
        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
        private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\\", "\\")

        /** Inverse of [encode]; null if the string is not a valid mapping. */
        fun decode(text: String): ColumnMapping? {
            val m = HashMap<String, String>()
            text.lineSequence().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) m[line.substring(0, i)] = unescape(line.substring(i + 1))
            }
            return try {
                ColumnMapping(
                    dateColumn = m["date"]!!.toInt(),
                    datePattern = m["datePattern"]!!,
                    descriptionColumns = m["desc"].orEmpty().split(',').filter { it.isNotBlank() }.map { it.toInt() },
                    amountColumn = m["amount"]?.toInt(),
                    debitColumn = m["debit"]?.toInt(),
                    creditColumn = m["credit"]?.toInt(),
                    directionColumn = m["dir"]?.toInt(),
                    debitMarkers = m["debitMarkers"]?.split('|')?.toSet().orEmpty(),
                    creditMarkers = m["creditMarkers"]?.split('|')?.toSet().orEmpty(),
                    invertSign = m["invert"] == "true",
                    decimalSeparator = m["decimal"]?.firstOrNull() ?: ',',
                    currencyColumn = m["currencyColumn"]?.toInt(),
                    fixedCurrency = m["currency"],
                    statusColumn = m["status"]?.toInt(),
                    skipStatuses = m["skipStatuses"]?.split('|')?.toSet().orEmpty(),
                    plusIsCredit = m["plusIsCredit"] == "true",
                ).takeIf { it.amountColumn != null || it.isSplit }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** The structural reading of a delimited file (before any column mapping). */
data class DelimitedLayout(
    val encoding: String,
    val delimiter: Char,
    /** Index into [rows] of the header row, or null when the file has no header. */
    val headerRow: Int?,
    /** Index into [rows] of the first data row. */
    val firstDataRow: Int,
    val rows: List<List<String>>,
    val columnCount: Int,
) {
    val header: List<String>? get() = headerRow?.let { rows[it] }
    val dataRows: List<List<String>> get() = if (firstDataRow in rows.indices) rows.subList(firstDataRow, rows.size) else emptyList()
}

data class SkippedRow(val rowIndex: Int, val reason: String)

/** Outcome of applying a mapping to the data rows. */
data class ParseOutcome(
    val transactions: List<ParsedTransaction>,
    /** Data-region rows that looked like data but could not be used (e.g. pending, balance rows). */
    val skipped: List<SkippedRow>,
    val rowsRead: Int,
)

enum class FileFailure { EMPTY, TOO_LARGE, NOT_TEXT, NO_TABLE, NO_DATE_COLUMN, NO_AMOUNT_COLUMN, NO_TRANSACTIONS, UNREADABLE }

/** Everything the preview screen needs about one file. */
data class FileAnalysis(
    val name: String,
    val kind: SourceKind,
    val layout: DelimitedLayout?,
    val mapping: ColumnMapping?,
    val fingerprint: String,
    val outcome: ParseOutcome?,
    /** 0..1 heuristic confidence of the detected mapping. */
    val confidence: Double,
    val warnings: List<ImportWarning>,
    val failure: FileFailure? = null,
    val fromSavedMapping: Boolean = false,
    val encoding: String = layout?.encoding ?: "",
) {
    val ok get() = failure == null && outcome != null && outcome.transactions.isNotEmpty()
    val transactions: List<ParsedTransaction> get() = outcome?.transactions.orEmpty()
}

enum class ImportWarning {
    AMBIGUOUS_DATE_ORDER, SIGN_GUESSED, NO_HEADER, SOME_ROWS_SKIPPED, LOW_CONFIDENCE, DECIMAL_GUESSED,
    SPLIT_ORDER_GUESSED, MIXED_ENCODING, TRUNCATED,
}

object Fingerprints {
    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Fingerprint of a delimited layout: normalized header cells (or column count + cell-shape for headerless files) + delimiter. */
    fun delimited(layout: DelimitedLayout): String {
        val header = layout.header
        val basis = if (header != null) {
            "h|" + header.joinToString("|") { HeaderHints.normalize(it) }
        } else {
            val shape = layout.dataRows.firstOrNull()?.joinToString("|") { cellShape(it) }.orEmpty()
            "n|${layout.columnCount}|$shape"
        }
        return sha256("$basis#${layout.delimiter.code}").take(32)
    }

    private fun cellShape(c: String): String = when {
        c.isBlank() -> "e"
        DateParsing.isDateLike(c) -> "d"
        NumberParsing.parse(c) != null -> "n"
        else -> "t"
    }
}
