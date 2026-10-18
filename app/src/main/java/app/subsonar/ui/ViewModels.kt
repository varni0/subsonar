package app.subsonar.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.subsonar.AppContainer
import app.subsonar.SubsonarApp
import app.subsonar.data.AppSettings
import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.data.PriceChange
import app.subsonar.data.PriceChangeSource
import app.subsonar.data.Subscription
import app.subsonar.data.ThemeMode
import app.subsonar.domain.CsvExporter
import app.subsonar.domain.PriceHistoryCalculator
import app.subsonar.domain.PriceIncreaseSummary
import app.subsonar.domain.RenewalCalculator
import app.subsonar.domain.SpendCalculator
import app.subsonar.domain.SpendSummary
import app.subsonar.domain.importer.Coverage
import app.subsonar.premium.EntitlementState
import app.subsonar.premium.PremiumFeature
import app.subsonar.reminders.ReminderNotifier
import app.subsonar.reminders.ReminderScheduler
import app.subsonar.ui.imports.ImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

private val Application.container: AppContainer get() = (this as SubsonarApp).container

object AppViewModelFactory {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            MainViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            HomeViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            EditViewModel(app, app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            SettingsViewModel(app, app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            ImportViewModel(app, app.container)
        }
    }
}

/* ---------------- Main (theme) ---------------- */

class MainViewModel(container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/* ---------------- Home: list + insights ---------------- */

data class SubscriptionRow(
    val sub: Subscription,
    val nextRenewal: LocalDate,
    val daysUntil: Long,
    /** Percent of a net price increase in the last 12 months (for the "price up" badge), else null. */
    val hikePercent: BigDecimal? = null,
)

data class HomeUiState(
    val loading: Boolean = true,
    val rows: List<SubscriptionRow> = emptyList(),
    val summary: SpendSummary? = null,
    val priceIncreases: PriceIncreaseSummary = PriceIncreaseSummary.EMPTY,
    val headline: app.subsonar.domain.HomeHeadline? = null,
    val settings: AppSettings = AppSettings(),
    val entitlements: EntitlementState? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        container.subscriptions.subscriptions,
        container.subscriptions.priceHistory,
        container.settings.settings,
        container.entitlements.state,
    ) { subs, history, settings, ent ->
        val today = LocalDate.now()
        val historyBySub = history.groupBy { it.subscriptionId }
        val rows = subs.map {
            val next = RenewalCalculator.nextRenewal(it.renewalAnchor, it.cycle, today)
            SubscriptionRow(it, next, RenewalCalculator.daysUntil(next, today), PriceHistoryCalculator.badgePercent(it, historyBySub[it.id].orEmpty(), today))
        }.sortedWith(compareBy<SubscriptionRow> { it.nextRenewal }.thenBy { it.sub.name.lowercase() })
        val summary = SpendCalculator.summarize(subs, settings.defaultCurrency, today)
        val increases = PriceHistoryCalculator.increaseSummary(subs, history, today, settings.defaultCurrency)
        HomeUiState(
            loading = false,
            rows = rows,
            summary = summary,
            priceIncreases = increases,
            headline = app.subsonar.domain.HomeHeadlineCalculator.of(summary, rows.map { it.daysUntil }, increases),
            settings = settings,
            entitlements = ent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun dismissNotificationPrompt() = viewModelScope.launch { container.settings.setNotificationPromptDismissed() }
}

/* ---------------- Add / edit ---------------- */

data class EditUiState(
    val id: Long = 0,
    val loaded: Boolean = false,
    val name: String = "",
    val price: String = "",
    val currency: String = "",
    val cycle: BillingCycle = BillingCycle.MONTHLY,
    val renewal: LocalDate = LocalDate.now(),
    val category: Category = Category.OTHER,
    val notes: String = "",
    val remindersEnabled: Boolean = true,
    /** Avatar colour key / encoded avatar; null = automatic (see [app.subsonar.domain.SubscriptionVisuals]). */
    val colorKey: String? = null,
    val avatar: String? = null,
    /** The stored subscription as loaded (null for a new one); the price history is relative to it. */
    val original: Subscription? = null,
    /** Date the edited price takes effect (only used when the price of an existing subscription changed). */
    val priceEffective: LocalDate = LocalDate.now(),
    val history: List<PriceChange> = emptyList(),
    val showErrors: Boolean = false,
    val limitReached: Boolean = false,
    val finished: Boolean = false,
) {
    val isNew get() = id == 0L
    val nameError get() = name.isBlank()
    val priceError get() = parsePrice(price) == null
    val currencyError get() = !isValidCurrency(currency)
    val valid get() = !nameError && !priceError && !currencyError

    /** True when an existing subscription gets a new price in the same currency and cycle: this is recorded as a price change. */
    val priceChanged: Boolean get() {
        val o = original ?: return false
        val p = parsePrice(price) ?: return false
        return p.compareTo(o.price) != 0 && currency.equals(o.currencyCode, ignoreCase = true) && cycle == o.cycle
    }
}

class EditViewModel(
    private val app: Application,
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: Long = savedState.get<Long>("id") ?: savedState.get<String>("id")?.toLongOrNull() ?: 0L
    private var original: Subscription? = null

    private val _state = MutableStateFlow(EditUiState(id = id))
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = if (id != 0L) container.subscriptions.get(id) else null
            original = existing
            // Keep the history if its flow already delivered (the two coroutines race).
            val loadedHistory = _state.value.history
            _state.value = if (existing != null) EditUiState(
                id = existing.id,
                loaded = true,
                name = existing.name,
                price = existing.price.toPlainString(),
                currency = existing.currencyCode,
                cycle = existing.cycle,
                renewal = RenewalCalculator.nextRenewal(existing.renewalAnchor, existing.cycle, LocalDate.now()),
                category = existing.category,
                notes = existing.notes,
                remindersEnabled = existing.remindersEnabled,
                colorKey = existing.colorKey,
                avatar = existing.avatar,
                original = existing,
                history = loadedHistory,
            ) else EditUiState(loaded = true, currency = container.settings.current().defaultCurrency)
        }
        if (id != 0L) viewModelScope.launch {
            container.subscriptions.history(id).collect { h -> _state.update { it.copy(history = h) } }
        }
    }

    fun update(transform: (EditUiState) -> EditUiState) = _state.update(transform)

    fun save() {
        val s = _state.value
        if (!s.valid) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            if (s.isNew && !container.entitlements.canAddSubscription(container.subscriptions.count())) {
                _state.update { it.copy(limitReached = true) }
                return@launch
            }
            val base = original
            // Keep the original anchor if the user didn't change the date, to preserve month-end logic.
            val unchangedDate = base != null && base.cycle == s.cycle &&
                RenewalCalculator.nextRenewal(base.renewalAnchor, base.cycle, LocalDate.now()) == s.renewal
            val anchor = if (unchangedDate) base.renewalAnchor else s.renewal
            val sub = Subscription(
                id = s.id,
                name = s.name.trim(),
                price = parsePrice(s.price)!!,
                currencyCode = s.currency.uppercase(),
                cycle = s.cycle,
                renewalAnchor = anchor,
                category = s.category,
                notes = s.notes.trim(),
                remindersEnabled = s.remindersEnabled,
                lastRemindedFor = if (unchangedDate) base.lastRemindedFor else null,
                createdAtMillis = base?.createdAtMillis ?: System.currentTimeMillis(),
                importKey = base?.importKey, // keep the link to the bank-import suggestion (it was dropped on edit before)
                colorKey = s.colorKey,
                avatar = s.avatar,
            )
            val change = if (base != null && s.priceChanged) {
                PriceChange(
                    subscriptionId = base.id, effectiveDate = minOf(s.priceEffective, LocalDate.now()), oldPrice = base.price,
                    newPrice = sub.price, currencyCode = sub.currencyCode, cycle = sub.cycle, source = PriceChangeSource.MANUAL,
                )
            } else null
            container.subscriptions.save(sub, listOfNotNull(change))
            ReminderScheduler.runNow(app)
            _state.update { it.copy(finished = true) }
        }
    }

    fun delete() {
        val base = original ?: return
        viewModelScope.launch {
            container.subscriptions.delete(base)
            _state.update { it.copy(finished = true) }
        }
    }

    fun dismissLimit() = _state.update { it.copy(limitReached = false) }
}

