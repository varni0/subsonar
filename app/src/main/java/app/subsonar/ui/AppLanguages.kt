package app.subsonar.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The languages the UI is translated into. Keep in sync with `res/xml/locales_config.xml` and the `values-*` folders
 * (a unit test checks this). English is the default resource set.
 *
 * Android 13+ shows these in system settings > App languages (via `locales_config.xml`); the in-app picker in Settings
 * uses the same storage through [AppCompatDelegate.setApplicationLocales], which also works on Android 8-12.
 */
object AppLanguages {
    /** BCP-47 tags, English first, rest in the order of the picker. */
    val SUPPORTED: List<String> = listOf(
        "en", "de", "fr", "es", "it", "pt-PT", "pt-BR", "pl", "nl", "nb", "sv", "da", "fi", "cs", "ro", "el", "hu", "uk", "ru", "tr",
    )

    /** Name of the language in that language itself ("Deutsch", "Ελληνικά", "Português (Brasil)"). */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** The tag of the language chosen in the app or in system settings, or "" when following the system language. */
    fun current(): String {
        val chosen = AppCompatDelegate.getApplicationLocales()
        if (chosen.isEmpty) return ""
        return match(chosen[0]) ?: ""
    }

    /** Applies [tag] ("" = follow the system). The activity is recreated by AppCompat. */
    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    /** The supported tag that best matches [locale] (exact language+region, else same language), or null. */
    fun match(locale: Locale?): String? {
        if (locale == null) return null
        val exact = SUPPORTED.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
        if (exact != null) return exact
        val lang = locale.language.let { if (it == "no") "nb" else it }
        val sameRegion = SUPPORTED.firstOrNull { Locale.forLanguageTag(it).let { l -> l.language == lang && l.country == locale.country && locale.country.isNotEmpty() } }
        return sameRegion ?: SUPPORTED.firstOrNull { it == lang } ?: SUPPORTED.firstOrNull { it.substringBefore('-') == lang }
    }

    /**
     * A context whose resources use the app language, for text produced outside an activity (notifications from a worker).
     * On Android 13+ the application context already follows the per-app language; before that [storedTag] (mirrored in
     * the settings) is applied.
     */
    fun localizedContext(context: Context, storedTag: String): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || storedTag.isEmpty()) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(storedTag))
        return context.createConfigurationContext(config)
    }

    fun localeOf(context: Context): Locale = context.resources.configuration.locales[0]
}
