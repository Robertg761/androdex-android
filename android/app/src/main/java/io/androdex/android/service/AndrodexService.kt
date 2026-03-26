package io.androdex.android.service

import io.androdex.android.AppEnvironment
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
import kotlinx.coroutines.CoroutineScope
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

data class AndrodexServiceState(
    val hasSavedPairing: Boolean = false,
    val defaultRelayUrl: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionDetail: String? = null,
    val secureFingerprint: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val selectedThreadId: String? = null,
    val selectedThreadTitle: String? = null,
    val timelineByThread: Map<String, List<ConversationMessage>> = emptyMap(),
    val activeTurnIdByThread: Map<String, String> = emptyMap(),
    val runningThreadIds: Set<String> = emptySet(),
    val isLoadingRuntimeConfig: Boolean = false,
    val availableModels: List<ModelOption> = emptyList(),
    val selectedModelId: String? = null,
    val selectedReasoningEffort: String? = null,
    val activeWorkspacePath: String? = null,
    val recentWorkspaces: List<WorkspacePathSummary> = emptyList(),
    val workspaceBrowserPath: String? = null,
    val workspaceBrowserParentPath: String? = null,
    val workspaceBrowserEntries: List<WorkspaceDirectoryEntry> = emptyList(),
    val isWorkspaceBrowserLoading: Boolean = false,
    val errorMessage: String? = null,
    val pendingApproval: ApprovalRequest? = null,
) {
    val messages: List<ConversationMessage>
        get() = selectedThreadId?.let { timelineByThread[it] }.orEmpty()
}

