package app.subsonar.domain.importer

import java.math.BigDecimal

/** What a numeric cell tells us about the file's decimal separator. */
enum class DecimalEvidence { DOT, COMMA, NONE, AMBIGUOUS }

data class ParsedNumber(val value: BigDecimal, val evidence: DecimalEvidence, val currencyHint: String? = null, val explicitPlus: Boolean = false)

/**
 * Locale-independent amount parser: handles 1.234,56 / 1,234.56 / 1 234,56 / 1'234.56, currency
 * symbols/codes, +/- prefix or suffix, (parentheses) and CR/DR markers.
 */
object NumberParsing {
    private val CURRENCY_WORDS = Regex(
        """(?i)(?<![\p{L}])(eur|usd|gbp|nok|sek|dkk|chf|pln|czk|huf|ron|bgn|isk|rub|uah|try|try|eura|euroa|euro|euros|kr|kč|kc|zł|zl|ft|lei|leu|din|hrk|rsd|lva|lv)(?![\p{L}])""",
    )
    private val SYMBOLS = Regex("[€$£¥₽₴₺₹]")
    private val DR_CR = Regex("""(?i)(?<![\p{L}])(cr|dr|db|crd|dbt|debit|credit)(?![\p{L}])""")

    private val ISO_BY_SYMBOL = mapOf('€' to "EUR", '£' to "GBP", '$' to "USD", '¥' to "JPY")
    private val ISO_BY_WORD = mapOf(
        "eur" to "EUR", "eura" to "EUR", "euroa" to "EUR", "euro" to "EUR", "euros" to "EUR",
        "usd" to "USD", "gbp" to "GBP", "nok" to "NOK", "sek" to "SEK", "dkk" to "DKK", "chf" to "CHF",
        "pln" to "PLN", "zł" to "PLN", "zl" to "PLN", "czk" to "CZK", "kč" to "CZK", "kc" to "CZK",
        "huf" to "HUF", "ft" to "HUF", "ron" to "RON", "lei" to "RON", "bgn" to "BGN", "isk" to "ISK",
    )

