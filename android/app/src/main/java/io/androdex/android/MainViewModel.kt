package io.androdex.android

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerMentionedFile
import io.androdex.android.model.ComposerMentionedSkill
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.ModelOption
import io.androdex.android.model.QueuePauseState
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadQueuedDraftState
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.service.AndrodexService
import io.androdex.android.service.AndrodexServiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AndrodexUiState(
    val pairingInput: String = "",
    val hasSavedPairing: Boolean = false,
    val defaultRelayUrl: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionDetail: String? = null,
    val secureFingerprint: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val selectedThreadId: String? = null,
    val selectedThreadTitle: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val activeTurnIdByThread: Map<String, String> = emptyMap(),
    val runningThreadIds: Set<String> = emptySet(),
    val protectedRunningFallbackThreadIds: Set<String> = emptySet(),
    val readyThreadIds: Set<String> = emptySet(),
    val failedThreadIds: Set<String> = emptySet(),
    val composerText: String = "",
    val composerDraftsByThread: Map<String, String> = emptyMap(),
    val composerPlanModeByThread: Set<String> = emptySet(),
    val composerSubagentsByThread: Set<String> = emptySet(),
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
    val activeWorkspacePath: String? = null,
    val recentWorkspaces: List<WorkspacePathSummary> = emptyList(),
    val workspaceBrowserPath: String? = null,
    val workspaceBrowserParentPath: String? = null,
    val workspaceBrowserEntries: List<WorkspaceDirectoryEntry> = emptyList(),
    val isProjectPickerOpen: Boolean = false,
    val isWorkspaceBrowserLoading: Boolean = false,
    val errorMessage: String? = null,
    val pendingApproval: ApprovalRequest? = null,
)

