package io.androdex.android.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexSelectableOptionRow
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.state.RuntimeSettingsOptionUiState
import io.androdex.android.ui.state.ThreadRuntimeUiState
import io.androdex.android.ui.theme.RemodexTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadRuntimeSheet(
    state: ThreadRuntimeUiState,
    onDismiss: () -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (String?) -> Unit,
    onUseDefaults: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    RemodexModalSheet(onDismissRequest = onDismiss) {
        RemodexSheetHeaderBlock(
            title = "Thread Runtime",
            subtitle = "Adjust reasoning and speed for this thread without changing the app-wide defaults.",
            trailing = {
                RemodexButton(
                    onClick = onUseDefaults,
                    enabled = state.hasOverrides,
                    style = RemodexButtonStyle.Ghost,
                ) {
                    Text("Reset")
                }
            },
        )

        if (state.hasOverrides) {
            RemodexSheetCard(
                tint = RemodexTheme.colors.selectedRowFill.copy(alpha = 0.86f),
            ) {
                RemodexSheetSectionLabel("Overrides")
                Text(
                    text = "This thread is using custom runtime settings. Reset to return to the app defaults.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }
        }

        RemodexSheetCard {
            RuntimeSectionHeader("Reasoning")
            state.reasoningOptions.forEach { option ->
                ThreadRuntimeOptionRow(
                    state = option,
                    onClick = { onSelectReasoning(option.value) },
                )
            }
            RemodexDivider()
            RuntimeSectionHeader("Speed")
            if (state.supportsServiceTier) {
                state.serviceTierOptions.forEach { option ->
                    ThreadRuntimeOptionRow(
                        state = option,
                        onClick = { onSelectServiceTier(option.value) },
                    )
                }
            } else {
                Text(
                    text = "This host bridge is using the default runtime tier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }
        }

        RemodexSheetCard {
            RuntimeSectionHeader("Collaboration")
            Text(
                text = if (state.supportsPlanMode) "Plan mode available" else "Plan mode unavailable",
                style = MaterialTheme.typography.titleSmall,
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = state.collaborationSummary,
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(geometry.spacing6))
            RemodexDivider()
            RuntimeSectionHeader("Access")
            Text(
                text = state.accessModeLabel,
                style = MaterialTheme.typography.titleSmall,
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = "Access mode is set at the app level and applies whenever the paired host runtime supports it.",
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RuntimeSectionHeader(title: String) {
    RemodexSheetSectionLabel(title)
}

@Composable
private fun ThreadRuntimeOptionRow(
    state: RuntimeSettingsOptionUiState,
    onClick: () -> Unit,
) {
    RemodexSelectableOptionRow(
        title = state.title,
        subtitle = state.subtitle,
        selected = state.selected,
        onClick = onClick,
    )
}