    fun parse(cell: String, decimal: Char? = null): ParsedNumber? {
        var s = cell.trim().trim('"', '\'').replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2212', '-').trim()
        if (s.isEmpty() || s.length > 32) return null
        var negative = false
        var explicitPlus = false
        var currency: String? = null

        SYMBOLS.find(s)?.let { currency = ISO_BY_SYMBOL[it.value[0]] }
        s = SYMBOLS.replace(s, " ")
        CURRENCY_WORDS.find(s)?.let { m -> ISO_BY_WORD[m.value.lowercase()]?.let { currency = currency ?: it } }
        s = CURRENCY_WORDS.replace(s, " ")
        DR_CR.find(s)?.let { m ->
            val w = m.value.lowercase()
            if (w == "dr" || w == "db" || w == "dbt" || w == "debit") negative = true
        }
        s = DR_CR.replace(s, " ")
        s = s.trim()
        if (s.isEmpty()) return null
        if (s.any { it.isLetter() }) return null

        if (s.startsWith("(") && s.endsWith(")")) { negative = !negative; s = s.substring(1, s.length - 1).trim() }
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1).trim() }
        else if (s.startsWith("+")) { explicitPlus = true; s = s.substring(1).trim() }
        if (s.endsWith("-")) { negative = !negative; s = s.dropLast(1).trim() }
        else if (s.endsWith("+")) { explicitPlus = true; s = s.dropLast(1).trim() }
        if (s.isEmpty() || !s[0].isDigit() && s[0] != '.' && s[0] != ',') return null
        if (!s.last().isDigit()) return null

        // Only digits and separators from here on.
        for (ch in s) if (!(ch.isDigit() || ch == '.' || ch == ',' || ch == ' ' || ch == '\'' || ch == '’')) return null

        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        val groupChars = s.count { it == ' ' || it == '\'' || it == '’' }

        var decimalChar: Char? = null
        var thousandChars = HashSet<Char>()
        var evidence = DecimalEvidence.NONE

        when {
            dots > 0 && commas > 0 -> {
                val lastDot = s.lastIndexOf('.')
                val lastComma = s.lastIndexOf(',')
                decimalChar = if (lastDot > lastComma) '.' else ','
                thousandChars.add(if (decimalChar == '.') ',' else '.')
                evidence = if (decimalChar == '.') DecimalEvidence.DOT else DecimalEvidence.COMMA
                // the decimal char must occur once
                if (s.count { it == decimalChar } != 1) return null
            }
            dots > 0 || commas > 0 -> {
                val ch = if (dots > 0) '.' else ','
                val count = if (dots > 0) dots else commas
                val after = s.length - s.lastIndexOf(ch) - 1
                if (count > 1) {
                    thousandChars.add(ch)
                } else if (after != 3) {
                    // 1 or 2 (or 4+) digits after a single separator => decimal
                    if (after == 0) return null
                    decimalChar = ch
                    evidence = if (ch == '.') DecimalEvidence.DOT else DecimalEvidence.COMMA
                } else {
                    // exactly three digits after a single separator: 1,234 vs 1.234
                    val honour = decimal
                    val leading = s.substring(0, s.indexOf(ch)).replace(" ", "").replace("'", "").replace("’", "")
                    if (honour != null) {
                        if (honour == ch) decimalChar = ch else thousandChars.add(ch)
                    } else if (groupChars > 0 || leading.length > 3 || leading == "0" || leading.isEmpty()) {
                        // "0,123" or "12345,678" can only be a decimal; a group char elsewhere means ch is decimal
                        decimalChar = ch
                    } else {
                        evidence = DecimalEvidence.AMBIGUOUS
                        decimalChar = null
                        thousandChars.add(ch)
                    }
                    if (decimalChar != null && evidence == DecimalEvidence.NONE) evidence = if (ch == '.') DecimalEvidence.DOT else DecimalEvidence.COMMA
                }
            }
        }
        // Explicit file-wide decimal separator overrides guesses (but must stay consistent).
        if (decimal != null && decimalChar != null && decimalChar != decimal && evidence != DecimalEvidence.AMBIGUOUS && !(dots > 0 && commas > 0)) {
            // value uses the other separator as decimal => this cell contradicts the file-wide setting
            return null
        }
        if (decimal != null && decimalChar == null) {
            // integer-with-groups case: e.g. "1.234" with decimal=',' => thousands; with '.' => decimal
            if (thousandChars.size == 1 && thousandChars.first() == decimal) {
                thousandChars.clear(); decimalChar = decimal
            }
        }

        // Validate thousands grouping and build the plain number.
        val intPart: String
        val fracPart: String
        if (decimalChar != null) {
            val idx = s.lastIndexOf(decimalChar)
            intPart = s.substring(0, idx).ifEmpty { "0" }
            fracPart = s.substring(idx + 1)
        } else {
            intPart = s
            fracPart = ""
        }
        if (fracPart.isNotEmpty() && !fracPart.all { it.isDigit() }) return null
        if (!validGrouping(intPart)) return null
        val digits = intPart.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val plain = if (fracPart.isEmpty()) digits else "$digits.$fracPart"
        if (digits.length > 12 || fracPart.length > 6) return null
        val value = try { BigDecimal(plain) } catch (_: NumberFormatException) { return null }
        val finalValue = if (negative) value.negate() else value
        return ParsedNumber(finalValue, if (decimalChar == null && evidence == DecimalEvidence.NONE) DecimalEvidence.NONE else evidence, currency, explicitPlus)
    }

    /** Integer part with optional group separators: groups after the first must have exactly 3 digits. */
    private fun validGrouping(intPart: String): Boolean {
        if (intPart.isEmpty()) return false
        val groups = intPart.split('.', ',', ' ', '\'', '’')
        if (groups.any { it.isEmpty() || !it.all(Char::isDigit) }) return false
        if (groups.size == 1) return true
        if (groups[0].length > 3) return false
        return groups.drop(1).all { it.length == 3 }
    }

    /** ISO-ish currency code found in a cell like "EUR" / "€" / "12,00 EUR" (null if none). */
    fun currencyOf(cell: String): String? = parse(cell)?.currencyHint
}
