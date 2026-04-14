package io.androdex.android.ui.state

import io.androdex.android.AppEnvironment
import io.androdex.android.AndrodexUiState
import io.androdex.android.ComposerSlashCommand
import io.androdex.android.ComposerReviewSelection
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.FreshPairingAttemptState
import io.androdex.android.FreshPairingStage
import io.androdex.android.GitActionKind
import io.androdex.android.GitAlertState
import io.androdex.android.GitBranchDialogState
import io.androdex.android.GitCommitDialogState
import io.androdex.android.GitWorktreeDialogState
import io.androdex.android.ThreadOpenPerfLogger
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.ComposerSendAvailability
import io.androdex.android.model.ConnectionStatus
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
import io.androdex.android.model.capabilityBlockReason
import io.androdex.android.model.createThreadBlockReason
import io.androdex.android.model.ThreadCapabilityAction
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.hasBlockingState
import io.androdex.android.model.readyAttachments
import io.androdex.android.model.requestId
import io.androdex.android.model.workspacePresentationNotice
import io.androdex.android.timeline.ThreadTimelineRenderSnapshot
import io.androdex.android.timeline.buildThreadTimelineRenderSnapshot
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
    data class Onboarding(val state: FirstPairingOnboardingUiState) : AndrodexDestinationUiState

    data class Pairing(val state: PairingScreenUiState) : AndrodexDestinationUiState

    data class Home(val state: HomeScreenUiState) : AndrodexDestinationUiState

    data class Thread(val state: ThreadTimelineUiState) : AndrodexDestinationUiState
}

internal data class ConnectionBannerUiState(
    val status: ConnectionStatus,
    val detail: String?,
    val fingerprint: String?,
    val presentationOverride: ConnectionBannerOverrideUiState? = null,
)

internal data class ConnectionBannerOverrideUiState(
    val title: String,
    val badgeLabel: String,
    val tone: SharedStatusTone,
    val guidance: String? = null,
)

internal enum class SharedStatusTone {
    Neutral,
    Accent,
    Success,
    Warning,
    Error,
}

internal data class BusyUiState(
    val isVisible: Boolean,
    val label: String?,
)

internal data class FirstPairingOnboardingUiState(
    val codexInstallCommand: String,
    val bridgeInstallCommand: String,
    val bridgeStartCommand: String,
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
    val hostRuntimeTargetOptions: List<RuntimeSettingsOptionUiState>,
    val activeWorkspacePath: String?,
    val createThreadSupported: Boolean = true,
    val createThreadBlockedReason: String? = null,
    val threadList: ThreadListPaneUiState,
    val projectPicker: ProjectPickerUiState?,
)

internal data class TrustedPairUiState(
    val title: String,
    val statusLabel: String,
    val tone: SharedStatusTone = SharedStatusTone.Neutral,
    val name: String,
    val systemName: String?,
    val detail: String?,
    val relayLabel: String?,
    val fingerprint: String?,
    val hasSavedRelaySession: Boolean,
)

internal data class BridgeStatusUiState(
    val title: String,
    val statusLabel: String,
    val tone: SharedStatusTone = SharedStatusTone.Neutral,
    val summary: String,
    val runtimeTargetLabel: String? = null,
    val backendProviderLabel: String? = null,
    val runtimeSyncLabel: String? = null,
    val runtimeSyncDetail: String? = null,
    val runtimeFailureDetail: String? = null,
    val serviceTierMessage: String,
    val threadForkMessage: String,
    val updateCommand: String,
)

internal data class HostAccountUiState(
    val title: String,
    val statusLabel: String,
    val tone: SharedStatusTone = SharedStatusTone.Neutral,
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
    val activeWorkspacePath: String?,
    val threads: List<ThreadListItemUiState>,
    val isLoading: Boolean,
    val showLoadingOverlay: Boolean,
    val emptyState: ThreadListEmptyStateUiState?,
    val createThreadSupported: Boolean = true,
    val createThreadBlockedReason: String? = null,
)

