package io.relaydex.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF123D35),
    onPrimary = Color(0xFFF4F7F2),
    secondary = Color(0xFFDB7C26),
    onSecondary = Color(0xFF20160A),
    background = Color(0xFFF6F2EA),
    onBackground = Color(0xFF1F1C17),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF1F1C17),
    surfaceVariant = Color(0xFFE3DCCE),
    onSurfaceVariant = Color(0xFF4F473D),
    outline = Color(0xFF7C7469),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8DD0C2),
    onPrimary = Color(0xFF04221C),
    secondary = Color(0xFFF3B371),
    onSecondary = Color(0xFF2B1A06),
    background = Color(0xFF111612),
    onBackground = Color(0xFFE8E4DC),
    surface = Color(0xFF18201B),
    onSurface = Color(0xFFE8E4DC),
    surfaceVariant = Color(0xFF2C3731),
    onSurfaceVariant = Color(0xFFC5C0B5),
    outline = Color(0xFF8E978E),
)

@Composable
fun RelaydexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
