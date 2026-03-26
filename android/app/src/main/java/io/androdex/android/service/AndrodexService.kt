package io.androdex.android.service

import io.androdex.android.AppEnvironment
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.attachmentSignature
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.ModelOption
import io.androdex.android.model.PlanStep
import io.androdex.android.model.SubagentAction
import io.androdex.android.model.SubagentRef
import io.androdex.android.model.SubagentState
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.TurnSkillMention
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

private data class SubagentIdentityEntry(
    val threadId: String?,
    val agentId: String?,
    val nickname: String?,
    val role: String?,
) {
    val hasMetadata: Boolean
        get() = !nickname.isNullOrBlank() || !role.isNullOrBlank() || !agentId.isNullOrBlank()
}

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
    val protectedRunningFallbackThreadIds: Set<String> = emptySet(),
    val readyThreadIds: Set<String> = emptySet(),
    val failedThreadIds: Set<String> = emptySet(),
    val latestTurnTerminalStateByThread: Map<String, TurnTerminalState> = emptyMap(),
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
    private val subagentIdentityByThreadId = mutableMapOf<String, SubagentIdentityEntry>()
    private val subagentIdentityByAgentId = mutableMapOf<String, SubagentIdentityEntry>()

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
        clearThreadRunState()
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
                readyThreadIds = current.readyThreadIds - threadId,
                failedThreadIds = current.failedThreadIds - threadId,
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

    suspend fun sendMessage(
        input: String,
        preferredThreadId: String? = stateFlow.value.selectedThreadId,
        attachments: List<ImageAttachment> = emptyList(),
        skillMentions: List<TurnSkillMention> = emptyList(),
        collaborationMode: CollaborationModeKind? = null,
    ) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty() && attachments.isEmpty()) {
            return
        }

        val preferredWorkspace = stateFlow.value.activeWorkspacePath
        val threadId = preferredThreadId?.trim()?.takeIf { it.isNotEmpty() }
            ?: repository.startThread(preferredWorkspace).id.also { newThreadId ->
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

        clearThreadOutcome(threadId)
        when {
            isThreadConsideredRunning(threadId) -> {
                when (val resolution = resolveActiveTurn(threadId)) {
                    is ActiveTurnResolution.Resolved -> {
                        repository.steerTurn(
                            threadId = threadId,
                            expectedTurnId = resolution.turnId,
                            userInput = trimmedInput,
                            attachments = attachments,
                            skillMentions = skillMentions,
                            collaborationMode = collaborationMode,
                        )
                        markThreadRunning(threadId, resolution.turnId)
                    }

                    ActiveTurnResolution.WaitingForTurnId -> {
                        throw IllegalStateException(
                            "The active run has not published a steerable turn ID yet. Please try again in a moment."
                        )
                    }

                    ActiveTurnResolution.NoActiveTurn -> {
                        repository.startTurn(
                            threadId = threadId,
                            userInput = trimmedInput,
                            attachments = attachments,
                            skillMentions = skillMentions,
                            collaborationMode = collaborationMode,
                        )
                        markThreadRunning(threadId = threadId, turnId = null)
                    }
                }
            }

            else -> {
                repository.startTurn(
                    threadId = threadId,
                    userInput = trimmedInput,
                    attachments = attachments,
                    skillMentions = skillMentions,
                    collaborationMode = collaborationMode,
                )
                markThreadRunning(threadId = threadId, turnId = null)
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
                attachments = attachments,
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    suspend fun interruptThread(threadId: String) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        when (val resolution = resolveActiveTurn(normalizedThreadId)) {
            is ActiveTurnResolution.Resolved -> {
                markThreadRunning(normalizedThreadId, resolution.turnId)
                repository.interruptTurn(normalizedThreadId, resolution.turnId)
            }

            ActiveTurnResolution.WaitingForTurnId -> {
                throw IllegalStateException(
                    "The active run has not published an interruptible turn ID yet. Please try again in a moment."
                )
            }

            ActiveTurnResolution.NoActiveTurn -> {
                throw IllegalStateException("No active run is available to stop.")
            }
        }
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
        val result = repository.loadThread(threadId)
        stateFlow.update { current ->
            val existingMessages = current.timelineByThread[threadId].orEmpty()
            val keepStreamingRows = isThreadConsideredRunning(threadId, current)
            current.copy(
                selectedThreadTitle = if (current.selectedThreadId == threadId) {
                    result.thread?.title ?: current.selectedThreadTitle
                } else {
                    current.selectedThreadTitle
                },
                timelineByThread = current.timelineByThread + (
                    threadId to mergeThreadMessages(
                        existing = existingMessages,
                        incoming = result.messages,
                        keepStreamingRows = keepStreamingRows,
                    )
                ),
            )
        }
        syncThreadRunStateFromSnapshot(threadId, result.runSnapshot)
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
                    )
                }
                when (update.status) {
                    ConnectionStatus.CONNECTED -> {
                        cancelSavedReconnectRetry()
                        scope.launch {
                            loadRuntimeConfig()
                            loadWorkspaceState()
                            recoverVisibleThreadState()
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
                updateSubagentIdentitiesFromThreads(update.threads)
                stateFlow.update { current ->
                    current.copy(
                        threads = update.threads,
                        selectedThreadTitle = update.threads.firstOrNull { it.id == current.selectedThreadId }?.title
                            ?: current.selectedThreadTitle,
                    )
                }
                refreshSubagentMessages()
            }

            is ClientUpdate.ThreadLoaded -> {
                val threadId = update.thread?.id ?: stateFlow.value.selectedThreadId ?: return
                updateSubagentIdentitiesFromThreads(listOfNotNull(update.thread))
                updateSubagentIdentitiesFromMessages(update.messages)
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
                refreshSubagentMessages()
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

            is ClientUpdate.PlanUpdated -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, itemId = null) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    upsertPlanMetadata(
                        messages = messages,
                        threadId = threadId,
                        turnId = update.turnId,
                        itemId = null,
                        explanation = update.explanation,
                        steps = update.steps,
                        isStreaming = isTurnActive(threadId, update.turnId),
                    )
                }
            }

            is ClientUpdate.PlanDelta -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    applyStreamingItemDelta(
                        messages = messages,
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.PLAN,
                        turnId = update.turnId,
                        itemId = null,
                        delta = update.delta,
                        isTurnActive = isTurnActive(threadId, update.turnId),
                    )
                }
            }

            is ClientUpdate.PlanCompleted -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank()) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    val updatedMessages = applyStreamingItemCompletion(
                        messages = messages,
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.PLAN,
                        turnId = update.turnId,
                        itemId = null,
                        text = update.text,
                        allowCreateWhenInactive = true,
                    )
                    upsertPlanMetadata(
                        messages = updatedMessages,
                        threadId = threadId,
                        turnId = update.turnId,
                        itemId = null,
                        explanation = update.explanation,
                        steps = update.steps,
                        isStreaming = false,
                    )
                }
            }

            is ClientUpdate.SubagentActionUpdate -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateSubagentIdentities(update.action)
                updateThreadMessages(threadId) { messages ->
                    upsertSubagentActionMessage(
                        messages = messages,
                        threadId = threadId,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        action = resolveSubagentAction(update.action),
                        isStreaming = update.isStreaming,
                    )
                }
            }

            is ClientUpdate.AssistantDelta -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    applyStreamingItemDelta(
                        messages = messages,
                        threadId = threadId,
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        delta = update.delta,
                        isTurnActive = isTurnActive(threadId, update.turnId),
                    )
                }
            }

            is ClientUpdate.AssistantCompleted -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank()) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    applyStreamingItemCompletion(
                        messages = messages,
                        threadId = threadId,
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        text = update.text,
                        allowCreateWhenInactive = true,
                    )
                }
            }

            is ClientUpdate.TurnStarted -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, itemId = null) ?: return
                markThreadRunning(threadId, update.turnId)
            }

            is ClientUpdate.ReasoningDelta -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    applyStreamingItemDelta(
                        messages = messages,
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.THINKING,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        delta = update.delta,
                        isTurnActive = isTurnActive(threadId, update.turnId),
                    )
                }
            }

            is ClientUpdate.ReasoningCompleted -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                updateThreadMessages(threadId) { messages ->
                    applyStreamingItemCompletion(
                        messages = messages,
                        threadId = threadId,
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.THINKING,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        text = update.text,
                        allowCreateWhenInactive = true,
                    )
                }
            }

            is ClientUpdate.ApprovalRequested -> {
                stateFlow.update { it.copy(pendingApproval = update.request) }
            }

            ClientUpdate.ApprovalCleared -> {
                stateFlow.update { it.copy(pendingApproval = null) }
            }

            is ClientUpdate.TurnCompleted -> {
                if (update.willRetry) {
                    return
                }
                val resolvedThreadId = resolveTurnCompletionThreadId(update.threadId, update.turnId)
                if (resolvedThreadId == null
                    && stateFlow.value.activeTurnIdByThread.isEmpty()
                    && stateFlow.value.runningThreadIds.isEmpty()
                    && stateFlow.value.protectedRunningFallbackThreadIds.isEmpty()
                ) {
                    return
                }
                resolvedThreadId?.let { markTurnCompleted(it, update.turnId, update.terminalState) }
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
                update.errorMessage?.let(::reportError)
            }

            is ClientUpdate.ThreadStatusChanged -> {
                val threadId = resolveThreadId(update.threadId, turnId = null, itemId = null) ?: return
                when (update.status?.trim()?.lowercase()) {
                    "running", "active", "in_progress" -> markThreadRunning(threadId, turnId = null)
                    "idle" -> {
                        if (!isTurnActive(threadId, turnId = null)
                            && threadId !in stateFlow.value.protectedRunningFallbackThreadIds
                        ) {
                            clearThreadRunningState(threadId)
                        }
                    }
                }
            }

            is ClientUpdate.Error -> {
                stateFlow.update { it.copy(errorMessage = update.message) }
            }
        }
    }

    private fun resolveThreadId(
        threadId: String?,
        turnId: String?,
        itemId: String?,
    ): String? {
        val snapshot = stateFlow.value
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedThreadId != null) {
            return normalizedThreadId
        }

        val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedTurnId != null) {
            val matchingActiveThreads = snapshot.activeTurnIdByThread
                .filterValues { it == normalizedTurnId }
                .keys
            if (matchingActiveThreads.size == 1) {
                return matchingActiveThreads.first()
            }
        }

        val runningCandidates = snapshot.runningThreadIds + snapshot.protectedRunningFallbackThreadIds
        if (runningCandidates.size == 1) {
            return runningCandidates.first()
        }

        val knownThreadIds = buildSet {
            snapshot.selectedThreadId?.let(::add)
            addAll(snapshot.timelineByThread.keys)
            addAll(snapshot.threads.map { it.id })
        }
        if (knownThreadIds.size == 1) {
            return knownThreadIds.first()
        }

        if (normalizedTurnId == null && itemId.isNullOrBlank() && knownThreadIds.size > 1) {
            return null
        }
        return snapshot.selectedThreadId?.takeIf { knownThreadIds.size <= 1 }
    }

    private fun resolveTurnCompletionThreadId(threadId: String?, turnId: String?): String? {
        resolveThreadId(threadId, turnId, itemId = null)?.let { return it }

        val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedTurnId != null) {
            val activeMatch = stateFlow.value.activeTurnIdByThread.entries.firstOrNull { it.value == normalizedTurnId }
            if (activeMatch != null) {
                return activeMatch.key
            }
        }

        val activeThreadIds = stateFlow.value.activeTurnIdByThread.keys
        if (activeThreadIds.size == 1) {
            return activeThreadIds.first()
        }

        val runningThreadIds = stateFlow.value.runningThreadIds + stateFlow.value.protectedRunningFallbackThreadIds
        if (runningThreadIds.size == 1) {
            return runningThreadIds.first()
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
            val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
            val nextActiveTurns = current.activeTurnIdByThread.toMutableMap().apply {
                if (normalizedTurnId != null) {
                    put(threadId, normalizedTurnId)
                }
            }
            current.copy(
                activeTurnIdByThread = nextActiveTurns,
                runningThreadIds = current.runningThreadIds + threadId,
                protectedRunningFallbackThreadIds = if (normalizedTurnId == null) {
                    current.protectedRunningFallbackThreadIds + threadId
                } else {
                    current.protectedRunningFallbackThreadIds - threadId
                },
                readyThreadIds = current.readyThreadIds - threadId,
                failedThreadIds = current.failedThreadIds - threadId,
            )
        }
    }

    private fun markTurnCompleted(
        threadId: String,
        turnId: String?,
        terminalState: TurnTerminalState,
    ) {
        stateFlow.update { current ->
            val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
            val nextActiveTurns = current.activeTurnIdByThread.toMutableMap()
            if (normalizedTurnId == null || nextActiveTurns[threadId] == normalizedTurnId) {
                nextActiveTurns.remove(threadId)
            }
            val selectedThreadId = current.selectedThreadId
            val nextReady = current.readyThreadIds.toMutableSet().apply {
                remove(threadId)
                if (terminalState == TurnTerminalState.COMPLETED && selectedThreadId != threadId) {
                    add(threadId)
                }
            }
            val nextFailed = current.failedThreadIds.toMutableSet().apply {
                remove(threadId)
                if (terminalState == TurnTerminalState.FAILED && selectedThreadId != threadId) {
                    add(threadId)
                }
            }
            current.copy(
                activeTurnIdByThread = nextActiveTurns,
                runningThreadIds = current.runningThreadIds - threadId,
                protectedRunningFallbackThreadIds = current.protectedRunningFallbackThreadIds - threadId,
                readyThreadIds = nextReady,
                failedThreadIds = nextFailed,
                latestTurnTerminalStateByThread = current.latestTurnTerminalStateByThread + (threadId to terminalState),
            )
        }
    }

    private fun clearThreadOutcome(threadId: String) {
        stateFlow.update { current ->
            current.copy(
                readyThreadIds = current.readyThreadIds - threadId,
                failedThreadIds = current.failedThreadIds - threadId,
            )
        }
    }

    private fun clearThreadRunningState(threadId: String) {
        stateFlow.update { current ->
            current.copy(
                activeTurnIdByThread = current.activeTurnIdByThread - threadId,
                runningThreadIds = current.runningThreadIds - threadId,
                protectedRunningFallbackThreadIds = current.protectedRunningFallbackThreadIds - threadId,
            )
        }
    }

    private fun clearThreadRunState() {
        stateFlow.update { current ->
            current.copy(
                activeTurnIdByThread = emptyMap(),
                runningThreadIds = emptySet(),
                protectedRunningFallbackThreadIds = emptySet(),
                readyThreadIds = emptySet(),
                failedThreadIds = emptySet(),
                latestTurnTerminalStateByThread = emptyMap(),
            )
        }
    }

    private suspend fun resolveActiveTurn(threadId: String): ActiveTurnResolution {
        val activeTurnId = stateFlow.value.activeTurnIdByThread[threadId]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (activeTurnId != null) {
            return ActiveTurnResolution.Resolved(activeTurnId)
        }

        val snapshot = repository.readThreadRunSnapshot(threadId)
        return when {
            snapshot.interruptibleTurnId != null -> {
                markThreadRunning(threadId, snapshot.interruptibleTurnId)
                ActiveTurnResolution.Resolved(snapshot.interruptibleTurnId)
            }

            snapshot.shouldAssumeRunningFromLatestTurn && snapshot.latestTurnId != null -> {
                markThreadRunning(threadId, snapshot.latestTurnId)
                ActiveTurnResolution.Resolved(snapshot.latestTurnId)
            }

            snapshot.hasInterruptibleTurnWithoutId -> {
                syncThreadRunStateFromSnapshot(threadId, snapshot)
                ActiveTurnResolution.WaitingForTurnId
            }

            else -> {
                syncThreadRunStateFromSnapshot(threadId, snapshot)
                ActiveTurnResolution.NoActiveTurn
            }
        }
    }

    private fun syncThreadRunStateFromSnapshot(threadId: String, snapshot: ThreadRunSnapshot) {
        stateFlow.update { current ->
            val nextActiveTurns = current.activeTurnIdByThread.toMutableMap()
            val nextRunning = current.runningThreadIds.toMutableSet()
            val nextProtected = current.protectedRunningFallbackThreadIds.toMutableSet()
            val nextLatestTerminal = current.latestTurnTerminalStateByThread.toMutableMap()
            val nextReady = current.readyThreadIds.toMutableSet().apply { remove(threadId) }
            val nextFailed = current.failedThreadIds.toMutableSet().apply { remove(threadId) }

            when {
                snapshot.interruptibleTurnId != null -> {
                    nextActiveTurns[threadId] = snapshot.interruptibleTurnId
                    nextRunning += threadId
                    nextProtected -= threadId
                }

                snapshot.shouldAssumeRunningFromLatestTurn && snapshot.latestTurnId != null -> {
                    nextActiveTurns[threadId] = snapshot.latestTurnId
                    nextRunning += threadId
                    nextProtected -= threadId
                }

                snapshot.hasInterruptibleTurnWithoutId -> {
                    nextActiveTurns.remove(threadId)
                    nextRunning -= threadId
                    nextProtected += threadId
                }

                else -> {
                    nextActiveTurns.remove(threadId)
                    nextRunning -= threadId
                    nextProtected -= threadId
                }
            }

            snapshot.latestTurnTerminalState?.let { terminalState ->
                nextLatestTerminal[threadId] = terminalState
                if (!isThreadConsideredRunning(
                        threadId,
                        current.copy(
                            activeTurnIdByThread = nextActiveTurns,
                            runningThreadIds = nextRunning,
                            protectedRunningFallbackThreadIds = nextProtected,
                        )
                    )
                    && current.selectedThreadId != threadId
                ) {
                    when (terminalState) {
                        TurnTerminalState.COMPLETED -> nextReady += threadId
                        TurnTerminalState.FAILED -> nextFailed += threadId
                        TurnTerminalState.STOPPED -> Unit
                    }
                }
            }

            current.copy(
                activeTurnIdByThread = nextActiveTurns,
                runningThreadIds = nextRunning,
                protectedRunningFallbackThreadIds = nextProtected,
                readyThreadIds = nextReady,
                failedThreadIds = nextFailed,
                latestTurnTerminalStateByThread = nextLatestTerminal,
            )
        }
    }

    private fun isTurnActive(threadId: String, turnId: String?): Boolean {
        val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() }
        val snapshot = stateFlow.value
        return when {
            normalizedTurnId != null -> snapshot.activeTurnIdByThread[threadId] == normalizedTurnId
            threadId in snapshot.activeTurnIdByThread -> true
            threadId in snapshot.runningThreadIds -> true
            else -> threadId in snapshot.protectedRunningFallbackThreadIds
        }
    }

    private fun shouldPromoteIncomingTurnActivity(threadId: String, turnId: String?): Boolean {
        val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return isThreadConsideredRunning(threadId)
        val snapshot = stateFlow.value
        return when {
            snapshot.activeTurnIdByThread[threadId] == normalizedTurnId -> true
            isThreadConsideredRunning(threadId, snapshot) -> true
            else -> threadId !in snapshot.latestTurnTerminalStateByThread
        }
    }

    private fun isThreadConsideredRunning(threadId: String, snapshot: AndrodexServiceState = stateFlow.value): Boolean {
        return threadId in snapshot.runningThreadIds || threadId in snapshot.protectedRunningFallbackThreadIds
    }

    private suspend fun recoverVisibleThreadState() {
        val selectedThreadId = stateFlow.value.selectedThreadId
        if (selectedThreadId != null) {
            loadThreadIntoState(selectedThreadId)
        }

        val siblingRunningThreads = (stateFlow.value.runningThreadIds + stateFlow.value.protectedRunningFallbackThreadIds)
            .filterNot { it == selectedThreadId }
        siblingRunningThreads.forEach { threadId ->
            runCatching {
                syncThreadRunStateFromSnapshot(threadId, repository.readThreadRunSnapshot(threadId))
            }
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

    private fun updateSubagentIdentitiesFromThreads(threads: List<ThreadSummary>) {
        var didChange = false
        threads.forEach { thread ->
            if (upsertSubagentIdentity(
                    threadId = thread.id,
                    agentId = thread.agentId,
                    nickname = thread.agentNickname,
                    role = thread.agentRole,
                )
            ) {
                didChange = true
            }
        }
        if (didChange) {
            refreshSubagentMessages()
        }
    }

    private fun updateSubagentIdentitiesFromMessages(messages: List<ConversationMessage>) {
        var didChange = false
        messages.mapNotNull { it.subagentAction }.forEach { action ->
            if (updateSubagentIdentities(action)) {
                didChange = true
            }
        }
        if (didChange) {
            refreshSubagentMessages()
        }
    }

    private fun updateSubagentIdentities(action: SubagentAction): Boolean {
        var didChange = false
        action.agentRows.forEach { row ->
            if (upsertSubagentIdentity(
                    threadId = row.threadId,
                    agentId = row.agentId,
                    nickname = row.nickname,
                    role = row.role,
                )
            ) {
                didChange = true
            }
        }
        return didChange
    }

    private fun upsertSubagentIdentity(
        threadId: String?,
        agentId: String?,
        nickname: String?,
        role: String?,
    ): Boolean {
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedAgentId = agentId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedNickname = nickname?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedRole = role?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedThreadId == null && normalizedAgentId == null) {
            return false
        }

        val entry = SubagentIdentityEntry(
            threadId = normalizedThreadId,
            agentId = normalizedAgentId,
            nickname = normalizedNickname,
            role = normalizedRole,
        )
        if (!entry.hasMetadata) {
            return false
        }

        var didChange = false
        normalizedThreadId?.let { key ->
            val merged = mergeSubagentIdentity(subagentIdentityByThreadId[key], entry)
            if (merged != subagentIdentityByThreadId[key]) {
                subagentIdentityByThreadId[key] = merged
                didChange = true
            }
        }
        normalizedAgentId?.let { key ->
            val merged = mergeSubagentIdentity(subagentIdentityByAgentId[key], entry)
            if (merged != subagentIdentityByAgentId[key]) {
                subagentIdentityByAgentId[key] = merged
                didChange = true
            }
        }
        return didChange
    }

    private fun mergeSubagentIdentity(
        existing: SubagentIdentityEntry?,
        incoming: SubagentIdentityEntry,
    ): SubagentIdentityEntry {
        return SubagentIdentityEntry(
            threadId = existing?.threadId ?: incoming.threadId,
            agentId = existing?.agentId ?: incoming.agentId,
            nickname = existing?.nickname ?: incoming.nickname,
            role = existing?.role ?: incoming.role,
        )
    }

    private fun resolvedSubagentIdentity(threadId: String?, agentId: String?): SubagentIdentityEntry? {
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedAgentId = agentId?.trim()?.takeIf { it.isNotEmpty() }
        val threadEntry = normalizedThreadId?.let(subagentIdentityByThreadId::get)
        val agentEntry = normalizedAgentId?.let(subagentIdentityByAgentId::get)
        val merged = SubagentIdentityEntry(
            threadId = threadEntry?.threadId ?: agentEntry?.threadId ?: normalizedThreadId,
            agentId = threadEntry?.agentId ?: agentEntry?.agentId ?: normalizedAgentId,
            nickname = threadEntry?.nickname ?: agentEntry?.nickname,
            role = threadEntry?.role ?: agentEntry?.role,
        )
        return merged.takeIf { it.hasMetadata }
    }

    private fun resolveSubagentAction(action: SubagentAction): SubagentAction {
        val orderedThreadIds = buildList {
            action.receiverThreadIds.forEach { threadId ->
                if (threadId !in this) {
                    add(threadId)
                }
            }
            action.receiverAgents.forEach { agent ->
                if (agent.threadId !in this) {
                    add(agent.threadId)
                }
            }
            action.agentStates.keys.forEach { threadId ->
                if (threadId !in this) {
                    add(threadId)
                }
            }
        }

        val resolvedAgents = orderedThreadIds.mapNotNull { threadId ->
            val existingAgent = action.receiverAgents.firstOrNull { it.threadId == threadId }
            val identity = resolvedSubagentIdentity(threadId = threadId, agentId = existingAgent?.agentId)
            val resolved = SubagentRef(
                threadId = threadId,
                agentId = existingAgent?.agentId ?: identity?.agentId,
                nickname = existingAgent?.nickname ?: identity?.nickname,
                role = existingAgent?.role ?: identity?.role,
                model = existingAgent?.model,
                prompt = existingAgent?.prompt,
            )
            resolved.takeIf {
                it.agentId != null || it.nickname != null || it.role != null || it.model != null || it.prompt != null
            } ?: existingAgent
        }

        return action.copy(
            receiverThreadIds = orderedThreadIds,
            receiverAgents = resolvedAgents.distinctBy { it.threadId },
        )
    }

    private fun refreshSubagentMessages() {
        stateFlow.update { current ->
            val nextTimeline = current.timelineByThread.mapValues { (_, messages) ->
                messages.map { message ->
                    if (message.kind != ConversationKind.SUBAGENT_ACTION || message.subagentAction == null) {
                        message
                    } else {
                        val resolvedAction = resolveSubagentAction(message.subagentAction)
                        message.copy(
                            text = resolvedAction.summaryText,
                            subagentAction = resolvedAction,
                        )
                    }
                }
            }
            current.copy(timelineByThread = nextTimeline)
        }
    }
}

