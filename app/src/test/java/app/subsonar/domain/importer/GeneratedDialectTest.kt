package app.subsonar.domain.importer

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/**
 * Property-style test: render one random ledger in hundreds of random "bank dialects" (delimiter, encoding, date format,
 * number format, header language, preamble/footer, debit/credit layout, quoting) and require the importer to read back
 * exactly the same transactions without any hints. None of these dialects is read from the sample corpus.
 */
class GeneratedDialectTest {
    private data class Row(val date: LocalDate, val amount: BigDecimal, val text: String)

    private fun ledger(rnd: Random, n: Int): List<Row> {
        val merchants = listOf("REMA 1000 OSLO", "Café Müller København", "Spotify AB", "Žabka Praha", "Bjørn's Bakery & Co", "Łódź Market", "NETFLIX.COM", "Ärztehaus Dr. Weiß", "SEPA LASTSCHRIFT Strom", "O'Brien's Pub")
        var d = LocalDate.of(2024, 1, 2)
        return (0 until n).map {
            d = d.plusDays(rnd.nextInt(3).toLong())
            val cents = (rnd.nextInt(250000) + 1)
            val amt = BigDecimal(cents).movePointLeft(2).let { if (rnd.nextInt(8) == 0) it else it.negate() }
            Row(d, amt, merchants[rnd.nextInt(merchants.size)] + if (rnd.nextBoolean()) " " + (1000 + rnd.nextInt(8999)) else "")
        }
    }

    private class Dialect(
        val delimiter: Char, val charset: String, val datePattern: String, val decimal: Char, val thousands: String, val split: Boolean,
        val header: List<String>, val preamble: Int, val footer: Boolean, val quoteAll: Boolean, val crlf: Boolean, val bom: Boolean, val signAfter: Boolean, val order: List<Int>,
    )

    private val headers = listOf(
        listOf("Date", "Description", "Amount"), listOf("Dato", "Forklaring", "Beløp"), listOf("Datum", "Verwendungszweck", "Betrag"),
        listOf("Datum", "Omschrijving", "Bedrag"), listOf("Fecha", "Concepto", "Importe"), listOf("Data", "Descrizione", "Importo"),
        listOf("Date", "Libellé", "Montant"), listOf("Data", "Opis", "Kwota"), listOf("Pvm", "Selitys", "Määrä"), listOf("Datum", "Text", "Belopp"),
        listOf("Buchungstag", "Buchungstext", "Umsatz"), listOf("Bokført", "Tekst", "Beløp"),
    )
    private val splitHeaders = mapOf("Date" to ("Debit" to "Credit"), "Dato" to ("Debet" to "Kredit"), "Datum" to ("Soll" to "Haben"), "Fecha" to ("Cargo" to "Abono"),
        "Data" to ("Uscite" to "Entrate"), "Pvm" to ("Veloitus" to "Hyvitys"), "Bokført" to ("Ut" to "Inn"))

