package app.subsonar.domain.importer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.parsers.SAXParserFactory

/** ISO 20022 camt.053 / camt.052 bank-to-customer statement (XML). One transaction per `Ntry`. */
object Camt053Parser {
    fun looksLikeCamt(text: String): Boolean {
        val head = text.take(4000)
        return head.contains("camt.05") || (head.contains("<BkToCstmrStmt") || head.contains("<BkToCstmrAcctRpt")) ||
            Regex("<(\\w+:)?(BkToCstmr(Stmt|AcctRpt)|Ntry)\\b").containsMatchIn(text.take(20000))
    }

    fun parse(xml: String): List<ParsedTransaction>? {
        val handler = Handler()
        try {
            val f = SAXParserFactory.newInstance()
            f.isNamespaceAware = true
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ).forEach { (k, v) -> try { f.setFeature(k, v) } catch (_: Exception) { } }
            val reader = f.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(xml)))
        } catch (_: Exception) {
            if (handler.out.isEmpty()) return null
        }
        return handler.out
    }

    private class Handler : DefaultHandler() {
        val out = ArrayList<ParsedTransaction>()
        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var inEntry = false
        private var amount: BigDecimal? = null
        private var currency: String? = null
        private var credit: Boolean? = null
        private var reversal = false
        private var bookDate: LocalDate? = null
        private var valueDate: LocalDate? = null
        private val ustrd = ArrayList<String>()
        private var addtl: String? = null
        private var debtorName: String? = null
        private var creditorName: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes?) {
            val n = localName.orEmpty().ifEmpty { qName.orEmpty().substringAfter(':') }
            path.add(n)
            text.setLength(0)
            if (n == "Ntry" && path.size >= 2) {
                inEntry = true; amount = null; currency = null; credit = null; reversal = false
                bookDate = null; valueDate = null; ustrd.clear(); addtl = null; debtorName = null; creditorName = null
            }
            if (inEntry && n == "Amt" && path.getOrNull(path.size - 2) == "Ntry") currency = attrs?.getValue("Ccy")
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val n = localName.orEmpty().ifEmpty { qName.orEmpty().substringAfter(':') }
            val v = text.toString().trim()
            if (inEntry) {
                val parent = path.getOrNull(path.size - 2)
                when {
                    n == "Amt" && parent == "Ntry" -> amount = v.toBigDecimalOrNull()
                    n == "CdtDbtInd" && parent == "Ntry" -> credit = v.equals("CRDT", true)
                    n == "RvslInd" && parent == "Ntry" -> reversal = v.equals("true", true)
                    n == "Dt" && parent == "BookgDt" -> bookDate = parseDate(v)
                    n == "DtTm" && parent == "BookgDt" -> bookDate = parseDate(v)
                    n == "Dt" && parent == "ValDt" -> valueDate = parseDate(v)
                    n == "DtTm" && parent == "ValDt" -> valueDate = parseDate(v)
                    n == "Ustrd" -> if (v.isNotEmpty()) ustrd.add(v)
                    n == "AddtlNtryInf" -> if (v.isNotEmpty()) addtl = v
                    n == "Nm" && parent == "Dbtr" && debtorName == null -> debtorName = v
                    n == "Nm" && parent == "Cdtr" && creditorName == null -> creditorName = v
                    n == "Nm" && parent == "Pty" && path.getOrNull(path.size - 3) == "Dbtr" && debtorName == null -> debtorName = v
                    n == "Nm" && parent == "Pty" && path.getOrNull(path.size - 3) == "Cdtr" && creditorName == null -> creditorName = v
                }
                if (n == "Ntry") {
                    inEntry = false
                    val date = bookDate ?: valueDate
                    val amt = amount
                    if (date != null && amt != null && credit != null) {
                        var isCredit = credit == true
                        if (reversal) isCredit = !isCredit
                        val signed = if (isCredit) amt.abs() else amt.abs().negate()
                        val party = if (isCredit) debtorName else creditorName
                        val desc = listOfNotNull(party, ustrd.joinToString(" ").ifEmpty { null } ?: addtl)
                            .joinToString(" ").replace(Regex("\\s+"), " ").trim()
                        out.add(ParsedTransaction(date, signed, currency?.uppercase(), desc))
                    }
                }
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun parseDate(v: String): LocalDate? = try {
            if (v.length == 10) LocalDate.parse(v) else OffsetDateTime.parse(v).toLocalDate()
        } catch (_: DateTimeParseException) {
            try { LocalDate.parse(v.take(10)) } catch (_: DateTimeParseException) { null }
        }
    }
}

