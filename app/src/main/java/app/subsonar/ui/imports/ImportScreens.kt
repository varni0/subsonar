@file:OptIn(ExperimentalMaterial3Api::class)

package app.subsonar.ui.imports

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.subsonar.R
import app.subsonar.domain.importer.ColumnMapping
import app.subsonar.domain.importer.ImportWarning
import app.subsonar.domain.importer.MappingEditing
import app.subsonar.domain.importer.SourceKind
import app.subsonar.domain.recurring.CandidateKind
import app.subsonar.domain.recurring.ConfidenceLevel
import app.subsonar.domain.recurring.Evidence
import app.subsonar.domain.recurring.EvidenceType
import app.subsonar.domain.recurring.RecurringCandidate
import app.subsonar.ui.AppViewModelFactory
import app.subsonar.ui.components.SectionHeader
import app.subsonar.ui.formatMoney
import app.subsonar.ui.formatPercent
import app.subsonar.domain.PriceHistoryCalculator
import app.subsonar.domain.recurring.PriceUpdateOffer
import app.subsonar.ui.labelRes
import java.math.BigDecimal

/** Everything the import screens can do; the screenshot tests pass no-ops. */
class ImportActions(
    val onChooseFiles: () -> Unit = {},
    val onReviewExisting: () -> Unit = {},
    val onToggleFile: (Int) -> Unit = {},
    val onRemoveFile: (Int) -> Unit = {},
    val onAdjust: (Int) -> Unit = {},
    val onMappingChange: (Int, ColumnMapping) -> Unit = { _, _ -> },
    val onCloseEditor: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onToggleCandidate: (String) -> Unit = {},
    val onToggleUpdate: (String) -> Unit = {},
    val onAddSelected: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@Composable
fun ImportRoute(onClose: () -> Unit, viewModel: ImportViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.onFilesPicked(it) }
    BackHandler { if (viewModel.back()) onClose() }
    val actions = remember(viewModel) {
        ImportActions(
            onChooseFiles = { picker.launch(arrayOf("*/*")) },
            onReviewExisting = viewModel::reviewExisting,
            onToggleFile = viewModel::toggleFile,
            onRemoveFile = viewModel::removeFile,
            onAdjust = viewModel::openEditor,
            onMappingChange = viewModel::updateMapping,
            onCloseEditor = viewModel::closeEditor,
            onConfirm = viewModel::confirmImport,
            onToggleCandidate = viewModel::toggleCandidate,
            onToggleUpdate = viewModel::toggleUpdate,
            onAddSelected = viewModel::addSelected,
            onBack = { if (viewModel.back()) onClose() },
            onClose = onClose,
        )
    }
    ImportScreen(state, actions)
}

@Composable
fun ImportScreen(state: ImportUiState, actions: ImportActions) {
    val title = when (state.stage) {
        ImportStage.PICK -> R.string.import_title
        ImportStage.PREVIEW -> R.string.import_preview_title
        ImportStage.EDIT -> R.string.import_edit_title
        ImportStage.REVIEW -> R.string.import_review_title
        ImportStage.DONE -> R.string.import_done_title
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.unlocked -> Text(stringResource(R.string.import_locked), Modifier.padding(24.dp))
                state.stage == ImportStage.PICK -> IntroContent(state, actions)
                state.stage == ImportStage.PREVIEW -> PreviewContent(state, actions)
                state.stage == ImportStage.EDIT -> {
                    val card = state.files.firstOrNull { it.id == state.editingFile }
                    if (card != null) EditorContent(card, actions)
                }
                state.stage == ImportStage.REVIEW -> state.review?.let { ReviewContent(it, state.busy, actions) }
                state.stage == ImportStage.DONE -> DoneContent(state, actions)
            }
            if (state.busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.import_reading), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ intro

@Composable
private fun IntroContent(state: ImportUiState, actions: ImportActions) {
    val res = LocalResources.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.import_intro_title), style = MaterialTheme.typography.headlineSmall)
        app.subsonar.ui.components.PrivacyBadge(subtle = true)
        Text(stringResource(R.string.import_intro_body), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.import_intro_tip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = actions.onChooseFiles, Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_choose_files)) }
        if (!state.coverage.isEmpty) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.import_history_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        pluralStringResource(R.plurals.import_history_summary, state.coverage.transactionCount, state.coverage.transactionCount, coverageSummary(state.coverage, res)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.coverage.gaps.isNotEmpty()) {
                        Text(
                            stringResource(R.string.import_coverage_gaps, state.coverage.gaps.joinToString(", ") { formatMonth(it) }),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(stringResource(R.string.import_not_backed_up), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = actions.onReviewExisting, Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_review_existing)) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ preview

@Composable
private fun PreviewContent(state: ImportUiState, actions: ImportActions) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.import_preview_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.files.size) { i -> FileCardView(state.files[i], actions) }
            item { TextButton(onClick = actions.onChooseFiles) { Text(stringResource(R.string.import_add_more_files)) } }
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp)) {
            if (state.importable == 0) Text(stringResource(R.string.import_nothing_to_import), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = actions.onConfirm, enabled = state.importable > 0 && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_confirm_n_files))
            }
        }
    }
}

