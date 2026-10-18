@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.subsonar.ui.list

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.subsonar.R
import app.subsonar.domain.SpendSummary
import app.subsonar.premium.AdSlot
import app.subsonar.premium.PremiumFeature
import app.subsonar.reminders.ReminderNotifier
import app.subsonar.reminders.ReminderScheduler
import app.subsonar.ui.HomeViewModel
import app.subsonar.ui.SubscriptionRow
import app.subsonar.ui.formatMoney
import app.subsonar.ui.formatPercent
import java.math.BigDecimal
import app.subsonar.ui.labelRes
import app.subsonar.domain.HomeHeadline
import app.subsonar.domain.SubscriptionVisuals
import app.subsonar.ui.components.BrandMark
import app.subsonar.ui.components.PrivacyBadge
import app.subsonar.ui.components.SubscriptionAvatar
import app.subsonar.ui.components.Wordmark
import app.subsonar.ui.formatSignedMoney
import app.subsonar.ui.theme.Brand
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign

@Composable
fun SubscriptionListScreen(
    viewModel: HomeViewModel,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onImport: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // Bank import is a premium candidate (PremiumFeature.BANK_IMPORT); unlocked while monetization is off.
    val importUnlocked = state.entitlements?.let { PremiumFeature.BANK_IMPORT in it.unlockedFeatures } ?: true

    var hasPermission by remember { mutableStateOf(ReminderNotifier.hasPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) ReminderScheduler.runNow(context) else viewModel.dismissNotificationPrompt()
    }
    val showPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission &&
        state.settings.remindersEnabled && !state.settings.notificationPromptDismissed && state.rows.isNotEmpty()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Wordmark() },
                scrollBehavior = scroll,
                actions = {
                    if (importUnlocked) IconButton(onClick = onImport) {
                        Icon(painterResource(R.drawable.ic_import), stringResource(R.string.import_list_action))
                    }
                },
            )
        },
        floatingActionButton = {
            // Empty state has its own primary (import) and secondary (manual) actions.
            if (!state.loading && state.rows.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text(stringResource(R.string.add_subscription)) },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        if (state.rows.isEmpty()) {
            ImportFirstEmptyState(importUnlocked, onImport = onImport, onAdd = onAdd, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            item { state.summary?.let { summary -> state.headline?.let { SummaryCard(it, summary) } } }
            if (showPrompt) item {
                NotificationPrompt(
                    onAllow = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onDismiss = viewModel::dismissNotificationPrompt,
                )
            }
            item { AdSlot(showAds = state.entitlements?.showAds == true) }
            items(state.rows, key = { it.sub.id }) { row ->
                SubscriptionItem(row, onClick = { onOpen(row.sub.id) })
            }
        }
    }
}

/** Top card: yearly total (big) + monthly, "N renew this week", "+X/yr from price hikes", other currencies. */
@Composable
private fun SummaryCard(headline: HomeHeadline, summary: SpendSummary) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val container = if (darkTheme) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
    val content = if (darkTheme) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(stringResource(R.string.yearly_total), style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f))
            Text(
                formatMoney(headline.yearly, headline.currencyCode),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                stringResource(R.string.summary_per_month_approx, formatMoney(headline.monthly, headline.currencyCode)),
                style = MaterialTheme.typography.bodyLarge,
                color = content.copy(alpha = 0.9f),
            )
            val others = summary.totals.filter { it.currencyCode != headline.currencyCode }
            if (others.isNotEmpty()) {
                Text(
                    others.joinToString("  ·  ") { resources.getString(R.string.per_month_short, formatMoney(it.monthly, it.currencyCode)) },
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FactChip(
                    icon = R.drawable.ic_week,
                    text = if (headline.renewThisWeek == 0) stringResource(R.string.summary_nothing_this_week)
                    else pluralStringResource(R.plurals.summary_renew_week, headline.renewThisWeek, headline.renewThisWeek),
                    container = content.copy(alpha = 0.14f), content = content,
                )
                headline.hikeExtraYearly?.let { extra ->
                    FactChip(
                        icon = R.drawable.ic_trend_up,
                        text = stringResource(R.string.summary_price_hikes, formatSignedMoney(extra, headline.currencyCode)),
                        container = Brand.Cloudberry, content = Brand.PolarNight,
                    )
                }
            }
        }
    }
}

@Composable
private fun FactChip(icon: Int, text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Empty list: bank import is the hero (primary), manual entry secondary, plus the privacy promise. */
@Composable
private fun ImportFirstEmptyState(importUnlocked: Boolean, onImport: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(112.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(if (importUnlocked) R.string.empty_import_title else R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (importUnlocked) R.string.empty_import_body else R.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (importUnlocked) {
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(painterResource(R.drawable.ic_import), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.empty_import_action))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.empty_manual_action))
            }
        } else {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.add_subscription)) }
        }
        Spacer(Modifier.height(24.dp))
        PrivacyBadge()
    }
}

@Composable
private fun NotificationPrompt(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.enable_notifications_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.enable_notifications_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
                TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) }
            }
        }
    }
}

@Composable
private fun SubscriptionItem(row: SubscriptionRow, onClick: () -> Unit) {
    val sub = row.sub
    val soon = row.daysUntil <= 3
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubscriptionAvatar(SubscriptionVisuals.resolve(sub))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(sub.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(sub.category.labelRes) + " · " + stringResource(sub.cycle.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // no line limit: long translations wrap instead of being cut off
            )
            row.hikePercent?.let { PriceUpBadge(it, Modifier.padding(top = 4.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 128.dp), horizontalAlignment = Alignment.End) {
            Text(formatMoney(sub.price, sub.currencyCode), style = MaterialTheme.typography.titleMedium)
            Text(
                renewalLabel(row.daysUntil),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                color = if (soon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (soon) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** Small "Price up 20%" tag for a net increase in the last 12 months. */
@Composable
internal fun PriceUpBadge(percent: BigDecimal, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            stringResource(R.string.price_up_badge, formatPercent(percent)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun renewalLabel(days: Long): String = when (days) {
    0L -> stringResource(R.string.renews_today)
    1L -> stringResource(R.string.renews_tomorrow)
    else -> pluralStringResource(R.plurals.renews_in_days, days.toInt(), days.toInt())
}
