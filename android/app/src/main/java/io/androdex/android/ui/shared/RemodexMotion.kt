package io.androdex.android.ui.shared

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max

internal val RemodexEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

internal fun <T> remodexTween(
    durationMillis: Int,
    delayMillis: Int = 0,
): TweenSpec<T> = tween(
    durationMillis = durationMillis,
    delayMillis = delayMillis,
    easing = RemodexEaseInOut,
)

internal fun remodexFadeIn(
    durationMillis: Int,
    delayMillis: Int = 0,
    initialAlpha: Float = 0.72f,
): EnterTransition = fadeIn(
    animationSpec = remodexTween(durationMillis, delayMillis),
    initialAlpha = initialAlpha,
)

internal fun remodexFadeOut(
    durationMillis: Int,
    delayMillis: Int = 0,
    targetAlpha: Float = 0.72f,
): ExitTransition = fadeOut(
    animationSpec = remodexTween(durationMillis, delayMillis),
    targetAlpha = targetAlpha,
)

internal fun remodexExpandVertically(
    durationMillis: Int,
    expandFrom: Alignment.Vertical = Alignment.Top,
): EnterTransition = expandVertically(
    animationSpec = remodexTween(durationMillis),
    expandFrom = expandFrom,
)

internal fun remodexShrinkVertically(
    durationMillis: Int,
    shrinkTowards: Alignment.Vertical = Alignment.Top,
): ExitTransition = shrinkVertically(
    animationSpec = remodexTween(durationMillis),
    shrinkTowards = shrinkTowards,
)

internal fun remodexExpandHorizontally(
    durationMillis: Int,
    expandFrom: Alignment.Horizontal = Alignment.End,
): EnterTransition = expandHorizontally(
    animationSpec = remodexTween(durationMillis),
    expandFrom = expandFrom,
)

internal fun remodexShrinkHorizontally(
    durationMillis: Int,
    shrinkTowards: Alignment.Horizontal = Alignment.End,
): ExitTransition = shrinkHorizontally(
    animationSpec = remodexTween(durationMillis),
    shrinkTowards = shrinkTowards,
)

internal fun remodexSlideInVertically(
    durationMillis: Int,
    initialOffsetY: (Int) -> Int,
): EnterTransition = slideInVertically(
    animationSpec = remodexTween(durationMillis),
    initialOffsetY = initialOffsetY,
)

internal fun remodexSlideOutVertically(
    durationMillis: Int,
    targetOffsetY: (Int) -> Int,
): ExitTransition = slideOutVertically(
    animationSpec = remodexTween(durationMillis),
    targetOffsetY = targetOffsetY,
)

internal fun remodexSlideInHorizontally(
    durationMillis: Int,
    initialOffsetX: (Int) -> Int,
): EnterTransition = slideInHorizontally(
    animationSpec = remodexTween(durationMillis),
    initialOffsetX = initialOffsetX,
)

internal fun remodexSlideOutHorizontally(
    durationMillis: Int,
    targetOffsetX: (Int) -> Int,
): ExitTransition = slideOutHorizontally(
    animationSpec = remodexTween(durationMillis),
    targetOffsetX = targetOffsetX,
)

internal fun Modifier.remodexPressedState(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedAlpha: Float = 0.74f,
    pressedScale: Float = 0.985f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val durationMillis = max(110, io.androdex.android.ui.theme.RemodexTheme.motion.microStateMillis / 2)
    val alpha by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedAlpha else 1f,
        animationSpec = remodexTween(durationMillis),
        label = "remodexPressedAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = remodexTween(durationMillis),
        label = "remodexPressedScale",
    )

    graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
    }
}
