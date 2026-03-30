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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
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

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(geometry.iconButtonSize),
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
            TextButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.defaultMinSize(minHeight = geometry.buttonHeight),
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

            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.defaultMinSize(minHeight = geometry.buttonHeight),
                shape = RoundedCornerShape(geometry.cornerLarge),
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

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(geometry.cornerSmall),
        color = colors.searchBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
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
                        .onFocusChanged { onFocusChange(it.isFocused) },
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
    mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
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
        Surface(
            shape = RoundedCornerShape(geometry.cornerMedium),
            color = colors.inputBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = geometry.spacing14, vertical = geometry.spacing12),
            ) {
                if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (mono) RemodexMonoFontFamily else MaterialTheme.typography.bodySmall.fontFamily,
                        ),
                        color = colors.inputPlaceholder,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    minLines = minLines,
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textPrimary,
                        fontFamily = if (mono) RemodexMonoFontFamily else MaterialTheme.typography.bodySmall.fontFamily,
                    ),
                    cursorBrush = SolidColor(colors.accentBlue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
                                dismissButton()
                                confirmButton()
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
