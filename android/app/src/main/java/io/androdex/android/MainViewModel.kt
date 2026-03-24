package io.androdex.android

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

private const val savedReconnectRetryDelayMs = 5_000L

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
    private val repository: AndrodexRepositoryContract,
) : ViewModel() {
    private var savedReconnectInFlight = false
    private var savedReconnectRetryJob: Job? = null
    private var suppressSavedReconnect = false

    private val uiStateFlow = MutableStateFlow(
        AndrodexUiState(
            hasSavedPairing = repository.hasSavedPairing(),
            defaultRelayUrl = AppEnvironment.defaultRelayUrl.takeIf { it.isNotEmpty() },
            secureFingerprint = repository.currentFingerprint(),
            errorMessage = repository.startupNotice(),
        )
    )

    val uiState: StateFlow<AndrodexUiState> = uiStateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            repository.updates.collect(::handleClientUpdate)
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
        uiStateFlow.update { it.copy(errorMessage = null) }
    }

    fun connectWithCurrentPairingInput() {
        val payload = uiStateFlow.value.pairingInput.trim()
        if (payload.isEmpty()) {
            uiStateFlow.update { it.copy(errorMessage = "Paste or scan the pairing payload first.") }
            return
        }

        suppressSavedReconnect = false
        cancelSavedReconnectRetry()
        runBusyAction {
            repository.connectWithPairingPayload(payload)
            repository.refreshThreads()
            loadWorkspaceState()
        }
    }

    fun reconnectSaved() {
        suppressSavedReconnect = false
        cancelSavedReconnectRetry()
        runBusyAction {
            repository.reconnectSaved()
            repository.refreshThreads()
            loadWorkspaceState()
        }
    }

    fun reconnectSavedIfAvailable() {
        val snapshot = uiStateFlow.value
        if (!snapshot.hasSavedPairing || savedReconnectInFlight) {
            return
        }
        if (snapshot.isBusy || snapshot.pairingInput.isNotBlank()) {
            return
        }
        if (snapshot.connectionStatus == ConnectionStatus.CONNECTED
            || snapshot.connectionStatus == ConnectionStatus.CONNECTING
            || snapshot.connectionStatus == ConnectionStatus.HANDSHAKING
        ) {
            return
        }

        suppressSavedReconnect = false
        scheduleSavedReconnectRetry(immediate = true)
    }

    fun disconnect(clearSavedPairing: Boolean = false) {
        suppressSavedReconnect = true
        cancelSavedReconnectRetry()
        runBusyAction {
            repository.disconnect(clearSavedPairing)
        }
    }

    fun refreshThreads() {
        runBusyAction("Loading threads...") {
            repository.refreshThreads()
            loadWorkspaceState()
        }
    }

    fun openThread(threadId: String) {
        val targetThread = uiStateFlow.value.threads.firstOrNull { it.id == threadId }
        uiStateFlow.update { current ->
            current.copy(
                selectedThreadId = threadId,
                selectedThreadTitle = targetThread?.title,
                messages = emptyList(),
            )
        }

        runBusyAction("Loading messages...") {
            ensureWorkspaceActivated(targetThread?.cwd)
            repository.loadThread(threadId)
        }
    }

    fun closeThread() {
        uiStateFlow.update {
            it.copy(
                selectedThreadId = null,
                selectedThreadTitle = null,
                messages = emptyList(),
            )
        }
    }

    fun createThread() {
        runBusyAction("Creating thread...") {
            val preferredWorkspace = uiStateFlow.value.activeWorkspacePath
            ensureWorkspaceActivated(preferredWorkspace)
            val thread = repository.startThread(preferredWorkspace)
            repository.refreshThreads()
            loadWorkspaceState()
            uiStateFlow.update { current ->
                current.copy(
                    selectedThreadId = thread.id,
                    selectedThreadTitle = thread.title.ifBlank { "Conversation" },
                    messages = emptyList(),
                )
            }
        }
    }

    fun sendMessage() {
        val input = uiStateFlow.value.composerText.trim()
        if (input.isEmpty()) {
            return
        }

        runBusyAction("Sending message...") {
            val preferredWorkspace = uiStateFlow.value.activeWorkspacePath
            val threadId = uiStateFlow.value.selectedThreadId ?: repository.startThread(preferredWorkspace).id.also { newThreadId ->
                repository.refreshThreads()
                loadWorkspaceState()
                uiStateFlow.update { current ->
                    current.copy(
                        selectedThreadId = newThreadId,
                        selectedThreadTitle = current.threads.firstOrNull { it.id == newThreadId }?.title ?: "Conversation",
                    )
                }
            }

            uiStateFlow.update { current ->
                current.copy(
                    composerText = "",
                    messages = current.messages + ConversationMessage(
                        id = UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = input,
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }

            repository.startTurn(threadId, input)
        }
    }

    fun respondToApproval(accept: Boolean) {
        val request = uiStateFlow.value.pendingApproval ?: return
        runBusyAction("Sending approval...") {
            repository.respondToApproval(request, accept)
        }
    }

    fun loadRuntimeConfig() {
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isLoadingRuntimeConfig = true) }
            try {
                repository.loadRuntimeConfig()
            } catch (_: Throwable) {
            } finally {
                uiStateFlow.update { it.copy(isLoadingRuntimeConfig = false) }
            }
        }
    }

    fun selectModel(modelId: String?) {
        viewModelScope.launch {
            repository.setSelectedModelId(modelId)
        }
    }

    fun selectReasoningEffort(effort: String?) {
        viewModelScope.launch {
            repository.setSelectedReasoningEffort(effort)
        }
    }

    fun openProjectPicker() {
        uiStateFlow.update { it.copy(isProjectPickerOpen = true) }
        viewModelScope.launch {
            try {
                loadWorkspaceState()
            } catch (error: Throwable) {
                uiStateFlow.update { current ->
                    current.copy(errorMessage = error.message ?: "Failed to load projects.")
                }
            }
        }
    }

    fun closeProjectPicker() {
        uiStateFlow.update {
            it.copy(
                isProjectPickerOpen = false,
                workspaceBrowserPath = null,
                workspaceBrowserParentPath = null,
                workspaceBrowserEntries = emptyList(),
                isWorkspaceBrowserLoading = false,
            )
        }
    }

    fun loadRecentWorkspaces() {
        runBusyAction("Loading projects...") {
            loadWorkspaceState()
        }
    }

    fun browseWorkspace(path: String?) {
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isWorkspaceBrowserLoading = true) }
            try {
                val result = repository.listWorkspaceDirectory(path)
                uiStateFlow.update {
                    it.copy(
                        activeWorkspacePath = result.activeCwd,
                        recentWorkspaces = result.recentWorkspaces,
                        workspaceBrowserPath = result.requestedPath,
                        workspaceBrowserParentPath = result.parentPath,
                        workspaceBrowserEntries = if (result.requestedPath == null) result.rootEntries else result.entries,
                        isWorkspaceBrowserLoading = false,
                        isProjectPickerOpen = true,
                    )
                }
            } catch (error: Throwable) {
                uiStateFlow.update {
                    it.copy(
                        isWorkspaceBrowserLoading = false,
                        errorMessage = error.message ?: "Failed to browse folders.",
                    )
                }
            }
        }
    }

    fun updateWorkspaceBrowserPath(path: String) {
        uiStateFlow.update { it.copy(workspaceBrowserPath = path) }
    }

    fun activateWorkspace(path: String) {
        runBusyAction("Switching project...") {
            val status = repository.activateWorkspace(path)
            repository.refreshThreads()
            val recent = repository.listRecentWorkspaces()
            uiStateFlow.update {
                it.copy(
                    activeWorkspacePath = status.currentCwd,
                    recentWorkspaces = recent.recentWorkspaces,
                    isProjectPickerOpen = false,
                    workspaceBrowserPath = null,
                    workspaceBrowserParentPath = null,
                    workspaceBrowserEntries = emptyList(),
                    selectedThreadId = null,
                    selectedThreadTitle = null,
                    messages = emptyList(),
                )
            }
        }
    }

    private fun runBusyAction(label: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isBusy = true, busyLabel = label) }
            try {
                block()
            } catch (error: Throwable) {
                uiStateFlow.update {
                    it.copy(errorMessage = error.message ?: "Request failed.")
                }
            } finally {
                uiStateFlow.update { it.copy(isBusy = false, busyLabel = null) }
            }
        }
    }

    private fun handleClientUpdate(update: ClientUpdate) {
        when (update) {
            is ClientUpdate.Connection -> {
                uiStateFlow.update {
                    it.copy(
                        connectionStatus = update.status,
                        connectionDetail = update.detail,
                        secureFingerprint = update.fingerprint ?: it.secureFingerprint,
                    )
                }
                when (update.status) {
                    ConnectionStatus.CONNECTED -> {
                        cancelSavedReconnectRetry()
                        loadRuntimeConfig()
                        openProjectPicker()
                    }

                    ConnectionStatus.RETRYING_SAVED_PAIRING -> {
                        scheduleSavedReconnectRetry()
                    }

                    ConnectionStatus.DISCONNECTED,
                    ConnectionStatus.RECONNECT_REQUIRED,
                    ConnectionStatus.UPDATE_REQUIRED -> {
                        cancelSavedReconnectRetry()
                    }

                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.HANDSHAKING -> Unit
                }
            }

            is ClientUpdate.PairingAvailability -> {
                uiStateFlow.update {
                    it.copy(
                        hasSavedPairing = update.hasSavedPairing,
                        secureFingerprint = update.fingerprint,
                    )
                }
                if (!update.hasSavedPairing) {
                    suppressSavedReconnect = false
                    cancelSavedReconnectRetry()
                }
            }

            is ClientUpdate.ThreadsLoaded -> {
                uiStateFlow.update { current ->
                    current.copy(
                        threads = update.threads,
                        selectedThreadTitle = update.threads.firstOrNull { it.id == current.selectedThreadId }?.title
                            ?: current.selectedThreadTitle,
                    )
                }
            }

            is ClientUpdate.ThreadLoaded -> {
                uiStateFlow.update { current ->
                    current.copy(
                        selectedThreadTitle = update.thread?.title ?: current.selectedThreadTitle,
                        messages = update.messages,
                    )
                }
            }

            is ClientUpdate.RuntimeConfigLoaded -> {
                uiStateFlow.update {
                    it.copy(
                        availableModels = update.models,
                        selectedModelId = update.selectedModelId,
                        selectedReasoningEffort = update.selectedReasoningEffort,
                    )
                }
            }

            is ClientUpdate.AssistantDelta -> {
                val selectedThreadId = uiStateFlow.value.selectedThreadId
                if (update.threadId != null && selectedThreadId != null && update.threadId != selectedThreadId) {
                    return
                }
                uiStateFlow.update { current ->
                    current.copy(messages = applyAssistantDelta(current.messages, current.selectedThreadId, update))
                }
            }

            is ClientUpdate.AssistantCompleted -> {
                uiStateFlow.update { current ->
                    current.copy(messages = applyAssistantCompletion(current.messages, current.selectedThreadId, update))
                }
            }

            is ClientUpdate.ApprovalRequested -> {
                uiStateFlow.update { it.copy(pendingApproval = update.request) }
            }

            ClientUpdate.ApprovalCleared -> {
                uiStateFlow.update { it.copy(pendingApproval = null) }
            }

            is ClientUpdate.TurnCompleted -> {
                val selectedThreadId = uiStateFlow.value.selectedThreadId
                if (selectedThreadId != null && (update.threadId == null || update.threadId == selectedThreadId)) {
                    openThread(selectedThreadId)
                } else {
                    refreshThreads()
                }
            }

            is ClientUpdate.Error -> {
                uiStateFlow.update { it.copy(errorMessage = update.message) }
            }
        }
    }

    private fun scheduleSavedReconnectRetry(immediate: Boolean = false) {
        val snapshot = uiStateFlow.value
        if (suppressSavedReconnect || !snapshot.hasSavedPairing || snapshot.pairingInput.isNotBlank()) {
            return
        }
        if (savedReconnectRetryJob?.isActive == true) {
            return
        }

        savedReconnectRetryJob = viewModelScope.launch {
            if (!immediate) {
                delay(savedReconnectRetryDelayMs)
            }
            while (isActive) {
                val state = uiStateFlow.value
                if (suppressSavedReconnect || !state.hasSavedPairing || state.pairingInput.isNotBlank()) {
                    break
                }
                if (state.connectionStatus == ConnectionStatus.CONNECTED) {
                    break
                }
                if (savedReconnectInFlight) {
                    delay(savedReconnectRetryDelayMs)
                    continue
                }

                savedReconnectInFlight = true
                try {
                    repository.reconnectSaved()
                    repository.refreshThreads()
                    loadWorkspaceState()
                    break
                } catch (_: Throwable) {
                    delay(savedReconnectRetryDelayMs)
                } finally {
                    savedReconnectInFlight = false
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (savedReconnectRetryJob === job) {
                    savedReconnectRetryJob = null
                }
            }
        }
    }

    private fun cancelSavedReconnectRetry() {
        savedReconnectRetryJob?.cancel()
        savedReconnectRetryJob = null
    }

    private fun applyAssistantDelta(
        messages: List<ConversationMessage>,
        selectedThreadId: String?,
        update: ClientUpdate.AssistantDelta,
    ): List<ConversationMessage> {
        val threadId = update.threadId ?: selectedThreadId ?: return messages
        val existingIndex = messages.indexOfLast { message ->
            message.role == ConversationRole.ASSISTANT
                && message.isStreaming
                && (update.turnId == null || message.turnId == update.turnId)
        }
        if (existingIndex >= 0) {
            val updated = messages.toMutableList()
            val message = updated[existingIndex]
            updated[existingIndex] = message.copy(text = message.text + update.delta)
            return updated
        }
        return messages + ConversationMessage(
            id = update.itemId ?: UUID.randomUUID().toString(),
            threadId = threadId,
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = update.delta,
            createdAtEpochMs = System.currentTimeMillis(),
            turnId = update.turnId,
            itemId = update.itemId,
            isStreaming = true,
        )
    }

    private fun applyAssistantCompletion(
        messages: List<ConversationMessage>,
        selectedThreadId: String?,
        update: ClientUpdate.AssistantCompleted,
    ): List<ConversationMessage> {
        val threadId = update.threadId ?: selectedThreadId ?: return messages
        val existingIndex = messages.indexOfLast { message ->
            message.role == ConversationRole.ASSISTANT
                && (update.turnId == null || message.turnId == update.turnId)
        }
        if (existingIndex >= 0) {
            val updated = messages.toMutableList()
            updated[existingIndex] = updated[existingIndex].copy(
                text = update.text,
                isStreaming = false,
            )
            return updated
        }
        return messages + ConversationMessage(
            id = update.itemId ?: UUID.randomUUID().toString(),
            threadId = threadId,
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = update.text,
            createdAtEpochMs = System.currentTimeMillis(),
            turnId = update.turnId,
            itemId = update.itemId,
            isStreaming = false,
        )
    }

    private suspend fun ensureWorkspaceActivated(path: String?) {
        val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (normalizedPath == uiStateFlow.value.activeWorkspacePath) {
            return
        }
        val status = repository.activateWorkspace(normalizedPath)
        uiStateFlow.update { it.copy(activeWorkspacePath = status.currentCwd ?: normalizedPath) }
    }

    private suspend fun loadWorkspaceState() {
        val recent = repository.listRecentWorkspaces()
        uiStateFlow.update {
            it.copy(
                activeWorkspacePath = recent.activeCwd,
                recentWorkspaces = recent.recentWorkspaces,
            )
        }
    }
}

private fun Uri.extractPairingPayload(): String? {
    return getQueryParameter("payload")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
