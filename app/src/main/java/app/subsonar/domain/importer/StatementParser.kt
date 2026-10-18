package app.subsonar.domain.importer

import java.math.BigDecimal
import java.util.Currency

/** Applies a [ColumnMapping] to a [DelimitedLayout]. Never throws on bad rows: they are reported as skipped. */
object StatementParser {
    private val ISO_CODES: Set<String> by lazy { Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() }

    fun mappingFits(layout: DelimitedLayout, m: ColumnMapping): Boolean {
        val w = layout.columnCount
        val all = listOfNotNull(m.dateColumn, m.amountColumn, m.debitColumn, m.creditColumn, m.directionColumn, m.currencyColumn) + m.descriptionColumns
        return all.all { it in 0 until w } && DatePattern.fromPattern(m.datePattern) != null
    }

    fun parse(layout: DelimitedLayout, mapping: ColumnMapping): ParseOutcome {
        val pattern = DatePattern.fromPattern(mapping.datePattern)
        val out = ArrayList<ParsedTransaction>()
        val skipped = ArrayList<SkippedRow>()
        var read = 0
        if (pattern == null) return ParseOutcome(out, skipped, 0)
        val rows = layout.rows
        for (idx in layout.firstDataRow until rows.size) {
            val row = rows[idx]
            if (row.all { it.isBlank() }) continue
            read++
            fun cell(c: Int?) = if (c == null) "" else row.getOrNull(c)?.trim().orEmpty()

            if (mapping.statusColumn != null && HeaderHints.normalize(cell(mapping.statusColumn)) in mapping.skipStatuses) {
                skipped.add(SkippedRow(idx, "status")); continue
            }
            val date = DateParsing.parse(cell(mapping.dateColumn), pattern)
            if (date == null) { skipped.add(SkippedRow(idx, "date")); continue }
            val amount = amountOf(row, mapping)
            if (amount == null) { skipped.add(SkippedRow(idx, "amount")); continue }
            val desc = mapping.descriptionColumns.map { cleanText(cell(it)) }.filter { it.isNotEmpty() }
                .let { dedupeChunks(it) }.joinToString(" ")
            out.add(ParsedTransaction(date, amount.first, amount.second, desc))
        }
        return ParseOutcome(out, skipped, read)
    }

    private fun amountOf(row: List<String>, m: ColumnMapping): Pair<BigDecimal, String?>? {
        fun cell(c: Int?) = if (c == null) "" else row.getOrNull(c)?.trim().orEmpty()
        val dec = m.decimalSeparator
        var currency: String? = null
        val value: BigDecimal
        if (m.isSplit) {
            val d = cell(m.debitColumn).takeIf { it.isNotEmpty() }?.let { NumberParsing.parse(it, dec) }
            val c = cell(m.creditColumn).takeIf { it.isNotEmpty() }?.let { NumberParsing.parse(it, dec) }
            if (d == null && c == null) return null
            if (cell(m.debitColumn).isNotEmpty() && d == null) return null
            if (cell(m.creditColumn).isNotEmpty() && c == null) return null
            value = (c?.value?.abs() ?: BigDecimal.ZERO) - (d?.value?.abs() ?: BigDecimal.ZERO)
            currency = d?.currencyHint ?: c?.currencyHint
        } else {
            val raw = cell(m.amountColumn)
            val p = NumberParsing.parse(raw, dec) ?: return null
            currency = p.currencyHint
            var v = p.value
            if (m.directionColumn != null) {
                val tok = HeaderHints.normalize(cell(m.directionColumn))
                v = when {
                    tok in m.debitMarkers -> v.abs().negate()
                    tok in m.creditMarkers -> v.abs()
                    else -> v
                }
            } else if (m.plusIsCredit) {
                v = if (p.explicitPlus) v.abs() else v.abs().negate()
            } else if (m.invertSign) v = v.negate()
            value = v
        }
        val colCurrency = cell(m.currencyColumn).uppercase().takeIf { it.length == 3 && it in ISO_CODES }
        return value to (colCurrency ?: currency ?: m.fixedCurrency)
    }

    fun cleanText(s: String): String =
        s.map { if (it.isISOControl() || it == '\u00A0' || it == '\u200B') ' ' else it }
            .joinToString("").replace(Regex("\\s+"), " ").trim()

    private fun dedupeChunks(chunks: List<String>): List<String> {
        val out = ArrayList<String>()
        for (c in chunks) if (out.none { it.equals(c, ignoreCase = true) || it.contains(c, ignoreCase = true) && c.length > 3 }) out.add(c)
        return out
    }
}
