package app.subsonar.domain.importer

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A named blob handed to the importer (a picked file, or an entry unpacked from a zip). */
class InputFile(val name: String, val bytes: ByteArray)

/**
 * Entry point of the pure-Kotlin import pipeline. Every file is analysed independently and never throws:
 * failures are returned as [FileAnalysis.failure].
 */
object StatementImporter {
    const val MAX_FILE_BYTES = 25 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 60
    const val MAX_ZIP_TOTAL_BYTES = 120L * 1024 * 1024

    /**
     * @param savedMapping a mapping previously confirmed for the same fingerprint; used when it still fits.
     * @param savedMappingFor looks up the saved mapping by fingerprint (called after the layout is known).
     */
    fun analyze(file: InputFile, savedMappingFor: (String) -> ColumnMapping? = { null }): FileAnalysis =
        try {
            analyzeUnsafe(file, savedMappingFor)
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            failed(file.name, FileFailure.UNREADABLE)
        }

    private fun failed(name: String, f: FileFailure, kind: SourceKind = SourceKind.CSV, layout: DelimitedLayout? = null) =
        FileAnalysis(name, kind, layout, null, "", null, 0.0, emptyList(), failure = f)

    private fun analyzeUnsafe(file: InputFile, savedMappingFor: (String) -> ColumnMapping?): FileAnalysis {
        val bytes = file.bytes
        if (bytes.isEmpty()) return failed(file.name, FileFailure.EMPTY)
        if (bytes.size > MAX_FILE_BYTES) return failed(file.name, FileFailure.TOO_LARGE)
        if (looksBinary(bytes)) return failed(file.name, FileFailure.NOT_TEXT)

        val decoded = TextDecoder.decode(bytes)
        val text = decoded.text
        if (text.isBlank()) return failed(file.name, FileFailure.EMPTY)
        val warnings = ArrayList<ImportWarning>()
        if (decoded.encoding.contains("mixed")) warnings.add(ImportWarning.MIXED_ENCODING)

        if (Camt053Parser.looksLikeCamt(text)) {
            val tx = Camt053Parser.parse(text)
            if (tx != null && tx.isNotEmpty()) return openBanking(file.name, SourceKind.CAMT053, "camt053", tx, decoded.encoding)
            if (text.trimStart().startsWith("<")) return failed(file.name, FileFailure.NO_TRANSACTIONS, SourceKind.CAMT053)
        }
        if (Mt940Parser.looksLikeMt940(text)) {
            val tx = Mt940Parser.parse(text)
            if (tx.isNotEmpty()) return openBanking(file.name, SourceKind.MT940, "mt940", tx, decoded.encoding)
        }

        val layout = (if (text.contains("<table", ignoreCase = true) && text.contains("<tr", ignoreCase = true)) htmlLayout(text, decoded.encoding) else null)
            ?: LayoutDetector.detect(decoded)
            ?: return failed(file.name, FileFailure.NO_TABLE)
        val fingerprint = Fingerprints.delimited(layout)

        var mapping: ColumnMapping? = null
        var fromSaved = false
        var confidence = 0.0
        val inferred: ColumnInference.Result? = run {
            savedMappingFor(fingerprint)?.let { saved ->
                if (StatementParser.mappingFits(layout, saved)) {
                    val o = StatementParser.parse(layout, saved)
                    if (o.rowsRead > 0 && o.transactions.size >= 0.5 * o.rowsRead) {
                        mapping = saved; fromSaved = true; confidence = 1.0
                        return@run null
                    }
                }
            }
            ColumnInference.infer(layout)
        }
        if (inferred != null) {
            if (inferred.mapping == null) {
                return FileAnalysis(file.name, SourceKind.CSV, layout, null, fingerprint, null, 0.0, inferred.warnings.toList(),
                    failure = inferred.failure ?: FileFailure.NO_TABLE)
            }
            mapping = inferred.mapping
            confidence = inferred.confidence
            warnings.addAll(inferred.warnings)
        }
        val m = mapping!!
        val outcome = StatementParser.parse(layout, m)
        if (outcome.skipped.isNotEmpty() && outcome.transactions.isNotEmpty()) warnings.add(ImportWarning.SOME_ROWS_SKIPPED)
        if (confidence < 0.6 && !fromSaved) warnings.add(ImportWarning.LOW_CONFIDENCE)
        val failure = if (outcome.transactions.isEmpty()) FileFailure.NO_TRANSACTIONS else null
        return FileAnalysis(file.name, SourceKind.CSV, layout, m, fingerprint, outcome, confidence, warnings.distinct(), failure, fromSaved)
    }

