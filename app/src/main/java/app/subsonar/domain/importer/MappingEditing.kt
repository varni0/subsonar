package app.subsonar.domain.importer

/** Helpers for the "adjust columns" screen. Pure Kotlin. */
object MappingEditing {
    private val COMMON_PATTERNS = listOf("dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy", "dd.MM.yy", "dd/MM/yy", "yyyyMMdd")

    /** Header text of a column, or null when the file has no header or the cell is blank. */
    fun headerOf(layout: DelimitedLayout, column: Int): String? = layout.header?.getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }

    /** First non-blank value below the header, shortened for display. */
    fun sampleOf(layout: DelimitedLayout, column: Int): String =
        layout.dataRows.asSequence().take(50).mapNotNull { it.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull().orEmpty().take(24)

    /** Date formats worth offering for [column]: those that parse real cells of the column first, then common ones. */
    fun dateFormatOptions(layout: DelimitedLayout, column: Int, current: String?): List<String> {
        val cells = layout.dataRows.asSequence().take(60).mapNotNull { it.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }.toList()
        val counts = HashMap<String, Int>()
        for (c in cells) for ((p, _) in DateParsing.interpretations(c)) counts.merge(p.pattern, 1, Int::plus)
        val fitting = counts.entries.sortedByDescending { it.value }.map { it.key }
        return (listOfNotNull(current) + fitting + COMMON_PATTERNS).distinct().filter { DatePattern.fromPattern(it) != null }
    }

    /** Switches between "one amount column" and "separate debit/credit columns", keeping sensible defaults. */
    fun withSingleAmount(m: ColumnMapping, column: Int): ColumnMapping =
        m.copy(amountColumn = column, debitColumn = null, creditColumn = null, directionColumn = null, debitMarkers = emptySet(), creditMarkers = emptySet(), plusIsCredit = false)

    fun withSplitAmount(m: ColumnMapping, debit: Int, credit: Int): ColumnMapping =
        m.copy(amountColumn = null, debitColumn = debit, creditColumn = credit, directionColumn = null, debitMarkers = emptySet(), creditMarkers = emptySet(), invertSign = false, plusIsCredit = false)

    fun toggleTextColumn(m: ColumnMapping, column: Int): ColumnMapping {
        val cols = if (column in m.descriptionColumns) m.descriptionColumns - column else (m.descriptionColumns + column).sorted()
        return m.copy(descriptionColumns = cols)
    }

    /** A user edit changes the meaning of the columns, so inferred status/direction rules are dropped when the columns they refer to are reassigned. */
    fun sanitized(m: ColumnMapping): ColumnMapping {
        val used = m.amountColumns.toSet() + m.dateColumn
        return m.copy(
            statusColumn = m.statusColumn?.takeIf { it !in used },
            skipStatuses = if (m.statusColumn != null && m.statusColumn in used) emptySet() else m.skipStatuses,
            directionColumn = m.directionColumn?.takeIf { it !in used },
            descriptionColumns = m.descriptionColumns.filter { it != m.dateColumn && it !in m.amountColumns },
        )
    }
}
