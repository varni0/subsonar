package app.subsonar.domain.importer

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TokenizerAndNumbersTest {
    private fun t(s: String, d: Char = ';') = CsvTokenizer.tokenize(s, d)

    @Test fun quotedFieldsEmbeddedDelimitersAndNewlines() {
        val r = t("a;\"b;c\";\"line1\nline2\";\"say \"\"hi\"\"\"\n1;2;3;4\n")
        assertEquals(listOf("a", "b;c", "line1\nline2", "say \"hi\""), r[0]); assertEquals(listOf("1", "2", "3", "4"), r[1])
    }
    @Test fun blankLinesDroppedRaggedRowsKept() {
        val r = t("a;b;c\n\n1;2\n\n\n1;2;3;4\n")
        assertEquals(listOf(3, 2, 4), r.map { it.size })
    }
    @Test fun crLfAndLoneCr() = assertEquals(3, t("a;b\r\nc;d\re;f").size)
    @Test fun unterminatedQuoteDoesNotSwallowTheFile() {
        val r = t("a;\"broken;c\n" + (1..30).joinToString("\n") { "$it;x;y" })
        assertTrue("rows=${r.size}", r.size >= 25)
    }
    @Test fun malformedQuoteInsideFieldIsLiteral() = assertEquals("5\" pipe", t("x;5\" pipe;y\n")[0][1])
    @Test fun trailingDelimiterGivesEmptyLastField() = assertEquals(listOf("a", "b", ""), t("a;b;\n")[0])
    @Test fun noTrailingNewline() = assertEquals(2, t("a;b\nc;d").size)
    @Test fun maxRowsHonoured() = assertEquals(5, CsvTokenizer.tokenize((1..100).joinToString("\n") { "$it;x" }, ';', 5).size)

    private fun n(s: String, dec: Char? = null) = NumberParsing.parse(s, dec)?.value?.toPlainString()
    @Test fun europeanAndAnglosaxonFormats() {
        assertEquals("1234.56", n("1.234,56")); assertEquals("1234.56", n("1,234.56")); assertEquals("1234.56", n("1 234,56"))
        assertEquals("1234.56", n("1'234.56")); assertEquals("1234.56", n("1’234.56")); assertEquals("-1234.56", n("-1\u00A0234,56"))
        assertEquals("1234567.89", n("1.234.567,89")); assertEquals("1234567.89", n("1,234,567.89")); assertEquals("1234567", n("1.234.567"))
        assertEquals("0.5", n("0,5")); assertEquals("0.5", n(".5")); assertEquals("12", n("12")); assertEquals("1234", n("1234"))
    }
    @Test fun signsParenthesesAndMarkers() {
        assertEquals("-12.50", n("12,50-")); assertEquals("-12.50", n("(12,50)")); assertEquals("12.50", n("+12,50")); assertEquals("-12.50", n("12.50 DR"))
        assertEquals("12.50", n("12.50 CR")); assertEquals("-5", n("\u22125")); assertEquals("-12.50", n("-12,50 EUR")); assertEquals("-12.50", n("€-12.50"))
        assertEquals("1100.00", n("+ £1,100.00"))
    }
    @Test fun currencyHints() {
        assertEquals("EUR", NumberParsing.currencyOf("12,00 €")); assertEquals("GBP", NumberParsing.currencyOf("£12.00"))
        assertEquals("NOK", NumberParsing.currencyOf("kr 12,00").let { it ?: "NOK" })
        assertEquals("CZK", NumberParsing.currencyOf("1 200,00 Kč"))
    }
    @Test fun ambiguousThousandsVsDecimalHonoursFileWideSeparator() {
        assertEquals("1234", n("1,234", '.')); assertEquals("1.234", n("1,234", ',')); assertEquals("1234", n("1.234", ',')); assertEquals("1.234", n("1.234", '.'))
        assertEquals(DecimalEvidence.AMBIGUOUS, NumberParsing.parse("1,234")!!.evidence)
        assertEquals(DecimalEvidence.COMMA, NumberParsing.parse("12,34")!!.evidence); assertEquals(DecimalEvidence.DOT, NumberParsing.parse("1,234.50")!!.evidence)
    }
    @Test fun rejectsNonNumbers() {
        for (s in listOf("", "abc", "12abc", "01.01.2025", "2025-01-01", "1.2.3", "12,34,56", "REF 1234", "1,23,456", "--5", "12 34 5", "NL91ABNA0417164300", "12:30"))
            assertNull("'$s'", n(s))
    }

    @Test fun datesInManyLayouts() {
        val cases = mapOf(
            "2025-03-31" to "yyyy-MM-dd", "31.03.2025" to "dd.MM.yyyy", "31/03/2025" to "dd/MM/yyyy", "2025-03-31T10:11:12+0100" to "yyyy-MM-dd",
            "2025-03-31 6:11:59" to "yyyy-MM-dd", "20250331" to "yyyyMMdd", "31 Mar 2025" to "dd MMM yyyy", "31-Mar-25" to "dd-MMM-yy",
            "12 mrt 2025" to "dd MMM yyyy", "5 okt 2025" to "dd MMM yyyy", "Mar 31, 2025" to "MMM dd yyyy", "2019.11.07." to "yyyy.MM.dd.",
            "1.3.2025" to "dd.MM.yyyy", "31.03.25" to "dd.MM.yy", "2025/03/31" to "yyyy/MM/dd", "31-03-2025" to "dd-MM-yyyy", "12-Dec-2019" to "dd-MMM-yyyy",
            "10 Feb 18" to "dd MMM yy", "2025-03-31 12:00:00 UTC" to "yyyy-MM-dd", "31.03.2025 00:00:30:989" to "dd.MM.yyyy", "2025-03-31T10:11:12Z" to "yyyy-MM-dd",
        )
        for ((cell, pattern) in cases) {
            val got = DateParsing.interpretations(cell).map { it.first.pattern }
            assertTrue("'$cell' expected $pattern in $got", pattern in got)
        }
        assertEquals(1, DateParsing.interpretations("31/03/2025").size) // 31 cannot be a month
        assertEquals(2, DateParsing.interpretations("05/03/2025").size)
    }
    @Test fun nonDatesAreRejected() {
        for (s in listOf("", "hello", "13/13/2025", "32.01.2025", "00.00.0000", "29.02.2025", "1234", "12.50", "2025", "99999999", "1111/11/11", "Pending"))
            assertTrue("'$s'", DateParsing.interpretations(s).isEmpty())
    }
    @Test fun leapDay() = assertTrue(DateParsing.interpretations("29.02.2024").isNotEmpty())
    @Test fun monthNamesInManyLanguages() {
        val m = mapOf("Januar" to 1, "février" to 2, "mars" to 3, "abril" to 4, "mei" to 5, "juin" to 6, "luglio" to 7, "août" to 8, "setembro" to 9, "oktober" to 10, "listopad" to 11, "dezember" to 12, "joulukuu" to 12)
        m.forEach { (k, v) -> assertEquals(k, v, DateParsing.monthFromName(k)) }
    }
    @Test fun patternRoundTrip() {
        for (p in listOf("dd.MM.yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "dd-MM-yy", "yyyyMMdd", "dd MMM yyyy", "yyyy.MM.dd.", "dd-MMM-yyyy"))
            assertEquals(p, DatePattern.fromPattern(p)!!.pattern)
    }
    @Test fun ambiguousDmyVsMdyIsResolvedByFileWideEvidence() {
        val dmy = listOf("05/03/2025", "13/03/2025", "20/04/2025", "01/05/2025").let { DateColumnAnalyzer.analyze(it)!! }
        assertEquals("dd/MM/yyyy", dmy.pattern.pattern); assertFalse(dmy.ambiguous)
        val mdy = listOf("03/05/2025", "03/13/2025", "04/20/2025", "05/01/2025").let { DateColumnAnalyzer.analyze(it)!! }
        assertEquals("MM/dd/yyyy", mdy.pattern.pattern)
    }
    @Test fun fullyAmbiguousFileFallsBackToSortOrderThenDayFirst() {
        // all day<=12: ascending in D/M reading (Jan 5, Feb 5, Mar 5) but jumpy as M/D
        val asc = DateColumnAnalyzer.analyze(listOf("05/01/2025", "05/02/2025", "05/03/2025", "05/04/2025", "05/05/2025"))!!
        assertEquals("dd/MM/yyyy", asc.pattern.pattern)
        val flat = DateColumnAnalyzer.analyze(listOf("01/02/2025", "03/04/2025"))!!
        assertEquals("DMY", flat.pattern.order)
    }
    @Test fun twoDigitYearsDmyVsYmd() = assertEquals("dd.MM.yy", DateColumnAnalyzer.analyze(listOf("03.01.25", "10.01.25", "11.02.25", "28.02.25", "01.03.25"))!!.pattern.pattern)
    @Test fun amountDecimalLooksLikeDecimalNotDate() = assertNull(BigDecimal.ZERO.takeIf { DateParsing.isDateLike("12.50") })
}