private fun delimiterRes(c: Char) = when (c) {
    ';' -> R.string.import_delim_semicolon
    ',' -> R.string.import_delim_comma
    '\t' -> R.string.import_delim_tab
    else -> R.string.import_delim_pipe
}

@Composable
private fun FileCardView(card: FileCard, actions: ImportActions) {
    val a = card.analysis
    val ok = card.usable
    Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ok) Checkbox(checked = card.included, onCheckedChange = { actions.onToggleFile(card.id) })
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val format = if (a.kind == SourceKind.CSV && a.layout != null) {
                        val datePattern = a.mapping?.datePattern.orEmpty()
                        stringResource(R.string.import_file_format, stringResource(R.string.import_kind_csv), stringResource(delimiterRes(a.layout.delimiter)) + " · " + a.encoding, datePattern)
                    } else stringResource(R.string.import_file_format_open, stringResource(a.kind.labelRes()))
                    if (ok) Text(format, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { actions.onRemoveFile(card.id) }) { Text(stringResource(R.string.import_remove_file)) }
            }
            if (!ok) {
                Text(stringResource(R.string.import_failed_file), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                Text(stringResource(failureText(a.failure ?: app.subsonar.domain.importer.FileFailure.NO_TRANSACTIONS)), style = MaterialTheme.typography.bodyMedium)
                if (a.layout != null) TextButton(onClick = { actions.onAdjust(card.id) }) { Text(stringResource(R.string.import_adjust)) }
            } else {
                val txs = a.transactions
                val from = txs.minOf { it.date }
                val to = txs.maxOf { it.date }
                Text(
                    pluralStringResource(R.plurals.import_file_rows, txs.size, txs.size, stringResource(R.string.import_file_range, formatDate(from), formatDate(to))),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                if (a.fromSavedMapping) Text(stringResource(R.string.import_remembered), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                a.mapping?.let { m -> a.layout?.let { l -> Text(mappingSummary(m, l), style = MaterialTheme.typography.bodySmall) } }
                Text(stringResource(R.string.import_sample_rows), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                txs.take(4).forEach { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDate(t.date), Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(t.description, Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(money(t.amount, t.currency), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                val skipped = a.outcome?.skipped?.size ?: 0
                if (skipped > 0) Text(pluralStringResource(R.plurals.import_rows_skipped, skipped, skipped), style = MaterialTheme.typography.bodySmall)
                a.warnings.forEach { w -> Text("• " + stringResource(warningRes(w)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                if (a.layout != null) TextButton(onClick = { actions.onAdjust(card.id) }) { Text(stringResource(R.string.import_adjust)) }
            }
        }
    }
}

/** Amounts of files without a currency column are shown as plain numbers (the currency is the user's default). */
private fun money(amount: BigDecimal, currency: String?): String = if (currency == null) amount.toPlainString() else formatMoney(amount, currency)

@Composable
private fun columnName(layout: app.subsonar.domain.importer.DelimitedLayout, index: Int): String =
    MappingEditing.headerOf(layout, index) ?: stringResource(R.string.import_column_n, index + 1)

@Composable
private fun mappingSummary(m: ColumnMapping, l: app.subsonar.domain.importer.DelimitedLayout): String {
    val amount = if (m.isSplit) stringResource(R.string.import_mapping_split, columnName(l, m.debitColumn!!), columnName(l, m.creditColumn!!)) else columnName(l, m.amountColumn ?: 0)
    val text = m.descriptionColumns.map { columnName(l, it) }.joinToString(", ")
    return stringResource(R.string.import_mapping_summary, columnName(l, m.dateColumn), amount, text)
}

private fun warningRes(w: ImportWarning) = when (w) {
    ImportWarning.AMBIGUOUS_DATE_ORDER -> R.string.import_warn_ambiguous_date
    ImportWarning.SIGN_GUESSED -> R.string.import_warn_sign
    ImportWarning.NO_HEADER -> R.string.import_warn_no_header
    ImportWarning.SOME_ROWS_SKIPPED -> R.string.import_warn_skipped
    ImportWarning.LOW_CONFIDENCE -> R.string.import_warn_low_confidence
    ImportWarning.DECIMAL_GUESSED -> R.string.import_warn_decimal
    ImportWarning.SPLIT_ORDER_GUESSED -> R.string.import_warn_split
    ImportWarning.MIXED_ENCODING -> R.string.import_warn_mixed_encoding
    ImportWarning.TRUNCATED -> R.string.import_warn_truncated
}

// ------------------------------------------------------------------------------------------------ editor

@Composable
private fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) }) }
            }
        }
    }
}