internal data class ThreadListItemUiState(
    val id: String,
    val title: String,
    val preview: String?,
    val projectName: String,
    val projectPath: String?,
    val projectPathAvailable: Boolean,
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
    val subtitle: String?,
    val workspaceNotice: String?,
    val timeline: ThreadTimelineRenderSnapshot,
    val focusedTurnId: String?,
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
    val availabilityMessage: String?,
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
    val isBranchContextLoading: Boolean,
    val isBranchContextReady: Boolean,
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
    val availabilityMessage: String?,
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
    val hostRuntimeTargetOptions: List<RuntimeSettingsOptionUiState>,
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
    val enabled: Boolean = true,
    val availabilityMessage: String? = null,
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
            shouldShowFirstPairingOnboarding() -> AndrodexDestinationUiState.Onboarding(
                state = toFirstPairingOnboardingUiState()
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
    val effectiveConnectionStatus = effectivePairingConnectionStatus()
    return PairingScreenUiState(
        pairingInput = pairingInput,
        hasSavedPairing = hasSavedPairing,
        trustedPair = trustedPairSnapshot.toTrustedPairUiState(effectiveConnectionStatus),
        hostAccount = hostAccountSnapshot.toHostAccountUiState(),
        defaultRelayUrl = defaultRelayUrl,
        isBusy = isBusy,
        reconnectButtonLabel = reconnectButtonLabel(),
        reconnectEnabled = !isBusy
            && connectionStatus != ConnectionStatus.RETRYING_SAVED_PAIRING
            && connectionStatus != ConnectionStatus.UPDATE_REQUIRED
            && freshPairingAttempt == null,
        recoveryTitle = pairingRecoveryTitle(),
        recoveryMessage = pairingRecoveryMessage(),
        compatibilityMessage = compatibilityMessage(),
        connection = toPairingConnectionBannerUiState(),
    )
}

private fun AndrodexUiState.toFirstPairingOnboardingUiState(): FirstPairingOnboardingUiState {
    return FirstPairingOnboardingUiState(
        codexInstallCommand = "npm install -g @openai/codex@latest",
        bridgeInstallCommand = "npm install -g androdex@latest",
        bridgeStartCommand = AppEnvironment.bridgeStartCommand,
    )
}

internal fun AndrodexUiState.toHomeScreenUiState(nowEpochMs: Long = System.currentTimeMillis()): HomeScreenUiState {
    val threadList = toThreadListPaneUiState(nowEpochMs)
    val currentRuntimeMetadata = resolveCurrentHostRuntimeMetadata()
    return HomeScreenUiState(
        connection = toConnectionBannerUiState(),
        busy = toBusyUiState(),
        trustedPair = trustedPairSnapshot.toTrustedPairUiState(connectionStatus),
        hostAccount = hostAccountSnapshot.toHostAccountUiState(),
        bridgeStatus = toBridgeStatusUiState(),
        hostRuntimeTargetOptions = buildHostRuntimeTargetOptions(currentRuntimeMetadata),
        activeWorkspacePath = activeWorkspacePath,
        createThreadSupported = threadList.createThreadSupported,
        createThreadBlockedReason = threadList.createThreadBlockedReason,
        threadList = threadList,
        projectPicker = toProjectPickerUiState(),
    )
}

private fun AndrodexUiState.toThreadListPaneUiState(nowEpochMs: Long): ThreadListPaneUiState {
    val createThreadBlockedReason = hostRuntimeMetadata.createThreadBlockReason()
    val createThreadSupported = createThreadBlockedReason == null
    val items = threads.map { thread ->
        ThreadListItemUiState(
            id = thread.id,
            title = thread.title,
            preview = thread.preview,
            projectName = thread.projectName,
            projectPath = thread.cwd?.trim()?.takeIf { it.isNotEmpty() },
            projectPathAvailable = thread.threadCapabilities?.workspaceAvailable
                ?: (thread.cwd?.trim()?.isNotEmpty() == true),
            updatedLabel = thread.updatedAtEpochMs?.let { relativeTimeLabel(it, nowEpochMs) },
            runState = threadRunBadge(thread.id),
            isForked = thread.forkedFromThreadId != null,
        )
    }
    val isLoading = items.isEmpty() && !isBusy && (isLoadingThreadList || !hasLoadedThreadList)
    val showLoadingOverlay = items.isNotEmpty() && !isBusy && isLoadingThreadList
    val emptyState = if (items.isEmpty() && !isBusy && hasLoadedThreadList && !isLoadingThreadList) {
        ThreadListEmptyStateUiState(
            title = "No conversations yet",
            message = if (activeWorkspacePath == null) {
                "Choose a project to start chatting"
            } else if (!createThreadSupported) {
                createThreadBlockedReason
            } else {
                "Use the active project to start a chat."
            },
            showChooseProjectAction = activeWorkspacePath == null,
        )
    } else {
        null
    }

    return ThreadListPaneUiState(
        activeWorkspacePath = activeWorkspacePath,
        threads = items,
        isLoading = isLoading,
        showLoadingOverlay = showLoadingOverlay,
        emptyState = emptyState,
        createThreadSupported = createThreadSupported,
        createThreadBlockedReason = createThreadBlockedReason,
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
    val timelineSnapshot = selectedThreadRenderSnapshot ?: buildThreadTimelineRenderSnapshot(
        threadId = threadId,
        messageRevision = 0L,
        messages = messages,
    )
    return ThreadOpenPerfLogger.measure(
        threadId = threadId,
        stage = "AndrodexFeatureState.toThreadTimelineUiState",
        extra = { "messages=${timelineSnapshot.messageCount} gitLoaded=${gitStateByThread[threadId] != null}" },
    ) {
        buildThreadTimelineUiState(
            threadId = threadId,
            timelineSnapshot = timelineSnapshot,
        )
    }
}

private fun AndrodexUiState.buildThreadTimelineUiState(
    threadId: String,
    timelineSnapshot: ThreadTimelineRenderSnapshot,
): ThreadTimelineUiState {
    val selectedThread = threads.firstOrNull { it.id == threadId }
    val turnStartBlockReason = selectedThread?.capabilityBlockReason(ThreadCapabilityAction.TURN_START)
    val turnInterruptBlockReason = selectedThread?.capabilityBlockReason(ThreadCapabilityAction.TURN_INTERRUPT)
    val toolInputBlockReason = selectedThread?.capabilityBlockReason(ThreadCapabilityAction.TOOL_INPUT_RESPONSES)
    val backgroundTerminalCleanupBlockReason = selectedThread?.capabilityBlockReason(ThreadCapabilityAction.BACKGROUND_TERMINAL_CLEANUP)
    val rollbackBlockReason = selectedThread?.capabilityBlockReason(ThreadCapabilityAction.CHECKPOINT_ROLLBACK)
    val isThreadRunning = threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds
    val planModeSupported = CollaborationModeKind.PLAN in collaborationModes
    val isPlanModeRequested = isComposerPlanMode || threadId in composerPlanModeByThread
    val isPlanModeEnabled = planModeSupported && isPlanModeRequested
    val isSubagentsEnabled = isComposerSubagentsEnabled || threadId in composerSubagentsByThread
    val reviewSelection = composerReviewSelectionByThread[threadId]
    val composerCapabilityBlockReason = if (isThreadRunning) {
        turnInterruptBlockReason ?: turnStartBlockReason
    } else {
        turnStartBlockReason
    }
    val isComposerCapabilityBlocked = composerCapabilityBlockReason != null
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
                submitEnabled = toolInputBlockReason == null
                    && request.questions.isNotEmpty()
                    && request.questions.all { question ->
                        answers[question.id]?.trim()?.isNotEmpty() == true
                    },
                availabilityMessage = toolInputBlockReason,
            )
    }
    val queueControlsEnabled = !isBusy && !isSendingMessage && !isInterruptingSelectedThread
    val maintenanceActionBusy = isBusy || isSendingMessage || isInterruptingSelectedThread
    val hasThreadHistory = timelineSnapshot.messageCount > 0
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
    val hasGitBranchContext = gitState?.status != null && gitState.branchTargets != null
    val isGitBranchContextLoading = gitState?.isLoadingBranchTargets == true
    val isGitRefreshInFlight = (gitState?.isRefreshing == true || gitState?.isLoadingBranchTargets == true) &&
        gitState.refreshWorkingDirectory == workingDirectory
    return ThreadTimelineUiState(
        threadId = threadId,
        title = selectedThreadTitle ?: "Conversation",
        subtitle = selectedThread?.projectName
            ?.takeIf { it.isNotBlank() && it != (selectedThreadTitle ?: "Conversation") },
        workspaceNotice = selectedThread?.workspacePresentationNotice(),
        timeline = timelineSnapshot,
        focusedTurnId = focusedTurnId,
        runState = threadRunBadge(threadId),
        isForkedThread = selectedThread?.forkedFromThreadId != null,
        busy = toBusyUiState(),
        git = ThreadGitUiState(
            hasWorkingDirectory = workingDirectory != null,
            availabilityMessage = when {
                workingDirectory == null -> "Bind this thread to a local checkout to use Git actions."
                connectionStatus != ConnectionStatus.CONNECTED -> "Reconnect to the host to use Git actions."
                isThreadRunning -> "Git actions pause while this thread is running."
                isGitRefreshInFlight -> "Refreshing Git status..."
                runningGitAction != null -> "Git action in progress."
                else -> null
            },
            status = gitState?.status,
            branchTargets = gitState?.branchTargets,
            diffPatch = gitState?.diffPatch,
            isRefreshing = gitState?.isRefreshing == true || gitState?.isLoadingBranchTargets == true,
            isBranchContextLoading = isGitBranchContextLoading,
            isBranchContextReady = hasGitBranchContext && !isGitBranchContextLoading,
            runningAction = runningGitAction,
            canRunActions = workingDirectory != null
                && connectionStatus == ConnectionStatus.CONNECTED
                && !isThreadRunning
                && !isGitRefreshInFlight
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
                && rollbackBlockReason == null
                && connectionStatus == ConnectionStatus.CONNECTED
                && !maintenanceActionBusy
                && !isThreadRunning
                && hasThreadHistory,
            availabilityMessage = when {
                !supportsThreadRollback -> "Update the host bridge to enable native thread rollback."
                rollbackBlockReason != null -> rollbackBlockReason
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
                && backgroundTerminalCleanupBlockReason == null
                && !maintenanceActionBusy
                && !isThreadRunning,
            availabilityMessage = when {
                !supportsBackgroundTerminalCleanup -> "Update the host bridge to enable background terminal cleanup."
                backgroundTerminalCleanupBlockReason != null -> backgroundTerminalCleanupBlockReason
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
            inputEnabled = !isComposerCapabilityBlocked && !isBusy && !isSendingMessage && !isInterruptingSelectedThread,
            submitMode = submitMode,
            isPlanModeEnabled = isPlanModeEnabled,
            isPlanModeSupported = planModeSupported,
            planModeEnabled = planModeSupported
                && !isComposerCapabilityBlocked
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread,
            planModeLabel = when {
                !planModeSupported -> "Plan unavailable"
                isPlanModeEnabled -> "Plan mode on"
                else -> "Plan mode"
            },
            isSubagentsEnabled = isSubagentsEnabled,
            subagentsEnabled = !isComposerCapabilityBlocked
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread,
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
                isSubagentsEnabled -> "Ask anything..."
                isPlanModeEnabled && submitMode == ComposerSubmitMode.QUEUE ->
                    "Queue a plan request for when this run finishes"
                isPlanModeEnabled -> "Ask Codex to make a plan before executing"
                submitMode == ComposerSubmitMode.QUEUE -> "Queue a follow-up for when this run finishes"
                else -> "Ask anything..."
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
                !isComposerCapabilityBlocked && !sendAvailability.isSendDisabled
            },
            availabilityMessage = composerCapabilityBlockReason,
            showStop = isThreadRunning,
            isStopping = isInterruptingSelectedThread,
            stopEnabled = isThreadRunning
                && turnInterruptBlockReason == null
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread,
            queuedCount = queuedDrafts.size,
            isQueuePaused = queueState?.isPaused == true,
            runtimeButtonLabel = buildComposerRuntimeButtonLabel(
                threadRuntime = threadRuntime,
                effectiveReasoning = effectiveReasoning,
                effectiveServiceTier = effectiveServiceTier,
            ),
            runtimeButtonEnabled = !isComposerCapabilityBlocked
                && !isBusy
                && !isSendingMessage
                && !isInterruptingSelectedThread,
        ),
    )
}

private fun AndrodexUiState.toRuntimeSettingsUiState(
    isVisible: Boolean,
): RuntimeSettingsUiState {
    val currentRuntimeMetadata = resolveCurrentHostRuntimeMetadata()
    val hostRuntimeTargetOptions = buildHostRuntimeTargetOptions(currentRuntimeMetadata)
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
        hostRuntimeTargetOptions = hostRuntimeTargetOptions,
        modelOptions = modelOptions,
        reasoningOptions = reasoningOptions,
        serviceTierOptions = serviceTierOptions,
        accessModeOptions = accessModeOptions,
        serviceTierSupported = supportsServiceTier,
    )
}

