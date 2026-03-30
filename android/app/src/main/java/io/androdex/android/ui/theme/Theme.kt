package io.androdex.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class RemodexColors(
    val appBackground: Color,
    val groupedBackground: Color,
    val raisedSurface: Color,
    val secondarySurface: Color,
    val selectedRowFill: Color,
    val separator: Color,
    val hairlineDivider: Color,
    val subtleGlassTint: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val accentOrange: Color,
    val errorRed: Color,
    val disabledForeground: Color,
    val disabledFill: Color,
    val inputBackground: Color,
    val inputPlaceholder: Color,
    val searchBackground: Color,
    val topBarBackground: Color,
    val sheetBackground: Color,
    val overlayDimmer: Color,
    val statusDotConnected: Color,
    val statusDotSyncing: Color,
    val statusDotOffline: Color,
    val statusDotError: Color,
    val primaryButtonForeground: Color,
)

@Immutable
data class RemodexGeometry(
    val spacing2: Dp,
    val spacing4: Dp,
    val spacing6: Dp,
    val spacing8: Dp,
    val spacing10: Dp,
    val spacing12: Dp,
    val spacing14: Dp,
    val spacing16: Dp,
    val spacing18: Dp,
    val spacing20: Dp,
    val spacing24: Dp,
    val spacing32: Dp,
    val cornerTiny: Dp,
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val cornerXLarge: Dp,
    val cornerComposer: Dp,
    val pageHorizontalPadding: Dp,
    val pageVerticalPadding: Dp,
    val sectionPadding: Dp,
    val sidebarOuterHorizontalPadding: Dp,
    val sidebarRowHorizontalPadding: Dp,
    val sidebarRowVerticalPadding: Dp,
    val sidebarSubRowVerticalPadding: Dp,
    val searchFieldHorizontalPadding: Dp,
    val searchFieldVerticalPadding: Dp,
    val buttonHeight: Dp,
    val rowHeight: Dp,
    val iconButtonSize: Dp,
    val iconSize: Dp,
    val chipHeight: Dp,
    val sheetHandleWidth: Dp,
    val sheetHandleHeight: Dp,
    val maxContentWidth: Dp,
)

@Immutable
data class RemodexMotion(
    val shellMillis: Int,
    val searchMillis: Int,
    val composerMillis: Int,
    val microStateMillis: Int,
    val pulseMillis: Int,
)

private val LightRemodexColors = RemodexColors(
    appBackground = Color(0xFFFDFDFE),
    groupedBackground = Color(0xFFF3F3F6),
    raisedSurface = Color(0xFFFDFDFE),
    secondarySurface = Color(0xFFF5F5F8),
    selectedRowFill = Color(0xFFE9EBF0),
    separator = Color(0x140E1622),
    hairlineDivider = Color(0x0F0E1622),
    subtleGlassTint = Color(0xEEFFFFFF),
    textPrimary = Color(0xFF13161B),
    textSecondary = Color(0xFF616977),
    textTertiary = Color(0xFF8B93A0),
    accentBlue = Color(0xFF0A84FF),
    accentGreen = Color(0xFF30D158),
    accentOrange = Color(0xFFFF9F0A),
    errorRed = Color(0xFFFF453A),
    disabledForeground = Color(0xFF9AA1AD),
    disabledFill = Color(0xFFE7E9EE),
    inputBackground = Color(0xFFF7F8FA),
    inputPlaceholder = Color(0xFF8C93A0),
    searchBackground = Color(0xFFEFF1F5),
    topBarBackground = Color(0xD9FFFFFF),
    sheetBackground = Color(0xF7FBFBFD),
    overlayDimmer = Color(0x52070A10),
    statusDotConnected = Color(0xFF30D158),
    statusDotSyncing = Color(0xFFFF9F0A),
    statusDotOffline = Color(0x668B93A0),
    statusDotError = Color(0xFFFF453A),
    primaryButtonForeground = Color.White,
)

