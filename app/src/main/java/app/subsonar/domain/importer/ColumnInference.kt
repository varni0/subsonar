package app.subsonar.domain.importer

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.math.abs

/** Detected date column: layout plus how sure we are. */
data class DateColumn(val pattern: DatePattern, val fraction: Double, val ambiguous: Boolean)

object DateColumnAnalyzer {
    fun analyze(cells: List<String>): DateColumn? {
        val nonEmpty = cells.filter { it.isNotBlank() }.take(1500)
        if (nonEmpty.isEmpty()) return null
        val perCell = nonEmpty.map { DateParsing.interpretations(it) }
        val parsed = perCell.count { it.isNotEmpty() }
        val fraction = parsed.toDouble() / nonEmpty.size
        if (fraction < 0.5) return null
        // Votes are keyed by layout class (trailing dot ignored) so "yyyy.MM.dd." and "yyyy.MM.dd" merge.
        val votes = HashMap<DatePattern, Int>()
        perCell.forEach { list -> list.forEach { (p, _) -> votes.merge(p.copy(trailingDot = false), 1, Int::plus) } }
        val dotted = perCell.any { l -> l.any { it.first.trailingDot } }
        val top = votes.values.max()
        val cover = votes.filterValues { it >= 0.9 * parsed }.keys.ifEmpty { votes.filterValues { it == top }.keys }
        if (cover.size == 1) return DateColumn(cover.first().copy(trailingDot = dotted), fraction, false)

        // Several layouts fit every cell (e.g. 05/03/2025 is d/M and M/d): use file-wide evidence.
        val scored = cover.map { p ->
            val dates = nonEmpty.take(400).mapNotNull { DateParsing.parse(it, p) }
            p to monotonic(dates)
        }.sortedByDescending { it.second }
        val (best, bestScore) = scored[0]
        val secondScore = scored.getOrNull(1)?.second ?: -1.0
        if (bestScore - secondScore >= 0.08) return DateColumn(best.copy(trailingDot = dotted), fraction, false)
        // Still tied: Europe-first priors (day before month), flagged ambiguous when D/M order is undecided.
        val tied = scored.filter { bestScore - it.second < 0.08 }.map { it.first }
        val pick = tied.firstOrNull { it.order == "DMY" } ?: tied.firstOrNull { it.order == "YMD" } ?: tied.first()
        val dm = tied.any { it.order == "DMY" } && tied.any { it.order == "MDY" }
        return DateColumn(pick.copy(trailingDot = dotted), fraction, dm)
    }

    /** 1.0 for a perfectly ascending or descending sequence; lower the more it jumps. */
    private fun monotonic(dates: List<LocalDate>): Double {
        if (dates.size < 3) return 0.0
        var asc = 0; var desc = 0; var big = 0
        for (i in 1 until dates.size) {
            val d = dates[i].toEpochDay() - dates[i - 1].toEpochDay()
            if (d > 0) asc++ else if (d < 0) desc++
            if (abs(d) > 400) big++
        }
        val dir = maxOf(asc, desc).toDouble() / maxOf(asc + desc, 1)
        return dir - 0.5 * big / (dates.size - 1)
    }
}

/** Infers a [ColumnMapping] for a delimited file by analysing values (and multilingual headers as hints). */
object ColumnInference {
    data class Result(
        val mapping: ColumnMapping?,
        val confidence: Double,
        val warnings: Set<ImportWarning>,
        val failure: FileFailure?,
    )

    private const val ANALYSIS_ROWS = 2000
    private val ISO_CODES: Set<String> by lazy { Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() }
    private val IBAN = Regex("""^[A-Z]{2}\d{2}[A-Z0-9 ]{8,32}$""")

    private class Col(val index: Int, val header: String?, val cells: List<String>) {
        val nonEmpty = cells.filter { it.isNotEmpty() }
        val fill = if (cells.isEmpty()) 0.0 else nonEmpty.size.toDouble() / cells.size
        var date: DateColumn? = null
        var nums: List<ParsedNumber?> = emptyList()
        var numericFraction = 0.0
        val idLike: Boolean
        val typeLike: Boolean
        val ibanLike: Boolean
        val distinct = nonEmpty.toSet().size
        val avgLen = if (nonEmpty.isEmpty()) 0.0 else nonEmpty.sumOf { it.length }.toDouble() / nonEmpty.size

