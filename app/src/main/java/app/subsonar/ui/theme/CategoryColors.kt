package app.subsonar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.subsonar.data.Category
import app.subsonar.domain.SubscriptionVisuals

/** Base colour of each avatar palette key (dark enough for 3:1 contrast on its own light tint). */
private val BASE = mapOf(
    "fjord" to Color(0xFF1E5568),
    "lake" to Color(0xFF2C6A9E),
    "aurora" to Color(0xFF1C7465),
    "pine" to Color(0xFF2E5A43),
    "moss" to Color(0xFF52692C),
    "dusk" to Color(0xFF5257A0),
    "heather" to Color(0xFF7A4C8F),
    "lingonberry" to Color(0xFFA3263F),
    "rust" to Color(0xFFA2452A),
    "cloudberry" to Color(0xFF9A5600),
    "birch" to Color(0xFF6F6150),
    "slate" to Color(0xFF4F616B),
)

/** Foreground (icon) and container colour of an avatar in the current theme. */
data class AvatarColors(val content: Color, val container: Color)

fun paletteBase(key: String): Color = BASE[key] ?: BASE.getValue("slate")

@Composable
fun avatarColors(key: String): AvatarColors {
    val base = paletteBase(key)
    val surface = MaterialTheme.colorScheme.surface
    return if (surface.luminance() < 0.5f) {
        AvatarColors(content = lerp(base, Color.White, 0.55f), container = lerp(surface, base, 0.42f))
    } else {
        AvatarColors(content = base, container = lerp(Color.White, base, 0.16f))
    }
}

/** Solid colour of a palette key for bars and swatches (lighter in the dark theme). */
@Composable
fun paletteColor(key: String): Color {
    val base = paletteBase(key)
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) lerp(base, Color.White, 0.4f) else base
}

/** Category colour (Insights bars) = the default avatar colour of that category, so list and charts agree. */
@Composable
fun categoryColor(category: Category): Color = paletteColor(SubscriptionVisuals.defaultColor(category))

internal val PALETTE_KEYS: Set<String> get() = BASE.keys
