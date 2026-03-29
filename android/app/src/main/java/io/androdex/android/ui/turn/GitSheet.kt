package io.androdex.android.ui.turn

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.GitActionKind
import io.androdex.android.model.GitChangedFile
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.ui.state.ThreadGitUiState

internal const val GIT_CHANGED_FILES_PREVIEW_LIMIT = 6

internal data class GitAffordanceUiState(
    val primaryLabel: String,
    val secondaryLabel: String?,
)

internal data class GitChangedFilesPreview(
    val visibleFiles: List<GitChangedFile>,
    val hiddenCount: Int,
)

internal fun buildGitAffordanceUiState(state: ThreadGitUiState): GitAffordanceUiState? {
    if (!state.hasWorkingDirectory) return null
    val status = state.status
    return GitAffordanceUiState(
        primaryLabel = status?.currentBranch?.trim()?.takeIf { it.isNotEmpty() } ?: "Git",
        secondaryLabel = compactGitStatusLabel(state),
    )
}

internal fun buildGitChangedFilesPreview(
    files: List<GitChangedFile>,
    limit: Int = GIT_CHANGED_FILES_PREVIEW_LIMIT,
): GitChangedFilesPreview {
    val safeLimit = limit.coerceAtLeast(0)
    val visibleFiles = files.take(safeLimit)
    return GitChangedFilesPreview(
        visibleFiles = visibleFiles,
        hiddenCount = (files.size - visibleFiles.size).coerceAtLeast(0),
    )
}

internal fun gitRepositoryName(status: GitRepoSyncResult?): String {
    return status?.repoRoot
        ?.trim()
        ?.substringAfterLast('\\')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotEmpty() }
        ?: "Git"
}

