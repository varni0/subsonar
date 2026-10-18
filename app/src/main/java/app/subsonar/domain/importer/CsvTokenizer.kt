package app.subsonar.domain.importer

/**
 * Quote-aware, forgiving delimited-text tokenizer.
 *
 * - quotes only open a quoted field at the start of a field; `""` is an escaped quote
 * - a quote inside a quoted field closes it only when followed by delimiter / line end;
 *   otherwise it is kept literally (real banks emit malformed quotes)
 * - an unterminated or implausibly long quoted field is re-read with that quote treated as literal
 * - blank lines are dropped, rows may have different widths (ragged)
 */
object CsvTokenizer {
    private const val MAX_QUOTED_NEWLINES = 12
    private const val MAX_QUOTED_LENGTH = 4000

    fun tokenize(text: String, delimiter: Char, maxRows: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val n = text.length
        var i = 0
        val literalQuotes = HashSet<Int>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var rowHasContent = false

        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (rowHasContent || row.size > 1) rows.add(row)
            row = ArrayList()
            rowHasContent = false
        }

        while (i < n && rows.size < maxRows) {
            val c = text[i]
            when {
                c == delimiter -> { endField(); rowHasContent = true; i++ }
                c == '\r' -> { endRow(); i++; if (i < n && text[i] == '\n') i++ }
                c == '\n' -> { endRow(); i++ }
                c == '"' && field.isBlank() && i !in literalQuotes -> {
                    // Try to read a quoted field.
                    val end = readQuoted(text, i, delimiter)
                    if (end == null) {
                        literalQuotes.add(i) // re-process this position as a literal quote
                    } else {
                        field.setLength(0)
                        field.append(end.first)
                        i = end.second
                        rowHasContent = true
                    }
                }
                else -> { field.append(c); if (!c.isWhitespace()) rowHasContent = true; i++ }
            }
        }
        if (rows.size < maxRows && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return rows
    }

    /** Returns (field content, index after closing quote) or null when it is not a sane quoted field. */
    private fun readQuoted(text: String, start: Int, delimiter: Char): Pair<String, Int>? {
        val n = text.length
        val sb = StringBuilder()
        var i = start + 1
        var newlines = 0
        while (i < n) {
            val c = text[i]
            if (c == '"') {
                if (i + 1 < n && text[i + 1] == '"') { sb.append('"'); i += 2; continue }
                // closing quote only if followed by delimiter / EOL / EOF (optionally after blanks)
                var j = i + 1
                while (j < n && (text[j] == ' ' || text[j] == '\t') && text[j] != delimiter) j++
                if (j >= n || text[j] == delimiter || text[j] == '\n' || text[j] == '\r') {
                    return sb.toString() to j
                }
                sb.append('"'); i++; continue
            }
            if (c == '\n') { newlines++; if (newlines > MAX_QUOTED_NEWLINES) return null }
            if (sb.length > MAX_QUOTED_LENGTH) return null
            sb.append(c)
            i++
        }
        return null // unterminated
    }
}