private sealed interface ActiveTurnResolution {
    data class Resolved(val turnId: String) : ActiveTurnResolution
    data object WaitingForTurnId : ActiveTurnResolution
    data object NoActiveTurn : ActiveTurnResolution
}

private fun applyStreamingItemDelta(
    messages: List<ConversationMessage>,
    threadId: String,
    role: ConversationRole,
    kind: ConversationKind,
    turnId: String?,
    itemId: String?,
    delta: String,
    isTurnActive: Boolean,
): List<ConversationMessage> {
    val existingIndex = messages.indexOfLast { message ->
        message.role == role
            && message.kind == kind
            && messagesRepresentSameItem(
                existing = message,
                incoming = message.copy(
                    threadId = threadId,
                    turnId = turnId,
                    itemId = itemId,
                ),
            )
    }
    if (existingIndex >= 0) {
        val updated = messages.toMutableList()
        val existing = updated[existingIndex]
        updated[existingIndex] = existing.copy(
            text = existing.text + delta,
            isStreaming = isTurnActive,
        )
        return updated
    }
    if (!isTurnActive && kind == ConversationKind.THINKING) {
        return messages
    }
    return messages + ConversationMessage(
        id = itemId ?: UUID.randomUUID().toString(),
        threadId = threadId,
        role = role,
        kind = kind,
        text = delta,
        createdAtEpochMs = System.currentTimeMillis(),
        turnId = turnId,
        itemId = itemId,
        isStreaming = isTurnActive,
    )
}

