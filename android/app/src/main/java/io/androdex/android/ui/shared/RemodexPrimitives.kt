package io.androdex.android.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

internal enum class RemodexButtonStyle {
    Primary,
    Secondary,
    Ghost,
}

internal enum class RemodexPillStyle {
    Neutral,
    Accent,
    Success,
    Warning,
    Error,
}

internal enum class RemodexInputFieldVariant {
    Default,
    Thread,
}

internal data class RemodexInputChromeSpec(
    val minHeightDp: Int,
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val cornerRadiusDp: Int,
    val useAnimatedFocusChrome: Boolean,
    val useBodyMediumText: Boolean,
)

@Composable
internal fun remodexBottomSafeAreaInsets(): WindowInsets =
    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)

@Composable
internal fun Modifier.remodexBottomSafeAreaPadding(): Modifier =
    windowInsetsPadding(remodexBottomSafeAreaInsets())

internal fun remodexInputChromeSpec(
    variant: RemodexInputFieldVariant,
): RemodexInputChromeSpec = when (variant) {
    RemodexInputFieldVariant.Default -> RemodexInputChromeSpec(
        minHeightDp = 0,
        horizontalPaddingDp = 14,
        verticalPaddingDp = 12,
        cornerRadiusDp = 16,
        useAnimatedFocusChrome = false,
        useBodyMediumText = false,
    )

    RemodexInputFieldVariant.Thread -> RemodexInputChromeSpec(
        minHeightDp = 44,
        horizontalPaddingDp = 14,
        verticalPaddingDp = 12,
        cornerRadiusDp = 18,
        useAnimatedFocusChrome = true,
        useBodyMediumText = true,
    )
}

@Composable
internal fun RemodexGroupedSurface(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = RemodexTheme.geometry.cornerComposer,
    tonalColor: Color = RemodexTheme.colors.subtleGlassTint,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = tonalColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
        shadowElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun RemodexFloatingComposerShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    RemodexGroupedSurface(
        modifier = modifier,
        cornerRadius = RemodexTheme.geometry.cornerComposer,
        tonalColor = RemodexTheme.colors.subtleGlassTint,
        content = content,
    )
}

@Composable
internal fun RemodexPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    centerTitle: Boolean = false,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors

    Surface(
        color = colors.topBarBackground,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        if (centerTitle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = geometry.pageHorizontalPadding,
                        end = geometry.pageHorizontalPadding,
                        top = geometry.spacing4,
                        bottom = geometry.spacing8,
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (navigation != null) {
                        navigation()
                    } else {
                        Spacer(modifier = Modifier.size(geometry.iconButtonSize))
                    }
                    if (actions != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    } else {
                        Spacer(modifier = Modifier.size(geometry.iconButtonSize))
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = geometry.iconButtonSize + geometry.spacing12),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (subtitle == null) 0.dp else geometry.spacing2),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = geometry.pageHorizontalPadding,
                        end = geometry.pageHorizontalPadding,
                        top = geometry.spacing4,
                        bottom = geometry.spacing8,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            ) {
                if (navigation != null) {
                    navigation()
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (subtitle == null) 0.dp else geometry.spacing2),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemodexIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = CircleShape,
    contentDescription: String?,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .size(geometry.iconButtonSize)
            .remodexPressedState(
                interactionSource = interactionSource,
                enabled = enabled,
            ),
        shape = shape,
        color = when {
            !enabled -> colors.disabledFill
            selected -> colors.selectedRowFill
            else -> colors.secondarySurface.copy(alpha = 0.72f)
        },
        contentColor = when {
            !enabled -> colors.disabledForeground
            else -> colors.textPrimary
        },
        border = androidx.compose.foundation.BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
internal fun RemodexButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: RemodexButtonStyle = RemodexButtonStyle.Primary,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    when (style) {
        RemodexButtonStyle.Ghost -> {
            val interactionSource = remember { MutableInteractionSource() }
            TextButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier
                    .defaultMinSize(minHeight = geometry.buttonHeight)
                    .remodexPressedState(
                        interactionSource = interactionSource,
                        enabled = enabled,
                    ),
                interactionSource = interactionSource,
                contentPadding = contentPadding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.accentBlue,
                    disabledContentColor = colors.disabledForeground,
                ),
                content = content,
            )
        }

        else -> {
            val containerColor = when {
                !enabled -> colors.disabledFill
                style == RemodexButtonStyle.Primary -> colors.accentBlue
                else -> colors.secondarySurface
            }
            val contentColor = when {
                !enabled -> colors.disabledForeground
                style == RemodexButtonStyle.Primary -> colors.primaryButtonForeground
                else -> colors.textPrimary
            }
            val interactionSource = remember { MutableInteractionSource() }

            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier
                    .defaultMinSize(minHeight = geometry.buttonHeight)
                    .remodexPressedState(
                        interactionSource = interactionSource,
                        enabled = enabled,
                    ),
                shape = RoundedCornerShape(geometry.cornerLarge),
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                    disabledContainerColor = colors.disabledFill,
                    disabledContentColor = colors.disabledForeground,
                ),
                contentPadding = contentPadding,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun RemodexPill(
    label: String,
    modifier: Modifier = Modifier,
    style: RemodexPillStyle = RemodexPillStyle.Neutral,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val (fill, textColor) = when (style) {
        RemodexPillStyle.Neutral -> colors.selectedRowFill to colors.textSecondary
        RemodexPillStyle.Accent -> colors.accentBlue.copy(alpha = 0.14f) to colors.accentBlue
        RemodexPillStyle.Success -> colors.accentGreen.copy(alpha = 0.14f) to colors.accentGreen
        RemodexPillStyle.Warning -> colors.accentOrange.copy(alpha = 0.16f) to colors.accentOrange
        RemodexPillStyle.Error -> colors.errorRed.copy(alpha = 0.14f) to colors.errorRed
    }

    Surface(
        modifier = modifier.height(geometry.chipHeight),
        shape = RoundedCornerShape(999.dp),
        color = fill,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = geometry.spacing10),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
            )
        }
    }
}

