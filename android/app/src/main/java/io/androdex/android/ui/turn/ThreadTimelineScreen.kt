package io.androdex.android.ui.turn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androdex.android.applyingSubagentsSelection
import io.androdex.android.ComposerSlashCommand
import io.androdex.android.GitAlertAction
import io.androdex.android.GitAlertButton
import io.androdex.android.GitAlertState
import io.androdex.android.GitBranchDialogState
import io.androdex.android.GitCommitDialogState
import io.androdex.android.GitWorktreeDialogState
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.ExecutionDetail
import io.androdex.android.model.ExecutionKind
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.GitWorktreeChangeTransferMode
import io.androdex.android.model.PlanStep
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.SubagentThreadPresentation
import io.androdex.android.ui.shared.BusyIndicator
import io.androdex.android.ui.state.ThreadRunBadgeUiState
import io.androdex.android.ui.state.ThreadTimelineUiState
import io.androdex.android.ui.state.ToolUserInputCardUiState
import io.androdex.android.ui.state.ToolUserInputQuestionUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private data class BubbleContext(
    val message: ConversationMessage,
    val isFirstInGroup: Boolean,
    val isLastInGroup: Boolean,
)

@Composable
private fun runStateDotColor(runState: ThreadRunBadgeUiState?): Color = when (runState) {
    ThreadRunBadgeUiState.RUNNING -> MaterialTheme.colorScheme.primary
    ThreadRunBadgeUiState.READY -> MaterialTheme.colorScheme.tertiary
    ThreadRunBadgeUiState.FAILED -> MaterialTheme.colorScheme.error
    null -> Color.Transparent
}

private fun buildBubbleContexts(messages: List<ConversationMessage>): List<BubbleContext> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<BubbleContext>()
    for (i in messages.indices) {
        val msg = messages[i]
        val isSystem = msg.role == ConversationRole.SYSTEM
        if (isSystem) {
            result.add(BubbleContext(message = msg, isFirstInGroup = true, isLastInGroup = true))
            continue
        }
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        fun sameGroup(a: ConversationMessage, b: ConversationMessage): Boolean {
            if (a.role == ConversationRole.SYSTEM || b.role == ConversationRole.SYSTEM) return false
            if (a.role != b.role) return false
            if (a.role == ConversationRole.ASSISTANT && (a.kind != ConversationKind.CHAT || b.kind != ConversationKind.CHAT)) return false
            if ((b.createdAtEpochMs - a.createdAtEpochMs) > 180_000L) return false
            return true
        }
        val isSameGroupAsPrev = prev != null && sameGroup(prev, msg)
        val isSameGroupAsNext = next != null && sameGroup(msg, next)
        result.add(
            BubbleContext(
                message = msg,
                isFirstInGroup = !isSameGroupAsPrev,
                isLastInGroup = !isSameGroupAsNext,
            )
        )
    }
    return result
}