private fun applyStreamingItemCompletion(
    messages: List<ConversationMessage>,
    threadId: String,
    role: ConversationRole,
    kind: ConversationKind,
    turnId: String?,
    itemId: String?,
    text: String,
    allowCreateWhenInactive: Boolean,
): List<ConversationMessage> {
    val incoming = ConversationMessage(
        id = itemId ?: UUID.randomUUID().toString(),
        threadId = threadId,
        role = role,
        kind = kind,
        text = text,
        createdAtEpochMs = System.currentTimeMillis(),
        turnId = turnId,
        itemId = itemId,
        isStreaming = false,
    )
    val existingIndex = messages.indexOfLast { message ->
        message.role == role && message.kind == kind && messagesRepresentSameItem(message, incoming)
    }
    if (existingIndex >= 0) {
        val updated = messages.toMutableList()
        updated[existingIndex] = mergeMatchedMessage(
            existing = updated[existingIndex],
            incoming = incoming,
            keepStreamingRows = false,
        ).copy(isStreaming = false)
        return updated
    }
    return if (allowCreateWhenInactive) messages + incoming else messages
}

private fun upsertPlanMetadata(
    messages: List<ConversationMessage>,
    threadId: String,
    turnId: String?,
    itemId: String?,
    explanation: String?,
    steps: List<PlanStep>?,
    isStreaming: Boolean,
): List<ConversationMessage> {
    if (explanation.isNullOrBlank() && steps.isNullOrEmpty()) {
        return messages
    }

    val incoming = ConversationMessage(
        id = itemId ?: UUID.randomUUID().toString(),
        threadId = threadId,
        role = ConversationRole.SYSTEM,
        kind = ConversationKind.PLAN,
        text = explanation?.takeIf { it.isNotBlank() } ?: "Planning...",
        createdAtEpochMs = System.currentTimeMillis(),
        turnId = turnId,
        itemId = itemId,
        isStreaming = isStreaming,
        planExplanation = explanation?.takeIf { it.isNotBlank() },
        planSteps = steps,
    )
    val existingIndex = messages.indexOfLast { message ->
        message.role == ConversationRole.SYSTEM
            && message.kind == ConversationKind.PLAN
            && messagesRepresentSameItem(message, incoming)
    }

    if (existingIndex < 0) {
        return messages + incoming
    }

    val updated = messages.toMutableList()
    val existing = updated[existingIndex]
    updated[existingIndex] = mergeMatchedMessage(
        existing = existing,
        incoming = incoming,
        keepStreamingRows = true,
    ).copy(
        text = existing.text.ifBlank { incoming.text },
        planExplanation = incoming.planExplanation ?: existing.planExplanation,
        planSteps = incoming.planSteps ?: existing.planSteps,
        isStreaming = isStreaming || existing.isStreaming,
    )
    return updated
}