        init {
            val digitsOnly = nonEmpty.count { c -> c.replace(" ", "").let { it.length >= 9 && it.all(Char::isDigit) } }
            idLike = nonEmpty.isNotEmpty() && digitsOnly >= 0.9 * nonEmpty.size
            ibanLike = nonEmpty.isNotEmpty() && nonEmpty.count { IBAN.matches(it.uppercase()) } >= 0.6 * nonEmpty.size
            typeLike = nonEmpty.size >= 4 && distinct <= 3 && nonEmpty.all { it.all(Char::isDigit) }
        }
        val label get() = header.orEmpty()
    }

    fun infer(layout: DelimitedLayout): Result {
        val data = layout.dataRows.take(ANALYSIS_ROWS)
        if (data.isEmpty()) return Result(null, 0.0, emptySet(), FileFailure.NO_TABLE)
        val width = layout.columnCount
        val header = layout.header
        val warnings = LinkedHashSet<ImportWarning>()
        if (header == null) warnings.add(ImportWarning.NO_HEADER)

        val cols = (0 until width).map { i ->
            Col(i, header?.getOrNull(i)?.trim(), data.map { it.getOrNull(i)?.trim().orEmpty() })
        }

        // ---- date column ----
        cols.forEach { it.date = DateColumnAnalyzer.analyze(it.cells) }
        val dateCandidates = cols.filter { it.date != null }
        if (dateCandidates.isEmpty()) return Result(null, 0.0, warnings, FileFailure.NO_DATE_COLUMN)
        val dateCol = dateCandidates.maxBy { c ->
            c.date!!.fraction * 10 + c.fill * 2 +
                (if (HeaderHints.isDate(c.label)) 2.0 else 0.0) +
                (if (HeaderHints.isValueDate(c.label) && !HeaderHints.isDate(c.label.replace(Regex("(?i)value|valuta|valeur|wert|rente|interest"), ""))) -2.5 else 0.0) -
                c.index * 0.01
        }
        val dc = dateCol.date!!
        if (dc.ambiguous) warnings.add(ImportWarning.AMBIGUOUS_DATE_ORDER)

        // ---- numeric columns ----
        val otherDates = dateCandidates.filter { it.index != dateCol.index }.map { it.index }.toSet()
        // First pass: decimal separator evidence over all plausible numeric columns.
        var dotVotes = 0
        var commaVotes = 0
        val firstPass = HashMap<Int, List<ParsedNumber?>>()
        for (c in cols) {
            if (c.index == dateCol.index || c.index in otherDates || c.idLike || c.ibanLike) continue
            val nums = c.cells.map { if (it.isEmpty()) null else NumberParsing.parse(it) }
            val ok = nums.count { it != null }
            if (c.nonEmpty.isEmpty() || ok < 0.7 * c.nonEmpty.size) continue
            firstPass[c.index] = nums
            nums.forEach {
                when (it?.evidence) { DecimalEvidence.DOT -> dotVotes++; DecimalEvidence.COMMA -> commaVotes++; else -> {} }
            }
        }
        val decimal: Char
        if (dotVotes != commaVotes) decimal = if (dotVotes > commaVotes) '.' else ','
        else {
            decimal = if (layout.delimiter == ',') '.' else ','
            if (dotVotes + commaVotes == 0) warnings.add(ImportWarning.DECIMAL_GUESSED)
        }
        for ((i, _) in firstPass) {
            val c = cols[i]
            c.nums = c.cells.map { if (it.isEmpty()) null else NumberParsing.parse(it, decimal) }
            c.numericFraction = if (c.nonEmpty.isEmpty()) 0.0 else c.nums.count { it != null }.toDouble() / c.nonEmpty.size
        }
        val numeric = cols.filter { it.index in firstPass && it.numericFraction >= 0.7 && it.fill >= 0.04 }
        if (numeric.isEmpty()) return Result(null, 0.0, warnings, FileFailure.NO_AMOUNT_COLUMN)

        // ---- balance column (running balance relation) ----
        val balanceCols = HashSet<Int>()
        numeric.filter { HeaderHints.isBalance(it.label) && !HeaderHints.isAmount(it.label.replace(Regex("(?i)saldo|balance|solde|egyenleg"), "")) }.forEach { balanceCols.add(it.index) }
        for (b in numeric) {
            if (b.index in balanceCols) continue
            for (a in numeric) {
                if (a.index == b.index || a.index in balanceCols) continue
                if (isRunningBalance(balance = b.nums, amount = a.nums)) { balanceCols.add(b.index); break }
            }
        }
        val candidates = numeric.filter { it.index !in balanceCols && !it.typeLike }

        // ---- amount structure ----
        var amountCol: Int? = null
        var debitCol: Int? = null
        var creditCol: Int? = null

        fun present(c: Col, i: Int) = c.nums.getOrNull(i)?.value?.signum()?.let { it != 0 } == true
        var splitPair: Pair<Col, Col>? = null
        var splitHinted = false
        for (x in candidates.indices) for (y in x + 1 until candidates.size) {
            val a = candidates[x]; val b = candidates[y]
            val n = data.size
            var both = 0; var either = 0
            for (i in 0 until n) {
                val pa = present(a, i); val pb = present(b, i)
                if (pa && pb) both++
                if (pa || pb) either++
            }
            val hinted = (HeaderHints.isDebit(a.label) && HeaderHints.isCredit(b.label)) || (HeaderHints.isCredit(a.label) && HeaderHints.isDebit(b.label))
            val aPres = (0 until n).count { present(a, it) }
            val bPres = (0 until n).count { present(b, it) }
            val exclusive = either > 0 && both.toDouble() / either <= 0.05
            val covers = either.toDouble() / n >= 0.8
            val bothUsed = aPres >= 0.03 * n && bPres >= 0.03 * n
            if (exclusive && bothUsed && (covers || hinted) && (a.nums.all { it == null || it.value.signum() >= 0 } || hinted) && (hinted || !singleLooksSigned(candidates))) {
                if (splitPair == null || hinted && !splitHinted) { splitPair = a to b; splitHinted = hinted }
            }
        }
        val singles = candidates.filter { c -> !c.idLike }
        fun singleScore(c: Col): Double {
            var s = 0.0
            if (HeaderHints.isAmount(c.label)) s += 4
            if (HeaderHints.isBalance(c.label)) s -= 6
            if (HeaderHints.isForeign(c.label)) s -= 3
            if (HeaderHints.isFee(c.label)) s -= 4
            if (HeaderHints.isDebit(c.label) || HeaderHints.isCredit(c.label)) s -= 1
            val vals = c.nums.filterNotNull()
            if (vals.any { it.value.signum() < 0 } && vals.any { it.value.signum() > 0 }) s += 1.5
            s += c.fill * 2
            if (vals.any { it.value.scale() == 2 }) s += 0.5
            s -= c.index * 0.01
            return s
        }
        val bestSingle = singles.maxByOrNull { singleScore(it) }
        if (splitPair != null && (splitHinted || bestSingle == null || singleScore(bestSingle) < 3.5)) {
            val (a, b) = splitPair
            val aIsDebit = when {
                HeaderHints.isDebit(a.label) || HeaderHints.isCredit(b.label) -> true
                HeaderHints.isDebit(b.label) || HeaderHints.isCredit(a.label) -> false
                else -> { warnings.add(ImportWarning.SPLIT_ORDER_GUESSED); (0 until data.size).count { present(a, it) } >= (0 until data.size).count { present(b, it) } }
            }
            debitCol = if (aIsDebit) a.index else b.index
            creditCol = if (aIsDebit) b.index else a.index
        } else if (bestSingle != null) {
            amountCol = bestSingle.index
        } else return Result(null, 0.0, warnings, FileFailure.NO_AMOUNT_COLUMN)

        // ---- direction flag / sign convention ----
        var directionCol: Int? = null
        var debitMarkers = emptySet<String>()
        var creditMarkers = emptySet<String>()
        var invert = false
        var plusIsCredit = false
        if (amountCol != null) {
            val ac = cols[amountCol]
            val vals = ac.nums.filterNotNull().map { it.value }
            val negatives = vals.count { it.signum() < 0 }
            val positives = vals.count { it.signum() > 0 }
            val plusCount = ac.nums.count { it?.explicitPlus == true }
            val unsignedCount = ac.nums.count { it != null && !it.explicitPlus && it.value.signum() > 0 }
            if (negatives == 0 && plusCount >= 1 && unsignedCount > plusCount) {
                // "+ 1,100.00" payment among unsigned purchases (UK-style card statements).
                plusIsCredit = true
            } else if (negatives == 0 && vals.isNotEmpty()) {
                val flag = findDirectionColumn(cols, setOf(dateCol.index, amountCol) + balanceCols, data.size)
                if (flag != null) {
                    directionCol = flag.first; debitMarkers = flag.second; creditMarkers = flag.third
                } else {
                    // All positive and no direction column: assume they are expenses.
                    invert = true
                    warnings.add(ImportWarning.SIGN_GUESSED)
                }
            } else if (positives + negatives > 0 && positives.toDouble() / (positives + negatives) >= 0.85 && negatives >= 1) {
                // Mostly positive with a few negatives: credit-card style (purchases positive).
                invert = true
                warnings.add(ImportWarning.SIGN_GUESSED)
            }
        }

        // ---- currency ----
        val used = HashSet<Int>().apply {
            add(dateCol.index); addAll(balanceCols); listOfNotNull(amountCol, debitCol, creditCol, directionCol).forEach { add(it) }
        }
        var currencyCol: Int? = null
        for (c in cols) {
            if (c.index in used || c.nonEmpty.isEmpty()) continue
            val iso = c.nonEmpty.count { it.length == 3 && it.uppercase() in ISO_CODES }
            if (iso >= 0.9 * c.nonEmpty.size && c.distinct <= 8 && c.fill >= 0.5) { currencyCol = c.index; break }
        }
        var fixedCurrency: String? = null
        if (currencyCol == null) {
            val amountHeaders = listOfNotNull(amountCol, debitCol, creditCol).mapNotNull { header?.getOrNull(it) }
            fixedCurrency = amountHeaders.firstNotNullOfOrNull(::currencyFromHeader)
            if (fixedCurrency == null) {
                fixedCurrency = listOfNotNull(amountCol, debitCol, creditCol).firstNotNullOfOrNull { ci ->
                    cols[ci].nums.firstNotNullOfOrNull { it?.currencyHint }
                }
            }
        }
        currencyCol?.let { used.add(it) }

        // ---- status column (pending / declined / deleted rows are not real bookings) ----
        var statusCol: Int? = null
        var skipStatuses = emptySet<String>()
        for (c in cols) {
            if (c.index in used || c.nonEmpty.isEmpty() || c.fill < 0.5 || c.distinct > 8) continue
            val values = c.nonEmpty.map { HeaderHints.normalize(it) }.toSet()
            val skip = values.filter { it in HeaderHints.SKIP_STATUS_WORDS }.toSet()
            if (skip.isNotEmpty() && (skip.size < values.size || HeaderHints.isStatus(c.label))) {
                statusCol = c.index; skipStatuses = skip; break
            }
        }
        statusCol?.let { used.add(it) }

        // ---- description columns ----
        val descScores = cols.filter { it.index !in used && it.index != dateCol.index && it.index !in otherDates }.mapNotNull { c ->
            if (c.nonEmpty.isEmpty() || c.idLike || c.ibanLike) return@mapNotNull null
            val numericShare = c.nonEmpty.count { NumberParsing.parse(it) != null }.toDouble() / c.nonEmpty.size
            if (numericShare >= 0.7) return@mapNotNull null
            var s = 0.0
            val h = c.label
            if (HeaderHints.isDescription(h)) s += 3
            if (HeaderHints.isPayee(h)) s += 1
            if (HeaderHints.isNotDescription(h)) s -= 4
            s += minOf(c.avgLen / 20.0, 2.0)
            s += (c.distinct.toDouble() / c.nonEmpty.size) * 1.5
            s += c.fill
            c to s
        }
        var descCols = descScores.filter { it.second >= 2.2 }.sortedByDescending { it.second }.take(3).map { it.first.index }.sorted()
        if (descCols.isEmpty()) descCols = descScores.maxByOrNull { it.second }?.let { listOf(it.first.index) } ?: emptyList()

        val mapping = ColumnMapping(
            dateColumn = dateCol.index,
            datePattern = dc.pattern.pattern,
            descriptionColumns = descCols,
            amountColumn = amountCol,
            debitColumn = debitCol,
            creditColumn = creditCol,
            directionColumn = directionCol,
            debitMarkers = debitMarkers,
            creditMarkers = creditMarkers,
            invertSign = invert,
            decimalSeparator = decimal,
            currencyColumn = currencyCol,
            fixedCurrency = fixedCurrency,
            statusColumn = statusCol,
            skipStatuses = skipStatuses,
            plusIsCredit = plusIsCredit,
        )
        var confidence = 0.5 + 0.3 * dc.fraction + 0.2 * (if (amountCol != null) cols[amountCol].numericFraction else 1.0)
        if (warnings.any { it in setOf(ImportWarning.AMBIGUOUS_DATE_ORDER, ImportWarning.SIGN_GUESSED, ImportWarning.DECIMAL_GUESSED, ImportWarning.SPLIT_ORDER_GUESSED) }) confidence -= 0.2
        if (header == null) confidence -= 0.1
        return Result(mapping, confidence.coerceIn(0.0, 1.0), warnings, null)
    }

