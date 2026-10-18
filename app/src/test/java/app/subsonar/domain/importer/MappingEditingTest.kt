package app.subsonar.domain.importer

import org.junit.Assert.*
import org.junit.Test

class MappingEditingTest {
    private val csv = "Dato;Tekst;Beløp;Saldo\n05.01.2025;Kiwi;-120,50;1000,00\n13.01.2025;Spotify;-99,00;901,00\n20.02.2025;Lønn;30000,00;30901,00\n"
    private val analysis = StatementImporter.analyze(InputFile("a.csv", csv.toByteArray()))

    @Test fun columnLabelsAndSamples() {
        val l = analysis.layout!!
        assertEquals("Beløp", MappingEditing.headerOf(l, 2)); assertNull(MappingEditing.headerOf(l, 9)); assertEquals("-120,50", MappingEditing.sampleOf(l, 2))
    }
    @Test fun dateFormatOptionsPutFittingFormatFirstAndOfferAlternatives() {
        val o = MappingEditing.dateFormatOptions(analysis.layout!!, 0, null)
        assertEquals("dd.MM.yyyy", o.first()); assertTrue("yyyy-MM-dd" in o); assertEquals(o.toSet().size, o.size)
        assertEquals("MM/dd/yyyy", MappingEditing.dateFormatOptions(analysis.layout!!, 0, "MM/dd/yyyy").first())
    }
    @Test fun switchingBetweenSingleAndSplitAmount() {
        val m = analysis.mapping!!
        val split = MappingEditing.withSplitAmount(m, 2, 3)
        assertTrue(split.isSplit); assertNull(split.amountColumn)
        val single = MappingEditing.withSingleAmount(split, 2)
        assertEquals(2, single.amountColumn); assertFalse(single.isSplit)
    }
    @Test fun editedMappingReparsesTheFile() {
        val m = analysis.mapping!!
        assertEquals(3, analysis.transactions.size)
        val wrongDate = StatementImporter.withMapping(analysis, m.copy(datePattern = "MM/dd/yyyy"))
        assertEquals(0, wrongDate.transactions.size); assertNotNull(wrongDate.failure)
        val swapped = StatementImporter.withMapping(analysis, MappingEditing.withSingleAmount(m, 3))
        assertEquals(3, swapped.transactions.size); assertEquals(java.math.BigDecimal("1000.00"), swapped.transactions[0].amount)
    }
    @Test fun toggleTextColumns() {
        val m = analysis.mapping!!
        val t = MappingEditing.toggleTextColumn(m, 3)
        assertTrue(3 in t.descriptionColumns); assertFalse(3 in MappingEditing.toggleTextColumn(t, 3).descriptionColumns)
    }
    @Test fun sanitizingDropsReferencesToReassignedColumns() {
        val m = analysis.mapping!!.copy(statusColumn = 2, skipStatuses = setOf("pending"), descriptionColumns = listOf(0, 1, 2))
        val s = MappingEditing.sanitized(m)
        assertNull(s.statusColumn); assertTrue(s.skipStatuses.isEmpty()); assertEquals(listOf(1), s.descriptionColumns)
    }
}
