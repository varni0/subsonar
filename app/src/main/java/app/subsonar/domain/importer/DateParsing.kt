package app.subsonar.domain.importer

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/**
 * A date layout, e.g. `dd.MM.yyyy`. Parsing is lenient about zero padding (1.3.2025 matches dd.MM.yyyy)
 * and ignores a trailing time-of-day.
 *
 * [order] is a permutation of "D","M","Y" (e.g. "DMY"). [sep] is the separator ("" for compact yyyyMMdd).
 */
data class DatePattern(
    val order: String,
    val sep: String,
    val yearDigits: Int,
    val monthText: Boolean = false,
    val trailingDot: Boolean = false,
) {
    val pattern: String
        get() {
            val parts = order.map {
                when (it) {
                    'D' -> "dd"
                    'M' -> if (monthText) "MMM" else "MM"
                    else -> if (yearDigits == 2) "yy" else "yyyy"
                }
            }
            return parts.joinToString(sep) + if (trailingDot) "." else ""
        }

    override fun toString() = pattern

    companion object {
        fun fromPattern(p: String): DatePattern? {
            val trailingDot = p.endsWith(".") && p.dropLast(1).any { it.isLetter() }
            val core = if (trailingDot) p.dropLast(1) else p
            val letters = Regex("d+|M+|y+").findAll(core).toList()
            if (letters.size != 3) return null
            val order = letters.joinToString("") { when (it.value[0]) { 'd' -> "D"; 'M' -> "M"; else -> "Y" } }
            if (order.toSet().size != 3) return null
            val yearTok = letters.first { it.value[0] == 'y' }.value
            val monthTok = letters.first { it.value[0] == 'M' }.value
            val sepStart = letters[0].range.last + 1
            val sepEnd = letters[1].range.first
            val sep = if (sepEnd >= sepStart) core.substring(sepStart, sepEnd) else ""
            return DatePattern(order, sep, if (yearTok.length <= 2) 2 else 4, monthTok.length >= 3, trailingDot)
        }
    }
}

/** Result of reading a cell as a date under the pattern family (all interpretations that are calendar-valid). */
object DateParsing {
    private const val SUFFIX = """(?:(?:[T\s,]+\d{1,2}[:.]\d{2}.*)|(?:\s+(?:UTC|GMT|[+-]\d{2}:?\d{2}))|\s*)?"""
    private val numeric = Regex("""^(\d{1,4})([./\-])(\d{1,2})\2(\d{1,4})(\.?)$SUFFIX$""")
    private val numericSpaced = Regex("""^(\d{1,4})\s(\d{1,2})\s(\d{4})$SUFFIX$""")
    private val dayMonthText = Regex("""^(\d{1,2})([ \-./]?)(\p{L}{3,9})\.?([ \-./,]*)(\d{4}|\d{2})$SUFFIX$""")
    private val monthTextDay = Regex("""^(\p{L}{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})$SUFFIX$""")
    private val yearMonthText = Regex("""^(\d{4})([ \-./]?)(\p{L}{3,9})\.?([ \-./]*)(\d{1,2})$SUFFIX$""")
    private val compact = Regex("""^(\d{8})$SUFFIX$""")

    private val MONTHS: Map<String, Int> = buildMap {
        fun add(m: Int, vararg names: String) = names.forEach { put(it, m) }
        add(1, "jan", "ene", "gen", "led", "sty", "tam")
        add(2, "feb", "fev", "uno", "lut", "hel")
        add(3, "mar", "mrt", "maa", "bre", "mrz", "maer")
        add(4, "apr", "abr", "avr", "dub", "kwi", "huh")
        add(5, "may", "mai", "maj", "kve", "mag", "mei", "tou")
        add(6, "jun", "giu", "cze", "kes")
        add(7, "jul", "lug", "lip", "hei")
        add(8, "aug", "ago", "aou", "srp", "sie", "elo")
        add(9, "sep", "set", "zar", "wrz", "syy")
        add(10, "oct", "okt", "out", "ott", "rij", "paz", "lok")
        add(11, "nov", "lis")
        add(12, "dec", "dez", "des", "dic", "pro", "gru", "jou")
    }

