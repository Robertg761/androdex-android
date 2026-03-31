package io.androdex.android.ui.turn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexDivider
import io.androdex.android.ui.shared.RemodexGroupedSurface
import io.androdex.android.ui.shared.RemodexIconButton
import io.androdex.android.ui.shared.RemodexInputField
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.ThreadMaintenanceConfirmationDialog
import io.androdex.android.ui.shared.remodexExpandVertically
import io.androdex.android.ui.shared.remodexFadeIn
import io.androdex.android.ui.shared.remodexFadeOut
import io.androdex.android.ui.shared.remodexPressedState
import io.androdex.android.ui.shared.remodexShrinkVertically
import io.androdex.android.ui.shared.remodexTween
import io.androdex.android.ui.state.ThreadRunBadgeUiState
import io.androdex.android.ui.state.ThreadTimelineUiState
import io.androdex.android.ui.state.ToolUserInputCardUiState
import io.androdex.android.ui.state.ToolUserInputQuestionUiState
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

internal data class BubbleContext(
    val message: ConversationMessage,
    val isFirstInGroup: Boolean,
    val isLastInGroup: Boolean,
)

internal sealed interface TimelineBodyBlock {
    data class Paragraph(val text: String) : TimelineBodyBlock
    data class CodeFence(val language: String?, val code: String) : TimelineBodyBlock
}

private data class TimelineStatusTone(
    val pillStyle: RemodexPillStyle,
    val accent: Color,
)

@Composable
private fun runStateDotColor(runState: ThreadRunBadgeUiState?): Color = when (runState) {
    ThreadRunBadgeUiState.RUNNING -> MaterialTheme.colorScheme.primary
    ThreadRunBadgeUiState.READY -> MaterialTheme.colorScheme.tertiary
    ThreadRunBadgeUiState.FAILED -> MaterialTheme.colorScheme.error
    null -> Color.Transparent
}

internal fun buildBubbleContexts(messages: List<ConversationMessage>): List<BubbleContext> {
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

internal fun buildAgentActivityText(messages: List<ConversationMessage>): String? {
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

internal fun parseTimelineBodyBlocks(text: String): List<TimelineBodyBlock> {
    if (text.isBlank()) return emptyList()

    val fenceRegex = Regex("""(?s)```([^\n`]*)\n(.*?)\n```""")
    val blocks = mutableListOf<TimelineBodyBlock>()
    var cursor = 0

    fenceRegex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            val plainText = text.substring(cursor, match.range.first)
            plainText.split(Regex("""\n{2,}"""))
                .map { it.trim('\n') }
                .filter { it.isNotBlank() }
                .forEach { paragraph ->
                    blocks += TimelineBodyBlock.Paragraph(paragraph)
                }
        }

        val language = match.groupValues.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() }
        val code = match.groupValues.getOrNull(2).orEmpty().trim('\n')
        if (code.isNotBlank()) {
            blocks += TimelineBodyBlock.CodeFence(language = language, code = code)
        }
        cursor = match.range.last + 1
    }

    if (cursor < text.length) {
        text.substring(cursor)
            .split(Regex("""\n{2,}"""))
            .map { it.trim('\n') }
            .filter { it.isNotBlank() }
            .forEach { paragraph ->
                blocks += TimelineBodyBlock.Paragraph(paragraph)
            }
    }

    return if (blocks.isEmpty()) listOf(TimelineBodyBlock.Paragraph(text.trim())) else blocks
}

internal fun findingReferenceText(finding: CodeCommentDirectiveFinding): String {
    return buildString {
        append(finding.file)
        finding.startLine?.let { start ->
            append(":")
            append(start)
            finding.endLine?.takeIf { it != start }?.let { end ->
                append("-")
                append(end)
            }
        }
    }
}

internal fun queuedDraftMetadataLabels(draft: QueuedTurnDraft): List<String> {
    val labels = mutableListOf<String>()
    if (draft.collaborationMode == CollaborationModeKind.PLAN) {
        labels += "Plan"
    }
    if (draft.subagentsSelectionEnabled) {
        labels += "Subagents"
    }
    if (draft.attachments.isNotEmpty()) {
        labels += if (draft.attachments.size == 1) "1 photo" else "${draft.attachments.size} photos"
    }
    if (draft.mentionedFiles.isNotEmpty()) {
        labels += if (draft.mentionedFiles.size == 1) "1 file" else "${draft.mentionedFiles.size} files"
    }
    if (draft.mentionedSkills.isNotEmpty()) {
        labels += if (draft.mentionedSkills.size == 1) "1 skill" else "${draft.mentionedSkills.size} skills"
    }
    return labels
}