private fun upsertSubagentActionMessage(
    messages: List<ConversationMessage>,
    threadId: String,
    turnId: String?,
    itemId: String?,
    action: SubagentAction,
    isStreaming: Boolean,
): List<ConversationMessage> {
    val incoming = ConversationMessage(
        id = itemId ?: UUID.randomUUID().toString(),
        threadId = threadId,
        role = ConversationRole.SYSTEM,
        kind = ConversationKind.SUBAGENT_ACTION,
        text = action.summaryText,
        createdAtEpochMs = System.currentTimeMillis(),
        turnId = turnId,
        itemId = itemId,
        isStreaming = isStreaming,
        subagentAction = action,
    )
    val existingIndex = messages.indexOfLast { message ->
        message.role == ConversationRole.SYSTEM
            && message.kind == ConversationKind.SUBAGENT_ACTION
            && subagentMessagesRepresentSameItem(message, incoming)
    }
    if (existingIndex < 0) {
        return messages + incoming
    }

    val updated = messages.toMutableList()
    val existing = updated[existingIndex]
    updated[existingIndex] = mergeMatchedMessage(
        existing = existing,
        incoming = incoming,
        keepStreamingRows = true,
    ).copy(
        text = action.summaryText,
        subagentAction = mergeSubagentActions(existing.subagentAction, action),
        isStreaming = isStreaming || existing.isStreaming,
    )
    return updated
}

