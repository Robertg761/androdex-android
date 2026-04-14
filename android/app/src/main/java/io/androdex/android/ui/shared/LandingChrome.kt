package io.androdex.android.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.theme.RemodexTheme

@Composable
internal fun LandingBackdrop(modifier: Modifier = Modifier) {
    val colors = RemodexTheme.colors
    val background = colors.appBackground

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(x = 110.dp, y = (-56).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.accentBlue.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.TopStart)
                .offset(x = (-72).dp, y = 140.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.accentGreen.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.groupedBackground.copy(alpha = 0.44f),
                            background,
                            background,
                            colors.groupedBackground.copy(alpha = 0.24f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.topBarBackground.copy(alpha = 0.82f),
                            background.copy(alpha = 0.48f),
                            background.copy(alpha = 0f),
                        ),
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