    private fun singleLooksSigned(cands: List<Col>): Boolean = cands.any { c ->
        val v = c.nums.filterNotNull()
        v.any { it.value.signum() < 0 } && v.any { it.value.signum() > 0 } && c.fill >= 0.9
    }

    private fun currencyFromHeader(h: String): String? {
        val n = HeaderHints.normalize(h).split(' ')
        for (t in n) {
            if (t.length == 3 && t.uppercase() in ISO_CODES && t.uppercase() != "AMT") return t.uppercase()
            if (t == "euroa" || t == "euro" || t == "eura") return "EUR"
        }
        return null
    }

    /** True when `|balance[i] - balance[i±1]| == |amount|` for most consecutive rows (a running balance). */
    internal fun isRunningBalance(balance: List<ParsedNumber?>, amount: List<ParsedNumber?>): Boolean {
        var pairs = 0
        var hitNext = 0
        var hitPrev = 0
        val eps = BigDecimal("0.005")
        for (i in 1 until minOf(balance.size, amount.size)) {
            val b0 = balance[i - 1]?.value ?: continue
            val b1 = balance[i]?.value ?: continue
            val a1 = amount[i]?.value
            val a0 = amount[i - 1]?.value
            if ((a1 == null || a1.signum() == 0) && (a0 == null || a0.signum() == 0)) continue
            pairs++
            val d = (b1 - b0).abs()
            if (a1 != null && (d - a1.abs()).abs() < eps) hitNext++
            if (a0 != null && (d - a0.abs()).abs() < eps) hitPrev++
        }
        return pairs >= 3 && maxOf(hitNext, hitPrev) >= 0.6 * pairs
    }

    private fun findDirectionColumn(cols: List<Col>, exclude: Set<Int>, rows: Int): Triple<Int, Set<String>, Set<String>>? {
        for (c in cols) {
            if (c.index in exclude || c.nonEmpty.isEmpty() || c.fill < 0.7 || c.distinct > 4 || c.distinct < 2) continue
            val values = c.nonEmpty.map { HeaderHints.normalize(it) }.toSet()
            val debit = values.filter { it in HeaderHints.DEBIT_TOKENS }.toSet()
            val credit = values.filter { it in HeaderHints.CREDIT_TOKENS && it !in debit }.toSet()
            if (debit.isNotEmpty() && credit.isNotEmpty()) return Triple(c.index, debit, credit)
        }
        return null
    }
}
