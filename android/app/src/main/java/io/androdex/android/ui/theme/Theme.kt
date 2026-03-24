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

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F3D33),
    onPrimary = Color(0xFFF4F7F2),
    primaryContainer = Color(0xFFD1EDE4),
    onPrimaryContainer = Color(0xFF0A2E26),
    secondary = Color(0xFFD17A22),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF3E2106),
    tertiary = Color(0xFF4A6572),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDE5F0),
    onTertiaryContainer = Color(0xFF1B2D36),
    background = Color(0xFFF7F4ED),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFE8E1D3),
    onSurfaceVariant = Color(0xFF4B453C),
    surfaceContainer = Color(0xFFF0ECE3),
    surfaceContainerHigh = Color(0xFFEAE6DD),
    surfaceContainerHighest = Color(0xFFE4E0D7),
    inverseSurface = Color(0xFF323028),
    inverseOnSurface = Color(0xFFF5F1E9),
    outline = Color(0xFF7D766C),
    outlineVariant = Color(0xFFCEC8BC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8CCFBF),
    onPrimary = Color(0xFF04221C),
    primaryContainer = Color(0xFF1A4D42),
    onPrimaryContainer = Color(0xFFA8E8D8),
    secondary = Color(0xFFF2B06A),
    onSecondary = Color(0xFF2B1A06),
    secondaryContainer = Color(0xFF5C3A12),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = Color(0xFFA0CFE0),
    onTertiary = Color(0xFF0D2430),
    tertiaryContainer = Color(0xFF2B4856),
    onTertiaryContainer = Color(0xFFBEE4F3),
    background = Color(0xFF0F1411),
    onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF151D18),
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF293530),
    onSurfaceVariant = Color(0xFFC3BEB3),
    surfaceContainer = Color(0xFF1C251F),
    surfaceContainerHigh = Color(0xFF232D27),
    surfaceContainerHighest = Color(0xFF2A3530),
    inverseSurface = Color(0xFFE6E2DA),
    inverseOnSurface = Color(0xFF1C1B17),
    outline = Color(0xFF8B958D),
    outlineVariant = Color(0xFF3F4B44),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AndrodexShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
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
