package io.androdex.android.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androdex.android.R
import io.androdex.android.ui.shared.BridgeStatusCard
import io.androdex.android.ui.shared.BusyIndicator
import io.androdex.android.ui.shared.HostAccountCard
import io.androdex.android.ui.shared.LandingBackdrop
import io.androdex.android.ui.shared.LandingSectionSurface
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.HomeScreenUiState
import io.androdex.android.ui.state.ThreadListEmptyStateUiState
import io.androdex.android.ui.state.ThreadListItemUiState
import io.androdex.android.ui.state.ThreadRunBadgeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: HomeScreenUiState,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreateThread: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProjects: () -> Unit,
    onCloseProjects: () -> Unit,
    onRefreshProjects: () -> Unit,
    onBrowseWorkspace: (String?) -> Unit,
    onWorkspaceBrowserPathChanged: (String) -> Unit,
    onActivateWorkspace: (String) -> Unit,
) {
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
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "Androdex",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenSidebar) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open sidebar",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Open settings",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    windowInsets = WindowInsets(0),
                )
                BusyIndicator(state = state.busy)
            }
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LandingBackdrop()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    HomeHeroCard(
                        state = state,
                        onCreateThread = onCreateThread,
                        onOpenProjects = onOpenProjects,
                    )
                }
                item {
                    StatusCapsule(state = state.connection)
                }
                item {
                    CurrentProjectCard(
                        activeWorkspacePath = state.activeWorkspacePath,
                        onOpenProjects = onOpenProjects,
                    )
                }
                item {
                    RecentConversationHeader(hasThreads = state.threadList.threads.isNotEmpty())
                }
                when {
                    state.threadList.isLoading -> {
                        item {
                            LoadingThreadsCard()
                        }
                    }

                    state.threadList.emptyState != null -> {
                        item {
                            EmptyThreadsCard(
                                state = state.threadList.emptyState,
                                onOpenProjects = onOpenProjects,
                            )
                        }
                    }

                    else -> {
                        items(state.threadList.threads.take(8), key = { it.id }) { thread ->
                            RecentConversationCard(
                                thread = thread,
                                onOpenThread = { onOpenThread(thread.id) },
                            )
                        }
                    }
                }
                item {
                    BridgeStatusCard(state = state.bridgeStatus)
                }
                state.trustedPair?.let { pair ->
                    item {
                        TrustedPairCard(state = pair)
                    }
                }
                state.hostAccount?.let { account ->
                    item {
                        HostAccountCard(state = account)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroCard(
    state: HomeScreenUiState,
    onCreateThread: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    val hasWorkspace = state.activeWorkspacePath != null

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Androdex",
                        modifier = Modifier.size(72.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Host-local Codex",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Run everything on your computer. Control it from Android.",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                text = if (hasWorkspace) {
                    "Jump back into recent conversations or start a fresh thread in ${displayName(state.activeWorkspacePath)}."
                } else {
                    "Choose a project to make the first screen useful right away, then start threads without bouncing through extra sheets."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "End-to-end encrypted remote control",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onCreateThread,
                    enabled = hasWorkspace && !state.busy.isVisible,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (hasWorkspace) "New chat" else "Pick a project first",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                FilledTonalButton(
                    onClick = onOpenProjects,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (hasWorkspace) "Switch project" else "Choose project",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentProjectCard(
    activeWorkspacePath: String?,
    onOpenProjects: () -> Unit,
) {
    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Current project",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = activeWorkspacePath?.let(::displayName) ?: "No project selected yet",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = activeWorkspacePath
                    ?: "Pick a host folder to anchor new chats, recent history, and file-aware actions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onOpenProjects,
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (activeWorkspacePath == null) "Open project picker" else "Change project",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentConversationHeader(hasThreads: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Recent conversations",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = if (hasThreads) {
                    "Reopen the work that already exists on your host."
                } else {
                    "Once threads exist, they show up here instead of hiding in the drawer."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(10.dp)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun LoadingThreadsCard() {
    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Loading your conversations",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "Androdex is syncing the thread list from the host.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyThreadsCard(
    state: ThreadListEmptyStateUiState,
    onOpenProjects: () -> Unit,
) {
    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.showChooseProjectAction) {
                FilledTonalButton(
                    onClick = onOpenProjects,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Choose a project")
                }
            }
        }
    }
}

@Composable
private fun RecentConversationCard(
    thread: ThreadListItemUiState,
    onOpenThread: () -> Unit,
) {
    val runColor = when (thread.runState) {
        ThreadRunBadgeUiState.RUNNING -> MaterialTheme.colorScheme.primary
        ThreadRunBadgeUiState.READY -> MaterialTheme.colorScheme.tertiary
        ThreadRunBadgeUiState.FAILED -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        onClick = onOpenThread,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(runColor),
                    )
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                thread.updatedLabel?.let { updated ->
                    Text(
                        text = updated,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = thread.preview ?: "Open this thread to continue where the host left off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Text(
                text = thread.projectName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun displayName(path: String): String {
    return path.trimEnd('/', '\\')
        .substringAfterLast('\\')
        .substringAfterLast('/')
        .ifBlank { path }
}
