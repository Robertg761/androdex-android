package io.androdex.android.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.ui.graphics.Brush
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
import io.androdex.android.ui.state.RuntimeSettingsOptionUiState
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
    onSelectHostRuntimeTarget: (String) -> Unit,
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
                            onSelectHostRuntimeTarget = onSelectHostRuntimeTarget,
                            onCreateThread = onCreateThread,
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
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    RemodexPageHeader(
        title = "Androdex",
        subtitle = "Host-local runtime",
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
    onSelectHostRuntimeTarget: (String) -> Unit,
    onCreateThread: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                colors.accentBlue.copy(alpha = 0.18f),
                                colors.accentGreen.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
            ) {
                Surface(
                    modifier = Modifier.size(88.dp),
                    shape = RoundedCornerShape(RemodexTheme.geometry.cornerXLarge),
                    color = colors.selectedRowFill,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Androdex",
                        modifier = Modifier.size(88.dp),
                    )
                }
                RemodexPill(
                    label = "Host mission control",
                    style = RemodexPillStyle.Accent,
                )
            }
        }
        StatusCapsule(
            state = state.connection,
            modifier = Modifier.fillMaxWidth(),
            bottomPadding = 0.dp,
        )
        HomeLaunchCard(
            activeWorkspacePath = state.activeWorkspacePath,
            createThreadSupported = state.createThreadSupported,
            createThreadBlockedReason = state.createThreadBlockedReason,
            busy = state.busy.isVisible,
            onCreateThread = onCreateThread,
            onOpenProjects = onOpenProjects,
        )
        QuickRuntimeSwitchCard(
            options = state.hostRuntimeTargetOptions,
            enabled = !state.busy.isVisible,
            onSelect = onSelectHostRuntimeTarget,
        )
    }
}