private fun mergeThreadMessages(
    existing: List<ConversationMessage>,
    incoming: List<ConversationMessage>,
    keepStreamingRows: Boolean,
): List<ConversationMessage> {
    if (existing.isEmpty()) {
        return incoming
    }
    if (incoming.isEmpty()) {
        return existing
    }

    val merged = existing.toMutableList()
    incoming.forEach { incomingMessage ->
        val existingIndex = merged.indexOfFirst { existingMessage ->
            messagesRepresentSameItem(existingMessage, incomingMessage)
        }
        if (existingIndex >= 0) {
            merged[existingIndex] = mergeMatchedMessage(
                existing = merged[existingIndex],
                incoming = incomingMessage,
                keepStreamingRows = keepStreamingRows,
            )
        } else {
            merged += incomingMessage
        }
    }
    return merged.sortedBy { it.createdAtEpochMs }
}

private fun mergeMatchedMessage(
    existing: ConversationMessage,
    incoming: ConversationMessage,
    keepStreamingRows: Boolean,
): ConversationMessage {
    val mergedText = when {
        incoming.text.isBlank() -> existing.text
        existing.text.isBlank() -> incoming.text
        keepStreamingRows && existing.isStreaming && incoming.text.length < existing.text.length -> existing.text
        incoming.text.length >= existing.text.length -> incoming.text
        else -> existing.text
    }
    val shouldKeepStreaming = keepStreamingRows && existing.isStreaming && incoming.text.length <= existing.text.length
    return incoming.copy(
        id = incoming.id.ifBlank { existing.id },
        text = mergedText,
        attachments = if (incoming.attachments.isEmpty()) existing.attachments else incoming.attachments,
        createdAtEpochMs = minOf(existing.createdAtEpochMs, incoming.createdAtEpochMs),
        isStreaming = incoming.isStreaming || shouldKeepStreaming,
        planExplanation = incoming.planExplanation ?: existing.planExplanation,
        planSteps = incoming.planSteps ?: existing.planSteps,
        subagentAction = incoming.subagentAction ?: existing.subagentAction,
    )
}