internal fun queuedDraftSummaryText(draftCount: Int, isPaused: Boolean): String {
    val countLabel = if (draftCount == 1) "1 queued draft" else "$draftCount queued drafts"
    return if (isPaused) "$countLabel paused" else "$countLabel waiting"
}

internal fun pendingToolInputSummary(requestCount: Int): String {
    return if (requestCount == 1) "1 request waiting" else "$requestCount requests waiting"
}

internal fun toolInputCustomFieldLabel(question: ToolUserInputQuestionUiState): String {
    return if (question.options.isEmpty()) "Answer" else "Other answer"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadTimelineScreen(
    state: ThreadTimelineUiState,
    backHandlerEnabled: Boolean = true,
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
    BackHandler(enabled = backHandlerEnabled, onBack = onBack)
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
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
            ThreadHeader(
                title = state.title,
                subtitle = state.subtitle,
                runState = state.runState,
                navigation = {
                    RemodexIconButton(
                        onClick = onBack,
                        contentDescription = "Back",
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(geometry.iconSize),
                        )
                    }
                },
                actions = {
                    gitAffordance?.let { affordance ->
                        GitTopBarPill(
                            state = affordance,
                            onClick = onOpenGitSheet,
                        )
                    }
                    RemodexIconButton(
                        onClick = onRefresh,
                        contentDescription = "Refresh",
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(geometry.iconSize),
                        )
                    }
                    Box {
                        RemodexIconButton(
                            onClick = { overflowMenuExpanded = true },
                            contentDescription = "More",
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(geometry.iconSize),
                            )
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
            )
        },
        containerColor = Color.Transparent,
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
            isBranchContextLoading = state.git.isBranchContextLoading,
            isBranchContextReady = state.git.isBranchContextReady,
            onDismiss = onDismissGitBranchDialog,
            onNameChange = onUpdateGitBranchName,
            onCreate = onRequestCreateGitBranch,
            onSwitch = onRequestSwitchGitBranch,
        )
        GitWorktreeDialog(
            state = state.git.worktreeDialog,
            branchTargets = state.git.branchTargets,
            isBranchContextLoading = state.git.isBranchContextLoading,
            isBranchContextReady = state.git.isBranchContextReady,
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = geometry.pageHorizontalPadding,
                            end = geometry.pageHorizontalPadding,
                            top = geometry.spacing18,
                            bottom = geometry.spacing24,
                        ),
                        verticalArrangement = Arrangement.spacedBy(geometry.spacing14),
                    ) {
                        if (state.isForkedThread) {
                            item(key = "forked-banner") {
                                ForkedThreadBanner()
                            }
                        }
                        items(bubbleContexts, key = { it.message.id }) { ctx ->
                            MessageBubble(
                                message = ctx.message,
                                isFirstInGroup = ctx.isFirstInGroup,
                                isLastInGroup = ctx.isLastInGroup,
                            )
                        }
                        item(key = "thread-shell-bottom-spacer") {
                            Spacer(modifier = Modifier.height(geometry.spacing12))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = geometry.pageHorizontalPadding,
                                bottom = geometry.spacing16,
                            ),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showJumpToLatest,
                            enter = remodexFadeIn(RemodexTheme.motion.microStateMillis),
                            exit = remodexFadeOut(RemodexTheme.motion.microStateMillis),
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        if (state.messages.isNotEmpty()) {
                                            listState.animateScrollToItem(state.messages.lastIndex)
                                        }
                                    }
                                },
                                shape = CircleShape,
                                containerColor = colors.secondarySurface.copy(alpha = 0.94f),
                                contentColor = colors.textPrimary,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Jump to latest",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = geometry.spacing10),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
            ) {
                AnimatedVisibility(
                    visible = state.queuedDrafts.isNotEmpty(),
                    enter = remodexFadeIn(RemodexTheme.motion.microStateMillis) +
                        remodexExpandVertically(RemodexTheme.motion.microStateMillis),
                    exit = remodexFadeOut(RemodexTheme.motion.microStateMillis) +
                        remodexShrinkVertically(RemodexTheme.motion.microStateMillis),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                            .padding(horizontal = geometry.pageHorizontalPadding),
                    ) {
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
                }

                AnimatedVisibility(
                    visible = state.pendingToolInputs.isNotEmpty(),
                    enter = remodexFadeIn(RemodexTheme.motion.microStateMillis) +
                        remodexExpandVertically(RemodexTheme.motion.microStateMillis),
                    exit = remodexFadeOut(RemodexTheme.motion.microStateMillis) +
                        remodexShrinkVertically(RemodexTheme.motion.microStateMillis),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                            .padding(horizontal = geometry.pageHorizontalPadding),
                    ) {
                        PendingToolInputsCard(
                            requests = state.pendingToolInputs,
                            onAnswerChanged = onToolInputAnswerChanged,
                            onSubmit = onSubmitToolInput,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 760.dp)
                        .padding(horizontal = geometry.pageHorizontalPadding),
                ) {
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
    }
}

