package app.subsonar.domain.importer

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Per-file format-detection tests over the development subset of the sample corpus.
 * (Held-out files are only evaluated in [HeldOutEvaluationTest].)
 */
@RunWith(Parameterized::class)
class CorpusFormatDetectionTest(private val entry: Corpus.Entry) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun files(): List<Corpus.Entry> = Corpus.entries.filter { !it.heldOut }
    }

    @Test
    fun detectsFormatCorrectly() {
        val bytes = entry.bytes()
        assumeTrue("GPL sample ${entry.path} not available (set -PcsvSamples.dir)", bytes != null)
        val analysis = StatementImporter.analyze(InputFile(entry.path, bytes!!))
        val results = CorpusChecks.evaluate(entry, bytes, analysis)
        val failed = results.filterValues { !it }.keys
        assertTrue("${entry.path}: failed checks $failed (mapping=${analysis.mapping}, enc=${analysis.encoding}, rows=${analysis.outcome?.transactions?.size}, expected=${entry.rows}, fail=${analysis.failure})", failed.isEmpty())
    }
}
