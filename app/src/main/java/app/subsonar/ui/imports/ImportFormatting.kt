package app.subsonar.ui.imports

import android.content.res.Resources
import app.subsonar.R
import app.subsonar.domain.importer.Coverage
import app.subsonar.ui.formatDateMedium
import app.subsonar.ui.formatMonthYear
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

internal fun formatDate(d: LocalDate, locale: Locale = Locale.getDefault()): String = formatDateMedium(d, locale)

internal fun formatMonth(m: YearMonth, locale: Locale = Locale.getDefault()): String = formatMonthYear(m, locale)

/** "Jan 2025 – Mar 2025, Jun 2025" */
internal fun coverageRanges(c: Coverage, res: Resources, locale: Locale = Locale.getDefault()): String =
    c.ranges.joinToString(", ") { r ->
        if (r.start == r.endInclusive) formatMonth(r.start, locale)
        else res.getString(R.string.import_coverage_range, formatMonth(r.start, locale), formatMonth(r.endInclusive, locale))
    }

internal fun coverageSummary(c: Coverage, res: Resources, locale: Locale = Locale.getDefault()): String =
    res.getQuantityString(R.plurals.import_months_covered, c.months.size, c.months.size, coverageRanges(c, res, locale))