@Composable
private fun ThreadHeader(
    title: String,
    subtitle: String?,
    runState: ThreadRunBadgeUiState?,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors
    val dotColor = runStateDotColor(runState)
    val infiniteTransition = rememberInfiniteTransition(label = "runDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = remodexTween(RemodexTheme.motion.pulseMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "runDotAlpha",
    )

    Surface(
        color = colors.topBarBackground,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = geometry.spacing14,
                    end = geometry.spacing14,
                    top = geometry.spacing4,
                    bottom = geometry.spacing8,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            navigation()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (subtitle == null) 0.dp else geometry.spacing2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                ) {
                    if (runState != null) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    dotColor.copy(
                                        alpha = if (runState == ThreadRunBadgeUiState.RUNNING) dotAlpha else 1f,
                                    )
                                ),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (subtitle == null) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = if (runState != null) 16.dp else 0.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

@Composable
private fun ForkedThreadBanner(modifier: Modifier = Modifier) {
    val geometry = RemodexTheme.geometry
    val colors = RemodexTheme.colors

    RemodexGroupedSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.84f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing16, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing6),
        ) {
            Surface(
                color = colors.selectedRowFill,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.widthIn(max = 128.dp),
            ) {
                Text(
                    text = "Forked thread",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = geometry.spacing10, vertical = geometry.spacing4),
                )
            }
            Text(
                text = "This conversation branched from an earlier thread so you can explore a different direction without losing the original path.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun GitTopBarPill(
    state: GitAffordanceUiState,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        color = colors.secondarySurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .remodexPressedState(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.primaryLabel,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            state.secondaryLabel?.let { secondary ->
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
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
    ThreadMaintenanceConfirmationDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.9f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing16, vertical = geometry.spacing14),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing4)) {
                    RemodexPill(
                        label = if (canResumeQueue) "Paused queue" else "Queued drafts",
                        style = if (canResumeQueue) RemodexPillStyle.Warning else RemodexPillStyle.Neutral,
                    )
                    Text(
                        text = "Queued follow-ups",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = queuedDraftSummaryText(
                            draftCount = drafts.size,
                            isPaused = canResumeQueue,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        canResumeQueue -> {
                            InlineActionPill(
                                label = "Resume",
                                onClick = onResumeQueue,
                                accent = colors.accentBlue,
                            )
                        }

                        canPauseQueue -> {
                            InlineActionPill(
                                label = "Pause",
                                onClick = onPauseQueue,
                                accent = colors.accentOrange,
                            )
                        }
                    }
                }
            }

            queuePauseMessage?.let { message ->
                RemodexGroupedSurface(
                    cornerRadius = geometry.cornerMedium,
                    tonalColor = colors.accentOrange.copy(alpha = 0.08f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = geometry.spacing12, vertical = geometry.spacing10),
                        verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    ) {
                        RemodexPill(
                            label = "Needs attention",
                            style = RemodexPillStyle.Warning,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            drafts.forEachIndexed { index, draft ->
                QueuedDraftRowCard(
                    index = index,
                    draft = draft,
                    canRestoreDraft = canRestoreDrafts,
                    onRestoreDraft = onRestoreDraft,
                    onRemoveDraft = onRemoveDraft,
                )
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.9f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing16, vertical = geometry.spacing14),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing4)) {
                RemodexPill(
                    label = "Tool input",
                    style = RemodexPillStyle.Accent,
                )
                Text(
                    text = "Action requested",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = pendingToolInputSummary(requests.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

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
                            .background(colors.hairlineDivider.copy(alpha = 0.8f)),
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        cornerRadius = geometry.cornerMedium,
        tonalColor = colors.raisedSurface.copy(alpha = 0.82f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing14),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing6)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = request.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RemodexPill(
                        label = if (request.questions.size == 1) "1 question" else "${request.questions.size} questions",
                        style = RemodexPillStyle.Neutral,
                    )
                }
                request.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }

            request.questions.forEachIndexed { index, question ->
                ToolInputQuestionCard(
                    requestId = request.requestId,
                    question = question,
                    enabled = !request.isSubmitting,
                    onAnswerChanged = onAnswerChanged,
                )
                if (index < request.questions.lastIndex) {
                    RemodexDivider(color = colors.hairlineDivider.copy(alpha = 0.9f))
                }
            }

            RemodexButton(
                onClick = { onSubmit(request.requestId) },
                enabled = request.submitEnabled && !request.isSubmitting,
                modifier = Modifier.align(Alignment.End),
                style = RemodexButtonStyle.Primary,
            ) {
                if (request.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primaryButtonForeground,
                    )
                } else {
                    Text("Submit answers")
                }
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing10)) {
        question.header?.takeIf { it.isNotBlank() }?.let { header ->
            RemodexPill(
                label = header,
                style = RemodexPillStyle.Accent,
            )
        }
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
        )

        question.options.forEach { option ->
            ToolInputOptionRow(
                option = option,
                enabled = enabled,
                onClick = { onAnswerChanged(requestId, question.id, option.label) },
            )
        }

        if (question.allowsCustomAnswer) {
            ToolInputAnswerField(
                value = question.answer,
                onValueChange = { onAnswerChanged(requestId, question.id, it) },
                enabled = enabled,
                label = toolInputCustomFieldLabel(question),
                isSecret = question.isSecret,
            )
        }
    }
}

