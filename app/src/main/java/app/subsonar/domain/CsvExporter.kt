package app.subsonar.domain

import app.subsonar.data.Subscription
import java.time.LocalDate

object CsvExporter {
    val HEADER = listOf(
        "name", "price", "currency", "billing_cycle", "next_renewal",
        "category", "monthly_cost", "yearly_cost", "reminders", "notes",
    )

    fun export(items: List<Subscription>, today: LocalDate): String = buildString {
        appendLine(HEADER.joinToString(","))
        items.sortedBy { RenewalCalculator.nextRenewal(it.renewalAnchor, it.cycle, today) }.forEach { s ->
            val row = listOf(
                s.name,
                s.price.toPlainString(),
                s.currencyCode,
                s.cycle.name.lowercase(),
                RenewalCalculator.nextRenewal(s.renewalAnchor, s.cycle, today).toString(),
                s.category.name.lowercase(),
                SpendCalculator.monthlyCost(s.price, s.cycle).toPlainString(),
                SpendCalculator.yearlyCost(s.price, s.cycle).setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(),
                if (s.remindersEnabled) "yes" else "no",
                s.notes,
            )
            appendLine(row.joinToString(",") { escape(it) })
        }
    }

    /** RFC 4180 quoting plus protection against spreadsheet formula injection. */
    fun escape(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || safe != safe.trim()
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
