package io.androdex.android

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ModelOption
import io.androdex.android.model.QueuePauseState
import io.androdex.android.model.QueuedTurnDraft
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadQueuedDraftState
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.service.AndrodexService
import io.androdex.android.service.AndrodexServiceState
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
    val isComposerPlanMode: Boolean = false,
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
    private var lastConnectionStatus: ConnectionStatus = service.state.value.connectionStatus

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
        uiStateFlow.update { current ->
            val threadId = current.selectedThreadId
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
        if (current.isSendingMessage || current.isInterruptingSelectedThread || current.isBusy || current.composerText.isNotEmpty()) {
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
            state.copy(
                composerText = draft.text,
                composerDraftsByThread = state.composerDraftsByThread + (threadId to draft.text),
                composerPlanModeByThread = nextPlanModes,
                isComposerPlanMode = threadId in nextPlanModes,
                queuedDraftStateByThread = state.queuedDraftStateByThread.updatedQueueEntry(
                    threadId = threadId,
                    queueState = queueState.copy(drafts = nextDrafts).normalized(),
                ),
            )
        }
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
        runBusyAction("Loading messages...") {
            service.openThread(threadId)
        }
    }

    fun closeThread() {
        service.closeThread()
    }

    fun createThread() {
        runBusyAction("Creating thread...") {
            service.createThread()
        }
    }

    fun sendMessage() {
        val current = uiStateFlow.value
        val input = current.composerText.trim()
        if (input.isEmpty() || current.isSendingMessage || current.isInterruptingSelectedThread || current.isBusy) {
            return
        }
        val collaborationMode = if (current.isComposerPlanMode) {
            CollaborationModeKind.PLAN
        } else {
            null
        }

        val threadId = current.selectedThreadId
        if (threadId != null && current.isThreadRunning(threadId)) {
            val queuedDraft = QueuedTurnDraft(
                id = UUID.randomUUID().toString(),
                text = input,
                createdAtEpochMs = System.currentTimeMillis(),
                collaborationMode = collaborationMode,
            )
            uiStateFlow.update { state ->
                val existingQueueState = state.queuedDraftStateByThread[threadId] ?: ThreadQueuedDraftState()
                state.copy(
                    composerText = "",
                    composerDraftsByThread = state.composerDraftsByThread - threadId,
                    queuedDraftStateByThread = state.queuedDraftStateByThread.updatedQueueEntry(
                        threadId = threadId,
                        queueState = existingQueueState.copy(
                            drafts = existingQueueState.drafts + queuedDraft,
                        ).normalized(),
                    ),
                )
            }
            return
        }

        uiStateFlow.update {
            it.copy(
                composerText = "",
                composerDraftsByThread = threadId?.let { id -> it.composerDraftsByThread - id } ?: it.composerDraftsByThread,
                isSendingMessage = true,
            )
        }
        viewModelScope.launch {
            try {
                service.sendMessage(
                    input = input,
                    preferredThreadId = threadId,
                    collaborationMode = collaborationMode,
                )
            } catch (error: Throwable) {
                uiStateFlow.update {
                    it.copy(
                        composerText = input,
                        composerDraftsByThread = threadId?.let { id -> it.composerDraftsByThread + (id to input) }
                            ?: it.composerDraftsByThread,
                    )
                }
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
                    input = draftToSend.text,
                    preferredThreadId = threadId,
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
    val selectedThreadComposerText = serviceState.selectedThreadId
        ?.let { current.composerDraftsByThread[it] }
        .orEmpty()
    val selectedThreadPlanMode = serviceState.selectedThreadId
        ?.let { it in current.composerPlanModeByThread }
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
        composerPlanModeByThread = current.composerPlanModeByThread,
        isComposerPlanMode = selectedThreadPlanMode,
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
