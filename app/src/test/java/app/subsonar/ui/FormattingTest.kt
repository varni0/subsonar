package app.subsonar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class FormattingTest {
    @Test fun parsesPrices() {
        assertEquals(BigDecimal("9.99"), parsePrice("9.99"))
        assertEquals(BigDecimal("9.99"), parsePrice(" 9,99 "))
        assertEquals(BigDecimal("120"), parsePrice("120"))
        assertNull(parsePrice(""))
        assertNull(parsePrice("-1"))
        assertNull(parsePrice("1.2.3"))
        assertNull(parsePrice("1e5"))
        assertNull(parsePrice("abc"))
    }

    @Test fun validatesCurrency() {
        assertTrue(isValidCurrency("NOK"))
        assertFalse(isValidCurrency("XYZQ"))
        assertFalse(isValidCurrency("ZZZ"))
    }

    @Test fun formatsMoney() {
        assertEquals("$9.99", formatMoney(BigDecimal("9.99"), "USD", Locale.US))
        assertEquals("¥500", formatMoney(BigDecimal("500"), "JPY", Locale.JAPAN).replace('￥', '¥'))
    }

    @Test fun parsesPricesWithGroupingSeparators() {
        assertEquals(BigDecimal("1299.00"), parsePrice("1,299.00"))
        assertEquals(BigDecimal("1299.00"), parsePrice("1.299,00"))
        assertEquals(BigDecimal("1299.50"), parsePrice("1 299,50"))
        assertEquals(BigDecimal("1299.50"), parsePrice("1\u00A0299,50"))
        assertEquals(BigDecimal("1299.50"), parsePrice("1\u202F299.50"))
        assertEquals(BigDecimal("1234567.89"), parsePrice("1,234,567.89"))
        assertNull(parsePrice("1.2.3,4.5"))
        assertNull(parsePrice("12,34,56,78,9a"))
    }

    @Test fun formatsPercentInTheNumberStyleOfTheLocale() {
        assertEquals("16.7%", formatPercent(BigDecimal("16.7"), Locale.US))
        assertEquals("+16.7%", formatPercent(BigDecimal("16.7"), Locale.US, signed = true))
        assertEquals("20%", formatPercent(BigDecimal("20.0"), Locale.US))
        val de = formatPercent(BigDecimal("16.7"), Locale.GERMANY, signed = true)
        assertTrue(de, de.startsWith("+16,7") && de.endsWith("%"))
        val fr = formatPercent(BigDecimal("16.7"), Locale.FRANCE)
        assertTrue(fr, fr.startsWith("16,7") && fr.endsWith("%"))
    }

    @Test fun formatsSignedMoney() {
        assertEquals("+$24.00", formatSignedMoney(BigDecimal("24"), "USD", Locale.US))
        assertEquals("\u2212$3.00", formatSignedMoney(BigDecimal("-3"), "USD", Locale.US))
        assertEquals("$0.00", formatSignedMoney(BigDecimal.ZERO, "USD", Locale.US))
    }

    @Test fun formatsMoneyWithTheDecimalAndCurrencyStyleOfEachLocale() {
        val de = formatMoney(BigDecimal("1234.5"), "EUR", Locale.GERMANY)
        assertTrue(de, de.contains("1.234,50") && de.contains("€"))
        val no = formatMoney(BigDecimal("99"), "NOK", Locale.forLanguageTag("nb-NO"))
        assertTrue(no, no.contains("99,00"))
        assertEquals("€1,234.50", formatMoney(BigDecimal("1234.5"), "EUR", Locale.US))
    }

    @Test fun formatsDatesInTheLocaleStyle() {
        val d = java.time.LocalDate.of(2026, 3, 5)
        assertEquals("Mar 5, 2026", formatDateMedium(d, Locale.US))
        assertEquals("05.03.2026", formatDateMedium(d, Locale.GERMANY))
    }
}