private fun compactGitStatusLabel(state: ThreadGitUiState): String? {
    val status = state.status
    return when {
        state.runningAction != null || state.isRefreshing -> "Busy"
        status == null -> null
        status.aheadCount > 0 -> "Ahead"
        status.behindCount > 0 -> "Behind"
        status.isDirty -> "Dirty"
        else -> "Clean"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitSheet(
    state: ThreadGitUiState,
    onDismiss: () -> Unit,
    onRefreshGit: () -> Unit,
    onLoadGitDiff: () -> Unit,
    onOpenGitCommit: () -> Unit,
    onPushGit: () -> Unit,
    onRequestGitPull: () -> Unit,
    onOpenGitBranchDialog: () -> Unit,
    onOpenGitWorktreeDialog: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val status = state.status
    val changedFiles = buildGitChangedFilesPreview(status?.files.orEmpty())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GitSheetHeader(state = state, status = status)

            state.availabilityMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }

            if (status != null) {
                GitSheetSectionHeader("Summary")
                GitSummaryCard(status = status)

                if (status.files.isNotEmpty()) {
                    GitSheetSectionHeader("Changed Files")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            changedFiles.visibleFiles.forEach { file ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GitFileStatusPill(file.status)
                                    Text(
                                        text = file.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (changedFiles.hiddenCount > 0) {
                                Text(
                                    text = if (changedFiles.hiddenCount == 1) {
                                        "1 more changed file not shown."
                                    } else {
                                        "${changedFiles.hiddenCount} more changed files not shown."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (state.hasWorkingDirectory && state.availabilityMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Git status has not been loaded yet. Refresh to inspect this checkout.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }

            GitSheetSectionHeader("Actions")
            GitActionGroup(
                title = "Inspect",
                actions = listOf(
                    GitSheetAction("Refresh", onRefreshGit, state.canRunActions),
                    GitSheetAction(
                        if (state.diffPatch.isNullOrBlank()) "Load Diff" else "Refresh Diff",
                        onLoadGitDiff,
                        state.canRunActions,
                    ),
                ),
            )
            GitActionGroup(
                title = "Changes",
                actions = listOf(
                    GitSheetAction(
                        "Commit",
                        onOpenGitCommit,
                        state.canRunActions && status?.isDirty == true,
                    ),
                ),
            )
            GitActionGroup(
                title = "Sync",
                actions = listOf(
                    GitSheetAction(
                        "Push",
                        onPushGit,
                        state.canRunActions && (status?.canPush == true),
                    ),
                    GitSheetAction(
                        "Pull",
                        onRequestGitPull,
                        state.canRunActions && status != null,
                    ),
                ),
            )
            GitActionGroup(
                title = "Branching",
                actions = listOf(
                    GitSheetAction("Branches", onOpenGitBranchDialog, state.canRunActions),
                    GitSheetAction("Worktrees", onOpenGitWorktreeDialog, state.canRunActions),
                ),
            )

            state.diffPatch?.takeIf { it.isNotBlank() }?.let { patch ->
                GitSheetSectionHeader("Diff")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DiffView(
                        diffText = patch,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun GitSheetHeader(
    state: ThreadGitUiState,
    status: GitRepoSyncResult?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = gitRepositoryName(status),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = status?.currentBranch?.trim()?.takeIf { it.isNotEmpty() } ?: "Repository controls",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                status?.state?.takeIf { it.isNotBlank() }?.let {
                    GitBadge(label = it.replace('_', ' '), emphasized = true)
                }
                status?.let {
                    GitBadge(label = if (it.isDirty) "Dirty" else "Clean")
                    if (it.aheadCount > 0) {
                        GitBadge(label = "Ahead ${it.aheadCount}")
                    }
                    if (it.behindCount > 0) {
                        GitBadge(label = "Behind ${it.behindCount}")
                    }
                }
                if (state.runningAction != null || state.isRefreshing) {
                    GitBadge(label = gitActionLabel(state.runningAction ?: GitActionKind.REFRESH))
                }
            }
        }

        if (state.runningAction != null || state.isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(20.dp)
                    .height(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun GitSummaryCard(status: GitRepoSyncResult) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GitSummaryValue(
                    label = "Working tree",
                    value = if (status.isDirty) "Dirty" else "Clean",
                    modifier = Modifier.weight(1f),
                )
                GitSummaryValue(
                    label = "Sync",
                    value = buildString {
                        if (status.aheadCount > 0) {
                            append("Ahead ${status.aheadCount}")
                        }
                        if (status.behindCount > 0) {
                            if (isNotEmpty()) append(" / ")
                            append("Behind ${status.behindCount}")
                        }
                        if (isEmpty()) append("Up to date")
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GitSummaryValue(
                    label = "Local only",
                    value = if (status.localOnlyCommitCount == 0) {
                        "None"
                    } else if (status.localOnlyCommitCount == 1) {
                        "1 commit"
                    } else {
                        "${status.localOnlyCommitCount} commits"
                    },
                    modifier = Modifier.weight(1f),
                )
                GitSummaryValue(
                    label = "Tracking",
                    value = status.trackingBranch?.takeIf { it.isNotBlank() } ?: "No upstream",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GitSummaryValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class GitSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean,
)

@Composable
private fun GitActionGroup(
    title: String,
    actions: List<GitSheetAction>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                OutlinedButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                ) {
                    Text(action.label)
                }
            }
        }
    }
}

@Composable
private fun GitSheetSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
    )
}

@Composable
private fun GitBadge(
    label: String,
    emphasized: Boolean = false,
) {
    Surface(
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GitFileStatusPill(status: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = status.ifBlank { "?" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun DiffView(
    diffText: String,
    modifier: Modifier = Modifier,
) {
    val addedBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    val removedBg = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    val addedText = MaterialTheme.colorScheme.tertiary
    val removedText = MaterialTheme.colorScheme.error
    val hunkText = MaterialTheme.colorScheme.secondary
    val contextText = MaterialTheme.colorScheme.onSurfaceVariant
    val scrollState = rememberScrollState()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(vertical = 4.dp),
        ) {
            diffText.lines().forEach { line ->
                val (bgColor, fgColor) = when {
                    line.startsWith("+++") || line.startsWith("---") -> {
                        Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    line.startsWith("+") -> addedBg to addedText
                    line.startsWith("-") -> removedBg to removedText
                    line.startsWith("@@") -> Color.Transparent to hunkText
                    else -> Color.Transparent to contextText
                }

                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = fgColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 1.dp),
                    softWrap = false,
                )
            }
        }
    }
}

private fun gitActionLabel(action: GitActionKind): String = when (action) {
    GitActionKind.REFRESH -> "Refreshing"
    GitActionKind.DIFF -> "Loading diff"
    GitActionKind.COMMIT -> "Committing"
    GitActionKind.PUSH -> "Pushing"
    GitActionKind.PULL -> "Pulling"
    GitActionKind.SWITCH_BRANCH -> "Switching"
    GitActionKind.CREATE_BRANCH -> "Creating branch"
    GitActionKind.CREATE_WORKTREE -> "Creating worktree"
    GitActionKind.REMOVE_WORKTREE -> "Removing worktree"
}
