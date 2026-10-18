package app.subsonar.domain

import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CsvExporterTest {
    @Test fun escapesAndProtectsAgainstFormulas() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("'=SUM(A1)", CsvExporter.escape("=SUM(A1)"))
    }

    @Test fun exportsRowsWithComputedRenewal() {
        val sub = Subscription(
            name = "Netflix, Premium", price = BigDecimal("17.99"), currencyCode = "EUR",
            cycle = BillingCycle.MONTHLY, renewalAnchor = LocalDate.parse("2026-09-15"), category = Category.STREAMING,
        )
        val lines = CsvExporter.export(listOf(sub), LocalDate.parse("2026-10-09")).trim().lines()
        assertEquals(CsvExporter.HEADER.joinToString(","), lines[0])
        assertEquals("\"Netflix, Premium\",17.99,EUR,monthly,2026-10-15,streaming,17.99,215.88,yes,", lines[1])
    }
}