@Composable
private fun QueuedDraftRowCard(
    index: Int,
    draft: QueuedTurnDraft,
    canRestoreDraft: Boolean,
    onRestoreDraft: (String) -> Unit,
    onRemoveDraft: (String) -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val metadataLabels = queuedDraftMetadataLabels(draft)

    RemodexGroupedSurface(
        cornerRadius = geometry.cornerMedium,
        tonalColor = colors.raisedSurface.copy(alpha = 0.84f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing12, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = colors.selectedRowFill,
                        shape = CircleShape,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Text(
                        text = "Queued reply",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineActionPill(
                        label = "Restore",
                        onClick = { onRestoreDraft(draft.id) },
                        accent = colors.accentBlue,
                        enabled = canRestoreDraft,
                    )
                    InlineActionPill(
                        label = "Remove",
                        onClick = { onRemoveDraft(draft.id) },
                        accent = colors.textSecondary,
                    )
                }
            }

            Text(
                text = applyingSubagentsSelection(draft.text, draft.subagentsSelectionEnabled),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (metadataLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(geometry.spacing6),
                ) {
                    metadataLabels.forEach { label ->
                        RemodexPill(
                            label = label,
                            style = when {
                                label == "Plan" -> RemodexPillStyle.Success
                                label == "Subagents" -> RemodexPillStyle.Accent
                                else -> RemodexPillStyle.Neutral
                            },
                        )
                    }
                }
            }

            if (draft.attachments.isNotEmpty()) {
                MessageAttachmentStrip(
                    attachments = draft.attachments,
                    tileSize = 64.dp,
                )
            }
        }
    }
}

