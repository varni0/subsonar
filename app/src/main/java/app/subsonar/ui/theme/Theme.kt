package app.subsonar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.subsonar.data.ThemeMode

/**
 * Brand palette ("Nordic calm"): deep fjord blue, snow white, soft slate and a warm cloudberry accent.
 * Deliberately not the Material default teal/purple and not the blue/green of competing trackers.
 * Shared with the store-asset generator (tools/store-assets/generate.py): keep both in sync.
 */
object Brand {
    val Fjord = Color(0xFF1E5568)
    val FjordDeep = Color(0xFF143E4D)
    val FjordLight = Color(0xFF8FCFE6)
    val Snow = Color(0xFFF7F9FB)
    val Slate = Color(0xFF4F616B)
    val Cloudberry = Color(0xFFF2A541)
    val CloudberryDeep = Color(0xFF8C5000)
    val PolarNight = Color(0xFF0F1417)
}

private val LightColors = lightColorScheme(
    primary = Brand.Fjord,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6E7F2),
    onPrimaryContainer = Color(0xFF001F29),
    inversePrimary = Brand.FjordLight,
    secondary = Brand.Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4EC),
    onSecondaryContainer = Color(0xFF0B1D25),
    tertiary = Brand.CloudberryDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Brand.Snow,
    onBackground = Color(0xFF171C1F),
    surface = Brand.Snow,
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDCE3E8),
    onSurfaceVariant = Color(0xFF40484D),
    surfaceTint = Brand.Fjord,
    inverseSurface = Color(0xFF2C3134),
    inverseOnSurface = Color(0xFFEEF1F3),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CD),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F6),
    surfaceContainer = Color(0xFFEBEFF2),
    surfaceContainerHigh = Color(0xFFE5E9EC),
    surfaceContainerHighest = Color(0xFFDFE3E6),
    surfaceBright = Brand.Snow,
    surfaceDim = Color(0xFFD7DBDE),
)

private val DarkColors = darkColorScheme(
    primary = Brand.FjordLight,
    onPrimary = Color(0xFF003545),
    primaryContainer = Color(0xFF004D62),
    onPrimaryContainer = Color(0xFFC2E8F6),
    inversePrimary = Brand.Fjord,
    secondary = Color(0xFFB7C9D3),
    onSecondary = Color(0xFF21323A),
    secondaryContainer = Color(0xFF384951),
    onSecondaryContainer = Color(0xFFD3E4EE),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF4B2800),
    tertiaryContainer = Color(0xFF6B3B00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Brand.PolarNight,
    onBackground = Color(0xFFDFE3E6),
    surface = Brand.PolarNight,
    onSurface = Color(0xFFDFE3E6),
    surfaceVariant = Color(0xFF40484D),
    onSurfaceVariant = Color(0xFFC0C8CD),
    surfaceTint = Brand.FjordLight,
    inverseSurface = Color(0xFFDFE3E6),
    inverseOnSurface = Color(0xFF2C3134),
    outline = Color(0xFF8A9297),
    outlineVariant = Color(0xFF40484D),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF252B2E),
    surfaceContainerHighest = Color(0xFF303538),
    surfaceBright = Color(0xFF353A3D),
    surfaceDim = Brand.PolarNight,
)

/** System font, but calmer and more "printed statement" than stock M3: heavier numbers/headlines, tighter titles. */
private val BrandTypography: Typography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.em),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
    )
}

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SubsonarTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colours. Off by default (see AppSettings) so the app looks like itself. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = BrandTypography, shapes = BrandShapes, content = content)
}