@Composable
private fun QuickRuntimeSwitchCard(
    options: List<RuntimeSettingsOptionUiState>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    val activeOption = options.firstOrNull { it.selected }

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
                ) {
                    Text(
                        text = "Host runtime",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = activeOption?.title?.let { "Currently running on $it." }
                            ?: "Swap between Codex and Androdex Server right from home.",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Switches only appear live when the host can actually serve them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                activeOption?.let {
                    RemodexPill(
                        label = "Live now",
                        style = if (it.enabled) RemodexPillStyle.Success else RemodexPillStyle.Warning,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                options.forEach { option ->
                    val optionEnabled = enabled && option.enabled
                    val supportingText = runtimeOptionSupportingText(option)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(geometry.cornerLarge),
                        color = when {
                            option.selected -> colors.accentBlue.copy(alpha = 0.12f)
                            optionEnabled -> colors.secondarySurface.copy(alpha = 0.46f)
                            else -> colors.selectedRowFill.copy(alpha = 0.42f)
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = when {
                                option.selected -> colors.accentBlue.copy(alpha = 0.28f)
                                else -> colors.hairlineDivider
                            },
                        ),
                    ) {
                        RemodexSelectionRow(
                            selected = false,
                            onClick = if (optionEnabled && !option.selected) {
                                { option.value?.let(onSelect) }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            paddingValues = PaddingValues(
                                horizontal = geometry.spacing14,
                                vertical = geometry.spacing14,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
                            ) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (optionEnabled || option.selected) {
                                        colors.textPrimary
                                    } else {
                                        colors.disabledForeground
                                    },
                                )
                                supportingText?.let { subtitle ->
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (optionEnabled || option.selected) {
                                            colors.textSecondary
                                        } else {
                                            colors.disabledForeground
                                        },
                                    )
                                }
                                option.availabilityMessage?.takeIf { it.isNotBlank() }?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (option.selected) colors.accentBlue else colors.accentOrange,
                                    )
                                }
                            }
                            RemodexPill(
                                label = when {
                                    option.selected && !option.enabled -> "Needs repair"
                                    option.selected -> "Active"
                                    option.enabled -> "Ready"
                                    else -> "Unavailable"
                                },
                                style = when {
                                    option.selected && !option.enabled -> RemodexPillStyle.Warning
                                    option.selected -> RemodexPillStyle.Accent
                                    option.enabled -> RemodexPillStyle.Success
                                    else -> RemodexPillStyle.Warning
                                },
                            )
                        }
                    }
                }
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
private fun HomeLaunchCard(
    activeWorkspacePath: String?,
    createThreadSupported: Boolean,
    createThreadBlockedReason: String?,
    busy: Boolean,
    onCreateThread: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    val hasWorkspace = activeWorkspacePath != null
    val canCreateThread = hasWorkspace && createThreadSupported && !busy

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            colors.accentBlue.copy(alpha = 0.08f),
                            Color.Transparent,
                            colors.accentGreen.copy(alpha = 0.05f),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = geometry.sectionPadding, vertical = geometry.sectionPadding),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
                ) {
                    Text(
                        text = "Run on your computer. Continue from Android.",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = if (hasWorkspace) {
                            if (createThreadSupported) {
                                "Jump back into recent work in ${displayName(activeWorkspacePath)} or start a fresh thread without leaving the home view."
                            } else {
                                "Jump back into recent work in ${displayName(activeWorkspacePath)} and keep browsing existing threads from Android while Androdex Server write actions stay on the host."
                            }
                        } else {
                            "Choose a project once, then keep chats, file-aware actions, and recovery flows anchored to your host workspace."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
                ProjectSpotlightCard(
                    activeWorkspacePath = activeWorkspacePath,
                    onOpenProjects = onOpenProjects,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Encrypted relay-safe control",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
                ) {
                    RemodexButton(
                        onClick = onCreateThread,
                        enabled = canCreateThread,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(
                            horizontal = geometry.spacing20,
                            vertical = geometry.spacing14,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = when {
                                !hasWorkspace -> "Pick a project first"
                                createThreadSupported -> "New chat"
                                else -> "New chat unavailable"
                            },
                            modifier = Modifier.padding(start = geometry.spacing8),
                        )
                    }
                    RemodexButton(
                        onClick = onOpenProjects,
                        modifier = Modifier.fillMaxWidth(),
                        style = RemodexButtonStyle.Secondary,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (hasWorkspace) "Switch project" else "Choose project",
                            modifier = Modifier.padding(start = geometry.spacing8),
                        )
                    }
                    if (hasWorkspace && !createThreadSupported) {
                        Text(
                            text = createThreadBlockedReason
                                ?: "Starting new chats from this runtime isn't available in Androdex yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectSpotlightCard(
    activeWorkspacePath: String?,
    onOpenProjects: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(geometry.cornerXLarge))
            .background(colors.selectedRowFill.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = colors.hairlineDivider,
                shape = RoundedCornerShape(geometry.cornerXLarge),
            )
            .padding(geometry.spacing16),
        verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
    ) {
        Text(
            text = "Active project",
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
        )
        Text(
            text = activeWorkspacePath?.let(::displayName) ?: "No project selected yet",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        Text(
            text = activeWorkspacePath
                ?: "Pick a host folder to anchor new chats, recent history, and file-aware actions.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        RemodexButton(
            onClick = onOpenProjects,
            style = RemodexButtonStyle.Ghost,
        ) {
            Text(text = if (activeWorkspacePath == null) "Open project picker" else "Change project")
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

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = geometry.sectionPadding,
                    vertical = geometry.spacing20,
                ),
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
}

@Composable
private fun EmptyThreadsCard(
    state: ThreadListEmptyStateUiState,
    onOpenProjects: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    LandingSectionSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = geometry.sectionPadding,
                    vertical = geometry.spacing20,
                ),
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

private fun runtimeOptionSupportingText(option: RuntimeSettingsOptionUiState): String? {
    val subtitle = option.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val availability = option.availabilityMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return subtitle
    if (subtitle == availability) {
        return null
    }
    val duplicatedSuffix = "\n$availability"
    return subtitle.removeSuffix(duplicatedSuffix).trimEnd().ifBlank { null }
}

private fun displayName(path: String): String {
    return path.trimEnd('/', '\\')
        .substringAfterLast('\\')
        .substringAfterLast('/')
        .ifBlank { path }
}