@Composable
private fun InlineActionPill(
    label: String,
    onClick: () -> Unit,
    accent: Color,
    enabled: Boolean = true,
) {
    val colors = RemodexTheme.colors

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) accent.copy(alpha = 0.12f) else colors.disabledFill,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (enabled) accent.copy(alpha = 0.18f) else colors.hairlineDivider,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) accent else colors.disabledForeground,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ToolInputOptionRow(
    option: io.androdex.android.ui.state.ToolUserInputOptionUiState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val borderColor = if (option.isSelected) colors.accentBlue.copy(alpha = 0.28f) else colors.hairlineDivider
    val containerColor = when {
        option.isSelected -> colors.accentBlue.copy(alpha = 0.09f)
        else -> colors.inputBackground
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(geometry.cornerMedium),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = geometry.spacing12, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (option.isSelected) colors.accentBlue else colors.textPrimary,
                    fontWeight = if (option.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (option.isSelected) {
                    RemodexPill(
                        label = "Selected",
                        style = RemodexPillStyle.Accent,
                    )
                }
            }
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ToolInputAnswerField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String,
    isSecret: Boolean,
) {
    RemodexInputField(
        value = value,
        onValueChange = {
            if (enabled) {
                onValueChange(it)
            }
        },
        label = label,
        modifier = Modifier.fillMaxWidth(),
        placeholder = if (isSecret) "Enter secure response" else "Type a response",
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
    )
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
    isBranchContextLoading: Boolean,
    isBranchContextReady: Boolean,
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
                if (!isBranchContextReady) {
                    Text(
                        text = if (isBranchContextLoading) {
                            "Loading Git status..."
                        } else {
                            "Git status is still loading..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            enabled = isBranchContextReady && branch != branchTargets?.currentBranch,
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
                enabled = isBranchContextReady && state.newBranchName.trim().isNotEmpty(),
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
    isBranchContextLoading: Boolean,
    isBranchContextReady: Boolean,
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
                if (!isBranchContextReady) {
                    Text(
                        text = if (isBranchContextLoading) {
                            "Loading Git status..."
                        } else {
                            "Git status is still loading..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        OutlinedButton(
                            onClick = { onBaseBranchChange(branch) },
                            enabled = isBranchContextReady,
                        ) {
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
                    OutlinedButton(
                        onClick = { onTransferModeChange(GitWorktreeChangeTransferMode.MOVE) },
                        enabled = isBranchContextReady,
                    ) {
                        Text(if (state.changeTransfer == GitWorktreeChangeTransferMode.MOVE) "• Move" else "Move")
                    }
                    OutlinedButton(
                        onClick = { onTransferModeChange(GitWorktreeChangeTransferMode.COPY) },
                        enabled = isBranchContextReady,
                    ) {
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
                enabled = isBranchContextReady
                    && state.branchName.trim().isNotEmpty()
                    && state.baseBranch.trim().isNotEmpty(),
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
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
    val bubbleShape = remember(isUser, isFirstInGroup, isLastInGroup) {
        messageBubbleShape(
            isUser = isUser,
            isFirstInGroup = isFirstInGroup,
            isLastInGroup = isLastInGroup,
        )
    }
    val containerColor = if (isUser) {
        colors.selectedRowFill.copy(alpha = 0.94f)
    } else {
        colors.subtleGlassTint.copy(alpha = 0.82f)
    }
    val borderColor = if (isUser) {
        colors.separator.copy(alpha = 0.32f)
    } else {
        colors.hairlineDivider.copy(alpha = 0.92f)
    }
    val bodyText = if (isUser) message.text else directiveContent.fallbackText

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInGroup) 10.dp else 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
    ) {
        if (isUser) {
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 60.dp),
            )
        }
        Surface(
            shape = bubbleShape,
            color = containerColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = geometry.spacing16, vertical = geometry.spacing12),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                if (message.isStreaming) {
                    StreamingIndicator(tint = colors.accentBlue)
                }

                if (message.attachments.isNotEmpty()) {
                    MessageAttachmentStrip(attachments = message.attachments)
                }

                if (!isUser && directiveContent.findings.isNotEmpty()) {
                    directiveContent.findings.forEach { finding ->
                        ReviewFindingCard(finding = finding)
                    }
                }

                if (bodyText.isNotBlank() || message.attachments.isEmpty()) {
                    TimelineBodyContent(
                        text = bodyText.ifBlank { " " },
                        textColor = colors.textPrimary,
                        secondaryTextColor = colors.textSecondary,
                        referenceColor = colors.accentBlue,
                        codeSurface = colors.groupedBackground.copy(alpha = 0.82f),
                    )
                }

                if (isLastInGroup) {
                    Text(
                        text = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(message.createdAtEpochMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) {
                            colors.textSecondary.copy(alpha = 0.78f)
                        } else {
                            colors.textTertiary
                        },
                    )
                }
            }
        }
        if (!isUser) {
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 44.dp),
            )
        }
    }
}

