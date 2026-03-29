package io.androdex.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.LandingSectionSurface
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Projects", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (state.isBrowsing) {
                            "Choose the host folder that new chats should use."
                        } else {
                            "Pick a recent project or browse the host for another one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Current project",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.activeWorkspacePath ?: "No project selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.activeWorkspacePath == null) {
                            "Selecting a project makes new chats and recent context land in the right place immediately."
                        } else {
                            "Switch projects any time without leaving the thread list."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!state.isBrowsing) {
                if (state.recentWorkspaces.isNotEmpty()) {
                    Text("Recent projects", style = MaterialTheme.typography.titleMedium)
                    state.recentWorkspaces.forEach { workspace ->
                        WorkspaceRow(
                            state = workspace,
                            onClick = { onActivateWorkspace(workspace.subtitle) },
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { onBrowse(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Browse host folders")
                }
            } else {
                Text("Browse host folders", style = MaterialTheme.typography.titleMedium)
                TextField(
                    value = state.browserPath,
                    onValueChange = onBrowserPathChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Folder path") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { onBrowse(state.browserParentPath) },
                        enabled = state.browserParentPath != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Up")
                    }
                    Button(
                        onClick = { onBrowse(state.browserPath.takeIf { it.isNotBlank() }) },
                        enabled = state.browserPath.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open")
                    }
                }

                state.browserEntries.forEach { entry ->
                    WorkspaceRow(
                        state = entry,
                        onClick = {
                            when (entry.action) {
                                WorkspaceRowAction.ACTIVATE -> onActivateWorkspace(entry.subtitle)
                                WorkspaceRowAction.BROWSE -> onBrowse(entry.subtitle)
                            }
                        },
                    )
                }

                Button(
                    onClick = { onActivateWorkspace(state.browserPath) },
                    enabled = state.browserPath.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use This Folder")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    state: WorkspaceRowUiState,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (state.active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                if (state.active) {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