/* ---------------- Settings ---------------- */

sealed interface ExportResult {
    data class Success(val count: Int) : ExportResult
    data object Failure : ExportResult
}

class SettingsViewModel(private val app: Application, private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val entitlements: StateFlow<EntitlementState> = container.entitlements.state
    val canExport: StateFlow<Boolean> = container.entitlements.state
        .map { PremiumFeature.CSV_EXPORT in it.unlockedFeatures }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val importCoverage: StateFlow<Coverage> = container.imports.coverage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Coverage.EMPTY)
    val importUnlocked: StateFlow<Boolean> = container.entitlements.state
        .map { PremiumFeature.BANK_IMPORT in it.unlockedFeatures }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun deleteImportedHistory() = viewModelScope.launch { container.imports.deleteHistory() }

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { container.settings.setDynamicColor(on) }
    fun setReminders(on: Boolean) = viewModelScope.launch {
        container.settings.setRemindersEnabled(on)
        if (on) ReminderScheduler.runNow(app)
    }
    fun setLeadDays(days: Int) = viewModelScope.launch {
        container.settings.setReminderLeadDays(days)
        ReminderScheduler.runNow(app)
    }
    fun setPriceAlerts(on: Boolean) = viewModelScope.launch { container.settings.setPriceAlertsEnabled(on) }

    /** Switches the app language ("" = system default); AppCompat recreates the activity. */
    fun setLanguage(tag: String) {
        viewModelScope.launch { container.settings.setLanguageTag(tag) }
        AppLanguages.set(tag)
        ReminderNotifier.createChannel(AppLanguages.localizedContext(app, tag)) // channel names follow the language
    }
    fun setDefaultCurrency(code: String) = viewModelScope.launch {
        if (isValidCurrency(code)) container.settings.setDefaultCurrency(code.uppercase())
    }
    fun onNotificationPermissionGranted() = ReminderScheduler.runNow(app)

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        _exportResult.value = try {
            val items = container.subscriptions.getAll()
            val csv = CsvExporter.export(items, LocalDate.now())
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM for Excel
                    out.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open output")
            }
            ExportResult.Success(items.size)
        } catch (e: Exception) {
            ExportResult.Failure
        }
    }

    fun consumeExportResult() { _exportResult.value = null }
}
