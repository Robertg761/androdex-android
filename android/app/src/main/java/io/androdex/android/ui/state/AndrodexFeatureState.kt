package io.androdex.android.ui.state

import io.androdex.android.AppEnvironment
import io.androdex.android.AndrodexUiState
import io.androdex.android.ComposerSlashCommand
import io.androdex.android.ComposerReviewSelection
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.GitActionKind
import io.androdex.android.GitAlertState
import io.androdex.android.GitBranchDialogState
import io.androdex.android.GitCommitDialogState
import io.androdex.android.GitWorktreeDialogState
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.ComposerSendAvailability
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountSnapshotOrigin
import io.androdex.android.model.HostAccountStatus
import io.androdex.android.model.MAX_COMPOSER_IMAGE_ATTACHMENTS
import io.androdex.android.model.MissingNotificationThreadPrompt
import io.androdex.android.model.ModelOption
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.hasBlockingState
import io.androdex.android.model.readyAttachments
import io.androdex.android.model.requestId
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal data class AndrodexAppUiState(
    val overlay: AndrodexOverlayUiState,
    val settings: RuntimeSettingsUiState,
    val destination: AndrodexDestinationUiState,
)

internal data class AndrodexOverlayUiState(
    val approvalRequest: ApprovalRequest?,
    val missingNotificationThreadPrompt: MissingNotificationThreadPrompt?,
    val errorMessage: String?,
)

internal sealed interface AndrodexDestinationUiState {
    data class Pairing(val state: PairingScreenUiState) : AndrodexDestinationUiState

    data class Home(val state: HomeScreenUiState) : AndrodexDestinationUiState

    data class Thread(val state: ThreadTimelineUiState) : AndrodexDestinationUiState
}

internal data class ConnectionBannerUiState(
    val status: ConnectionStatus,
    val detail: String?,
    val fingerprint: String?,
)

internal data class BusyUiState(
    val isVisible: Boolean,
    val label: String?,
)

internal data class PairingScreenUiState(
    val pairingInput: String,
    val hasSavedPairing: Boolean,
    val trustedPair: TrustedPairUiState?,
    val hostAccount: HostAccountUiState?,
    val defaultRelayUrl: String?,
    val isBusy: Boolean,
    val reconnectButtonLabel: String,
    val reconnectEnabled: Boolean,
    val recoveryTitle: String,
    val recoveryMessage: String,
    val compatibilityMessage: String?,
    val connection: ConnectionBannerUiState,
)

internal data class HomeScreenUiState(
    val connection: ConnectionBannerUiState,
    val busy: BusyUiState,
    val trustedPair: TrustedPairUiState?,
    val hostAccount: HostAccountUiState?,
    val bridgeStatus: BridgeStatusUiState,
    val activeWorkspacePath: String?,
    val threadList: ThreadListPaneUiState,
    val projectPicker: ProjectPickerUiState?,
)

internal data class TrustedPairUiState(
    val title: String,
    val statusLabel: String,
    val name: String,
    val systemName: String?,
    val detail: String?,
    val relayLabel: String?,
    val fingerprint: String?,
)

internal data class BridgeStatusUiState(
    val title: String,
    val summary: String,
    val serviceTierMessage: String,
    val threadForkMessage: String,
    val updateCommand: String,
)

internal data class HostAccountUiState(
    val title: String,
    val statusLabel: String,
    val detail: String?,
    val providerLabel: String?,
    val sourceLabel: String?,
    val authControlLabel: String?,
    val bridgeVersionLabel: String?,
    val rateLimits: List<HostAccountRateLimitUiState>,
)

internal data class HostAccountRateLimitUiState(
    val name: String,
    val usageLabel: String?,
    val resetLabel: String?,
)

internal data class ThreadListPaneUiState(
    val threads: List<ThreadListItemUiState>,
    val emptyState: ThreadListEmptyStateUiState?,
)

internal data class ThreadListItemUiState(
    val id: String,
    val title: String,
    val preview: String?,
    val projectName: String,
    val updatedLabel: String?,
    val runState: ThreadRunBadgeUiState?,
    val isForked: Boolean,
)

internal data class ThreadListEmptyStateUiState(
    val title: String,
    val message: String,
    val showChooseProjectAction: Boolean,
)

internal data class ProjectPickerUiState(
    val isLoading: Boolean,
    val activeWorkspacePath: String?,
    val recentWorkspaces: List<WorkspaceRowUiState>,
    val browserPath: String,
    val browserParentPath: String?,
    val browserEntries: List<WorkspaceRowUiState>,
    val isBrowsing: Boolean,
)

internal data class WorkspaceRowUiState(
    val title: String,
    val subtitle: String,
    val active: Boolean,
    val action: WorkspaceRowAction,
)

internal enum class WorkspaceRowAction {
    ACTIVATE,
    BROWSE,
}

internal data class ThreadTimelineUiState(
    val threadId: String,
    val title: String,
    val messages: List<ConversationMessage>,
    val runState: ThreadRunBadgeUiState?,
    val isForkedThread: Boolean,
    val busy: BusyUiState,
    val git: ThreadGitUiState,
    val queuedDrafts: List<QueuedTurnDraft>,
    val queuePauseMessage: String?,
    val canRestoreQueuedDrafts: Boolean,
    val canPauseQueue: Boolean,
    val canResumeQueue: Boolean,
    val pendingToolInputs: List<ToolUserInputCardUiState>,
    val runtime: ThreadRuntimeUiState,
    val compact: ThreadActionUiState,
    val rollback: ThreadActionUiState,
    val backgroundTerminals: ThreadActionUiState,
    val fork: ThreadForkUiState,
    val composer: ComposerUiState,
)

internal data class ThreadActionUiState(
    val isEnabled: Boolean,
    val availabilityMessage: String?,
)

internal data class ToolUserInputCardUiState(
    val requestId: String,
    val title: String,
    val message: String?,
    val questions: List<ToolUserInputQuestionUiState>,
    val isSubmitting: Boolean,
    val submitEnabled: Boolean,
)

internal data class ToolUserInputQuestionUiState(
    val id: String,
    val header: String?,
    val question: String,
    val answer: String,
    val options: List<ToolUserInputOptionUiState>,
    val allowsCustomAnswer: Boolean,
    val isSecret: Boolean,
)

