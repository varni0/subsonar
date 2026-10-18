package app.subsonar.ui

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structural checks for the translations: every language has exactly the keys of the default (English) resources, the
 * placeholders match, the plural categories are the ones the language needs, and the language lists (picker, locales_config.xml,
 * values-* folders) agree. Pure JVM; reads src/main/res directly (the working directory of unit tests is the app module).
 */
class LocalizationTest {
    private val res = File("src/main/res")

    /** Resource folder of each supported tag. English is the default resource set. */
    private fun folder(tag: String) = when (tag) {
        "en" -> "values"
        "pt-PT" -> "values-pt" // Portuguese default = European Portuguese; pt-BR overrides it
        "pt-BR" -> "values-pt-rBR"
        else -> "values-$tag"
    }

    /** CLDR plural categories used for whole numbers, per language (what Android lint's MissingQuantity expects as well). */
    private val quantities = mapOf(
        "en" to setOf("one", "other"), "de" to setOf("one", "other"), "fr" to setOf("one", "many", "other"), "es" to setOf("one", "many", "other"),
        "it" to setOf("one", "many", "other"), "pt-PT" to setOf("one", "many", "other"), "pt-BR" to setOf("one", "many", "other"),
        "pl" to setOf("one", "few", "many", "other"), "nl" to setOf("one", "other"), "nb" to setOf("one", "other"), "sv" to setOf("one", "other"),
        "da" to setOf("one", "other"), "fi" to setOf("one", "other"), "cs" to setOf("one", "few", "many", "other"), "ro" to setOf("one", "few", "other"),
        "el" to setOf("one", "other"), "hu" to setOf("one", "other"), "uk" to setOf("one", "few", "many", "other"),
        "ru" to setOf("one", "few", "many", "other"), "tr" to setOf("one", "other"),
    )

    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val translatable: Map<String, Boolean>)

    private fun load(dir: String): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val strings = linkedMapOf<String, String>(); val plurals = linkedMapOf<String, Map<String, String>>(); val tr = HashMap<String, Boolean>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            tr[name] = e.getAttribute("translatable") != "false"
            when (e.tagName) {
                "string" -> strings[name] = e.textContent
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val c = e.getElementsByTagName("item")
                    for (j in 0 until c.length) (c.item(j) as Element).let { items[it.getAttribute("quantity")] = it.textContent }
                    plurals[name] = items
                }
            }
        }
        return Res(strings, plurals, tr)
    }

    private val default = load("values")
    private val nonDefault = AppLanguages.SUPPORTED.filter { it != "en" }
    private val placeholder = Regex("%(?:(\\d+)\\$)?([sd])")
    private fun placeholders(s: String) = placeholder.findAll(s).map { (it.groupValues[1].ifEmpty { "-" }) + it.groupValues[2] }.sorted().toList()

    @Test fun languageListsAgree() {
        val xml = File(res, "xml/locales_config.xml").readText()
        val inConfig = Regex("android:name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals("locales_config.xml must list exactly AppLanguages.SUPPORTED", AppLanguages.SUPPORTED, inConfig)
        for (tag in AppLanguages.SUPPORTED) assertTrue("missing resources for $tag (${folder(tag)})", File(res, "${folder(tag)}/strings.xml").exists())
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!.map { it.name }.toSet()
        assertEquals("every values-* folder with strings must belong to a supported language", AppLanguages.SUPPORTED.map(::folder).toSet() - "values", folders)
        assertEquals(setOf("en") + nonDefault, quantities.keys)
        assertTrue(File(res, "resources.properties").readText().contains("unqualifiedResLocale=en"))
    }

    @Test fun manifestPointsToLocaleConfig() {
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""))
    }

    @Test fun nineteenLanguagesPlusEnglish() {
        assertEquals(20, AppLanguages.SUPPORTED.size) // 19 requested languages (Portuguese counted once) + pt-BR
        assertEquals("en", AppLanguages.SUPPORTED.first())
    }

    @Test fun everyLanguageHasExactlyTheDefaultKeys() {
        val translatableStrings = default.strings.keys.filter { default.translatable[it] == true }.toSet()
        for (tag in nonDefault) {
            val r = load(folder(tag))
            assertEquals("$tag: missing/extra strings", translatableStrings, r.strings.keys)
            assertEquals("$tag: missing/extra plurals", default.plurals.keys, r.plurals.keys)
            assertFalse("$tag: app_name must not be translated", "app_name" in r.strings)
        }
    }

    @Test fun placeholdersMatchTheDefault() {
        for (tag in nonDefault) {
            val r = load(folder(tag))
            for ((k, v) in r.strings) assertEquals("$tag/$k placeholders", placeholders(default.strings.getValue(k)), placeholders(v))
            for ((k, items) in r.plurals) for ((q, v) in items) {
                val ref = default.plurals.getValue(k).let { it[q] ?: it.getValue("other") }
                assertEquals("$tag/$k[$q] placeholders", placeholders(ref), placeholders(v))
            }
        }
    }

    @Test fun pluralsHaveExactlyTheCategoriesOfTheLanguage() {
        for (tag in AppLanguages.SUPPORTED) {
            val r = load(folder(tag))
            for ((k, items) in r.plurals) assertEquals("$tag/$k quantities", quantities.getValue(tag), items.keys)
        }
    }

    @Test fun formatsWithSeveralArgumentsArePositional() {
        for (tag in AppLanguages.SUPPORTED) {
            val r = load(folder(tag))
            val all = r.strings.values + r.plurals.values.flatMap { it.values }
            for (v in all) if (placeholder.findAll(v).count() > 1) assertTrue("$tag: '$v' mixes positional and non-positional", placeholder.findAll(v).all { it.groupValues[1].isNotEmpty() })
        }
    }

    @Test fun noEmptyValuesAndNoStrayWhitespace() {
        for (tag in nonDefault) {
            val r = load(folder(tag))
            val all = r.strings + r.plurals.flatMap { (k, items) -> items.map { (q, v) -> "$k[$q]" to v } }
            for ((k, v) in all) {
                assertTrue("$tag/$k is empty", v.isNotBlank())
                assertEquals("$tag/$k has leading/trailing whitespace", v.trim(), v)
                assertFalse("$tag/$k contains a double space", "  " in v)
            }
        }
    }

    @Test fun mostStringsAreActuallyTranslated() {
        // Catches a language file that was just copied from English. Identical values are fine for names, codes and symbols.
        for (tag in nonDefault) {
            val r = load(folder(tag))
            val same = r.strings.filter { (k, v) -> v == default.strings[k] }.keys
            assertTrue("$tag: ${same.size} of ${r.strings.size} strings equal English: $same", same.size <= r.strings.size * 0.12)
        }
    }

    @Test fun portugueseVariantsDiffer() {
        val pt = load("values-pt"); val br = load("values-pt-rBR")
        assertTrue(br.strings.count { (k, v) -> pt.strings[k] != v } > 10)
    }

    @Test fun pickerNamesAreNativeAndUnique() {
        val names = AppLanguages.SUPPORTED.map(AppLanguages::nativeName)
        assertEquals(names.size, names.toSet().size)
        assertEquals("Deutsch", AppLanguages.nativeName("de"))
        assertEquals("Ελληνικά", AppLanguages.nativeName("el"))
        assertEquals("Українська", AppLanguages.nativeName("uk"))
        assertEquals("Русский", AppLanguages.nativeName("ru"))
        assertTrue(AppLanguages.nativeName("nb").startsWith("Norsk"))
    }

    @Test fun matchFindsTheBestSupportedLanguage() {
        fun m(tag: String) = AppLanguages.match(java.util.Locale.forLanguageTag(tag))
        assertEquals("de", m("de-AT")); assertEquals("pt-BR", m("pt-BR")); assertEquals("pt-PT", m("pt-PT")); assertEquals("pt-PT", m("pt"))
        assertEquals("nb", m("nb-NO")); assertEquals("nb", m("no")); assertEquals("en", m("en-GB")); assertNull(m("ja")); assertNull(AppLanguages.match(null))
    }
}
