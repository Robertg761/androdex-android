package io.androdex.android.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexInputField
import io.androdex.android.ui.shared.RemodexInsetActionRow
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.theme.RemodexTheme
import io.androdex.android.ui.state.ProjectPickerUiState
import io.androdex.android.ui.state.WorkspaceRowAction
import io.androdex.android.ui.state.WorkspaceRowUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectPickerSheet(
    state: ProjectPickerUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onBrowse: (String?) -> Unit,
    onBrowserPathChanged: (String) -> Unit,
    onActivateWorkspace: (String) -> Unit,
) {
    val geometry = RemodexTheme.geometry

    RemodexModalSheet(onDismissRequest = onDismiss) {
        RemodexSheetHeaderBlock(
            title = "Projects",
            subtitle = if (state.isBrowsing) {
                "Choose the host folder that new chats and new threads should use."
            } else {
                "Switch between recent workspaces or browse the host for another folder."
            },
            trailing = {
                RemodexButton(
                    onClick = onRefresh,
                    style = RemodexButtonStyle.Ghost,
                ) {
                    Text("Refresh")
                }
            },
        )

        if (state.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RemodexTheme.colors.accentBlue,
                trackColor = RemodexTheme.colors.selectedRowFill,
            )
        }

        RemodexSheetCard {
            RemodexSheetSectionLabel("Current project")
            Text(
                text = state.activeWorkspacePath ?: "No project selected",
                style = MaterialTheme.typography.titleMedium,
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = if (state.activeWorkspacePath == null) {
                    "Selecting a project keeps new chats, attachments, and recent context anchored to the right host folder."
                } else {
                    "You can switch projects without leaving the thread list or reconnecting to the host."
                },
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
            )
            if (state.activeWorkspacePath != null) {
                RemodexPill(
                    label = "Active project",
                    style = RemodexPillStyle.Accent,
                )
            }
        }

        if (!state.isBrowsing) {
            if (state.recentWorkspaces.isNotEmpty()) {
                RemodexSheetCard {
                    RemodexSheetSectionLabel("Recent projects")
                    state.recentWorkspaces.forEachIndexed { index, workspace ->
                        ProjectWorkspaceRow(
                            state = workspace,
                            trailingLabel = if (workspace.active) "Active" else "Open",
                            onClick = { onActivateWorkspace(workspace.subtitle) },
                        )
                        if (index != state.recentWorkspaces.lastIndex) {
                            Spacer(modifier = Modifier.height(geometry.spacing2))
                        }
                    }
                }
            }

            RemodexInsetActionRow {
                RemodexButton(
                    onClick = { onBrowse(null) },
                    modifier = Modifier.weight(1f),
                    style = RemodexButtonStyle.Primary,
                ) {
                    Text("Browse host folders")
                }
            }
        } else {
            RemodexSheetCard {
                RemodexSheetSectionLabel("Browse host folders")
                RemodexInputField(
                    value = state.browserPath,
                    onValueChange = onBrowserPathChanged,
                    label = "Folder path",
                    placeholder = "/Users/you/project",
                    modifier = Modifier.fillMaxWidth(),
                    mono = true,
                )
                RemodexInsetActionRow {
                    RemodexButton(
                        onClick = { onBrowse(state.browserParentPath) },
                        enabled = state.browserParentPath != null,
                        modifier = Modifier.weight(1f),
                        style = RemodexButtonStyle.Secondary,
                    ) {
                        Text("Up one level")
                    }
                    RemodexButton(
                        onClick = { onBrowse(state.browserPath.takeIf { it.isNotBlank() }) },
                        enabled = state.browserPath.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open folder")
                    }
                }
            }

            if (state.browserEntries.isNotEmpty()) {
                RemodexSheetCard {
                    RemodexSheetSectionLabel("Available folders")
                    state.browserEntries.forEachIndexed { index, entry ->
                        ProjectWorkspaceRow(
                            state = entry,
                            trailingLabel = when (entry.action) {
                                WorkspaceRowAction.ACTIVATE -> if (entry.active) "Active" else "Use"
                                WorkspaceRowAction.BROWSE -> "Open"
                            },
                            onClick = {
                                when (entry.action) {
                                    WorkspaceRowAction.ACTIVATE -> onActivateWorkspace(entry.subtitle)
                                    WorkspaceRowAction.BROWSE -> onBrowse(entry.subtitle)
                                }
                            },
                        )
                        if (index != state.browserEntries.lastIndex) {
                            Spacer(modifier = Modifier.height(geometry.spacing2))
                        }
                    }
                }
            }

            RemodexInsetActionRow {
                RemodexButton(
                    onClick = { onActivateWorkspace(state.browserPath) },
                    enabled = state.browserPath.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Use this folder")
                }
            }
        }
    }
}

@Composable
private fun ProjectWorkspaceRow(
    state: WorkspaceRowUiState,
    trailingLabel: String,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    Surface(
        onClick = onClick,
        color = if (state.active) {
            colors.selectedRowFill
        } else {
            colors.groupedBackground.copy(alpha = 0.52f)
        },
        shape = RoundedCornerShape(geometry.cornerLarge),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (state.active) colors.accentBlue.copy(alpha = 0.18f) else colors.hairlineDivider,
        ),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                Box {
                    RemodexPill(
                        label = trailingLabel,
                        style = if (state.active) RemodexPillStyle.Accent else RemodexPillStyle.Neutral,
                    )
                }
            }
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