@Composable
private fun EditorContent(card: FileCard, actions: ImportActions) {
    val layout = card.analysis.layout ?: return
    val m = card.analysis.mapping ?: ColumnMapping(0, "yyyy-MM-dd", emptyList(), amountColumn = 1)
    val columns = (0 until layout.columnCount).toList()
    val choice = columns.map { i -> i to stringResource(R.string.import_edit_column_choice, columnName(layout, i), MappingEditing.sampleOf(layout, i)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.import_edit_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // raw rows
        val shown = (listOfNotNull(layout.header) + layout.dataRows.take(3))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            shown.forEachIndexed { r, row ->
                Row {
                    columns.forEach { c ->
                        val text = row.getOrNull(c).orEmpty()
                        Text(
                            if (r == 0 && layout.header != null) "${c + 1} · $text" else text,
                            Modifier.width(120.dp).padding(end = 8.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (r == 0 && layout.header != null) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        Picker(stringResource(R.string.import_edit_date_column), choice, m.dateColumn) { actions.onMappingChange(card.id, m.copy(dateColumn = it, datePattern = MappingEditing.dateFormatOptions(layout, it, null).first())) }
        val formats = MappingEditing.dateFormatOptions(layout, m.dateColumn, m.datePattern)
        Picker(stringResource(R.string.import_edit_date_format), formats.map { it to it }, m.datePattern) { actions.onMappingChange(card.id, m.copy(datePattern = it)) }

        Text(stringResource(R.string.import_edit_amount_mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RadioRow(stringResource(R.string.import_edit_amount_single), !m.isSplit) {
            if (m.isSplit) actions.onMappingChange(card.id, MappingEditing.withSingleAmount(m, m.debitColumn ?: m.creditColumn ?: 0))
        }
        RadioRow(stringResource(R.string.import_edit_amount_split), m.isSplit) {
            if (!m.isSplit) {
                val a = m.amountColumn ?: 0
                actions.onMappingChange(card.id, MappingEditing.withSplitAmount(m, a, (a + 1).coerceAtMost(layout.columnCount - 1)))
            }
        }
        if (m.isSplit) {
            Picker(stringResource(R.string.import_edit_out_column), choice, m.debitColumn) { actions.onMappingChange(card.id, m.copy(debitColumn = it)) }
            Picker(stringResource(R.string.import_edit_in_column), choice, m.creditColumn) { actions.onMappingChange(card.id, m.copy(creditColumn = it)) }
        } else {
            Picker(stringResource(R.string.import_edit_amount_column), choice, m.amountColumn) { actions.onMappingChange(card.id, m.copy(amountColumn = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.import_edit_flip), Modifier.weight(1f))
                Switch(checked = m.invertSign, onCheckedChange = { actions.onMappingChange(card.id, m.copy(invertSign = it)) })
            }
        }
        Text(stringResource(R.string.import_edit_decimal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RadioRow(stringResource(R.string.import_edit_decimal_comma), m.decimalSeparator == ',') { actions.onMappingChange(card.id, m.copy(decimalSeparator = ',')) }
        RadioRow(stringResource(R.string.import_edit_decimal_dot), m.decimalSeparator == '.') { actions.onMappingChange(card.id, m.copy(decimalSeparator = '.')) }

        Text(stringResource(R.string.import_edit_text_columns), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            columns.forEach { c ->
                FilterChip(
                    selected = c in m.descriptionColumns,
                    onClick = { actions.onMappingChange(card.id, MappingEditing.toggleTextColumn(m, c)) },
                    label = { Text(columnName(layout, c), maxLines = 1) },
                )
            }
        }
        HorizontalDivider()
        val n = card.analysis.transactions.size
        Text(
            if (n > 0) pluralStringResource(R.plurals.import_edit_result, n, n) else stringResource(R.string.import_edit_result_none),
            style = MaterialTheme.typography.titleSmall, color = if (n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        card.analysis.transactions.take(3).forEach { t ->
            Text("${formatDate(t.date)}  ${t.description.take(30)}  ${money(t.amount, t.currency)}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Button(onClick = actions.onCloseEditor, Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_edit_done)) }
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 12.dp))
    }
}

// ------------------------------------------------------------------------------------------------ review

@Composable
private fun ReviewContent(r: ReviewState, busy: Boolean, actions: ImportActions) {
    val res = LocalResources.current
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val guidance = when {
                        r.isEmpty -> stringResource(R.string.import_review_none)
                        r.basis.months.size <= 1 -> stringResource(R.string.import_review_guidance_one)
                        else -> stringResource(R.string.import_review_guidance_n, coverageSummary(r.basis, res))
                    }
                    Text(guidance, style = MaterialTheme.typography.bodyMedium)
                    if (r.newTransactions + r.knownTransactions > 0) {
                        Text(pluralStringResource(R.plurals.import_review_added_summary, r.newTransactions, r.newTransactions, r.knownTransactions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.basis.gaps.isNotEmpty()) Text(stringResource(R.string.import_coverage_gaps, r.basis.gaps.joinToString(", ") { formatMonth(it) }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            candidateGroup(R.string.import_group_likely, null, r.groups.likely, r, actions)
            candidateGroup(R.string.import_group_bills, R.string.import_group_bills_hint, r.groups.bills, r, actions)
            candidateGroup(R.string.import_group_low, R.string.import_group_low_hint, r.groups.low, r, actions)
        }
        HorizontalDivider()
        val count = r.selectedCount
        Button(onClick = actions.onAddSelected, enabled = count > 0 && !busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.import_add_selected) + if (count > 0) " ($count)" else "")
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.candidateGroup(
    title: Int, hint: Int?, items: List<RecurringCandidate>, r: ReviewState, actions: ImportActions,
) {
    if (items.isEmpty()) return
    item { Column { SectionHeader(androidx.compose.ui.res.stringResource(title)); } }
    if (hint != null) item { Text(androidx.compose.ui.res.stringResource(hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(items.size) { i ->
        val c = items[i]
        CandidateRow(
            c, c.key in r.selected, c.key in r.duplicates, r.offers[c.key], c.key in r.selectedUpdates,
            onToggle = { actions.onToggleCandidate(c.key) }, onToggleUpdate = { actions.onToggleUpdate(c.key) },
        )
    }
}

@Composable
private fun CandidateRow(
    c: RecurringCandidate, selected: Boolean, duplicate: Boolean, offer: PriceUpdateOffer?, updateSelected: Boolean,
    onToggle: () -> Unit, onToggleUpdate: () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = !duplicate, role = Role.Checkbox, onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = !duplicate)
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val money = formatMoney(c.amount, c.currency).let { if (c.variableAmount) stringResource(R.string.import_candidate_variable, it) else it }
            Text(
                stringResource(R.string.import_candidate_line, money, stringResource(c.cycle.labelRes), formatDate(c.nextRenewal)) +
                    if (c.possiblyEnded) " · " + stringResource(R.string.import_possibly_ended) else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (duplicate) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.import_already_tracked)) })
                else {
                    SuggestionChip(onClick = {}, label = { Text(stringResource(when (c.confidence) {
                        ConfidenceLevel.HIGH -> R.string.import_confidence_high
                        ConfidenceLevel.MEDIUM -> R.string.import_confidence_medium
                        ConfidenceLevel.LOW -> R.string.import_confidence_low
                    })) })
                    if (c.unconfirmed) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.import_unconfirmed)) })
                }
                c.priceChange?.let { (from, to) ->
                    PercentChip(from, to)
                }
            }
            c.evidence.take(3).forEach { e ->
                Text("• " + evidenceText(e, c.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duplicate && c.priceSteps.isNotEmpty()) {
                Text(stringResource(R.string.import_price_history_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (offer != null) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = updateSelected, role = Role.Checkbox, onClick = onToggleUpdate).padding(start = 28.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = updateSelected, onCheckedChange = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(
                        R.string.import_price_update_offer, offer.subscriptionName,
                        formatMoney(offer.oldPrice, offer.currency), formatMoney(offer.newPrice, offer.currency),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.import_price_update_hint, formatDate(offer.effectiveFrom)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
  }
}

/** "Price up 16.7%" / "Price down 5%" chip for a detected price step. */
@Composable
private fun PercentChip(from: BigDecimal, to: BigDecimal) {
    val percent = PriceHistoryCalculator.percentChange(from, to) ?: return
    val text = if (percent.signum() >= 0) stringResource(R.string.price_up_badge, formatPercent(percent.abs()))
    else stringResource(R.string.price_down_badge, formatPercent(percent.abs()))
    SuggestionChip(onClick = {}, label = { Text(text) })
}

@Composable
internal fun evidenceText(e: Evidence, currency: String): String {
    // Amounts in the evidence are plain numbers; show them as money in the user's number format.
    val raw = e.detail.orEmpty()
    val d = if (e.type == EvidenceType.PRICE_CHANGE) {
        raw.split('→').mapNotNull { it.toBigDecimalOrNull() }.takeIf { it.size == 2 }
            ?.joinToString(" → ") { formatMoney(it, currency) } ?: raw
    } else if (e.type == EvidenceType.SAME_AMOUNT || e.type == EvidenceType.LARGE_FIXED_AMOUNT) {
        raw.toBigDecimalOrNull()?.let { formatMoney(it, currency) } ?: raw
    } else raw
    return when (e.type) {
        EvidenceType.SAME_AMOUNT -> stringResource(R.string.evidence_same_amount, d)
        EvidenceType.VARIABLE_AMOUNT -> stringResource(R.string.evidence_variable_amount)
        EvidenceType.PRICE_CHANGE -> stringResource(R.string.evidence_price_change, d)
        EvidenceType.REGULAR_INTERVAL -> stringResource(R.string.evidence_regular_interval, e.count.toString())
        EvidenceType.KEYWORD -> stringResource(R.string.evidence_keyword, d)
        EvidenceType.KNOWN_SERVICE -> stringResource(R.string.evidence_known_service, d)
        EvidenceType.MANDATE_ID -> stringResource(R.string.evidence_mandate)
        EvidenceType.BILL_KEYWORD -> stringResource(R.string.evidence_bill_keyword, d)
        EvidenceType.LARGE_FIXED_AMOUNT -> stringResource(R.string.evidence_large_fixed, d)
        EvidenceType.STORE_LIKE -> stringResource(R.string.evidence_store_like, d)
        EvidenceType.EXTRA_CHARGES -> stringResource(R.string.evidence_extra_charges)
        EvidenceType.FEW_OCCURRENCES -> stringResource(R.string.evidence_few_occurrences, e.count.toString())
        EvidenceType.ENDED -> stringResource(R.string.evidence_ended)
        EvidenceType.ONLY_ONE_CHARGE -> stringResource(R.string.evidence_only_one)
        EvidenceType.IRREGULAR_INTERVAL -> stringResource(R.string.evidence_irregular)
        EvidenceType.FREE_TRIAL_BEFORE -> stringResource(R.string.evidence_trial)
        EvidenceType.IGNORED_OTHER_AMOUNTS -> stringResource(R.string.evidence_ignored_currency, e.count.toString())
    }
}

// ------------------------------------------------------------------------------------------------ done

@Composable
private fun DoneContent(state: ImportUiState, actions: ImportActions) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val n = state.addedCount ?: 0
        if (n > 0 || state.updatedCount == 0) Text(pluralStringResource(R.plurals.import_done_body, n, n), style = MaterialTheme.typography.titleLarge)
        if (state.updatedCount > 0) {
            Text(
                pluralStringResource(R.plurals.import_done_updated, state.updatedCount, state.updatedCount),
                Modifier.padding(top = if (n > 0) 8.dp else 0.dp),
                style = if (n > 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
            )
        }
        if (state.limitHit) Text(stringResource(R.string.import_done_limit), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = actions.onClose, Modifier.padding(top = 24.dp)) { Text(stringResource(R.string.import_done_close)) }
    }
}
