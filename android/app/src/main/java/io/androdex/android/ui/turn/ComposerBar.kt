package io.androdex.android.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.state.ComposerUiState
import io.androdex.android.ui.state.ComposerSubmitMode

@Composable
internal fun ComposerBar(
    state: ComposerUiState,
    onTextChange: (String) -> Unit,
    onPlanModeChanged: (Boolean) -> Unit,
    onSubagentsModeChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                FilterChip(
                    selected = state.isPlanModeEnabled,
                    onClick = { onPlanModeChanged(!state.isPlanModeEnabled) },
                    enabled = state.planModeEnabled,
                    label = {
                        Text(if (state.isPlanModeEnabled) "Plan mode on" else "Plan mode")
                    },
                )
                FilterChip(
                    selected = state.isSubagentsEnabled,
                    onClick = { onSubagentsModeChanged(!state.isSubagentsEnabled) },
                    enabled = state.subagentsEnabled,
                    label = {
                        Text(if (state.isSubagentsEnabled) "Subagents on" else "Subagents")
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (state.isPlanModeEnabled && state.isSubagentsEnabled && state.submitMode == ComposerSubmitMode.QUEUE) {
                                "Queue a delegated plan request for when this run finishes"
                            } else if (state.isPlanModeEnabled && state.isSubagentsEnabled) {
                                "Ask Codex to plan and delegate the work"
                            } else if (state.isSubagentsEnabled && state.submitMode == ComposerSubmitMode.QUEUE) {
                                "Queue a delegated follow-up for when this run finishes"
                            } else if (state.isSubagentsEnabled) {
                                "Ask Codex to delegate distinct work in parallel"
                            } else if (state.isPlanModeEnabled && state.submitMode == ComposerSubmitMode.QUEUE) {
                                "Queue a plan request for when this run finishes"
                            } else if (state.isPlanModeEnabled) {
                                "Ask Codex to make a plan before executing"
                            } else if (state.submitMode == ComposerSubmitMode.QUEUE) {
                                "Queue a follow-up for when this run finishes"
                            } else {
                                "Ask Codex..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    enabled = state.inputEnabled,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 5,
                )

                if (state.showStop) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = state.stopEnabled,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        if (state.isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Stop")
                        }
                    }

                    Button(
                        onClick = onSend,
                        enabled = state.submitEnabled,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                if (state.submitMode == ComposerSubmitMode.QUEUE) {
                                    if (state.isPlanModeEnabled && state.isSubagentsEnabled) {
                                        if (state.queuedCount > 0) "Queue Delegate (${state.queuedCount + 1})" else "Queue Delegate"
                                    } else if (state.isPlanModeEnabled) {
                                        if (state.queuedCount > 0) "Queue Plan (${state.queuedCount + 1})" else "Queue Plan"
                                    } else if (state.isSubagentsEnabled) {
                                        if (state.queuedCount > 0) "Queue Delegate (${state.queuedCount + 1})" else "Queue Delegate"
                                    } else {
                                        if (state.queuedCount > 0) "Queue (${state.queuedCount + 1})" else "Queue"
                                    }
                                } else {
                                    when {
                                        state.isPlanModeEnabled && state.isSubagentsEnabled -> "Delegate"
                                        state.isPlanModeEnabled -> "Plan"
                                        state.isSubagentsEnabled -> "Delegate"
                                        else -> "Send"
                                    }
                                }
                            )
                        }
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = state.submitEnabled,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (state.submitEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (state.submitEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                        modifier = Modifier.size(44.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