internal data class ToolUserInputOptionUiState(
    val label: String,
    val description: String?,
    val isSelected: Boolean,
)

internal data class ThreadGitUiState(
    val hasWorkingDirectory: Boolean,
    val availabilityMessage: String?,
    val status: GitRepoSyncResult?,
    val branchTargets: GitBranchesWithStatusResult?,
    val diffPatch: String?,
    val isRefreshing: Boolean,
    val runningAction: GitActionKind?,
    val canRunActions: Boolean,
    val commitDialog: GitCommitDialogState?,
    val branchDialog: GitBranchDialogState?,
    val worktreeDialog: GitWorktreeDialogState?,
    val alert: GitAlertState?,
)

internal data class ComposerUiState(
    val text: String,
    val attachments: List<ComposerImageAttachment>,
    val remainingAttachmentSlots: Int,
    val hasBlockingAttachmentState: Boolean,
    val mentionedFiles: List<ComposerMentionedFile>,
    val mentionedSkills: List<ComposerMentionedSkill>,
    val fileAutocompleteItems: List<FuzzyFileMatch>,
    val isFileAutocompleteVisible: Boolean,
    val isFileAutocompleteLoading: Boolean,
    val fileAutocompleteQuery: String,
    val skillAutocompleteItems: List<SkillMetadata>,
    val isSkillAutocompleteVisible: Boolean,
    val isSkillAutocompleteLoading: Boolean,
    val skillAutocompleteQuery: String,
    val slashCommandItems: List<ComposerSlashCommand>,
    val isSlashCommandAutocompleteVisible: Boolean,
    val slashCommandQuery: String,
    val inputEnabled: Boolean,
    val submitMode: ComposerSubmitMode,
    val isPlanModeEnabled: Boolean,
    val isPlanModeSupported: Boolean,
    val planModeEnabled: Boolean,
    val planModeLabel: String,
    val isSubagentsEnabled: Boolean,
    val subagentsEnabled: Boolean,
    val reviewSelection: ComposerReviewSelection?,
    val isReviewModeEnabled: Boolean,
    val reviewTarget: ComposerReviewTarget?,
    val reviewBaseBranchValue: String,
    val reviewBaseBranchLabel: String?,
    val placeholderText: String,
    val submitButtonLabel: String,
    val isSubmitting: Boolean,
    val submitEnabled: Boolean,
    val showStop: Boolean,
    val isStopping: Boolean,
    val stopEnabled: Boolean,
    val queuedCount: Int,
    val isQueuePaused: Boolean,
    val runtimeButtonLabel: String,
    val runtimeButtonEnabled: Boolean,
)

internal enum class ComposerSubmitMode {
    SEND,
    QUEUE,
}

internal enum class ThreadRunBadgeUiState {
    RUNNING,
    READY,
    FAILED,
}

internal data class RuntimeSettingsUiState(
    val isVisible: Boolean,
    val isLoading: Boolean,
    val trustedPair: TrustedPairUiState?,
    val hostAccount: HostAccountUiState?,
    val bridgeStatus: BridgeStatusUiState,
    val about: AboutUiState,
    val modelOptions: List<RuntimeSettingsOptionUiState>,
    val reasoningOptions: List<RuntimeSettingsOptionUiState>,
    val serviceTierOptions: List<RuntimeSettingsOptionUiState>,
    val accessModeOptions: List<RuntimeSettingsOptionUiState>,
    val serviceTierSupported: Boolean,
)

internal data class AboutUiState(
    val appVersionLabel: String,
    val projectUrl: String,
    val issuesUrl: String,
    val privacyPolicyUrl: String,
    val bridgeStartCommand: String,
    val bridgeUpdateCommand: String,
)

internal data class RuntimeSettingsOptionUiState(
    val value: String?,
    val title: String,
    val subtitle: String?,
    val selected: Boolean,
)

internal data class ThreadRuntimeUiState(
    val reasoningOptions: List<RuntimeSettingsOptionUiState>,
    val selectedReasoningOverride: String?,
    val serviceTierOptions: List<RuntimeSettingsOptionUiState>,
    val selectedServiceTierOverride: String?,
    val supportsServiceTier: Boolean,
    val supportsPlanMode: Boolean,
    val collaborationSummary: String,
    val accessModeLabel: String,
    val hasOverrides: Boolean,
)

internal data class ThreadForkUiState(
    val isEnabled: Boolean,
    val availabilityMessage: String?,
    val targets: List<ThreadForkTargetUiState>,
)

internal data class ThreadForkTargetUiState(
    val projectPath: String?,
    val title: String,
    val subtitle: String?,
)

internal fun AndrodexUiState.toAppUiState(
    isSettingsVisible: Boolean,
    nowEpochMs: Long = System.currentTimeMillis(),
): AndrodexAppUiState {
    return AndrodexAppUiState(
        overlay = AndrodexOverlayUiState(
            approvalRequest = pendingApproval,
            missingNotificationThreadPrompt = missingNotificationThreadPrompt,
            errorMessage = errorMessage,
        ),
        settings = toRuntimeSettingsUiState(isSettingsVisible),
        destination = when {
            selectedThreadId != null -> AndrodexDestinationUiState.Thread(
                state = toThreadTimelineUiState()
            )
            connectionStatus == ConnectionStatus.CONNECTED -> AndrodexDestinationUiState.Home(
                state = toHomeScreenUiState(nowEpochMs)
            )
            else -> AndrodexDestinationUiState.Pairing(
                state = toPairingScreenUiState()
            )
        },
    )
}

internal fun relativeTimeLabel(
    epochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val diff = nowEpochMs - epochMs
    if (diff < 0) {
        return "now"
    }

    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
    }
}

private fun AndrodexUiState.toPairingScreenUiState(): PairingScreenUiState {
    return PairingScreenUiState(
        pairingInput = pairingInput,
        hasSavedPairing = hasSavedPairing,
        trustedPair = trustedPairSnapshot.toTrustedPairUiState(connectionStatus),
        hostAccount = hostAccountSnapshot.toHostAccountUiState(),
        defaultRelayUrl = defaultRelayUrl,
        isBusy = isBusy,
        reconnectButtonLabel = reconnectButtonLabel(),
        reconnectEnabled = !isBusy && connectionStatus != ConnectionStatus.RETRYING_SAVED_PAIRING,
        recoveryTitle = pairingRecoveryTitle(),
        recoveryMessage = pairingRecoveryMessage(),
        compatibilityMessage = compatibilityMessage(),
        connection = toConnectionBannerUiState(),
    )
}