private fun messagesRepresentSameItem(
    existing: ConversationMessage,
    incoming: ConversationMessage,
): Boolean {
    val existingItemId = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
    val incomingItemId = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
    if (existingItemId != null || incomingItemId != null) {
        return existingItemId != null && existingItemId == incomingItemId
    }

    val existingTurnId = existing.turnId?.trim()?.takeIf { it.isNotEmpty() }
    val incomingTurnId = incoming.turnId?.trim()?.takeIf { it.isNotEmpty() }
    if (existingTurnId != null && incomingTurnId != null) {
        return existing.threadId == incoming.threadId
            && existing.role == incoming.role
            && existing.kind == incoming.kind
            && existingTurnId == incomingTurnId
    }

    if (existing.role == ConversationRole.USER
        && incoming.role == ConversationRole.USER
        && existing.kind == ConversationKind.CHAT
        && incoming.kind == ConversationKind.CHAT
        && existing.text == incoming.text
        && attachmentSignature(existing.attachments) == attachmentSignature(incoming.attachments)
    ) {
        return kotlin.math.abs(existing.createdAtEpochMs - incoming.createdAtEpochMs) <= 60_000L
    }

    return false
}

private fun subagentMessagesRepresentSameItem(
    existing: ConversationMessage,
    incoming: ConversationMessage,
): Boolean {
    if (messagesRepresentSameItem(existing, incoming)) {
        return true
    }

    val existingAction = existing.subagentAction ?: return false
    val incomingAction = incoming.subagentAction ?: return false
    val existingTurnId = existing.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val incomingTurnId = incoming.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (existing.threadId != incoming.threadId
        || existingTurnId != incomingTurnId
        || existingAction.normalizedTool != incomingAction.normalizedTool
    ) {
        return false
    }

    val existingPrompt = existingAction.prompt?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val incomingPrompt = incomingAction.prompt?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (existingPrompt != null && incomingPrompt != null) {
        return existingPrompt == incomingPrompt
    }

    val existingModel = existingAction.model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val incomingModel = incomingAction.model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (existingPrompt == null && existingModel != null && incomingModel != null) {
        return existingModel == incomingModel
    }

    return existing.text == incoming.text
}

