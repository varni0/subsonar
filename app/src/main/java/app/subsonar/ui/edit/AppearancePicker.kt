@file:OptIn(ExperimentalLayoutApi::class)

package app.subsonar.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.subsonar.R
import app.subsonar.data.Category
import app.subsonar.domain.Avatar
import app.subsonar.domain.SubscriptionVisuals
import app.subsonar.ui.components.SubscriptionAvatar
import app.subsonar.ui.components.SubscriptionIcons
import app.subsonar.ui.theme.avatarColors
import app.subsonar.ui.theme.paletteColor

/** Row on the edit screen: live avatar preview (smart default follows name + category) that opens the picker. */
@Composable
internal fun AppearanceRow(name: String, category: Category, colorKey: String?, avatar: String?, onClick: () -> Unit) {
    val visual = SubscriptionVisuals.resolve(name, category, colorKey, avatar)
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SubscriptionAvatar(visual, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (visual.isAutomatic) R.string.appearance_automatic else R.string.appearance_custom),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Colour swatches + line icons / emoji. Starts from what is shown now (also when that is the automatic default);
 * "Automatic" clears both choices again.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceDialog(
    name: String,
    category: Category,
    colorKey: String?,
    avatar: String?,
    onConfirm: (colorKey: String?, avatar: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismiss) {
        AppearancePanel(name, category, colorKey, avatar, onConfirm, onDismiss)
    }
}

/** The dialog's surface and content (separate so it can be rendered inline, e.g. for store screenshots). */
@Composable
fun AppearancePanel(
    name: String,
    category: Category,
    colorKey: String?,
    avatar: String?,
    onConfirm: (colorKey: String?, avatar: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = SubscriptionVisuals.resolve(name, category, colorKey, avatar)
    var color by remember { mutableStateOf(colorKey) }
    var pick by remember { mutableStateOf(avatar) }
    var emojiTab by remember { mutableStateOf(start.avatar is Avatar.Emoji) }
    val current = SubscriptionVisuals.resolve(name, category, color, pick)
    val effectiveColor = current.colorKey

    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
            Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubscriptionAvatar(current, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(name.ifBlank { stringResource(R.string.new_subscription) }, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.appearance_colour), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val keys = SubscriptionVisuals.COLORS
                    keys.forEachIndexed { i, key ->
                        val selected = key == effectiveColor
                        val cd = stringResource(R.string.appearance_option, i + 1, keys.size)
                        Box(
                            Modifier.size(36.dp)
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .padding(if (selected) 5.dp else 0.dp)
                                .background(paletteColor(key), CircleShape)
                                .clickable(role = Role.RadioButton) { color = key }
                                .semantics { contentDescription = cd; this.selected = selected },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !emojiTab, onClick = { emojiTab = false }, label = { Text(stringResource(R.string.appearance_icons)) })
                    FilterChip(selected = emojiTab, onClick = { emojiTab = true }, label = { Text(stringResource(R.string.appearance_emoji)) })
                }
                val colors = avatarColors(effectiveColor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val options: List<Avatar> = if (emojiTab) SubscriptionVisuals.EMOJIS.map(Avatar::Emoji) else SubscriptionVisuals.ICONS.map(Avatar::Icon)
                    options.forEachIndexed { i, option ->
                        val selected = current.avatar == option
                        val cd = stringResource(R.string.appearance_option, i + 1, options.size)
                        Box(
                            Modifier.size(44.dp)
                                .background(if (selected) colors.container else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                .then(if (selected) Modifier.border(2.dp, colors.content, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable(role = Role.RadioButton) { pick = SubscriptionVisuals.encode(option) }
                                .semantics { contentDescription = cd; this.selected = selected },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (option) {
                                is Avatar.Icon -> Icon(
                                    painterResource(SubscriptionIcons.res(option.key)), null, Modifier.size(24.dp),
                                    tint = if (selected) colors.content else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                is Avatar.Emoji -> Text(option.emoji, fontSize = with(LocalDensity.current) { 22.dp.toSp() })
                            }
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onConfirm(null, null) }) { Text(stringResource(R.string.appearance_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onConfirm(color, pick) }) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