private fun AndrodexUiState.toHomeScreenUiState(nowEpochMs: Long): HomeScreenUiState {
    return HomeScreenUiState(
        connection = toConnectionBannerUiState(),
        busy = toBusyUiState(),
        trustedPair = trustedPairSnapshot.toTrustedPairUiState(connectionStatus),
        hostAccount = hostAccountSnapshot.toHostAccountUiState(),
        bridgeStatus = toBridgeStatusUiState(),
        activeWorkspacePath = activeWorkspacePath,
        threadList = toThreadListPaneUiState(nowEpochMs),
        projectPicker = toProjectPickerUiState(),
    )
}

private fun AndrodexUiState.toThreadListPaneUiState(nowEpochMs: Long): ThreadListPaneUiState {
    val items = threads.map { thread ->
        ThreadListItemUiState(
            id = thread.id,
            title = thread.title,
            preview = thread.preview,
            projectName = thread.projectName,
            updatedLabel = thread.updatedAtEpochMs?.let { relativeTimeLabel(it, nowEpochMs) },
            runState = threadRunBadge(thread.id),
            isForked = thread.forkedFromThreadId != null,
        )
    }
    val emptyState = if (items.isEmpty() && !isBusy) {
        ThreadListEmptyStateUiState(
            title = "No conversations yet",
            message = if (activeWorkspacePath == null) {
                "Choose a project to start chatting"
            } else {
                "Tap \"New Chat\" to start one"
            },
            showChooseProjectAction = activeWorkspacePath == null,
        )
    } else {
        null
    }

    return ThreadListPaneUiState(
        threads = items,
        emptyState = emptyState,
    )
}

private fun AndrodexUiState.toProjectPickerUiState(): ProjectPickerUiState? {
    if (!isProjectPickerOpen) {
        return null
    }

    val isBrowsing = workspaceBrowserPath != null || workspaceBrowserEntries.isNotEmpty()
    return ProjectPickerUiState(
        isLoading = isWorkspaceBrowserLoading,
        activeWorkspacePath = activeWorkspacePath,
        recentWorkspaces = recentWorkspaces.map { workspace ->
            WorkspaceRowUiState(
                title = workspace.name,
                subtitle = workspace.path,
                active = workspace.isActive,
                action = WorkspaceRowAction.ACTIVATE,
            )
        },
        browserPath = workspaceBrowserPath.orEmpty(),
        browserParentPath = workspaceBrowserParentPath,
        browserEntries = workspaceBrowserEntries.map { entry ->
            WorkspaceRowUiState(
                title = entry.name,
                subtitle = entry.path,
                active = entry.isActive,
                action = if (entry.source == "recent") {
                    WorkspaceRowAction.ACTIVATE
                } else {
                    WorkspaceRowAction.BROWSE
                },
            )
        },
        isBrowsing = isBrowsing,
    )
}