@Composable
private fun ReviewFindingCard(finding: CodeCommentDirectiveFinding) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val priorityLabel = finding.priority?.let { "P$it" } ?: "Finding"
    val tone = reviewFindingTone(finding.priority)

    Surface(
        color = colors.raisedSurface.copy(alpha = 0.84f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing12, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemodexPill(label = priorityLabel, style = tone.pillStyle)
                finding.confidence?.let { confidence ->
                    Text(
                        text = "${(confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }

            Text(
                text = finding.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = finding.body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Text(
                text = findingReferenceText(finding),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = RemodexMonoFontFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = tone.accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SystemCapsule(message: ConversationMessage) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.secondarySurface.copy(alpha = 0.76f),
            shape = RoundedCornerShape(999.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Text(
                text = message.text.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing8),
            )
        }
    }
}

@Composable
private fun ThinkingBubble(message: ConversationMessage) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val pulseMillis = RemodexTheme.motion.pulseMillis
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = remodexTween(pulseMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingPulse",
    )

    TimelineSystemCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemodexPill(label = "Thinking", style = RemodexPillStyle.Accent)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = remodexTween(pulseMillis, delayMillis = index * 120),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(colors.accentBlue.copy(alpha = dotAlpha)),
                    )
                }
            }
            Text(
                text = message.text.ifBlank { "Thinking..." },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FileChangeBubble(message: ConversationMessage) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val tone = executionStatusTone(message.status)
    var expanded by remember { mutableStateOf(true) }

    TimelineSystemCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tone.accent),
            )
            Text(
                text = message.filePath
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?: "File change",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = RemodexMonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RemodexPill(
                label = (message.status ?: "changed").normalizedExecutionStatusLabel(),
                style = tone.pillStyle,
            )
        }

        message.filePath?.let { path ->
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = RemodexMonoFontFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = colors.accentBlue,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = remodexFadeIn(RemodexTheme.motion.microStateMillis) +
                remodexExpandVertically(RemodexTheme.motion.microStateMillis),
            exit = remodexFadeOut(RemodexTheme.motion.microStateMillis) +
                remodexShrinkVertically(RemodexTheme.motion.microStateMillis),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                if (!message.diffText.isNullOrBlank()) {
                    DiffView(diffText = message.diffText)
                } else {
                    fileChangeSummaryText(message)?.let { summaryText ->
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val execution = remember(message) { message.execution ?: fallbackExecutionContent(message) }
    val normalizedStatus = execution.status.trim().lowercase()
    val isRunning = normalizedStatus == "running" || normalizedStatus == "in_progress"
    val isFailed = normalizedStatus == "failed" || normalizedStatus == "error"
    val tone = executionStatusTone(execution.status)
    val titleStyle = if (execution.kind == ExecutionKind.COMMAND) {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = RemodexMonoFontFamily)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val hasDetails = execution.summary?.isNotBlank() == true
        || execution.details.isNotEmpty()
        || execution.output?.isNotBlank() == true
    var expanded by remember(message.id) {
        mutableStateOf(message.isStreaming || isFailed)
    }

    TimelineSystemCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetails) { expanded = !expanded },
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
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
                RemodexPill(label = execution.label.uppercase(), style = RemodexPillStyle.Neutral)
                Spacer(modifier = Modifier.weight(1f))
                RemodexPill(
                    label = execution.status.normalizedExecutionStatusLabel(),
                    style = tone.pillStyle,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing6)) {
                Text(
                    text = execution.title,
                    style = titleStyle,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )

                execution.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = if (expanded || !hasDetails) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = tone.accent,
                    trackColor = colors.selectedRowFill,
                )
            } else if (hasDetails) {
                Text(
                    text = if (expanded) "Hide details" else "Show details",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }

            AnimatedVisibility(
                visible = expanded && hasDetails,
                enter = remodexFadeIn(RemodexTheme.motion.microStateMillis) +
                    remodexExpandVertically(RemodexTheme.motion.microStateMillis),
                exit = remodexFadeOut(RemodexTheme.motion.microStateMillis) +
                    remodexShrinkVertically(RemodexTheme.motion.microStateMillis),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                    execution.details.forEach { detail ->
                        ExecutionDetailRow(
                            detail = detail,
                            labelColor = colors.textTertiary,
                            textColor = colors.textPrimary,
                        )
                    }
                    execution.output?.takeIf { it.isNotBlank() }?.let { output ->
                        ExecutionOutputView(
                            output = output,
                            surfaceColor = colors.groupedBackground.copy(alpha = 0.9f),
                            textColor = colors.textSecondary,
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
        labelColor = RemodexTheme.colors.textTertiary,
        textColor = RemodexTheme.colors.textPrimary,
    )
}

@Composable
private fun ExecutionDetailRow(
    detail: ExecutionDetail,
    labelColor: Color,
    textColor: Color,
) {
    val geometry = RemodexTheme.geometry
    Row(
        horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = detail.label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = detail.value,
            style = if (detail.isMonospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = RemodexMonoFontFamily)
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
    val colors = RemodexTheme.colors
    val scrollState = rememberScrollState()
    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
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
                        fontFamily = RemodexMonoFontFamily,
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
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
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

    TimelineSystemCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemodexPill(label = "Plan", style = RemodexPillStyle.Accent)
            Text(
                text = planStatus,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (planSteps.isNotEmpty()) {
                Text(
                    text = "$completedCount/${planSteps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textTertiary,
                )
            }
        }

        if (planSteps.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { completedCount.toFloat() / planSteps.size.coerceAtLeast(1).toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accentBlue,
                trackColor = colors.selectedRowFill,
            )
        }

        summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
        }

        if (planSteps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                planSteps.forEachIndexed { index, step ->
                    PlanStepRow(index = index + 1, step = step)
                }
            }
        } else if (summary == null) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun PlanStepRow(
    index: Int,
    step: PlanStep,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val isComplete = step.isCompleted()
    val isActive = step.isInProgress()
    val stepColor = when {
        isComplete -> colors.accentGreen
        isActive -> colors.accentBlue
        else -> colors.textTertiary
    }
    val indicator = when {
        isComplete -> "✓"
        isActive -> "•"
        else -> "$index"
    }

    Surface(
        color = colors.raisedSurface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = geometry.spacing12, vertical = geometry.spacing10),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing10),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                color = stepColor.copy(alpha = 0.14f),
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
            ) {
                Text(
                    text = step.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isComplete) {
                        colors.textSecondary
                    } else {
                        colors.textPrimary
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
}

@Composable
private fun SubagentActionBubble(message: ConversationMessage) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val action = message.subagentAction

    TimelineSystemCard {
        if (message.isStreaming) {
            StreamingIndicator(tint = colors.accentBlue)
        }

        Text(
            text = action?.summaryText ?: message.text,
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        action?.let { subagentAction ->
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing8),
            ) {
                CapsuleLabel(text = subagentAction.tool, emphasized = true)
                CapsuleLabel(text = subagentAction.status)
                subagentAction.model?.let { CapsuleLabel(text = it) }
            }

            subagentAction.prompt?.let { prompt ->
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                subagentAction.agentRows.forEach { row ->
                    SubagentAgentRow(row)
                }
            }
        }
    }
}

