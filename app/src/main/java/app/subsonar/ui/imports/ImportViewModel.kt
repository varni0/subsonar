package app.subsonar.ui.imports

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.subsonar.AppContainer
import app.subsonar.R
import app.subsonar.data.PriceChange
import app.subsonar.data.PriceChangeSource
import app.subsonar.data.Subscription
import app.subsonar.data.imports.FileImportResult
import app.subsonar.data.imports.ImportedFile
import app.subsonar.domain.importer.ColumnMapping
import app.subsonar.domain.importer.Coverage
import app.subsonar.domain.importer.FileAnalysis
import app.subsonar.domain.importer.FileFailure
import app.subsonar.domain.importer.InputFile
import app.subsonar.domain.importer.MappingEditing
import app.subsonar.domain.importer.SourceKind
import app.subsonar.domain.importer.StatementImporter
import app.subsonar.domain.PriceHistoryCalculator
import app.subsonar.domain.recurring.ExistingSubscription
import app.subsonar.domain.recurring.PriceUpdateOffer
import app.subsonar.domain.recurring.RecurringCandidate
import app.subsonar.domain.recurring.RecurringDetector
import app.subsonar.domain.recurring.SuggestionGroups
import app.subsonar.domain.recurring.Suggestions
import app.subsonar.premium.PremiumFeature
import app.subsonar.reminders.PriceAlert
import app.subsonar.reminders.PriceAlertNotifier
import app.subsonar.reminders.PriceAlertPolicy
import app.subsonar.reminders.ReminderScheduler
import app.subsonar.ui.AppLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

enum class ImportStage { PICK, PREVIEW, EDIT, REVIEW, DONE }

/** One picked file (or zip entry) and what the importer made of it. */
data class FileCard(val id: Int, val analysis: FileAnalysis, val included: Boolean = true) {
    val name get() = analysis.name
    val usable get() = analysis.ok
}

data class ReviewState(
    val groups: SuggestionGroups,
    val duplicates: Set<String>,
    val selected: Set<String>,
    /** Months of history the detection was based on. */
    val basis: Coverage,
    val newTransactions: Int = 0,
    val knownTransactions: Int = 0,
    /** Price changes found for subscriptions that are already tracked, by candidate key. */
    val offers: Map<String, PriceUpdateOffer> = emptyMap(),
    /** Offers the user wants applied (all pre-selected; the user confirms with the button). */
    val selectedUpdates: Set<String> = emptySet(),
) {
    val isEmpty get() = groups.likely.isEmpty() && groups.bills.isEmpty() && groups.low.isEmpty() && offers.isEmpty()

    /** Number of things "apply" will do: new subscriptions plus price updates. */
    val selectedCount get() = selected.count { it !in duplicates } + selectedUpdates.count { it in offers }
}

data class ImportUiState(
    val stage: ImportStage = ImportStage.PICK,
    val busy: Boolean = false,
    val unlocked: Boolean = true,
    val files: List<FileCard> = emptyList(),
    val editingFile: Int? = null,
    val coverage: Coverage = Coverage.EMPTY,
    val review: ReviewState? = null,
    val addedCount: Int? = null,
    val updatedCount: Int = 0,
    val limitHit: Boolean = false,
) {
    val importable get() = files.count { it.included && it.usable }
}