private fun AndrodexUiState.toThreadTimelineUiState(): ThreadTimelineUiState {
    val threadId = requireNotNull(selectedThreadId)
    val selectedThread = threads.firstOrNull { it.id == threadId }
    val isThreadRunning = threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds
    val planModeSupported = CollaborationModeKind.PLAN in collaborationModes
    val isPlanModeRequested = isComposerPlanMode || threadId in composerPlanModeByThread
    val isPlanModeEnabled = planModeSupported && isPlanModeRequested
    val isSubagentsEnabled = isComposerSubagentsEnabled || threadId in composerSubagentsByThread
    val reviewSelection = composerReviewSelectionByThread[threadId]
    val reviewBaseBranchValue = reviewSelection
        ?.takeIf { it.target == ComposerReviewTarget.BASE_BRANCH }
        ?.baseBranch
        .orEmpty()
    val reviewBaseBranchLabel = reviewSelection?.takeIf { it.target == ComposerReviewTarget.BASE_BRANCH }
        ?.baseBranch
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: gitStateByThread[threadId]?.branchTargets?.defaultBranch?.trim()?.takeIf { it.isNotEmpty() }
        ?: gitStateByThread[threadId]?.status?.currentBranch?.trim()?.takeIf { it.isNotEmpty() }
    val composerAttachments = composerAttachmentsByThread[threadId].orEmpty()
    val hasBlockingAttachmentState = composerAttachments.hasBlockingState()
    val readyComposerAttachments = composerAttachments.readyAttachments()
    val queueState = queuedDraftStateByThread[threadId]
    val queuedDrafts = queueState?.drafts.orEmpty().map { draft ->
        draft.copy(collaborationMode = draft.collaborationMode?.takeIf { it in collaborationModes })
    }
    val pendingToolInputs = pendingToolInputsByThread[threadId]
        .orEmpty()
        .values
        .map { request ->
            val answers = toolInputAnswersByRequest[request.requestId].orEmpty()
            ToolUserInputCardUiState(
                requestId = request.requestId,
                title = request.title ?: "Questions",
                message = request.message,
                questions = request.questions.map { question ->
                    val answer = answers[question.id].orEmpty()
                    ToolUserInputQuestionUiState(
                        id = question.id,
                        header = question.header,
                        question = question.question,
                        answer = answer,
                        options = question.options.map { option ->
                            ToolUserInputOptionUiState(
                                label = option.label,
                                description = option.description,
                                isSelected = answer == option.label,
                            )
                        },
                        allowsCustomAnswer = question.allowsCustomAnswer(answer),
                        isSecret = question.isSecret,
                    )
                },
                isSubmitting = request.requestId in submittingToolInputRequestIds,
                submitEnabled = request.questions.isNotEmpty() && request.questions.all { question ->
                    answers[question.id]?.trim()?.isNotEmpty() == true
                },
            )
        }
    val queueControlsEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread
    val maintenanceActionBusy = isBusy || isSendingMessage || isInterruptingSelectedThread
    val hasThreadHistory = messages.isNotEmpty()
    val workingDirectory = selectedThread?.cwd
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: activeWorkspacePath?.trim()?.takeIf { it.isNotEmpty() }
    val selectedModel = resolveSelectedModel(availableModels, selectedModelId)
    val threadRuntimeOverride = threadRuntimeOverridesByThread[threadId]
    val effectiveReasoning = resolveEffectiveReasoning(
        model = selectedModel,
        selectedReasoningEffort = selectedReasoningEffort,
        threadRuntimeOverride = threadRuntimeOverride,
    )
    val effectiveServiceTier = resolveEffectiveServiceTier(
        selectedServiceTier = selectedServiceTier,
        threadRuntimeOverride = threadRuntimeOverride,
        supportsServiceTier = supportsServiceTier,
    )
    val threadRuntime = buildThreadRuntimeUiState(
        selectedModel = selectedModel,
        selectedReasoningEffort = selectedReasoningEffort,
        selectedServiceTier = selectedServiceTier,
        selectedAccessMode = selectedAccessMode,
        supportsServiceTier = supportsServiceTier,
        collaborationModes = collaborationModes,
        threadRuntimeOverride = threadRuntimeOverride,
    )
    val forkTargets = buildThreadForkTargets(
        selectedThread = selectedThread,
        activeWorkspacePath = activeWorkspacePath,
    )
    val submitMode = if (isThreadRunning) ComposerSubmitMode.QUEUE else ComposerSubmitMode.SEND
    val sendAvailability = ComposerSendAvailability(
        isSending = isSendingMessage || isBusy || isInterruptingSelectedThread,
        isConnected = connectionStatus == ConnectionStatus.CONNECTED,
        trimmedInput = composerText.trim(),
        hasReadyImages = readyComposerAttachments.isNotEmpty(),
        hasBlockingAttachmentState = hasBlockingAttachmentState,
        hasPlanModeSelection = isPlanModeEnabled,
        hasReviewSelection = reviewSelection != null,
        hasSubagentsSelection = isSubagentsEnabled,
    )
    val gitState = gitStateByThread[threadId]
    val runningGitAction = runningGitActionByThread[threadId]
    return ThreadTimelineUiState(
        threadId = threadId,
        title = selectedThreadTitle ?: "Conversation",
        messages = messages,
        runState = threadRunBadge(threadId),
        isForkedThread = selectedThread?.forkedFromThreadId != null,
        busy = toBusyUiState(),
        git = ThreadGitUiState(
            hasWorkingDirectory = workingDirectory != null,
            availabilityMessage = when {
                workingDirectory == null -> "Bind this thread to a local checkout to use Git actions."
                connectionStatus != ConnectionStatus.CONNECTED -> "Reconnect to the host to use Git actions."
                isThreadRunning -> "Git actions pause while this thread is running."
                runningGitAction != null -> "Git action in progress."
                else -> null
            },
            status = gitState?.status,
            branchTargets = gitState?.branchTargets,
            diffPatch = gitState?.diffPatch,
            isRefreshing = gitState?.isRefreshing == true || gitState?.isLoadingBranchTargets == true,
            runningAction = runningGitAction,
            canRunActions = workingDirectory != null
                && connectionStatus == ConnectionStatus.CONNECTED
                && !isThreadRunning
                && runningGitAction == null
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread,
            commitDialog = gitCommitDialog,
            branchDialog = gitBranchDialog,
            worktreeDialog = gitWorktreeDialog,
            alert = gitAlert,
        ),
        queuedDrafts = queuedDrafts,
        queuePauseMessage = queueState?.pauseMessage,
        canRestoreQueuedDrafts = queuedDrafts.isNotEmpty()
            && queueControlsEnabled
            && composerText.isBlank()
            && composerAttachments.isEmpty()
            && composerMentionedFilesByThread[threadId].orEmpty().isEmpty()
            && composerMentionedSkillsByThread[threadId].orEmpty().isEmpty()
            && !isSubagentsEnabled
            && reviewSelection == null,
        canPauseQueue = queuedDrafts.isNotEmpty()
            && queueControlsEnabled
            && queueState?.isPaused != true,
        canResumeQueue = queuedDrafts.isNotEmpty()
            && queueControlsEnabled
            && queueState?.isPaused == true,
        pendingToolInputs = pendingToolInputs,
        runtime = threadRuntime,
        compact = ThreadActionUiState(
            isEnabled = supportsThreadCompaction
                && connectionStatus == ConnectionStatus.CONNECTED
                && !maintenanceActionBusy
                && !isThreadRunning
                && hasThreadHistory,
            availabilityMessage = when {
                !supportsThreadCompaction -> "Update the host bridge to enable native thread compaction."
                connectionStatus != ConnectionStatus.CONNECTED -> "Reconnect to the host to compact this thread."
                !hasThreadHistory -> "No thread history is available to compact yet."
                isThreadRunning -> "Wait for the current run to finish before compacting this thread."
                maintenanceActionBusy -> "Wait for the current action to finish before compacting this thread."
                else -> null
            },
        ),
        rollback = ThreadActionUiState(
            isEnabled = supportsThreadRollback
                && connectionStatus == ConnectionStatus.CONNECTED
                && !maintenanceActionBusy
                && !isThreadRunning
                && hasThreadHistory,
            availabilityMessage = when {
                !supportsThreadRollback -> "Update the host bridge to enable native thread rollback."
                connectionStatus != ConnectionStatus.CONNECTED -> "Reconnect to the host to roll back this thread."
                !hasThreadHistory -> "No thread history is available to roll back yet."
                isThreadRunning -> "Wait for the current run to finish before rolling back this thread."
                maintenanceActionBusy -> "Wait for the current action to finish before rolling back this thread."
                else -> null
            },
        ),
        backgroundTerminals = ThreadActionUiState(
            isEnabled = supportsBackgroundTerminalCleanup
                && connectionStatus == ConnectionStatus.CONNECTED
                && !maintenanceActionBusy
                && !isThreadRunning,
            availabilityMessage = when {
                !supportsBackgroundTerminalCleanup -> "Update the host bridge to enable background terminal cleanup."
                connectionStatus != ConnectionStatus.CONNECTED -> "Reconnect to the host to clean background terminals."
                isThreadRunning -> "Wait for the current run to finish before cleaning background terminals."
                maintenanceActionBusy -> "Wait for the current action to finish before cleaning background terminals."
                else -> null
            },
        ),
        fork = ThreadForkUiState(
            isEnabled = supportsThreadFork
                && connectionStatus == ConnectionStatus.CONNECTED
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread
                && !isThreadRunning,
            availabilityMessage = when {
                !supportsThreadFork -> "Update the host bridge to enable native thread forks."
                connectionStatus != ConnectionStatus.CONNECTED -> "Reconnect to the host to fork this thread."
                isThreadRunning -> "Wait for the current run to finish before forking."
                isBusy || isSendingMessage || isInterruptingSelectedThread -> "Wait for the current action to finish before forking."
                else -> null
            },
            targets = forkTargets,
        ),
        composer = ComposerUiState(
            text = composerText,
            attachments = composerAttachments,
            remainingAttachmentSlots = (MAX_COMPOSER_IMAGE_ATTACHMENTS - composerAttachments.size).coerceAtLeast(0),
            hasBlockingAttachmentState = hasBlockingAttachmentState,
            mentionedFiles = composerMentionedFilesByThread[threadId].orEmpty(),
            mentionedSkills = composerMentionedSkillsByThread[threadId].orEmpty(),
            fileAutocompleteItems = composerFileAutocompleteItems,
            isFileAutocompleteVisible = isFileAutocompleteVisible,
            isFileAutocompleteLoading = isFileAutocompleteLoading,
            fileAutocompleteQuery = fileAutocompleteQuery,
            skillAutocompleteItems = composerSkillAutocompleteItems,
            isSkillAutocompleteVisible = isSkillAutocompleteVisible,
            isSkillAutocompleteLoading = isSkillAutocompleteLoading,
            skillAutocompleteQuery = skillAutocompleteQuery,
            slashCommandItems = composerSlashCommandItems,
            isSlashCommandAutocompleteVisible = isSlashCommandAutocompleteVisible,
            slashCommandQuery = slashCommandQuery,
            inputEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            submitMode = submitMode,
            isPlanModeEnabled = isPlanModeEnabled,
            isPlanModeSupported = planModeSupported,
            planModeEnabled = planModeSupported && !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            planModeLabel = when {
                !planModeSupported -> "Plan unavailable"
                isPlanModeEnabled -> "Plan mode on"
                else -> "Plan mode"
            },
            isSubagentsEnabled = isSubagentsEnabled,
            subagentsEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            reviewSelection = reviewSelection,
            isReviewModeEnabled = reviewSelection != null,
            reviewTarget = reviewSelection?.target,
            reviewBaseBranchValue = reviewBaseBranchValue,
            reviewBaseBranchLabel = reviewBaseBranchLabel,
            placeholderText = when {
                reviewSelection?.target == ComposerReviewTarget.BASE_BRANCH && !reviewBaseBranchLabel.isNullOrEmpty() ->
                    "Review against base branch $reviewBaseBranchLabel"
                reviewSelection != null -> "Review current changes"
                isPlanModeEnabled && isSubagentsEnabled && submitMode == ComposerSubmitMode.QUEUE ->
                    "Queue a delegated plan request for when this run finishes"
                isPlanModeEnabled && isSubagentsEnabled ->
                    "Ask Codex to plan and delegate the work"
                isSubagentsEnabled && submitMode == ComposerSubmitMode.QUEUE ->
                    "Queue a delegated follow-up for when this run finishes"
                isSubagentsEnabled -> "Ask anything... @files, \$skills, /commands"
                isPlanModeEnabled && submitMode == ComposerSubmitMode.QUEUE ->
                    "Queue a plan request for when this run finishes"
                isPlanModeEnabled -> "Ask Codex to make a plan before executing"
                submitMode == ComposerSubmitMode.QUEUE -> "Queue a follow-up for when this run finishes"
                else -> "Ask anything... @files, \$skills, /commands"
            },
            submitButtonLabel = when {
                reviewSelection != null -> "Review"
                isPlanModeEnabled && isSubagentsEnabled && submitMode == ComposerSubmitMode.QUEUE -> {
                    if (queuedDrafts.isNotEmpty()) "Queue Delegate (${queuedDrafts.size + 1})" else "Queue Delegate"
                }
                isPlanModeEnabled && isSubagentsEnabled -> "Delegate"
                isPlanModeEnabled && submitMode == ComposerSubmitMode.QUEUE -> {
                    if (queuedDrafts.isNotEmpty()) "Queue Plan (${queuedDrafts.size + 1})" else "Queue Plan"
                }
                isPlanModeEnabled -> "Plan"
                isSubagentsEnabled && submitMode == ComposerSubmitMode.QUEUE -> {
                    if (queuedDrafts.isNotEmpty()) "Queue Delegate (${queuedDrafts.size + 1})" else "Queue Delegate"
                }
                isSubagentsEnabled -> "Delegate"
                submitMode == ComposerSubmitMode.QUEUE -> {
                    if (queuedDrafts.isNotEmpty()) "Queue (${queuedDrafts.size + 1})" else "Queue"
                }
                else -> "Send"
            },
            isSubmitting = isSendingMessage,
            submitEnabled = if (reviewSelection?.target == ComposerReviewTarget.BASE_BRANCH
                && reviewBaseBranchValue.trim().isEmpty()
            ) {
                false
            } else {
                !sendAvailability.isSendDisabled
            },
            showStop = isThreadRunning,
            isStopping = isInterruptingSelectedThread,
            stopEnabled = isThreadRunning && !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            queuedCount = queuedDrafts.size,
            isQueuePaused = queueState?.isPaused == true,
            runtimeButtonLabel = buildComposerRuntimeButtonLabel(
                threadRuntime = threadRuntime,
                effectiveReasoning = effectiveReasoning,
                effectiveServiceTier = effectiveServiceTier,
            ),
            runtimeButtonEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
        ),
    )
}