@Composable
private fun SubagentAgentRow(row: SubagentThreadPresentation) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Surface(
        color = colors.raisedSurface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = geometry.spacing12, vertical = geometry.spacing10),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing4),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
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
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun CapsuleLabel(text: String, emphasized: Boolean = false) {
    RemodexPill(
        label = text.normalizedExecutionStatusLabel(),
        style = if (emphasized) RemodexPillStyle.Accent else RemodexPillStyle.Neutral,
    )
}

@Composable
private fun TimelineBodyContent(
    text: String,
    textColor: Color,
    secondaryTextColor: Color,
    referenceColor: Color,
    codeSurface: Color,
) {
    val geometry = RemodexTheme.geometry
    val bodyBlocks = remember(text) { parseTimelineBodyBlocks(text) }

    Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
        bodyBlocks.forEach { block ->
            when (block) {
                is TimelineBodyBlock.CodeFence -> TimelineCodeFence(
                    code = block.code,
                    language = block.language,
                    surfaceColor = codeSurface,
                    textColor = textColor,
                )

                is TimelineBodyBlock.Paragraph -> {
                    val paragraphText = normalizeTimelineParagraph(block.text)
                    if (paragraphText.isNotBlank()) {
                        val isStandaloneReference = isStandaloneFileReferenceParagraph(paragraphText)
                        Text(
                            text = paragraphText,
                            style = if (isStandaloneReference) {
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = RemodexMonoFontFamily,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = when {
                                isStandaloneReference -> referenceColor
                                isListParagraph(paragraphText) -> secondaryTextColor
                                else -> textColor
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineCodeFence(
    code: String,
    language: String?,
    surfaceColor: Color,
    textColor: Color,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val scrollState = rememberScrollState()

    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = geometry.spacing10, vertical = geometry.spacing8),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing8),
        ) {
            language?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(modifier = Modifier.horizontalScroll(scrollState)) {
                code.lines().forEach { line ->
                    Text(
                        text = line.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = RemodexMonoFontFamily,
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
}

@Composable
private fun TimelineSystemCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerMedium,
        tonalColor = colors.subtleGlassTint.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = geometry.spacing14, vertical = geometry.spacing12),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
            content = content,
        )
    }
}

@Composable
private fun reviewFindingTone(priority: Int?): TimelineStatusTone {
    return when (priority) {
        0, 1 -> TimelineStatusTone(RemodexPillStyle.Error, RemodexTheme.colors.errorRed)
        2 -> TimelineStatusTone(RemodexPillStyle.Warning, RemodexTheme.colors.accentOrange)
        3 -> TimelineStatusTone(RemodexPillStyle.Accent, RemodexTheme.colors.accentBlue)
        else -> TimelineStatusTone(RemodexPillStyle.Neutral, RemodexTheme.colors.textSecondary)
    }
}

@Composable
private fun executionStatusTone(status: String?): TimelineStatusTone {
    return when (status?.trim()?.lowercase()) {
        "running", "in_progress", "active" -> TimelineStatusTone(
            RemodexPillStyle.Warning,
            RemodexTheme.colors.accentOrange,
        )

        "failed", "error", "cancelled", "stopped" -> TimelineStatusTone(
            RemodexPillStyle.Error,
            RemodexTheme.colors.errorRed,
        )

        "completed", "done", "success" -> TimelineStatusTone(
            RemodexPillStyle.Success,
            RemodexTheme.colors.accentGreen,
        )

        else -> TimelineStatusTone(RemodexPillStyle.Accent, RemodexTheme.colors.accentBlue)
    }
}

private fun messageBubbleShape(
    isUser: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
): RoundedCornerShape {
    val outer = 24.dp
    val inner = 10.dp
    return if (isUser) {
        when {
            isFirstInGroup && isLastInGroup -> RoundedCornerShape(outer)
            isFirstInGroup -> RoundedCornerShape(outer, outer, inner, outer)
            isLastInGroup -> RoundedCornerShape(outer, inner, outer, outer)
            else -> RoundedCornerShape(outer, inner, inner, outer)
        }
    } else {
        when {
            isFirstInGroup && isLastInGroup -> RoundedCornerShape(outer)
            isFirstInGroup -> RoundedCornerShape(outer, outer, outer, inner)
            isLastInGroup -> RoundedCornerShape(inner, outer, outer, outer)
            else -> RoundedCornerShape(inner, outer, outer, inner)
        }
    }
}

private fun fileChangeSummaryText(message: ConversationMessage): String? {
    return message.text
        .removePrefix("Status: ${message.status ?: "completed"}")
        .removePrefix("\n\n")
        .removePrefix("Path: ${message.filePath ?: ""}")
        .removePrefix("\n\n")
        .trim()
        .takeIf { it.isNotBlank() }
}

internal fun looksLikeFileReference(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("[") && trimmed.contains("](/")
        || trimmed.startsWith("/")
        || trimmed.startsWith("./")
}

internal fun normalizeTimelineParagraph(text: String): String {
    return text
        .lineSequence()
        .map { it.trimEnd() }
        .joinToString("\n")
        .trim('\n')
}

internal fun isStandaloneFileReferenceParagraph(text: String): Boolean {
    val significantLines = normalizeTimelineParagraph(text)
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    return significantLines.size == 1 && looksLikeFileReference(significantLines.single())
}

internal fun isListParagraph(text: String): Boolean {
    val significantLines = normalizeTimelineParagraph(text)
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (significantLines.isEmpty()) return false
    val listPattern = Regex("""^(\- |\* |\d+\.\s).+""")
    return significantLines.all { listPattern.matches(it) }
}

private fun PlanStep.isCompleted(): Boolean {
    return status?.trim()?.lowercase() in setOf("completed", "done", "complete")
}

private fun PlanStep.isInProgress(): Boolean {
    return status?.trim()?.lowercase() in setOf("in_progress", "active", "running")
}

internal fun String.normalizedPlanStatusLabel(): String {
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

internal fun String.normalizedExecutionStatusLabel(): String {
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
private fun StreamingIndicator(tint: Color = RemodexTheme.colors.accentBlue) {
    val colors = RemodexTheme.colors
    val pulseMillis = RemodexTheme.motion.pulseMillis
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val dotScale by infiniteTransition.animateFloat(
                    initialValue = 0.72f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = remodexTween(pulseMillis, delayMillis = index * 140),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "streamDot$index",
                )
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.42f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = remodexTween(pulseMillis, delayMillis = index * 140),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "streamDotAlpha$index",
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .scale(dotScale)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = dotAlpha)),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Writing",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