private fun buildAgentActivityText(messages: List<ConversationMessage>): String? {
    val isStreaming = messages.any { it.isStreaming }
    if (!isStreaming) return null

    val activeSystemMessage = messages.lastOrNull {
        it.role == ConversationRole.SYSTEM && it.isStreaming
    } ?: messages.lastOrNull { it.role == ConversationRole.SYSTEM }

    return when {
        activeSystemMessage == null -> "Agent is writing a response..."
        activeSystemMessage.kind == ConversationKind.FILE_CHANGE -> {
            val fileName = activeSystemMessage.filePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
            if (fileName != null) "Edited $fileName" else "Edited files"
        }
        activeSystemMessage.kind == ConversationKind.COMMAND -> {
            val title = activeSystemMessage.execution?.title
                ?: activeSystemMessage.command
                ?: "command"
            "Running: ${title.take(40)}"
        }
        activeSystemMessage.kind == ConversationKind.EXECUTION -> {
            val execution = activeSystemMessage.execution
            when {
                execution == null -> "Running activity..."
                execution.title.isBlank() -> "Running ${execution.label.lowercase()}..."
                else -> "${execution.label}: ${execution.title.take(48)}"
            }
        }
        activeSystemMessage.kind == ConversationKind.SUBAGENT_ACTION -> "Managing subagents..."
        activeSystemMessage.kind == ConversationKind.THINKING -> "Thinking..."
        activeSystemMessage.kind == ConversationKind.PLAN -> "Planning..."
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadTimelineScreen(
    state: ThreadTimelineUiState,
    onBack: () -> Unit,
    onOpenSidebar: () -> Unit,
    onRefresh: () -> Unit,
    onComposerChanged: (String) -> Unit,
    onPlanModeChanged: (Boolean) -> Unit,
    onSubagentsModeChanged: (Boolean) -> Unit,
    onSelectReviewTarget: (io.androdex.android.ComposerReviewTarget) -> Unit,
    onReviewBaseBranchChanged: (String) -> Unit,
    onRemoveReviewSelection: () -> Unit,
    onSelectFileAutocomplete: (FuzzyFileMatch) -> Unit,
    onRemoveMentionedFile: (String) -> Unit,
    onSelectSkillAutocomplete: (SkillMetadata) -> Unit,
    onRemoveMentionedSkill: (String) -> Unit,
    onSelectSlashCommand: (ComposerSlashCommand) -> Unit,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit,
    onRemoveComposerAttachment: (String) -> Unit,
    onOpenRuntime: () -> Unit,
    onOpenGitSheet: () -> Unit,
    onOpenFork: () -> Unit,
    onCompactThread: () -> Unit,
    onRollbackThread: () -> Unit,
    onCleanBackgroundTerminals: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onRestoreQueuedDraft: (String) -> Unit,
    onRemoveQueuedDraft: (String) -> Unit,
    onToolInputAnswerChanged: (String, String, String) -> Unit,
    onSubmitToolInput: (String) -> Unit,
    onUpdateGitCommitMessage: (String) -> Unit,
    onDismissGitCommit: () -> Unit,
    onSubmitGitCommit: () -> Unit,
    onUpdateGitBranchName: (String) -> Unit,
    onDismissGitBranchDialog: () -> Unit,
    onRequestCreateGitBranch: () -> Unit,
    onRequestSwitchGitBranch: (String) -> Unit,
    onUpdateGitWorktreeBranchName: (String) -> Unit,
    onUpdateGitWorktreeBaseBranch: (String) -> Unit,
    onUpdateGitWorktreeTransferMode: (GitWorktreeChangeTransferMode) -> Unit,
    onDismissGitWorktreeDialog: () -> Unit,
    onRequestCreateGitWorktree: () -> Unit,
    onRequestRemoveGitWorktree: (String, String) -> Unit,
    onDismissGitAlert: () -> Unit,
    onHandleGitAlertAction: (GitAlertAction) -> Unit,
) {
    BackHandler(onBack = onBack)
    val listState = remember { LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    var overflowMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var rollbackDialogOpen by rememberSaveable { mutableStateOf(false) }
    var cleanBackgroundTerminalsDialogOpen by rememberSaveable { mutableStateOf(false) }
    val showJumpToLatest by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) {
                false
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisibleIndex < totalItems - 1
            }
        }
    }

    LaunchedEffect(state.threadId, state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }
    val bubbleContexts = remember(state.messages) { buildBubbleContexts(state.messages) }
    val agentActivityText = remember(state.messages) { buildAgentActivityText(state.messages) }
    val gitAffordance = remember(state.git) { buildGitAffordanceUiState(state.git) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val dotColor = runStateDotColor(state.runState)
                    val infiniteTransition = rememberInfiniteTransition(label = "runDot")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "runDotAlpha",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.runState != null) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        dotColor.copy(
                                            alpha = if (state.runState == ThreadRunBadgeUiState.RUNNING) dotAlpha else 1f
                                        )
                                    ),
                            )
                        }
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        gitAffordance?.let { affordance ->
                            GitTopBarPill(
                                state = affordance,
                                onClick = onOpenGitSheet,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { overflowMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Runtime overrides") },
                                enabled = state.composer.runtimeButtonEnabled,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onOpenRuntime()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Fork thread") },
                                enabled = state.fork.isEnabled,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onOpenFork()
                                },
                            )
                            if (state.git.hasWorkingDirectory) {
                                DropdownMenuItem(
                                    text = { Text("Git") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onOpenGitSheet()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Compact context") },
                                enabled = state.compact.isEnabled,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onCompactThread()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Roll back last turn") },
                                enabled = state.rollback.isEnabled,
                                onClick = {
                                    overflowMenuExpanded = false
                                    rollbackDialogOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clean background terminals") },
                                enabled = state.backgroundTerminals.isEnabled,
                                onClick = {
                                    overflowMenuExpanded = false
                                    cleanBackgroundTerminalsDialogOpen = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        GitAlertDialog(
            state = state.git.alert,
            onDismiss = onDismissGitAlert,
            onAction = onHandleGitAlertAction,
        )
        if (rollbackDialogOpen) {
            ThreadMaintenanceDialog(
                title = "Roll back the last turn?",
                message = "This removes the latest turn from the thread history only. It does not revert any local file changes the agent already made.",
                confirmLabel = "Roll Back",
                onDismiss = { rollbackDialogOpen = false },
                onConfirm = {
                    rollbackDialogOpen = false
                    onRollbackThread()
                },
            )
        }
        if (cleanBackgroundTerminalsDialogOpen) {
            ThreadMaintenanceDialog(
                title = "Clean background terminals?",
                message = "This stops background terminal work still attached to this thread and refreshes the timeline state from the host.",
                confirmLabel = "Clean",
                onDismiss = { cleanBackgroundTerminalsDialogOpen = false },
                onConfirm = {
                    cleanBackgroundTerminalsDialogOpen = false
                    onCleanBackgroundTerminals()
                },
            )
        }
        GitCommitDialog(
            state = state.git.commitDialog,
            onDismiss = onDismissGitCommit,
            onMessageChange = onUpdateGitCommitMessage,
            onSubmit = onSubmitGitCommit,
        )
        GitBranchDialog(
            state = state.git.branchDialog,
            branchTargets = state.git.branchTargets,
            onDismiss = onDismissGitBranchDialog,
            onNameChange = onUpdateGitBranchName,
            onCreate = onRequestCreateGitBranch,
            onSwitch = onRequestSwitchGitBranch,
        )
        GitWorktreeDialog(
            state = state.git.worktreeDialog,
            branchTargets = state.git.branchTargets,
            onDismiss = onDismissGitWorktreeDialog,
            onBranchNameChange = onUpdateGitWorktreeBranchName,
            onBaseBranchChange = onUpdateGitWorktreeBaseBranch,
            onTransferModeChange = onUpdateGitWorktreeTransferMode,
            onCreate = onRequestCreateGitWorktree,
            onRemoveWorktree = onRequestRemoveGitWorktree,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            BusyIndicator(state = state.busy)
            if (state.isForkedThread) {
                ForkedThreadBanner(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    items(bubbleContexts, key = { it.message.id }) { ctx ->
                        MessageBubble(
                            message = ctx.message,
                            isFirstInGroup = ctx.isFirstInGroup,
                            isLastInGroup = ctx.isLastInGroup,
                        )
                    }
                }

                if (showJumpToLatest) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (state.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(state.messages.lastIndex)
                                }
                            }
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Jump to latest",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.queuedDrafts.isNotEmpty()) {
                QueuedDraftsCard(
                    drafts = state.queuedDrafts,
                    queuePauseMessage = state.queuePauseMessage,
                    canRestoreDrafts = state.canRestoreQueuedDrafts,
                    canPauseQueue = state.canPauseQueue,
                    canResumeQueue = state.canResumeQueue,
                    onPauseQueue = onPauseQueue,
                    onResumeQueue = onResumeQueue,
                    onRestoreDraft = onRestoreQueuedDraft,
                    onRemoveDraft = onRemoveQueuedDraft,
                )
            }

            AnimatedVisibility(visible = state.pendingToolInputs.isNotEmpty()) {
                PendingToolInputsCard(
                    requests = state.pendingToolInputs,
                    onAnswerChanged = onToolInputAnswerChanged,
                    onSubmit = onSubmitToolInput,
                )
            }

            ComposerBar(
                state = state.composer,
                activityText = agentActivityText,
                onTextChange = onComposerChanged,
                onPlanModeChanged = onPlanModeChanged,
                onSubagentsModeChanged = onSubagentsModeChanged,
                onSelectReviewTarget = onSelectReviewTarget,
                onReviewBaseBranchChanged = onReviewBaseBranchChanged,
                onRemoveReviewSelection = onRemoveReviewSelection,
                onSelectFileAutocomplete = onSelectFileAutocomplete,
                onRemoveMentionedFile = onRemoveMentionedFile,
                onSelectSkillAutocomplete = onSelectSkillAutocomplete,
                onRemoveMentionedSkill = onRemoveMentionedSkill,
                onSelectSlashCommand = onSelectSlashCommand,
                onAddCamera = onAddCamera,
                onAddGallery = onAddGallery,
                onRemoveAttachment = onRemoveComposerAttachment,
                onOpenRuntime = onOpenRuntime,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun ForkedThreadBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Forked thread",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "This conversation branched from an earlier thread so you can explore a different direction without losing the original.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun GitTopBarPill(
    state: GitAffordanceUiState,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.primaryLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            state.secondaryLabel?.let { secondary ->
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ThreadMaintenanceDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun QueuedDraftsCard(
    drafts: List<QueuedTurnDraft>,
    queuePauseMessage: String?,
    canRestoreDrafts: Boolean,
    canPauseQueue: Boolean,
    canResumeQueue: Boolean,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onRestoreDraft: (String) -> Unit,
    onRemoveDraft: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Queued follow-ups",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${drafts.size} waiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    canResumeQueue -> {
                        TextButton(onClick = onResumeQueue) {
                            Text("Resume")
                        }
                    }

                    canPauseQueue -> {
                        TextButton(onClick = onPauseQueue) {
                            Text("Pause")
                        }
                    }
                }
            }

            queuePauseMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            drafts.forEachIndexed { index, draft ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (draft.collaborationMode == CollaborationModeKind.PLAN) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = "PLAN",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (draft.subagentsSelectionEnabled) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = "SUBAGENTS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (draft.attachments.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = if (draft.attachments.size == 1) "1 PHOTO" else "${draft.attachments.size} PHOTOS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = applyingSubagentsSelection(draft.text, draft.subagentsSelectionEnabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    TextButton(
                        onClick = { onRestoreDraft(draft.id) },
                        enabled = canRestoreDrafts,
                    ) {
                        Text("Restore")
                    }

                    TextButton(
                        onClick = { onRemoveDraft(draft.id) },
                    ) {
                        Text("Remove")
                    }
                }

                if (index < drafts.lastIndex) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingToolInputsCard(
    requests: List<ToolUserInputCardUiState>,
    onAnswerChanged: (String, String, String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            requests.forEachIndexed { index, request ->
                ToolInputRequestCard(
                    request = request,
                    onAnswerChanged = onAnswerChanged,
                    onSubmit = onSubmit,
                )
                if (index < requests.lastIndex) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolInputRequestCard(
    request: ToolUserInputCardUiState,
    onAnswerChanged: (String, String, String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = request.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            request.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        request.questions.forEach { question ->
            ToolInputQuestionCard(
                requestId = request.requestId,
                question = question,
                enabled = !request.isSubmitting,
                onAnswerChanged = onAnswerChanged,
            )
        }

        Button(
            onClick = { onSubmit(request.requestId) },
            enabled = request.submitEnabled && !request.isSubmitting,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (request.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Submit")
            }
        }
    }
}

@Composable
private fun ToolInputQuestionCard(
    requestId: String,
    question: ToolUserInputQuestionUiState,
    enabled: Boolean,
    onAnswerChanged: (String, String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        question.header?.takeIf { it.isNotBlank() }?.let { header ->
            Text(
                text = header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        question.options.forEach { option ->
            OutlinedButton(
                onClick = { onAnswerChanged(requestId, question.id, option.label) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (option.isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (option.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    option.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (question.allowsCustomAnswer) {
            OutlinedTextField(
                value = question.answer,
                onValueChange = { onAnswerChanged(requestId, question.id, it) },
                enabled = enabled,
                label = {
                    Text(if (question.options.isEmpty()) "Answer" else "Other")
                },
                visualTransformation = if (question.isSecret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GitCommitDialog(
    state: GitCommitDialogState?,
    onDismiss: () -> Unit,
    onMessageChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    if (state == null) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit changes") },
        text = {
            OutlinedTextField(
                value = state.message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Commit message") },
                minLines = 2,
            )
        },
        confirmButton = {
            Button(onClick = onSubmit) {
                Text("Commit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun GitBranchDialog(
    state: GitBranchDialogState?,
    branchTargets: GitBranchesWithStatusResult?,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onSwitch: (String) -> Unit,
) {
    if (state == null) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branches") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.newBranchName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New branch name") },
                    placeholder = { Text("topic/refactor") },
                )
                branchTargets?.defaultBranch?.takeIf { it.isNotBlank() }?.let { defaultBranch ->
                    Text(
                        text = "Default branch: $defaultBranch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                branchTargets?.branches.orEmpty().take(8).forEach { branch ->
                    val path = branchTargets?.worktreePathByBranch?.get(branch)
                    val checkedOutElsewhere = branch in branchTargets?.branchesCheckedOutElsewhere.orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = branch,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when {
                                    branch == branchTargets?.currentBranch -> "Current checkout"
                                    checkedOutElsewhere && !path.isNullOrBlank() -> path
                                    checkedOutElsewhere -> "Open in another worktree"
                                    else -> "Switch checkout"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { onSwitch(branch) },
                            enabled = branch != branchTargets?.currentBranch,
                        ) {
                            Text("Switch")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = state.newBranchName.trim().isNotEmpty(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun GitWorktreeDialog(
    state: GitWorktreeDialogState?,
    branchTargets: GitBranchesWithStatusResult?,
    onDismiss: () -> Unit,
    onBranchNameChange: (String) -> Unit,
    onBaseBranchChange: (String) -> Unit,
    onTransferModeChange: (GitWorktreeChangeTransferMode) -> Unit,
    onCreate: () -> Unit,
    onRemoveWorktree: (String, String) -> Unit,
) {
    if (state == null) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Managed worktrees") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.branchName,
                    onValueChange = onBranchNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New worktree branch") },
                    placeholder = { Text("topic/mobile-git") },
                )
                Text(
                    text = "Base branch",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    branchTargets?.branches.orEmpty().take(8).forEach { branch ->
                        val emphasized = branch == state.baseBranch
                        OutlinedButton(onClick = { onBaseBranchChange(branch) }) {
                            Text(if (emphasized) "• $branch" else branch)
                        }
                    }
                }
                Text(
                    text = "Change transfer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onTransferModeChange(GitWorktreeChangeTransferMode.MOVE) }) {
                        Text(if (state.changeTransfer == GitWorktreeChangeTransferMode.MOVE) "• Move" else "Move")
                    }
                    OutlinedButton(onClick = { onTransferModeChange(GitWorktreeChangeTransferMode.COPY) }) {
                        Text(if (state.changeTransfer == GitWorktreeChangeTransferMode.COPY) "• Copy" else "Copy")
                    }
                }
                val elsewhereBranches = branchTargets?.branchesCheckedOutElsewhere.orEmpty()
                if (elsewhereBranches.isNotEmpty()) {
                    Text(
                        text = "Existing managed worktrees",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    elsewhereBranches.forEach { branch ->
                        val path = branchTargets?.worktreePathByBranch?.get(branch).orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = branch,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = path.ifBlank { "Open in another worktree" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (path.isNotBlank()) {
                                TextButton(onClick = { onRemoveWorktree(branch, path) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = state.branchName.trim().isNotEmpty() && state.baseBranch.trim().isNotEmpty(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun GitAlertDialog(
    state: GitAlertState?,
    onDismiss: () -> Unit,
    onAction: (GitAlertAction) -> Unit,
) {
    if (state == null) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.buttons.forEach { button ->
                    val actionColor = if (button.isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    OutlinedButton(
                        onClick = { onAction(button.action) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = button.label,
                            color = actionColor,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun MessageBubble(
    message: ConversationMessage,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
) {
    val isUser = message.role == ConversationRole.USER
    val isSystem = message.role == ConversationRole.SYSTEM

    if (isSystem) {
        Box(modifier = Modifier.padding(top = if (isFirstInGroup) 10.dp else 2.dp)) {
            when (message.kind) {
                ConversationKind.FILE_CHANGE -> FileChangeBubble(message)
                ConversationKind.COMMAND -> CommandBubble(message)
                ConversationKind.EXECUTION -> ExecutionBubble(message)
                ConversationKind.SUBAGENT_ACTION -> SubagentActionBubble(message)
                ConversationKind.PLAN -> PlanBubble(message)
                ConversationKind.THINKING -> ThinkingBubble(message)
                else -> SystemCapsule(message)
            }
        }
        return
    }

    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (isUser) {
        when {
            isFirstInGroup && isLastInGroup -> RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
            isFirstInGroup -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 20.dp)
            isLastInGroup -> RoundedCornerShape(20.dp, 6.dp, 6.dp, 20.dp)
            else -> RoundedCornerShape(20.dp, 6.dp, 6.dp, 20.dp)
        }
    } else {
        when {
            isFirstInGroup && isLastInGroup -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
            isFirstInGroup -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 20.dp)
            isLastInGroup -> RoundedCornerShape(6.dp, 20.dp, 20.dp, 6.dp)
            else -> RoundedCornerShape(6.dp, 20.dp, 20.dp, 6.dp)
        }
    }
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val directiveContent = remember(message.text) {
        if (isUser) {
            CodeCommentDirectiveContent(
                findings = emptyList(),
                fallbackText = message.text,
            )
        } else {
            CodeCommentDirectiveParser.parse(message.text)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInGroup) 10.dp else 2.dp),
        contentAlignment = alignment,
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (message.isStreaming) {
                    StreamingIndicator()
                }

                if (message.attachments.isNotEmpty()) {
                    MessageAttachmentStrip(attachments = message.attachments)
                }

                if (!isUser && directiveContent.findings.isNotEmpty()) {
                    directiveContent.findings.forEach { finding ->
                        ReviewFindingCard(finding = finding)
                    }
                }

                val bodyText = if (isUser) message.text else directiveContent.fallbackText
                if (bodyText.isNotBlank() || message.attachments.isEmpty()) {
                    Text(
                        text = bodyText.ifBlank { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                    )
                }

                if (isLastInGroup) {
                    Text(
                        text = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(message.createdAtEpochMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewFindingCard(finding: CodeCommentDirectiveFinding) {
    val priorityLabel = finding.priority?.let { "P$it" } ?: "Finding"
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = priorityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                finding.confidence?.let { confidence ->
                    Text(
                        text = "${(confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = finding.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = finding.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildString {
                    append(finding.file)
                    finding.startLine?.let { start ->
                        append(":")
                        append(start)
                        finding.endLine?.takeIf { it != start }?.let { end ->
                            append("-")
                            append(end)
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SystemCapsule(message: ConversationMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = message.text.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ThinkingBubble(message: ConversationMessage) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "thinkingPulse",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(600, delayMillis = index * 150),
                            RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)),
                    )
                }
            }
            Text(
                text = message.text.ifBlank { "Thinking..." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileChangeBubble(message: ConversationMessage) {
    val isCompleted = message.status?.lowercase() == "completed"
    val statusColor = if (isCompleted) Color(0xFF34D399) else Color(0xFFFBBF24)
    var expanded by remember { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Text(
                        text = message.filePath
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?: "File Change",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = (message.status ?: "changed").replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            message.filePath?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                if (!message.diffText.isNullOrBlank()) {
                    DiffView(
                        diffText = message.diffText,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                } else {
                    val summaryText = message.text
                        .removePrefix("Status: ${message.status ?: "completed"}")
                        .removePrefix("\n\n")
                        .removePrefix("Path: ${message.filePath ?: ""}")
                        .removePrefix("\n\n")
                        .trim()
                    if (summaryText.isNotBlank()) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandBubble(message: ConversationMessage) {
    ExecutionBubble(message = message)
}

@Composable
private fun ExecutionBubble(message: ConversationMessage) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.1f
    val terminalSurface = if (isDark) Color(0xFF1E1E2E) else MaterialTheme.colorScheme.surfaceContainerLowest
    val terminalHeader = if (isDark) Color(0xFF313244) else MaterialTheme.colorScheme.surfaceContainerLow
    val terminalText = if (isDark) Color(0xFFCDD6F4) else MaterialTheme.colorScheme.onSurface
    val terminalSubtext = if (isDark) Color(0xFFBAC2DE) else MaterialTheme.colorScheme.onSurfaceVariant
    val outputSurface = if (isDark) Color(0xFF111827) else MaterialTheme.colorScheme.surfaceContainerLowest
    val outputText = if (isDark) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
    val labelPill = if (isDark) Color(0xFF313244) else MaterialTheme.colorScheme.surfaceContainerHighest
    val labelPillText = if (isDark) Color(0xFFCDD6F4) else MaterialTheme.colorScheme.onSurfaceVariant
    val execution = remember(message) { message.execution ?: fallbackExecutionContent(message) }
    val normalizedStatus = execution.status.trim().lowercase()
    val isRunning = normalizedStatus == "running" || normalizedStatus == "in_progress"
    val isFailed = normalizedStatus == "failed" || normalizedStatus == "error"
    val statusColor = when {
        isRunning -> Color(0xFFFBBF24)
        isFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val titleStyle = if (execution.kind == ExecutionKind.COMMAND) {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val hasDetails = execution.summary?.isNotBlank() == true
        || execution.details.isNotEmpty()
        || execution.output?.isNotBlank() == true
    var expanded by remember(message.id) {
        mutableStateOf(message.isStreaming || isFailed)
    }

    Surface(
        color = terminalSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(
                color = terminalHeader,
                shape = if (expanded && hasDetails) {
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                } else {
                    RoundedCornerShape(12.dp)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hasDetails) { expanded = !expanded }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5F57)),
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFBD2E)),
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF28C840)),
                            )
                        }

                        Surface(
                            color = labelPill,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = execution.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = labelPillText,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Surface(
                            color = statusColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = execution.status.normalizedExecutionStatusLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = execution.title,
                            style = titleStyle,
                            color = terminalText,
                            fontWeight = FontWeight.SemiBold,
                        )

                        execution.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = terminalSubtext,
                                maxLines = if (expanded || !hasDetails) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (isRunning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = statusColor,
                            trackColor = terminalSurface,
                        )
                    } else if (hasDetails) {
                        Text(
                            text = if (expanded) "Hide details" else "Show details",
                            style = MaterialTheme.typography.labelSmall,
                            color = terminalSubtext,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && hasDetails) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    execution.details.forEach { detail ->
                        ExecutionDetailRow(
                            detail = detail,
                            labelColor = terminalSubtext,
                            textColor = terminalText,
                        )
                    }
                    execution.output?.takeIf { it.isNotBlank() }?.let { output ->
                        ExecutionOutputView(
                            output = output,
                            surfaceColor = outputSurface,
                            textColor = outputText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionDetailRow(detail: ExecutionDetail) {
    ExecutionDetailRow(
        detail = detail,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        textColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ExecutionDetailRow(
    detail: ExecutionDetail,
    labelColor: Color,
    textColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = detail.label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = detail.value,
            style = if (detail.isMonospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExecutionOutputView(
    output: String,
    surfaceColor: Color,
    textColor: Color,
) {
    val scrollState = rememberScrollState()
    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            output.lines().forEach { line ->
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = textColor,
                    softWrap = false,
                )
            }
        }
    }
}

private fun fallbackExecutionContent(message: ConversationMessage): ExecutionContent {
    val normalizedStatus = message.status ?: "completed"
    return ExecutionContent(
        kind = if (message.kind == ConversationKind.COMMAND) ExecutionKind.COMMAND else ExecutionKind.ACTIVITY,
        title = message.command ?: message.text.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank {
            if (message.kind == ConversationKind.COMMAND) "command" else "Activity"
        },
        status = normalizedStatus,
        summary = message.text.takeIf { it.isNotBlank() && it != message.command },
        output = null,
    )
}

@Composable
private fun PlanBubble(message: ConversationMessage) {
    val planSteps = message.planSteps.orEmpty()
    val completedCount = planSteps.count { it.isCompleted() }
    val activeCount = planSteps.count { it.isInProgress() }
    val planStatus = when {
        planSteps.isNotEmpty() && completedCount == planSteps.size -> "Completed"
        activeCount > 0 -> "In Progress"
        planSteps.isNotEmpty() -> "Pending"
        else -> "Updated"
    }
    val summary = message.planExplanation
        ?.takeIf { it.isNotBlank() }
        ?: message.text.takeIf { it.isNotBlank() && it != "Planning..." }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "PLAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Text(
                    text = planStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.weight(1f))

                if (planSteps.isNotEmpty()) {
                    Text(
                        text = "$completedCount/${planSteps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (planSteps.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { completedCount.toFloat() / planSteps.size.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (planSteps.isNotEmpty()) {
                planSteps.forEachIndexed { index, step ->
                    PlanStepRow(index = index + 1, step = step)
                    if (index < planSteps.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            } else if (summary == null) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PlanStepRow(
    index: Int,
    step: PlanStep,
) {
    val isComplete = step.isCompleted()
    val isActive = step.isInProgress()
    val stepColor = when {
        isComplete -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val indicator = when {
        isComplete -> "✓"
        isActive -> "•"
        else -> "$index"
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = stepColor.copy(alpha = 0.15f),
            shape = CircleShape,
            modifier = Modifier.size(22.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = indicator,
                    style = MaterialTheme.typography.labelSmall,
                    color = stepColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isComplete) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            step.status?.takeIf { it.isNotBlank() && !isComplete }?.let {
                Text(
                    text = it.normalizedPlanStatusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = stepColor,
                )
            }
        }
    }
}

@Composable
private fun SubagentActionBubble(message: ConversationMessage) {
    val action = message.subagentAction
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (message.isStreaming) {
                StreamingIndicator()
            }

            Text(
                text = action?.summaryText ?: message.text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )

            action?.let { subagentAction ->
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapsuleLabel(text = subagentAction.tool, emphasized = true)
                    CapsuleLabel(text = subagentAction.status)
                    subagentAction.model?.let { CapsuleLabel(text = it) }
                }

                subagentAction.prompt?.let { prompt ->
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }

                subagentAction.agentRows.forEach { row ->
                    SubagentAgentRow(row)
                }
            }
        }
    }
}

@Composable
private fun SubagentAgentRow(row: SubagentThreadPresentation) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                row.fallbackStatus?.let { CapsuleLabel(text = it) }
            }

            val detailParts = listOfNotNull(
                row.model?.let { model ->
                    if (row.modelIsRequestedHint) "$model requested" else model
                },
                row.fallbackMessage,
            )
            if (detailParts.isNotEmpty()) {
                Text(
                    text = detailParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CapsuleLabel(text: String, emphasized: Boolean = false) {
    Surface(
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onSecondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun PlanStep.isCompleted(): Boolean {
    return status?.trim()?.lowercase() in setOf("completed", "done", "complete")
}

private fun PlanStep.isInProgress(): Boolean {
    return status?.trim()?.lowercase() in setOf("in_progress", "active", "running")
}

private fun String.normalizedPlanStatusLabel(): String {
    return trim()
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }
}

private fun String.normalizedExecutionStatusLabel(): String {
    return trim()
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }
}

@Composable
private fun StreamingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val dotScale by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        tween(500, delayMillis = index * 120),
                        RepeatMode.Reverse,
                    ),
                    label = "streamDot$index",
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .scale(dotScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Writing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