private fun AndrodexUiState.toRuntimeSettingsUiState(
    isVisible: Boolean,
): RuntimeSettingsUiState {
    val selectedModel = resolveSelectedModel(availableModels, selectedModelId)
    val modelOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "Auto",
                subtitle = selectedModel?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "Default: $it" },
                selected = selectedModelId == null,
            )
        )
        availableModels.forEach { model ->
            add(
                RuntimeSettingsOptionUiState(
                    value = model.stableIdentifier,
                    title = model.displayName,
                    subtitle = model.description.ifBlank { model.model },
                    selected = selectedModelId == model.stableIdentifier
                        || selectedModelId == model.model,
                )
            )
        }
    }
    val reasoningOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "Auto",
                subtitle = selectedModel?.defaultReasoningEffort?.let { "Default: $it" },
                selected = selectedReasoningEffort == null,
            )
        )
        selectedModel?.supportedReasoningEfforts.orEmpty().forEach { effort ->
            add(
                RuntimeSettingsOptionUiState(
                    value = effort.reasoningEffort,
                    title = effort.reasoningEffort,
                    subtitle = effort.description,
                    selected = selectedReasoningEffort == effort.reasoningEffort,
                )
            )
        }
    }
    val serviceTierOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "Normal",
                subtitle = "Use the standard runtime tier.",
                selected = selectedServiceTier == null,
            )
        )
        ServiceTier.entries.forEach { tier ->
            add(
                RuntimeSettingsOptionUiState(
                    value = tier.wireValue,
                    title = tier.displayName,
                    subtitle = tier.description,
                    selected = selectedServiceTier == tier,
                )
            )
        }
    }
    val accessModeOptions = AccessMode.entries.map { mode ->
        RuntimeSettingsOptionUiState(
            value = mode.wireValue,
            title = mode.menuTitle,
            subtitle = when (mode) {
                AccessMode.ON_REQUEST -> "Ask before elevated host actions."
                AccessMode.FULL_ACCESS -> "Allow full host access for supported actions."
            },
            selected = selectedAccessMode == mode,
        )
    }

    return RuntimeSettingsUiState(
        isVisible = isVisible,
        isLoading = isLoadingRuntimeConfig,
        trustedPair = trustedPairSnapshot.toTrustedPairUiState(connectionStatus),
        hostAccount = hostAccountSnapshot.toHostAccountUiState(),
        bridgeStatus = toBridgeStatusUiState(),
        about = AboutUiState(
            appVersionLabel = AppEnvironment.appVersionLabel,
            projectUrl = AppEnvironment.projectUrl,
            issuesUrl = AppEnvironment.issuesUrl,
            privacyPolicyUrl = AppEnvironment.privacyPolicyUrl,
            bridgeStartCommand = AppEnvironment.bridgeStartCommand,
            bridgeUpdateCommand = AppEnvironment.bridgeUpdateCommand,
        ),
        modelOptions = modelOptions,
        reasoningOptions = reasoningOptions,
        serviceTierOptions = serviceTierOptions,
        accessModeOptions = accessModeOptions,
        serviceTierSupported = supportsServiceTier,
    )
}

