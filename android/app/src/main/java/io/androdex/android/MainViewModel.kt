package io.androdex.android

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerAttachmentIntakePlan
import io.androdex.android.model.ComposerAttachmentIntakeReservation
import io.androdex.android.model.ComposerImageAttachment
import io.androdex.android.model.ComposerImageAttachmentState
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitOperationException
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.GitWorktreeChangeTransferMode
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostRuntimeMetadata
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.MAX_COMPOSER_IMAGE_ATTACHMENTS
import io.androdex.android.model.ModelOption
import io.androdex.android.model.MissingNotificationThreadPrompt
import io.androdex.android.model.QueuePauseState
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadQueuedDraftState
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.requestId
import io.androdex.android.model.hasBlockingState
import io.androdex.android.model.readyAttachments
import io.androdex.android.notifications.NotificationCoordinator
import io.androdex.android.notifications.NoopNotificationCoordinator
import io.androdex.android.notifications.decodeNotificationOpenPayload
import io.androdex.android.onboarding.FirstPairingOnboardingStore
import io.androdex.android.onboarding.InMemoryFirstPairingOnboardingStore
import io.androdex.android.pairing.extractPairingPayloadFromUriString as extractMirrorPairingPayloadFromUriString
import io.androdex.android.service.AndrodexService
import io.androdex.android.service.AndrodexServiceState
import io.androdex.android.timeline.ThreadTimelineRenderSnapshot
import io.androdex.android.ui.pairing.ConnectPayloadValidationResult
import io.androdex.android.ui.pairing.validateConnectPayload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AndrodexUiState(
    val pairingInput: String = "",
    val hasSavedPairing: Boolean = false,
    val trustedPairSnapshot: TrustedPairSnapshot? = null,
    val freshPairingAttempt: FreshPairingAttemptState? = null,
    val hasSeenFirstPairingOnboarding: Boolean = false,
    val isFirstPairingOnboardingActive: Boolean = false,
    val hostAccountSnapshot: HostAccountSnapshot? = null,
    val hostRuntimeMetadata: HostRuntimeMetadata? = null,
    val defaultRelayUrl: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionDetail: String? = null,
    val secureFingerprint: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val hasLoadedThreadList: Boolean = false,
    val isLoadingThreadList: Boolean = false,
    val selectedThreadId: String? = null,
    val selectedThreadTitle: String? = null,
    val selectedThreadRenderSnapshot: ThreadTimelineRenderSnapshot? = null,
    val selectedThreadMessageCount: Int = 0,
    val messages: List<ConversationMessage> = emptyList(),
    val focusedTurnId: String? = null,
    val activeTurnIdByThread: Map<String, String> = emptyMap(),
    val runningThreadIds: Set<String> = emptySet(),
    val protectedRunningFallbackThreadIds: Set<String> = emptySet(),
    val readyThreadIds: Set<String> = emptySet(),
    val failedThreadIds: Set<String> = emptySet(),
    val composerText: String = "",
    val composerDraftsByThread: Map<String, String> = emptyMap(),
    val composerPlanModeByThread: Set<String> = emptySet(),
    val composerSubagentsByThread: Set<String> = emptySet(),
    val composerReviewSelectionByThread: Map<String, ComposerReviewSelection> = emptyMap(),
    val composerAttachmentsByThread: Map<String, List<ComposerImageAttachment>> = emptyMap(),
    val composerMentionedFilesByThread: Map<String, List<ComposerMentionedFile>> = emptyMap(),
    val composerMentionedSkillsByThread: Map<String, List<ComposerMentionedSkill>> = emptyMap(),
    val composerFileAutocompleteItems: List<FuzzyFileMatch> = emptyList(),
    val isFileAutocompleteVisible: Boolean = false,
    val isFileAutocompleteLoading: Boolean = false,
    val fileAutocompleteQuery: String = "",
    val composerSkillAutocompleteItems: List<SkillMetadata> = emptyList(),
    val isSkillAutocompleteVisible: Boolean = false,
    val isSkillAutocompleteLoading: Boolean = false,
    val skillAutocompleteQuery: String = "",
    val composerSlashCommandItems: List<ComposerSlashCommand> = emptyList(),
    val isSlashCommandAutocompleteVisible: Boolean = false,
    val slashCommandQuery: String = "",
    val isComposerPlanMode: Boolean = false,
    val isComposerSubagentsEnabled: Boolean = false,
    val queuedDraftStateByThread: Map<String, ThreadQueuedDraftState> = emptyMap(),
    val queueFlushingThreadIds: Set<String> = emptySet(),
    val isSendingMessage: Boolean = false,
    val isInterruptingSelectedThread: Boolean = false,
    val isBusy: Boolean = false,
    val busyLabel: String? = null,
    val isLoadingRuntimeConfig: Boolean = false,
    val availableModels: List<ModelOption> = emptyList(),
    val selectedModelId: String? = null,
    val selectedReasoningEffort: String? = null,
    val selectedAccessMode: AccessMode = AccessMode.ON_REQUEST,
    val selectedServiceTier: ServiceTier? = null,
    val supportsServiceTier: Boolean = true,
    val supportsThreadCompaction: Boolean = true,
    val supportsThreadRollback: Boolean = true,
    val supportsBackgroundTerminalCleanup: Boolean = true,
    val supportsThreadFork: Boolean = true,
    val collaborationModes: Set<CollaborationModeKind> = emptySet(),
    val threadRuntimeOverridesByThread: Map<String, ThreadRuntimeOverride> = emptyMap(),
    val activeWorkspacePath: String? = null,
    val recentWorkspaces: List<WorkspacePathSummary> = emptyList(),
    val workspaceBrowserPath: String? = null,
    val workspaceBrowserParentPath: String? = null,
    val workspaceBrowserEntries: List<WorkspaceDirectoryEntry> = emptyList(),
    val isProjectPickerOpen: Boolean = false,
    val isWorkspaceBrowserLoading: Boolean = false,
    val missingNotificationThreadPrompt: MissingNotificationThreadPrompt? = null,
    val errorMessage: String? = null,
    val pendingApproval: ApprovalRequest? = null,
    val pendingToolInputsByThread: Map<String, Map<String, ToolUserInputRequest>> = emptyMap(),
    val toolInputAnswersByRequest: Map<String, Map<String, String>> = emptyMap(),
    val submittingToolInputRequestIds: Set<String> = emptySet(),
    val gitStateByThread: Map<String, ThreadGitState> = emptyMap(),
    val runningGitActionByThread: Map<String, GitActionKind> = emptyMap(),
    val runningGitWorkingDirectoryByThread: Map<String, String> = emptyMap(),
    val gitCommitDialog: GitCommitDialogState? = null,
    val gitBranchDialog: GitBranchDialogState? = null,
    val gitWorktreeDialog: GitWorktreeDialogState? = null,
    val gitAlert: GitAlertState? = null,
    val pendingGitBranchOperation: GitBranchUserOperation? = null,
    val pendingGitRemoveWorktree: GitPendingRemoveWorktree? = null,
)

private const val gitLoadReasonThreadOpen = "thread_open"