    private fun stripAccents(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** Month number from a month name or abbreviation in common European languages; null if unknown. */
    fun monthFromName(name: String): Int? {
        val n = stripAccents(name)
        if (n.length < 3) return null
        when {
            n.startsWith("juin") -> return 6
            n.startsWith("juil") -> return 7
            n.startsWith("marras") -> return 11
            n.startsWith("cervenec") -> return 7
            n.startsWith("cerven") -> return 6
        }
        return MONTHS[n.take(4)] ?: MONTHS[n.take(3)]
    }

    private class Raw(val p1: Int, val p2: Int, val p3: Int, val w1: Int, val w3: Int, val sep: String, val trailingDot: Boolean)

    /**
     * Every pattern under which [cell] is a valid calendar date, with the resulting date.
     * Empty list when the cell is not date-like.
     */
    fun interpretations(cell: String): List<Pair<DatePattern, LocalDate>> {
        val s = cell.trim().trim('"', '\'').trim()
        if (s.length < 6 || s.length > 40 || !s[0].isLetterOrDigit()) return emptyList()
        val out = ArrayList<Pair<DatePattern, LocalDate>>(3)

        compact.matchEntire(s)?.let { m ->
            val d = m.groupValues[1]
            val a = d.substring(0, 4).toInt()
            val b = d.substring(4, 6).toInt()
            val c = d.substring(6, 8).toInt()
            make(a, b, c)?.takeIf { a in 1900..2100 }?.let { out.add(DatePattern("YMD", "", 4) to it) }
            val y2 = d.substring(4, 8).toInt()
            if (y2 in 1900..2100) {
                make(y2, b = d.substring(2, 4).toInt(), d = d.substring(0, 2).toInt())?.let { out.add(DatePattern("DMY", "", 4) to it) }
                make(y2, b = d.substring(0, 2).toInt(), d = d.substring(2, 4).toInt())?.let { out.add(DatePattern("MDY", "", 4) to it) }
            }
            return out
        }

        numeric.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val raw = Raw(g[1].toInt(), g[3].toInt(), g[4].toInt(), g[1].length, g[4].length, g[2], g[5] == ".")
            if (g[3].length > 2) return emptyList()
            return numericInterpretations(raw)
        }
        numericSpaced.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val raw = Raw(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[1].length, 4, " ", false)
            return numericInterpretations(raw)
        }
        dayMonthText.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val month = monthFromName(g[3]) ?: return emptyList()
            val ys = g[5]
            val year = expandYear(ys.toInt(), ys.length)
            val day = g[1].toInt()
            make(year, month, day)?.let {
                val sep = if (g[2].isNotEmpty()) g[2] else if (g[4].isNotBlank()) g[4].trim().take(1).ifEmpty { " " } else " "
                out.add(DatePattern("DMY", sep.ifEmpty { " " }, ys.length.coerceAtMost(4).let { if (it <= 2) 2 else 4 }, monthText = true) to it)
            }
            return out
        }
        monthTextDay.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val month = monthFromName(g[1]) ?: return emptyList()
            make(g[3].toInt(), month, g[2].toInt())?.let { out.add(DatePattern("MDY", " ", 4, monthText = true) to it) }
            return out
        }
        yearMonthText.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val month = monthFromName(g[3]) ?: return emptyList()
            make(g[1].toInt(), month, g[5].toInt())?.let { out.add(DatePattern("YMD", g[2].ifEmpty { " " }, 4, monthText = true) to it) }
            return out
        }
        return emptyList()
    }

    private fun numericInterpretations(r: Raw): List<Pair<DatePattern, LocalDate>> {
        val out = ArrayList<Pair<DatePattern, LocalDate>>(3)
        if (r.w1 == 4) {
            // yyyy-MM-dd (year first); also reject an ambiguous 4-digit trailing part
            if (r.w3 <= 2) make(r.p1, r.p2, r.p3)?.let { out.add(DatePattern("YMD", r.sep, 4, trailingDot = r.trailingDot) to it) }
        } else if (r.w3 == 4) {
            val y = r.p3
            make(y, r.p2, r.p1)?.let { out.add(DatePattern("DMY", r.sep, 4, trailingDot = r.trailingDot) to it) }
            make(y, r.p1, r.p2)?.let { out.add(DatePattern("MDY", r.sep, 4, trailingDot = r.trailingDot) to it) }
        } else if (r.w1 <= 2 && r.w3 == 2) {
            make(expandYear(r.p3, 2), r.p2, r.p1)?.let { out.add(DatePattern("DMY", r.sep, 2, trailingDot = r.trailingDot) to it) }
            make(expandYear(r.p3, 2), r.p1, r.p2)?.let { out.add(DatePattern("MDY", r.sep, 2, trailingDot = r.trailingDot) to it) }
            make(expandYear(r.p1, 2), r.p2, r.p3)?.let { out.add(DatePattern("YMD", r.sep, 2, trailingDot = r.trailingDot) to it) }
        }
        return out
    }

    fun expandYear(y: Int, digits: Int): Int = if (digits >= 4) y else if (y < 70) 2000 + y else 1900 + y

    private fun make(y: Int, b: Int, d: Int): LocalDate? {
        if (y !in 1900..2100) return null
        if (b !in 1..12 || d !in 1..31) return null
        return try { YearMonth.of(y, b).let { if (d <= it.lengthOfMonth()) it.atDay(d) else null } } catch (_: DateTimeException) { null }
    }

    /** Parse [cell] with an explicit [pattern]; null if the cell does not match it. */
    fun parse(cell: String, pattern: DatePattern): LocalDate? {
        val list = interpretations(cell)
        // Match on the structural parts (order, year digits, sep family, text month); tolerate separator
        // differences like "." vs "/" only if they are the same layout class.
        return list.firstOrNull { (p, _) -> sameLayout(p, pattern) }?.second
    }

    private fun sameLayout(a: DatePattern, b: DatePattern) =
        a.order == b.order && a.monthText == b.monthText && a.yearDigits == b.yearDigits && (a.sep == b.sep || a.monthText)

    fun parse(cell: String, pattern: String): LocalDate? = DatePattern.fromPattern(pattern)?.let { parse(cell, it) }

    /** True if the cell looks like a date under some layout. */
    fun isDateLike(cell: String): Boolean = interpretations(cell).isNotEmpty()
}
