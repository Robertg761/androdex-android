package io.androdex.android.ui.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.R
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexIconButton
import io.androdex.android.ui.shared.RemodexSearchField
import io.androdex.android.ui.shared.RemodexSelectionRow
import io.androdex.android.ui.state.ConnectionBannerUiState
import io.androdex.android.ui.state.ThreadListItemUiState
import io.androdex.android.ui.state.ThreadListPaneUiState
import io.androdex.android.ui.state.ThreadRunBadgeUiState
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

@Composable
internal fun SidebarContent(
    threadList: ThreadListPaneUiState,
    connection: ConnectionBannerUiState,
    macName: String?,
    selectedThreadId: String?,
    onCreateThread: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    var searchText by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var expandedProjects by rememberSaveable { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize()) {
        SidebarHeader()
        SidebarSearchField(
            text = searchText,
            focused = searchFocused,
            onTextChange = { searchText = it },
            onFocusChange = { searchFocused = it },
            onCancel = {
                searchText = ""
                searchFocused = false
            },
        )
        SidebarNewChatButton(onCreate = onCreateThread)
        SidebarThreadCollection(
            threadList = threadList,
            searchText = searchText,
            selectedThreadId = selectedThreadId,
            expandedProjects = expandedProjects,
            onToggleProject = { project ->
                expandedProjects = if (project in expandedProjects) {
                    expandedProjects - project
                } else {
                    expandedProjects + project
                }
            },
            onOpenThread = onOpenThread,
            modifier = Modifier.weight(1f),
        )
        SidebarFooter(
            connection = connection,
            macName = macName,
            onOpenSettings = onOpenSettings,
        )
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun SidebarHeader() {
    val geometry = RemodexTheme.geometry

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = geometry.sidebarOuterHorizontalPadding,
                end = geometry.sidebarOuterHorizontalPadding,
                top = geometry.spacing12,
                bottom = geometry.spacing8,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
    ) {
        // App logo 26×26 with rounded corners
        Surface(
            modifier = Modifier.size(26.dp),
            shape = RoundedCornerShape(RemodexTheme.geometry.cornerTiny),
            color = RemodexTheme.colors.selectedRowFill,
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
        }

        Text(
            text = "Androdex",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                letterSpacing = 0.sp,
            ),
            color = RemodexTheme.colors.textPrimary,
        )
    }
}

// ── Search field ─────────────────────────────────────────────────────────────

@Composable
private fun SidebarSearchField(
    text: String,
    focused: Boolean,
    onTextChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = geometry.sidebarOuterHorizontalPadding,
                end = geometry.sidebarOuterHorizontalPadding,
                top = geometry.spacing2,
                bottom = geometry.spacing8,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
    ) {
        RemodexSearchField(
            text = text,
            onTextChange = onTextChange,
            modifier = Modifier.weight(1f),
            onFocusChange = onFocusChange,
        )

        // Cancel button (visible when focused)
        AnimatedVisibility(
            visible = focused,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            RemodexButton(
                onClick = onCancel,
                style = RemodexButtonStyle.Ghost,
                contentPadding = PaddingValues(horizontal = geometry.spacing4, vertical = 0.dp),
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RemodexTheme.colors.accentBlue,
                )
            }
        }
    }
}

// ── New Chat button ───────────────────────────────────────────────────────────