@Composable
internal fun RemodexDivider(
    modifier: Modifier = Modifier,
    color: Color = RemodexTheme.colors.hairlineDivider,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

@Composable
internal fun RemodexSelectionRow(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues = PaddingValues(
        horizontal = RemodexTheme.geometry.sidebarRowHorizontalPadding,
        vertical = RemodexTheme.geometry.sidebarRowVerticalPadding,
    ),
    content: @Composable RowScope.() -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .remodexPressedState(
                interactionSource = interactionSource,
                enabled = onClick != null,
            )
            .clip(RoundedCornerShape(geometry.cornerSmall))
            .background(if (selected) colors.selectedRowFill else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(paddingValues),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
        content = content,
    )
}

@Composable
internal fun RemodexSearchField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val motion = RemodexTheme.motion
    var isFocused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            colors.accentBlue.copy(alpha = 0.18f)
        } else {
            colors.hairlineDivider
        },
        animationSpec = remodexTween(motion.searchMillis),
        label = "searchFieldBorder",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(geometry.cornerSmall),
        color = colors.searchBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(
                start = geometry.spacing10,
                end = geometry.searchFieldHorizontalPadding,
                top = geometry.searchFieldVerticalPadding,
                bottom = geometry.searchFieldVerticalPadding,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(geometry.spacing14),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inputPlaceholder,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            isFocused = it.isFocused
                            onFocusChange(it.isFocused)
                        },
                )
            }
            if (text.isNotEmpty()) {
                RemodexIconButton(
                    onClick = { onTextChange("") },
                    modifier = Modifier.size(geometry.iconSize),
                    contentDescription = "Clear search",
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(geometry.spacing10),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemodexInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    minLines: Int = 1,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    mono: Boolean = false,
    variant: RemodexInputFieldVariant = RemodexInputFieldVariant.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val spec = remodexInputChromeSpec(variant)
    var isFocused by remember { mutableStateOf(false) }
    val textStyle = if (spec.useBodyMediumText) {
        MaterialTheme.typography.bodyMedium
    } else {
        MaterialTheme.typography.bodySmall
    }
    val resolvedFontFamily = if (mono) {
        RemodexMonoFontFamily
    } else {
        textStyle.fontFamily
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
            )
        }
        RemodexInputChrome(
            variant = variant,
            enabled = enabled,
            focused = isFocused,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                minLines = if (singleLine) 1 else minLines,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = textStyle.copy(
                    color = if (enabled) colors.textPrimary else colors.disabledForeground,
                    fontFamily = resolvedFontFamily,
                ),
                cursorBrush = SolidColor(colors.accentBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = spec.minHeightDp.dp)
                            .padding(
                                horizontal = spec.horizontalPaddingDp.dp,
                                vertical = spec.verticalPaddingDp.dp,
                            ),
                    ) {
                        if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                            Text(
                                text = placeholder,
                                style = textStyle.copy(fontFamily = resolvedFontFamily),
                                color = colors.inputPlaceholder,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
internal fun RemodexInputChrome(
    variant: RemodexInputFieldVariant,
    enabled: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val motion = RemodexTheme.motion
    val spec = remodexInputChromeSpec(variant)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled && variant == RemodexInputFieldVariant.Thread -> colors.inputBackground.copy(alpha = 0.68f)
            focused && spec.useAnimatedFocusChrome -> colors.searchBackground.copy(alpha = 0.96f)
            else -> colors.inputBackground
        },
        animationSpec = remodexTween(motion.composerMillis),
        label = "inputChromeBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && spec.useAnimatedFocusChrome -> colors.accentBlue.copy(alpha = 0.22f)
            else -> colors.hairlineDivider
        },
        animationSpec = remodexTween(motion.composerMillis),
        label = "inputChromeBorder",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spec.cornerRadiusDp.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
internal fun RemodexSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
    ) {
        Box(
            modifier = Modifier
                .width(geometry.sheetHandleWidth)
                .height(geometry.sheetHandleHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.hairlineDivider),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun RemodexAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: (@Composable (() -> Unit))? = null,
    confirmButton: (@Composable RowScope.() -> Unit),
    dismissButton: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = geometry.spacing20),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(geometry.cornerComposer),
                color = colors.sheetBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = geometry.spacing20,
                        vertical = geometry.spacing18,
                    ),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing18),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
                    ) {
                        if (icon != null) {
                            icon()
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                        )
                        content()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissButton != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                confirmButton()
                                dismissButton()
                            }
                        } else {
                            Row(content = confirmButton)
                        }
                    }
                }
            }
        }
    }
}