private fun AndrodexUiState.resolveCurrentHostRuntimeMetadata() = hostRuntimeMetadata

private fun AndrodexUiState.currentHostRuntimeTarget(): String =
    resolveCurrentHostRuntimeMetadata()?.runtimeTarget?.trim()?.takeIf { it.isNotEmpty() } ?: "codex-native"

private fun buildHostRuntimeTargetOptions(
    metadata: io.androdex.android.model.HostRuntimeMetadata?,
): List<RuntimeSettingsOptionUiState> {
    val currentRuntimeTarget = metadata?.runtimeTarget?.trim()?.takeIf { it.isNotEmpty() } ?: "codex-native"
    val metadataOptions = metadata?.runtimeTargetOptions.orEmpty()
    if (metadataOptions.isNotEmpty()) {
        return metadataOptions.map { option ->
            RuntimeSettingsOptionUiState(
                value = option.value,
                title = option.title,
                subtitle = resolveRuntimeTargetOptionSubtitle(
                    baseSubtitle = option.subtitle,
                    enabled = option.enabled,
                    availabilityMessage = option.availabilityMessage,
                ),
                selected = option.selected || option.value == currentRuntimeTarget,
                enabled = option.selected || option.enabled,
                availabilityMessage = option.availabilityMessage,
            )
        }
    }

    return listOf(
        RuntimeSettingsOptionUiState(
            value = "codex-native",
            title = "Codex",
            subtitle = "Use the normal host-local Codex runtime.",
            selected = currentRuntimeTarget == "codex-native",
        ),
        RuntimeSettingsOptionUiState(
            value = "t3-server",
            title = "Androdex Server",
            subtitle = "Attach the host bridge to the local Androdex Server runtime.",
            selected = currentRuntimeTarget == "t3-server",
        ),
    )
}

