@file:OptIn(ExperimentalMaterial3Api::class)

package app.subsonar.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.subsonar.R
import app.subsonar.domain.CategoryShare
import app.subsonar.premium.AdSlot
import app.subsonar.ui.HomeViewModel
import app.subsonar.ui.components.EmptyState
import app.subsonar.ui.components.SectionHeader
import app.subsonar.ui.formatMoney
import app.subsonar.ui.formatPercent
import app.subsonar.ui.formatSignedMoney
import app.subsonar.domain.PriceIncreaseSummary
import app.subsonar.ui.labelRes
import app.subsonar.ui.theme.categoryColor

@Composable
fun InsightsScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_insights)) }) }) { padding ->
        val summary = state.summary
        val primary = summary?.primary
        if (state.loading) return@Scaffold
        if (summary == null || primary == null || state.rows.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_insights,
                title = stringResource(R.string.tab_insights),
                body = stringResource(R.string.insights_empty),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    StatCard(stringResource(R.string.monthly_total), formatMoney(primary.monthly, primary.currencyCode), Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    StatCard(stringResource(R.string.yearly_total), formatMoney(primary.yearly, primary.currencyCode), Modifier.weight(1f))
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    StatCard(stringResource(R.string.active_subscriptions), state.rows.size.toString(), Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    StatCard(stringResource(R.string.next_30_days), summary.dueNext30Days.toString(), Modifier.weight(1f))
                }
            }
            item { SectionHeader(stringResource(R.string.by_category)) }
            items(summary.categories, key = { it.category.name }) { share ->
                CategoryBar(share, primary.currencyCode)
            }
            item { SectionHeader(stringResource(R.string.price_increases_title)) }
            priceIncreaseItems(state.priceIncreases)
            val others = summary.totals.filter { it.currencyCode != primary.currencyCode }
            if (others.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.other_currencies)) }
                items(others, key = { it.currencyCode }) { t ->
                    Text(
                        stringResource(
                            R.string.currency_totals,
                            t.currencyCode,
                            formatMoney(t.monthly, t.currencyCode),
                            formatMoney(t.yearly, t.currencyCode),
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item { AdSlot(showAds = state.entitlements?.showAds == true, modifier = Modifier.padding(16.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.priceIncreaseItems(p: PriceIncreaseSummary) {
    if (p.isEmpty) {
        item {
            Text(
                stringResource(R.string.price_increases_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        return
    }
    item {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.price_increases_extra_yearly), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.totals.forEachIndexed { i, t ->
                    Text(
                        formatSignedMoney(t.extraYearly, t.currencyCode),
                        style = if (i == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.price_increases_count, p.hikes.size, p.hikes.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item { Text(stringResource(R.string.price_increases_biggest), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) }
    items(p.hikes.take(MAX_BIGGEST), key = { it.subscriptionId }) { h ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.price_from_to, formatMoney(h.fromPrice, h.currencyCode), formatMoney(h.toPrice, h.currencyCode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    h.percent?.let { formatPercent(it, signed = true) } ?: "",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.price_per_year, formatSignedMoney(h.extraYearly, h.currencyCode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_BIGGEST = 5

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryBar(share: CategoryShare, currency: String) {
    val color = categoryColor(share.category)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(share.category.labelRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(formatMoney(share.monthly, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "  " + formatPercent(java.math.BigDecimal((share.fraction * 100).toInt())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share.fraction.coerceIn(0f, 1f)).background(color))
        }
    }
}
