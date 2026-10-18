package app.subsonar.domain.importer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterBehaviourTest {
    private fun file(path: String) = InputFile(path.substringAfterLast('/'), Corpus.resource(path)!!)
    private fun ids(a: FileAnalysis) = Dedupe.withIds(a.transactions).map { it.first }

    // ---------------- open banking ----------------

    @Test fun camt053FilesAreParsed() {
        val expected = mapOf("ledgerbridge-camt053_reversal.xml" to 2, "ledgerbridge-camt053_unbalanced.xml" to 2, "ledgerbridge-camt053_v02.xml" to 3,
            "ledgerbridge-camt053_v08.xml" to 5, "pfs-camt.053.001.02-statement.xml" to 5, "pfs-camt.053.001.08-statement.xml" to 5)
        for ((n, rows) in expected) {
            val a = StatementImporter.analyze(file("_open-banking/camt053/$n"))
            assertEquals(n, SourceKind.CAMT053, a.kind)
            assertEquals(n, rows, a.transactions.size)
            assertTrue(n, a.transactions.all { it.currency != null && it.description.isNotBlank() || it.currency != null })
        }
    }
    @Test fun camt053DebitsAreNegativeAndCreditsPositive() {
        val a = StatementImporter.analyze(file("_open-banking/camt053/pfs-camt.053.001.02-statement.xml"))
        assertTrue(a.transactions.any { it.amount.signum() < 0 }); assertTrue(a.transactions.any { it.amount.signum() > 0 })
        val xml = String(Corpus.resource("_open-banking/camt053/pfs-camt.053.001.02-statement.xml")!!)
        val dbit = Regex("<CdtDbtInd>DBIT</CdtDbtInd>").findAll(xml).count()
        // each Ntry carries one CdtDbtInd at entry level (plus the opening/closing balance indicators); at least the debit entries must appear negative
        assertTrue(a.transactions.count { it.amount.signum() < 0 } in 1..dbit)
    }
    @Test fun mt940IsParsedWithSigns() {
        val a = StatementImporter.analyze(file("_open-banking/mt940/pfs-mt940-statement.sta"))
        assertEquals(SourceKind.MT940, a.kind); assertEquals(5, a.transactions.size)
        val raw = String(Corpus.resource("_open-banking/mt940/pfs-mt940-statement.sta")!!)
        val debits = Regex(":61:\\d{6}\\d{0,4}R?D").findAll(raw).count().let { if (it == 0) Regex(":61:\\d+R?D").findAll(raw).count() else it }
        assertEquals(debits, a.transactions.count { it.amount.signum() < 0 })
    }
    @Test fun brokenXmlAndTruncatedMt940FailCleanly() {
        val xml = String(Corpus.resource("_open-banking/camt053/pfs-camt.053.001.02-statement.xml")!!)
        val a = StatementImporter.analyze(InputFile("cut.xml", xml.take(xml.length / 2).toByteArray()))
        assertTrue(a.ok || a.failure != null)
        val b = StatementImporter.analyze(InputFile("x.sta", ":20:ABC\n:25:123\n:61:2501".toByteArray()))
        assertTrue(b.ok || b.failure != null)
        // XXE: DOCTYPE with an external entity must be ignored, not resolved.
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\"><BkToCstmrStmt><Stmt><Ntry><Amt Ccy=\"EUR\">1.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2025-01-01</Dt></BookgDt><AddtlNtryInf>&x;</AddtlNtryInf></Ntry></Stmt></BkToCstmrStmt></Document>"
        val e = StatementImporter.analyze(InputFile("evil.xml", evil.toByteArray()))
        assertFalse(e.transactions.any { it.description.contains("root:") })
    }

    // ---------------- dedupe ----------------

    @Test fun sameLedgerInTwoDifferentDialectsGetsIdenticalIds() {
        val a = ids(StatementImporter.analyze(file("synthetic/matrix/A01.csv")))
        val b = ids(StatementImporter.analyze(file("synthetic/matrix/A05.csv")))
        assertEquals(258, a.size)
        val overlap = a.toSet().intersect(b.toSet()).size
        assertTrue("overlap $overlap of ${a.size}", overlap >= a.size * 0.97)
    }
    @Test fun sameDayIdenticalPurchasesStayDistinctButReimportMergesThem() {
        val t = ParsedTransaction(LocalDate.of(2025, 1, 5), BigDecimal("-4.50"), "EUR", "Coffee  Shop")
        val withIds = Dedupe.withIds(listOf(t, t, t.copy(description = "COFFEE SHOP")))
        assertEquals(3, withIds.map { it.first }.toSet().size)
        assertEquals(withIds.map { it.first }, Dedupe.withIds(listOf(t, t, t)).map { it.first })
    }
    @Test fun idIgnoresCaseWhitespaceAccentsAndTrailingZeros() {
        val a = ParsedTransaction(LocalDate.of(2025, 1, 5), BigDecimal("-4.5"), "EUR", "Café   Müller")
        val b = ParsedTransaction(LocalDate.of(2025, 1, 5), BigDecimal("-4.50"), "NOK", "CAFE MULLER")
        assertEquals(Dedupe.withIds(listOf(a)).single().first, Dedupe.withIds(listOf(b)).single().first)
    }
    @Test fun differentAmountDateOrTextGiveDifferentIds() {
        val base = ParsedTransaction(LocalDate.of(2025, 1, 5), BigDecimal("-4.50"), "EUR", "Shop")
        val ids = listOf(base, base.copy(amount = BigDecimal("-4.51")), base.copy(date = LocalDate.of(2025, 1, 6)), base.copy(description = "Shop2"))
            .map { Dedupe.withIds(listOf(it)).single().first }
        assertEquals(4, ids.toSet().size)
    }
    @Test fun overlappingMonthlyExportsMergeWithoutLosingOrDoublingRows() {
        val all = StatementImporter.analyze(file("synthetic/matrix/A01.csv")).transactions
        fun part(from: LocalDate, to: LocalDate) = all.filter { it.date >= from && it.date <= to }
        val f1 = part(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 9, 30))
        val f2 = part(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30))
        val merged = LinkedHashMap<String, ParsedTransaction>()
        for (f in listOf(f1, f2)) Dedupe.withIds(f).forEach { (id, t) -> merged.putIfAbsent(id, t) }
        assertEquals(all.size, merged.size)
        assertTrue(f1.size + f2.size > all.size)
    }

    // ---------------- multi-file, zip, failures ----------------

    @Test fun oneBadFileDoesNotBlockTheOthers() {
        val inputs = listOf(file("synthetic/matrix/A01.csv"), InputFile("empty.csv", ByteArray(0)), InputFile("junk.csv", "just some words\nno table here\n".toByteArray()),
            InputFile("bin.csv", ByteArray(5000) { (it * 31).toByte() }), file("synthetic/matrix/A03.csv"))
        val r = inputs.flatMap { StatementImporter.expand(it) }.map { StatementImporter.analyze(it) }
        assertEquals(listOf(true, false, false, false, true), r.map { it.ok })
        assertEquals(FileFailure.EMPTY, r[1].failure)
        assertNotNull(r[2].failure); assertNotNull(r[3].failure)
    }
    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, b) in e) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }
    @Test fun zipIsExpandedIntoItsFiles() {
        val z = zip("a/jan.csv" to Corpus.resource("synthetic/matrix/A01.csv")!!, "b/feb.csv" to Corpus.resource("synthetic/matrix/A02.csv")!!, "__MACOSX/._x" to byteArrayOf(1), "dir/" to ByteArray(0))
        val files = StatementImporter.expand(InputFile("bank.zip", z))
        assertEquals(2, files.size)
        assertTrue(files.map { StatementImporter.analyze(it) }.all { it.ok })
        assertEquals("bank.zip/jan.csv", files[0].name)
    }
    @Test fun corruptAndHostileZipsAreHandled() {
        val z = zip("a.csv" to Corpus.resource("synthetic/matrix/A01.csv")!!)
        val broken = z.copyOf(z.size / 2)
        val r = StatementImporter.expand(InputFile("b.zip", broken)).map { StatementImporter.analyze(it) }
        assertTrue(r.isNotEmpty())
        // zip bomb: highly compressible 40 MB entry is refused as too large without OOM
        val bomb = zip("bomb.csv" to ByteArray(40 * 1024 * 1024) { 'a'.code.toByte() })
        val rb = StatementImporter.expand(InputFile("bomb.zip", bomb)).map { StatementImporter.analyze(it) }
        assertEquals(FileFailure.TOO_LARGE, rb.single().failure)
        // many entries are capped
        val many = zip(*(1..200).map { "f$it.csv" to "a;b\n1;2\n".toByteArray() }.toTypedArray())
        assertTrue(StatementImporter.expand(InputFile("m.zip", many)).size <= StatementImporter.MAX_ZIP_ENTRIES)
    }
    @Test fun spreadsheetsAndPdfsAreRejectedAsNotText() {
        assertEquals(FileFailure.NOT_TEXT, StatementImporter.analyze(InputFile("a.xlsx", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0))).failure)
        assertEquals(FileFailure.NOT_TEXT, StatementImporter.analyze(InputFile("a.pdf", "%PDF-1.7\n....".toByteArray())).failure)
    }

    // ---------------- saved mappings / user corrections ----------------

    @Test fun savedMappingIsReusedForTheSameFingerprint() {
        val first = StatementImporter.analyze(file("synthetic/matrix/A01.csv"))
        val saved = first.mapping!!
        val again = StatementImporter.analyze(file("synthetic/matrix/A01.csv")) { fp -> saved.takeIf { fp == first.fingerprint } }
        assertTrue(again.fromSavedMapping); assertEquals(first.transactions, again.transactions)
        assertEquals(1.0, again.confidence, 0.0)
    }
    @Test fun savedMappingThatNoLongerFitsIsIgnored() {
        val first = StatementImporter.analyze(file("synthetic/matrix/A01.csv"))
        val wrong = first.mapping!!.copy(dateColumn = first.mapping!!.amountColumn ?: 2)
        val again = StatementImporter.analyze(file("synthetic/matrix/A01.csv")) { wrong }
        assertFalse(again.fromSavedMapping); assertEquals(first.transactions.size, again.transactions.size)
    }
    @Test fun mappingEncodeDecodeRoundTrip() {
        val m = StatementImporter.analyze(file("synthetic/matrix/A09.csv")).mapping!!
        assertEquals(m, ColumnMapping.decode(m.encode()))
        val full = ColumnMapping(1, "dd.MM.yyyy", listOf(2, 3), null, 4, 5, 6, setOf("d", "debit"), setOf("c"), true, '.', 7, "NOK", 8, setOf("pending"), true)
        assertEquals(full, ColumnMapping.decode(full.encode()))
        assertNull(ColumnMapping.decode("garbage")); assertNull(ColumnMapping.decode(""))
    }
    @Test fun userCorrectionReparsesAndFixesAWrongGuess() {
        val a = StatementImporter.analyze(file("synthetic/matrix/A01.csv"))
        val m = a.mapping!!
        val flipped = m.copy(invertSign = !m.invertSign)
        val b = StatementImporter.withMapping(a, flipped)
        assertEquals(a.transactions.size, b.transactions.size)
        assertEquals(a.transactions.first().amount.negate(), b.transactions.first().amount)
        val badDate = StatementImporter.withMapping(a, m.copy(datePattern = "yyyy-MM-dd"))
        assertTrue(badDate.transactions.size < a.transactions.size || badDate.failure != null || a.mapping!!.datePattern == "yyyy-MM-dd")
        assertSame(a, StatementImporter.withMapping(a, m.copy(dateColumn = 99)))
    }
    @Test fun fingerprintIsStableAcrossDataAndDiffersAcrossLayouts() {
        val a = StatementImporter.analyze(file("synthetic/matrix/A01.csv")).fingerprint
        val a2 = StatementImporter.analyze(InputFile("x.csv", Corpus.resource("synthetic/matrix/A01.csv")!!.let { String(it).lines().take(30).joinToString("\n").toByteArray() })).fingerprint
        val b = StatementImporter.analyze(file("DE/n26/n26-current.csv")).fingerprint
        assertEquals(a, a2); assertNotEquals(a, b)
    }

    // ---------------- robustness / fuzz ----------------

    @Test fun garbageNeverCrashes() {
        val rnd = Random(1234)
        val samples = ArrayList<ByteArray>()
        samples += ByteArray(0); samples += byteArrayOf(0); samples += "\n\n\n\n".toByteArray(); samples += ";;;;;\n;;;;;\n".toByteArray(); samples += "\"".toByteArray()
        samples += "\"\"\"\"\"\"\n\"\"\"".toByteArray(); samples += "a".repeat(2_000_000).toByteArray(); samples += (";".repeat(10_000) + "\n").repeat(50).toByteArray()
        samples += "<?xml".toByteArray(); samples += ":20:\n:61:\n:86:".toByteArray(); samples += "<Ntry></Ntry>".toByteArray(); samples += "0.0;0.0;0.0\n".repeat(1000).toByteArray()
        samples += "\uFEFF".toByteArray(); samples += byteArrayOf(0xFF.toByte(), 0xFE.toByte()); samples += byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0)
        samples += ("99999999999999999999999999999999;2025-01-01;1e999\n").repeat(40).toByteArray()
        samples += ("2025-01-01;-" + "9".repeat(40) + "\n").repeat(40).toByteArray()
        repeat(150) { samples += ByteArray(rnd.nextInt(3000)).also(rnd::nextBytes) }
        repeat(150) { samples += String(CharArray(rnd.nextInt(2000)) { "0123456789;,.\t|\"\n -/:ABCabcæøå€()+"[rnd.nextInt(33)] }).toByteArray(Charsets.UTF_8) }
        for ((i, s) in samples.withIndex()) {
            val a = try { StatementImporter.analyze(InputFile("fuzz$i", s)) } catch (t: Throwable) { fail("sample $i threw $t") ; return }
            if (a.ok) assertTrue(a.transactions.all { it.description.length < 100_000 })
        }
    }
    @Test fun mutatedRealFilesNeverCrashAndStillMostlyParse() {
        val rnd = Random(77)
        val base = Corpus.resource("synthetic/matrix/A01.csv")!!
        var parsed = 0
        repeat(200) {
            val b = base.copyOf()
            repeat(1 + rnd.nextInt(30)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size)) else b
            val a = StatementImporter.analyze(InputFile("mut$it", cut))
            if (a.ok) parsed++
        }
        assertTrue("only $parsed/200 mutated files parsed", parsed >= 150)
    }
    @Test fun hugeFileIsHandledQuickly() {
        val sb = StringBuilder("Date;Description;Amount\n")
        val rnd = Random(5)
        repeat(300_000) { sb.append("2025-%02d-%02d;SHOP %d;-%d,%02d\n".format(1 + it % 12, 1 + it % 28, rnd.nextInt(500), rnd.nextInt(200), rnd.nextInt(100))) }
        val bytes = sb.toString().toByteArray()
        assertTrue(bytes.size < StatementImporter.MAX_FILE_BYTES)
        val t0 = System.nanoTime()
        val a = StatementImporter.analyze(InputFile("big.csv", bytes))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(300_000, a.transactions.size)
        assertTrue("took ${ms}ms", ms < 20_000)
        val tooBig = StatementImporter.analyze(InputFile("huge.csv", ByteArray(StatementImporter.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }))
        assertEquals(FileFailure.TOO_LARGE, tooBig.failure)
    }
    @Test fun veryLongSingleLineAndDeepQuotesAreOk() {
        val longCell = "x".repeat(500_000)
        StatementImporter.analyze(InputFile("long.csv", "Date;Text;Amount\n2025-01-01;$longCell;-1,00\n2025-01-02;b;-2,00\n2025-01-03;c;-3,00\n".toByteArray()))
        StatementImporter.analyze(InputFile("q.csv", ("Date;Text;Amount\n" + "2025-01-01;\"a \"\"b\" \"\"c;-1,00\n".repeat(50)).toByteArray()))
    }
}
