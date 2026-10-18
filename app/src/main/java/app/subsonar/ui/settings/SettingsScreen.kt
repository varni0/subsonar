@file:OptIn(ExperimentalMaterial3Api::class)

package app.subsonar.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.subsonar.BuildConfig
import app.subsonar.R
import app.subsonar.data.ThemeMode
import app.subsonar.reminders.ReminderNotifier
import app.subsonar.ui.AppLanguages
import app.subsonar.ui.AppViewModelFactory
import app.subsonar.ui.ExportResult
import app.subsonar.ui.SettingsViewModel
import app.subsonar.ui.components.SectionHeader
import app.subsonar.ui.isValidCurrency
import java.time.LocalDate

private val LEAD_DAY_OPTIONS = listOf(1, 2, 3, 5, 7)

@Composable
fun SettingsScreen(onImport: () -> Unit = {}, viewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entitlements by viewModel.entitlements.collectAsStateWithLifecycle()
    val canExport by viewModel.canExport.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val coverage by viewModel.importCoverage.collectAsStateWithLifecycle()
    val importUnlocked by viewModel.importUnlocked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var dialog by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(AppLanguages.current()) }
    // Re-check on resume: the user may have changed notification permission in system settings.
    var canNotify by remember { mutableStateOf(ReminderNotifier.canNotify(context)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { canNotify = ReminderNotifier.canNotify(context) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = ReminderNotifier.canNotify(context)
        if (granted) viewModel.onNotificationPermissionGranted()
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(viewModel::exportCsv)
    }

    LaunchedEffect(exportResult) {
        when (val r = exportResult) {
            is ExportResult.Success -> Toast.makeText(context, resources.getQuantityString(R.plurals.export_done, r.count, r.count), Toast.LENGTH_SHORT).show()
            ExportResult.Failure -> Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
            null -> Unit
        }
        if (exportResult != null) viewModel.consumeExportResult()
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            app.subsonar.ui.components.PrivacyBadge(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), subtle = true)
            SectionHeader(stringResource(R.string.settings_appearance))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(stringResource(settings.themeMode.labelRes())) },
                modifier = Modifier.clickable { dialog = "theme" },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(if (language.isEmpty()) stringResource(R.string.theme_system) else AppLanguages.nativeName(language)) },
                modifier = Modifier.clickable { dialog = "language" },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchItem(stringResource(R.string.settings_dynamic_color), settings.dynamicColor, viewModel::setDynamicColor)
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_reminders))
            SwitchItem(stringResource(R.string.settings_reminders_enabled), settings.remindersEnabled) { on ->
                viewModel.setReminders(on)
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderNotifier.hasPermission(context)) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (settings.remindersEnabled && !canNotify) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.settings_notifications_blocked), color = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
            SwitchItem(stringResource(R.string.settings_price_alerts), settings.priceAlertsEnabled, viewModel::setPriceAlerts)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_lead_days)) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.days_before, settings.reminderLeadDays, settings.reminderLeadDays))
                },
                modifier = Modifier.clickable(enabled = settings.remindersEnabled) { dialog = "lead" },
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_data))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_currency)) },
                supportingContent = { Text(settings.defaultCurrency) },
                modifier = Modifier.clickable { dialog = "currency" },
            )
            if (canExport) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_export_csv)) },
                    supportingContent = { Text(stringResource(R.string.settings_export_csv_summary)) },
                    modifier = Modifier.clickable { exportLauncher.launch("subsonar-${LocalDate.now()}.csv") },
                )
            }

            if (importUnlocked) {
                HorizontalDivider()
                SectionHeader(stringResource(R.string.import_title))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_settings_item)) },
                    supportingContent = { Text(stringResource(R.string.import_settings_summary)) },
                    modifier = Modifier.clickable(onClick = onImport),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_history_title)) },
                    supportingContent = {
                        if (coverage.isEmpty) Text(stringResource(R.string.import_history_none))
                        else Column {
                            Text(
                                pluralStringResource(
                                    R.plurals.import_history_summary, coverage.transactionCount, coverage.transactionCount,
                                    app.subsonar.ui.imports.coverageSummary(coverage, resources),
                                ),
                            )
                            Text(stringResource(R.string.import_not_backed_up), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_delete_history), color = if (coverage.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.import_delete_history_summary)) },
                    modifier = Modifier.clickable(enabled = !coverage.isEmpty) { dialog = "deleteHistory" },
                )
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_plan)) },
                supportingContent = {
                    Text(
                        when {
                            !entitlements.monetizationActive -> stringResource(R.string.plan_early_access)
                            entitlements.isPremium -> stringResource(R.string.plan_premium)
                            else -> stringResource(R.string.plan_free) // TODO(monetization): open paywall on tap
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            )
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { stringResource(it.labelRes()) },
            onSelect = { viewModel.setTheme(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "language" -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = listOf("") + AppLanguages.SUPPORTED,
            selected = language,
            label = { if (it.isEmpty()) stringResource(R.string.theme_system) else AppLanguages.nativeName(it) },
            onSelect = { language = it; viewModel.setLanguage(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "lead" -> ChoiceDialog(
            title = stringResource(R.string.settings_lead_days),
            options = LEAD_DAY_OPTIONS,
            selected = settings.reminderLeadDays,
            label = { pluralStringResource(R.plurals.days_before, it, it) },
            onSelect = { viewModel.setLeadDays(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "deleteHistory" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.import_delete_confirm_title)) },
            text = { Text(stringResource(R.string.import_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteImportedHistory(); dialog = null
                    Toast.makeText(context, R.string.import_history_deleted, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        "currency" -> CurrencyDialog(
            initial = settings.defaultCurrency,
            onConfirm = { viewModel.setDefaultCurrency(it); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CurrencyDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf(initial) }
    val valid = isValidCurrency(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_default_currency)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter(Char::isLetter).uppercase().take(3) },
                singleLine = true,
                isError = !valid,
                supportingText = if (!valid) { { Text(stringResource(R.string.error_currency_invalid)) } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(code) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