private fun AndrodexUiState.toConnectionBannerUiState(): ConnectionBannerUiState {
    return ConnectionBannerUiState(
        status = connectionStatus,
        detail = connectionDetail,
        fingerprint = secureFingerprint,
    )
}

private fun AndrodexUiState.toBusyUiState(): BusyUiState {
    return BusyUiState(
        isVisible = isBusy || isLoadingRuntimeConfig,
        label = busyLabel ?: if (isLoadingRuntimeConfig) "Loading models..." else null,
    )
}

private fun AndrodexUiState.threadRunBadge(threadId: String): ThreadRunBadgeUiState? {
    return when {
        threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds -> ThreadRunBadgeUiState.RUNNING
        threadId in failedThreadIds -> ThreadRunBadgeUiState.FAILED
        threadId in readyThreadIds -> ThreadRunBadgeUiState.READY
        else -> null
    }
}

private fun AndrodexUiState.reconnectButtonLabel(): String {
    return when (connectionStatus) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying Saved Pairing..."
        ConnectionStatus.RECONNECT_REQUIRED -> "Reconnect Saved Pairing"
        ConnectionStatus.UPDATE_REQUIRED -> "Reconnect After Updating"
        else -> "Reconnect Saved Pairing"
    }
}

private fun AndrodexUiState.pairingRecoveryTitle(): String {
    return when {
        hasSavedPairing -> "Saved Pair Recovery"
        else -> "First Pairing"
    }
}

private fun AndrodexUiState.pairingRecoveryMessage(): String {
    return when (connectionStatus) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> {
            "This phone still trusts the host. Keep Androdex running on the PC and we'll retry automatically in the foreground."
        }
        ConnectionStatus.RECONNECT_REQUIRED -> {
            "The previous trusted pair needs attention. Try reconnecting from the saved pair first, then scan a fresh QR code if the host trust changed."
        }
        ConnectionStatus.UPDATE_REQUIRED -> {
            "The Android app and host bridge are out of sync. Update the host package or app, then retry the saved pair or scan a fresh QR code."
        }
        else -> {
            "Run `${AppEnvironment.bridgeStartCommand}` on the host to show a fresh QR, or reconnect later from the saved trusted pair."
        }
    }
}

private fun AndrodexUiState.compatibilityMessage(): String? {
    return when {
        connectionStatus == ConnectionStatus.UPDATE_REQUIRED -> {
            "Update command: ${AppEnvironment.bridgeUpdateCommand}"
        }
        !supportsServiceTier || !supportsThreadFork -> {
            "This host bridge is missing some newer Android features. Updating the npm package keeps runtime controls and fork behavior aligned."
        }
        else -> null
    }
}

