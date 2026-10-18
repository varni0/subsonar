package app.subsonar.domain.importer

import org.junit.Assert.*
import org.junit.Test

class TextDecoderTest {
    private val sample = "Dato;Forklaring;Beløp\n01.01.2025;Kjøp ÆØÅ æøå é ü;-12,50\n"

    @Test fun utf8WithoutBom() = assertEquals(sample to "UTF-8", decode(sample.toByteArray(Charsets.UTF_8)))
    @Test fun utf8Bom() {
        val r = TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray())
        assertEquals(sample, r.text); assertTrue(r.hadBom); assertEquals("UTF-8", r.encoding)
    }
    @Test fun utf16WithBomLe() = assertEquals(sample, TextDecoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)).text)
    @Test fun utf16WithBomBe() = assertEquals(sample, TextDecoder.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)).text)
    @Test fun utf16WithoutBom() {
        assertEquals(sample to "UTF-16LE", decode(sample.toByteArray(Charsets.UTF_16LE)))
        assertEquals(sample to "UTF-16BE", decode(sample.toByteArray(Charsets.UTF_16BE)))
    }
    @Test fun windows1252AndLatin1() {
        val (t, enc) = decode(sample.toByteArray(charset("windows-1252")))
        assertEquals(sample, t); assertEquals("windows-1252", enc)
        assertEquals(sample, decode(sample.toByteArray(Charsets.ISO_8859_1)).first)
    }
    @Test fun windows1250CentralEuropean() {
        val cz = "Datum;Popis;Částka\n01.01.2025;Platba kartou Řezníkův žlutý kůň ěščřžýáíé;-120,00\n"
        val (t, enc) = decode(cz.toByteArray(charset("windows-1250")))
        assertEquals(cz, t); assertEquals("windows-1250", enc)
        val pl = "Data;Tytuł;Kwota\n01.01.2025;Zażółć gęślą jaźń ŁÓDŹ;-12,00\n"
        assertEquals(pl, decode(pl.toByteArray(charset("windows-1250"))).first)
    }
    @Test fun norwegianLatin1IsNotMistakenForCentralEuropean() {
        val no = "Dato;Forklaring;Beløp\n01.01.2025;Bjørn Ærlig Åse røde grøt;-99,00\n"
        assertEquals("windows-1252", decode(no.toByteArray(charset("windows-1252"))).second)
    }
    @Test fun asciiIsReportedAsUtf8() = assertEquals("UTF-8", decode("a;b\n1;2\n".toByteArray()).second)
    @Test fun mixedUtf8AndLegacyLinesAreBothReadable() {
        val bytes = "Datum;Text\n".toByteArray() + "01.01.2025;Café Müller\n".toByteArray(Charsets.UTF_8) + "02.01.2025;Bj".toByteArray() + byteArrayOf(0xF8.toByte()) + "rn\n".toByteArray()
        val r = TextDecoder.decode(bytes)
        assertTrue(r.text.contains("Café Müller")); assertTrue(r.text.contains("Bjørn")); assertTrue(r.encoding.contains("mixed"))
    }
    @Test fun emptyAndSingleByteInputs() {
        assertEquals("", TextDecoder.decode(ByteArray(0)).text)
        TextDecoder.decode(byteArrayOf(0xFF.toByte())); TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte()))
    }
    private fun decode(b: ByteArray) = TextDecoder.decode(b).let { it.text to it.encoding }
}
