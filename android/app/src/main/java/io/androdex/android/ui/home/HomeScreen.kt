package io.androdex.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androdex.android.ui.shared.BusyIndicator
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.sidebar.ThreadListPane
import io.androdex.android.ui.state.HomeScreenUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: HomeScreenUiState,
    onDisconnect: () -> Unit,
    onForgetPairing: () -> Unit,
    onRefresh: () -> Unit,
    onCreateThread: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onCloseProjects: () -> Unit,
    onRefreshProjects: () -> Unit,
    onBrowseWorkspace: (String?) -> Unit,
    onWorkspaceBrowserPathChanged: (String) -> Unit,
    onActivateWorkspace: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    state.projectPicker?.let { projectPicker ->
        ProjectPickerSheet(
            state = projectPicker,
            onDismiss = onCloseProjects,
            onRefresh = onRefreshProjects,
            onBrowse = onBrowseWorkspace,
            onBrowserPathChanged = onWorkspaceBrowserPathChanged,
            onActivateWorkspace = onActivateWorkspace,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Threads", style = MaterialTheme.typography.titleLarge)
                },
                actions = {
                    TextButton(onClick = onOpenProjects) {
                        Text("Projects")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = {
                                    menuExpanded = false
                                    onDisconnect()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Forget Pairing",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onForgetPairing()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateThread,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New Chat") },
                shape = MaterialTheme.shapes.large,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 6.dp,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            StatusCapsule(
                state = state.connection,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            BusyIndicator(state = state.busy)

            ActiveWorkspaceBanner(
                activeWorkspacePath = state.activeWorkspacePath,
                onOpenProjects = onOpenProjects,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            ThreadListPane(
                state = state.threadList,
                onOpenThread = onOpenThread,
                onOpenProjects = onOpenProjects,
            )
        }
    }
}

@Composable
private fun ActiveWorkspaceBanner(
    activeWorkspacePath: String?,
    onOpenProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Active Project",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = activeWorkspacePath ?: "No project selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onOpenProjects) {
                Text(if (activeWorkspacePath == null) "Choose" else "Switch")
            }
        }
    }
}