private fun AndrodexUiState.toBridgeStatusUiState(): BridgeStatusUiState {
    val title = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "Bridge Ready"
        ConnectionStatus.CONNECTING, ConnectionStatus.HANDSHAKING -> "Connecting To Host"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Waiting For Host"
        ConnectionStatus.RECONNECT_REQUIRED -> "Pair Needs Repair"
        ConnectionStatus.UPDATE_REQUIRED -> "Update Needed"
        ConnectionStatus.DISCONNECTED -> "Host Not Connected"
    }
    val summary = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "Codex stays on the host machine. Android is acting as the paired remote control for threads, projects, approvals, and runtime changes."
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Saved pairing is still present. Automatic reconnect stays available while the host or relay comes back."
        ConnectionStatus.RECONNECT_REQUIRED -> "The previous trusted pair is no longer enough on its own. Use saved reconnect or scan a fresh QR from the host."
        ConnectionStatus.UPDATE_REQUIRED -> "The host bridge and Android build are speaking different compatibility levels. Update the older side, then reconnect."
        else -> "Pair this phone with a host bridge, then manage local Codex workspaces and runs from Android."
    }
    return BridgeStatusUiState(
        title = title,
        summary = summary,
        serviceTierMessage = if (supportsServiceTier) {
            "Runtime speed tiers are available from Android."
        } else {
            "Runtime speed tiers are unavailable on this host bridge build."
        },
        threadForkMessage = if (supportsThreadFork) {
            "Native thread fork handoff is available."
        } else {
            "Native thread fork handoff needs a newer host bridge."
        },
        updateCommand = AppEnvironment.bridgeUpdateCommand,
    )
}

private fun TrustedPairSnapshot?.toTrustedPairUiState(
    connectionStatus: ConnectionStatus,
): TrustedPairUiState? {
    val snapshot = this ?: return null
    val shortFingerprint = snapshot.fingerprint
        ?.takeLast(12)
        ?.takeIf { it.isNotBlank() }
    val name = shortFingerprint?.let { "Host $it" } ?: "Trusted Host"
    val detailParts = buildList {
        add(
            when (connectionStatus) {
                ConnectionStatus.CONNECTED -> "Connected"
                ConnectionStatus.CONNECTING -> "Connecting"
                ConnectionStatus.HANDSHAKING -> "Pairing"
                ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying saved pair"
                ConnectionStatus.RECONNECT_REQUIRED -> "Needs re-pair"
                ConnectionStatus.UPDATE_REQUIRED -> "Update required"
                ConnectionStatus.DISCONNECTED -> "Saved pair available"
            }
        )
        snapshot.lastPairedAtEpochMs
            ?.takeIf { it > 0 }
            ?.let { "Last paired ${relativeTimeLabel(it)}" }
    }
    return TrustedPairUiState(
        title = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "Connected Pair"
            ConnectionStatus.HANDSHAKING -> "Pairing Host"
            ConnectionStatus.RECONNECT_REQUIRED -> "Previous Pair"
            else -> "Saved Pair"
        },
        statusLabel = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "Connected pair"
            ConnectionStatus.CONNECTING -> "Connecting"
            ConnectionStatus.HANDSHAKING -> "Pairing in progress"
            ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying saved pair"
            ConnectionStatus.RECONNECT_REQUIRED -> "Needs re-pair"
            ConnectionStatus.UPDATE_REQUIRED -> "Update required"
            ConnectionStatus.DISCONNECTED -> "Saved pair"
        },
        name = name,
        systemName = snapshot.deviceId.takeIf { it.isNotBlank() },
        detail = detailParts.joinToString(" • ").takeIf { it.isNotBlank() },
        relayLabel = snapshot.relayUrl?.let(::compactRelayLabel),
        fingerprint = snapshot.fingerprint,
    )
}

private fun HostAccountSnapshot?.toHostAccountUiState(): HostAccountUiState? {
    val snapshot = this ?: return null
    val statusLabel = when (snapshot.status) {
        HostAccountStatus.AUTHENTICATED -> if (snapshot.needsReauth) "Needs reauth" else "Authenticated"
        HostAccountStatus.EXPIRED -> "Expired"
        HostAccountStatus.LOGIN_PENDING -> "Login pending"
        HostAccountStatus.NOT_LOGGED_IN -> "Not logged in"
        HostAccountStatus.UNAVAILABLE -> "Unavailable"
        HostAccountStatus.UNKNOWN -> "Unknown"
    }
    val detail = buildList {
        snapshot.email?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        snapshot.planType?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        if (snapshot.status == HostAccountStatus.AUTHENTICATED && snapshot.tokenReady == false) {
            add("Token syncing")
        }
    }.joinToString(" • ").takeIf { it.isNotBlank() }

    return HostAccountUiState(
        title = "Host account",
        statusLabel = statusLabel,
        detail = detail,
        providerLabel = snapshot.authMethod?.trim()?.takeIf { it.isNotEmpty() },
        sourceLabel = when (snapshot.origin) {
            HostAccountSnapshotOrigin.NATIVE_LIVE -> "Live host updates"
            HostAccountSnapshotOrigin.BRIDGE_BOOTSTRAP -> "Bridge snapshot"
            HostAccountSnapshotOrigin.BRIDGE_FALLBACK -> "Bridge snapshot fallback"
        },
        authControlLabel = "Managed on the host",
        bridgeVersionLabel = snapshot.bridgeVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { "Bridge $it" },
        rateLimits = snapshot.rateLimits.map { rateLimit ->
            HostAccountRateLimitUiState(
                name = rateLimit.name,
                usageLabel = rateLimit.usageLabel(),
                resetLabel = rateLimit.resetsAtEpochMs?.let(::rateLimitResetLabel),
            )
        },
    )
}

private fun io.androdex.android.model.HostRateLimitBucket.usageLabel(): String? {
    return when {
        remaining != null && limit != null -> "$remaining left of $limit"
        used != null && limit != null -> "$used used of $limit"
        remaining != null -> "$remaining remaining"
        used != null -> "$used used"
        limit != null -> "Limit $limit"
        else -> null
    }
}

private fun rateLimitResetLabel(epochMs: Long): String {
    return "Resets ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))}"
}

private fun compactRelayLabel(rawUrl: String): String {
    return runCatching {
        URI(rawUrl).host?.takeIf { it.isNotBlank() } ?: rawUrl
    }.getOrDefault(rawUrl)
}

private fun resolveSelectedModel(
    models: List<ModelOption>,
    selectedModelId: String?,
): ModelOption? {
    return models.firstOrNull { it.id == selectedModelId || it.model == selectedModelId }
        ?: models.firstOrNull { it.isDefault }
        ?: models.firstOrNull()
}

