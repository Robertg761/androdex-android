package io.androdex.android.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.theme.RemodexTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemodexModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = RemodexTheme.geometry.spacing16,
    contentPadding: PaddingValues = PaddingValues(
        start = RemodexTheme.geometry.pageHorizontalPadding,
        top = 0.dp,
        end = RemodexTheme.geometry.pageHorizontalPadding,
        bottom = RemodexTheme.geometry.spacing32,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = geometry.cornerComposer, topEnd = geometry.cornerComposer),
        containerColor = colors.sheetBackground,
        scrimColor = colors.overlayDimmer,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.height(geometry.spacing10))
                Box(
                    modifier = Modifier
                        .width(geometry.sheetHandleWidth)
                        .height(geometry.sheetHandleHeight)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.hairlineDivider),
                )
                Spacer(modifier = Modifier.height(geometry.spacing12))
            }
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}

@Composable
internal fun RemodexSheetHeaderBlock(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        if (trailing != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}

@Composable
internal fun RemodexSheetSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = RemodexTheme.colors.accentBlue,
        fontWeight = FontWeight.Bold,
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
    )
}

@Composable
internal fun RemodexSheetCard(
    modifier: Modifier = Modifier,
    tint: Color = RemodexTheme.colors.secondarySurface.copy(alpha = 0.78f),
    contentPadding: PaddingValues = PaddingValues(
        horizontal = RemodexTheme.geometry.sectionPadding,
        vertical = RemodexTheme.geometry.spacing14,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tint,
        shape = RoundedCornerShape(RemodexTheme.geometry.cornerLarge),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing10),
            content = content,
        )
    }
}

@Composable
internal fun RemodexSelectableOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(geometry.cornerLarge),
        color = if (selected) colors.selectedRowFill else colors.groupedBackground.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) colors.accentBlue.copy(alpha = 0.22f) else colors.hairlineDivider,
        ),
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing12),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemodexOptionIndicator(selected = selected, enabled = enabled)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) colors.textPrimary else colors.disabledForeground,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) colors.textSecondary else colors.disabledForeground,
                    )
                }
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing6),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}

@Composable
internal fun RemodexInsetActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing10),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RemodexInlineLinkRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingLabel: String? = null,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(geometry.cornerLarge))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = geometry.spacing6, vertical = geometry.spacing4),
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.textPrimary else colors.disabledForeground,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.textSecondary else colors.disabledForeground,
                )
            }
        }
        trailingLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.accentBlue else colors.disabledForeground,
            )
        }
    }
}

@Composable
private fun RemodexOptionIndicator(
    selected: Boolean,
    enabled: Boolean,
) {
    val colors = RemodexTheme.colors
    val outerColor = when {
        !enabled -> colors.disabledForeground.copy(alpha = 0.4f)
        selected -> colors.accentBlue
        else -> colors.textTertiary
    }

    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, outerColor),
            modifier = Modifier.size(18.dp),
        ) {}
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(outerColor),
            )
        }
    }
}
