package app.subsonar.domain.importer

/** Compares an importer analysis with a corpus expectation; returns check name -> passed. */
object CorpusChecks {
    val CHECKS = listOf("parsed", "delimiter", "encoding", "datePattern", "dateColumn", "amountColumns", "direction", "decimal", "header", "rows")

    fun encodingClass(enc: String): String = when {
        enc.startsWith("UTF-16") -> "utf16"
        enc.startsWith("windows-125") || enc.startsWith("ISO-8859") || enc.contains("mixed") -> "single"
        else -> "utf8" // UTF-8 (also what pure ASCII is reported as)
    }

    fun evaluate(e: Corpus.Entry, bytes: ByteArray, a: FileAnalysis): Map<String, Boolean> {
        val r = LinkedHashMap<String, Boolean>()
        val m = a.mapping
        // Delimiter and encoding are checked independently of whether later stages succeed.
        val decoded = TextDecoder.decode(bytes)
        if (!e.masked) r["parsed"] = m != null && a.outcome != null
        e.delimiter?.let { r["delimiter"] = LayoutDetector.detectDelimiter(decoded.text) == it }
        e.encodingClass?.let { exp ->
            val got = encodingClass(decoded.encoding)
            r["encoding"] = got == exp || (exp == "ascii" && got == "utf8")
        }
        if (e.masked) return r
        e.datePattern?.let { r["datePattern"] = m != null && normalizePattern(m.datePattern) == normalizePattern(it) }
        if (e.dateCols.isNotEmpty()) r["dateColumn"] = m != null && m.dateColumn in e.dateCols
        if (e.debitCol != null) r["amountColumns"] = m != null && m.debitColumn == e.debitCol && m.creditColumn == e.creditCol
        else if (e.amountCols.isNotEmpty()) {
            r["amountColumns"] = m != null && (m.amountColumn in e.amountCols ||
                (e.altSplit.size == 2 && m.debitColumn == e.altSplit[0] && m.creditColumn == e.altSplit[1]))
        }
        if (e.dirCol != null) r["direction"] = m != null && m.directionColumn == e.dirCol
        e.decimal?.let { r["decimal"] = m != null && m.decimalSeparator == it }
        e.hasHeader?.let { r["header"] = a.layout != null && (a.layout.headerRow != null) == it }
        e.rows?.let { r["rows"] = a.outcome?.transactions?.size == it }
        e.rowsRange?.let { r["rows"] = (a.outcome?.transactions?.size ?: -1) in it }
        return r
    }

    private fun normalizePattern(p: String) = p.removeSuffix(".").replace("yyyy", "yy").replace("MMM", "MM")
}
