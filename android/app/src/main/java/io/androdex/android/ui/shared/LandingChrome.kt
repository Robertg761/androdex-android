package io.androdex.android.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.theme.RemodexTheme

@Composable
internal fun LandingBackdrop(modifier: Modifier = Modifier) {
    val colors = RemodexTheme.colors
    val background = colors.appBackground
    val primaryGlow = colors.accentBlue.copy(alpha = 0.10f)
    val secondaryGlow = colors.accentGreen.copy(alpha = 0.06f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            background,
                            background.copy(alpha = 0.98f),
                            colors.groupedBackground.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .offset(x = (-84).dp, y = (-54).dp)
                .size(220.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(primaryGlow, Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .offset(x = 240.dp, y = 160.dp)
                .size(240.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(secondaryGlow, Color.Transparent),
                    ),
                ),
        )
    }
}

@Composable
internal fun LandingSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = RemodexTheme.geometry.cornerComposer,
        content = content,
    )
}
