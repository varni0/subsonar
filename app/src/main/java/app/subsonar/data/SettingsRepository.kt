package app.subsonar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Currency
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colours; off by default so the app keeps its own Nordic palette (still optional in Settings). */
    val dynamicColor: Boolean = false,
    val remindersEnabled: Boolean = true,
    val reminderLeadDays: Int = 2,
    val defaultCurrency: String = defaultCurrencyForLocale(),
    val notificationPromptDismissed: Boolean = false,
    /** Notify when a price increase is found while importing bank statements (needs [remindersEnabled] too). */
    val priceAlertsEnabled: Boolean = true,
    /** BCP-47 tag of the in-app language, or "" for the system default. Mirrors AppCompatDelegate's stored locale (see [app.subsonar.ui.AppLanguages]). */
    val languageTag: String = "",
)

fun defaultCurrencyForLocale(): String =
    runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD")

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val LEAD_DAYS = intPreferencesKey("reminder_lead_days")
        val CURRENCY = stringPreferencesKey("default_currency")
        val PROMPT_DISMISSED = booleanPreferencesKey("notification_prompt_dismissed")
        val PRICE_ALERTS = booleanPreferencesKey("price_alerts_enabled")
        val LANGUAGE = stringPreferencesKey("language_tag")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = p[Keys.THEME]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: defaults.themeMode,
            dynamicColor = p[Keys.DYNAMIC] ?: defaults.dynamicColor,
            remindersEnabled = p[Keys.REMINDERS] ?: defaults.remindersEnabled,
            reminderLeadDays = p[Keys.LEAD_DAYS] ?: defaults.reminderLeadDays,
            defaultCurrency = p[Keys.CURRENCY] ?: defaults.defaultCurrency,
            notificationPromptDismissed = p[Keys.PROMPT_DISMISSED] ?: false,
            priceAlertsEnabled = p[Keys.PRICE_ALERTS] ?: defaults.priceAlertsEnabled,
            languageTag = p[Keys.LANGUAGE] ?: defaults.languageTag,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setRemindersEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.REMINDERS] = enabled }
    suspend fun setReminderLeadDays(days: Int) = context.dataStore.edit { it[Keys.LEAD_DAYS] = days.coerceIn(0, 30) }
    suspend fun setDefaultCurrency(code: String) = context.dataStore.edit { it[Keys.CURRENCY] = code }
    suspend fun setNotificationPromptDismissed() = context.dataStore.edit { it[Keys.PROMPT_DISMISSED] = true }
    suspend fun setPriceAlertsEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.PRICE_ALERTS] = enabled }
    suspend fun setLanguageTag(tag: String) = context.dataStore.edit { it[Keys.LANGUAGE] = tag }
}