class MainViewModel(
    repository: AndrodexRepositoryContract,
    private val notificationCoordinator: NotificationCoordinator = NoopNotificationCoordinator,
    private val firstPairingOnboardingStore: FirstPairingOnboardingStore = InMemoryFirstPairingOnboardingStore(),
) : ViewModel() {
    private var nextGitRefreshRequestCounter: Long = 0L

    private val service = AndrodexService(repository, viewModelScope)
    private val repository = repository
    private var lastConnectionStatus: ConnectionStatus = service.state.value.connectionStatus
    private var lastSkillInventoryVersion: Long = service.state.value.skillInventoryVersion
    private var fileAutocompleteJob: Job? = null
    private var skillAutocompleteJob: Job? = null
    private val autocompleteDebounceMs = 180L

    private val initialServiceState = service.state.value
    private val uiStateFlow = MutableStateFlow(
        createInitialUiState(initialServiceState)
    )

    val uiState: StateFlow<AndrodexUiState> = uiStateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            service.state.collect { serviceState ->
                uiStateFlow.update { current -> applyServiceState(current, serviceState) }
                syncFirstPairingOnboardingSeenFlag(serviceState)
                if (serviceState.skillInventoryVersion != lastSkillInventoryVersion
                    && uiStateFlow.value.isSkillAutocompleteVisible
                ) {
                    refreshComposerAutocomplete(uiStateFlow.value.composerText)
                }
                lastSkillInventoryVersion = serviceState.skillInventoryVersion
                flushEligibleQueues()
                notificationCoordinator.syncRegistration(
                    connectionStatus = serviceState.connectionStatus,
                    hasSavedPairing = serviceState.hasSavedPairing,
                )
                lastConnectionStatus = serviceState.connectionStatus
            }
        }
        viewModelScope.launch {
            service.runCompletionEvents.collect { event ->
                notificationCoordinator.notifyRunCompletion(
                    threadId = event.threadId,
                    turnId = event.turnId,
                    title = event.threadTitle,
                    terminalState = event.terminalState,
                )
            }
        }
    }

    fun consumeIntent(intent: Intent?) {
        val extras = intent?.extras?.keySet().orEmpty().associateWith { key ->
            intent?.extras?.getString(key)
        }
        decodeNotificationOpenPayload(extras)?.let { payload ->
            service.handleNotificationOpen(payload.threadId, payload.turnId)
            return
        }
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val deepLinkText = intent?.data?.extractPairingPayload()
        val payload = sharedText?.takeIf { it.isNotEmpty() }
            ?: deepLinkText?.takeIf { it.isNotEmpty() }
            ?: return
        uiStateFlow.update { it.copy(pairingInput = payload) }
        connectWithCurrentPairingInput(fromExternalPayload = true)
    }

    fun beginFreshPairingScan() {
        service.beginFreshPairingScan()
    }

    fun updatePairingInput(value: String) {
        uiStateFlow.update { it.copy(pairingInput = value) }
    }

    fun updateComposerText(value: String) {
        val threadId = uiStateFlow.value.selectedThreadId
        uiStateFlow.update { current ->
            val nextDrafts = current.composerDraftsByThread.toMutableMap()
            if (threadId != null) {
                if (value.isEmpty()) {
                    nextDrafts.remove(threadId)
                } else {
                    nextDrafts[threadId] = value
                }
            }
            current.copy(
                composerText = value,
                composerDraftsByThread = nextDrafts,
            )
        }
        if (value.isNotBlank()) {
            clearComposerReviewSelectionIfNeededForNonReviewContent()
        }
        refreshComposerAutocomplete(value)
    }

    fun updateComposerPlanMode(enabled: Boolean) {
        var effectiveEnabled = false
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            effectiveEnabled = enabled && current.supportsCollaborationMode(CollaborationModeKind.PLAN)
            current.copy(
                composerPlanModeByThread = current.composerPlanModeByThread.updatedPlanMode(threadId, effectiveEnabled),
                isComposerPlanMode = effectiveEnabled,
            )
        }
        if (effectiveEnabled) {
            clearComposerReviewSelectionIfNeededForNonReviewContent()
        }
    }

    fun updateComposerSubagentsEnabled(enabled: Boolean) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            current.copy(
                composerSubagentsByThread = current.composerSubagentsByThread.updatedPlanMode(threadId, enabled),
                isComposerSubagentsEnabled = enabled,
            )
        }
        if (enabled) {
            clearComposerReviewSelectionIfNeededForNonReviewContent()
        }
        if (!enabled) {
            clearSlashCommandAutocomplete()
        }
    }

    fun updateComposerReviewTarget(target: ComposerReviewTarget) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val currentReview = current.composerReviewSelectionByThread[threadId]
            if (currentReview == null && !canArmReviewSelection(current, threadId)) {
                return@update current
            }
            val nextSelection = when (target) {
                ComposerReviewTarget.UNCOMMITTED_CHANGES -> ComposerReviewSelection(target = target)
                ComposerReviewTarget.BASE_BRANCH -> {
                    val branchName = currentReview?.baseBranch
                        ?: current.gitStateForThread(threadId)?.branchTargets?.defaultBranch
                        ?: current.gitStateForThread(threadId)?.status?.currentBranch
                    ComposerReviewSelection(
                        target = target,
                        baseBranch = branchName?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
            }
            current.copy(
                composerReviewSelectionByThread = current.composerReviewSelectionByThread + (threadId to nextSelection),
            )
        }
        clearSlashCommandAutocomplete()
    }

    fun updateComposerReviewBaseBranch(value: String) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val currentReview = current.composerReviewSelectionByThread[threadId]
                ?: return@update current
            if (currentReview.target != ComposerReviewTarget.BASE_BRANCH) {
                return@update current
            }
            current.copy(
                composerReviewSelectionByThread = current.composerReviewSelectionByThread + (
                    threadId to currentReview.copy(baseBranch = value)
                ),
            )
        }
    }

    fun clearComposerReviewSelection() {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            current.copy(
                composerReviewSelectionByThread = current.composerReviewSelectionByThread - threadId,
            )
        }
    }

    fun beginComposerAttachmentIntake(requestedCount: Int): ComposerAttachmentIntakeReservation? {
        var reservation: ComposerAttachmentIntakeReservation? = null
        var shouldClearReviewSelection = false
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val existingAttachments = current.composerAttachmentsByThread[threadId].orEmpty()
            val intakePlan = ComposerAttachmentIntakePlan.make(
                requestedCount = requestedCount,
                remainingSlots = (MAX_COMPOSER_IMAGE_ATTACHMENTS - existingAttachments.size).coerceAtLeast(0),
            )
            if (intakePlan.acceptedCount == 0) {
                service.reportError("You can attach up to $MAX_COMPOSER_IMAGE_ATTACHMENTS images per message.")
                return@update current
            }
            if (intakePlan.hasOverflow) {
                service.reportError("Only $MAX_COMPOSER_IMAGE_ATTACHMENTS images are allowed per message.")
            }

            shouldClearReviewSelection = true
            val acceptedIds = List(intakePlan.acceptedCount) { UUID.randomUUID().toString() }
            reservation = ComposerAttachmentIntakeReservation(
                threadId = threadId,
                acceptedIds = acceptedIds,
                droppedCount = intakePlan.droppedCount,
            )
            current.copy(
                composerAttachmentsByThread = current.composerAttachmentsByThread.updatedComposerAttachments(
                    threadId = threadId,
                    attachments = existingAttachments + acceptedIds.map { attachmentId ->
                        ComposerImageAttachment(
                            id = attachmentId,
                            state = ComposerImageAttachmentState.Loading,
                        )
                    },
                ),
            )
        }
        if (shouldClearReviewSelection) {
            clearComposerReviewSelectionIfNeededForNonReviewContent()
        }
        return reservation
    }

    fun updateComposerAttachmentState(
        threadId: String,
        attachmentId: String,
        state: ComposerImageAttachmentState,
    ) {
        uiStateFlow.update { current ->
            val attachments = current.composerAttachmentsByThread[threadId].orEmpty()
            val attachmentIndex = attachments.indexOfFirst { it.id == attachmentId }
            if (attachmentIndex < 0) {
                return@update current
            }
            val updatedAttachments = attachments.toMutableList()
            updatedAttachments[attachmentIndex] = updatedAttachments[attachmentIndex].copy(state = state)
            current.copy(
                composerAttachmentsByThread = current.composerAttachmentsByThread.updatedComposerAttachments(
                    threadId = threadId,
                    attachments = updatedAttachments,
                ),
            )
        }
    }

    fun removeComposerAttachment(id: String) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val attachments = current.composerAttachmentsByThread[threadId].orEmpty()
            current.copy(
                composerAttachmentsByThread = current.composerAttachmentsByThread.updatedComposerAttachments(
                    threadId = threadId,
                    attachments = attachments.filterNot { it.id == id },
                ),
            )
        }
    }

    fun reportAttachmentError(message: String) {
        service.reportError(message)
    }

    fun selectFileAutocomplete(item: FuzzyFileMatch) {
        clearComposerReviewSelectionIfNeededForNonReviewContent()
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val fullPath = item.path.trim().ifEmpty { item.fileName }
            val nextText = replacingTrailingFileAutocompleteToken(current.composerText, item.fileName)
                ?: current.composerText
            val mentions = current.composerMentionedFilesByThread[threadId].orEmpty()
            val nextMentions = if (mentions.any { it.path == fullPath }) {
                mentions
            } else {
                mentions + ComposerMentionedFile(
                    fileName = item.fileName,
                    path = fullPath,
                )
            }
            current.copy(
                composerText = nextText,
                composerDraftsByThread = current.composerDraftsByThread.updatedThreadDraft(threadId, nextText),
                composerMentionedFilesByThread = current.composerMentionedFilesByThread + (threadId to nextMentions),
            )
        }
        clearFileAutocomplete()
    }

    fun removeMentionedFile(id: String) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val mentions = current.composerMentionedFilesByThread[threadId].orEmpty()
            val mention = mentions.firstOrNull { it.id == id } ?: return@update current
            val ambiguousKeys = ambiguousFileNameAliasKeys(mentions)
            val allowFileNameAliases = fileMentionCollisionKey(mention.fileName)
                ?.let { it !in ambiguousKeys }
                ?: true
            val nextText = removingFileMentionAliases(
                text = current.composerText,
                mention = mention,
                allowFileNameAliases = allowFileNameAliases,
            )
            val nextMentions = mentions.filterNot { it.id == id }
            current.copy(
                composerText = nextText,
                composerDraftsByThread = current.composerDraftsByThread.updatedThreadDraft(threadId, nextText),
                composerMentionedFilesByThread = current.composerMentionedFilesByThread.updatedMentionedFiles(threadId, nextMentions),
            )
        }
    }

    fun selectSkillAutocomplete(skill: SkillMetadata) {
        clearComposerReviewSelectionIfNeededForNonReviewContent()
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val normalizedSkillName = skill.name.trim()
            if (normalizedSkillName.isEmpty()) {
                return@update current
            }
            val nextText = replacingTrailingSkillAutocompleteToken(current.composerText, normalizedSkillName)
                ?: current.composerText
            val mentions = current.composerMentionedSkillsByThread[threadId].orEmpty()
            val nextMentions = if (mentions.any { it.name.equals(normalizedSkillName, ignoreCase = true) }) {
                mentions
            } else {
                mentions + ComposerMentionedSkill(
                    name = normalizedSkillName,
                    path = skill.path?.trim()?.takeIf { it.isNotEmpty() },
                    description = skill.description,
                )
            }
            current.copy(
                composerText = nextText,
                composerDraftsByThread = current.composerDraftsByThread.updatedThreadDraft(threadId, nextText),
                composerMentionedSkillsByThread = current.composerMentionedSkillsByThread + (threadId to nextMentions),
            )
        }
        clearSkillAutocomplete()
    }

    fun removeMentionedSkill(id: String) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            val mentions = current.composerMentionedSkillsByThread[threadId].orEmpty()
            val mention = mentions.firstOrNull { it.id == id } ?: return@update current
            val nextText = removeBoundedToken(
                token = "$${mention.name}",
                text = current.composerText,
            )
            val nextMentions = mentions.filterNot { it.id == id }
            current.copy(
                composerText = nextText,
                composerDraftsByThread = current.composerDraftsByThread.updatedThreadDraft(threadId, nextText),
                composerMentionedSkillsByThread = current.composerMentionedSkillsByThread.updatedMentionedSkills(threadId, nextMentions),
            )
        }
    }

    fun selectSlashCommand(command: ComposerSlashCommand) {
        when (command) {
            ComposerSlashCommand.REVIEW -> {
                uiStateFlow.update { current ->
                    val threadId = current.selectedThreadId ?: return@update current
                    val nextText = removingTrailingSlashCommandToken(current.composerText)
                        ?: current.composerText
                    if (current.composerReviewSelectionByThread[threadId] != null
                        || nextText.isNotBlank()
                        || current.composerAttachmentsByThread[threadId].orEmpty().isNotEmpty()
                        || current.composerMentionedFilesByThread[threadId].orEmpty().isNotEmpty()
                        || current.composerMentionedSkillsByThread[threadId].orEmpty().isNotEmpty()
                        || current.isComposerPlanMode
                        || current.isComposerSubagentsEnabled
                    ) {
                        return@update current
                    }
                    current.copy(
                        composerText = nextText,
                        composerDraftsByThread = current.composerDraftsByThread.updatedThreadDraft(threadId, nextText),
                        composerReviewSelectionByThread = current.composerReviewSelectionByThread + (
                            threadId to ComposerReviewSelection(
                                target = ComposerReviewTarget.UNCOMMITTED_CHANGES,
                            )
                        ),
                    )
                }
            }
            ComposerSlashCommand.SUBAGENTS -> {
                uiStateFlow.update { current ->
                    val threadId = current.selectedThreadId ?: return@update current
                    val nextText = removingTrailingSlashCommandToken(current.composerText)
                        ?: current.composerText
                    current.copy(
                        composerText = nextText,
                        composerDraftsByThread = current.composerDraftsByThread.updatedThreadDraft(threadId, nextText),
                        composerSubagentsByThread = current.composerSubagentsByThread + threadId,
                        isComposerSubagentsEnabled = true,
                    )
                }
            }
        }
        clearSlashCommandAutocomplete()
    }

    fun pauseSelectedThreadQueue() {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        updateQueuedDraftState(threadId) { current ->
            if (current.drafts.isEmpty()) {
                current
            } else {
                current.copy(
                    pauseState = QueuePauseState.PAUSED,
                    pauseMessage = null,
                )
            }
        }
    }

    fun resumeSelectedThreadQueue() {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        updateQueuedDraftState(threadId) { current ->
            current.copy(
                pauseState = QueuePauseState.ACTIVE,
                pauseMessage = null,
            )
        }
        flushQueueIfPossible(threadId)
    }

    fun restoreQueuedDraftToComposer(draftId: String) {
        val current = uiStateFlow.value
        val threadId = current.selectedThreadId ?: return
        if (current.isSendingMessage
            || current.isInterruptingSelectedThread
            || current.isBusy
            || current.composerText.isNotEmpty()
            || current.composerAttachmentsByThread[threadId].orEmpty().isNotEmpty()
            || current.composerMentionedFilesByThread[threadId].orEmpty().isNotEmpty()
            || current.composerMentionedSkillsByThread[threadId].orEmpty().isNotEmpty()
            || current.isComposerSubagentsEnabled
            || current.composerReviewSelectionByThread[threadId] != null
        ) {
            return
        }

        val queueState = current.queuedDraftStateByThread[threadId] ?: return
        val draftIndex = queueState.drafts.indexOfFirst { it.id == draftId }
        if (draftIndex < 0) {
            return
        }

        val draft = queueState.drafts[draftIndex]
        val nextDrafts = queueState.drafts.toMutableList().apply { removeAt(draftIndex) }
        uiStateFlow.update { state ->
            val restoredCollaborationMode = draft.collaborationMode.normalizedFor(state.collaborationModes)
            val nextPlanModes = state.composerPlanModeByThread.updatedPlanMode(
                threadId = threadId,
                enabled = restoredCollaborationMode == CollaborationModeKind.PLAN,
            )
            val nextSubagents = state.composerSubagentsByThread.updatedPlanMode(
                threadId = threadId,
                enabled = draft.subagentsSelectionEnabled,
            )
            state.copy(
                composerText = draft.text,
                composerDraftsByThread = state.composerDraftsByThread + (threadId to draft.text),
                composerPlanModeByThread = nextPlanModes,
                composerSubagentsByThread = nextSubagents,
                composerAttachmentsByThread = state.composerAttachmentsByThread.updatedComposerAttachments(
                    threadId = threadId,
                    attachments = draft.attachments.map { attachment ->
                        ComposerImageAttachment(
                            id = attachment.id,
                            state = ComposerImageAttachmentState.Ready(attachment),
                        )
                    },
                ),
                composerMentionedFilesByThread = state.composerMentionedFilesByThread.updatedMentionedFiles(
                    threadId,
                    draft.mentionedFiles,
                ),
                composerMentionedSkillsByThread = state.composerMentionedSkillsByThread.updatedMentionedSkills(
                    threadId,
                    draft.mentionedSkills,
                ),
                isComposerPlanMode = threadId in nextPlanModes,
                isComposerSubagentsEnabled = threadId in nextSubagents,
                queuedDraftStateByThread = state.queuedDraftStateByThread.updatedQueueEntry(
                    threadId = threadId,
                    queueState = queueState.copy(drafts = nextDrafts).normalized(),
                ),
            )
        }
        refreshComposerAutocomplete(draft.text)
    }

    fun removeQueuedDraft(draftId: String) {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        updateQueuedDraftState(threadId) { current ->
            current.copy(
                drafts = current.drafts.filterNot { it.id == draftId },
            ).normalized()
        }
    }

    fun refreshSelectedThreadGitState() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        if (isGitLoadInFlight(threadId, workingDirectory, snapshot)) {
            return
        }
        runGitAction(threadId, workingDirectory, GitActionKind.REFRESH) {
            loadGitSnapshot(
                threadId = threadId,
                workingDirectory = workingDirectory,
                loadDiff = false,
                suppressErrors = false,
            )
        }
    }

    fun refreshSelectedThreadGitDiff() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        if (isGitLoadInFlight(threadId, workingDirectory, snapshot)) {
            return
        }
        runGitAction(threadId, workingDirectory, GitActionKind.DIFF) {
            loadGitSnapshot(
                threadId = threadId,
                workingDirectory = workingDirectory,
                loadDiff = true,
                suppressErrors = false,
            )
        }
    }

    fun openGitCommitDialog() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        uiStateFlow.update {
            it.copy(
                gitCommitDialog = GitCommitDialogState("Changes from Androdex"),
                gitAlert = null,
            )
        }
    }

    fun updateGitCommitMessage(value: String) {
        uiStateFlow.update { current ->
            current.copy(
                gitCommitDialog = current.gitCommitDialog?.copy(message = value),
            )
        }
    }

    fun dismissGitCommitDialog() {
        uiStateFlow.update { it.copy(gitCommitDialog = null) }
    }

    fun submitGitCommit() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        val message = snapshot.gitCommitDialog?.message?.trim().orEmpty()
            .ifEmpty { "Changes from Androdex" }
        runGitAction(threadId, workingDirectory, GitActionKind.COMMIT) {
            try {
                repository.gitCommit(workingDirectory, message)
                uiStateFlow.update { it.copy(gitCommitDialog = null) }
            } catch (error: GitOperationException) {
                if (error.code == "nothing_to_commit") {
                    uiStateFlow.update {
                        it.copy(
                            gitCommitDialog = null,
                            gitAlert = dismissOnlyGitAlert(
                                title = "Nothing to commit",
                                message = error.message,
                            ),
                        )
                    }
                    return@runGitAction
                }
                throw error
            }
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = false)
        }
    }

    fun pushGitChanges() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        runGitAction(threadId, workingDirectory, GitActionKind.PUSH) {
            val result = repository.gitPush(workingDirectory)
            updateThreadGitStatusIfCurrentContext(threadId, workingDirectory, result.status)
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = true)
        }
    }

    fun requestGitPull() {
        viewModelScope.launch {
            var snapshot = uiStateFlow.value
            val threadId = snapshot.selectedThreadId ?: return@launch
            val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            snapshot = ensureGitBranchContextLoaded(threadId, workingDirectory) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val status = snapshot.gitStateByThread[threadId]?.status
            if (status?.state == "diverged" || status?.state == "dirty_and_behind") {
                val (title, message) = if (status.state == "diverged") {
                    "Branch diverged from remote" to
                        "Local and remote history both moved. Pull with rebase to reconcile them?"
                } else {
                    "Local changes need attention" to
                        "You have local changes and the remote branch moved ahead. Pull with rebase only if you're ready to resolve conflicts."
                }
                uiStateFlow.update {
                    it.copy(
                        gitAlert = GitAlertState(
                            title = title,
                            message = message,
                            buttons = listOf(
                                GitAlertButton("Cancel", GitAlertAction.DISMISS),
                                GitAlertButton("Pull & Rebase", GitAlertAction.PULL_REBASE),
                            ),
                        ),
                        pendingGitBranchOperation = null,
                        pendingGitRemoveWorktree = null,
                    )
                }
                return@launch
            }
            performGitPull(threadId, workingDirectory)
        }
    }

    fun openGitBranchDialog() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        if (isGitLoadInFlight(threadId, workingDirectory, snapshot)) {
            return
        }
        uiStateFlow.update {
            it.copy(
                gitBranchDialog = GitBranchDialogState(),
                gitAlert = null,
            )
        }
        viewModelScope.launch {
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = true)
        }
    }

    fun updateGitBranchName(value: String) {
        uiStateFlow.update { current ->
            current.copy(
                gitBranchDialog = current.gitBranchDialog?.copy(newBranchName = value),
            )
        }
    }

    fun dismissGitBranchDialog() {
        uiStateFlow.update { it.copy(gitBranchDialog = null) }
    }

    fun requestCreateGitBranch() {
        viewModelScope.launch {
            var snapshot = uiStateFlow.value
            val threadId = snapshot.selectedThreadId ?: return@launch
            val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val initialBranchName = snapshot.gitBranchDialog?.newBranchName?.trim().orEmpty()
            if (initialBranchName.isEmpty()) {
                return@launch
            }
            snapshot = ensureGitBranchContextLoaded(threadId, workingDirectory) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val branchName = snapshot.gitBranchDialog?.newBranchName?.trim().orEmpty()
            if (branchName.isEmpty()) {
                return@launch
            }
            val operation = GitBranchUserOperation.Create(branchName)
            val alert = gitBranchAlert(snapshot, operation)
            if (alert != null) {
                uiStateFlow.update {
                    it.copy(
                        gitAlert = alert,
                        pendingGitBranchOperation = operation,
                        pendingGitRemoveWorktree = null,
                    )
                }
                return@launch
            }
            performGitCreateBranch(threadId, workingDirectory, branchName)
        }
    }

    fun requestSwitchGitBranch(branch: String) {
        viewModelScope.launch {
            var snapshot = uiStateFlow.value
            val threadId = snapshot.selectedThreadId ?: return@launch
            val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val trimmedBranch = branch.trim()
            if (trimmedBranch.isEmpty()) {
                return@launch
            }
            snapshot = ensureGitBranchContextLoaded(threadId, workingDirectory) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val branchTargets = snapshot.gitStateByThread[threadId]?.branchTargets
            if (trimmedBranch in branchTargets?.branchesCheckedOutElsewhere.orEmpty()) {
                uiStateFlow.update {
                    it.copy(
                        gitAlert = dismissOnlyGitAlert(
                            title = "Branch switch failed",
                            message = "Cannot switch branches: this branch is already open in another worktree.",
                        ),
                        pendingGitBranchOperation = null,
                        pendingGitRemoveWorktree = null,
                    )
                }
                return@launch
            }
            val operation = GitBranchUserOperation.SwitchTo(trimmedBranch)
            val alert = gitBranchAlert(snapshot, operation)
            if (alert != null) {
                uiStateFlow.update {
                    it.copy(
                        gitAlert = alert,
                        pendingGitBranchOperation = operation,
                        pendingGitRemoveWorktree = null,
                    )
                }
                return@launch
            }
            performGitSwitchBranch(threadId, workingDirectory, trimmedBranch)
        }
    }

    fun openGitWorktreeDialog() {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        val branchTargets = snapshot.gitStateByThread[threadId]
            ?.takeIf { it.hasLoadedBranchContextFor(workingDirectory) }
            ?.branchTargets
        uiStateFlow.update {
            it.copy(
                gitWorktreeDialog = GitWorktreeDialogState(
                    baseBranch = branchTargets?.defaultBranch
                        ?: branchTargets?.currentBranch
                        ?: "",
                ),
                gitAlert = null,
            )
        }
        viewModelScope.launch {
            val refreshedState = ensureGitBranchContextLoaded(
                threadId = threadId,
                workingDirectory = workingDirectory,
                suppressErrors = true,
            ) ?: return@launch
            val refreshedTargets = refreshedState.gitStateByThread[threadId]?.branchTargets ?: return@launch
            val baseBranch = refreshedTargets.defaultBranch
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: refreshedTargets.currentBranch
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: return@launch
            uiStateFlow.update { current ->
                val dialog = current.gitWorktreeDialog ?: return@update current
                if (!isSelectedGitContext(threadId, workingDirectory, current) || dialog.baseBranch.trim().isNotEmpty()) {
                    return@update current
                }
                current.copy(
                    gitWorktreeDialog = dialog.copy(baseBranch = baseBranch),
                )
            }
        }
    }

    fun updateGitWorktreeBranchName(value: String) {
        uiStateFlow.update { current ->
            current.copy(
                gitWorktreeDialog = current.gitWorktreeDialog?.copy(branchName = value),
            )
        }
    }

    fun updateGitWorktreeBaseBranch(value: String) {
        uiStateFlow.update { current ->
            current.copy(
                gitWorktreeDialog = current.gitWorktreeDialog?.copy(baseBranch = value),
            )
        }
    }

    fun updateGitWorktreeTransferMode(value: GitWorktreeChangeTransferMode) {
        uiStateFlow.update { current ->
            current.copy(
                gitWorktreeDialog = current.gitWorktreeDialog?.copy(changeTransfer = value),
            )
        }
    }

    fun dismissGitWorktreeDialog() {
        uiStateFlow.update { it.copy(gitWorktreeDialog = null) }
    }

    fun requestCreateGitWorktree() {
        viewModelScope.launch {
            var snapshot = uiStateFlow.value
            val threadId = snapshot.selectedThreadId ?: return@launch
            val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val initialDialog = snapshot.gitWorktreeDialog ?: return@launch
            if (initialDialog.branchName.trim().isEmpty() || initialDialog.baseBranch.trim().isEmpty()) {
                return@launch
            }
            snapshot = ensureGitBranchContextLoaded(threadId, workingDirectory) ?: return@launch
            if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
                return@launch
            }
            val dialog = snapshot.gitWorktreeDialog ?: return@launch
            val branchName = dialog.branchName.trim()
            val baseBranch = dialog.baseBranch.trim()
            if (branchName.isEmpty() || baseBranch.isEmpty()) {
                return@launch
            }
            val operation = GitBranchUserOperation.CreateWorktree(
                branchName = branchName,
                baseBranch = baseBranch,
                changeTransfer = dialog.changeTransfer,
            )
            val alert = gitBranchAlert(snapshot, operation)
            if (alert != null) {
                uiStateFlow.update {
                    it.copy(
                        gitAlert = alert,
                        pendingGitBranchOperation = operation,
                        pendingGitRemoveWorktree = null,
                    )
                }
                return@launch
            }
            performGitCreateWorktree(
                threadId = threadId,
                workingDirectory = workingDirectory,
                branchName = branchName,
                baseBranch = baseBranch,
                changeTransfer = dialog.changeTransfer,
            )
        }
    }

    fun requestRemoveGitWorktree(branch: String, worktreePath: String) {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        if (!canStartGitAction(threadId, workingDirectory, snapshot)) {
            return
        }
        val trimmedBranch = branch.trim()
        val trimmedPath = worktreePath.trim()
        if (trimmedBranch.isEmpty() || trimmedPath.isEmpty()) {
            return
        }
        uiStateFlow.update {
            it.copy(
                gitAlert = GitAlertState(
                    title = "Remove managed worktree?",
                    message = "This removes the managed worktree for '$trimmedBranch' at $trimmedPath.",
                    buttons = listOf(
                        GitAlertButton("Cancel", GitAlertAction.DISMISS),
                        GitAlertButton(
                            label = "Remove Worktree",
                            action = GitAlertAction.REMOVE_WORKTREE,
                            isDestructive = true,
                        ),
                    ),
                ),
                pendingGitRemoveWorktree = GitPendingRemoveWorktree(
                    branch = trimmedBranch,
                    worktreePath = trimmedPath,
                ),
                pendingGitBranchOperation = null,
            )
        }
    }

    fun dismissGitAlert() {
        uiStateFlow.update {
            it.copy(
                gitAlert = null,
                pendingGitBranchOperation = null,
                pendingGitRemoveWorktree = null,
            )
        }
    }

    fun handleGitAlertAction(action: GitAlertAction) {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot) ?: return
        val pendingBranchOperation = snapshot.pendingGitBranchOperation
        val pendingRemoveWorktree = snapshot.pendingGitRemoveWorktree
        uiStateFlow.update {
            it.copy(
                gitAlert = null,
                pendingGitBranchOperation = null,
                pendingGitRemoveWorktree = null,
            )
        }
        when (action) {
            GitAlertAction.DISMISS -> Unit
            GitAlertAction.PULL_REBASE -> performGitPull(threadId, workingDirectory)
            GitAlertAction.CONTINUE_BRANCH_OPERATION -> continueGitBranchOperation(
                threadId = threadId,
                workingDirectory = workingDirectory,
                operation = pendingBranchOperation,
            )
            GitAlertAction.COMMIT_AND_CONTINUE_BRANCH_OPERATION -> {
                val operation = pendingBranchOperation ?: return
                viewModelScope.launch {
                    var shouldRefreshAfterFailure = false
                    markGitActionRunning(threadId, workingDirectory, GitActionKind.COMMIT)
                    try {
                        repository.gitCommit(workingDirectory, "WIP before switching branches")
                        loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = true)
                    } catch (error: Throwable) {
                        reportGitFailure(error, "Commit failed.")
                        shouldRefreshAfterFailure = true
                        return@launch
                    } finally {
                        clearGitActionRunning(threadId)
                        if (shouldRefreshAfterFailure) {
                            refreshGitStateAfterActionFailure(threadId, workingDirectory)
                        }
                    }
                    continueGitBranchOperation(threadId, workingDirectory, operation)
                }
            }
            GitAlertAction.REMOVE_WORKTREE -> {
                val request = pendingRemoveWorktree ?: return
                runGitAction(threadId, workingDirectory, GitActionKind.REMOVE_WORKTREE) {
                    repository.gitRemoveWorktree(
                        workingDirectory = request.worktreePath,
                        branch = request.branch,
                    )
                    loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = false)
                }
            }
        }
    }

    fun clearError() {
        service.clearError()
    }

    fun dismissMissingNotificationThreadPrompt() {
        service.dismissMissingNotificationThreadPrompt()
    }

    fun connectWithCurrentPairingInput(fromExternalPayload: Boolean = false) {
        val payload = uiStateFlow.value.pairingInput.trim()
        if (payload.isEmpty()) {
            service.reportError("Paste or scan the pairing payload first.")
            return
        }
        service.recordFreshPairingPayloadCaptured()
        when (val validation = validateConnectPayload(payload)) {
            is ConnectPayloadValidationResult.Error -> {
                service.reportError(validation.message)
                return
            }
            is ConnectPayloadValidationResult.UpdateRequired -> {
                service.reportError(validation.message)
                return
            }
            is ConnectPayloadValidationResult.Success,
            is ConnectPayloadValidationResult.RecoverySuccess -> Unit
        }

        if (fromExternalPayload) {
            markFirstPairingOnboardingSeen()
            dismissFirstPairingOnboarding()
        }
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isBusy = true, busyLabel = null) }
            try {
                when (validateConnectPayload(payload)) {
                    is ConnectPayloadValidationResult.Success -> {
                        service.connectWithPairingPayload(payload, isFreshPairing = true)
                    }
                    is ConnectPayloadValidationResult.RecoverySuccess -> {
                        service.connectWithRecoveryPayload(payload)
                    }
                    else -> error("Validated payload unexpectedly failed on second pass.")
                }
            } catch (error: Throwable) {
                service.failFreshPairingAttempt()
                service.reportError(error.message ?: "Request failed.")
            } finally {
                uiStateFlow.update { it.copy(isBusy = false, busyLabel = null) }
            }
        }
    }

    fun completeFreshPairingScan(payload: String?) {
        val normalizedPayload = payload?.trim()
        if (normalizedPayload.isNullOrEmpty()) {
            if (uiStateFlow.value.isFirstPairingOnboardingActive && !uiStateFlow.value.hasSavedPairing) {
                dismissFirstPairingOnboarding()
                service.clearFreshPairingAttempt()
                return
            }
            service.cancelFreshPairingScan()
            return
        }
        updatePairingInput(normalizedPayload)
        service.recordFreshPairingPayloadCaptured()
        connectWithCurrentPairingInput()
    }

    fun markFirstPairingOnboardingSeen() {
        if (!uiStateFlow.value.hasSeenFirstPairingOnboarding) {
            firstPairingOnboardingStore.markFirstPairingOnboardingSeen()
        }
        uiStateFlow.update {
            it.copy(hasSeenFirstPairingOnboarding = true)
        }
    }

    fun dismissFirstPairingOnboarding() {
        uiStateFlow.update {
            it.copy(isFirstPairingOnboardingActive = false)
        }
    }

    fun reconnectSaved() {
        runBusyAction {
            service.reconnectSaved()
        }
    }

    fun forgetTrustedHost() {
        runBusyAction {
            service.forgetTrustedHost()
        }
    }

    fun openManualPairingSetup() {
        clearComposerAutocomplete()
        uiStateFlow.update {
            it.copy(
                gitCommitDialog = null,
                gitBranchDialog = null,
                gitWorktreeDialog = null,
                gitAlert = null,
                pendingGitBranchOperation = null,
                pendingGitRemoveWorktree = null,
            )
        }
        runBusyAction("Opening pairing setup...") {
            service.closeThread()
            service.disconnect(clearSavedPairing = false)
        }
    }

    fun reconnectSavedIfAvailable() {
        if (uiStateFlow.value.isBusy
            || uiStateFlow.value.pairingInput.isNotBlank()
            || uiStateFlow.value.freshPairingAttempt != null
        ) {
            return
        }
        service.reconnectSavedIfAvailable()
    }

    fun onAppForegrounded() {
        notificationCoordinator.onAppForegrounded()
        service.onAppForegrounded()
        reconnectSavedIfAvailable()
    }

    fun onAppBackgrounded() {
        notificationCoordinator.onAppBackgrounded()
        service.onAppBackgrounded()
    }

    fun shouldRequestNotificationPermission(): Boolean {
        return notificationCoordinator.shouldRequestPermission(uiStateFlow.value.hasSavedPairing)
    }

    fun onNotificationPermissionPromptStarted() {
        notificationCoordinator.onPermissionPromptStarted()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationCoordinator.onPermissionResult(granted)
    }

    fun disconnect(clearSavedPairing: Boolean = false) {
        runBusyAction {
            service.disconnect(clearSavedPairing)
        }
    }

    fun refreshThreads() {
        runBusyAction("Loading threads...") {
            service.refreshThreads()
        }
    }

    fun openThread(
        threadId: String,
        forceRefresh: Boolean = false,
    ) {
        clearComposerAutocomplete()
        uiStateFlow.update {
            it.copy(
                gitCommitDialog = null,
                gitBranchDialog = null,
                gitWorktreeDialog = null,
                gitAlert = null,
                pendingGitBranchOperation = null,
                pendingGitRemoveWorktree = null,
            )
        }
        ThreadOpenPerfLogger.startAttempt(threadId, stage = "MainViewModel.openThread:start")
        runBusyAction("Loading messages...") {
            ThreadOpenPerfLogger.measure(threadId, stage = "MainViewModel.openThread") {
                service.openThread(threadId, forceRefresh = forceRefresh)
            }
            refreshGitStateAfterThreadOpen(threadId)
        }
    }

    fun closeThread() {
        clearComposerAutocomplete()
        uiStateFlow.update {
            it.copy(
                gitCommitDialog = null,
                gitBranchDialog = null,
                gitWorktreeDialog = null,
                gitAlert = null,
                pendingGitBranchOperation = null,
                pendingGitRemoveWorktree = null,
            )
        }
        service.closeThread()
    }

    fun createThread(projectPath: String? = null) {
        clearComposerAutocomplete()
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isBusy = true, busyLabel = "Creating thread...") }
            var createdThreadId: String? = null
            var workingDirectory: String? = null
            try {
                service.createThread(projectPath)
                createdThreadId = service.state.value.selectedThreadId
                workingDirectory = createdThreadId?.let { threadId ->
                    gitWorkingDirectoryForThread(threadId, uiStateFlow.value)
                }
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Request failed.")
            } finally {
                uiStateFlow.update { it.copy(isBusy = false, busyLabel = null) }
            }

            if (createdThreadId != null && workingDirectory != null) {
                loadGitSnapshot(
                    threadId = createdThreadId,
                    workingDirectory = workingDirectory,
                    loadDiff = false,
                    suppressErrors = true,
                )
            }
        }
    }

    fun sendMessage() {
        val current = uiStateFlow.value
        val rawInput = current.composerText
        val subagentsEnabled = current.isComposerSubagentsEnabled
        val threadId = current.selectedThreadId ?: return
        val composerAttachments = current.composerAttachmentsByThread[threadId].orEmpty()
        val hasBlockingAttachmentState = composerAttachments.hasBlockingState()
        val readyAttachments = composerAttachments.readyAttachments()
        val mentionedFiles = current.composerMentionedFilesByThread[threadId].orEmpty()
        val mentionedSkills = current.composerMentionedSkillsByThread[threadId].orEmpty()
        val fileMentions = buildTurnFileMentions(mentionedFiles)
        val reviewSelection = current.composerReviewSelectionByThread[threadId]
        val payload = buildComposerPayloadText(
            text = rawInput,
            mentionedFiles = mentionedFiles,
            isSubagentsSelected = subagentsEnabled,
        )
        val skillMentions = buildTurnSkillMentions(mentionedSkills)
        if ((payload.isEmpty() && readyAttachments.isEmpty() && reviewSelection == null)
            || hasBlockingAttachmentState
            || current.isSendingMessage
            || current.isInterruptingSelectedThread
            || current.isBusy
        ) {
            return
        }
        val collaborationMode = if (current.isComposerPlanMode
            && current.supportsCollaborationMode(CollaborationModeKind.PLAN)
        ) {
            CollaborationModeKind.PLAN
        } else {
            null
        }
        val knownActiveTurnId = current.activeTurnIdByThread[threadId]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (reviewSelection != null) {
            if (current.isThreadRunning(threadId)) {
                service.reportError("Finish the current run before starting a code review.")
                return
            }
            if (current.composerText.trim().isNotEmpty()
                || current.composerAttachmentsByThread[threadId].orEmpty().isNotEmpty()
                || current.composerMentionedFilesByThread[threadId].orEmpty().isNotEmpty()
                || current.composerMentionedSkillsByThread[threadId].orEmpty().isNotEmpty()
                || current.isComposerPlanMode
                || current.isComposerSubagentsEnabled
            ) {
                service.reportError("Clear text, files, skills, images, and subagents before starting a code review.")
                return
            }
            uiStateFlow.update {
                it.copy(
                    composerText = "",
                    composerDraftsByThread = it.composerDraftsByThread - threadId,
                    composerReviewSelectionByThread = it.composerReviewSelectionByThread - threadId,
                    composerAttachmentsByThread = it.composerAttachmentsByThread - threadId,
                    composerMentionedFilesByThread = it.composerMentionedFilesByThread - threadId,
                    composerMentionedSkillsByThread = it.composerMentionedSkillsByThread - threadId,
                    composerSubagentsByThread = it.composerSubagentsByThread - threadId,
                    isComposerSubagentsEnabled = false,
                    isSendingMessage = true,
                )
            }
            clearComposerAutocomplete()
            viewModelScope.launch {
                try {
                    service.startReview(
                        threadId = threadId,
                        target = reviewSelection.target,
                        baseBranch = reviewSelection.baseBranch,
                    )
                } catch (error: Throwable) {
                    uiStateFlow.update {
                        it.copy(
                            composerReviewSelectionByThread = it.composerReviewSelectionByThread + (
                                threadId to reviewSelection
                            ),
                            isComposerSubagentsEnabled = threadId in it.composerSubagentsByThread,
                        )
                    }
                    service.reportError(error.message ?: "Failed to start code review.")
                } finally {
                    uiStateFlow.update { it.copy(isSendingMessage = false) }
                    flushEligibleQueues()
                }
            }
            return
        }

        if (knownActiveTurnId != null) {
            queueFollowUpDraft(
                threadId = threadId,
                rawInput = rawInput,
                attachments = readyAttachments,
                collaborationMode = collaborationMode,
                subagentsEnabled = subagentsEnabled,
                mentionedFiles = mentionedFiles,
                mentionedSkills = mentionedSkills,
            )
            return
        }

        if (current.isThreadRunning(threadId)) {
            uiStateFlow.update { it.copy(isSendingMessage = true) }
            viewModelScope.launch {
                try {
                    if (service.shouldQueueFollowUp(threadId)) {
                        queueFollowUpDraft(
                            threadId = threadId,
                            rawInput = rawInput,
                            attachments = readyAttachments,
                            collaborationMode = collaborationMode,
                            subagentsEnabled = subagentsEnabled,
                            mentionedFiles = mentionedFiles,
                            mentionedSkills = mentionedSkills,
                        )
                    } else {
                        performImmediateComposerSend(
                            threadId = threadId,
                            rawInput = rawInput,
                            payload = payload,
                            composerAttachments = composerAttachments,
                            readyAttachments = readyAttachments,
                            mentionedFiles = mentionedFiles,
                            mentionedSkills = mentionedSkills,
                            fileMentions = fileMentions,
                            skillMentions = skillMentions,
                            collaborationMode = collaborationMode,
                            subagentsEnabled = subagentsEnabled,
                            manageSendingState = false,
                        )
                    }
                } catch (error: Throwable) {
                    service.reportError(error.message ?: "Failed to confirm the active run state.")
                } finally {
                    uiStateFlow.update { it.copy(isSendingMessage = false) }
                    flushEligibleQueues()
                }
            }
            return
        }

        uiStateFlow.update {
            it.copy(
                isSendingMessage = true,
            )
        }
        viewModelScope.launch {
            performImmediateComposerSend(
                threadId = threadId,
                rawInput = rawInput,
                payload = payload,
                composerAttachments = composerAttachments,
                readyAttachments = readyAttachments,
                mentionedFiles = mentionedFiles,
                mentionedSkills = mentionedSkills,
                fileMentions = fileMentions,
                skillMentions = skillMentions,
                collaborationMode = collaborationMode,
                subagentsEnabled = subagentsEnabled,
                manageSendingState = true,
            )
        }
    }

    fun interruptSelectedThread() {
        val current = uiStateFlow.value
        val threadId = current.selectedThreadId ?: return
        if (current.isInterruptingSelectedThread || current.isSendingMessage || current.isBusy) {
            return
        }
        uiStateFlow.update { it.copy(isInterruptingSelectedThread = true) }
        viewModelScope.launch {
            try {
                service.interruptThread(threadId)
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Failed to stop the active run.")
            } finally {
                uiStateFlow.update { it.copy(isInterruptingSelectedThread = false) }
            }
        }
    }

    fun updateToolInputAnswer(
        requestId: String,
        questionId: String,
        value: String,
    ) {
        uiStateFlow.update { current ->
            val normalizedRequestId = requestId.trim().takeIf { it.isNotEmpty() } ?: return@update current
            val normalizedQuestionId = questionId.trim().takeIf { it.isNotEmpty() } ?: return@update current
            val existingAnswers = current.toolInputAnswersByRequest[normalizedRequestId].orEmpty()
            val nextAnswers = if (value.isBlank()) {
                existingAnswers - normalizedQuestionId
            } else {
                existingAnswers + (normalizedQuestionId to value)
            }
            current.copy(
                toolInputAnswersByRequest = current.toolInputAnswersByRequest.updatedToolInputAnswers(
                    requestId = normalizedRequestId,
                    answers = nextAnswers,
                ),
            )
        }
    }

    fun submitToolInput(requestId: String) {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: return
        val request = snapshot.pendingToolInputsByThread[threadId]?.get(requestId) ?: return
        if (requestId in snapshot.submittingToolInputRequestIds
            || snapshot.isBusy
            || snapshot.isSendingMessage
            || snapshot.isInterruptingSelectedThread
        ) {
            return
        }

        val answers = snapshot.toolInputAnswersByRequest[requestId].orEmpty()
        if (!request.hasCompleteAnswerSet(answers)) {
            service.reportError("Answer each prompt before submitting.")
            return
        }

        uiStateFlow.update {
            it.copy(submittingToolInputRequestIds = it.submittingToolInputRequestIds + requestId)
        }
        viewModelScope.launch {
            try {
                service.respondToToolUserInput(
                    threadId = threadId,
                    requestId = requestId,
                    answers = answers,
                )
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Failed to submit the prompt response.")
            } finally {
                uiStateFlow.update {
                    it.copy(submittingToolInputRequestIds = it.submittingToolInputRequestIds - requestId)
                }
            }
        }
    }

    fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        runBusyAction("Sending approval...") {
            service.respondToApproval(request, accept)
        }
    }

    fun loadRuntimeConfig() {
        viewModelScope.launch {
            service.loadRuntimeConfig()
        }
    }

    fun selectModel(modelId: String?) {
        viewModelScope.launch {
            service.selectModel(modelId)
        }
    }

    fun selectReasoningEffort(effort: String?) {
        viewModelScope.launch {
            service.selectReasoningEffort(effort)
        }
    }

    fun selectHostRuntimeTarget(targetKind: String) {
        runBusyAction("Switching runtime...") {
            service.selectHostRuntimeTarget(targetKind)
            service.loadRuntimeConfig()
        }
    }

    fun selectAccessMode(accessMode: AccessMode) {
        viewModelScope.launch {
            service.selectAccessMode(accessMode)
        }
    }

    fun selectServiceTier(serviceTier: ServiceTier?) {
        viewModelScope.launch {
            service.selectServiceTier(serviceTier)
        }
    }

    fun selectThreadReasoningOverride(effort: String?) {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        viewModelScope.launch {
            val currentOverride = uiStateFlow.value.threadRuntimeOverridesByThread[threadId]
            service.setThreadRuntimeOverride(
                threadId = threadId,
                runtimeOverride = ThreadRuntimeOverride(
                    reasoningEffort = effort,
                    serviceTierRawValue = currentOverride?.serviceTierRawValue,
                    overridesReasoning = effort != null,
                    overridesServiceTier = currentOverride?.overridesServiceTier == true,
                ),
            )
        }
    }

    fun selectThreadServiceTierOverride(serviceTier: ServiceTier?) {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        viewModelScope.launch {
            val currentOverride = uiStateFlow.value.threadRuntimeOverridesByThread[threadId]
            service.setThreadRuntimeOverride(
                threadId = threadId,
                runtimeOverride = ThreadRuntimeOverride(
                    reasoningEffort = currentOverride?.reasoningEffort,
                    serviceTierRawValue = serviceTier?.wireValue,
                    overridesReasoning = currentOverride?.overridesReasoning == true,
                    overridesServiceTier = serviceTier != null,
                ),
            )
        }
    }

    fun useThreadRuntimeDefaults() {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        viewModelScope.launch {
            service.setThreadRuntimeOverride(threadId, null)
        }
    }

    fun compactSelectedThread() {
        val current = uiStateFlow.value
        val threadId = current.selectedThreadId ?: return
        if (current.isBusy
            || current.isSendingMessage
            || current.isInterruptingSelectedThread
            || current.isThreadRunning(threadId)
            || current.selectedThreadMessageCount == 0
        ) {
            return
        }

        runBusyAction("Starting context compaction...") {
            service.compactThread(threadId)
        }
    }

    fun rollbackSelectedThread() {
        val current = uiStateFlow.value
        val threadId = current.selectedThreadId ?: return
        if (current.isBusy
            || current.isSendingMessage
            || current.isInterruptingSelectedThread
            || current.isThreadRunning(threadId)
            || current.selectedThreadMessageCount == 0
        ) {
            return
        }

        runBusyAction("Rolling back the last turn...") {
            service.rollbackThread(threadId)
            gitWorkingDirectoryForThread(threadId, uiStateFlow.value)?.let { workingDirectory ->
                loadGitSnapshot(
                    threadId = threadId,
                    workingDirectory = workingDirectory,
                    loadDiff = false,
                    suppressErrors = true,
                )
            }
        }
    }

    fun cleanSelectedThreadBackgroundTerminals() {
        val current = uiStateFlow.value
        val threadId = current.selectedThreadId ?: return
        if (current.isBusy
            || current.isSendingMessage
            || current.isInterruptingSelectedThread
            || current.isThreadRunning(threadId)
        ) {
            return
        }

        runBusyAction("Cleaning background terminals...") {
            service.cleanBackgroundTerminals(threadId)
        }
    }

    fun forkSelectedThread(preferredProjectPath: String?) {
        val current = uiStateFlow.value
        val threadId = current.selectedThreadId ?: return
        if (current.isBusy || current.isSendingMessage || current.isInterruptingSelectedThread || current.isThreadRunning(threadId)) {
            return
        }

        runBusyAction("Forking thread...") {
            service.forkThread(threadId, preferredProjectPath)
            val forkedThreadId = service.state.value.selectedThreadId ?: return@runBusyAction
            gitWorkingDirectoryForThread(forkedThreadId, uiStateFlow.value)?.let { workingDirectory ->
                loadGitSnapshot(
                    threadId = forkedThreadId,
                    workingDirectory = workingDirectory,
                    loadDiff = false,
                    suppressErrors = true,
                )
            }
        }
    }

    fun openProjectPicker() {
        uiStateFlow.update { it.copy(isProjectPickerOpen = true) }
        viewModelScope.launch {
            try {
                service.loadWorkspaceState()
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Failed to load projects.")
            }
        }
    }

    fun closeProjectPicker() {
        service.clearWorkspaceBrowser()
        uiStateFlow.update { it.copy(isProjectPickerOpen = false) }
    }

    fun loadRecentWorkspaces() {
        runBusyAction("Loading projects...") {
            service.loadWorkspaceState()
        }
    }

    fun browseWorkspace(path: String?) {
        viewModelScope.launch {
            try {
                service.browseWorkspace(path)
                uiStateFlow.update { it.copy(isProjectPickerOpen = true) }
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Failed to browse folders.")
            }
        }
    }

    fun updateWorkspaceBrowserPath(path: String) {
        service.updateWorkspaceBrowserPath(path)
    }

    fun consumeFocusedTurnId() {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        service.consumeFocusedTurnId(threadId)
    }

    fun activateWorkspace(path: String) {
        runBusyAction("Switching project...") {
            service.activateWorkspace(path)
            uiStateFlow.update { it.copy(isProjectPickerOpen = false) }
        }
    }

    internal fun canRunGitAction(
        isConnected: Boolean,
        isThreadRunning: Boolean,
        hasGitWorkingDirectory: Boolean,
    ): Boolean {
        return isConnected && hasGitWorkingDirectory && !isThreadRunning
    }

    private fun canStartGitAction(
        threadId: String,
        workingDirectory: String,
        state: AndrodexUiState = uiStateFlow.value,
    ): Boolean {
        return canRunGitAction(
            isConnected = state.connectionStatus == ConnectionStatus.CONNECTED,
            isThreadRunning = state.isThreadRunning(threadId),
            hasGitWorkingDirectory = workingDirectory.isNotBlank(),
        ) && threadId !in state.runningGitActionByThread
            && !state.isBusy
            && !state.isSendingMessage
            && !state.isInterruptingSelectedThread
    }

    private fun refreshGitStateAfterThreadOpen(threadId: String) {
        val snapshot = uiStateFlow.value
        val workingDirectory = gitWorkingDirectoryForThread(threadId, snapshot)
        if (workingDirectory == null) {
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "MainViewModel.openThread.gitSkipped",
                extra = "reason=no_working_directory",
            )
            return
        }
        val gitState = snapshot.gitStateForThread(threadId)
        val isMatchingRefreshInFlight = (gitState?.isRefreshing == true || gitState?.isLoadingBranchTargets == true) &&
            gitState.refreshWorkingDirectory == workingDirectory
        val isMatchingGitActionInFlight = snapshot.runningGitWorkingDirectoryByThread[threadId] == workingDirectory
        if (isMatchingRefreshInFlight || isMatchingGitActionInFlight) {
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "MainViewModel.openThread.gitSkipped",
                extra = "reason=load_in_flight",
            )
            return
        }
        ThreadOpenPerfLogger.logStage(
            threadId = threadId,
            stage = "MainViewModel.openThread.gitScheduled",
            extra = if (gitState.hasLoadedBranchContextFor(workingDirectory)) {
                "reason=refresh_cached_state"
            } else {
                "reason=missing_cached_state"
            },
        )
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            loadGitSnapshot(
                threadId = threadId,
                workingDirectory = workingDirectory,
                loadDiff = false,
                suppressErrors = true,
                reason = gitLoadReasonThreadOpen,
            )
        }
    }

    private suspend fun loadGitSnapshot(
        threadId: String,
        workingDirectory: String,
        loadDiff: Boolean,
        suppressErrors: Boolean,
        reason: String = "general",
    ) {
        if (!isCurrentGitWorkingDirectoryForThread(threadId, workingDirectory)) {
            return
        }
        val refreshRequestId = nextGitRefreshRequestId()
        val shouldLogPerf = reason == gitLoadReasonThreadOpen
        if (shouldLogPerf) {
            ThreadOpenPerfLogger.ensureAttempt(
                threadId = threadId,
                stage = "MainViewModel.loadGitSnapshot:start",
                extra = "loadDiff=$loadDiff suppressErrors=$suppressErrors",
            )
        }
        val loadStartedAt = System.currentTimeMillis()
        updateThreadGitState(threadId) { current ->
            val isDifferentWorkingDirectory = current.loadedWorkingDirectory != null
                && current.loadedWorkingDirectory != workingDirectory
            current.copy(
                status = if (isDifferentWorkingDirectory) null else current.status,
                branchTargets = if (isDifferentWorkingDirectory) null else current.branchTargets,
                diffPatch = if (isDifferentWorkingDirectory) null else current.diffPatch,
                isRefreshing = true,
                isLoadingBranchTargets = true,
                refreshWorkingDirectory = workingDirectory,
                refreshRequestId = refreshRequestId,
            )
        }
        try {
            val branchStatusStartedAt = System.currentTimeMillis()
            val branchTargets = repository.gitBranchesWithStatus(workingDirectory)
            if (shouldLogPerf) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "MainViewModel.loadGitSnapshot.branchStatus",
                    durationMs = System.currentTimeMillis() - branchStatusStartedAt,
                    extra = "hasStatus=${branchTargets.status != null}",
                )
            }
            val didApplyBranchStatus = updateThreadGitStateIfCurrentRefreshMatches(
                threadId = threadId,
                workingDirectory = workingDirectory,
                refreshRequestId = refreshRequestId,
            ) { current ->
                current.copy(
                    status = branchTargets.status ?: current.status,
                    branchTargets = branchTargets,
                    isRefreshing = loadDiff,
                    isLoadingBranchTargets = false,
                    loadedWorkingDirectory = workingDirectory,
                    loadedRefreshRequestId = refreshRequestId,
                    refreshWorkingDirectory = if (loadDiff) workingDirectory else null,
                    refreshRequestId = if (loadDiff) refreshRequestId else 0L,
                )
            }
            if (!didApplyBranchStatus) {
                return
            }
        } catch (error: Throwable) {
            val didApplyErrorState = updateThreadGitStateIfCurrentRefreshMatches(
                threadId = threadId,
                workingDirectory = workingDirectory,
                refreshRequestId = refreshRequestId,
            ) { current ->
                current.copy(
                    isRefreshing = false,
                    isLoadingBranchTargets = false,
                    refreshWorkingDirectory = null,
                    refreshRequestId = 0L,
                )
            }
            if (!didApplyErrorState) {
                return
            }
            if (shouldLogPerf) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "MainViewModel.loadGitSnapshot.statusError",
                    durationMs = System.currentTimeMillis() - loadStartedAt,
                    extra = "message=${error.message ?: "unknown"}",
                )
            }
            if (!suppressErrors) {
                reportGitFailure(error, "Failed to load Git status.")
            }
            return
        }

        if (!loadDiff) {
            if (shouldLogPerf) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "MainViewModel.loadGitSnapshot:complete",
                    durationMs = System.currentTimeMillis() - loadStartedAt,
                    extra = "loadDiff=false",
                )
            }
            return
        }

        try {
            val diffStartedAt = System.currentTimeMillis()
            val diff = repository.gitDiff(workingDirectory)
            if (shouldLogPerf) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "MainViewModel.loadGitSnapshot.diff",
                    durationMs = System.currentTimeMillis() - diffStartedAt,
                    extra = "hasDiff=${diff.patch.isNotBlank()}",
                )
            }
            updateThreadGitStateIfCurrentRefreshMatches(
                threadId = threadId,
                workingDirectory = workingDirectory,
                refreshRequestId = refreshRequestId,
            ) { current ->
                current.copy(
                    diffPatch = diff.patch.trim().ifEmpty { null },
                    isRefreshing = false,
                    refreshWorkingDirectory = null,
                    refreshRequestId = 0L,
                )
            }
        } catch (error: Throwable) {
            val didApplyErrorState = updateThreadGitStateIfCurrentRefreshMatches(
                threadId = threadId,
                workingDirectory = workingDirectory,
                refreshRequestId = refreshRequestId,
            ) { current ->
                current.copy(
                    isRefreshing = false,
                    refreshWorkingDirectory = null,
                    refreshRequestId = 0L,
                )
            }
            if (!didApplyErrorState) {
                return
            }
            if (shouldLogPerf) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "MainViewModel.loadGitSnapshot.diffError",
                    durationMs = System.currentTimeMillis() - loadStartedAt,
                    extra = "message=${error.message ?: "unknown"}",
                )
            }
            if (!suppressErrors) {
                reportGitFailure(error, "Failed to load the Git diff.")
            }
        } finally {
            if (shouldLogPerf) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "MainViewModel.loadGitSnapshot:complete",
                    durationMs = System.currentTimeMillis() - loadStartedAt,
                    extra = "loadDiff=$loadDiff",
                )
            }
        }
    }

    private suspend fun ensureGitBranchContextLoaded(
        threadId: String,
        workingDirectory: String,
        suppressErrors: Boolean = false,
    ): AndrodexUiState? {
        var snapshot = uiStateFlow.value
        val gitState = snapshot.gitStateByThread[threadId]
        if (gitState.hasLoadedBranchContextFor(workingDirectory) && gitState?.isLoadingBranchTargets != true) {
            return snapshot.takeIf { isSelectedGitContext(threadId, workingDirectory, it) }
        }
        if (gitState?.isLoadingBranchTargets == true && gitState.refreshWorkingDirectory == workingDirectory) {
            val inFlightRefreshRequestId = gitState.refreshRequestId
            snapshot = uiStateFlow.first { state ->
                val refreshedState = state.gitStateByThread[threadId]
                !isSelectedGitContext(threadId, workingDirectory, state)
                    || refreshedState.hasLoadedBranchContextForRequest(
                        workingDirectory = workingDirectory,
                        refreshRequestId = inFlightRefreshRequestId,
                    )
                    || refreshedState?.isLoadingBranchTargets != true
            }
            if (!isSelectedGitContext(threadId, workingDirectory, snapshot)) {
                return null
            }
            snapshot.takeIf {
                it.gitStateByThread[threadId].hasLoadedBranchContextForRequest(
                    workingDirectory = workingDirectory,
                    refreshRequestId = inFlightRefreshRequestId,
                )
            }?.let { return it }
        }
        if (!isSelectedGitContext(threadId, workingDirectory, snapshot)) {
            return null
        }
        loadGitSnapshot(
            threadId = threadId,
            workingDirectory = workingDirectory,
            loadDiff = false,
            suppressErrors = suppressErrors,
        )
        snapshot = uiStateFlow.value
        return snapshot.takeIf {
            isSelectedGitContext(threadId, workingDirectory, it)
                && it.gitStateByThread[threadId].hasLoadedBranchContextFor(workingDirectory)
        }
    }

    private fun performGitPull(threadId: String, workingDirectory: String) {
        runGitAction(threadId, workingDirectory, GitActionKind.PULL) {
            val result = repository.gitPull(workingDirectory)
            updateThreadGitStatusIfCurrentContext(threadId, workingDirectory, result.status)
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = true)
        }
    }

    private fun performGitCreateBranch(
        threadId: String,
        workingDirectory: String,
        branchName: String,
    ) {
        runGitAction(threadId, workingDirectory, GitActionKind.CREATE_BRANCH) {
            repository.gitCreateBranch(workingDirectory, branchName)
            uiStateFlow.update { it.copy(gitBranchDialog = null) }
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = false)
        }
    }

    private fun performGitSwitchBranch(
        threadId: String,
        workingDirectory: String,
        branch: String,
    ) {
        runGitAction(threadId, workingDirectory, GitActionKind.SWITCH_BRANCH) {
            repository.gitCheckout(workingDirectory, branch)
            uiStateFlow.update { it.copy(gitBranchDialog = null) }
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = false)
        }
    }

    private fun performGitCreateWorktree(
        threadId: String,
        workingDirectory: String,
        branchName: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ) {
        runGitAction(threadId, workingDirectory, GitActionKind.CREATE_WORKTREE) {
            repository.gitCreateWorktree(
                workingDirectory = workingDirectory,
                name = branchName,
                baseBranch = baseBranch,
                changeTransfer = changeTransfer,
            )
            uiStateFlow.update { it.copy(gitWorktreeDialog = null) }
            loadGitSnapshot(threadId, workingDirectory, loadDiff = false, suppressErrors = false)
        }
    }

    private fun continueGitBranchOperation(
        threadId: String,
        workingDirectory: String,
        operation: GitBranchUserOperation?,
    ) {
        when (operation) {
            is GitBranchUserOperation.Create -> performGitCreateBranch(
                threadId = threadId,
                workingDirectory = workingDirectory,
                branchName = operation.branchName,
            )
            is GitBranchUserOperation.SwitchTo -> performGitSwitchBranch(
                threadId = threadId,
                workingDirectory = workingDirectory,
                branch = operation.branchName,
            )
            is GitBranchUserOperation.CreateWorktree -> performGitCreateWorktree(
                threadId = threadId,
                workingDirectory = workingDirectory,
                branchName = operation.branchName,
                baseBranch = operation.baseBranch,
                changeTransfer = operation.changeTransfer,
            )
            null -> Unit
        }
    }

    private fun gitBranchAlert(
        state: AndrodexUiState,
        operation: GitBranchUserOperation,
    ): GitAlertState? {
        val threadId = state.selectedThreadId ?: return null
        val status = state.gitStateByThread[threadId]?.status ?: return null
        val branchTargets = state.gitStateByThread[threadId]?.branchTargets
        val currentBranch = (status.currentBranch ?: branchTargets?.currentBranch).orEmpty().trim()
        val defaultBranch = branchTargets?.defaultBranch.orEmpty().trim()
        val onDefaultBranch = currentBranch.isNotEmpty() && currentBranch == defaultBranch

        return when (operation) {
            is GitBranchUserOperation.Create -> {
                if (status.isDirty) {
                    GitAlertState(
                        title = "Bring local changes to '${operation.branchName}'?",
                        message = newBranchDirtyAlertMessage(
                            branchName = operation.branchName,
                            currentBranch = currentBranch,
                            defaultBranch = defaultBranch,
                            localOnlyCommitCount = status.localOnlyCommitCount,
                            files = status.files.map { it.path },
                        ),
                        buttons = listOf(
                            GitAlertButton("Cancel", GitAlertAction.DISMISS),
                            GitAlertButton("Carry to New Branch", GitAlertAction.CONTINUE_BRANCH_OPERATION),
                            GitAlertButton("Commit, Create & Switch", GitAlertAction.COMMIT_AND_CONTINUE_BRANCH_OPERATION),
                        ),
                    )
                } else if (onDefaultBranch && status.localOnlyCommitCount > 0) {
                    val commitLabel = if (status.localOnlyCommitCount == 1) {
                        "1 local commit"
                    } else {
                        "${status.localOnlyCommitCount} local commits"
                    }
                    GitAlertState(
                        title = "Local commits stay on $defaultBranch",
                        message = "$defaultBranch already has $commitLabel that are not on the remote. Creating '${operation.branchName}' starts the new branch from the current HEAD, but those commits stay in $defaultBranch's history.",
                        buttons = listOf(
                            GitAlertButton("Cancel", GitAlertAction.DISMISS),
                            GitAlertButton("Create Anyway", GitAlertAction.CONTINUE_BRANCH_OPERATION),
                        ),
                    )
                } else {
                    null
                }
            }
            is GitBranchUserOperation.SwitchTo -> {
                if (!status.isDirty) {
                    null
                } else {
                    GitAlertState(
                        title = "Commit changes before switching branch?",
                        message = dirtyBranchAlertMessage(
                            intro = "These local changes can block checkout or be hard to reason about after the switch. Commit them on ${currentBranch.ifEmpty { "the current branch" }} first, then switch to '${operation.branchName}'.",
                            files = status.files.map { it.path },
                        ),
                        buttons = listOf(
                            GitAlertButton("Cancel", GitAlertAction.DISMISS),
                            GitAlertButton("Commit & Switch", GitAlertAction.COMMIT_AND_CONTINUE_BRANCH_OPERATION),
                        ),
                    )
                }
            }
            is GitBranchUserOperation.CreateWorktree -> {
                if (status.isDirty && currentBranch != operation.baseBranch) {
                    val transferVerb = if (operation.changeTransfer == GitWorktreeChangeTransferMode.COPY) {
                        "copy"
                    } else {
                        "move"
                    }
                    dismissOnlyGitAlert(
                        title = "${transferVerb.replaceFirstChar(Char::titlecase)} local changes from the current branch",
                        message = "Creating '${operation.branchName}' can $transferVerb tracked local changes only from $currentBranch. Switch the base branch to '$currentBranch' or clean up local changes before creating the worktree.",
                    )
                } else if (onDefaultBranch && currentBranch == operation.baseBranch && status.localOnlyCommitCount > 0) {
                    val commitLabel = if (status.localOnlyCommitCount == 1) {
                        "1 local commit"
                    } else {
                        "${status.localOnlyCommitCount} local commits"
                    }
                    val dirtySuffix = if (status.isDirty) {
                        if (operation.changeTransfer == GitWorktreeChangeTransferMode.MOVE) {
                            " Tracked local changes will move into the new worktree; ignored files stay here."
                        } else {
                            " Tracked local changes will also be copied into the new worktree; ignored files stay here."
                        }
                    } else {
                        ""
                    }
                    GitAlertState(
                        title = "Local commits stay on $defaultBranch",
                        message = "$defaultBranch already has $commitLabel that are not on the remote. Creating the new worktree branch '${operation.branchName}' from ${operation.baseBranch} starts from the current HEAD, but those commits stay in $defaultBranch's history too.$dirtySuffix",
                        buttons = listOf(
                            GitAlertButton("Cancel", GitAlertAction.DISMISS),
                            GitAlertButton("Create Anyway", GitAlertAction.CONTINUE_BRANCH_OPERATION),
                        ),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun dirtyBranchAlertMessage(
        intro: String,
        files: List<String>,
    ): String {
        if (files.isEmpty()) {
            return intro
        }
        val previewFiles = files.take(3)
        val overflowCount = files.size - previewFiles.size
        return buildString {
            append(intro)
            append("\n\nFiles with local changes:\n")
            previewFiles.forEach { path ->
                append("• ")
                append(path)
                append('\n')
            }
            if (overflowCount > 0) {
                append("• +")
                append(overflowCount)
                append(" more files")
            } else {
                deleteCharAt(lastIndex)
            }
        }
    }

    private fun newBranchDirtyAlertMessage(
        branchName: String,
        currentBranch: String,
        defaultBranch: String,
        localOnlyCommitCount: Int,
        files: List<String>,
    ): String {
        val sourceBranch = currentBranch.ifEmpty { "the current branch" }
        val intro = if (defaultBranch.isNotEmpty()
            && sourceBranch == defaultBranch
            && localOnlyCommitCount > 0
        ) {
            val commitLabel = if (localOnlyCommitCount == 1) {
                "1 local commit"
            } else {
                "$localOnlyCommitCount local commits"
            }
            "$defaultBranch already has $commitLabel that are not on the remote. Those commits stay in $defaultBranch's history. You're creating '$branchName' from $sourceBranch. Carry your tracked changes onto the new branch, or commit first and then create and switch."
        } else {
            "You're creating '$branchName' from $sourceBranch. Carry your tracked changes onto the new branch, or commit first and then create and switch."
        }
        return dirtyBranchAlertMessage(intro = intro, files = files)
    }

    private fun dismissOnlyGitAlert(
        title: String,
        message: String,
    ): GitAlertState {
        return GitAlertState(
            title = title,
            message = message,
            buttons = listOf(
                GitAlertButton("OK", GitAlertAction.DISMISS),
            ),
        )
    }

    private fun runGitAction(
        threadId: String,
        workingDirectory: String,
        action: GitActionKind,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            var shouldRefreshAfterFailure = false
            markGitActionRunning(threadId, workingDirectory, action)
            try {
                block()
            } catch (error: Throwable) {
                reportGitFailure(error, "Git action failed.")
                shouldRefreshAfterFailure = true
            } finally {
                clearGitActionRunning(threadId)
                if (shouldRefreshAfterFailure) {
                    refreshGitStateAfterActionFailure(threadId, workingDirectory)
                }
            }
        }
    }

    private fun markGitActionRunning(
        threadId: String,
        workingDirectory: String,
        action: GitActionKind,
    ) {
        uiStateFlow.update {
            it.copy(
                runningGitActionByThread = it.runningGitActionByThread + (threadId to action),
                runningGitWorkingDirectoryByThread = it.runningGitWorkingDirectoryByThread + (threadId to workingDirectory),
            )
        }
    }

    private fun clearGitActionRunning(threadId: String) {
        uiStateFlow.update {
            it.copy(
                runningGitActionByThread = it.runningGitActionByThread - threadId,
                runningGitWorkingDirectoryByThread = it.runningGitWorkingDirectoryByThread - threadId,
            )
        }
    }

    private fun refreshGitStateAfterActionFailure(
        threadId: String,
        workingDirectory: String,
    ) {
        val snapshot = uiStateFlow.value
        if (!isCurrentGitWorkingDirectoryForThread(threadId, workingDirectory, snapshot)) {
            return
        }
        val gitState = snapshot.gitStateForThread(threadId)
        val hasMatchingRefreshInFlight = (gitState?.isRefreshing == true || gitState?.isLoadingBranchTargets == true) &&
            gitState.refreshWorkingDirectory == workingDirectory
        if (hasMatchingRefreshInFlight) {
            return
        }
        viewModelScope.launch {
            loadGitSnapshot(
                threadId = threadId,
                workingDirectory = workingDirectory,
                loadDiff = false,
                suppressErrors = true,
            )
        }
    }

    private fun reportGitFailure(
        error: Throwable,
        fallbackMessage: String,
    ) {
        service.reportError(error.message ?: fallbackMessage)
    }

    private fun updateThreadGitState(
        threadId: String,
        transform: (ThreadGitState) -> ThreadGitState,
    ) {
        uiStateFlow.update { current ->
            val existing = current.gitStateByThread[threadId] ?: ThreadGitState()
            current.copy(
                gitStateByThread = current.gitStateByThread + (threadId to transform(existing)),
            )
        }
    }

    private fun updateThreadGitStatusIfCurrentContext(
        threadId: String,
        workingDirectory: String,
        status: GitRepoSyncResult?,
    ) {
        if (status == null) {
            return
        }
        uiStateFlow.update { current ->
            if (!isCurrentGitWorkingDirectoryForThread(threadId, workingDirectory, current)) {
                return@update current
            }
            val existing = current.gitStateByThread[threadId] ?: ThreadGitState()
            current.copy(
                gitStateByThread = current.gitStateByThread + (
                    threadId to existing.copy(status = status)
                ),
            )
        }
    }

    private fun updateThreadGitStateIfCurrentRefreshMatches(
        threadId: String,
        workingDirectory: String,
        refreshRequestId: Long,
        transform: (ThreadGitState) -> ThreadGitState,
    ): Boolean {
        var updated = false
        uiStateFlow.update { current ->
            val existing = current.gitStateByThread[threadId] ?: ThreadGitState()
            if (existing.refreshWorkingDirectory != workingDirectory || existing.refreshRequestId != refreshRequestId) {
                return@update current
            }
            updated = true
            current.copy(
                gitStateByThread = current.gitStateByThread + (threadId to transform(existing)),
            )
        }
        return updated
    }

    private fun gitWorkingDirectoryForThread(
        threadId: String,
        state: AndrodexUiState,
    ): String? {
        return state.threads.firstOrNull { it.id == threadId }?.cwd
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: state.activeWorkspacePath?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isCurrentGitWorkingDirectoryForThread(
        threadId: String,
        workingDirectory: String,
        state: AndrodexUiState = uiStateFlow.value,
    ): Boolean {
        return gitWorkingDirectoryForThread(threadId, state) == workingDirectory
    }

    private fun isGitLoadInFlight(
        threadId: String,
        workingDirectory: String,
        state: AndrodexUiState = uiStateFlow.value,
    ): Boolean {
        val gitState = state.gitStateForThread(threadId)
        return (gitState?.isRefreshing == true || gitState?.isLoadingBranchTargets == true) &&
            gitState.refreshWorkingDirectory == workingDirectory
    }

    private fun isSelectedGitContext(
        threadId: String,
        workingDirectory: String,
        state: AndrodexUiState = uiStateFlow.value,
    ): Boolean {
        return state.selectedThreadId == threadId && gitWorkingDirectoryForThread(threadId, state) == workingDirectory
    }

    private fun queueFollowUpDraft(
        threadId: String,
        rawInput: String,
        attachments: List<ImageAttachment>,
        collaborationMode: CollaborationModeKind?,
        subagentsEnabled: Boolean,
        mentionedFiles: List<ComposerMentionedFile>,
        mentionedSkills: List<ComposerMentionedSkill>,
    ) {
        val queuedDraft = QueuedTurnDraft(
            id = UUID.randomUUID().toString(),
            text = rawInput,
            attachments = attachments,
            createdAtEpochMs = System.currentTimeMillis(),
            collaborationMode = collaborationMode,
            subagentsSelectionEnabled = subagentsEnabled,
            mentionedFiles = mentionedFiles,
            mentionedSkills = mentionedSkills,
        )
        uiStateFlow.update { state ->
            val existingQueueState = state.queuedDraftStateByThread[threadId] ?: ThreadQueuedDraftState()
            state.copy(
                composerText = "",
                composerDraftsByThread = state.composerDraftsByThread - threadId,
                composerSubagentsByThread = state.composerSubagentsByThread - threadId,
                composerAttachmentsByThread = state.composerAttachmentsByThread - threadId,
                composerMentionedFilesByThread = state.composerMentionedFilesByThread - threadId,
                composerMentionedSkillsByThread = state.composerMentionedSkillsByThread - threadId,
                isComposerSubagentsEnabled = false,
                queuedDraftStateByThread = state.queuedDraftStateByThread.updatedQueueEntry(
                    threadId = threadId,
                    queueState = existingQueueState.copy(
                        drafts = existingQueueState.drafts + queuedDraft,
                    ).normalized(),
                ),
            )
        }
        clearComposerAutocomplete()
    }

    private suspend fun performImmediateComposerSend(
        threadId: String,
        rawInput: String,
        payload: String,
        composerAttachments: List<ComposerImageAttachment>,
        readyAttachments: List<ImageAttachment>,
        mentionedFiles: List<ComposerMentionedFile>,
        mentionedSkills: List<ComposerMentionedSkill>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
        subagentsEnabled: Boolean,
        manageSendingState: Boolean,
    ) {
        uiStateFlow.update {
            it.copy(
                composerText = "",
                composerDraftsByThread = it.composerDraftsByThread - threadId,
                composerSubagentsByThread = it.composerSubagentsByThread - threadId,
                composerAttachmentsByThread = it.composerAttachmentsByThread - threadId,
                composerMentionedFilesByThread = it.composerMentionedFilesByThread - threadId,
                composerMentionedSkillsByThread = it.composerMentionedSkillsByThread - threadId,
                isComposerSubagentsEnabled = false,
            )
        }
        clearComposerAutocomplete()
        try {
            service.sendMessage(
                input = payload,
                preferredThreadId = threadId,
                attachments = readyAttachments,
                fileMentions = fileMentions,
                skillMentions = skillMentions,
                collaborationMode = collaborationMode,
            )
        } catch (error: Throwable) {
            uiStateFlow.update {
                val nextSubagents = if (subagentsEnabled) {
                    it.composerSubagentsByThread + threadId
                } else {
                    it.composerSubagentsByThread - threadId
                }
                it.copy(
                    composerText = rawInput,
                    composerDraftsByThread = it.composerDraftsByThread + (threadId to rawInput),
                    composerSubagentsByThread = nextSubagents,
                    composerAttachmentsByThread = it.composerAttachmentsByThread.updatedComposerAttachments(
                        threadId,
                        composerAttachments,
                    ),
                    composerMentionedFilesByThread = it.composerMentionedFilesByThread + (threadId to mentionedFiles),
                    composerMentionedSkillsByThread = it.composerMentionedSkillsByThread + (threadId to mentionedSkills),
                    isComposerSubagentsEnabled = threadId in nextSubagents,
                )
            }
            refreshComposerAutocomplete(rawInput)
            service.reportError(error.message ?: "Failed to send message.")
        } finally {
            if (manageSendingState) {
                uiStateFlow.update { it.copy(isSendingMessage = false) }
                flushEligibleQueues()
            }
        }
    }

    private fun flushEligibleQueues() {
        val snapshot = uiStateFlow.value
        if (snapshot.connectionStatus != ConnectionStatus.CONNECTED) {
            return
        }

        snapshot.queuedDraftStateByThread.forEach { (threadId, queueState) ->
            if (queueState.drafts.isNotEmpty()) {
                flushQueueIfPossible(threadId)
            }
        }
    }

    private fun flushQueueIfPossible(threadId: String) {
        var nextDraft: QueuedTurnDraft? = null
        var reservedDraft = false

        uiStateFlow.update { current ->
            if (threadId in current.queueFlushingThreadIds) {
                return@update current
            }
            val queueState = current.queuedDraftStateByThread[threadId] ?: return@update current
            if (queueState.drafts.isEmpty()
                || queueState.isPaused
                || current.connectionStatus != ConnectionStatus.CONNECTED
                || current.isThreadRunning(threadId)
            ) {
                return@update current
            }

            nextDraft = queueState.drafts.first()
            reservedDraft = true

            current.copy(
                queueFlushingThreadIds = current.queueFlushingThreadIds + threadId,
                queuedDraftStateByThread = current.queuedDraftStateByThread.updatedQueueEntry(
                    threadId = threadId,
                    queueState = queueState.copy(
                        drafts = queueState.drafts.drop(1),
                    ).normalized(),
                ),
            )
        }
        val draftToSend = nextDraft
        if (!reservedDraft || draftToSend == null) {
            return
        }

        viewModelScope.launch {
            try {
                val normalizedDraft = draftToSend.normalizedFor(uiStateFlow.value.collaborationModes)
                service.sendMessage(
                    input = buildComposerPayloadText(
                        text = normalizedDraft.text,
                        mentionedFiles = normalizedDraft.mentionedFiles,
                        isSubagentsSelected = normalizedDraft.subagentsSelectionEnabled,
                    ),
                    preferredThreadId = threadId,
                    attachments = normalizedDraft.attachments,
                    fileMentions = buildTurnFileMentions(normalizedDraft.mentionedFiles),
                    skillMentions = buildTurnSkillMentions(normalizedDraft.mentionedSkills),
                    collaborationMode = normalizedDraft.collaborationMode,
                )
            } catch (error: Throwable) {
                val queueMessage = error.message ?: "Failed to send the queued follow-up."
                uiStateFlow.update { current ->
                    val queueState = current.queuedDraftStateByThread[threadId] ?: ThreadQueuedDraftState()
                    current.copy(
                        queueFlushingThreadIds = current.queueFlushingThreadIds - threadId,
                        queuedDraftStateByThread = current.queuedDraftStateByThread.updatedQueueEntry(
                            threadId = threadId,
                            queueState = queueState.copy(
                                drafts = listOf(draftToSend.normalizedFor(current.collaborationModes)) + queueState.drafts,
                                pauseState = QueuePauseState.PAUSED,
                                pauseMessage = queueMessage,
                            ).normalized(),
                        ),
                    )
                }
                service.reportError("Queue paused: $queueMessage")
                return@launch
            }

            uiStateFlow.update { it.copy(queueFlushingThreadIds = it.queueFlushingThreadIds - threadId) }
        }
    }

    private fun updateQueuedDraftState(
        threadId: String,
        transform: (ThreadQueuedDraftState) -> ThreadQueuedDraftState,
    ) {
        uiStateFlow.update { current ->
            val queueState = current.queuedDraftStateByThread[threadId] ?: ThreadQueuedDraftState()
            current.copy(
                queuedDraftStateByThread = current.queuedDraftStateByThread.updatedQueueEntry(
                    threadId = threadId,
                    queueState = transform(queueState).normalized(),
                ),
            )
        }
    }

    private fun createInitialUiState(serviceState: AndrodexServiceState): AndrodexUiState {
        val persistedOnboardingSeen = firstPairingOnboardingStore.hasSeenFirstPairingOnboarding()
        val isAlreadyPaired = serviceState.hasSavedPairing || serviceState.trustedPairSnapshot != null
        val effectiveOnboardingSeen = persistedOnboardingSeen || isAlreadyPaired
        if (effectiveOnboardingSeen && !persistedOnboardingSeen) {
            firstPairingOnboardingStore.markFirstPairingOnboardingSeen()
        }
        return applyServiceState(
            current = AndrodexUiState(
                hasSeenFirstPairingOnboarding = effectiveOnboardingSeen,
                isFirstPairingOnboardingActive = !effectiveOnboardingSeen && !isAlreadyPaired,
            ),
            serviceState = serviceState,
        )
    }

    private fun syncFirstPairingOnboardingSeenFlag(serviceState: AndrodexServiceState) {
        val isPaired = serviceState.hasSavedPairing || serviceState.trustedPairSnapshot != null
        if (!isPaired || uiStateFlow.value.hasSeenFirstPairingOnboarding) {
            return
        }
        firstPairingOnboardingStore.markFirstPairingOnboardingSeen()
        uiStateFlow.update {
            it.copy(hasSeenFirstPairingOnboarding = true)
        }
    }

    private fun runBusyAction(label: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isBusy = true, busyLabel = label) }
            try {
                block()
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Request failed.")
            } finally {
                uiStateFlow.update { it.copy(isBusy = false, busyLabel = null) }
            }
        }
    }

    private fun refreshComposerAutocomplete(text: String) {
        val snapshot = uiStateFlow.value
        val threadId = snapshot.selectedThreadId ?: run {
            clearComposerAutocomplete()
            return
        }
        val root = selectedComposerRoot(snapshot) ?: run {
            clearComposerAutocomplete()
            return
        }

        val mentionedFiles = snapshot.composerMentionedFilesByThread[threadId].orEmpty()
        val fileToken = trailingFileAutocompleteToken(text)
        if (fileToken != null && !hasClosedConfirmedFileMentionPrefix(text, mentionedFiles)) {
            val query = fileToken.query.trim()
            clearSkillAutocomplete()
            clearSlashCommandAutocomplete()
            if (query.length < 2) {
                fileAutocompleteJob?.cancel()
                uiStateFlow.update {
                    it.copy(
                        composerFileAutocompleteItems = emptyList(),
                        isFileAutocompleteVisible = false,
                        isFileAutocompleteLoading = false,
                        fileAutocompleteQuery = query,
                    )
                }
                return
            }

            val cancellationToken = "$threadId:${System.currentTimeMillis()}"
            uiStateFlow.update {
                it.copy(
                    composerFileAutocompleteItems = emptyList(),
                    isFileAutocompleteVisible = true,
                    isFileAutocompleteLoading = true,
                    fileAutocompleteQuery = query,
                )
            }
            fileAutocompleteJob?.cancel()
            fileAutocompleteJob = viewModelScope.launch {
                delay(autocompleteDebounceMs)
                runCatching {
                    repository.fuzzyFileSearch(
                        query = query,
                        roots = listOf(root),
                        cancellationToken = cancellationToken,
                    )
                }.onSuccess { matches ->
                    uiStateFlow.update { current ->
                        if (current.fileAutocompleteQuery != query) {
                            current
                        } else {
                            current.copy(
                                composerFileAutocompleteItems = matches.take(6),
                                isFileAutocompleteVisible = matches.isNotEmpty(),
                                isFileAutocompleteLoading = false,
                            )
                        }
                    }
                }.onFailure {
                    uiStateFlow.update { current ->
                        if (current.fileAutocompleteQuery != query) {
                            current
                        } else {
                            current.copy(
                                composerFileAutocompleteItems = emptyList(),
                                isFileAutocompleteVisible = false,
                                isFileAutocompleteLoading = false,
                            )
                        }
                    }
                }
            }
            return
        }

        val skillToken = trailingSkillAutocompleteToken(text)
        if (skillToken != null) {
            val query = skillToken.query.trim()
            clearFileAutocomplete()
            clearSlashCommandAutocomplete()
            if (query.length < 2) {
                skillAutocompleteJob?.cancel()
                uiStateFlow.update {
                    it.copy(
                        composerSkillAutocompleteItems = emptyList(),
                        isSkillAutocompleteVisible = false,
                        isSkillAutocompleteLoading = false,
                        skillAutocompleteQuery = query,
                    )
                }
                return
            }

            uiStateFlow.update {
                it.copy(
                    composerSkillAutocompleteItems = emptyList(),
                    isSkillAutocompleteVisible = true,
                    isSkillAutocompleteLoading = true,
                    skillAutocompleteQuery = query,
                )
            }
            skillAutocompleteJob?.cancel()
            skillAutocompleteJob = viewModelScope.launch {
                delay(autocompleteDebounceMs)
                runCatching {
                    repository.listSkills(listOf(root))
                        .filter { it.enabled }
                        .filter {
                            val searchBlob = listOfNotNull(it.name, it.description).joinToString("\n").lowercase()
                            searchBlob.contains(query.lowercase())
                        }
                        .take(6)
                }.onSuccess { skills ->
                    uiStateFlow.update { current ->
                        if (current.skillAutocompleteQuery != query) {
                            current
                        } else {
                            current.copy(
                                composerSkillAutocompleteItems = skills,
                                isSkillAutocompleteVisible = skills.isNotEmpty(),
                                isSkillAutocompleteLoading = false,
                            )
                        }
                    }
                }.onFailure {
                    uiStateFlow.update { current ->
                        if (current.skillAutocompleteQuery != query) {
                            current
                        } else {
                            current.copy(
                                composerSkillAutocompleteItems = emptyList(),
                                isSkillAutocompleteVisible = false,
                                isSkillAutocompleteLoading = false,
                            )
                        }
                    }
                }
            }
            return
        }

        val slashToken = trailingSlashCommandToken(text)
        if (slashToken != null) {
            clearFileAutocomplete()
            clearSkillAutocomplete()
            val commands = ComposerSlashCommand.filtered(slashToken.query)
            uiStateFlow.update {
                it.copy(
                    composerSlashCommandItems = commands,
                    isSlashCommandAutocompleteVisible = commands.isNotEmpty(),
                    slashCommandQuery = slashToken.query,
                )
            }
            return
        }

        clearComposerAutocomplete()
    }

    private fun clearComposerAutocomplete() {
        clearFileAutocomplete()
        clearSkillAutocomplete()
        clearSlashCommandAutocomplete()
    }

    private fun clearFileAutocomplete() {
        fileAutocompleteJob?.cancel()
        fileAutocompleteJob = null
        uiStateFlow.update {
            it.copy(
                composerFileAutocompleteItems = emptyList(),
                isFileAutocompleteVisible = false,
                isFileAutocompleteLoading = false,
                fileAutocompleteQuery = "",
            )
        }
    }

    private fun clearSkillAutocomplete() {
        skillAutocompleteJob?.cancel()
        skillAutocompleteJob = null
        uiStateFlow.update {
            it.copy(
                composerSkillAutocompleteItems = emptyList(),
                isSkillAutocompleteVisible = false,
                isSkillAutocompleteLoading = false,
                skillAutocompleteQuery = "",
            )
        }
    }

    private fun clearSlashCommandAutocomplete() {
        uiStateFlow.update {
            it.copy(
                composerSlashCommandItems = emptyList(),
                isSlashCommandAutocompleteVisible = false,
                slashCommandQuery = "",
            )
        }
    }

    private fun canArmReviewSelection(
        state: AndrodexUiState,
        threadId: String,
    ): Boolean {
        return state.composerText.trim().isEmpty()
            && state.composerAttachmentsByThread[threadId].orEmpty().isEmpty()
            && state.composerMentionedFilesByThread[threadId].orEmpty().isEmpty()
            && state.composerMentionedSkillsByThread[threadId].orEmpty().isEmpty()
            && !state.isComposerPlanMode
            && !state.isComposerSubagentsEnabled
            && state.composerReviewSelectionByThread[threadId] == null
    }

    private fun clearComposerReviewSelectionIfNeededForNonReviewContent() {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        if (uiStateFlow.value.composerReviewSelectionByThread[threadId] == null) {
            return
        }
        if (uiStateFlow.value.composerText.trim().isNotEmpty()
            || uiStateFlow.value.composerAttachmentsByThread[threadId].orEmpty().isNotEmpty()
            || uiStateFlow.value.composerMentionedFilesByThread[threadId].orEmpty().isNotEmpty()
            || uiStateFlow.value.composerMentionedSkillsByThread[threadId].orEmpty().isNotEmpty()
            || uiStateFlow.value.isComposerPlanMode
            || uiStateFlow.value.isComposerSubagentsEnabled
        ) {
            clearComposerReviewSelection()
        }
    }

    private fun AndrodexUiState.gitStateForThread(threadId: String): ThreadGitState? {
        return gitStateByThread[threadId]
    }

    private fun ThreadGitState?.hasLoadedBranchContext(): Boolean {
        return this?.status != null && this.branchTargets != null
    }

    private fun ThreadGitState?.hasLoadedBranchContextFor(workingDirectory: String): Boolean {
        return hasLoadedBranchContext() && this?.loadedWorkingDirectory == workingDirectory
    }

    private fun ThreadGitState?.hasLoadedBranchContextForRequest(
        workingDirectory: String,
        refreshRequestId: Long,
    ): Boolean {
        return hasLoadedBranchContextFor(workingDirectory) && this?.loadedRefreshRequestId == refreshRequestId
    }

    private fun nextGitRefreshRequestId(): Long {
        nextGitRefreshRequestCounter += 1L
        return nextGitRefreshRequestCounter
    }

    private fun selectedComposerRoot(state: AndrodexUiState): String? {
        return state.selectedThreadId
            ?.let { threadId -> state.threads.firstOrNull { it.id == threadId }?.cwd }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: state.activeWorkspacePath?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun Uri.extractPairingPayload(): String? {
    return extractPairingPayloadFromUriString(toString())
}

internal fun extractPairingPayloadFromUriString(rawUri: String): String? {
    return extractMirrorPairingPayloadFromUriString(rawUri)
}

private fun applyServiceState(
    current: AndrodexUiState,
    serviceState: AndrodexServiceState,
): AndrodexUiState {
    val activeThreadId = serviceState.selectedThreadId ?: current.selectedThreadId
    return ThreadOpenPerfLogger.measure(
        threadId = activeThreadId,
        stage = "MainViewModel.applyServiceState",
        extra = {
            "serviceMessages=${serviceState.selectedThreadMessageCount} threads=${serviceState.threads.size}"
        },
    ) {
        applyServiceStateSnapshot(current = current, serviceState = serviceState)
    }
}

private fun applyServiceStateSnapshot(
    current: AndrodexUiState,
    serviceState: AndrodexServiceState,
): AndrodexUiState {
    val selectedThreadId = serviceState.selectedThreadId
    val normalizedComposerPlanModes = current.composerPlanModeByThread.normalizedFor(serviceState.collaborationModes)
    val normalizedQueuedDraftState = current.queuedDraftStateByThread.normalizedFor(serviceState.collaborationModes)
    val selectedThreadComposerText = serviceState.selectedThreadId
        ?.let { current.composerDraftsByThread[it] }
        .orEmpty()
    val selectedThreadPlanMode = serviceState.selectedThreadId
        ?.let { it in normalizedComposerPlanModes }
        ?: false
    val selectedThreadSubagents = serviceState.selectedThreadId
        ?.let { it in current.composerSubagentsByThread }
        ?: false
    val pendingToolInputRequestIds = serviceState.pendingToolInputsByThread.values
        .flatMap { it.keys }
        .toSet()
    val pendingToolInputByRequestId = serviceState.pendingToolInputsByThread.values
        .flatMap { it.values }
        .associateBy { it.requestId }
    val toolInputAnswersByRequest = current.toolInputAnswersByRequest.mapNotNull { (requestId, answers) ->
        val questionIds = pendingToolInputByRequestId[requestId]
            ?.questions
            ?.mapTo(mutableSetOf()) { it.id }
            ?: return@mapNotNull null
        val filteredAnswers = answers.filterKeys { it in questionIds }
        if (filteredAnswers.isEmpty()) {
            null
        } else {
            requestId to filteredAnswers
        }
    }.toMap()
    val hasPairingIdentity = serviceState.hasSavedPairing || serviceState.trustedPairSnapshot != null
    return current.copy(
        hasSavedPairing = serviceState.hasSavedPairing,
        trustedPairSnapshot = serviceState.trustedPairSnapshot,
        freshPairingAttempt = serviceState.freshPairingAttempt,
        hasSeenFirstPairingOnboarding = current.hasSeenFirstPairingOnboarding || hasPairingIdentity,
        isFirstPairingOnboardingActive = current.isFirstPairingOnboardingActive && !hasPairingIdentity,
        hostAccountSnapshot = serviceState.hostAccountSnapshot,
        hostRuntimeMetadata = serviceState.hostRuntimeMetadata,
        defaultRelayUrl = serviceState.defaultRelayUrl,
        connectionStatus = serviceState.connectionStatus,
        connectionDetail = serviceState.connectionDetail,
        secureFingerprint = serviceState.secureFingerprint,
        threads = serviceState.threads,
        hasLoadedThreadList = serviceState.hasLoadedThreadList,
        isLoadingThreadList = serviceState.isLoadingThreadList,
        selectedThreadId = serviceState.selectedThreadId,
        selectedThreadTitle = serviceState.selectedThreadTitle,
        selectedThreadRenderSnapshot = serviceState.selectedThreadRenderSnapshot,
        selectedThreadMessageCount = serviceState.selectedThreadMessageCount,
        messages = serviceState.messages.takeIf { serviceState.selectedThreadRenderSnapshot == null } ?: emptyList(),
        focusedTurnId = serviceState.focusedTurnId,
        activeTurnIdByThread = serviceState.activeTurnIdByThread,
        runningThreadIds = serviceState.runningThreadIds,
        protectedRunningFallbackThreadIds = serviceState.protectedRunningFallbackThreadIds,
        readyThreadIds = serviceState.readyThreadIds,
        failedThreadIds = serviceState.failedThreadIds,
        composerText = selectedThreadComposerText,
        composerAttachmentsByThread = current.composerAttachmentsByThread,
        composerMentionedFilesByThread = current.composerMentionedFilesByThread,
        composerMentionedSkillsByThread = current.composerMentionedSkillsByThread,
        composerFileAutocompleteItems = if (selectedThreadId == null) emptyList() else current.composerFileAutocompleteItems,
        isFileAutocompleteVisible = selectedThreadId != null && current.isFileAutocompleteVisible,
        isFileAutocompleteLoading = selectedThreadId != null && current.isFileAutocompleteLoading,
        fileAutocompleteQuery = if (selectedThreadId == null) "" else current.fileAutocompleteQuery,
        composerSkillAutocompleteItems = if (selectedThreadId == null) emptyList() else current.composerSkillAutocompleteItems,
        isSkillAutocompleteVisible = selectedThreadId != null && current.isSkillAutocompleteVisible,
        isSkillAutocompleteLoading = selectedThreadId != null && current.isSkillAutocompleteLoading,
        skillAutocompleteQuery = if (selectedThreadId == null) "" else current.skillAutocompleteQuery,
        composerSlashCommandItems = if (selectedThreadId == null) emptyList() else current.composerSlashCommandItems,
        isSlashCommandAutocompleteVisible = selectedThreadId != null && current.isSlashCommandAutocompleteVisible,
        slashCommandQuery = if (selectedThreadId == null) "" else current.slashCommandQuery,
        composerPlanModeByThread = normalizedComposerPlanModes,
        composerSubagentsByThread = current.composerSubagentsByThread,
        isComposerPlanMode = selectedThreadPlanMode,
        isComposerSubagentsEnabled = selectedThreadSubagents,
        queuedDraftStateByThread = normalizedQueuedDraftState,
        isLoadingRuntimeConfig = serviceState.isLoadingRuntimeConfig,
        availableModels = serviceState.availableModels,
        selectedModelId = serviceState.selectedModelId,
        selectedReasoningEffort = serviceState.selectedReasoningEffort,
        selectedAccessMode = serviceState.selectedAccessMode,
        selectedServiceTier = serviceState.selectedServiceTier,
        supportsServiceTier = serviceState.supportsServiceTier,
        supportsThreadCompaction = serviceState.supportsThreadCompaction,
        supportsThreadRollback = serviceState.supportsThreadRollback,
        supportsBackgroundTerminalCleanup = serviceState.supportsBackgroundTerminalCleanup,
        supportsThreadFork = serviceState.supportsThreadFork,
        collaborationModes = serviceState.collaborationModes,
        threadRuntimeOverridesByThread = serviceState.threadRuntimeOverridesByThread,
        activeWorkspacePath = serviceState.activeWorkspacePath,
        recentWorkspaces = serviceState.recentWorkspaces,
        workspaceBrowserPath = serviceState.workspaceBrowserPath,
        workspaceBrowserParentPath = serviceState.workspaceBrowserParentPath,
        workspaceBrowserEntries = serviceState.workspaceBrowserEntries,
        isWorkspaceBrowserLoading = serviceState.isWorkspaceBrowserLoading,
        missingNotificationThreadPrompt = serviceState.missingNotificationThreadPrompt,
        errorMessage = serviceState.errorMessage,
        pendingApproval = serviceState.pendingApproval,
        pendingToolInputsByThread = serviceState.pendingToolInputsByThread,
        toolInputAnswersByRequest = toolInputAnswersByRequest,
        submittingToolInputRequestIds = current.submittingToolInputRequestIds.intersect(pendingToolInputRequestIds),
    )
}

private fun AndrodexUiState.isThreadRunning(threadId: String): Boolean {
    return threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds
}

private fun AndrodexUiState.supportsCollaborationMode(mode: CollaborationModeKind): Boolean {
    return mode in collaborationModes
}

private fun CollaborationModeKind?.normalizedFor(
    collaborationModes: Set<CollaborationModeKind>,
): CollaborationModeKind? {
    return this?.takeIf { it in collaborationModes }
}

private fun QueuedTurnDraft.normalizedFor(
    collaborationModes: Set<CollaborationModeKind>,
): QueuedTurnDraft {
    val normalizedMode = collaborationMode.normalizedFor(collaborationModes)
    return if (normalizedMode == collaborationMode) {
        this
    } else {
        copy(collaborationMode = normalizedMode)
    }
}

private fun ThreadQueuedDraftState.normalized(): ThreadQueuedDraftState {
    return if (drafts.isEmpty()) {
        ThreadQueuedDraftState()
    } else {
        copy(
            pauseMessage = if (pauseState == QueuePauseState.PAUSED) pauseMessage else null,
        )
    }
}

private fun Map<String, ThreadQueuedDraftState>.updatedQueueEntry(
    threadId: String,
    queueState: ThreadQueuedDraftState,
): Map<String, ThreadQueuedDraftState> {
    return if (queueState.drafts.isEmpty()) {
        this - threadId
    } else {
        this + (threadId to queueState)
    }
}

private fun Set<String>.normalizedFor(collaborationModes: Set<CollaborationModeKind>): Set<String> {
    return if (CollaborationModeKind.PLAN in collaborationModes) this else emptySet()
}

private fun Map<String, ThreadQueuedDraftState>.normalizedFor(
    collaborationModes: Set<CollaborationModeKind>,
): Map<String, ThreadQueuedDraftState> {
    return entries.mapNotNull { (threadId, queueState) ->
        val normalizedQueueState = queueState.copy(
            drafts = queueState.drafts.map { it.normalizedFor(collaborationModes) },
        ).normalized()
        normalizedQueueState.takeIf { it.drafts.isNotEmpty() }?.let { threadId to it }
    }.toMap()
}

private fun Map<String, String>.updatedThreadDraft(
    threadId: String,
    value: String,
): Map<String, String> {
    return if (value.isEmpty()) {
        this - threadId
    } else {
        this + (threadId to value)
    }
}

private fun Map<String, Map<String, String>>.updatedToolInputAnswers(
    requestId: String,
    answers: Map<String, String>,
): Map<String, Map<String, String>> {
    return if (answers.isEmpty()) {
        this - requestId
    } else {
        this + (requestId to answers)
    }
}

private fun Map<String, List<ComposerImageAttachment>>.updatedComposerAttachments(
    threadId: String,
    attachments: List<ComposerImageAttachment>,
): Map<String, List<ComposerImageAttachment>> {
    return if (attachments.isEmpty()) {
        this - threadId
    } else {
        this + (threadId to attachments)
    }
}

private fun ToolUserInputRequest.hasCompleteAnswerSet(answers: Map<String, String>): Boolean {
    if (questions.isEmpty()) {
        return false
    }
    return questions.all { question ->
        answers[question.id]?.trim()?.isNotEmpty() == true
    }
}

private fun Map<String, List<ComposerMentionedFile>>.updatedMentionedFiles(
    threadId: String,
    files: List<ComposerMentionedFile>,
): Map<String, List<ComposerMentionedFile>> {
    return if (files.isEmpty()) {
        this - threadId
    } else {
        this + (threadId to files)
    }
}

private fun Map<String, List<ComposerMentionedSkill>>.updatedMentionedSkills(
    threadId: String,
    skills: List<ComposerMentionedSkill>,
): Map<String, List<ComposerMentionedSkill>> {
    return if (skills.isEmpty()) {
        this - threadId
    } else {
        this + (threadId to skills)
    }
}

private fun Set<String>.updatedPlanMode(
    threadId: String,
    enabled: Boolean,
): Set<String> {
    return if (enabled) {
        this + threadId
    } else {
        this - threadId
    }
}

private fun fileMentionCollisionKey(fileName: String): String? {
    return fileName
        .substringBeforeLast('.', missingDelimiterValue = fileName)
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .takeIf { it.isNotEmpty() }
}
