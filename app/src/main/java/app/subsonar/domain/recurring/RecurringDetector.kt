package app.subsonar.domain.recurring

import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.domain.RenewalCalculator
import app.subsonar.domain.importer.ParsedTransaction
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A step in the price of a recurring charge: from [from] to [to], first charged at [to] on [date]. */
data class PriceStep(val date: LocalDate, val from: BigDecimal, val to: BigDecimal)

enum class CandidateKind { SUBSCRIPTION, BILL, UNLIKELY }
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

enum class EvidenceType {
    SAME_AMOUNT, VARIABLE_AMOUNT, PRICE_CHANGE, REGULAR_INTERVAL, KEYWORD, KNOWN_SERVICE, MANDATE_ID, BILL_KEYWORD, LARGE_FIXED_AMOUNT,
    STORE_LIKE, EXTRA_CHARGES, FEW_OCCURRENCES, ENDED, ONLY_ONE_CHARGE, IRREGULAR_INTERVAL, FREE_TRIAL_BEFORE, IGNORED_OTHER_AMOUNTS,
}

/** One piece of evidence shown to the user. [detail] is a word/amount, [count] a number (occurrences, months...). */
data class Evidence(val type: EvidenceType, val detail: String? = null, val count: Int = 0)

data class RecurringCandidate(
    /** Normalized merchant key; stable across imports (used to avoid re-suggesting dismissed items). */
    val key: String,
    val displayName: String,
    val kind: CandidateKind,
    /** 0..1 */
    val score: Double,
    val confidence: ConfidenceLevel,
    /** True when based on too little history (fewer occurrences than needed to be sure) or keyword/known-service signals only. */
    val unconfirmed: Boolean,
    val cycle: BillingCycle,
    /** Latest charge amount (positive). For variable amounts: median of the last charges. */
    val amount: BigDecimal,
    val currency: String,
    val variableAmount: Boolean,
    val occurrences: Int,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val nextRenewal: LocalDate,
    /** No charge for more than ~1.6 cycles before the end of the data. */
    val possiblyEnded: Boolean,
    /** The latest price step (old to new), if the amount changed once or twice in an otherwise steady series. */
    val priceChange: Pair<BigDecimal, BigDecimal>?,
    val category: Category,
    val evidence: List<Evidence>,
    /** Date of the first charge at the new price of [priceChange]. */
    val priceChangeDate: LocalDate? = null,
    /** All detected price steps, oldest first (at most two). The last one equals [priceChange]. */
    val priceSteps: List<PriceStep> = emptyList(),
)

/**
 * Finds recurring charges in a transaction history. Pure Kotlin.
 *
 * Signals (see [RecurringDetector.detect]): interval regularity per cycle (with weekend tolerance), amount consistency,
 * one charge per period, subscription keywords / SEPA creditor+mandate ids, known service names, penalty for stores / fuel /
 * restaurants. Works on a single month at reduced confidence ("unconfirmed") and improves with more history.
 */
object RecurringDetector {
    const val MIN_OUTPUT_SCORE = 0.30

    private data class Charge(val date: LocalDate, val amount: BigDecimal, val currency: String, val text: String)

    fun detect(
        transactions: List<ParsedTransaction>,
        today: LocalDate = LocalDate.now(),
        defaultCurrency: String = "EUR",
        includeUnlikely: Boolean = false,
    ): List<RecurringCandidate> {
        val charges = transactions
            .filter { it.amount.signum() < 0 }
            .map { Charge(it.date, it.amount.abs(), (it.currency ?: defaultCurrency).uppercase(), it.description) }
        if (charges.isEmpty()) return emptyList()
        val trialRows = transactions.filter { it.amount.signum() == 0 }
        val dataEnd = transactions.maxOf { it.date }

        // Level 1: group by first meaningful token. Level 2 (fallback): first two tokens.
        val parsed = charges.map { it to MerchantNames.tokens(it.text) }.filter { it.second.isNotEmpty() }
        val level1 = parsed.groupBy { it.second.first() }
        val out = ArrayList<RecurringCandidate>()
        for ((_, members) in level1) {
            val whole = analyze(members.map { it.first }, members.map { it.second }, dataEnd, today, trialRows)
            val wholeGood = whole != null && whole.kind != CandidateKind.UNLIKELY && whole.score >= 0.5
            if (wholeGood || members.map { MerchantNames.key(it.second, 2) }.toSet().size == 1) {
                whole?.let(out::add)
            } else {
                val sub = members.groupBy { MerchantNames.key(it.second, 2) }
                val subCands = sub.values.mapNotNull { m -> analyze(m.map { it.first }, m.map { it.second }, dataEnd, today, trialRows) }
                val best = subCands.filter { it.kind != CandidateKind.UNLIKELY }
                if (best.isNotEmpty()) out.addAll(best) else whole?.let(out::add)
            }
        }
        return out
            .filter { includeUnlikely || (it.kind != CandidateKind.UNLIKELY && it.score >= MIN_OUTPUT_SCORE) }
            .sortedWith(compareBy<RecurringCandidate> { it.kind.ordinal }.thenByDescending { it.score })
    }