private val DarkRemodexColors = RemodexColors(
    appBackground = Color(0xFF0A0C10),
    groupedBackground = Color(0xFF12151B),
    raisedSurface = Color(0xFF171B22),
    secondarySurface = Color(0xFF20252E),
    selectedRowFill = Color(0xFF262C37),
    separator = Color(0x1FFFFFFF),
    hairlineDivider = Color(0x14FFFFFF),
    subtleGlassTint = Color(0xE61C2028),
    textPrimary = Color(0xFFF5F7FB),
    textSecondary = Color(0xFFC3CAD6),
    textTertiary = Color(0xFF8E97A6),
    accentBlue = Color(0xFF5AB2FF),
    accentGreen = Color(0xFF32D867),
    accentOrange = Color(0xFFFFB54A),
    errorRed = Color(0xFFFF6B61),
    disabledForeground = Color(0xFF687181),
    disabledFill = Color(0xFF232830),
    inputBackground = Color(0xFF181D24),
    inputPlaceholder = Color(0xFF6F7887),
    searchBackground = Color(0xFF1F252E),
    topBarBackground = Color(0xD90A0C10),
    sheetBackground = Color(0xF71A1E26),
    overlayDimmer = Color(0xB2000000),
    statusDotConnected = Color(0xFF32D867),
    statusDotSyncing = Color(0xFFFFB54A),
    statusDotOffline = Color(0x668E97A6),
    statusDotError = Color(0xFFFF6B61),
    primaryButtonForeground = Color(0xFF06111F),
)

private val DefaultRemodexGeometry = RemodexGeometry(
    spacing2 = 2.dp,
    spacing4 = 4.dp,
    spacing6 = 6.dp,
    spacing8 = 8.dp,
    spacing10 = 10.dp,
    spacing12 = 12.dp,
    spacing14 = 14.dp,
    spacing16 = 16.dp,
    spacing18 = 18.dp,
    spacing20 = 20.dp,
    spacing24 = 24.dp,
    spacing32 = 32.dp,
    cornerTiny = 8.dp,
    cornerSmall = 14.dp,
    cornerMedium = 16.dp,
    cornerLarge = 18.dp,
    cornerXLarge = 22.dp,
    cornerComposer = 28.dp,
    pageHorizontalPadding = 20.dp,
    pageVerticalPadding = 20.dp,
    sectionPadding = 18.dp,
    sidebarOuterHorizontalPadding = 16.dp,
    sidebarRowHorizontalPadding = 12.dp,
    sidebarRowVerticalPadding = 12.dp,
    sidebarSubRowVerticalPadding = 4.dp,
    searchFieldHorizontalPadding = 16.dp,
    searchFieldVerticalPadding = 8.dp,
    buttonHeight = 48.dp,
    rowHeight = 44.dp,
    iconButtonSize = 36.dp,
    iconSize = 18.dp,
    chipHeight = 24.dp,
    sheetHandleWidth = 36.dp,
    sheetHandleHeight = 4.dp,
    maxContentWidth = 280.dp,
)

private val DefaultRemodexMotion = RemodexMotion(
    shellMillis = 220,
    searchMillis = 200,
    composerMillis = 180,
    microStateMillis = 200,
    pulseMillis = 800,
)

private val LocalRemodexColors: ProvidableCompositionLocal<RemodexColors> =
    staticCompositionLocalOf { LightRemodexColors }
private val LocalRemodexGeometry: ProvidableCompositionLocal<RemodexGeometry> =
    staticCompositionLocalOf { DefaultRemodexGeometry }
private val LocalRemodexMotion: ProvidableCompositionLocal<RemodexMotion> =
    staticCompositionLocalOf { DefaultRemodexMotion }

object RemodexTheme {
    val colors: RemodexColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRemodexColors.current

    val geometry: RemodexGeometry
        @Composable
        @ReadOnlyComposable
        get() = LocalRemodexGeometry.current

    val motion: RemodexMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalRemodexMotion.current
}

