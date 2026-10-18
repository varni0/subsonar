@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.subsonar.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.subsonar.R
import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.ui.AppViewModelFactory
import app.subsonar.domain.PriceHistoryCalculator
import app.subsonar.ui.EditUiState
import app.subsonar.ui.EditViewModel
import app.subsonar.ui.formatDateMedium
import app.subsonar.ui.formatMoney
import app.subsonar.ui.formatPercent
import app.subsonar.ui.formatSignedMoney
import app.subsonar.ui.labelRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun EditScreen(onDone: () -> Unit, viewModel: EditViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showEffectivePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }

    LaunchedEffect(s.finished) { if (s.finished) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.isNew) R.string.new_subscription else R.string.edit_subscription)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!s.isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = s.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v.take(60)) } },
                label = { Text(stringResource(R.string.field_name)) },
                isError = s.showErrors && s.nameError,
                supportingText = if (s.showErrors && s.nameError) {
                    { Text(stringResource(R.string.error_name_required)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                OutlinedTextField(
                    value = s.price,
                    onValueChange = { v -> viewModel.update { it.copy(price = v.take(12)) } },
                    label = { Text(stringResource(R.string.field_price)) },
                    isError = s.showErrors && s.priceError,
                    supportingText = if (s.showErrors && s.priceError) {
                        { Text(stringResource(R.string.error_price_invalid)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = s.currency,
                    onValueChange = { v ->
                        viewModel.update { it.copy(currency = v.filter(Char::isLetter).uppercase().take(3)) }
                    },
                    label = { Text(stringResource(R.string.field_currency)) },
                    isError = s.showErrors && s.currencyError,
                    supportingText = if (s.showErrors && s.currencyError) {
                        { Text(stringResource(R.string.error_currency_invalid)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.width(120.dp),
                )
            }

            if (s.priceChanged) {
                // Editing the price of an existing subscription records a price change; the user says from when it applies.
                ClickableField(
                    value = formatDateMedium(s.priceEffective),
                    label = stringResource(R.string.field_price_effective),
                    trailingIcon = R.drawable.ic_calendar,
                    onClick = { showEffectivePicker = true },
                )
                Text(
                    stringResource(R.string.price_change_recorded_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.field_cycle), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillingCycle.entries.forEach { c ->
                    FilterChip(
                        selected = s.cycle == c,
                        onClick = { viewModel.update { it.copy(cycle = c) } },
                        label = { Text(stringResource(c.labelRes)) },
                    )
                }
            }

            ClickableField(
                value = formatDateMedium(s.renewal),
                label = stringResource(R.string.field_next_renewal),
                trailingIcon = R.drawable.ic_calendar,
                onClick = { showDatePicker = true },
            )

            CategoryField(selected = s.category, onSelect = { c -> viewModel.update { it.copy(category = c) } })

            AppearanceRow(s.name, s.category, s.colorKey, s.avatar, onClick = { showAppearance = true })

            OutlinedTextField(
                value = s.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v.take(500)) } },
                label = { Text(stringResource(R.string.field_notes)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.field_reminders), Modifier.weight(1f))
                Switch(
                    checked = s.remindersEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(remindersEnabled = v) } },
                )
            }

            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.save))
            }

            if (!s.isNew) PriceHistorySection(s)
        }
    }

    if (showDatePicker) {
        DateDialog(
            initial = s.renewal, latest = null,
            onPick = { date -> viewModel.update { it.copy(renewal = date) } },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showEffectivePicker) {
        // A price change cannot start in the future: the stored price is the price being charged now.
        DateDialog(
            initial = s.priceEffective, latest = LocalDate.now(),
            onPick = { date -> viewModel.update { it.copy(priceEffective = date) } },
            onDismiss = { showEffectivePicker = false },
        )
    }

    if (showAppearance) {
        AppearanceDialog(
            name = s.name, category = s.category, colorKey = s.colorKey, avatar = s.avatar,
            onConfirm = { color, avatar -> viewModel.update { it.copy(colorKey = color, avatar = avatar) }; showAppearance = false },
            onDismiss = { showAppearance = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, s.name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (s.limitReached) {
        // Only reachable once monetization is enabled (see PremiumManager). Hook the paywall in here.
        AlertDialog(
            onDismissRequest = viewModel::dismissLimit,
            text = { Text(stringResource(R.string.limit_reached)) },
            confirmButton = { TextButton(onClick = viewModel::dismissLimit) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun DateDialog(initial: LocalDate, latest: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = if (latest == null) DatePickerDefaults.AllDates else object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(latest)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { ms -> onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = pickerState) }
}

/** Price history of the stored subscription: total change and a timeline, newest first. */
@Composable
private fun PriceHistorySection(s: EditUiState) {
    val original = s.original ?: return
    val summary = PriceHistoryCalculator.summarize(s.history, original.price, original.currencyCode, original.cycle)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.price_history_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (summary == null) {
            Text(
                stringResource(R.string.price_history_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val currency = summary.currencyCode
        val up = summary.yearlyDelta.signum() > 0
        val tone = when {
            summary.yearlyDelta.signum() > 0 -> MaterialTheme.colorScheme.error
            summary.yearlyDelta.signum() < 0 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(
            stringResource(R.string.price_history_total, summary.totalPercent?.let { formatPercent(it, signed = true) } ?: formatSignedMoney(summary.currentPrice - summary.startPrice, currency)),
            style = MaterialTheme.typography.titleMedium, color = tone,
        )
        Text(
            stringResource(R.string.price_per_year, formatSignedMoney(summary.yearlyDelta, currency)),
            style = MaterialTheme.typography.bodyMedium, color = tone,
        )
        Spacer(Modifier.height(4.dp))
        val steps = summary.timeline.reversed()
        steps.forEachIndexed { i, step ->
            TimelineRow(
                title = formatDateMedium(step.effectiveDate),
                detail = stringResource(R.string.price_from_to, formatMoney(step.oldPrice, currency), formatMoney(step.newPrice, currency)),
                badge = PriceHistoryCalculator.percentChange(step.oldPrice, step.newPrice)?.let { formatPercent(it, signed = true) },
                rising = step.newPrice > step.oldPrice,
                isLast = false,
            )
        }
        TimelineRow(
            title = stringResource(R.string.price_history_start),
            detail = formatMoney(summary.startPrice, currency),
            badge = null, rising = up, isLast = true,
        )
    }
}

@Composable
private fun TimelineRow(title: String, detail: String, badge: String?, rising: Boolean, isLast: Boolean) {
    val dot = if (badge == null) MaterialTheme.colorScheme.outline else if (rising) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).background(dot, CircleShape))
            if (!isLast) Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (isLast) 0.dp else 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
        if (badge != null) Text(badge, style = MaterialTheme.typography.bodyMedium, color = dot, fontWeight = FontWeight.Medium)
    }
}

/** Read-only text field that opens something on tap. */
@Composable
private fun ClickableField(value: String, label: String, trailingIcon: Int?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = trailingIcon?.let { { Icon(painterResource(it), null) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

@Composable
private fun CategoryField(selected: Category, onSelect: (Category) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ClickableField(
            value = stringResource(selected.labelRes),
            label = stringResource(R.string.field_category),
            trailingIcon = null,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Category.entries.forEach { c ->
                DropdownMenuItem(text = { Text(stringResource(c.labelRes)) }, onClick = { onSelect(c); expanded = false })
            }
        }
    }
}