private fun resolveRuntimeTargetOptionSubtitle(
    baseSubtitle: String?,
    enabled: Boolean,
    availabilityMessage: String?,
): String? {
    val normalizedSubtitle = baseSubtitle?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedAvailability = availabilityMessage?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        enabled || normalizedAvailability == null -> normalizedSubtitle
        normalizedSubtitle == null -> normalizedAvailability
        else -> "$normalizedSubtitle\n$normalizedAvailability"
    }
}

private fun AndrodexUiState.toConnectionBannerUiState(): ConnectionBannerUiState {
    return ConnectionBannerUiState(
        status = connectionStatus,
        detail = connectionDetail,
        fingerprint = secureFingerprint,
    )
}

private fun AndrodexUiState.toPairingConnectionBannerUiState(): ConnectionBannerUiState {
    val freshPairingAttempt = freshPairingAttempt
    return ConnectionBannerUiState(
        status = when (freshPairingAttempt?.stage) {
            FreshPairingStage.SCANNER_OPEN,
            FreshPairingStage.PAYLOAD_CAPTURED -> ConnectionStatus.CONNECTING
            FreshPairingStage.CONNECTING -> ConnectionStatus.HANDSHAKING
            null -> connectionStatus
        },
        detail = freshPairingAttempt?.toDetailMessage() ?: connectionDetail,
        fingerprint = secureFingerprint,
        presentationOverride = freshPairingAttempt?.toConnectionBannerOverride(),
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
    if (freshPairingAttempt != null) {
        return "Reconnect Saved Pairing"
    }
    return when (connectionStatus) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying Saved Pairing..."
        ConnectionStatus.TRUST_BLOCKED -> "Repair With Fresh QR"
        ConnectionStatus.RECONNECT_REQUIRED -> "Reconnect Saved Pairing"
        ConnectionStatus.UPDATE_REQUIRED -> "Reconnect After Updating"
        ConnectionStatus.DISCONNECTED -> if (trustedPairSnapshot?.hasSavedRelaySession == false) {
            "Resolve Live Session"
        } else {
            "Reconnect Saved Pairing"
        }
        else -> "Reconnect Saved Pairing"
    }
}