class AndrodexService(
    private val repository: AndrodexRepositoryContract,
    private val scope: CoroutineScope,
) {
    private var appInForeground = false
    private var savedReconnectInFlight = false
    private var savedReconnectRetryJob: Job? = null
    private var suppressSavedReconnect = false

    private val stateFlow = MutableStateFlow(
        AndrodexServiceState(
            hasSavedPairing = repository.hasSavedPairing(),
            defaultRelayUrl = AppEnvironment.defaultRelayUrl.takeIf { it.isNotEmpty() },
            secureFingerprint = repository.currentFingerprint(),
            errorMessage = repository.startupNotice(),
        )
    )

    val state: StateFlow<AndrodexServiceState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            repository.updates.collect(::processClientUpdate)
        }
    }

    fun clearError() {
        stateFlow.update { it.copy(errorMessage = null) }
    }

    fun reportError(message: String) {
        stateFlow.update { it.copy(errorMessage = message) }
    }

    fun closeThread() {
        stateFlow.update {
            it.copy(
                selectedThreadId = null,
                selectedThreadTitle = null,
            )
        }
    }

    fun onAppForegrounded() {
        appInForeground = true
        reconnectSavedIfAvailable()
    }

    fun onAppBackgrounded() {
        appInForeground = false
        cancelSavedReconnectRetry()
    }

    suspend fun connectWithPairingPayload(rawPayload: String) {
        suppressSavedReconnect = false
        cancelSavedReconnectRetry()
        repository.connectWithPairingPayload(rawPayload)
        refreshThreadsInternal()
        loadWorkspaceState()
    }

    suspend fun reconnectSaved() {
        suppressSavedReconnect = false
        cancelSavedReconnectRetry()
        repository.reconnectSaved()
        refreshThreadsInternal()
        loadWorkspaceState()
    }

    fun reconnectSavedIfAvailable() {
        if (!appInForeground) {
            return
        }
        val snapshot = stateFlow.value
        if (!snapshot.hasSavedPairing || savedReconnectInFlight) {
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

    suspend fun disconnect(clearSavedPairing: Boolean = false) {
        suppressSavedReconnect = true
        cancelSavedReconnectRetry()
        repository.disconnect(clearSavedPairing)
    }

    suspend fun refreshThreads() {
        refreshThreadsInternal()
        loadWorkspaceState()
    }

    suspend fun openThread(threadId: String) {
        val targetThread = stateFlow.value.threads.firstOrNull { it.id == threadId }
        stateFlow.update { current ->
            current.copy(
                selectedThreadId = threadId,
                selectedThreadTitle = targetThread?.title,
            )
        }

        ensureWorkspaceActivated(targetThread?.cwd)
        loadThreadIntoState(threadId)
    }

    suspend fun createThread() {
        val preferredWorkspace = stateFlow.value.activeWorkspacePath
        ensureWorkspaceActivated(preferredWorkspace)
        val thread = repository.startThread(preferredWorkspace)
        stateFlow.update { current ->
            current.copy(
                selectedThreadId = thread.id,
                selectedThreadTitle = thread.title.ifBlank { "Conversation" },
                timelineByThread = current.timelineByThread + (thread.id to emptyList()),
            )
        }
        refreshThreadsInternal()
        loadWorkspaceState()
    }

    suspend fun sendMessage(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) {
            return
        }

        val preferredWorkspace = stateFlow.value.activeWorkspacePath
        val threadId = stateFlow.value.selectedThreadId ?: repository.startThread(preferredWorkspace).id.also { newThreadId ->
            refreshThreadsInternal()
            loadWorkspaceState()
            stateFlow.update { current ->
                current.copy(
                    selectedThreadId = newThreadId,
                    selectedThreadTitle = current.threads.firstOrNull { it.id == newThreadId }?.title ?: "Conversation",
                    timelineByThread = current.timelineByThread + (newThreadId to current.timelineByThread[newThreadId].orEmpty()),
                )
            }
        }

        appendMessage(
            threadId = threadId,
            message = ConversationMessage(
                id = UUID.randomUUID().toString(),
                threadId = threadId,
                role = ConversationRole.USER,
                kind = ConversationKind.CHAT,
                text = trimmedInput,
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
        markThreadRunning(threadId = threadId, turnId = null)
        repository.startTurn(threadId, trimmedInput)
    }

    suspend fun respondToApproval(accept: Boolean) {
        val request = stateFlow.value.pendingApproval ?: return
        repository.respondToApproval(request, accept)
    }

    suspend fun loadRuntimeConfig() {
        stateFlow.update { it.copy(isLoadingRuntimeConfig = true) }
        try {
            repository.loadRuntimeConfig()
        } catch (error: Throwable) {
            reportError(error.message ?: "Failed to load runtime settings.")
        } finally {
            stateFlow.update { it.copy(isLoadingRuntimeConfig = false) }
        }
    }

    suspend fun selectModel(modelId: String?) {
        repository.setSelectedModelId(modelId)
    }

    suspend fun selectReasoningEffort(effort: String?) {
        repository.setSelectedReasoningEffort(effort)
    }

    suspend fun loadWorkspaceState() {
        val recent = repository.listRecentWorkspaces()
        stateFlow.update {
            it.copy(
                activeWorkspacePath = recent.activeCwd,
                recentWorkspaces = recent.recentWorkspaces,
            )
        }
    }

    fun clearWorkspaceBrowser() {
        stateFlow.update {
            it.copy(
                workspaceBrowserPath = null,
                workspaceBrowserParentPath = null,
                workspaceBrowserEntries = emptyList(),
                isWorkspaceBrowserLoading = false,
            )
        }
    }

    suspend fun browseWorkspace(path: String?) {
        stateFlow.update { it.copy(isWorkspaceBrowserLoading = true) }
        try {
            val result = repository.listWorkspaceDirectory(path)
            stateFlow.update {
                it.copy(
                    activeWorkspacePath = result.activeCwd,
                    recentWorkspaces = result.recentWorkspaces,
                    workspaceBrowserPath = result.requestedPath,
                    workspaceBrowserParentPath = result.parentPath,
                    workspaceBrowserEntries = if (result.requestedPath == null) result.rootEntries else result.entries,
                    isWorkspaceBrowserLoading = false,
                )
            }
        } catch (error: Throwable) {
            stateFlow.update {
                it.copy(
                    isWorkspaceBrowserLoading = false,
                    errorMessage = error.message ?: "Failed to browse folders.",
                )
            }
            throw error
        }
    }

    fun updateWorkspaceBrowserPath(path: String) {
        stateFlow.update { it.copy(workspaceBrowserPath = path) }
    }

    suspend fun activateWorkspace(path: String) {
        val status = repository.activateWorkspace(path)
        refreshThreadsInternal()
        val recent = repository.listRecentWorkspaces()
        stateFlow.update {
            it.copy(
                activeWorkspacePath = status.currentCwd,
                recentWorkspaces = recent.recentWorkspaces,
                workspaceBrowserPath = null,
                workspaceBrowserParentPath = null,
                workspaceBrowserEntries = emptyList(),
                selectedThreadId = null,
                selectedThreadTitle = null,
                isWorkspaceBrowserLoading = false,
            )
        }
    }

    private suspend fun refreshThreadsInternal() {
        repository.refreshThreads()
    }

    private suspend fun loadThreadIntoState(threadId: String) {
        val (summary, messages) = repository.loadThread(threadId)
        stateFlow.update { current ->
            current.copy(
                selectedThreadTitle = if (current.selectedThreadId == threadId) {
                    summary?.title ?: current.selectedThreadTitle
                } else {
                    current.selectedThreadTitle
                },
                timelineByThread = current.timelineByThread + (threadId to messages),
            )
        }
    }

    private suspend fun ensureWorkspaceActivated(path: String?) {
        val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (normalizedPath == stateFlow.value.activeWorkspacePath) {
            return
        }
        val status = repository.activateWorkspace(normalizedPath)
        stateFlow.update { it.copy(activeWorkspacePath = status.currentCwd ?: normalizedPath) }
    }

    internal fun processClientUpdate(update: ClientUpdate) {
        when (update) {
            is ClientUpdate.Connection -> {
                stateFlow.update {
                    it.copy(
                        connectionStatus = update.status,
                        connectionDetail = update.detail,
                        secureFingerprint = update.fingerprint ?: it.secureFingerprint,
                        pendingApproval = if (update.status == ConnectionStatus.CONNECTED) it.pendingApproval else null,
                        activeTurnIdByThread = if (update.status == ConnectionStatus.CONNECTED) it.activeTurnIdByThread else emptyMap(),
                        runningThreadIds = if (update.status == ConnectionStatus.CONNECTED) it.runningThreadIds else emptySet(),
                    )
                }
                when (update.status) {
                    ConnectionStatus.CONNECTED -> {
                        cancelSavedReconnectRetry()
                        scope.launch {
                            loadRuntimeConfig()
                            loadWorkspaceState()
                        }
                    }

                    ConnectionStatus.RETRYING_SAVED_PAIRING -> {
                        if (appInForeground) {
                            scheduleSavedReconnectRetry()
                        } else {
                            cancelSavedReconnectRetry()
                        }
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
                stateFlow.update {
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
                stateFlow.update { current ->
                    current.copy(
                        threads = update.threads,
                        selectedThreadTitle = update.threads.firstOrNull { it.id == current.selectedThreadId }?.title
                            ?: current.selectedThreadTitle,
                    )
                }
            }

            is ClientUpdate.ThreadLoaded -> {
                val threadId = update.thread?.id ?: stateFlow.value.selectedThreadId ?: return
                stateFlow.update { current ->
                    current.copy(
                        selectedThreadTitle = if (current.selectedThreadId == threadId) {
                            update.thread?.title ?: current.selectedThreadTitle
                        } else {
                            current.selectedThreadTitle
                        },
                        timelineByThread = current.timelineByThread + (threadId to update.messages),
                    )
                }
            }

            is ClientUpdate.RuntimeConfigLoaded -> {
                stateFlow.update {
                    it.copy(
                        availableModels = update.models,
                        selectedModelId = update.selectedModelId,
                        selectedReasoningEffort = update.selectedReasoningEffort,
                    )
                }
            }

            is ClientUpdate.AssistantDelta -> {
                val threadId = resolveThreadId(update.threadId) ?: return
                if (!update.turnId.isNullOrBlank()) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    applyAssistantDelta(messages, threadId, update)
                }
            }

            is ClientUpdate.AssistantCompleted -> {
                val threadId = resolveThreadId(update.threadId) ?: return
                if (!update.turnId.isNullOrBlank()) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    applyAssistantCompletion(messages, threadId, update)
                }
            }

            is ClientUpdate.TurnStarted -> {
                val threadId = resolveThreadId(update.threadId) ?: return
                markThreadRunning(threadId, update.turnId)
            }

            is ClientUpdate.ApprovalRequested -> {
                stateFlow.update { it.copy(pendingApproval = update.request) }
            }

            ClientUpdate.ApprovalCleared -> {
                stateFlow.update { it.copy(pendingApproval = null) }
            }

            is ClientUpdate.TurnCompleted -> {
                val resolvedThreadId = resolveTurnCompletionThreadId(update.threadId)
                if (resolvedThreadId == null
                    && stateFlow.value.activeTurnIdByThread.isEmpty()
                    && stateFlow.value.runningThreadIds.isEmpty()
                ) {
                    return
                }
                resolvedThreadId?.let { markTurnCompleted(it, null) }
                val selectedThreadId = stateFlow.value.selectedThreadId
                if (selectedThreadId != null && (resolvedThreadId == null || resolvedThreadId == selectedThreadId)) {
                    scope.launch {
                        loadThreadIntoState(selectedThreadId)
                    }
                } else {
                    scope.launch {
                        refreshThreadsInternal()
                    }
                }
            }

            is ClientUpdate.Error -> {
                stateFlow.update { it.copy(errorMessage = update.message) }
            }
        }
    }

    private fun resolveThreadId(threadId: String?): String? {
        val normalized = threadId?.trim()?.takeIf { it.isNotEmpty() }
        return normalized ?: stateFlow.value.selectedThreadId
    }

    private fun resolveTurnCompletionThreadId(threadId: String?): String? {
        val normalized = threadId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized != null) {
            return normalized
        }

        val activeThreadIds = stateFlow.value.activeTurnIdByThread.keys
        if (activeThreadIds.size == 1) {
            return activeThreadIds.first()
        }

        val runningThreadIds = stateFlow.value.runningThreadIds
        if (runningThreadIds.size == 1) {
            return runningThreadIds.first()
        }

        val selectedThreadId = stateFlow.value.selectedThreadId
        if (selectedThreadId != null && selectedThreadId in runningThreadIds) {
            return selectedThreadId
        }
        return null
    }

    private fun appendMessage(threadId: String, message: ConversationMessage) {
        updateThreadMessages(threadId) { messages -> messages + message }
    }

    private fun updateThreadMessages(
        threadId: String,
        transform: (List<ConversationMessage>) -> List<ConversationMessage>,
    ) {
        stateFlow.update { current ->
            val existing = current.timelineByThread[threadId].orEmpty()
            current.copy(
                timelineByThread = current.timelineByThread + (threadId to transform(existing)),
            )
        }
    }

    private fun markThreadRunning(threadId: String, turnId: String?) {
        stateFlow.update { current ->
            val nextActiveTurns = current.activeTurnIdByThread.toMutableMap().apply {
                val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
                if (normalizedTurnId != null) {
                    put(threadId, normalizedTurnId)
                }
            }
            current.copy(
                activeTurnIdByThread = nextActiveTurns,
                runningThreadIds = current.runningThreadIds + threadId,
            )
        }
    }

    private fun markTurnCompleted(threadId: String, turnId: String?) {
        stateFlow.update { current ->
            val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
            val nextActiveTurns = current.activeTurnIdByThread.toMutableMap()
            if (normalizedTurnId == null || nextActiveTurns[threadId] == normalizedTurnId) {
                nextActiveTurns.remove(threadId)
            }
            current.copy(
                activeTurnIdByThread = nextActiveTurns,
                runningThreadIds = current.runningThreadIds - threadId,
            )
        }
    }

    private fun scheduleSavedReconnectRetry(immediate: Boolean = false) {
        val snapshot = stateFlow.value
        if (!appInForeground || suppressSavedReconnect || !snapshot.hasSavedPairing) {
            return
        }
        if (savedReconnectRetryJob?.isActive == true) {
            return
        }

        savedReconnectRetryJob = scope.launch {
            if (!immediate) {
                delay(savedReconnectRetryDelayMs)
            }
            while (isActive) {
                val current = stateFlow.value
                if (!appInForeground || suppressSavedReconnect || !current.hasSavedPairing) {
                    break
                }
                if (current.connectionStatus == ConnectionStatus.CONNECTED) {
                    break
                }
                if (savedReconnectInFlight) {
                    delay(savedReconnectRetryDelayMs)
                    continue
                }

                savedReconnectInFlight = true
                try {
                    repository.reconnectSaved()
                    refreshThreadsInternal()
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
}

private fun applyAssistantDelta(
    messages: List<ConversationMessage>,
    threadId: String,
    update: ClientUpdate.AssistantDelta,
): List<ConversationMessage> {
    val existingIndex = messages.indexOfLast { message ->
        message.role == ConversationRole.ASSISTANT
            && message.isStreaming
            && (update.itemId != null && message.itemId == update.itemId
            || update.turnId != null && message.turnId == update.turnId)
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
    threadId: String,
    update: ClientUpdate.AssistantCompleted,
): List<ConversationMessage> {
    val existingIndex = messages.indexOfLast { message ->
        message.role == ConversationRole.ASSISTANT
            && (update.itemId != null && message.itemId == update.itemId
            || update.turnId != null && message.turnId == update.turnId)
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
