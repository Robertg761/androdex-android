package io.androdex.android.ui.turn

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.GitActionKind
import io.androdex.android.model.GitChangedFile
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexModalSheet
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.RemodexSheetCard
import io.androdex.android.ui.shared.RemodexSheetHeaderBlock
import io.androdex.android.ui.shared.RemodexSheetSectionLabel
import io.androdex.android.ui.state.ThreadGitUiState
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme

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
    val status = state.status
    val changedFiles = buildGitChangedFilesPreview(status?.files.orEmpty())
    val geometry = RemodexTheme.geometry

    RemodexModalSheet(onDismissRequest = onDismiss) {
        GitSheetHeader(state = state, status = status)

        state.availabilityMessage?.takeIf { it.isNotBlank() }?.let { message ->
            RemodexSheetCard(
                tint = RemodexTheme.colors.selectedRowFill.copy(alpha = 0.84f),
            ) {
                RemodexSheetSectionLabel("Availability")
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }
        }

        if (status != null) {
            GitSummaryCard(status = status)

            if (status.files.isNotEmpty()) {
                RemodexSheetCard {
                    RemodexSheetSectionLabel("Changed files")
                    changedFiles.visibleFiles.forEachIndexed { index, file ->
                        GitChangedFileRow(file = file)
                        if (index != changedFiles.visibleFiles.lastIndex) {
                            Spacer(modifier = Modifier.height(geometry.spacing2))
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
                            color = RemodexTheme.colors.textSecondary,
                        )
                    }
                }
            }
        } else if (state.hasWorkingDirectory && state.availabilityMessage.isNullOrBlank()) {
            RemodexSheetCard {
                RemodexSheetSectionLabel("Summary")
                Text(
                    text = "Git status has not been loaded yet. Refresh to inspect this checkout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemodexTheme.colors.textSecondary,
                )
            }
        }

        RemodexSheetCard {
            RemodexSheetSectionLabel("Actions")
            GitActionGroup(
                title = "Inspect",
                actions = listOf(
                    GitSheetAction("Refresh", onRefreshGit, state.canRunActions, RemodexButtonStyle.Secondary),
                    GitSheetAction(
                        if (state.diffPatch.isNullOrBlank()) "Load diff" else "Refresh diff",
                        onLoadGitDiff,
                        state.canRunActions,
                        RemodexButtonStyle.Secondary,
                    ),
                ),
            )
            RemodexDivider()
            GitActionGroup(
                title = "Changes",
                actions = listOf(
                    GitSheetAction(
                        "Commit",
                        onOpenGitCommit,
                        state.canRunActions && status?.isDirty == true,
                        RemodexButtonStyle.Primary,
                    ),
                ),
            )
            RemodexDivider()
            GitActionGroup(
                title = "Sync",
                actions = listOf(
                    GitSheetAction(
                        "Push",
                        onPushGit,
                        state.canRunActions && (status?.canPush == true),
                        RemodexButtonStyle.Primary,
                    ),
                    GitSheetAction(
                        "Pull",
                        onRequestGitPull,
                        state.canRunActions && status != null,
                        RemodexButtonStyle.Secondary,
                    ),
                ),
            )
            RemodexDivider()
            GitActionGroup(
                title = "Branching",
                actions = listOf(
                    GitSheetAction("Branches", onOpenGitBranchDialog, state.canRunActions, RemodexButtonStyle.Secondary),
                    GitSheetAction("Worktrees", onOpenGitWorktreeDialog, state.canRunActions, RemodexButtonStyle.Secondary),
                ),
            )
        }

        state.diffPatch?.takeIf { it.isNotBlank() }?.let { patch ->
            RemodexSheetCard {
                RemodexSheetSectionLabel("Diff")
                DiffView(
                    diffText = patch,
                    modifier = Modifier.clip(RoundedCornerShape(RemodexTheme.geometry.cornerLarge)),
                )
            }
        }
    }
}

