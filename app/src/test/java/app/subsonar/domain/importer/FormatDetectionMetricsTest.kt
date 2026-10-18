package app.subsonar.domain.importer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Computes format-detection accuracy over the whole corpus and over the held-out subset (see HELDOUT.txt:
 * chosen by SHA-1 of the path before any heuristics were written; the heuristics were developed against the
 * other files only). Writes build/reports/csv-eval/format-metrics.md.
 */
class FormatDetectionMetricsTest {
    private class FileResult(val entry: Corpus.Entry, val checks: Map<String, Boolean>) { val allOk get() = checks.values.all { it } }

    private fun run(entries: List<Corpus.Entry>): List<FileResult> = entries.mapNotNull { e ->
        val bytes = e.bytes() ?: return@mapNotNull null // GPL sample not available
        val a = StatementImporter.analyze(InputFile(e.path, bytes))
        FileResult(e, CorpusChecks.evaluate(e, bytes, a))
    }

    private fun table(title: String, rs: List<FileResult>): String {
        val sb = StringBuilder("### $title (${rs.size} files)\n\n| check | passed | accuracy |\n|---|---|---|\n")
        for (c in CorpusChecks.CHECKS) {
            val applicable = rs.filter { c in it.checks }
            if (applicable.isEmpty()) continue
            val ok = applicable.count { it.checks[c] == true }
            sb.append("| $c | $ok/${applicable.size} | ${"%.1f".format(100.0 * ok / applicable.size)}% |\n")
        }
        val full = rs.count { it.allOk }
        sb.append("| **all checks for a file** | $full/${rs.size} | **${"%.1f".format(100.0 * full / rs.size)}%** |\n\n")
        return sb.toString()
    }

    @Test
    fun reportAndGuardAccuracy() {
        val all = run(Corpus.entries)
        val held = all.filter { it.entry.heldOut }
        val dev = all.filter { !it.entry.heldOut }
        val report = buildString {
            append("## Format detection accuracy\n\n")
            append(table("All files", all)); append(table("Development files", dev)); append(table("HELD-OUT files", held))
            val failing = all.filter { !it.allOk }
            if (failing.isNotEmpty()) {
                append("Files with at least one failed check:\n\n")
                failing.forEach { append("- ${if (it.entry.heldOut) "[held-out] " else ""}${it.entry.path}: ${it.checks.filterValues { v -> !v }.keys}\n") }
            }
        }
        File("build/reports/csv-eval").apply { mkdirs() }.resolve("format-metrics.md").writeText(report)
        println(report)
        assertTrue("overall full-file accuracy too low\n$report", all.count { it.allOk } >= 0.9 * all.size)
        assertTrue("held-out full-file accuracy too low\n$report", held.isEmpty() || held.count { it.allOk } >= 0.8 * held.size)
    }
}