private fun mergeSubagentActions(existing: SubagentAction?, incoming: SubagentAction): SubagentAction {
    if (existing == null) {
        return incoming
    }

    val mergedThreadIds = buildList {
        existing.receiverThreadIds.forEach { if (it !in this) add(it) }
        incoming.receiverThreadIds.forEach { if (it !in this) add(it) }
        existing.receiverAgents.forEach { if (it.threadId !in this) add(it.threadId) }
        incoming.receiverAgents.forEach { if (it.threadId !in this) add(it.threadId) }
        existing.agentStates.keys.forEach { if (it !in this) add(it) }
        incoming.agentStates.keys.forEach { if (it !in this) add(it) }
    }

    val mergedAgents = mergedThreadIds.mapNotNull { threadId ->
        val current = existing.receiverAgents.firstOrNull { it.threadId == threadId }
        val next = incoming.receiverAgents.firstOrNull { it.threadId == threadId }
        next ?: current
    }

    val mergedStates = existing.agentStates.toMutableMap()
    incoming.agentStates.forEach { (threadId, nextState) ->
        val currentState = mergedStates[threadId]
        mergedStates[threadId] = if (currentState == null) {
            nextState
        } else {
            SubagentState(
                threadId = threadId,
                status = nextState.status.ifBlank { currentState.status },
                message = nextState.message ?: currentState.message,
            )
        }
    }

    return incoming.copy(
        tool = incoming.tool.ifBlank { existing.tool },
        status = incoming.status.ifBlank { existing.status },
        prompt = incoming.prompt ?: existing.prompt,
        model = incoming.model ?: existing.model,
        receiverThreadIds = mergedThreadIds,
        receiverAgents = mergedAgents,
        agentStates = mergedStates,
    )
}
