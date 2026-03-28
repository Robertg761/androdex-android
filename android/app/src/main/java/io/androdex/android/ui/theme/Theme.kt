package io.androdex.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// iOS-inspired neutral palette matching remodex
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),              // iOS blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EAFF),
    onPrimaryContainer = Color(0xFF001C3D),
    secondary = Color(0xFF636366),            // iOS gray
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E5EA),   // iOS tertiary fill
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF34C759),             // iOS green
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4F0DC),
    onTertiaryContainer = Color(0xFF002111),
    background = Color(0xFFF2F2F7),           // iOS system grouped background
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),     // iOS gray
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFD8D8DC),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    outline = Color(0xFFC6C6C8),              // iOS separator
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),                // iOS red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),              // iOS blue dark
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF003E8F),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = Color(0xFF8E8E93),            // iOS gray dark
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2C2C2E),   // iOS tertiary background dark
    onSecondaryContainer = Color(0xFFEBEBF5),
    tertiary = Color(0xFF30D158),             // iOS green dark
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF004417),
    onTertiaryContainer = Color(0xFFB4F1C6),
    background = Color(0xFF000000),           // iOS pure dark background
    onBackground = Color(0xFFEBEBF5),
    surface = Color(0xFF1C1C1E),              // iOS secondary background dark
    onSurface = Color(0xFFEBEBF5),
    surfaceVariant = Color(0xFF2C2C2E),       // iOS tertiary background dark
    onSurfaceVariant = Color(0xFF8E8E93),     // iOS gray dark
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    inverseSurface = Color(0xFFEBEBF5),
    inverseOnSurface = Color(0xFF1C1C1E),
    outline = Color(0xFF38383A),              // iOS separator dark
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFFF453A),                // iOS red dark
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AndrodexShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AndrodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AndrodexTypography,
        shapes = AndrodexShapes,
        content = content,
    )
}