private fun AndrodexUiState.pairingRecoveryTitle(): String {
    if (freshPairingAttempt != null) {
        return "Fresh Pairing"
    }
    return when {
        hasSavedPairing -> "Saved Pair Recovery"
        else -> "First Pairing"
    }
}

private fun AndrodexUiState.shouldShowFirstPairingOnboarding(): Boolean {
    return isFirstPairingOnboardingActive
        && !hasSavedPairing
        && trustedPairSnapshot == null
}

private fun AndrodexUiState.pairingRecoveryMessage(): String {
    freshPairingAttempt?.let { attempt ->
        return when (attempt.stage) {
            FreshPairingStage.SCANNER_OPEN ->
                "A fresh QR scan is in progress. Saved reconnect is paused until the scanner returns or you cancel."
            FreshPairingStage.PAYLOAD_CAPTURED ->
                "A fresh pairing payload is loaded. Connect with it to replace the stale reconnect, or choose reconnect manually if you want the old host."
            FreshPairingStage.CONNECTING ->
                "A fresh pairing payload is connecting now. Saved reconnect stays paused until this attempt succeeds or fails."
        }
    }
    return when (connectionStatus) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> {
            "This phone still trusts the host. Keep Androdex running on the PC and we'll retry automatically in the foreground."
        }
        ConnectionStatus.TRUST_BLOCKED -> {
            "This phone cannot read its saved trusted identity, so automatic reconnect is blocked locally. Repair with a fresh QR code or forget the trusted host on this phone."
        }
        ConnectionStatus.RECONNECT_REQUIRED -> {
            "The previous trusted pair needs attention. Trusted-host details were preserved, but this phone needs repair before a secure reconnect."
        }
        ConnectionStatus.UPDATE_REQUIRED -> {
            "The Android app and host bridge are out of sync. Update the older side, then resolve a fresh live session without throwing away the trusted host."
        }
        ConnectionStatus.DISCONNECTED -> {
            if (trustedPairSnapshot != null && !trustedPairSnapshot.hasSavedRelaySession) {
                "This phone still knows the trusted host, but the last live relay session was cleared. Reconnect to resolve a fresh live session without rescanning."
            } else {
                "Run `${AppEnvironment.bridgeStartCommand}` on the host to show a fresh QR, or reconnect later from the saved trusted pair."
            }
        }
        else -> "Run `${AppEnvironment.bridgeStartCommand}` on the host to show a fresh QR, or reconnect later from the saved trusted pair."
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

private fun AndrodexUiState.effectivePairingConnectionStatus(): ConnectionStatus {
    return when (freshPairingAttempt?.stage) {
        FreshPairingStage.SCANNER_OPEN,
        FreshPairingStage.PAYLOAD_CAPTURED -> ConnectionStatus.CONNECTING
        FreshPairingStage.CONNECTING -> ConnectionStatus.HANDSHAKING
        null -> connectionStatus
    }
}

private fun FreshPairingAttemptState.toConnectionBannerOverride(): ConnectionBannerOverrideUiState {
    return when (stage) {
        FreshPairingStage.SCANNER_OPEN -> ConnectionBannerOverrideUiState(
            title = "Fresh pairing ready",
            badgeLabel = "Scanner",
            tone = SharedStatusTone.Accent,
            guidance = "Use the fresh QR or return to manual recovery. Saved reconnect stays paused during this handoff.",
        )
        FreshPairingStage.PAYLOAD_CAPTURED -> ConnectionBannerOverrideUiState(
            title = "Fresh pairing payload captured",
            badgeLabel = "Manual pair",
            tone = SharedStatusTone.Accent,
            guidance = "Finish connecting with this payload to replace the stale reconnect, or switch back to saved reconnect yourself.",
        )
        FreshPairingStage.CONNECTING -> ConnectionBannerOverrideUiState(
            title = "Connecting fresh pairing",
            badgeLabel = "Pairing",
            tone = SharedStatusTone.Accent,
            guidance = "Saved reconnect stays paused until this fresh pairing attempt finishes.",
        )
    }
}

private fun FreshPairingAttemptState.toDetailMessage(): String {
    return when (stage) {
        FreshPairingStage.SCANNER_OPEN ->
            "Scan the fresh QR code now. Background reconnect is paused so this new pairing can take over cleanly."
        FreshPairingStage.PAYLOAD_CAPTURED ->
            "A fresh pairing payload is ready. Tap connect to finish replacing the stale reconnect."
        FreshPairingStage.CONNECTING ->
            "Connecting with the fresh pairing payload. Background reconnect remains paused."
    }
}

private fun AndrodexUiState.toBridgeStatusUiState(): BridgeStatusUiState {
    val runtimeTargetLabel = hostRuntimeMetadata?.runtimeTargetDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: hostRuntimeMetadata?.runtimeTarget
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val backendProviderLabel = hostRuntimeMetadata?.backendProviderDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: hostRuntimeMetadata?.backendProvider
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val runtimeSyncLabel = buildRuntimeSyncLabel(hostRuntimeMetadata)
    val runtimeSyncDetail = buildRuntimeSyncDetail(hostRuntimeMetadata)
    val runtimeFailureDetail = hostRuntimeMetadata?.runtimeAttachFailure
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val title = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "Bridge Ready"
        ConnectionStatus.CONNECTING, ConnectionStatus.HANDSHAKING -> "Connecting To Host"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Waiting For Host"
        ConnectionStatus.TRUST_BLOCKED -> "Local Trust Blocked"
        ConnectionStatus.RECONNECT_REQUIRED -> "Pair Needs Repair"
        ConnectionStatus.UPDATE_REQUIRED -> "Update Needed"
        ConnectionStatus.DISCONNECTED -> if (trustedPairSnapshot != null && !trustedPairSnapshot.hasSavedRelaySession) {
            "Trusted Host Ready"
        } else {
            "Host Not Connected"
        }
    }
    val statusLabel = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING, ConnectionStatus.HANDSHAKING -> "Syncing"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying"
        ConnectionStatus.TRUST_BLOCKED -> "Blocked"
        ConnectionStatus.RECONNECT_REQUIRED -> "Repair needed"
        ConnectionStatus.UPDATE_REQUIRED -> "Update required"
        ConnectionStatus.DISCONNECTED -> if (trustedPairSnapshot != null && !trustedPairSnapshot.hasSavedRelaySession) {
            "Trusted host"
        } else {
            "Offline"
        }
    }
    val summary = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "The active runtime stays on the host machine. Android acts as the paired remote control for threads, projects, approvals, and runtime changes."
        ConnectionStatus.RETRYING_SAVED_PAIRING -> "Saved pairing is still present. Automatic reconnect stays available while the host or relay comes back."
        ConnectionStatus.TRUST_BLOCKED -> "The host may still be trusted, but this Android device cannot read its local trusted identity. Repair with a fresh QR code or forget the trusted host on this phone."
        ConnectionStatus.RECONNECT_REQUIRED -> "The trusted host record is still present, but this phone identity needs repair before secure reconnect can resume."
        ConnectionStatus.UPDATE_REQUIRED -> "The host bridge and Android build are speaking different compatibility levels. Update the older side, then reconnect."
        ConnectionStatus.DISCONNECTED -> if (trustedPairSnapshot != null && !trustedPairSnapshot.hasSavedRelaySession) {
            "The trusted host is still remembered. Resolve a fresh live relay session and reconnect without rescanning unless trust actually changed."
        } else {
            "Pair this phone with a host bridge, then manage host-local workspaces and runs from Android."
        }
        else -> "Pair this phone with a host bridge, then manage host-local workspaces and runs from Android."
    }
    return BridgeStatusUiState(
        title = title,
        statusLabel = statusLabel,
        tone = connectionStatus.toSharedStatusTone(),
        summary = summary,
        runtimeTargetLabel = runtimeTargetLabel,
        backendProviderLabel = backendProviderLabel,
        runtimeSyncLabel = runtimeSyncLabel,
        runtimeSyncDetail = runtimeSyncDetail,
        runtimeFailureDetail = runtimeFailureDetail,
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

private fun buildRuntimeSyncLabel(metadata: io.androdex.android.model.HostRuntimeMetadata?): String? {
    val attachState = metadata?.runtimeAttachState
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace('_', ' ')
    val subscriptionState = metadata?.runtimeSubscriptionState
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace('_', ' ')
    val protocolVersion = metadata?.runtimeProtocolVersion?.trim()?.takeIf { it.isNotEmpty() }
    val authMode = metadata?.runtimeAuthMode
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace('-', ' ')
        ?.replace('_', ' ')
    val parts = listOfNotNull(
        attachState?.replaceFirstChar(Char::uppercaseChar),
        subscriptionState?.replaceFirstChar(Char::uppercaseChar),
        protocolVersion?.let { "Protocol $it" },
        authMode?.let { "Auth ${it.replaceFirstChar(Char::uppercaseChar)}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun buildRuntimeSyncDetail(metadata: io.androdex.android.model.HostRuntimeMetadata?): String? {
    val endpointHost = metadata?.runtimeEndpointHost?.trim()?.takeIf { it.isNotEmpty() }
    val snapshotSequence = metadata?.runtimeSnapshotSequence?.let { "Snapshot $it" }
    val replaySequence = metadata?.runtimeReplaySequence?.let { "Replay $it" }
    val duplicateCount = metadata?.runtimeDuplicateSuppressionCount?.let { "Duplicates $it" }
    val parts = listOfNotNull(
        endpointHost?.let { "Endpoint $it" },
        snapshotSequence,
        replaySequence,
        duplicateCount,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
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
                ConnectionStatus.TRUST_BLOCKED -> "Local trust blocked"
                ConnectionStatus.RECONNECT_REQUIRED -> "Needs repair"
                ConnectionStatus.UPDATE_REQUIRED -> "Update required"
                ConnectionStatus.DISCONNECTED -> if (snapshot.hasSavedRelaySession) "Saved pair available" else "Trusted host known"
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
            ConnectionStatus.TRUST_BLOCKED -> "Trusted Host Blocked"
            ConnectionStatus.RECONNECT_REQUIRED -> "Trusted Host Needs Repair"
            ConnectionStatus.DISCONNECTED -> if (snapshot.hasSavedRelaySession) "Saved Pair" else "Trusted Host"
            else -> "Saved Pair"
        },
        statusLabel = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "Connected pair"
            ConnectionStatus.CONNECTING -> "Connecting"
            ConnectionStatus.HANDSHAKING -> "Pairing in progress"
            ConnectionStatus.RETRYING_SAVED_PAIRING -> "Retrying saved pair"
            ConnectionStatus.TRUST_BLOCKED -> "Repair with fresh QR"
            ConnectionStatus.RECONNECT_REQUIRED -> "Needs repair"
            ConnectionStatus.UPDATE_REQUIRED -> "Update required"
            ConnectionStatus.DISCONNECTED -> if (snapshot.hasSavedRelaySession) "Saved pair" else "Trusted host"
        },
        tone = connectionStatus.toSharedStatusTone(),
        name = snapshot.displayName?.takeIf { it.isNotBlank() } ?: name,
        systemName = snapshot.deviceId.takeIf { it.isNotBlank() },
        detail = detailParts.joinToString(" • ").takeIf { it.isNotBlank() },
        relayLabel = snapshot.relayUrl?.let(::compactRelayLabel),
        fingerprint = snapshot.fingerprint,
        hasSavedRelaySession = snapshot.hasSavedRelaySession,
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
        tone = snapshot.status.toSharedStatusTone(needsReauth = snapshot.needsReauth),
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

private fun ConnectionStatus.toSharedStatusTone(): SharedStatusTone {
    return when (this) {
        ConnectionStatus.CONNECTED -> SharedStatusTone.Success
        ConnectionStatus.CONNECTING,
        ConnectionStatus.HANDSHAKING,
        ConnectionStatus.RETRYING_SAVED_PAIRING -> SharedStatusTone.Accent
        ConnectionStatus.TRUST_BLOCKED,
        ConnectionStatus.RECONNECT_REQUIRED,
        ConnectionStatus.UPDATE_REQUIRED -> SharedStatusTone.Warning
        ConnectionStatus.DISCONNECTED -> SharedStatusTone.Neutral
    }
}

private fun HostAccountStatus.toSharedStatusTone(needsReauth: Boolean?): SharedStatusTone {
    return when (this) {
        HostAccountStatus.AUTHENTICATED -> if (needsReauth == true) SharedStatusTone.Warning else SharedStatusTone.Success
        HostAccountStatus.EXPIRED -> SharedStatusTone.Error
        HostAccountStatus.LOGIN_PENDING -> SharedStatusTone.Accent
        HostAccountStatus.NOT_LOGGED_IN,
        HostAccountStatus.UNAVAILABLE,
        HostAccountStatus.UNKNOWN -> SharedStatusTone.Neutral
    }
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