class ImportViewModel(private val app: Application, private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState(unlocked = container.entitlements.isUnlocked(PremiumFeature.BANK_IMPORT)))
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var nextId = 1
    private var mappings: Map<String, ColumnMapping> = emptyMap()

    init {
        viewModelScope.launch { container.imports.coverage.collect { c -> _state.update { it.copy(coverage = c) } } }
        viewModelScope.launch { container.entitlements.state.collect { e -> _state.update { it.copy(unlocked = PremiumFeature.BANK_IMPORT in e.unlockedFeatures) } } }
    }

    // ---------------- picking and reading ----------------

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            mappings = container.imports.loadMappings()
            val cards = withContext(Dispatchers.Default) {
                uris.flatMap { uri -> readInput(uri) }.flatMap { StatementImporter.expand(it) }.map { f ->
                    StatementImporter.analyze(f) { fp -> mappings[fp] }
                }
            }
            val withIds = cards.map { FileCard(nextId++, it) }
            _state.update { it.copy(busy = false, files = it.files + withIds, stage = ImportStage.PREVIEW) }
        }
    }

    private suspend fun readInput(uri: Uri): List<InputFile> = withContext(Dispatchers.IO) {
        val name = try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "file"
        try {
            val limit = (StatementImporter.MAX_FILE_BYTES + 1).toLong()
            val bytes = app.contentResolver.openInputStream(uri)?.use { readUpTo(it, limit.toInt()) }
            if (bytes == null) listOf(InputFile(name, ByteArray(0))) else listOf(InputFile(name, bytes))
        } catch (_: Exception) {
            // Reported per file as "could not be read" without blocking the others.
            listOf(InputFile(name, ByteArray(0)))
        }
    }

    /** Reads at most [max] bytes (API 26 safe replacement for readNBytes). */
    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }

    fun toggleFile(id: Int) = updateFiles { f -> if (f.id == id) f.copy(included = !f.included) else f }
    fun removeFile(id: Int) = _state.update { s ->
        val files = s.files.filterNot { it.id == id }
        s.copy(files = files, stage = if (files.isEmpty()) ImportStage.PICK else s.stage)
    }
    private fun updateFiles(t: (FileCard) -> FileCard) = _state.update { s -> s.copy(files = s.files.map(t)) }

    // ---------------- adjusting columns ----------------

    fun openEditor(id: Int) = _state.update { it.copy(stage = ImportStage.EDIT, editingFile = id) }
    fun closeEditor() = _state.update { it.copy(stage = ImportStage.PREVIEW, editingFile = null) }

    /** Re-parses the file with a user-edited mapping. */
    fun updateMapping(id: Int, mapping: ColumnMapping) {
        val card = _state.value.files.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.Default) { StatementImporter.withMapping(card.analysis, MappingEditing.sanitized(mapping)) }
            _state.update { s -> s.copy(files = s.files.map { if (it.id == id) it.copy(analysis = updated) else it }) }
        }
    }

    // ---------------- import + detect ----------------

    fun confirmImport() {
        val s = _state.value
        val chosen = s.files.filter { it.included && it.usable }
        if (chosen.isEmpty() || s.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val results = container.imports.commit(chosen.map { c ->
                ImportedFile(c.name, c.analysis.fingerprint, c.analysis.mapping, c.analysis.transactions, c.analysis.outcome?.rowsRead ?: c.analysis.transactions.size)
            })
            showReview(results)
        }
    }

    /** Runs the detector over everything imported so far (no new files). */
    fun reviewExisting() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { showReview(emptyList()) }
    }

    private suspend fun showReview(results: List<FileImportResult>) {
        val today = LocalDate.now()
        val currency = container.settings.current().defaultCurrency
        val history = container.imports.allTransactions()
        val candidates = withContext(Dispatchers.Default) { RecurringDetector.detect(history, today, currency) }
        val existing = container.subscriptions.getAll().map { ExistingSubscription(it.name, it.importKey, it.id, it.price, it.currencyCode, it.cycle) }
        val groups = Suggestions.group(candidates)
        val all = groups.likely + groups.bills + groups.low
        val dups = all.filter { Suggestions.isDuplicate(it, existing) }.map { it.key }.toSet()
        val pre = all.filter { Suggestions.preselected(it, existing) }.map { it.key }.toSet()
        val offers = all.mapNotNull { c -> Suggestions.priceUpdateOffer(c, existing)?.let { c.key to it } }.toMap()
        _state.update {
            it.copy(
                busy = false, stage = ImportStage.REVIEW, files = emptyList(), editingFile = null,
                review = ReviewState(groups, dups, pre, Coverage.of(history.map { t -> t.date }, history.size), results.sumOf { r -> r.added }, results.sumOf { r -> r.duplicates }, offers, offers.keys),
            )
        }
    }

    fun toggleCandidate(key: String) = _state.update { s ->
        val r = s.review ?: return@update s
        if (key in r.duplicates) return@update s
        s.copy(review = r.copy(selected = if (key in r.selected) r.selected - key else r.selected + key))
    }

    fun toggleUpdate(key: String) = _state.update { s ->
        val r = s.review ?: return@update s
        if (key !in r.offers) return@update s
        s.copy(review = r.copy(selectedUpdates = if (key in r.selectedUpdates) r.selectedUpdates - key else r.selectedUpdates + key))
    }

    fun addSelected() {
        val s = _state.value
        val r = s.review ?: return
        val chosen = (r.groups.likely + r.groups.bills + r.groups.low).filter { it.key in r.selected && it.key !in r.duplicates }
        val updates = r.offers.filterKeys { it in r.selectedUpdates }.values.toList()
        if ((chosen.isEmpty() && updates.isEmpty()) || s.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val today = LocalDate.now()
            val settings = container.settings.current()
            val alerts = ArrayList<PriceAlert>()
            fun maybeAlert(id: Long, name: String, currency: String, cycle: app.subsonar.data.BillingCycle, from: BigDecimal, to: BigDecimal, date: LocalDate) {
                if (PriceAlertPolicy.shouldNotify(settings, from, to, date, today)) {
                    alerts += PriceAlert(id, name, currency, from, to, date, PriceHistoryCalculator.yearlyDelta(from, to, cycle))
                }
            }
            var added = 0
            var limit = false
            for (c in chosen) {
                if (!container.entitlements.canAddSubscription(container.subscriptions.count())) { limit = true; break }
                val id = container.subscriptions.save(toSubscription(c), priceHistoryOf(c))
                added++
                c.priceSteps.lastOrNull()?.let { maybeAlert(id, c.displayName, c.currency, c.cycle, it.from, it.to, it.date) }
            }
            var updated = 0
            for (o in updates) {
                val change = PriceChange(
                    subscriptionId = o.subscriptionId, effectiveDate = o.effectiveFrom, oldPrice = o.oldPrice, newPrice = o.newPrice,
                    currencyCode = o.currency, cycle = o.cycle, source = PriceChangeSource.IMPORT,
                )
                if (container.subscriptions.applyPriceChange(o.subscriptionId, change)) {
                    updated++
                    maybeAlert(o.subscriptionId, o.subscriptionName, o.currency, o.cycle, o.oldPrice, o.newPrice, o.effectiveFrom)
                }
            }
            if (added > 0 || updated > 0) ReminderScheduler.runNow(app)
            if (alerts.isNotEmpty()) {
                val ctx = AppLanguages.localizedContext(app, settings.languageTag)
                PriceAlertNotifier.notify(ctx, alerts, AppLanguages.localeOf(ctx))
            }
            _state.update { it.copy(busy = false, stage = ImportStage.DONE, addedCount = added, updatedCount = updated, limitHit = limit) }
        }
    }

    /** The price steps found for a new subscription, as history entries (the subscription id is filled in on save). */
    private fun priceHistoryOf(c: RecurringCandidate): List<PriceChange> = c.priceSteps.map {
        PriceChange(
            subscriptionId = 0, effectiveDate = it.date, oldPrice = it.from, newPrice = it.to, currencyCode = c.currency,
            cycle = c.cycle, source = PriceChangeSource.IMPORT,
        )
    }

    private fun toSubscription(c: RecurringCandidate) = Subscription(
        name = c.displayName, price = c.amount, currencyCode = c.currency, cycle = c.cycle,
        // The last charge is the anchor; RenewalCalculator derives the upcoming date (and keeps month-end dates stable).
        renewalAnchor = c.lastDate, category = c.category, notes = app.getString(R.string.import_note_from_bank), importKey = c.key,
    )

    fun backToFiles() = _state.update { it.copy(stage = if (it.files.isEmpty()) ImportStage.PICK else ImportStage.PREVIEW) }

    fun reset() = _state.update { ImportUiState(unlocked = it.unlocked, coverage = it.coverage) }

    /** Back button: one step back, true if the screen should close. */
    fun back(): Boolean {
        val s = _state.value
        return when (s.stage) {
            ImportStage.EDIT -> { closeEditor(); false }
            ImportStage.PREVIEW -> { reset(); false }
            ImportStage.REVIEW -> { reset(); false }
            else -> true
        }
    }
}

internal fun failureText(f: FileFailure): Int = when (f) {
    FileFailure.EMPTY -> R.string.import_fail_empty
    FileFailure.TOO_LARGE -> R.string.import_fail_too_large
    FileFailure.NOT_TEXT -> R.string.import_fail_not_text
    FileFailure.NO_TABLE -> R.string.import_fail_no_table
    FileFailure.NO_DATE_COLUMN -> R.string.import_fail_no_date
    FileFailure.NO_AMOUNT_COLUMN -> R.string.import_fail_no_amount
    FileFailure.NO_TRANSACTIONS -> R.string.import_fail_no_transactions
    FileFailure.UNREADABLE -> R.string.import_fail_unreadable
}

internal fun SourceKind.labelRes(): Int = when (this) {
    SourceKind.CSV -> R.string.import_kind_csv
    SourceKind.CAMT053 -> R.string.import_kind_camt
    SourceKind.MT940 -> R.string.import_kind_mt940
}