    /** Re-run parsing after the user edited the mapping in the preview. */
    fun withMapping(analysis: FileAnalysis, mapping: ColumnMapping): FileAnalysis {
        val layout = analysis.layout ?: return analysis
        if (!StatementParser.mappingFits(layout, mapping)) return analysis
        val outcome = StatementParser.parse(layout, mapping)
        val warnings = analysis.warnings.filter {
            it != ImportWarning.SOME_ROWS_SKIPPED && it != ImportWarning.LOW_CONFIDENCE
        }.toMutableList()
        if (outcome.skipped.isNotEmpty() && outcome.transactions.isNotEmpty()) warnings.add(ImportWarning.SOME_ROWS_SKIPPED)
        return analysis.copy(
            mapping = mapping, outcome = outcome, warnings = warnings,
            failure = if (outcome.transactions.isEmpty()) FileFailure.NO_TRANSACTIONS else null,
            confidence = 1.0,
        )
    }

    private fun openBanking(name: String, kind: SourceKind, fp: String, tx: List<ParsedTransaction>, enc: String) =
        FileAnalysis(name, kind, null, null, fp, ParseOutcome(tx, emptyList(), tx.size), 0.95, emptyList(), encoding = enc)

    private fun looksBinary(b: ByteArray): Boolean {
        if (b.size >= 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) return true // zip/xlsx container
        if (b.size >= 4 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte()) return true // legacy xls/doc
        if (b.size >= 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte()) return true
        // UTF-16 text has many NULs but is handled by the decoder; only reject odd binary without BOM.
        val n = minOf(b.size, 8192)
        var ctrl = 0
        var nul = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) nul++
            else if (c < 9 || (c in 14..31)) ctrl++
        }
        val hasBom = b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))
        if (ctrl > n * 0.05) return true
        if (nul > n * 0.02 && !hasBom && nul < n * 0.25 && ctrl > 0) return true
        return false
    }

    private fun htmlLayout(text: String, encoding: String): DelimitedLayout? {
        val rows = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(text).map { tr ->
            Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>").findAll(tr.groupValues[1]).map { cell ->
                cell.groupValues[1].replace(Regex("(?s)<[^>]*>"), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace(Regex("\\s+"), " ").trim()
            }.toList()
        }.filter { r -> r.any { it.isNotEmpty() } }.toList()
        if (rows.size < 2) return null
        return LayoutDetector.locate(rows, '\t', "$encoding (HTML table)")
    }

    // ---------------- zip support ----------------

    /** Expands [file] if it is a zip archive into its CSV/XML/text entries (guarding against zip bombs); otherwise returns it as is. */
    fun expand(file: InputFile): List<InputFile> {
        val b = file.bytes
        if (!(b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && (b[2].toInt() == 3 || b[2].toInt() == 5))) return listOf(file)
        val out = ArrayList<InputFile>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(b)).use { zin ->
                while (out.size < MAX_ZIP_ENTRIES) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    if (name.startsWith(".") || e.name.startsWith("__MACOSX")) continue
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    var size = 0
                    var tooBig = false
                    while (true) {
                        val r = zin.read(chunk)
                        if (r < 0) break
                        size += r; total += r
                        if (size > MAX_FILE_BYTES || total > MAX_ZIP_TOTAL_BYTES) { tooBig = true; break }
                        buf.write(chunk, 0, r)
                    }
                    if (tooBig) {
                        out.add(InputFile(file.name + "/" + name, ByteArray(MAX_FILE_BYTES + 1)))
                        if (total > MAX_ZIP_TOTAL_BYTES) break
                    } else out.add(InputFile(file.name + "/" + name, buf.toByteArray()))
                }
            }
        } catch (_: Exception) {
            if (out.isEmpty()) return listOf(InputFile(file.name, ByteArray(0)))
        }
        return out.ifEmpty { listOf(InputFile(file.name, ByteArray(0))) }
    }
}

/** Stable identity for a transaction so re-imports and overlapping files do not create duplicates. */
object Dedupe {
    fun normalizeDescription(s: String): String =
        HeaderHints.normalize(s).replace(Regex("\\s+"), " ")

    fun baseKey(t: ParsedTransaction): String =
        "${t.date}|${t.amount.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }}|${normalizeDescription(t.description)}"

    /**
     * Assigns ids: `sha256(date|amount|normalizedDescription|occurrenceIndex)` where the occurrence index counts identical
     * (date, amount, description) rows within one file, in file order. Two genuine same-day duplicate purchases therefore
     * get different ids, while the same row appearing in two overlapping files gets the same id.
     */
    fun withIds(transactions: List<ParsedTransaction>): List<Pair<String, ParsedTransaction>> {
        val seen = HashMap<String, Int>()
        val md = MessageDigest.getInstance("SHA-256")
        return transactions.map { t ->
            val key = baseKey(t)
            val idx = seen.merge(key, 1, Int::plus)!! - 1
            val id = md.digest("$key|$idx".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
            id to t
        }
    }
}