/** SWIFT MT940 customer statement: `:61:` statement lines with `:86:` information. */
object Mt940Parser {
    private val tagLine = Regex("""^:(\d{2}[A-Z]?):(.*)$""")
    private val line61 = Regex("""^(\d{6})(\d{4})?(R?[DC])([A-Z])?(\d+(?:,\d*)?)N([A-Z0-9]{3})(.*)$""")

    fun looksLikeMt940(text: String): Boolean {
        val head = text.take(6000)
        return Regex("(?m)^:20:").containsMatchIn(head) && Regex("(?m)^:6[01][A-Z]?:").containsMatchIn(text.take(20000)) ||
            Regex("(?m)^:61:\\d{6}").containsMatchIn(head)
    }

    fun parse(text: String): List<ParsedTransaction> {
        // Join lines into tag blocks.
        val blocks = ArrayList<Pair<String, String>>()
        var tag: String? = null
        val body = StringBuilder()
        fun flush() { tag?.let { blocks.add(it to body.toString().trimEnd()) }; body.setLength(0) }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            val m = tagLine.matchEntire(line)
            if (m != null) { flush(); tag = m.groupValues[1]; body.append(m.groupValues[2]) }
            else if (line.startsWith("-") && line.trim() == "-") { flush(); tag = null }
            else if (tag != null && !line.startsWith("{") && !line.startsWith("}")) body.append('\n').append(line)
        }
        flush()

        val out = ArrayList<ParsedTransaction>()
        var currency: String? = null
        var i = 0
        while (i < blocks.size) {
            val (t, b) = blocks[i]
            if (t.startsWith("60") || t.startsWith("62")) {
                Regex("""^[CD](\d{6})([A-Z]{3})""").find(b)?.let { currency = it.groupValues[2] }
            }
            if (t == "61") {
                val first = b.lineSequence().first().trim()
                val m = line61.matchEntire(first)
                if (m != null) {
                    val value = yymmdd(m.groupValues[1])
                    var date = value
                    val md = m.groupValues[2]
                    if (md.isNotEmpty() && value != null) {
                        val month = md.substring(0, 2).toInt(); val day = md.substring(2).toInt()
                        date = runCatching { LocalDate.of(value.year, month, day) }.getOrNull()
                            ?.let { d -> if (d.isAfter(value.plusMonths(6))) d.minusYears(1) else if (d.isBefore(value.minusMonths(6))) d.plusYears(1) else d } ?: value
                    }
                    val mark = m.groupValues[3]
                    val amount = m.groupValues[5].replace(',', '.').toBigDecimalOrNull()
                    var desc = ""
                    if (i + 1 < blocks.size && blocks[i + 1].first == "86") { desc = clean86(blocks[i + 1].second); i++ }
                    if (date != null && amount != null) {
                        val credit = mark.endsWith("C")
                        val reversal = mark.startsWith("R")
                        val positive = credit != reversal
                        out.add(ParsedTransaction(date, if (positive) amount.abs() else amount.abs().negate(), currency, desc))
                    }
                }
            }
            i++
        }
        return out
    }

    private fun yymmdd(s: String): LocalDate? = runCatching {
        LocalDate.of(DateParsing.expandYear(s.substring(0, 2).toInt(), 2), s.substring(2, 4).toInt(), s.substring(4, 6).toInt())
    }.getOrNull()

    private fun clean86(s: String): String {
        var t = s.replace(Regex("""\?\d{2}"""), " ").replace(Regex("""/[A-Z]{2,5}/"""), " ")
        t = t.replace(Regex("""(?m)^\d{3}\s"""), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
