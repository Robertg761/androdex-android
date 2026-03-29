package io.androdex.android.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun LandingBackdrop(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val secondaryGlow = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)

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
                            background.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .offset(x = (-72).dp, y = (-24).dp)
                .size(240.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(primaryGlow, Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .offset(x = 220.dp, y = 120.dp)
                .size(280.dp)
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(content = content)
    }
}
