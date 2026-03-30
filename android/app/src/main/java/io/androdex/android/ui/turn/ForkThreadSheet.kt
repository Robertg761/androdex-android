package io.androdex.android.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.state.ThreadForkTargetUiState
import io.androdex.android.ui.state.ThreadForkUiState
import io.androdex.android.ui.theme.RemodexTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForkThreadSheet(
    state: ThreadForkUiState,
    onDismiss: () -> Unit,
    onFork: (String?) -> Unit,
) {
    val geometry = RemodexTheme.geometry

    RemodexModalSheet(onDismissRequest = onDismiss) {
        RemodexSheetHeaderBlock(
            title = "Fork Thread",
            subtitle = "Start a new thread from the current conversation and optionally rebind it to a different host project.",
        )

        state.availabilityMessage?.takeIf { it.isNotBlank() }?.let { message ->
            RemodexSheetCard(
                tint = RemodexTheme.colors.selectedRowFill.copy(alpha = 0.84f),
            ) {
                RemodexSheetSectionLabel("Availability")
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }
        }

        RemodexSheetCard {
            RemodexSheetSectionLabel("Fork destination")
            state.targets.forEachIndexed { index, target ->
                ForkTargetCard(
                    target = target,
                    enabled = state.isEnabled,
                    onClick = { onFork(target.projectPath) },
                )
                if (index != state.targets.lastIndex) {
                    Spacer(modifier = Modifier.height(geometry.spacing2))
                }
            }
        }
    }
}

@Composable
private fun ForkTargetCard(
    target: ThreadForkTargetUiState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    Surface(
        color = if (enabled) colors.groupedBackground.copy(alpha = 0.58f) else colors.disabledFill,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(geometry.cornerLarge),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        enabled = enabled,
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) colors.hairlineDivider else colors.disabledForeground.copy(alpha = 0.2f),
        ),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing12),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
            ) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) colors.textPrimary else colors.disabledForeground,
                    fontWeight = FontWeight.SemiBold,
                )
                target.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) colors.textSecondary else colors.disabledForeground,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            RemodexPill(
                label = if (enabled) forkTargetActionLabel(target.projectPath) else "Unavailable",
                style = if (enabled) RemodexPillStyle.Accent else RemodexPillStyle.Neutral,
            )
        }
    }
}

internal fun forkTargetActionLabel(projectPath: String?): String {
    return if (projectPath == null) "Fork here" else "Fork"
}