private fun lightMaterialScheme(tokens: RemodexColors): ColorScheme = lightColorScheme(
    primary = tokens.accentBlue,
    onPrimary = tokens.primaryButtonForeground,
    primaryContainer = Color(0xFFDCEEFF),
    onPrimaryContainer = tokens.accentBlue,
    secondary = tokens.textSecondary,
    onSecondary = tokens.textPrimary,
    secondaryContainer = tokens.secondarySurface,
    onSecondaryContainer = tokens.textPrimary,
    tertiary = tokens.accentGreen,
    onTertiary = Color(0xFF03170A),
    tertiaryContainer = Color(0xFFD8F6E1),
    onTertiaryContainer = Color(0xFF12321B),
    background = tokens.appBackground,
    onBackground = tokens.textPrimary,
    surface = tokens.raisedSurface,
    onSurface = tokens.textPrimary,
    surfaceVariant = tokens.searchBackground,
    onSurfaceVariant = tokens.textSecondary,
    surfaceContainer = tokens.groupedBackground,
    surfaceContainerHigh = tokens.secondarySurface,
    surfaceContainerHighest = tokens.selectedRowFill,
    inverseSurface = Color(0xFF1A1D24),
    inverseOnSurface = Color(0xFFF8FAFD),
    outline = tokens.separator,
    outlineVariant = tokens.hairlineDivider,
    error = tokens.errorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE2DE),
    onErrorContainer = tokens.errorRed,
    scrim = tokens.overlayDimmer,
)

private fun darkMaterialScheme(tokens: RemodexColors): ColorScheme = darkColorScheme(
    primary = tokens.accentBlue,
    onPrimary = tokens.primaryButtonForeground,
    primaryContainer = Color(0xFF133049),
    onPrimaryContainer = Color(0xFFB9E1FF),
    secondary = tokens.textSecondary,
    onSecondary = tokens.textPrimary,
    secondaryContainer = tokens.secondarySurface,
    onSecondaryContainer = tokens.textPrimary,
    tertiary = tokens.accentGreen,
    onTertiary = Color(0xFF021107),
    tertiaryContainer = Color(0xFF133320),
    onTertiaryContainer = Color(0xFFC1F2CF),
    background = tokens.appBackground,
    onBackground = tokens.textPrimary,
    surface = tokens.raisedSurface,
    onSurface = tokens.textPrimary,
    surfaceVariant = tokens.searchBackground,
    onSurfaceVariant = tokens.textSecondary,
    surfaceContainer = tokens.groupedBackground,
    surfaceContainerHigh = tokens.secondarySurface,
    surfaceContainerHighest = tokens.selectedRowFill,
    inverseSurface = Color(0xFFF4F7FA),
    inverseOnSurface = Color(0xFF11151C),
    outline = tokens.separator,
    outlineVariant = tokens.hairlineDivider,
    error = tokens.errorRed,
    onError = Color(0xFF250202),
    errorContainer = Color(0xFF49201F),
    onErrorContainer = Color(0xFFFFD7D3),
    scrim = tokens.overlayDimmer,
)

private fun remodexShapes(geometry: RemodexGeometry) = Shapes(
    extraSmall = RoundedCornerShape(geometry.cornerTiny),
    small = RoundedCornerShape(geometry.cornerSmall),
    medium = RoundedCornerShape(geometry.cornerMedium),
    large = RoundedCornerShape(geometry.cornerLarge),
    extraLarge = RoundedCornerShape(geometry.cornerComposer),
)

@Composable
fun AndrodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkRemodexColors else LightRemodexColors
    val materialColors = if (darkTheme) darkMaterialScheme(colors) else lightMaterialScheme(colors)
    val geometry = DefaultRemodexGeometry
    val motion = DefaultRemodexMotion

    androidx.compose.runtime.CompositionLocalProvider(
        LocalRemodexColors provides colors,
        LocalRemodexGeometry provides geometry,
        LocalRemodexMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = AndrodexTypography,
            shapes = remodexShapes(geometry),
            content = content,
        )
    }
}
