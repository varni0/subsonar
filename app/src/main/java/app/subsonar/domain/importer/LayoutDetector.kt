package app.subsonar.domain.importer

/**
 * Finds the structure of a delimited bank export: encoding, delimiter, and where the header and the
 * data begin (skipping preamble lines such as account/period info, blank lines and stray rows).
 */
object LayoutDetector {
    private val CANDIDATES = charArrayOf(';', ',', '\t', '|')
    private const val SAMPLE_CHARS = 120_000

    fun detect(bytes: ByteArray): DelimitedLayout? = detect(TextDecoder.decode(bytes))

    fun detect(decoded: DecodedText): DelimitedLayout? {
        val text = decoded.text
        if (text.isBlank()) return null
        val delimiter = detectDelimiter(text) ?: return null
        val rows = CsvTokenizer.tokenize(text, delimiter)
        if (rows.isEmpty()) return null
        return locate(rows, delimiter, decoded.encoding)
    }

    /** Most plausible delimiter, or null if no candidate yields a multi-column table. */
    fun detectDelimiter(text: String): Char? {
        var sample = if (text.length > SAMPLE_CHARS) text.substring(0, SAMPLE_CHARS) else text
        if (text.length > SAMPLE_CHARS) sample = sample.substring(0, maxOf(sample.lastIndexOf('\n'), 1))
        var best: Char? = null
        var bestScore = 0.0
        for (d in CANDIDATES) {
            val rows = CsvTokenizer.tokenize(sample, d, maxRows = 400)
            if (rows.isEmpty()) continue
            val widths = rows.map { it.size }
            val multi = widths.filter { it >= 2 }
            if (multi.size < maxOf(1, rows.size / 4)) continue
            val modal = multi.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }).first().key
            val exact = widths.count { it == modal }
            val near = widths.count { it == modal - 1 || it == modal + 1 }
            val consistency = (exact + 0.25 * near) / rows.size
            val score = consistency + 0.03 * minOf(modal, 12)
            if (score > bestScore + 1e-9) { bestScore = score; best = d }
        }
        return best
    }

    /** True if the row has a date-like and a number-like cell (a transaction row, whatever the columns). */
    internal fun isDataLike(row: List<String>): Boolean {
        var date = false
        var number = false
        for (c in row) {
            if (c.isBlank()) continue
            if (DateParsing.isDateLike(c)) date = true
            else if (NumberParsing.parse(c) != null) number = true
            if (date && number) return true
        }
        return false
    }

    private fun dateColumns(row: List<String>): Set<Int> =
        row.indices.filter { DateParsing.isDateLike(row[it]) }.toSet()

    private fun isHeaderLike(row: List<String>): Boolean {
        val nonEmpty = row.filter { it.isNotBlank() }
        if (nonEmpty.size < 2) return false
        return nonEmpty.none { DateParsing.isDateLike(it) || NumberParsing.parse(it) != null }
    }

    fun locate(rows: List<List<String>>, delimiter: Char, encoding: String): DelimitedLayout? {
        val flags = BooleanArray(rows.size) { isDataLike(rows[it]) }
        var first = -1
        for (i in rows.indices) {
            if (!flags[i]) continue
            val cols = dateColumns(rows[i])
            val window = (i + 1..minOf(i + 5, rows.lastIndex))
            val support = window.count { j -> flags[j] && dateColumns(rows[j]).any { it in cols } }
            if (window.isEmpty() || support >= minOf(2, window.count())) { first = i; break }
        }
        if (first < 0) return null
        var header: Int? = null
        var j = first - 1
        while (j >= 0 && first - j <= 4) {
            if (isHeaderLike(rows[j])) { header = j; break }
            j--
        }
        // A "header" far narrower than the data is a preamble line, not the column names.
        val width = rows.subList(first, rows.size).maxOf { it.size }
        if (header != null && rows[header].size < width / 2) header = null
        val columnCount = maxOf(width, header?.let { rows[it].size } ?: 0)
        // With a header, everything below it is the data region (rows without a valid date are reported as skipped).
        return DelimitedLayout(encoding, delimiter, header, if (header != null) header + 1 else first, rows, columnCount)
    }
}
