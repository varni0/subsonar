package app.subsonar.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.subsonar.R

/**
 * The app icon (background + foreground layers) as an in-app brand mark. Contains no text, so renaming the app only
 * changes the wordmark string next to it.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    // The adaptive-icon layers are 108dp with a 72dp visible area: draw them 1.5x larger and clip to the visible part.
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}

/**
 * Product name shown in the wordmark. null = the app_name resource. Store-screenshot renders provide the final name here,
 * so a rename never needs code changes (only the string).
 */
val LocalProductName = staticCompositionLocalOf<String?> { null }

/** Brand mark + app name for top bars. */
@Composable
fun Wordmark(name: String = LocalProductName.current ?: stringResource(R.string.app_name)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(28.dp)
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.titleLarge)
    }
}

/** "Nothing leaves this phone · No internet access". [subtle] = smaller, outline-only variant for Settings/import intro. */
@Composable
fun PrivacyBadge(modifier: Modifier = Modifier, subtle: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (subtle) scheme.surfaceContainerLow else scheme.secondaryContainer,
        contentColor = if (subtle) scheme.onSurfaceVariant else scheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = if (subtle) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_privacy), null, Modifier.size(if (subtle) 16.dp else 18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.privacy_badge),
                style = if (subtle) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            )
        }
    }
}
