package app.subsonar.domain.importer

import java.time.LocalDate
import java.time.YearMonth

/** Which calendar months the imported history covers. */
data class Coverage(val months: List<YearMonth>, val transactionCount: Int) {
    val isEmpty get() = months.isEmpty()
    val first: YearMonth? get() = months.firstOrNull()
    val last: YearMonth? get() = months.lastOrNull()

    /** Consecutive runs of months, e.g. [2025-01..2025-03, 2025-06..2025-06]. */
    val ranges: List<ClosedRange<YearMonth>> get() = rangesOf(months)

    /** Months between the first and last imported month that have no transactions (a hint that a file is missing). */
    val gaps: List<YearMonth> get() {
        val f = first ?: return emptyList()
        val l = last ?: return emptyList()
        val have = months.toSet()
        return generateSequence(f) { it.plusMonths(1) }.takeWhile { it <= l }.filter { it !in have }.toList()
    }

    companion object {
        val EMPTY = Coverage(emptyList(), 0)

        fun of(dates: Collection<LocalDate>, count: Int = dates.size) =
            Coverage(dates.map { YearMonth.from(it) }.distinct().sorted(), count)

        fun rangesOf(months: List<YearMonth>): List<ClosedRange<YearMonth>> {
            val sorted = months.distinct().sorted()
            if (sorted.isEmpty()) return emptyList()
            val out = ArrayList<ClosedRange<YearMonth>>()
            var start = sorted[0]
            var prev = sorted[0]
            for (m in sorted.drop(1)) {
                if (m != prev.plusMonths(1)) { out.add(start..prev); start = m }
                prev = m
            }
            out.add(start..prev)
            return out
        }
    }
}
