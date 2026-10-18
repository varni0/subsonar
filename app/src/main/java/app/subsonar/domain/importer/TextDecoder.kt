package app.subsonar.domain.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Result of turning raw bytes into text. [encoding] is a human-readable charset name. */
data class DecodedText(val text: String, val encoding: String, val hadBom: Boolean = false)

/**
 * Detects the text encoding of a bank export and decodes it. Pure JVM, never throws.
 *
 * Order: BOM -> UTF-16 without BOM (NUL-byte pattern) -> strict UTF-8 -> mixed UTF-8/legacy lines ->
 * single-byte code page (windows-1252 / ISO-8859-1 / windows-1250, chosen by letter statistics).
 * Pure-ASCII input is reported as "UTF-8" (all candidates decode it identically).
 */
object TextDecoder {
    private val CP1252 = Charset.forName("windows-1252")
    private val CP1250 = Charset.forName("windows-1250")
    private val LATIN1 = Charsets.ISO_8859_1

    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.isEmpty()) return DecodedText("", "UTF-8")
        // --- BOM ---
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return DecodedText(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8", hadBom = true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE", hadBom = true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE", hadBom = true)
        }
        // --- UTF-16 without BOM: mostly-ASCII text has a NUL in every other byte ---
        sniffUtf16(bytes)?.let { cs ->
            return DecodedText(String(bytes, cs), cs.name())
        }
        // --- UTF-8 (strict) ---
        strictUtf8(bytes)?.let { return DecodedText(it, "UTF-8") }
        // --- Mixed: some lines valid UTF-8 with multibyte chars, others legacy ---
        val (validMulti, invalid) = utf8Stats(bytes)
        if (validMulti >= 1 && validMulti >= invalid) {
            return DecodedText(decodeMixed(bytes), "UTF-8/windows-1252 (mixed)")
        }
        // --- Single-byte code pages ---
        return decodeSingleByte(bytes)
    }

    private fun sniffUtf16(b: ByteArray): Charset? {
        if (b.size < 4) return null
        val n = minOf(b.size, 4096) / 2 * 2
        var evenZero = 0
        var oddZero = 0
        var i = 0
        while (i < n) {
            if (b[i] == 0.toByte()) evenZero++
            if (b[i + 1] == 0.toByte()) oddZero++
            i += 2
        }
        val pairs = n / 2
        return when {
            oddZero > pairs * 0.3 && evenZero < pairs * 0.05 -> Charsets.UTF_16LE
            evenZero > pairs * 0.3 && oddZero < pairs * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun strictUtf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** (number of valid multibyte UTF-8 sequences, number of stray high bytes). */
    private fun utf8Stats(b: ByteArray): Pair<Int, Int> {
        var valid = 0
        var invalid = 0
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) { i++; continue }
            val need = when {
                c in 0xC2..0xDF -> 1
                c in 0xE0..0xEF -> 2
                c in 0xF0..0xF4 -> 3
                else -> -1
            }
            if (need > 0 && (1..need).all { k -> i + k < b.size && (b[i + k].toInt() and 0xC0) == 0x80 }) {
                valid++; i += need + 1
            } else {
                invalid++; i++
            }
        }
        return valid to invalid
    }

    private fun decodeMixed(b: ByteArray): String {
        val sb = StringBuilder()
        var start = 0
        while (start <= b.size) {
            var end = start
            while (end < b.size && b[end] != '\n'.code.toByte()) end++
            val lineEnd = minOf(end + 1, b.size)
            val line = b.copyOfRange(start, lineEnd)
            sb.append(strictUtf8(line) ?: String(line, CP1252))
            if (lineEnd >= b.size) break
            start = lineEnd
        }
        return sb.toString()
    }

    // Letters whose windows-1252 reading would be a symbol, a control gap or a rarely used character.
    private const val CENTRAL_STRONG = "ąĄłżŻśŚźŹľĽťŤ"
    // Clearly Western-European letters (Nordic, French, Spanish, Portuguese).
    private const val WESTERN_COMMON = "åæøÅÆØçñêëâîôÇÑÊËÂÎÔ"
    // Grave-accented vowels inside a word are typical for Czech/Slovak text read as windows-1252 (ě->ì, ů->ù, ň->ò), not for Western text.
    private val MID_WORD_GRAVE = Regex("[ìùòÌÙÒ]\\p{L}")

    private fun decodeSingleByte(b: ByteArray): DecodedText {
        val s1252 = String(b, CP1252)
        val s1250 = String(b, CP1250)
        val hasC1 = b.any { (it.toInt() and 0xFF) in 0x80..0x9F }

        val central = s1250.count { it in CENTRAL_STRONG } + MID_WORD_GRAVE.findAll(s1252).count() +
            // bytes undefined in windows-1252 (0x81 0x8D 0x8F 0x90 0x9D) can only be Central-European text
            b.count { (it.toInt() and 0xFF) in intArrayOf(0x81, 0x8D, 0x8F, 0x90, 0x9D) }
        val western = s1252.count { it in WESTERN_COMMON }
        val useCentral = central >= 2 && central * 2 >= western
        if (useCentral) return DecodedText(s1250, "windows-1250")
        // windows-1252 vs ISO-8859-1: they differ only in 0x80-0x9F (control chars in Latin-1).
        if (hasC1 && s1252.contains('\uFFFD')) return DecodedText(String(b, LATIN1), "ISO-8859-1")
        return DecodedText(s1252, "windows-1252")
    }
}
