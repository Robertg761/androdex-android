package io.androdex.android

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.service.AndrodexService
import io.androdex.android.service.AndrodexServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        uiStateFlow.update { it.copy(composerText = value) }
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
        val input = uiStateFlow.value.composerText.trim()
        if (input.isEmpty()) {
            return
        }

        uiStateFlow.update { it.copy(composerText = "") }
        runBusyAction("Sending message...") {
            service.sendMessage(input)
        }
    }

    fun interruptSelectedThread() {
        val threadId = uiStateFlow.value.selectedThreadId ?: return
        viewModelScope.launch {
            try {
                service.interruptThread(threadId)
            } catch (error: Throwable) {
                service.reportError(error.message ?: "Failed to stop the active run.")
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
