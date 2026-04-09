package io.androdex.android.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
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
import io.androdex.android.ui.shared.remodexBottomSafeAreaInsets
import io.androdex.android.ui.state.HomeScreenUiState
import io.androdex.android.ui.state.ThreadListPaneUiState
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
        contentWindowInsets = remodexBottomSafeAreaInsets(),
        topBar = {
            Column {
                HomeTopBar(
                    runtimeTargetLabel = state.bridgeStatus.runtimeTargetLabel,
                    onOpenSidebar = onOpenSidebar,
                    onOpenSettings = onOpenSettings,
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
                    top = innerPadding.calculateTopPadding() + geometry.spacing20,
                    bottom = innerPadding.calculateBottomPadding() + geometry.spacing32,
                ),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing24),
            ) {
                item {
                    HomeNarrowSection {
                        HomeHeroSection(
                            state = state,
                            onCreateThread = onCreateThread,
                            onOpenProjects = onOpenProjects,
                        )
                    }
                }
                item {
                    HomeNarrowSection {
                        CurrentProjectCard(
                            activeWorkspacePath = state.activeWorkspacePath,
                            onOpenProjects = onOpenProjects,
                        )
                    }
                }
                item {
                    HomeWideSection {
                        RecentConversationSection(
                            state = state.threadList,
                            onOpenProjects = onOpenProjects,
                            onOpenThread = onOpenThread,
                        )
                    }
                }
                item {
                    HomeWideSection {
                        BridgeStatusCard(state = state.bridgeStatus)
                    }
                }
                state.trustedPair?.let { pair ->
                    item {
                        HomeWideSection {
                            TrustedPairCard(state = pair)
                        }
                    }
                }
                state.hostAccount?.let { account ->
                    item {
                        HomeWideSection {
                            HostAccountCard(state = account)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    runtimeTargetLabel: String?,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    RemodexPageHeader(
        title = "Androdex",
        subtitle = runtimeTargetLabel?.takeIf { it.isNotBlank() } ?: "Host-local runtime",
        centerTitle = true,
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
}

@Composable
private fun HomeNarrowSection(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = RemodexTheme.geometry.maxContentWidth),
            content = content,
        )
    }
}

@Composable
private fun HomeWideSection(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            content = content,
        )
    }
}

@Composable
private fun HomeHeroSection(
    state: HomeScreenUiState,
    onCreateThread: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    val hasWorkspace = state.activeWorkspacePath != null
    val canCreateThread = hasWorkspace && state.createThreadSupported
    val geometry = RemodexTheme.geometry

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = RoundedCornerShape(RemodexTheme.geometry.cornerXLarge),
            color = RemodexTheme.colors.selectedRowFill,
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = "Androdex",
                modifier = Modifier.size(88.dp),
            )
        }
        StatusCapsule(
            state = state.connection,
            modifier = Modifier.fillMaxWidth(),
            bottomPadding = 0.dp,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            Text(
                text = "Run on your computer. Continue from Android.",
                style = MaterialTheme.typography.titleLarge,
                color = RemodexTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (hasWorkspace) {
                    if (state.createThreadSupported) {
                        "Jump back into recent work in ${displayName(state.activeWorkspacePath)} or start a fresh thread without leaving the home view."
                    } else {
                        "Jump back into recent work in ${displayName(state.activeWorkspacePath)} and keep browsing existing threads from Android while T3 write actions stay on the host."
                    }
                } else {
                    "Choose a project once, then keep chats, file-aware actions, and recovery flows anchored to your host workspace."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = RemodexTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = RemodexTheme.colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Encrypted relay-safe control",
                style = MaterialTheme.typography.labelLarge,
                color = RemodexTheme.colors.textSecondary,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            RemodexButton(
                onClick = onCreateThread,
                enabled = canCreateThread && !state.busy.isVisible,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = geometry.spacing20, vertical = geometry.spacing14),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        !hasWorkspace -> "Pick a project first"
                        state.createThreadSupported -> "New chat"
                        else -> "New chat unavailable"
                    },
                    modifier = Modifier.padding(start = geometry.spacing8),
                )
            }
            RemodexButton(
                onClick = onOpenProjects,
                modifier = Modifier.fillMaxWidth(),
                style = RemodexButtonStyle.Ghost,
            ) {
                Text(text = if (hasWorkspace) "Switch project" else "Choose project")
            }
            if (hasWorkspace && !state.createThreadSupported) {
                Text(
                    text = state.createThreadBlockedReason
                        ?: "Starting new chats from this runtime isn't available in Androdex yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RecentConversationSection(
    state: ThreadListPaneUiState,
    onOpenProjects: () -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val geometry = RemodexTheme.geometry

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
    ) {
        RecentConversationHeader(
            hasThreads = state.threads.isNotEmpty(),
            threadCount = state.threads.size,
        )
        when {
            state.isLoading -> LoadingThreadsCard()
            state.emptyState != null -> EmptyThreadsCard(
                state = state.emptyState,
                onOpenProjects = onOpenProjects,
            )
            else -> RecentConversationList(
                threads = state.threads.take(8),
                onOpenThread = onOpenThread,
            )
        }
    }
}