    // ------------------------------------------------------------------------------------------------

    private class Cycle(val cycle: BillingCycle, val days: Double, val tolerance: Int, val minOccurrences: Int)

    private val CYCLES = listOf(
        Cycle(BillingCycle.WEEKLY, 7.0, 2, 4),
        Cycle(BillingCycle.MONTHLY, 30.44, 5, 3),
        Cycle(BillingCycle.QUARTERLY, 91.3, 7, 3),
        Cycle(BillingCycle.YEARLY, 365.25, 10, 2),
    )

    private fun cycleFor(medianGap: Double): Cycle? = when {
        medianGap in 5.0..9.5 -> CYCLES[0]
        medianGap in 24.0..38.0 -> CYCLES[1]
        medianGap in 80.0..102.0 -> CYCLES[2]
        medianGap in 335.0..395.0 -> CYCLES[3]
        else -> null
    }

    private class SeriesEval(
        val dates: List<LocalDate>, val amounts: List<BigDecimal>, val cycle: Cycle?, val regularity: Double,
        val fixedShare: Double, val variable: Boolean, val priceChange: Pair<BigDecimal, BigDecimal>?, val extraRatio: Double,
        val sameDay: Int, val steps: List<PriceStep> = emptyList(),
    )

    private fun analyze(
        group: List<Charge>, tokenLists: List<List<String>>, dataEnd: LocalDate, today: LocalDate, trialRows: List<ParsedTransaction>,
    ): RecurringCandidate? {
        val currency = group.groupingBy { it.currency }.eachCount().maxBy { it.value }.key
        val sameCurrency = group.filter { it.currency == currency }.sortedBy { it.date }
        if (sameCurrency.isEmpty()) return null
        val ignoredOther = group.size - sameCurrency.size

        val tokens = tokenLists.flatten()
        val tokenSet = tokens.map { it.lowercase() }.toSet()
        val joined = tokens.joinToString("").lowercase()
        val firstTokens = tokenLists.firstOrNull().orEmpty()
        val nameTokens = if (tokenLists.map { MerchantNames.key(it, 2) }.toSet().size == 1) MerchantNames.nameLength(firstTokens) else 1
        val name = MerchantNames.display(firstTokens, nameTokens)
        val keyStr = MerchantNames.key(firstTokens, nameTokens)

        // ---- text signals ----
        val knownHit = tokenSet.firstOrNull { it in Vocabulary.KNOWN_SERVICES } ?: Vocabulary.KNOWN_SERVICES.firstOrNull { k -> k.length >= 6 && joined.startsWith(k) }
        val allText = group.joinToString(" ") { it.text }
        val normalizedText = MerchantNames.fold(allText)
        val kwStrong = tokenSet.firstOrNull { it in Vocabulary.SUBSCRIPTION_KEYWORDS && it !in Vocabulary.WEAK_KEYWORDS }
        val kwWeak = tokenSet.firstOrNull { it in Vocabulary.WEAK_KEYWORDS }
        val mandate = Regex("""\b[A-Z]{2}\d{2}ZZZ[A-Z0-9]{3,28}\b|\b(mandate|mandat|mandats?referenz|mndt|creditor ?id|glaubiger ?id|incassant|machtiging|autogiro|avtalegiro|betalingsservice|direct debit)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalizedText)
        val billKeyword = tokenSet.firstOrNull { it in Vocabulary.BILL_KEYWORDS }
        val weakBill = tokenSet.firstOrNull { it in Vocabulary.AMBIGUOUS_BILL_WORDS }
        val storeHit = tokenSet.firstOrNull { it in Vocabulary.STORE_TOKENS } ?: tokens.firstOrNull { t ->
            val l = t.lowercase(); l.length >= 7 && (l.endsWith("mart") || l.endsWith("bakery") || l.endsWith("market"))
        }?.lowercase()
        val yearlyWord = tokenSet.firstOrNull { it in Vocabulary.YEARLY_WORDS }
        val quarterlyWord = tokenSet.firstOrNull { it in Vocabulary.QUARTERLY_WORDS }

        // ---- candidate series: everything, and the dominant amount cluster (ignores one-off odd charges) ----
        val evals = ArrayList<SeriesEval>()
        evals.add(evaluate(sameCurrency))
        dominantCluster(sameCurrency)?.let { cl -> if (cl.size < sameCurrency.size) evals.add(evaluate(cl)) }
        val maxN = evals.maxOf { it.dates.size }
        val best = evals.maxBy { seriesQuality(it) * (0.4 + 0.6 * it.dates.size / maxN) }

        val dates = best.dates
        val n = dates.size
        val evidence = ArrayList<Evidence>()
        val cycleKnown = best.cycle != null

        var cycle = best.cycle?.cycle
        // ---- score ----
        var score: Double
        val minOcc = best.cycle?.minOccurrences ?: 3
        if (n >= 2 && cycleKnown) {
            val cf = when {
                n >= minOcc + 1 -> 1.0
                n == minOcc -> 0.75
                else -> 0.4
            }
            val amountPart = when {
                best.fixedShare >= 0.8 -> 1.0
                best.variable -> 0.55
                else -> best.fixedShare * 0.6
            }
            score = 0.40 * best.regularity + 0.25 * amountPart + 0.20 * cf + 0.15 * (1 - min(1.0, best.extraRatio * 2.5))
            val cap = when {
                n == 2 -> if (cycle == BillingCycle.YEARLY) 0.62 else 0.58
                n == 3 && cycle != BillingCycle.YEARLY && cycle != BillingCycle.QUARTERLY -> 0.82
                else -> 1.0
            }
            score = min(score, cap)
            evidence.add(Evidence(EvidenceType.REGULAR_INTERVAL, cycle?.name, n))
            if (best.fixedShare >= 0.8) evidence.add(Evidence(EvidenceType.SAME_AMOUNT, best.amounts.last().toPlainString(), n))
            else if (best.variable) evidence.add(Evidence(EvidenceType.VARIABLE_AMOUNT))
            best.priceChange?.let { evidence.add(Evidence(EvidenceType.PRICE_CHANGE, "${it.first.toPlainString()}→${it.second.toPlainString()}")) }
            if (n < minOcc) evidence.add(Evidence(EvidenceType.FEW_OCCURRENCES, null, n))
            if (best.extraRatio > 0.15) evidence.add(Evidence(EvidenceType.EXTRA_CHARGES))
        } else if (n >= 3) {
            score = 0.05 + 0.1 * best.regularity
            evidence.add(Evidence(EvidenceType.IRREGULAR_INTERVAL, null, n))
        } else {
            score = 0.0
            if (n == 1 || !cycleKnown) evidence.add(Evidence(EvidenceType.ONLY_ONE_CHARGE, null, n))
        }
        if (ignoredOther > 0) evidence.add(Evidence(EvidenceType.IGNORED_OTHER_AMOUNTS, null, ignoredOther))
        if (best.sameDay > 0) score -= 0.05

        // ---- text boosts / penalties ----
        var textBoost = 0.0
        if (knownHit != null) { textBoost += 0.16; evidence.add(Evidence(EvidenceType.KNOWN_SERVICE, knownHit)) }
        if (kwStrong != null) { textBoost += 0.10; evidence.add(Evidence(EvidenceType.KEYWORD, kwStrong)) }
        else if (kwWeak != null) { textBoost += 0.04 }
        if (mandate) { textBoost += 0.05; evidence.add(Evidence(EvidenceType.MANDATE_ID)) }
        if (billKeyword != null) { evidence.add(Evidence(EvidenceType.BILL_KEYWORD, billKeyword)) }
        val regular = n >= 2 && cycleKnown && best.regularity >= 0.7
        if (storeHit != null && knownHit == null) {
            score -= if (regular && best.fixedShare >= 0.8) 0.20 else 0.30
            evidence.add(Evidence(EvidenceType.STORE_LIKE, storeHit))
        }
        if (regular) score += textBoost else score += 0.0

        // ---- single / unclear history: text-only signals, flagged unconfirmed ----
        var textOnly = false
        if (!regular) {
            val textScore = when {
                knownHit != null && kwStrong != null -> 0.58
                knownHit != null -> 0.52
                kwStrong != null && (mandate || n >= 1) -> 0.44
                (yearlyWord != null || quarterlyWord != null) && n == 1 && storeHit == null -> 0.34
                mandate && billKeyword != null -> 0.40
                mandate -> 0.34
                else -> 0.0
            }
            if (storeHit != null && knownHit == null) { /* no text-only signal for stores */ }
            else if (textScore > score && n <= 2) { score = textScore; textOnly = true }
            else if (textScore > 0.0 && score < 0.3 && n <= 2) { score = max(score, min(textScore, 0.40)); textOnly = true }
        }
        if (textOnly) {
            // keep evidence for the signals that triggered
            if (knownHit == null && kwStrong != null && evidence.none { it.type == EvidenceType.KEYWORD }) evidence.add(Evidence(EvidenceType.KEYWORD, kwStrong))
        }
        // Weekly "subscriptions" are rare; without a word or brand backing them they are mostly groceries/lunch habits.
        if (cycle == BillingCycle.WEEKLY && knownHit == null && kwStrong == null) score = min(score, 0.25)

        // Cycle guess when nothing regular was found.
        if (cycle == null) {
            cycle = when {
                yearlyWord != null -> BillingCycle.YEARLY
                quarterlyWord != null -> BillingCycle.QUARTERLY
                else -> BillingCycle.MONTHLY
            }
        }
        if (n == 1 || !cycleKnown) {
            if (yearlyWord != null) cycle = BillingCycle.YEARLY else if (quarterlyWord != null) cycle = BillingCycle.QUARTERLY
        }

        // ---- ended? ----
        val cycleDays = when (cycle) { BillingCycle.WEEKLY -> 7.0; BillingCycle.MONTHLY -> 30.44; BillingCycle.QUARTERLY -> 91.3; BillingCycle.YEARLY -> 365.25 }
        val last = dates.last()
        val possiblyEnded = ChronoUnit.DAYS.between(last, dataEnd) > 1.6 * cycleDays
        if (possiblyEnded && n >= 2) { score *= 0.8; evidence.add(Evidence(EvidenceType.ENDED)) }

        // Trial row (0.00) shortly before the first charge.
        if (trialRows.any { t -> MerchantNames.tokens(t.description).firstOrNull() == tokenLists.first().first() && t.date <= dates.first() && ChronoUnit.DAYS.between(t.date, dates.first()) <= 45 }) {
            evidence.add(Evidence(EvidenceType.FREE_TRIAL_BEFORE))
        }

        score = score.coerceIn(0.0, 0.97)

        // ---- amount, kind ----
        val shown = if (best.variable && best.fixedShare < 0.8) median(best.amounts.takeLast(3)) else best.amounts.last()
        val threshold = Vocabulary.largeFixedThreshold(currency)
        val largeFixed = cycle != BillingCycle.WEEKLY && best.fixedShare >= 0.8 && monthlyEquivalent(shown, cycle).toDouble() >= threshold && n >= 2
        if (largeFixed) evidence.add(Evidence(EvidenceType.LARGE_FIXED_AMOUNT, shown.toPlainString()))
        val isBill = billKeyword != null && kwStrong == null && knownHit == null ||
            (billKeyword != null && knownHit == null) ||
            largeFixed && knownHit == null ||
            (weakBill != null && knownHit == null && kwStrong == null && best.variable)
        var kind = if (isBill) CandidateKind.BILL else CandidateKind.SUBSCRIPTION
        if (score < MIN_OUTPUT_SCORE) kind = CandidateKind.UNLIKELY

        val confidence = when {
            score >= 0.75 -> ConfidenceLevel.HIGH
            score >= 0.50 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
        val unconfirmed = textOnly || n < minOcc
        val next = RenewalCalculator.nextRenewal(last, cycle, today)
        val category = Vocabulary.guessCategory(tokens)
        return RecurringCandidate(
            key = keyStr, displayName = name, kind = kind, score = score, confidence = confidence, unconfirmed = unconfirmed,
            cycle = cycle, amount = shown.setScale(maxOf(shown.scale(), 2), java.math.RoundingMode.HALF_UP), currency = currency,
            variableAmount = best.variable && best.fixedShare < 0.8, occurrences = n, firstDate = dates.first(), lastDate = last,
            nextRenewal = next, possiblyEnded = possiblyEnded, priceChange = best.priceChange,
            priceChangeDate = best.steps.lastOrNull()?.date, priceSteps = best.steps,
            category = category, evidence = evidence,
        )
    }

    private fun monthlyEquivalent(a: BigDecimal, c: BillingCycle): BigDecimal = when (c) {
        BillingCycle.WEEKLY -> a.multiply(BigDecimal("4.345"))
        BillingCycle.MONTHLY -> a
        BillingCycle.QUARTERLY -> a.divide(BigDecimal(3), 2, java.math.RoundingMode.HALF_UP)
        BillingCycle.YEARLY -> a.divide(BigDecimal(12), 2, java.math.RoundingMode.HALF_UP)
    }

    private fun median(v: List<BigDecimal>): BigDecimal = v.sorted().let { it[it.size / 2] }

    private fun seriesQuality(e: SeriesEval): Double =
        if (e.cycle == null || e.dates.size < 2) 0.0 else e.regularity * (0.5 + 0.5 * e.fixedShare) * min(1.0, e.dates.size / 4.0 + 0.25)

    /** Largest group of charges with (almost) the same amount, when it has at least two members. */
    private fun dominantCluster(ch: List<Charge>): List<Charge>? {
        var bestSize = 0
        var best: List<Charge>? = null
        for (c in ch.distinctBy { it.amount.setScale(2, java.math.RoundingMode.HALF_UP) }) {
            val tol = c.amount.multiply(BigDecimal("0.02")).max(BigDecimal("0.05"))
            val members = ch.filter { (it.amount - c.amount).abs() <= tol }
            if (members.size > bestSize) { bestSize = members.size; best = members }
        }
        return best?.takeIf { it.size >= 2 }
    }

    private fun evaluate(ch: List<Charge>): SeriesEval {
        // Collapse same-day charges (keep one; count the rest as suspicious extras).
        val byDay = ch.groupBy { it.date }.toSortedMap()
        val dates = byDay.keys.toList()
        val amounts = byDay.values.map { it.first().amount }
        val sameDay = ch.size - dates.size
        if (dates.size < 2) return SeriesEval(dates, amounts, null, 0.0, 1.0, false, null, 0.0, sameDay)
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toDouble() }
        val med = gaps.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val cyc = cycleFor(med)
        // amounts
        val last = amounts.last()
        val tol = last.multiply(BigDecimal("0.02")).max(BigDecimal("0.05"))
        val fixedShareLast = amounts.count { (it - last).abs() <= tol }.toDouble() / amounts.size
        val mode = dominantAmountShare(amounts)
        var changes = 0
        for (i in 1 until amounts.size) {
            val t = amounts[i - 1].multiply(BigDecimal("0.02")).max(BigDecimal("0.05"))
            if ((amounts[i] - amounts[i - 1]).abs() > t) changes++
        }
        var priceChange: Pair<BigDecimal, BigDecimal>? = null
        var steps: List<PriceStep> = emptyList()
        var fixedShare = mode
        if (changes in 1..2 && amounts.size >= 3 && fixedShareLast >= 0.2) {
            // step changes (price hikes) keep the series "fixed" when the level changes are few
            fixedShare = max(mode, 1.0 - changes.toDouble() / (amounts.size - 1) * 1.5)
            val idx = (1 until amounts.size).last { (amounts[it] - amounts[it - 1]).abs() > amounts[it - 1].multiply(BigDecimal("0.02")).max(BigDecimal("0.05")) }
            priceChange = amounts[idx - 1] to amounts[idx]
            steps = (1 until amounts.size).filter { (amounts[it] - amounts[it - 1]).abs() > amounts[it - 1].multiply(BigDecimal("0.02")).max(BigDecimal("0.05")) }
                .map { PriceStep(dates[it], amounts[it - 1], amounts[it]) }
        }
        val med2 = amounts.sorted()[amounts.size / 2]
        val spread = amounts.maxOf { ((it - med2).abs().toDouble() / max(med2.toDouble(), 0.01)) }
        val variable = fixedShare < 0.8 && spread <= 0.30 && amounts.size >= 3

        // regularity relative to the cycle
        if (cyc == null) return SeriesEval(dates, amounts, null, 0.0, fixedShare, variable, priceChange, 0.0, sameDay, steps)
        var credit = 0.0
        var extra = 0
        for (i in 1 until dates.size) {
            val a = dates[i - 1]; val b = dates[i]
            val gap = ChronoUnit.DAYS.between(a, b).toDouble()
            val k = max(1, (gap / cyc.days).roundToInt())
            val dev: Long = when (cyc.cycle) {
                BillingCycle.WEEKLY -> abs(ChronoUnit.DAYS.between(a.plusWeeks(k.toLong()), b))
                BillingCycle.MONTHLY -> abs(ChronoUnit.DAYS.between(a.plusMonths(k.toLong()), b))
                BillingCycle.QUARTERLY -> abs(ChronoUnit.DAYS.between(a.plusMonths(3L * k), b))
                BillingCycle.YEARLY -> abs(ChronoUnit.DAYS.between(a.plusYears(k.toLong()), b))
            }
            val tolDays = cyc.tolerance
            if (gap < cyc.days * 0.5) { extra++; continue }
            if (dev <= tolDays) credit += if (k == 1) 1.0 else if (k == 2) 0.6 else 0.35
        }
        val regularity = credit / gaps.size
        return SeriesEval(dates, amounts, cyc, regularity.coerceIn(0.0, 1.0), fixedShare, variable, priceChange, extra.toDouble() / gaps.size, sameDay, steps)
    }

    private fun dominantAmountShare(amounts: List<BigDecimal>): Double {
        var best = 0
        for (a in amounts) {
            val tol = a.multiply(BigDecimal("0.02")).max(BigDecimal("0.05"))
            best = max(best, amounts.count { (it - a).abs() <= tol })
        }
        return best.toDouble() / amounts.size
    }
}

/** Normalizes noisy bank text into merchant tokens / keys. */
object MerchantNames {
    private val TLD = Regex("""\.(COM|NO|SE|DK|FI|DE|NL|FR|ES|IT|PT|PL|CZ|HU|NET|ORG|IO|EU|CO\.UK|UK|AT|CH|BE|IE|APP|TV|ME)$""")

    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('ø', 'o').replace('Ø', 'O').replace('æ', 'a').replace('Æ', 'A').replace('ß', 's')

    /** Meaningful upper-case tokens of a description (digits-heavy words, card boilerplate and TLDs removed). */
    fun tokens(text: String): List<String> {
        var s = fold(text).uppercase()
        s = s.replace(Regex("APPLE\\s*PAY|GOOGLE\\s*PAY|GOOGLE\\s*WALLET|PAYPAL\\s*\\*"), " ")
        s = s.replace(Regex("[*/|_,;:()#\\[\\]\"'+=<>@]|\\s-\\s"), " ")
        val out = ArrayList<String>()
        for (raw in s.split(Regex("\\s+"))) {
            var t = raw.trim('.', '-', '&')
            if (t.isEmpty()) continue
            t = TLD.replace(t, "")
            if (t.length < 2) continue
            val digits = t.count { it.isDigit() }
            if (digits > 0 && (digits.toDouble() / t.length >= 0.34 || t.length <= 3 && digits > 0)) continue
            if (t in Vocabulary.NOISE) continue
            if (t.all { !it.isLetter() }) continue
            out.add(t)
        }
        return out
    }

    /** A long first word is a brand by itself ("STREAMFLIX"); a short one needs its neighbour ("GYM CITY"). */
    fun nameLength(tokens: List<String>): Int = if (tokens.size < 2 || tokens[0].length >= 7) 1 else 2

    fun key(tokens: List<String>, n: Int): String = tokens.take(n).joinToString(" ").lowercase()

    fun display(tokens: List<String>, n: Int): String =
        tokens.take(n).joinToString(" ") { t -> t.lowercase().replaceFirstChar { c -> c.uppercase() } }
}