private fun resolveEffectiveReasoning(
    model: ModelOption?,
    selectedReasoningEffort: String?,
    threadRuntimeOverride: ThreadRuntimeOverride?,
): String? {
    val resolvedModel = model ?: return null
    val supportedEfforts = resolvedModel.supportedReasoningEfforts.map { it.reasoningEffort }.toSet()
    if (supportedEfforts.isEmpty()) {
        return null
    }

    val overriddenReasoning = threadRuntimeOverride?.reasoningEffort
        ?.takeIf { threadRuntimeOverride.overridesReasoning && it in supportedEfforts }
    if (overriddenReasoning != null) {
        return overriddenReasoning
    }

    return when {
        selectedReasoningEffort != null && selectedReasoningEffort in supportedEfforts -> selectedReasoningEffort
        resolvedModel.defaultReasoningEffort != null && resolvedModel.defaultReasoningEffort in supportedEfforts -> resolvedModel.defaultReasoningEffort
        "medium" in supportedEfforts -> "medium"
        else -> resolvedModel.supportedReasoningEfforts.firstOrNull()?.reasoningEffort
    }
}

private fun resolveEffectiveServiceTier(
    selectedServiceTier: ServiceTier?,
    threadRuntimeOverride: ThreadRuntimeOverride?,
    supportsServiceTier: Boolean,
): ServiceTier? {
    if (!supportsServiceTier) {
        return null
    }
    if (threadRuntimeOverride?.overridesServiceTier == true) {
        return threadRuntimeOverride.serviceTier
    }
    return selectedServiceTier
}

private fun buildThreadRuntimeUiState(
    selectedModel: ModelOption?,
    selectedReasoningEffort: String?,
    selectedServiceTier: ServiceTier?,
    selectedAccessMode: AccessMode,
    supportsServiceTier: Boolean,
    collaborationModes: Set<CollaborationModeKind>,
    threadRuntimeOverride: ThreadRuntimeOverride?,
): ThreadRuntimeUiState {
    val reasoningOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "App default",
                subtitle = resolveEffectiveReasoning(
                    model = selectedModel,
                    selectedReasoningEffort = selectedReasoningEffort,
                    threadRuntimeOverride = null,
                )?.let { "Current: ${it.replaceFirstChar(Char::uppercaseChar)}" },
                selected = threadRuntimeOverride?.overridesReasoning != true,
            )
        )
        selectedModel?.supportedReasoningEfforts.orEmpty().forEach { effort ->
            add(
                RuntimeSettingsOptionUiState(
                    value = effort.reasoningEffort,
                    title = effort.reasoningEffort.replaceFirstChar(Char::uppercaseChar),
                    subtitle = effort.description,
                    selected = threadRuntimeOverride?.overridesReasoning == true
                        && threadRuntimeOverride.reasoningEffort == effort.reasoningEffort,
                )
            )
        }
    }
    val serviceTierOptions = buildList {
        add(
            RuntimeSettingsOptionUiState(
                value = null,
                title = "App default",
                subtitle = selectedServiceTier?.displayName?.let { "Current: $it" } ?: "Current: Normal",
                selected = threadRuntimeOverride?.overridesServiceTier != true,
            )
        )
        ServiceTier.entries.forEach { tier ->
            add(
                RuntimeSettingsOptionUiState(
                    value = tier.wireValue,
                    title = tier.displayName,
                    subtitle = tier.description,
                    selected = threadRuntimeOverride?.overridesServiceTier == true && threadRuntimeOverride.serviceTier == tier,
                )
            )
        }
    }
    return ThreadRuntimeUiState(
        reasoningOptions = reasoningOptions,
        selectedReasoningOverride = threadRuntimeOverride?.reasoningEffort?.takeIf { threadRuntimeOverride.overridesReasoning },
        serviceTierOptions = serviceTierOptions,
        selectedServiceTierOverride = threadRuntimeOverride?.serviceTierRawValue?.takeIf { threadRuntimeOverride.overridesServiceTier },
        supportsServiceTier = supportsServiceTier,
        supportsPlanMode = CollaborationModeKind.PLAN in collaborationModes,
        collaborationSummary = if (CollaborationModeKind.PLAN in collaborationModes) {
            "Plan mode is available for this host runtime."
        } else {
            "This host runtime has not advertised plan mode support."
        },
        accessModeLabel = selectedAccessMode.menuTitle,
        hasOverrides = threadRuntimeOverride?.isEmpty == false,
    )
}

private fun buildComposerRuntimeButtonLabel(
    threadRuntime: ThreadRuntimeUiState,
    effectiveReasoning: String?,
    effectiveServiceTier: ServiceTier?,
): String {
    if (!threadRuntime.hasOverrides) {
        return "Runtime"
    }

    val parts = mutableListOf<String>()
    if (threadRuntime.selectedReasoningOverride != null) {
        parts += effectiveReasoning?.replaceFirstChar(Char::uppercaseChar) ?: "Reasoning"
    }
    if (threadRuntime.selectedServiceTierOverride != null) {
        parts += effectiveServiceTier?.displayName ?: "Speed"
    }
    return if (parts.isEmpty()) "Runtime" else "Runtime: ${parts.joinToString(" • ")}"
}

private fun buildThreadForkTargets(
    selectedThread: ThreadSummary?,
    activeWorkspacePath: String?,
): List<ThreadForkTargetUiState> {
    val targets = mutableListOf<ThreadForkTargetUiState>()
    val currentProjectPath = selectedThread?.cwd?.trim()?.takeIf { it.isNotEmpty() }
    if (currentProjectPath != null) {
        targets += ThreadForkTargetUiState(
            projectPath = currentProjectPath,
            title = "Current project",
            subtitle = currentProjectPath,
        )
    }

    val normalizedActiveWorkspace = activeWorkspacePath?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedActiveWorkspace != null && normalizedActiveWorkspace != currentProjectPath) {
        targets += ThreadForkTargetUiState(
            projectPath = normalizedActiveWorkspace,
            title = "Active workspace",
            subtitle = normalizedActiveWorkspace,
        )
    }

    if (targets.isEmpty()) {
        targets += ThreadForkTargetUiState(
            projectPath = null,
            title = "Current runtime context",
            subtitle = "Fork without changing the host project binding.",
        )
    }

    return targets
}

private fun ToolUserInputQuestion.allowsCustomAnswer(currentAnswer: String): Boolean {
    return options.isEmpty()
        || isOther
        || (currentAnswer.isNotBlank() && options.none { option -> option.label == currentAnswer })
}