@Composable
private fun RecentConversationList(
    threads: List<ThreadListItemUiState>,
    onOpenThread: (String) -> Unit,
) {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column {
            threads.forEachIndexed { index, thread ->
                RecentConversationCard(
                    thread = thread,
                    onOpenThread = { onOpenThread(thread.id) },
                )
                if (index != threads.lastIndex) {
                    RemodexDivider(modifier = Modifier.padding(horizontal = geometry.sectionPadding))
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
                color = RemodexTheme.colors.textSecondary,
            )
            Text(
                text = activeWorkspacePath?.let(::displayName) ?: "No project selected yet",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = activeWorkspacePath
                    ?: "Pick a host folder to anchor new chats, recent history, and file-aware actions.",
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
            )
            RemodexButton(
                onClick = onOpenProjects,
                style = RemodexButtonStyle.Ghost,
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
private fun RecentConversationHeader(
    hasThreads: Boolean,
    threadCount: Int,
) {
    val geometry = RemodexTheme.geometry

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing2),
        ) {
            Text(
                text = "Recent conversations",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RemodexTheme.colors.textPrimary,
            )
            Text(
                text = if (hasThreads) {
                    "Reopen the work that already exists on your host."
                } else {
                    "The latest threads will appear here as soon as your host has something to continue."
                },
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
            )
        }
        if (hasThreads) {
            RemodexPill(
                label = if (threadCount == 1) "1 thread" else "$threadCount threads",
                style = RemodexPillStyle.Neutral,
            )
        }
    }
}

@Composable
private fun LoadingThreadsCard() {
    val geometry = RemodexTheme.geometry

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = geometry.spacing16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = RemodexTheme.colors.accentBlue,
            trackColor = RemodexTheme.colors.selectedRowFill,
        )
        Text(
            text = "Loading your conversations",
            style = MaterialTheme.typography.titleSmall,
            color = RemodexTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Androdex is syncing the thread list from the host.",
            style = MaterialTheme.typography.bodySmall,
            color = RemodexTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyThreadsCard(
    state: ThreadListEmptyStateUiState,
    onOpenProjects: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = geometry.spacing16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = RemodexTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = RemodexTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
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

@Composable
private fun RecentConversationCard(
    thread: ThreadListItemUiState,
    onOpenThread: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val runColor = threadRunColor(thread.runState)
    val runLabel = threadRunLabel(thread.runState)

    RemodexSelectionRow(
        selected = false,
        onClick = onOpenThread,
        modifier = Modifier.fillMaxWidth(),
        paddingValues = PaddingValues(horizontal = geometry.sectionPadding, vertical = geometry.spacing14),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
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
                        style = MaterialTheme.typography.titleSmall,
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
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = thread.projectName,
                    style = MaterialTheme.typography.labelLarge,
                    color = RemodexTheme.colors.textSecondary,
                )
                if (thread.isForked) {
                    RemodexPill(label = "Fork", style = RemodexPillStyle.Neutral)
                }
                if (runLabel != null) {
                    Text(
                        text = runLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = runColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun threadRunColor(runState: ThreadRunBadgeUiState?): Color = when (runState) {
    ThreadRunBadgeUiState.RUNNING -> RemodexTheme.colors.accentBlue
    ThreadRunBadgeUiState.READY -> RemodexTheme.colors.accentGreen
    ThreadRunBadgeUiState.FAILED -> RemodexTheme.colors.errorRed
    null -> RemodexTheme.colors.textTertiary
}

private fun threadRunLabel(runState: ThreadRunBadgeUiState?): String? = when (runState) {
    ThreadRunBadgeUiState.RUNNING -> "Running"
    ThreadRunBadgeUiState.READY -> "Ready"
    ThreadRunBadgeUiState.FAILED -> "Needs review"
    null -> null
}

private fun displayName(path: String): String {
    return path.trimEnd('/', '\\')
        .substringAfterLast('\\')
        .substringAfterLast('/')
        .ifBlank { path }
}