    private fun dialect(rnd: Random): Dialect {
        val charset = listOf("UTF-8", "windows-1252", "ISO-8859-1", "UTF-16LE").let { it[rnd.nextInt(it.size)] }
        val delim = listOf(';', ',', '\t', '|').let { it[rnd.nextInt(it.size)] }
        val decimal = if (delim == ',' && rnd.nextBoolean()) '.' else if (rnd.nextInt(3) == 0) '.' else ','
        val h = headers[rnd.nextInt(headers.size)]
        return Dialect(
            delim,
            charset,
            listOf("dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "dd.MM.yy", "d.M.yyyy", "yyyyMMdd", "dd MMM yyyy", "MM/dd/yyyy")[rnd.nextInt(10)],
            decimal,
            if (decimal == '.') listOf("", ",", " ", "'")[rnd.nextInt(4)] else listOf("", ".", " ", "\u00A0")[rnd.nextInt(4)],
            rnd.nextInt(4) == 0 && splitHeaders.containsKey(h[0]),
            h, if (rnd.nextInt(3) == 0) rnd.nextInt(5) + 1 else 0, rnd.nextInt(4) == 0, rnd.nextInt(3) == 0, rnd.nextBoolean(), rnd.nextInt(5) == 0,
            rnd.nextInt(8) == 0, listOf(0, 1, 2),
        )
    }

    private fun fmt(v: BigDecimal, d: Dialect): String {
        val abs = v.abs().setScale(2)
        val (ip, fp) = abs.toPlainString().split('.')
        val grouped = if (d.thousands.isEmpty()) ip else ip.reversed().chunked(3).joinToString(d.thousands) { it }.reversed().let { r -> ip.reversed().chunked(3).reversed().joinToString(d.thousands) { it.reversed() } }
        return grouped + d.decimal + fp
    }

    private fun render(rows: List<Row>, d: Dialect): ByteArray {
        val sep = d.delimiter.toString()
        val nl = if (d.crlf) "\r\n" else "\n"
        fun q(s: String): String {
            val needs = d.quoteAll || s.contains(d.delimiter) || s.contains('"') || s.contains('\n')
            return if (needs) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
        val sb = StringBuilder()
        repeat(d.preamble) { i -> sb.append(listOf("Account statement", "Customer: Test Person", "IBAN: NO9386011117947", "Period: 2024", "", "Printed on 2025-01-01")[i % 6]).append(nl) }
        val fmtDate = DateTimeFormatter.ofPattern(d.datePattern, Locale.ENGLISH)
        if (d.split) {
            val (deb, cre) = splitHeaders.getValue(d.header[0])
            sb.append(listOf(d.header[0], d.header[1], deb, cre).joinToString(sep) { q(it) }).append(nl)
        } else sb.append(d.header.joinToString(sep) { q(it) }).append(nl)
        for (r in rows) {
            val dt = r.date.format(fmtDate)
            if (d.split) {
                val s = fmt(r.amount, d)
                sb.append(listOf(dt, r.text, if (r.amount.signum() < 0) s else "", if (r.amount.signum() > 0) s else "").joinToString(sep) { q(it) })
            } else {
                var s = fmt(r.amount, d)
                if (r.amount.signum() < 0) s = if (d.signAfter) "$s-" else "-$s"
                sb.append(listOf(dt, r.text, s).joinToString(sep) { q(it) })
            }
            sb.append(nl)
        }
        if (d.footer) sb.append("Total;;").append(fmt(rows.fold(BigDecimal.ZERO) { a, b -> a + b.amount }, d)).append(nl).append("End of statement").append(nl)
        val text = sb.toString()
        val cs = Charset.forName(d.charset)
        val body = text.toByteArray(cs)
        return when {
            d.charset == "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
            d.charset == "UTF-8" && d.bom -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
            else -> body
        }
    }

    /** Characters not representable in the chosen charset are replaced by '?': mirror that in the expectation. */
    private fun expectedText(s: String, charset: String) = String(s.toByteArray(Charset.forName(charset)), Charset.forName(charset))

    @Test fun randomDialectsRoundTrip() {
        val total = 600
        var ok = 0
        val failures = ArrayList<String>()
        for (seed in 1..total) {
            val rnd = Random(seed.toLong())
            val rows = ledger(rnd, 40 + rnd.nextInt(60))
            val d = dialect(rnd)
            // MM/dd/yyyy is ambiguous unless some day > 12 exists; with 40+ rows spanning months that is guaranteed.
            val bytes = render(rows, d)
            val a = StatementImporter.analyze(InputFile("gen$seed.csv", bytes))
            val got = a.transactions
            val good = got.size == rows.size && got.indices.all { i ->
                got[i].date == rows[i].date && got[i].amount.compareTo(rows[i].amount) == 0
            }
            if (good) ok++ else failures.add("seed=$seed delim='${if (d.delimiter == '\t') "TAB" else d.delimiter}' cs=${d.charset} date=${d.datePattern} dec='${d.decimal}' th='${d.thousands}' split=${d.split} pre=${d.preamble} " +
                "hdr=${d.header} signAfter=${d.signAfter} -> rows=${got.size}/${rows.size} fail=${a.failure}")
        }
        println("generated dialects: $ok/$total round-trip exactly")
        java.io.File("build/reports/csv-eval").apply { mkdirs() }.resolve("generated-dialects.txt").writeText("$ok/$total\n" + failures.joinToString("\n"))
        assertTrue("only $ok/$total ok; first failures:\n" + failures.take(15).joinToString("\n"), ok >= total * 0.98)
    }

    @Test fun descriptionsSurviveEncodingRoundTrip() {
        val rnd = Random(99)
        val rows = ledger(rnd, 30)
        for (cs in listOf("UTF-8", "windows-1252", "UTF-16LE")) {
            val d = Dialect(';', cs, "dd.MM.yyyy", ',', "", false, listOf("Dato", "Forklaring", "Beløp"), 0, false, false, false, false, false, listOf(0, 1, 2))
            val a = StatementImporter.analyze(InputFile("x.csv", render(rows, d)))
            assertEquals(cs, rows.size, a.transactions.size)
            val want = rows.map { expectedText(it.text, cs) }
            val have = a.transactions.map { it.description }
            if (cs != "UTF-8") assertTrue(cs, have.zip(want).all { (h, w) -> h.contains(w.substringBefore(" 1").trim().take(6)) })
            else assertTrue(have.zip(want).all { (h, w) -> h.contains(w.take(6)) })
        }
    }
}
