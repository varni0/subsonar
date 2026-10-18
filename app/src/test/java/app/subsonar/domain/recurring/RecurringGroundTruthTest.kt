package app.subsonar.domain.recurring

import app.subsonar.domain.importer.Corpus
import app.subsonar.domain.importer.InputFile
import app.subsonar.domain.importer.ParsedTransaction
import app.subsonar.domain.importer.StatementImporter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * End-to-end: matrix file (24 dialects of one ledger) -> importer -> detector, scored against synthetic/ground_truth.json.
 */
class RecurringGroundTruthTest {
    private val today = LocalDate.of(2026, 7, 1)
    private val truth = JSONObject(Corpus.resourceText("synthetic/ground_truth.json"))
    private val expected: List<Pair<String, JSONObject>> = truth.getJSONArray("expected_recurring").let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("merchant").lowercase().split(" ").first() to o } }
    }
    private val notRecurring: List<String> = truth.getJSONArray("expected_not_recurring").let { a ->
        (0 until a.length()).map { a.getString(it).lowercase().split(" ").first() }
    }
    private val optional = setOf("vpnguard") // one occurrence: "at most possible"
    private val expectedBills = setOf("rent")
    private val eitherKind = setOf("telco")

    private fun ledger(file: String): List<ParsedTransaction> =
        StatementImporter.analyze(InputFile(file, Corpus.resource("synthetic/matrix/$file")!!)).transactions

    private class Score(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0, var kindOk: Int = 0, var kindTotal: Int = 0) {
        val precision get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
    }

    private fun score(tx: List<ParsedTransaction>, s: Score, minScore: Double = RecurringDetector.MIN_OUTPUT_SCORE, trapOut: MutableList<String>? = null) {
        val out = RecurringDetector.detect(tx, today, "EUR").filter { it.score >= minScore }
        val matched = HashSet<String>()
        for (c in out) {
            val e = expected.firstOrNull { c.key.startsWith(it.first) }
            if (e == null) { if (!tx.isEmpty()) { s.fp++; trapOut?.add(c.key) } } else {
                matched.add(e.first)
                if (e.first in optional) continue
                s.tp++
                s.kindTotal++
                val ok = when (e.first) {
                    in eitherKind -> true
                    in expectedBills -> c.kind == CandidateKind.BILL
                    else -> c.kind == CandidateKind.SUBSCRIPTION
                }
                if (ok) s.kindOk++
            }
        }
        // Only merchants that actually have a charge in the window can be missed.
        val present = expected.filter { (m, _) -> m !in optional && tx.any { it.amount.signum() < 0 && MerchantNames.tokens(it.description).firstOrNull()?.lowercase() == m } }
        s.fn += present.count { it.first !in matched }
    }

    @Test fun fullLedgerInEveryDialectMatchesGroundTruth() {
        val s = Score()
        val fps = ArrayList<String>()
        val files = (1..24).map { "A%02d.csv".format(it) }
        val reference = RecurringDetector.detect(ledger("A01.csv"), today, "EUR").map { Triple(it.key, it.kind, it.cycle) }.toSet()
        for (f in files) {
            val tx = ledger(f)
            score(tx, s, trapOut = fps)
            val got = RecurringDetector.detect(tx, today, "EUR").map { Triple(it.key, it.kind, it.cycle) }.toSet()
            assertEquals("candidate set differs between A01 and $f", reference, got)
        }
        assertEquals("false positives: $fps", 0, s.fp)
        assertEquals(0, s.fn)
        assertEquals(s.kindTotal, s.kindOk)
    }

    @Test fun trapsAreNeverReportedAtAnyHistoryLength() {
        val all = ledger("A01.csv")
        val trapKeys = notRecurring
        for (months in listOf(1, 2, 3, 6, 12, 17)) {
            val from = YearMonth.of(2025, 1)
            val window = all.filter { YearMonth.from(it.date) >= from && YearMonth.from(it.date) < from.plusMonths(months.toLong()) }
            val out = RecurringDetector.detect(window, today, "EUR")
            for (c in out) assertFalse("trap '${c.key}' reported with $months months", trapKeys.any { c.key.startsWith(it) })
        }
    }

    @Test fun reportRecallAndPrecisionByHistoryLength() {
        val all = ledger("A01.csv")
        val sb = StringBuilder("## Recurring detector vs synthetic ground truth\n\n")
        val s = Score(); val fps = ArrayList<String>()
        score(all, s, trapOut = fps)
        sb.append("Full ledger (${all.map { YearMonth.from(it.date) }.distinct().size} months, ${all.size} rows; matrix A01): precision ${"%.2f".format(s.precision)}, recall ${"%.2f".format(s.recall)} " +
            "(TP ${s.tp}, FP ${s.fp}, FN ${s.fn}); subscription-vs-bill classification ${s.kindOk}/${s.kindTotal}\n\n")
        // all 24 dialects
        val s24 = Score()
        (1..24).forEach { score(ledger("A%02d.csv".format(it)), s24) }
        sb.append("All 24 dialect files: precision ${"%.2f".format(s24.precision)}, recall ${"%.2f".format(s24.recall)} (TP ${s24.tp}, FP ${s24.fp}, FN ${s24.fn})\n\n")
        sb.append("History-length sweep (first N months of the ledger; expected merchants only counted when they have a charge in the window):\n\n")
        sb.append("| months | candidates | TP | FP | FN | recall | precision | unconfirmed share of TP |\n|---|---|---|---|---|---|---|---|\n")
        for (months in listOf(1, 2, 3, 6, 9, 12, 17)) {
            val from = YearMonth.of(2025, 1)
            val window = all.filter { YearMonth.from(it.date) >= from && YearMonth.from(it.date) < from.plusMonths(months.toLong()) }
            val ws = Score(); score(window, ws)
            val out = RecurringDetector.detect(window, today, "EUR")
            val unc = out.count { c -> expected.any { c.key.startsWith(it.first) } && c.unconfirmed }
            sb.append("| $months | ${out.size} | ${ws.tp} | ${ws.fp} | ${ws.fn} | ${"%.2f".format(ws.recall)} | ${"%.2f".format(ws.precision)} | $unc/${ws.tp} |\n")
        }
        File("build/reports/csv-eval").apply { mkdirs() }.resolve("detector-metrics.md").writeText(sb.toString())
        println(sb)
        assertTrue(s.precision >= 0.95 && s.recall >= 0.95)
    }
}
