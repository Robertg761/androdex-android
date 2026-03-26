package io.androdex.android.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                Text("Projects", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Current Project",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.activeWorkspacePath ?: "No project selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (!state.isBrowsing) {
                if (state.recentWorkspaces.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium)
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
                    Text("Browse Folders")
                }
            } else {
                Text("Browse Host Folders", style = MaterialTheme.typography.titleMedium)
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
        color = if (state.active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.title, style = MaterialTheme.typography.titleSmall)
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