class MainViewModel(
    repository: AndrodexRepositoryContract,
) : ViewModel() {
    private val service = AndrodexService(repository, viewModelScope)
    private val repository = repository
    private var lastConnectionStatus: ConnectionStatus = service.state.value.connectionStatus
    private var fileAutocompleteJob: Job? = null
    private var skillAutocompleteJob: Job? = null
    private val autocompleteDebounceMs = 180L

    private val uiStateFlow = MutableStateFlow(
        applyServiceState(
            current = AndrodexUiState(),
            serviceState = service.state.value,
        )
    )

    val uiState: StateFlow<AndrodexUiState> = uiStateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            service.state.collect { serviceState ->
                uiStateFlow.update { current -> applyServiceState(current, serviceState) }
                flushEligibleQueues()
                if (lastConnectionStatus != ConnectionStatus.CONNECTED
                    && serviceState.connectionStatus == ConnectionStatus.CONNECTED
                ) {
                    uiStateFlow.update { it.copy(isProjectPickerOpen = true) }
                }
                lastConnectionStatus = serviceState.connectionStatus
            }
        }
    }

    fun consumeIntent(intent: Intent?) {
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val deepLinkText = intent?.data?.extractPairingPayload()
        val payload = sharedText?.takeIf { it.isNotEmpty() }
            ?: deepLinkText?.takeIf { it.isNotEmpty() }
            ?: return
        uiStateFlow.update { it.copy(pairingInput = payload) }
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
        refreshComposerAutocomplete(value)
    }

    fun updateComposerPlanMode(enabled: Boolean) {
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId ?: return@update current
            current.copy(
                composerPlanModeByThread = current.composerPlanModeByThread.updatedPlanMode(threadId, enabled),
                isComposerPlanMode = enabled,
            )
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
        if (!enabled) {
            clearSlashCommandAutocomplete()
        }
    }

    fun selectFileAutocomplete(item: FuzzyFileMatch) {
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
            || current.composerMentionedFilesByThread[threadId].orEmpty().isNotEmpty()
            || current.composerMentionedSkillsByThread[threadId].orEmpty().isNotEmpty()
            || current.isComposerSubagentsEnabled
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
            val nextPlanModes = state.composerPlanModeByThread.updatedPlanMode(
                threadId = threadId,
                enabled = draft.collaborationMode == CollaborationModeKind.PLAN,
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

    fun clearError() {
        service.clearError()
    }

    fun connectWithCurrentPairingInput() {
        val payload = uiStateFlow.value.pairingInput.trim()
        if (payload.isEmpty()) {
            service.reportError("Paste or scan the pairing payload first.")
            return
        }

        runBusyAction {
            service.connectWithPairingPayload(payload)
        }
    }

    fun reconnectSaved() {
        runBusyAction {
            service.reconnectSaved()
        }
    }

    fun reconnectSavedIfAvailable() {
        if (uiStateFlow.value.isBusy || uiStateFlow.value.pairingInput.isNotBlank()) {
            return
        }
        service.reconnectSavedIfAvailable()
    }

    fun onAppForegrounded() {
        service.onAppForegrounded()
        reconnectSavedIfAvailable()
    }

    fun onAppBackgrounded() {
        service.onAppBackgrounded()
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

    fun openThread(threadId: String) {
        clearComposerAutocomplete()
        runBusyAction("Loading messages...") {
            service.openThread(threadId)
        }
    }

    fun closeThread() {
        clearComposerAutocomplete()
        service.closeThread()
    }

    fun createThread() {
        clearComposerAutocomplete()
        runBusyAction("Creating thread...") {
            service.createThread()
        }
    }

    fun sendMessage() {
        val current = uiStateFlow.value
        val rawInput = current.composerText
        val subagentsEnabled = current.isComposerSubagentsEnabled
        val threadId = current.selectedThreadId
        val mentionedFiles = threadId?.let { current.composerMentionedFilesByThread[it].orEmpty() }.orEmpty()
        val mentionedSkills = threadId?.let { current.composerMentionedSkillsByThread[it].orEmpty() }.orEmpty()
        val payload = buildComposerPayloadText(
            text = rawInput,
            mentionedFiles = mentionedFiles,
            isSubagentsSelected = subagentsEnabled,
        )
        val skillMentions = buildTurnSkillMentions(mentionedSkills)
        if (payload.isEmpty() || current.isSendingMessage || current.isInterruptingSelectedThread || current.isBusy) {
            return
        }
        val collaborationMode = if (current.isComposerPlanMode) {
            CollaborationModeKind.PLAN
        } else {
            null
        }

        if (threadId != null && current.isThreadRunning(threadId)) {
            val queuedDraft = QueuedTurnDraft(
                id = UUID.randomUUID().toString(),
                text = rawInput,
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
            return
        }

        uiStateFlow.update {
            it.copy(
                composerText = "",
                composerDraftsByThread = threadId?.let { id -> it.composerDraftsByThread - id } ?: it.composerDraftsByThread,
                composerSubagentsByThread = threadId?.let { id -> it.composerSubagentsByThread - id }
                    ?: it.composerSubagentsByThread,
                composerMentionedFilesByThread = threadId?.let { id -> it.composerMentionedFilesByThread - id }
                    ?: it.composerMentionedFilesByThread,
                composerMentionedSkillsByThread = threadId?.let { id -> it.composerMentionedSkillsByThread - id }
                    ?: it.composerMentionedSkillsByThread,
                isComposerSubagentsEnabled = false,
                isSendingMessage = true,
            )
        }
        clearComposerAutocomplete()
        viewModelScope.launch {
            try {
                service.sendMessage(
                    input = payload,
                    preferredThreadId = threadId,
                    skillMentions = skillMentions,
                    collaborationMode = collaborationMode,
                )
            } catch (error: Throwable) {
                uiStateFlow.update {
                    val nextSubagents = threadId?.let { id ->
                        if (subagentsEnabled) it.composerSubagentsByThread + id else it.composerSubagentsByThread - id
                    } ?: it.composerSubagentsByThread
                    it.copy(
                        composerText = rawInput,
                        composerDraftsByThread = threadId?.let { id -> it.composerDraftsByThread + (id to rawInput) }
                            ?: it.composerDraftsByThread,
                        composerSubagentsByThread = nextSubagents,
                        composerMentionedFilesByThread = threadId?.let { id ->
                            it.composerMentionedFilesByThread + (id to mentionedFiles)
                        } ?: it.composerMentionedFilesByThread,
                        composerMentionedSkillsByThread = threadId?.let { id ->
                            it.composerMentionedSkillsByThread + (id to mentionedSkills)
                        } ?: it.composerMentionedSkillsByThread,
                        isComposerSubagentsEnabled = threadId?.let { id -> id in nextSubagents }
                            ?: it.isComposerSubagentsEnabled,
                    )
                }
                refreshComposerAutocomplete(rawInput)
                service.reportError(error.message ?: "Failed to send message.")
            } finally {
                uiStateFlow.update { it.copy(isSendingMessage = false) }
                flushEligibleQueues()
            }
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

    fun respondToApproval(accept: Boolean) {
        runBusyAction("Sending approval...") {
            service.respondToApproval(accept)
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

    fun activateWorkspace(path: String) {
        runBusyAction("Switching project...") {
            service.activateWorkspace(path)
            uiStateFlow.update { it.copy(isProjectPickerOpen = false) }
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
                service.sendMessage(
                    input = buildComposerPayloadText(
                        text = draftToSend.text,
                        mentionedFiles = draftToSend.mentionedFiles,
                        isSubagentsSelected = draftToSend.subagentsSelectionEnabled,
                    ),
                    preferredThreadId = threadId,
                    skillMentions = buildTurnSkillMentions(draftToSend.mentionedSkills),
                    collaborationMode = draftToSend.collaborationMode,
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
                                drafts = listOf(draftToSend) + queueState.drafts,
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

    private fun selectedComposerRoot(state: AndrodexUiState): String? {
        return state.selectedThreadId
            ?.let { threadId -> state.threads.firstOrNull { it.id == threadId }?.cwd }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: state.activeWorkspacePath?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun Uri.extractPairingPayload(): String? {
    return getQueryParameter("payload")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun applyServiceState(
    current: AndrodexUiState,
    serviceState: AndrodexServiceState,
): AndrodexUiState {
    val selectedThreadId = serviceState.selectedThreadId
    val selectedThreadComposerText = serviceState.selectedThreadId
        ?.let { current.composerDraftsByThread[it] }
        .orEmpty()
    val selectedThreadPlanMode = serviceState.selectedThreadId
        ?.let { it in current.composerPlanModeByThread }
        ?: false
    val selectedThreadSubagents = serviceState.selectedThreadId
        ?.let { it in current.composerSubagentsByThread }
        ?: false
    return current.copy(
        hasSavedPairing = serviceState.hasSavedPairing,
        defaultRelayUrl = serviceState.defaultRelayUrl,
        connectionStatus = serviceState.connectionStatus,
        connectionDetail = serviceState.connectionDetail,
        secureFingerprint = serviceState.secureFingerprint,
        threads = serviceState.threads,
        selectedThreadId = serviceState.selectedThreadId,
        selectedThreadTitle = serviceState.selectedThreadTitle,
        messages = serviceState.messages,
        activeTurnIdByThread = serviceState.activeTurnIdByThread,
        runningThreadIds = serviceState.runningThreadIds,
        protectedRunningFallbackThreadIds = serviceState.protectedRunningFallbackThreadIds,
        readyThreadIds = serviceState.readyThreadIds,
        failedThreadIds = serviceState.failedThreadIds,
        composerText = selectedThreadComposerText,
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
        composerPlanModeByThread = current.composerPlanModeByThread,
        composerSubagentsByThread = current.composerSubagentsByThread,
        isComposerPlanMode = selectedThreadPlanMode,
        isComposerSubagentsEnabled = selectedThreadSubagents,
        isLoadingRuntimeConfig = serviceState.isLoadingRuntimeConfig,
        availableModels = serviceState.availableModels,
        selectedModelId = serviceState.selectedModelId,
        selectedReasoningEffort = serviceState.selectedReasoningEffort,
        activeWorkspacePath = serviceState.activeWorkspacePath,
        recentWorkspaces = serviceState.recentWorkspaces,
        workspaceBrowserPath = serviceState.workspaceBrowserPath,
        workspaceBrowserParentPath = serviceState.workspaceBrowserParentPath,
        workspaceBrowserEntries = serviceState.workspaceBrowserEntries,
        isWorkspaceBrowserLoading = serviceState.isWorkspaceBrowserLoading,
        errorMessage = serviceState.errorMessage,
        pendingApproval = serviceState.pendingApproval,
    )
}

private fun AndrodexUiState.isThreadRunning(threadId: String): Boolean {
    return threadId in runningThreadIds || threadId in protectedRunningFallbackThreadIds
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