@Composable
private fun SidebarNewChatButton(onCreate: () -> Unit) {
    val geometry = RemodexTheme.geometry

    RemodexSelectionRow(
        selected = false,
        onClick = onCreate,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = geometry.rowHeight + geometry.spacing12)
            .padding(horizontal = geometry.spacing4),
        paddingValues = PaddingValues(
            horizontal = geometry.sidebarRowHorizontalPadding,
            vertical = geometry.spacing10,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = RemodexTheme.colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "New Chat",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            ),
            color = RemodexTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun SidebarProjectHeader(
    projectName: String,
    threadCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val geometry = RemodexTheme.geometry

    // Display only the last path component (like remodex)
    val displayName = projectName.trimEnd('/').substringAfterLast('/')
        .ifBlank { projectName }

    Surface(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = geometry.spacing4)
            .heightIn(min = geometry.rowHeight + geometry.spacing12),
        color = Color.Transparent,
        shape = RoundedCornerShape(geometry.cornerTiny),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = geometry.sidebarRowHorizontalPadding,
                    end = geometry.sidebarRowHorizontalPadding,
                    top = geometry.spacing14,
                    bottom = geometry.spacing6,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse $displayName" else "Expand $displayName",
                tint = RemodexTheme.colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = RemodexTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = threadCount.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                color = RemodexTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun SidebarThreadRow(
    thread: ThreadListItemUiState,
    isSelected: Boolean,
    onOpenThread: (String) -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val runDotColor = when (thread.runState) {
        ThreadRunBadgeUiState.RUNNING -> RemodexTheme.colors.accentBlue
        ThreadRunBadgeUiState.READY   -> RemodexTheme.colors.accentGreen
        ThreadRunBadgeUiState.FAILED  -> RemodexTheme.colors.errorRed
        null -> RemodexTheme.colors.textTertiary
    }

    RemodexSelectionRow(
        selected = isSelected,
        onClick = { onOpenThread(thread.id) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = geometry.rowHeight + geometry.spacing8),
        paddingValues = PaddingValues(
            horizontal = geometry.sidebarOuterHorizontalPadding,
            vertical = geometry.spacing6,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(runDotColor),
        )

        Text(
            text = thread.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            ),
            color = if (isSelected) RemodexTheme.colors.accentBlue else RemodexTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        thread.updatedLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = RemodexTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun SidebarThreadCollection(
    threadList: ThreadListPaneUiState,
    searchText: String,
    selectedThreadId: String?,
    expandedProjects: Set<String>,
    onToggleProject: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandAllProjects: Boolean = false,
) {
    val geometry = RemodexTheme.geometry
    val filteredThreads = remember(threadList.threads, searchText) {
        if (searchText.isBlank()) {
            threadList.threads
        } else {
            threadList.threads.filter { thread ->
                thread.title.contains(searchText, ignoreCase = true) ||
                    thread.projectName.contains(searchText, ignoreCase = true) ||
                    thread.preview.orEmpty().contains(searchText, ignoreCase = true)
            }
        }
    }
    val groupedThreads = remember(filteredThreads) {
        filteredThreads
            .groupBy { thread ->
                thread.projectName
                    .trim()
                    .ifBlank { "Untitled" }
            }
            .entries
            .sortedBy { entry ->
                if (entry.key == "Untitled") {
                    "\uFFFF"
                } else {
                    entry.key.lowercase()
                }
            }
    }

    Box(modifier = modifier.fillMaxHeight()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = geometry.spacing4),
        ) {
            when {
                threadList.isLoading && searchText.isBlank() -> {
                    item {
                        SidebarListStatusCard(
                            title = "Loading conversations...",
                            message = "Checking the host for your latest grouped threads.",
                            loading = true,
                        )
                    }
                }

                threadList.emptyState != null && searchText.isBlank() -> {
                    item {
                        SidebarListStatusCard(
                            title = threadList.emptyState.title,
                            message = threadList.emptyState.message,
                        )
                    }
                }

                filteredThreads.isEmpty() && searchText.isNotBlank() -> {
                    item {
                        SidebarListStatusCard(
                            title = "No matching conversations",
                            message = "Try a different title, project, or preview phrase.",
                        )
                    }
                }

                else -> {
                    groupedThreads.forEach { (project, threads) ->
                        val isExpanded = expandAllProjects ||
                            searchText.isNotBlank() ||
                            selectedThreadId in threads.map { it.id } ||
                            project in expandedProjects
                        item(key = "project_$project") {
                            SidebarProjectHeader(
                                projectName = project,
                                threadCount = threads.size,
                                expanded = isExpanded,
                                onToggle = { onToggleProject(project) },
                            )
                        }
                        if (isExpanded) {
                            items(threads, key = { thread -> thread.id }) { thread ->
                                SidebarThreadRow(
                                    thread = thread,
                                    isSelected = thread.id == selectedThreadId,
                                    onOpenThread = onOpenThread,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(geometry.spacing16))
            }
        }

        if (threadList.showLoadingOverlay && searchText.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RemodexTheme.colors.groupedBackground.copy(alpha = 0.76f))
                    .padding(horizontal = geometry.sidebarOuterHorizontalPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                SidebarListStatusCard(
                    title = "Refreshing conversations...",
                    message = "Updating the grouped drawer from the host workspace.",
                    loading = true,
                )
            }
        }
    }
}

@Composable
private fun SidebarListStatusCard(
    title: String,
    message: String,
    loading: Boolean = false,
) {
    val geometry = RemodexTheme.geometry

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = geometry.sidebarOuterHorizontalPadding,
                vertical = geometry.spacing24,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
            modifier = Modifier.widthIn(max = 250.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = RemodexTheme.colors.accentBlue,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = RemodexTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SidebarFooter(
    connection: ConnectionBannerUiState,
    macName: String?,
    onOpenSettings: () -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val isConnected = connection.status == ConnectionStatus.CONNECTED
    val connectionLabel = if (isConnected) "Connected to Mac" else "Saved Mac"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = geometry.spacing4,
                end = geometry.spacing4,
                bottom = geometry.spacing6,
            ),
    ) {
        Surface(
            color = RemodexTheme.colors.secondarySurface.copy(alpha = 0.8f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(geometry.cornerXLarge)),
            shape = RoundedCornerShape(geometry.cornerXLarge),
            border = BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = geometry.sidebarRowHorizontalPadding,
                        vertical = geometry.spacing12,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemodexIconButton(
                    onClick = onOpenSettings,
                    contentDescription = "Settings",
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = RemodexTheme.colors.textPrimary,
                        modifier = Modifier.size(RemodexTheme.geometry.iconSize),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (macName != null) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.widthIn(max = 190.dp),
                    ) {
                        Text(
                            text = macName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = RemodexMonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                            ),
                            color = RemodexTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = connectionLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = RemodexMonoFontFamily,
                                fontSize = 10.sp,
                            ),
                            color = RemodexTheme.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
