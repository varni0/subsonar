package app.subsonar.domain.recurring

import app.subsonar.domain.importer.ParsedTransaction
import java.math.BigDecimal
import java.time.LocalDate

internal fun tx(date: String, amount: String, desc: String, cur: String? = "EUR") =
    ParsedTransaction(LocalDate.parse(date), BigDecimal(amount), cur, desc)

/** Monthly series on [day] of each month starting at [start] ("2025-01"), shifted to Monday when it falls on a weekend. */
internal fun monthly(start: String, months: Int, day: Int, amount: String, desc: String, weekendShift: Boolean = true, cur: String? = "EUR"): List<ParsedTransaction> {
    val s = java.time.YearMonth.parse(start)
    return (0 until months).map { i ->
        val ym = s.plusMonths(i.toLong())
        var d = ym.atDay(minOf(day, ym.lengthOfMonth()))
        if (weekendShift) while (d.dayOfWeek.value > 5) d = d.plusDays(1)
        ParsedTransaction(d, BigDecimal(amount), cur, desc)
    }
}

internal fun List<RecurringCandidate>.find(keyStart: String) = firstOrNull { it.key.startsWith(keyStart.lowercase()) }
