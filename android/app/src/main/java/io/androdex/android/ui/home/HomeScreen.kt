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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexIconButton
import io.androdex.android.ui.shared.RemodexPageHeader
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.RemodexSelectionRow
import io.androdex.android.ui.shared.StatusCapsule
import io.androdex.android.ui.shared.TrustedPairCard
import io.androdex.android.ui.state.HomeScreenUiState
import io.androdex.android.ui.state.ThreadListEmptyStateUiState
import io.androdex.android.ui.state.ThreadListItemUiState
import io.androdex.android.ui.state.ThreadRunBadgeUiState
import io.androdex.android.ui.theme.RemodexTheme

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
    val geometry = RemodexTheme.geometry

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
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        topBar = {
            Column {
                RemodexPageHeader(
                    title = "Androdex",
                    navigation = {
                        RemodexIconButton(
                            onClick = onOpenSidebar,
                            contentDescription = "Open sidebar",
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = null,
                                tint = RemodexTheme.colors.textPrimary,
                                modifier = Modifier.size(RemodexTheme.geometry.iconSize),
                            )
                        }
                    },
                    actions = {
                        RemodexIconButton(
                            onClick = onOpenSettings,
                            contentDescription = "Open settings",
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = RemodexTheme.colors.textPrimary,
                                modifier = Modifier.size(RemodexTheme.geometry.iconSize),
                            )
                        }
                    },
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
                    start = geometry.pageHorizontalPadding,
                    end = geometry.pageHorizontalPadding,
                    top = innerPadding.calculateTopPadding() + geometry.spacing8,
                    bottom = innerPadding.calculateBottomPadding() + geometry.spacing32,
                ),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
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
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.pageHorizontalPadding,
                vertical = geometry.pageVerticalPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing18),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing14),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(RemodexTheme.geometry.cornerXLarge),
                    color = RemodexTheme.colors.selectedRowFill,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Androdex",
                        modifier = Modifier.size(72.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing6)) {
                    Text(
                        text = "Host-local Codex",
                        style = MaterialTheme.typography.labelLarge,
                        color = RemodexTheme.colors.accentBlue,
                    )
                    Text(
                        text = "Run everything on your computer. Control it from Android.",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = RemodexTheme.colors.textPrimary,
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
                color = RemodexTheme.colors.textSecondary,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = RemodexTheme.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "End-to-end encrypted remote control",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            ) {
                RemodexButton(
                    onClick = onCreateThread,
                    enabled = hasWorkspace && !state.busy.isVisible,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (hasWorkspace) "New chat" else "Pick a project first",
                        modifier = Modifier.padding(start = geometry.spacing8),
                    )
                }
                RemodexButton(
                    onClick = onOpenProjects,
                    modifier = Modifier.weight(1f),
                    style = RemodexButtonStyle.Secondary,
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (hasWorkspace) "Switch project" else "Choose project",
                        modifier = Modifier.padding(start = geometry.spacing8),
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
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Text(
                text = "Current project",
                style = MaterialTheme.typography.labelLarge,
                color = RemodexTheme.colors.accentBlue,
            )
            Text(
                text = activeWorkspacePath?.let(::displayName) ?: "No project selected yet",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = activeWorkspacePath
                    ?: "Pick a host folder to anchor new chats, recent history, and file-aware actions.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
            RemodexButton(
                onClick = onOpenProjects,
                style = RemodexButtonStyle.Secondary,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (activeWorkspacePath == null) "Open project picker" else "Change project",
                    modifier = Modifier.padding(start = geometry.spacing6),
                )
            }
        }
    }
}

@Composable
private fun RecentConversationHeader(hasThreads: Boolean) {
    val geometry = RemodexTheme.geometry

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
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
                color = RemodexTheme.colors.textSecondary,
            )
        }
        RemodexPill(label = "History", style = RemodexPillStyle.Accent)
    }
}

@Composable
private fun LoadingThreadsCard() {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            Text(
                text = "Loading your conversations",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = "Androdex is syncing the thread list from the host.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun EmptyThreadsCard(
    state: ThreadListEmptyStateUiState,
    onOpenProjects: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
            )
            if (state.showChooseProjectAction) {
                RemodexButton(
                    onClick = onOpenProjects,
                    style = RemodexButtonStyle.Secondary,
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
    val geometry = RemodexTheme.geometry
    val runColor = when (thread.runState) {
        ThreadRunBadgeUiState.RUNNING -> RemodexTheme.colors.accentBlue
        ThreadRunBadgeUiState.READY -> RemodexTheme.colors.accentGreen
        ThreadRunBadgeUiState.FAILED -> RemodexTheme.colors.errorRed
        null -> RemodexTheme.colors.textTertiary
    }

    RemodexSelectionRow(
        selected = false,
        onClick = onOpenThread,
        modifier = Modifier.fillMaxWidth(),
        paddingValues = PaddingValues(horizontal = geometry.sectionPadding, vertical = geometry.spacing16),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
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
                        color = RemodexTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                thread.updatedLabel?.let { updated ->
                    Text(
                        text = updated,
                        style = MaterialTheme.typography.labelSmall,
                        color = RemodexTheme.colors.textSecondary,
                    )
                }
            }
            Text(
                text = thread.preview ?: "Open this thread to continue where the host left off.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            RemodexDivider()
            Text(
                text = thread.projectName,
                style = MaterialTheme.typography.labelLarge,
                color = RemodexTheme.colors.accentBlue,
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