@Composable
private fun GitSheetHeader(
    state: ThreadGitUiState,
    status: GitRepoSyncResult?,
) {
    RemodexSheetHeaderBlock(
        title = gitRepositoryName(status),
        subtitle = status?.currentBranch?.trim()?.takeIf { it.isNotEmpty() } ?: "Repository controls",
        trailing = {
            if (state.runningAction != null || state.isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(20.dp)
                        .height(20.dp),
                    strokeWidth = 2.dp,
                    color = RemodexTheme.colors.accentBlue,
                )
            }
        },
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

@Composable
private fun GitSummaryCard(status: GitRepoSyncResult) {
    RemodexSheetCard {
        RemodexSheetSectionLabel("Summary")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RemodexPill(
                label = if (status.isDirty) "Working tree dirty" else "Working tree clean",
                style = if (status.isDirty) RemodexPillStyle.Warning else RemodexPillStyle.Success,
            )
            status.trackingBranch?.takeIf { it.isNotBlank() }?.let {
                RemodexPill(label = it, style = RemodexPillStyle.Neutral)
            }
            if (status.localOnlyCommitCount > 0) {
                RemodexPill(
                    label = "${status.localOnlyCommitCount} local",
                    style = RemodexPillStyle.Accent,
                )
            }
        }
        Column(
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

            RemodexDivider()

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
            color = RemodexTheme.colors.accentBlue,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = RemodexTheme.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class GitSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean,
    val style: RemodexButtonStyle,
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
            color = RemodexTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                RemodexButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                    style = action.style,
                ) {
                    Text(action.label)
                }
            }
        }
    }
}

@Composable
private fun GitBadge(
    label: String,
    emphasized: Boolean = false,
) {
    RemodexPill(
        label = label.replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase()
            } else {
                char.toString()
            }
        },
        style = if (emphasized) RemodexPillStyle.Accent else RemodexPillStyle.Neutral,
    )
}

@Composable
private fun GitFileStatusPill(status: String) {
    RemodexPill(
        label = status.ifBlank { "?" },
        style = gitFileStatusPillStyle(status),
    )
}

@Composable
private fun GitChangedFileRow(file: GitChangedFile) {
    Surface(
        color = RemodexTheme.colors.groupedBackground.copy(alpha = 0.54f),
        shape = RoundedCornerShape(RemodexTheme.geometry.cornerLarge),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemodexTheme.colors.hairlineDivider),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RemodexTheme.geometry.spacing12, vertical = RemodexTheme.geometry.spacing10),
            horizontalArrangement = Arrangement.spacedBy(RemodexTheme.geometry.spacing10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GitFileStatusPill(file.status)
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodySmall,
                color = RemodexTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DiffView(
    diffText: String,
    modifier: Modifier = Modifier,
) {
    val colors = RemodexTheme.colors
    val addedBg = colors.accentGreen.copy(alpha = 0.12f)
    val removedBg = colors.errorRed.copy(alpha = 0.12f)
    val addedText = colors.accentGreen
    val removedText = colors.errorRed
    val hunkText = colors.accentBlue
    val contextText = colors.textSecondary
    val scrollState = rememberScrollState()

    Surface(
        color = colors.groupedBackground.copy(alpha = 0.92f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(vertical = 6.dp),
        ) {
            diffText.lines().forEach { line ->
                val (bgColor, fgColor) = when {
                    line.startsWith("+++") || line.startsWith("---") -> {
                        Color.Transparent to colors.textTertiary
                    }
                    line.startsWith("+") -> addedBg to addedText
                    line.startsWith("-") -> removedBg to removedText
                    line.startsWith("@@") -> Color.Transparent to hunkText
                    else -> Color.Transparent to contextText
                }

                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = RemodexMonoFontFamily,
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

internal fun gitFileStatusPillStyle(status: String): RemodexPillStyle {
    return when (status.trim().uppercase()) {
        "A", "??" -> RemodexPillStyle.Success
        "M", "MM" -> RemodexPillStyle.Warning
        "D" -> RemodexPillStyle.Error
        "R", "C", "U" -> RemodexPillStyle.Accent
        else -> RemodexPillStyle.Neutral
    }
}
