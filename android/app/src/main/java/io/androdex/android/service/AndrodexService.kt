package io.androdex.android.service

import android.util.Log
import io.androdex.android.AppEnvironment
import io.androdex.android.ThreadOpenPerfLogger
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.FreshPairingAttemptState
import io.androdex.android.FreshPairingStage
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.attachmentSignature
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.ModelOption
import io.androdex.android.model.MissingNotificationThreadPrompt
import io.androdex.android.model.PlanStep
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SubagentAction
import io.androdex.android.model.SubagentRef
import io.androdex.android.model.SubagentState
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadTokenUsage
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ToolUserInputAnswer
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.requestId
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.reviewRequestText
import io.androdex.android.timeline.ThreadTimelineRenderSnapshot
import io.androdex.android.timeline.buildThreadTimelineRenderSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private const val savedReconnectRetryDelayMs = 5_000L
private const val executionReloadMergeWindowMs = 5_000L
private const val minimumThreadListLoadingVisibleMs = 350L
private const val slowThreadLoadLogThresholdMs = 400L
private const val logTag = "AndrodexService"

private data class SubagentIdentityEntry(
    val threadId: String?,
    val agentId: String?,
    val nickname: String?,
    val role: String?,
) {
    val hasMetadata: Boolean
        get() = !nickname.isNullOrBlank() || !role.isNullOrBlank() || !agentId.isNullOrBlank()
}

data class ThreadTimelineState(
    val threadId: String,
    val rawMessages: List<ConversationMessage> = emptyList(),
    val messageRevision: Long = 0L,
    val renderSnapshot: ThreadTimelineRenderSnapshot = ThreadTimelineRenderSnapshot.empty(threadId),
)

data class AndrodexServiceState(
    val hasSavedPairing: Boolean = false,
    val trustedPairSnapshot: TrustedPairSnapshot? = null,
    val freshPairingAttempt: FreshPairingAttemptState? = null,
    val hostAccountSnapshot: HostAccountSnapshot? = null,
    val defaultRelayUrl: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionDetail: String? = null,
    val secureFingerprint: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val hasLoadedThreadList: Boolean = false,
    val isLoadingThreadList: Boolean = false,
    val selectedThreadId: String? = null,
    val selectedThreadTitle: String? = null,
    val threadTimelineStateByThread: Map<String, ThreadTimelineState> = emptyMap(),
    val hydratedThreadIds: Set<String> = emptySet(),
    val hydratedThreadVersions: Map<String, Long?> = emptyMap(),
    val activeTurnIdByThread: Map<String, String> = emptyMap(),
    val runningThreadIds: Set<String> = emptySet(),
    val protectedRunningFallbackThreadIds: Set<String> = emptySet(),
    val readyThreadIds: Set<String> = emptySet(),
    val failedThreadIds: Set<String> = emptySet(),
    val latestTurnTerminalStateByThread: Map<String, TurnTerminalState> = emptyMap(),
    val tokenUsageByThread: Map<String, ThreadTokenUsage> = emptyMap(),
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
    val isWorkspaceBrowserLoading: Boolean = false,
    val pendingNotificationOpenThreadId: String? = null,
    val pendingNotificationOpenTurnId: String? = null,
    val focusedTurnId: String? = null,
    val missingNotificationThreadPrompt: MissingNotificationThreadPrompt? = null,
    val skillInventoryVersion: Long = 0L,
    val errorMessage: String? = null,
    val pendingApproval: ApprovalRequest? = null,
    val pendingToolInputsByThread: Map<String, Map<String, ToolUserInputRequest>> = emptyMap(),
) {
    val timelineByThread: Map<String, List<ConversationMessage>>
        get() = threadTimelineStateByThread.mapValues { (_, timelineState) -> timelineState.rawMessages }

    val timelineRenderSnapshotsByThread: Map<String, ThreadTimelineRenderSnapshot>
        get() = threadTimelineStateByThread.mapValues { (_, timelineState) -> timelineState.renderSnapshot }

    val messages: List<ConversationMessage>
        get() = selectedThreadId?.let { threadTimelineStateByThread[it]?.rawMessages }.orEmpty()

    val selectedThreadRenderSnapshot: ThreadTimelineRenderSnapshot?
        get() = selectedThreadId?.let { threadTimelineStateByThread[it]?.renderSnapshot }

    val selectedThreadMessageCount: Int
        get() = selectedThreadRenderSnapshot?.messageCount ?: 0
}

class AndrodexService(
    private val repository: AndrodexRepositoryContract,
    private val scope: CoroutineScope,
) {
    private var appInForeground = false
    private var savedReconnectInFlight = false
    private var savedReconnectRetryJob: Job? = null
    private var suppressSavedReconnect = false
    private val threadCollectionRequestMutex = Mutex()
    private val threadHydrationRequestMutex = Mutex()
    private val threadHydrationLoads = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val threadHydrationLoadContextRevisions = mutableMapOf<String, Long>()
    private val threadHydrationForceLoadRevisions = mutableMapOf<String, Int>()
    private val threadHydrationForceRequestRevisions = mutableMapOf<String, Int>()
    private val threadHydrationForceSatisfiedRevisions = mutableMapOf<String, Int>()
    private val selectedThreadLoadCounts = mutableMapOf<String, Int>()
    private var deferredThreadListRefreshPending = false
    private val subagentIdentityByThreadId = mutableMapOf<String, SubagentIdentityEntry>()
    private val subagentIdentityByAgentId = mutableMapOf<String, SubagentIdentityEntry>()
    private val runCompletionEventsFlow = MutableSharedFlow<RunCompletionEvent>(extraBufferCapacity = 8)
    private val threadSessionContextRevision = AtomicLong(0L)
    private val threadOpenAttemptRevision = AtomicLong(0L)
    private val optimisticWorkspaceThreadIds = ConcurrentHashMap.newKeySet<String>()

    private val stateFlow = MutableStateFlow(
        AndrodexServiceState(
            hasSavedPairing = repository.hasSavedPairing(),
            trustedPairSnapshot = repository.currentTrustedPairSnapshot(),
            defaultRelayUrl = AppEnvironment.defaultRelayUrl.takeIf { it.isNotEmpty() },
            connectionStatus = repository.startupConnectionStatus() ?: ConnectionStatus.DISCONNECTED,
            connectionDetail = repository.startupConnectionDetail(),
            secureFingerprint = repository.currentFingerprint(),
            errorMessage = repository.startupNotice(),
        )
    )

    val state: StateFlow<AndrodexServiceState> = stateFlow.asStateFlow()
    val runCompletionEvents: SharedFlow<RunCompletionEvent> = runCompletionEventsFlow.asSharedFlow()

    init {
        scope.launch {
            repository.updates.collect(::processClientUpdate)
        }
    }

    fun clearError() {
        stateFlow.update { it.copy(errorMessage = null) }
    }

    fun dismissMissingNotificationThreadPrompt() {
        stateFlow.update { it.copy(missingNotificationThreadPrompt = null) }
    }

    fun consumeFocusedTurnId(threadId: String) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        stateFlow.update { current ->
            if (current.selectedThreadId != normalizedThreadId || current.focusedTurnId == null) {
                current
            } else {
                current.copy(focusedTurnId = null)
            }
        }
    }

    fun reportError(message: String) {
        stateFlow.update { it.copy(errorMessage = message) }
    }

    fun closeThread() {
        stateFlow.update {
            it.copy(
                selectedThreadId = null,
                selectedThreadTitle = null,
                focusedTurnId = null,
            )
        }
    }

    fun onAppForegrounded() {
        appInForeground = true
        reconnectSavedIfAvailable()
        scope.launch {
            routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
        }
    }

    fun onAppBackgrounded() {
        appInForeground = false
        cancelSavedReconnectRetry()
    }

    fun beginFreshPairingScan() {
        suppressSavedReconnect = true
        cancelSavedReconnectRetry()
        val shouldResumeSavedReconnect = stateFlow.value.hasSavedPairing
        stateFlow.update {
            it.copy(
                freshPairingAttempt = FreshPairingAttemptState(
                    stage = FreshPairingStage.SCANNER_OPEN,
                    shouldResumeSavedReconnectOnCancel = shouldResumeSavedReconnect,
                ),
            )
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                repository.disconnect(clearSavedPairing = false)
            }
            stateFlow.update { it.copy(isLoadingThreadList = false) }
        }
    }

    fun recordFreshPairingPayloadCaptured() {
        suppressSavedReconnect = true
        cancelSavedReconnectRetry()
        stateFlow.update {
            it.copy(
                freshPairingAttempt = FreshPairingAttemptState(
                    stage = FreshPairingStage.PAYLOAD_CAPTURED,
                ),
            )
        }
    }

    fun cancelFreshPairingScan() {
        val shouldResumeSavedReconnect = stateFlow.value
            .freshPairingAttempt
            ?.shouldResumeSavedReconnectOnCancel == true
        stateFlow.update { it.copy(freshPairingAttempt = null) }
        if (shouldResumeSavedReconnect) {
            reportError("No QR code was captured. Reconnecting to the saved pair.")
            reconnectSavedIfAvailable()
        } else {
            reportError("No QR code was captured.")
        }
    }

    fun failFreshPairingAttempt() {
        val freshPairingAttempt = stateFlow.value.freshPairingAttempt ?: return
        if (freshPairingAttempt.stage == FreshPairingStage.CONNECTING) {
            stateFlow.update {
                it.copy(
                    freshPairingAttempt = freshPairingAttempt.copy(
                        stage = FreshPairingStage.PAYLOAD_CAPTURED,
                        shouldResumeSavedReconnectOnCancel = false,
                    ),
                )
            }
        }
    }

    suspend fun connectWithPairingPayload(rawPayload: String, isFreshPairing: Boolean = false) {
        suppressSavedReconnect = isFreshPairing
        cancelSavedReconnectRetry()
        resetThreadSessionState(
            isLoadingThreadList = false,
            preservePendingNotificationTarget = true,
        )
        stateFlow.update {
            it.copy(
                freshPairingAttempt = if (isFreshPairing) {
                    FreshPairingAttemptState(stage = FreshPairingStage.CONNECTING)
                } else {
                    null
                },
            )
        }
        try {
            if (isFreshPairing) {
                runCatching {
                    repository.disconnect(clearSavedPairing = false)
                }
            }
            repository.connectWithPairingPayload(rawPayload)
            suppressSavedReconnect = false
            stateFlow.update { it.copy(freshPairingAttempt = null) }
            refreshThreadsInternal()
            loadWorkspaceState()
        } catch (error: Throwable) {
            if (isFreshPairing) {
                failFreshPairingAttempt()
            }
            throw error
        }
    }

    suspend fun reconnectSaved() {
        suppressSavedReconnect = false
        cancelSavedReconnectRetry()
        stateFlow.update { it.copy(freshPairingAttempt = null) }
        val connected = repository.reconnectSaved()
        if (connected) {
            refreshThreadsInternal()
            loadWorkspaceState()
        }
    }

    suspend fun forgetTrustedHost() {
        suppressSavedReconnect = true
        cancelSavedReconnectRetry()
        resetThreadSessionState()
        stateFlow.update { it.copy(freshPairingAttempt = null) }
        repository.forgetTrustedHost()
    }

    fun reconnectSavedIfAvailable() {
        if (!appInForeground) {
            return
        }
        val snapshot = stateFlow.value
        if (!snapshot.hasSavedPairing || savedReconnectInFlight || snapshot.freshPairingAttempt != null) {
            return
        }
        if (snapshot.connectionStatus == ConnectionStatus.CONNECTED
            || snapshot.connectionStatus == ConnectionStatus.CONNECTING
            || snapshot.connectionStatus == ConnectionStatus.HANDSHAKING
            || snapshot.connectionStatus == ConnectionStatus.TRUST_BLOCKED
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
        if (clearSavedPairing) {
            resetThreadSessionState(isLoadingThreadList = false)
        }
        stateFlow.update {
            it.copy(
                freshPairingAttempt = null,
                isLoadingThreadList = false,
                focusedTurnId = null,
            )
        }
    }

    suspend fun refreshThreads() {
        refreshThreadsInternal()
        loadWorkspaceState()
    }

    suspend fun openThread(
        threadId: String,
        forceRefresh: Boolean = false,
    ) {
        val openAttemptRevision = threadOpenAttemptRevision.incrementAndGet()
        ThreadOpenPerfLogger.ensureAttempt(threadId, stage = "AndrodexService.openThread:start")
        ThreadOpenPerfLogger.measure(threadId, stage = "AndrodexService.openThread") {
            val targetThread = stateFlow.value.threads.firstOrNull { it.id == threadId }
            val previousState = stateFlow.value
            val previousWorkspacePath = previousState.activeWorkspacePath
            val selectionStartedAt = System.currentTimeMillis()
            prepareThreadForDisplay(threadId = threadId, targetThread = targetThread)
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "AndrodexService.openThread.selectThread",
                durationMs = System.currentTimeMillis() - selectionStartedAt,
                extra = "hasTarget=${targetThread != null}",
            )

            val workspaceStartedAt = System.currentTimeMillis()
            val workspaceSwitched = try {
                ensureWorkspaceActivated(
                    path = targetThread?.cwd,
                    incomingThreadId = threadId,
                )
            } catch (error: Throwable) {
                if (isCurrentThreadOpenAttempt(openAttemptRevision)) {
                    restoreThreadPresentationState(previousState)
                }
                throw error
            }
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "AndrodexService.openThread.ensureWorkspace",
                durationMs = System.currentTimeMillis() - workspaceStartedAt,
                extra = "hasCwd=${!targetThread?.cwd.isNullOrBlank()}",
            )

            val shouldForceHydrate = forceRefresh
                || isThreadConsideredRunning(threadId, previousState)
                || previousState.selectedThreadId != threadId
            val hydrateStartedAt = System.currentTimeMillis()
            try {
                ensureThreadHydrated(threadId, forceRefresh = shouldForceHydrate)
            } catch (error: Throwable) {
                if (workspaceSwitched && isCurrentThreadOpenAttempt(openAttemptRevision)) {
                    restoreWorkspaceSwitchFailure(
                        previousState = previousState,
                        previousWorkspacePath = previousWorkspacePath,
                    )
                }
                throw error
            }
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "AndrodexService.openThread.ensureThreadHydrated",
                durationMs = System.currentTimeMillis() - hydrateStartedAt,
                extra = "force=$shouldForceHydrate hydrated=${isThreadHydrated(threadId)}",
            )
        }
    }

    fun handleNotificationOpen(threadId: String, turnId: String?) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        stateFlow.update {
            it.copy(
                pendingNotificationOpenThreadId = normalizedThreadId,
                pendingNotificationOpenTurnId = turnId?.trim()?.takeIf { value -> value.isNotEmpty() },
                focusedTurnId = null,
                missingNotificationThreadPrompt = null,
            )
        }
        scope.launch {
            routePendingNotificationOpenIfPossible()
        }
    }

    suspend fun createThread(preferredWorkspacePath: String? = null) {
        val preferredWorkspace = preferredWorkspacePath ?: stateFlow.value.activeWorkspacePath
        ensureWorkspaceActivated(preferredWorkspace)
        val thread = repository.startThread(preferredWorkspace)
        stateFlow.update { current ->
            val sanitized = current.clearScopedStateForThread(thread.id)
            sanitized.copy(
                threads = mergeRecoveredNotificationThread(sanitized.threads, thread),
                selectedThreadId = thread.id,
                selectedThreadTitle = thread.title.ifBlank { "Conversation" },
                focusedTurnId = null,
                threadTimelineStateByThread = sanitized.threadTimelineStateByThread.withThreadMessages(
                    threadId = thread.id,
                    rawMessages = emptyList(),
                ),
                hydratedThreadIds = sanitized.hydratedThreadIds + thread.id,
                hydratedThreadVersions = sanitized.hydratedThreadVersions + (thread.id to threadHydrationVersion(thread)),
            )
        }
        scheduleThreadCollectionsRefresh()
    }

    suspend fun sendMessage(
        input: String,
        preferredThreadId: String? = stateFlow.value.selectedThreadId,
        attachments: List<ImageAttachment> = emptyList(),
        fileMentions: List<TurnFileMention> = emptyList(),
        skillMentions: List<TurnSkillMention> = emptyList(),
        collaborationMode: CollaborationModeKind? = null,
    ) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty() && attachments.isEmpty()) {
            return
        }

        val preferredWorkspace = stateFlow.value.activeWorkspacePath
        val threadId = preferredThreadId?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            val thread = repository.startThread(preferredWorkspace)
            stateFlow.update { current ->
                val sanitized = current.clearScopedStateForThread(thread.id)
                sanitized.copy(
                    threads = mergeRecoveredNotificationThread(sanitized.threads, thread),
                    selectedThreadId = thread.id,
                    selectedThreadTitle = thread.title.ifBlank { "Conversation" },
                    focusedTurnId = null,
                    threadTimelineStateByThread = sanitized.threadTimelineStateByThread.withThreadMessages(
                        threadId = thread.id,
                        rawMessages = sanitized.threadMessages(thread.id),
                    ),
                    hydratedThreadIds = sanitized.hydratedThreadIds + thread.id,
                    hydratedThreadVersions = sanitized.hydratedThreadVersions + (thread.id to threadHydrationVersion(thread)),
                )
            }
            scheduleThreadCollectionsRefresh()
            thread.id
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
                            fileMentions = fileMentions,
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
                            fileMentions = fileMentions,
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
                    fileMentions = fileMentions,
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

    suspend fun shouldQueueFollowUp(threadId: String): Boolean {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return false
        stateFlow.value.activeTurnIdByThread[normalizedThreadId]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return true }
        if (!isThreadConsideredRunning(normalizedThreadId)) {
            return false
        }

        val snapshot = repository.readThreadRunSnapshot(normalizedThreadId)
        syncThreadRunStateFromSnapshot(normalizedThreadId, snapshot)
        return snapshot.interruptibleTurnId != null
            || snapshot.hasInterruptibleTurnWithoutId
            || (snapshot.shouldAssumeRunningFromLatestTurn && snapshot.latestTurnId != null)
    }

    suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String? = null,
    ) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        clearThreadOutcome(normalizedThreadId)
        repository.startReview(
            threadId = normalizedThreadId,
            target = target,
            baseBranch = baseBranch,
        )
        markThreadRunning(normalizedThreadId, turnId = null)
        appendMessage(
            threadId = normalizedThreadId,
            message = ConversationMessage(
                id = UUID.randomUUID().toString(),
                threadId = normalizedThreadId,
                role = ConversationRole.USER,
                kind = ConversationKind.CHAT,
                text = reviewRequestText(target, baseBranch),
                attachments = emptyList(),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
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

    suspend fun respondToToolUserInput(
        threadId: String,
        requestId: String,
        answers: Map<String, String>,
    ) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedRequestId = requestId.trim().takeIf { it.isNotEmpty() } ?: return
        val request = stateFlow.value.pendingToolInputsByThread[normalizedThreadId]
            ?.get(normalizedRequestId)
            ?: return
        val response = ToolUserInputResponse(
            answers = request.questions.mapNotNull { question ->
                val answer = answers[question.id]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                question.id to ToolUserInputAnswer(answers = listOf(answer))
            }.toMap()
        )
        repository.respondToToolUserInput(request, response)
        stateFlow.update { current ->
            current.copy(
                pendingToolInputsByThread = current.pendingToolInputsByThread.removePendingToolInputRequest(
                    threadId = normalizedThreadId,
                    requestId = normalizedRequestId,
                )
            )
        }
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

    suspend fun selectAccessMode(accessMode: AccessMode) {
        repository.setSelectedAccessMode(accessMode)
    }

    suspend fun selectServiceTier(serviceTier: ServiceTier?) {
        repository.setSelectedServiceTier(serviceTier)
    }

    suspend fun setThreadRuntimeOverride(
        threadId: String,
        runtimeOverride: ThreadRuntimeOverride?,
    ) {
        repository.setThreadRuntimeOverride(threadId, runtimeOverride)
    }

    suspend fun compactThread(threadId: String) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        clearThreadOutcome(normalizedThreadId)
        repository.compactThread(normalizedThreadId)
        markThreadRunning(normalizedThreadId, turnId = null)
    }

    suspend fun rollbackThread(
        threadId: String,
        numTurns: Int = 1,
    ) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        val result = repository.rollbackThread(normalizedThreadId, numTurns)
        stateFlow.update { current ->
            current.copy(
                threads = mergeRecoveredNotificationThread(current.threads, result.thread),
                selectedThreadTitle = if (current.selectedThreadId == normalizedThreadId) {
                    result.thread?.title ?: current.selectedThreadTitle
                } else {
                    current.selectedThreadTitle
                },
                threadTimelineStateByThread = current.threadTimelineStateByThread.withThreadMessages(
                    threadId = normalizedThreadId,
                    rawMessages = result.messages,
                ),
                hydratedThreadIds = current.hydratedThreadIds + normalizedThreadId,
                hydratedThreadVersions = current.hydratedThreadVersions + (
                    normalizedThreadId to resolveHydratedThreadVersion(
                        threadId = normalizedThreadId,
                        thread = result.thread,
                        snapshot = current,
                    )
                ),
                readyThreadIds = current.readyThreadIds - normalizedThreadId,
                failedThreadIds = current.failedThreadIds - normalizedThreadId,
            )
        }
        syncThreadRunStateFromSnapshot(normalizedThreadId, result.runSnapshot)
        refreshThreadsInternal()
    }

    suspend fun cleanBackgroundTerminals(threadId: String) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        repository.cleanBackgroundTerminals(normalizedThreadId)
        ensureThreadHydrated(
            threadId = normalizedThreadId,
            forceRefresh = true,
            keepStreamingRowsOverride = false,
        )
        refreshThreadsInternal()
    }

    suspend fun forkThread(
        sourceThreadId: String,
        preferredProjectPath: String? = null,
    ) {
        val sourceThread = stateFlow.value.threads.firstOrNull { it.id == sourceThreadId }
        val forkedThread = repository.forkThread(
            threadId = sourceThreadId,
            preferredProjectPath = preferredProjectPath,
            preferredModel = sourceThread?.model,
        )
        stateFlow.update { current ->
            val sanitized = current.clearScopedStateForThread(forkedThread.id)
            sanitized.copy(
                selectedThreadId = forkedThread.id,
                selectedThreadTitle = forkedThread.title,
                focusedTurnId = null,
                threadTimelineStateByThread = sanitized.threadTimelineStateByThread.withThreadMessages(
                    threadId = forkedThread.id,
                    rawMessages = sanitized.threadMessages(forkedThread.id),
                ),
            )
        }
        ensureWorkspaceActivated(
            path = forkedThread.cwd ?: preferredProjectPath,
            incomingThreadId = forkedThread.id,
        )
        refreshThreadsInternal()
        loadWorkspaceState()
        ensureThreadHydrated(forkedThread.id, forceRefresh = true)
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
        resetThreadSessionState(isLoadingThreadList = true)
        stateFlow.update {
            it.copy(
                activeWorkspacePath = status.currentCwd,
                workspaceBrowserPath = null,
                workspaceBrowserParentPath = null,
                workspaceBrowserEntries = emptyList(),
                isWorkspaceBrowserLoading = false,
            )
        }
        refreshThreadsInternal()
        val recent = repository.listRecentWorkspaces()
        stateFlow.update {
            it.copy(
                activeWorkspacePath = status.currentCwd,
                recentWorkspaces = recent.recentWorkspaces,
                isWorkspaceBrowserLoading = false,
            )
        }
    }

    private suspend fun refreshThreadsInternal(allowDuringSelectedThreadLoad: Boolean = false) {
        if (!allowDuringSelectedThreadLoad && isSelectedThreadLoadInFlight()) {
            deferredThreadListRefreshPending = true
            return
        }
        val startedAt = System.currentTimeMillis()
        stateFlow.update { it.copy(isLoadingThreadList = true) }
        try {
            val threads = threadCollectionRequestMutex.withLock {
                repository.refreshThreads()
            }
            applyLoadedThreads(threads)
        } finally {
            val elapsedMs = System.currentTimeMillis() - startedAt
            val remainingMs = minimumThreadListLoadingVisibleMs - elapsedMs
            if (remainingMs > 0) {
                delay(remainingMs)
            }
            stateFlow.update { it.copy(isLoadingThreadList = false) }
        }
    }

    private fun scheduleThreadCollectionsRefresh() {
        scope.launch {
            runCatching { refreshThreadsInternal() }
            runCatching { loadWorkspaceState() }
        }
    }

    private fun applyLoadedThreads(threads: List<ThreadSummary>) {
        updateSubagentIdentitiesFromThreads(threads)
        val threadIds = threads.mapTo(linkedSetOf()) { it.id }
        optimisticWorkspaceThreadIds.removeAll(threadIds)
        val threadsById = threads.associateBy { it.id }
        stateFlow.update { current ->
            val nextHydratedThreadIds = current.hydratedThreadIds.filterTo(linkedSetOf()) { threadId ->
                shouldKeepHydratedThread(
                    threadId = threadId,
                    snapshot = current,
                    refreshedThread = threadsById[threadId],
                    knownThreadIds = threadIds,
                )
            }
            current.copy(
                threads = threads,
                hasLoadedThreadList = true,
                selectedThreadTitle = threads.firstOrNull { it.id == current.selectedThreadId }?.title
                    ?: current.selectedThreadTitle,
                hydratedThreadIds = nextHydratedThreadIds,
                hydratedThreadVersions = current.hydratedThreadVersions.filterKeys { it in nextHydratedThreadIds },
            )
        }
        refreshSubagentMessages()
        if (stateFlow.value.pendingNotificationOpenThreadId != null) {
            scope.launch {
                routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
            }
        }
    }

    private fun invalidateHydratedThreads() {
        threadSessionContextRevision.incrementAndGet()
        optimisticWorkspaceThreadIds.clear()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            threadHydrationRequestMutex.withLock {
                threadHydrationLoads.clear()
                threadHydrationLoadContextRevisions.clear()
                threadHydrationForceLoadRevisions.clear()
                threadHydrationForceRequestRevisions.clear()
                threadHydrationForceSatisfiedRevisions.clear()
            }
        }
        stateFlow.update { current ->
            if (current.hydratedThreadIds.isEmpty() && current.hydratedThreadVersions.isEmpty()) {
                current
            } else {
                current.copy(
                    hydratedThreadIds = emptySet(),
                    hydratedThreadVersions = emptyMap(),
                )
            }
        }
    }

    private fun clearThreadSessionState(
        isLoadingThreadList: Boolean? = null,
        preservePendingNotificationTarget: Boolean = false,
    ) {
        threadSessionContextRevision.incrementAndGet()
        optimisticWorkspaceThreadIds.clear()
        selectedThreadLoadCounts.clear()
        deferredThreadListRefreshPending = false
        subagentIdentityByThreadId.clear()
        subagentIdentityByAgentId.clear()
        stateFlow.update { current ->
            current.copy(
                threads = emptyList(),
                hasLoadedThreadList = false,
                isLoadingThreadList = isLoadingThreadList ?: current.isLoadingThreadList,
                selectedThreadId = null,
                selectedThreadTitle = null,
                threadTimelineStateByThread = emptyMap(),
                hydratedThreadIds = emptySet(),
                hydratedThreadVersions = emptyMap(),
                activeTurnIdByThread = emptyMap(),
                runningThreadIds = emptySet(),
                protectedRunningFallbackThreadIds = emptySet(),
                readyThreadIds = emptySet(),
                failedThreadIds = emptySet(),
                latestTurnTerminalStateByThread = emptyMap(),
                tokenUsageByThread = emptyMap(),
                pendingNotificationOpenThreadId = if (preservePendingNotificationTarget) {
                    current.pendingNotificationOpenThreadId
                } else {
                    null
                },
                pendingNotificationOpenTurnId = if (preservePendingNotificationTarget) {
                    current.pendingNotificationOpenTurnId
                } else {
                    null
                },
                focusedTurnId = null,
                missingNotificationThreadPrompt = null,
                pendingToolInputsByThread = emptyMap(),
            )
        }
    }

    private suspend fun resetThreadSessionState(
        isLoadingThreadList: Boolean? = null,
        preservePendingNotificationTarget: Boolean = false,
    ) {
        clearThreadSessionState(
            isLoadingThreadList = isLoadingThreadList,
            preservePendingNotificationTarget = preservePendingNotificationTarget,
        )
        threadHydrationRequestMutex.withLock {
            threadHydrationLoads.clear()
            threadHydrationLoadContextRevisions.clear()
            threadHydrationForceLoadRevisions.clear()
            threadHydrationForceRequestRevisions.clear()
            threadHydrationForceSatisfiedRevisions.clear()
        }
    }

    private suspend fun clearThreadScopedStateForWorkspaceSwitch(incomingThreadId: String? = null) {
        threadSessionContextRevision.incrementAndGet()
        optimisticWorkspaceThreadIds.clear()
        selectedThreadLoadCounts.clear()
        deferredThreadListRefreshPending = false
        subagentIdentityByThreadId.clear()
        subagentIdentityByAgentId.clear()
        threadHydrationRequestMutex.withLock {
            threadHydrationLoads.clear()
            threadHydrationLoadContextRevisions.clear()
            threadHydrationForceLoadRevisions.clear()
            threadHydrationForceRequestRevisions.clear()
            threadHydrationForceSatisfiedRevisions.clear()
        }
        stateFlow.update { current ->
            current.clearWorkspaceScopedThreadState()
        }
    }

    private fun isSelectedThreadLoadInFlight(snapshot: AndrodexServiceState = stateFlow.value): Boolean {
        val selectedThreadId = snapshot.selectedThreadId ?: return false
        return (selectedThreadLoadCounts[selectedThreadId] ?: 0) > 0
    }

    private fun markSelectedThreadLoadStarted(threadId: String): Boolean {
        val selectedThreadId = stateFlow.value.selectedThreadId ?: return false
        if (selectedThreadId != threadId) {
            return false
        }
        selectedThreadLoadCounts[threadId] = (selectedThreadLoadCounts[threadId] ?: 0) + 1
        return true
    }

    private fun markSelectedThreadLoadFinished(threadId: String) {
        val currentCount = selectedThreadLoadCounts[threadId] ?: return
        if (currentCount <= 1) {
            selectedThreadLoadCounts.remove(threadId)
        } else {
            selectedThreadLoadCounts[threadId] = currentCount - 1
        }
        if (isSelectedThreadLoadInFlight()) {
            return
        }
        if (!deferredThreadListRefreshPending) {
            return
        }
        deferredThreadListRefreshPending = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                refreshThreadsInternal(allowDuringSelectedThreadLoad = true)
            }
        }
    }

    private suspend fun loadThreadIntoState(
        threadId: String,
        keepStreamingRowsOverride: Boolean? = null,
        expectedContextRevision: Long = threadSessionContextRevision.get(),
    ) {
        val trackedSelectedThreadLoad = markSelectedThreadLoadStarted(threadId)
        try {
            ThreadOpenPerfLogger.ensureAttempt(
                threadId = threadId,
                stage = "AndrodexService.loadThreadIntoState:start",
                extra = "keepStreamingOverride=$keepStreamingRowsOverride",
            )
            val totalStartedAt = System.currentTimeMillis()
            val loadStartedAt = System.currentTimeMillis()
            val result = threadCollectionRequestMutex.withLock {
                repository.loadThread(threadId)
            }
            if (expectedContextRevision != threadSessionContextRevision.get()) {
                return
            }
            val loadDurationMs = System.currentTimeMillis() - loadStartedAt
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "AndrodexService.loadThreadIntoState.repositoryLoad",
                durationMs = loadDurationMs,
                extra = "incoming=${result.messages.size}",
            )
            val turnIdToInvalidatePendingToolInputs =
                pendingToolInputTurnToInvalidateAfterThreadRecovery(result.runSnapshot)
            var mergedMessageCount = 0
            val mergeStartedAt = System.currentTimeMillis()
            stateFlow.update { current ->
                val existingMessages = current.threadMessages(threadId)
                val keepStreamingRows = keepStreamingRowsOverride ?: isThreadConsideredRunning(threadId, current)
                val mergedMessages = mergeThreadMessages(
                    threadId = threadId,
                    existing = existingMessages,
                    incoming = result.messages,
                    keepStreamingRows = keepStreamingRows,
                )
                mergedMessageCount = mergedMessages.size
                current.copy(
                    selectedThreadTitle = if (current.selectedThreadId == threadId) {
                        result.thread?.title ?: current.selectedThreadTitle
                    } else {
                        current.selectedThreadTitle
                    },
                    threadTimelineStateByThread = current.threadTimelineStateByThread.withThreadMessages(
                        threadId = threadId,
                        rawMessages = mergedMessages,
                    ),
                    hydratedThreadIds = current.hydratedThreadIds + threadId,
                    hydratedThreadVersions = current.hydratedThreadVersions + (
                        threadId to resolveHydratedThreadVersion(
                            threadId = threadId,
                            thread = result.thread,
                            snapshot = current,
                        )
                    ),
                    pendingToolInputsByThread = if (turnIdToInvalidatePendingToolInputs != null) {
                        current.pendingToolInputsByThread.removePendingToolInputTurn(
                            threadId = threadId,
                            turnId = turnIdToInvalidatePendingToolInputs,
                        )
                    } else {
                        current.pendingToolInputsByThread
                    },
                )
            }
            val mergeDurationMs = System.currentTimeMillis() - mergeStartedAt
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "AndrodexService.loadThreadIntoState.stateMerge",
                durationMs = mergeDurationMs,
                extra = "merged=$mergedMessageCount",
            )
            ThreadOpenPerfLogger.logStage(
                threadId = threadId,
                stage = "AndrodexService.loadThreadIntoState:complete",
                durationMs = System.currentTimeMillis() - totalStartedAt,
                extra = "incoming=${result.messages.size} merged=$mergedMessageCount",
            )
            if (loadDurationMs >= slowThreadLoadLogThresholdMs || mergeDurationMs >= slowThreadLoadLogThresholdMs) {
                Log.i(
                    logTag,
                    "loadThreadIntoState thread=$threadId hostLoadMs=$loadDurationMs mergeMs=$mergeDurationMs incoming=${result.messages.size} merged=$mergedMessageCount",
                )
            }
            syncThreadRunStateFromSnapshot(threadId, result.runSnapshot)
        } finally {
            if (trackedSelectedThreadLoad) {
                markSelectedThreadLoadFinished(threadId)
            }
        }
    }

    internal suspend fun routePendingNotificationOpenIfPossible(refreshIfNeeded: Boolean = true): Boolean {
        val pendingThreadId = stateFlow.value.pendingNotificationOpenThreadId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false

        return when (val lookup = resolveNotificationTargetThread(
            threadId = pendingThreadId,
            refreshIfNeeded = refreshIfNeeded,
        )) {
            is NotificationThreadLookupResult.Found -> when (attemptPendingNotificationOpen(lookup.thread)) {
                NotificationRouteResult.Opened -> true
                NotificationRouteResult.Missing -> {
                    finalizeMissingNotificationThread(pendingThreadId)
                    false
                }
                NotificationRouteResult.Unavailable -> false
            }

            NotificationThreadLookupResult.Missing -> {
                finalizeMissingNotificationThread(pendingThreadId)
                false
            }

            NotificationThreadLookupResult.Unavailable -> false
        }
    }

    private suspend fun ensureWorkspaceActivated(
        path: String?,
        incomingThreadId: String? = null,
    ): Boolean {
        val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (normalizedPath == stateFlow.value.activeWorkspacePath) {
            return false
        }
        val status = repository.activateWorkspace(normalizedPath)
        clearThreadScopedStateForWorkspaceSwitch(incomingThreadId = incomingThreadId)
        stateFlow.update { it.copy(activeWorkspacePath = status.currentCwd ?: normalizedPath) }
        return true
    }

    private suspend fun resolveNotificationTargetThread(
        threadId: String,
        refreshIfNeeded: Boolean,
    ): NotificationThreadLookupResult {
        stateFlow.value.threads.firstOrNull { it.id == threadId }?.let {
            return NotificationThreadLookupResult.Found(it)
        }
        if (stateFlow.value.connectionStatus != ConnectionStatus.CONNECTED || !refreshIfNeeded) {
            return NotificationThreadLookupResult.Unavailable
        }
        val refreshed = runCatching {
            refreshThreadsInternal()
            true
        }.getOrDefault(false)
        if (!refreshed) {
            return NotificationThreadLookupResult.Unavailable
        }
        return stateFlow.value.threads.firstOrNull { it.id == threadId }
            ?.let(NotificationThreadLookupResult::Found)
            ?: NotificationThreadLookupResult.Missing
    }

    private suspend fun attemptPendingNotificationOpen(thread: ThreadSummary): NotificationRouteResult {
        val threadId = thread.id
        val previousState = stateFlow.value
        val previousWorkspacePath = previousState.activeWorkspacePath
        var workspaceSwitched = false
        return runCatching {
            workspaceSwitched = ensureWorkspaceActivated(
                path = thread.cwd,
                incomingThreadId = threadId,
            )
            repository.loadThread(threadId)
        }.fold(
            onSuccess = { result ->
                openNotificationThread(threadId, result)
                NotificationRouteResult.Opened
            },
            onFailure = { error ->
                if (workspaceSwitched) {
                    runCatching {
                        restoreWorkspaceSwitchFailure(
                            previousState = previousState,
                            previousWorkspacePath = previousWorkspacePath,
                        )
                    }
                } else {
                    restoreThreadPresentationState(previousState)
                }
                if (isMissingNotificationThreadError(error)) {
                    NotificationRouteResult.Missing
                } else {
                    NotificationRouteResult.Unavailable
                }
            },
        )
    }

    private suspend fun openNotificationThread(
        threadId: String,
        result: ThreadLoadResult,
    ) {
        ensureWorkspaceActivated(
            path = result.thread?.cwd,
            incomingThreadId = threadId,
        )
        val pendingFocusedTurnId = stateFlow.value.pendingNotificationOpenTurnId
        val turnIdToInvalidatePendingToolInputs =
            pendingToolInputTurnToInvalidateAfterThreadRecovery(result.runSnapshot)
        stateFlow.update { current ->
            val updatedThreads = mergeRecoveredNotificationThread(current.threads, result.thread)
            current.copy(
                threads = updatedThreads,
                selectedThreadId = threadId,
                selectedThreadTitle = result.thread?.title ?: current.selectedThreadTitle,
                threadTimelineStateByThread = current.threadTimelineStateByThread.withThreadMessages(
                    threadId = threadId,
                    rawMessages = mergeThreadMessages(
                        threadId = threadId,
                        existing = current.threadMessages(threadId),
                        incoming = result.messages,
                        keepStreamingRows = isThreadConsideredRunning(threadId, current),
                    ),
                ),
                hydratedThreadIds = current.hydratedThreadIds + threadId,
                hydratedThreadVersions = current.hydratedThreadVersions + (
                    threadId to resolveHydratedThreadVersion(
                        threadId = threadId,
                        thread = result.thread,
                        snapshot = current,
                    )
                ),
                pendingNotificationOpenThreadId = null,
                pendingNotificationOpenTurnId = null,
                focusedTurnId = pendingFocusedTurnId,
                missingNotificationThreadPrompt = null,
                pendingToolInputsByThread = if (turnIdToInvalidatePendingToolInputs != null) {
                    current.pendingToolInputsByThread.removePendingToolInputTurn(
                        threadId = threadId,
                        turnId = turnIdToInvalidatePendingToolInputs,
                    )
                } else {
                    current.pendingToolInputsByThread
                },
                readyThreadIds = current.readyThreadIds - threadId,
                failedThreadIds = current.failedThreadIds - threadId,
            )
        }
        syncThreadRunStateFromSnapshot(threadId, result.runSnapshot)
    }

    private fun finalizeMissingNotificationThread(threadId: String) {
        stateFlow.update { current ->
            val fallbackThread = current.threads.firstOrNull { it.id != threadId }
            val nextSelectedThreadId = when {
                current.selectedThreadId == null -> fallbackThread?.id
                current.selectedThreadId == threadId -> fallbackThread?.id
                else -> current.selectedThreadId
            }
            current.copy(
                selectedThreadId = nextSelectedThreadId,
                selectedThreadTitle = current.threads.firstOrNull { it.id == nextSelectedThreadId }?.title,
                pendingNotificationOpenThreadId = null,
                pendingNotificationOpenTurnId = null,
                focusedTurnId = null,
                missingNotificationThreadPrompt = MissingNotificationThreadPrompt(threadId),
            )
        }
    }

    internal fun processClientUpdate(update: ClientUpdate) {
        when (update) {
            is ClientUpdate.Connection -> {
                val previousStatus = stateFlow.value.connectionStatus
                val freshPairingAttempt = stateFlow.value.freshPairingAttempt
                if (freshPairingAttempt != null) {
                    if (update.status == ConnectionStatus.RETRYING_SAVED_PAIRING) {
                        return
                    }
                    if ((update.status == ConnectionStatus.CONNECTING
                            || update.status == ConnectionStatus.HANDSHAKING
                            || update.status == ConnectionStatus.CONNECTED)
                        && freshPairingAttempt.stage != FreshPairingStage.CONNECTING
                    ) {
                        return
                    }
                }
                stateFlow.update {
                    it.copy(
                        freshPairingAttempt = if (update.status == ConnectionStatus.CONNECTED) null else it.freshPairingAttempt,
                        connectionStatus = update.status,
                        connectionDetail = update.detail,
                        secureFingerprint = update.fingerprint ?: it.secureFingerprint,
                        pendingApproval = if (update.status == ConnectionStatus.CONNECTED) it.pendingApproval else null,
                    )
                }
                when (update.status) {
                    ConnectionStatus.CONNECTED -> {
                        suppressSavedReconnect = false
                        cancelSavedReconnectRetry()
                        if (previousStatus != ConnectionStatus.CONNECTED) {
                            invalidateHydratedThreads()
                        }
                        scope.launch {
                            refreshThreadsInternal()
                            loadRuntimeConfig()
                            loadWorkspaceState()
                            recoverVisibleThreadState()
                            routePendingNotificationOpenIfPossible()
                        }
                    }

                    ConnectionStatus.RETRYING_SAVED_PAIRING -> {
                        if (appInForeground) {
                            scheduleSavedReconnectRetry()
                        } else {
                            cancelSavedReconnectRetry()
                        }
                    }

                    ConnectionStatus.DISCONNECTED -> {
                        cancelSavedReconnectRetry()
                        val snapshot = stateFlow.value
                        if (appInForeground
                            && snapshot.hasSavedPairing
                            && snapshot.trustedPairSnapshot?.hasSavedRelaySession == false
                        ) {
                            scheduleSavedReconnectRetry(immediate = true)
                        }
                    }

                    ConnectionStatus.TRUST_BLOCKED,
                    ConnectionStatus.RECONNECT_REQUIRED,
                    ConnectionStatus.UPDATE_REQUIRED -> {
                        cancelSavedReconnectRetry()
                    }

                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.HANDSHAKING -> Unit
                }
            }

            is ClientUpdate.PairingAvailability -> {
                if (!update.hasSavedPairing) {
                    clearThreadSessionState(isLoadingThreadList = false)
                }
                stateFlow.update {
                    it.copy(
                        hasSavedPairing = update.hasSavedPairing,
                        trustedPairSnapshot = repository.currentTrustedPairSnapshot(),
                        secureFingerprint = update.fingerprint,
                        threads = if (update.hasSavedPairing) it.threads else emptyList(),
                        hasLoadedThreadList = if (update.hasSavedPairing) it.hasLoadedThreadList else false,
                        selectedThreadId = if (update.hasSavedPairing) it.selectedThreadId else null,
                        selectedThreadTitle = if (update.hasSavedPairing) it.selectedThreadTitle else null,
                        focusedTurnId = if (update.hasSavedPairing) it.focusedTurnId else null,
                    )
                }
                if (!update.hasSavedPairing && stateFlow.value.freshPairingAttempt == null) {
                    suppressSavedReconnect = false
                    cancelSavedReconnectRetry()
                }
            }

            is ClientUpdate.ThreadsLoaded -> {
                applyLoadedThreads(update.threads)
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
                        threadTimelineStateByThread = current.threadTimelineStateByThread.withThreadMessages(
                            threadId = threadId,
                            rawMessages = update.messages,
                        ),
                        hydratedThreadIds = current.hydratedThreadIds + threadId,
                        hydratedThreadVersions = current.hydratedThreadVersions + (
                            threadId to resolveHydratedThreadVersion(
                                threadId = threadId,
                                thread = update.thread,
                                snapshot = current,
                            )
                        ),
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
                        selectedAccessMode = update.selectedAccessMode,
                        selectedServiceTier = update.selectedServiceTier,
                        supportsServiceTier = update.supportsServiceTier,
                        supportsThreadCompaction = update.supportsThreadCompaction,
                        supportsThreadRollback = update.supportsThreadRollback,
                        supportsBackgroundTerminalCleanup = update.supportsBackgroundTerminalCleanup,
                        supportsThreadFork = update.supportsThreadFork,
                        collaborationModes = update.collaborationModes,
                        threadRuntimeOverridesByThread = update.threadRuntimeOverridesByThread,
                    )
                }
            }

            is ClientUpdate.AccountStatusLoaded -> {
                stateFlow.update {
                    it.copy(
                        hostAccountSnapshot = update.snapshot,
                    )
                }
            }

            is ClientUpdate.TokenUsageUpdated -> {
                val threadId = update.threadId?.trim()?.takeIf { it.isNotEmpty() } ?: return
                if (!shouldAcceptExplicitThreadId(threadId, allowOptimisticUnknownExplicitThreadId = false)) {
                    return
                }
                stateFlow.update {
                    it.copy(
                        tokenUsageByThread = it.tokenUsageByThread + (threadId to update.usage),
                    )
                }
            }

            is ClientUpdate.SkillsChanged -> {
                stateFlow.update {
                    it.copy(
                        skillInventoryVersion = it.skillInventoryVersion + 1L,
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
                val threadId = resolveThreadId(
                    update.threadId,
                    update.turnId,
                    itemId = null,
                    allowOptimisticUnknownExplicitThreadId = true,
                ) ?: return
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

            is ClientUpdate.CommandExecutionUpdate -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    upsertExecutionMessage(
                        messages = messages,
                        threadId = threadId,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        kind = ConversationKind.COMMAND,
                        command = update.command,
                        status = update.status,
                        text = update.text,
                        execution = update.execution,
                        isStreaming = update.isStreaming,
                    )
                }
            }

            is ClientUpdate.ExecutionUpdate -> {
                val threadId = resolveThreadId(update.threadId, update.turnId, update.itemId) ?: return
                if (!update.turnId.isNullOrBlank() && shouldPromoteIncomingTurnActivity(threadId, update.turnId)) {
                    markThreadRunning(threadId, update.turnId)
                }
                updateThreadMessages(threadId) { messages ->
                    upsertExecutionMessage(
                        messages = messages,
                        threadId = threadId,
                        turnId = update.turnId,
                        itemId = update.itemId,
                        kind = ConversationKind.EXECUTION,
                        command = null,
                        status = update.execution.status,
                        text = update.text,
                        execution = update.execution,
                        isStreaming = update.isStreaming,
                    )
                }
            }

            is ClientUpdate.ApprovalRequested -> {
                stateFlow.update { it.copy(pendingApproval = update.request) }
            }

            is ClientUpdate.ToolUserInputRequested -> {
                when (val resolution = resolveToolInputThreadOwnership(update.request)) {
                    is ToolInputThreadOwnership.Resolved -> {
                        stateFlow.update { current ->
                            val existingForThread = current.pendingToolInputsByThread[resolution.threadId].orEmpty()
                            current.copy(
                                pendingToolInputsByThread = current.pendingToolInputsByThread + (
                                    resolution.threadId to (existingForThread + (update.request.requestId to update.request))
                                )
                            )
                        }
                    }

                    is ToolInputThreadOwnership.Unroutable -> {
                        stateFlow.update { it.copy(errorMessage = resolution.userMessage) }
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            runCatching {
                                repository.rejectToolUserInput(update.request, resolution.hostMessage)
                            }.onFailure { error ->
                                reportError(error.message ?: resolution.userMessage)
                            }
                        }
                    }
                }
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
                resolvedThreadId?.let {
                    markTurnCompleted(it, update.turnId, update.terminalState)
                    clearPendingToolInputsForTurn(
                        threadId = it,
                        turnId = update.turnId,
                    )
                    emitRunCompletionEvent(it, update.turnId, update.terminalState)
                }
                val selectedThreadId = stateFlow.value.selectedThreadId
                if (selectedThreadId != null && (resolvedThreadId == null || resolvedThreadId == selectedThreadId)) {
                    scope.launch {
                        ensureThreadHydrated(selectedThreadId, forceRefresh = true)
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
        allowOptimisticUnknownExplicitThreadId: Boolean = false,
    ): String? {
        val snapshot = stateFlow.value
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedThreadId != null) {
            return normalizedThreadId.takeIf {
                shouldAcceptExplicitThreadId(
                    threadId = it,
                    snapshot = snapshot,
                    allowOptimisticUnknownExplicitThreadId = allowOptimisticUnknownExplicitThreadId,
                )
            }
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

    private fun resolveTurnCompletionThreadId(
        threadId: String?,
        turnId: String?,
        allowOptimisticUnknownExplicitThreadId: Boolean = false,
    ): String? {
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        resolveThreadId(
            threadId,
            turnId,
            itemId = null,
            allowOptimisticUnknownExplicitThreadId = allowOptimisticUnknownExplicitThreadId,
        )?.let { return it }
        if (normalizedThreadId != null) {
            return null
        }

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
            current.copy(
                threadTimelineStateByThread = current.threadTimelineStateByThread.withThreadMessages(
                    threadId = threadId,
                    rawMessages = transform(current.threadMessages(threadId)),
                ),
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

    private fun resolveToolInputThreadOwnership(request: ToolUserInputRequest): ToolInputThreadOwnership {
        resolveThreadId(
            request.threadId,
            request.turnId,
            request.itemId,
            allowOptimisticUnknownExplicitThreadId = true,
        )?.let {
            return ToolInputThreadOwnership.Resolved(it)
        }
        if (!request.threadId.isNullOrBlank()) {
            return ToolInputThreadOwnership.Unroutable(
                userMessage = "Structured tool input no longer matches the active workspace. Reopen the relevant thread and retry from the host.",
                hostMessage = "Structured tool input thread id does not belong to the active workspace context.",
            )
        }

        val snapshot = stateFlow.value
        snapshot.selectedThreadId?.let {
            return ToolInputThreadOwnership.Resolved(it)
        }
        val knownThreadIds = buildSet {
            addAll(snapshot.timelineByThread.keys)
            addAll(snapshot.threads.map { it.id })
        }
        return when (knownThreadIds.size) {
            1 -> ToolInputThreadOwnership.Resolved(knownThreadIds.first())
            0 -> ToolInputThreadOwnership.Unroutable(
                userMessage = "Structured tool input could not be matched to a thread. Open the relevant thread and retry from the host.",
                hostMessage = "Structured tool input requires an explicit or uniquely derivable thread id.",
            )
            else -> ToolInputThreadOwnership.Unroutable(
                userMessage = "Structured tool input could not be matched to a single thread. Reopen the intended thread and retry from the host.",
                hostMessage = "Structured tool input could not be routed because multiple candidate threads are open.",
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

    private fun isThreadHydrated(threadId: String, snapshot: AndrodexServiceState = stateFlow.value): Boolean {
        return threadId in snapshot.hydratedThreadIds
    }

    private fun threadHydrationVersion(thread: ThreadSummary?): Long? {
        return thread?.updatedAtEpochMs
    }

    private fun resolveHydratedThreadVersion(
        threadId: String,
        thread: ThreadSummary?,
        snapshot: AndrodexServiceState,
    ): Long? {
        return threadHydrationVersion(thread)
            ?: snapshot.hydratedThreadVersions[threadId]
            ?: threadHydrationVersion(snapshot.threads.firstOrNull { it.id == threadId })
    }

    private fun shouldKeepHydratedThread(
        threadId: String,
        snapshot: AndrodexServiceState,
        refreshedThread: ThreadSummary?,
        knownThreadIds: Set<String>,
    ): Boolean {
        if (threadId !in knownThreadIds && threadId !in snapshot.timelineByThread) {
            return false
        }
        refreshedThread ?: return true
        val refreshedVersion = threadHydrationVersion(refreshedThread) ?: return false
        val hydratedVersion = snapshot.hydratedThreadVersions[threadId] ?: return false
        return refreshedVersion <= hydratedVersion
    }

    private fun shouldAcceptExplicitThreadId(
        threadId: String,
        snapshot: AndrodexServiceState = stateFlow.value,
        allowOptimisticUnknownExplicitThreadId: Boolean = false,
    ): Boolean {
        if (threadId == snapshot.selectedThreadId || threadId == snapshot.pendingNotificationOpenThreadId) {
            return true
        }
        if (threadId in optimisticWorkspaceThreadIds) {
            return true
        }
        if (threadId in snapshot.timelineByThread
            || threadId in snapshot.pendingToolInputsByThread
            || threadId in snapshot.activeTurnIdByThread
            || threadId in snapshot.runningThreadIds
            || threadId in snapshot.protectedRunningFallbackThreadIds
            || threadId in snapshot.readyThreadIds
            || threadId in snapshot.failedThreadIds
            || threadId in snapshot.latestTurnTerminalStateByThread
        ) {
            return true
        }

        val activeWorkspacePath = snapshot.activeWorkspacePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return true
        val knownThread = snapshot.threads.firstOrNull { it.id == threadId }
            ?: return when {
                hasKnownThreadRelationshipInWorkspace(
                    threadId = threadId,
                    activeWorkspacePath = activeWorkspacePath,
                    snapshot = snapshot,
                ) -> true
                allowOptimisticUnknownExplicitThreadId -> adoptOptimisticWorkspaceThreadId(threadId)
                else -> false
            }
        val knownThreadWorkspacePath = knownThread.cwd
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return true
        return knownThreadWorkspacePath == activeWorkspacePath
    }

    private fun adoptOptimisticWorkspaceThreadId(threadId: String): Boolean {
        val didAdd = optimisticWorkspaceThreadIds.add(threadId)
        if (didAdd) {
            scheduleThreadCollectionsRefresh()
        }
        return true
    }

    private fun hasKnownThreadRelationshipInWorkspace(
        threadId: String,
        activeWorkspacePath: String,
        snapshot: AndrodexServiceState,
    ): Boolean {
        return snapshot.threads.any { thread ->
            val threadWorkspacePath = thread.cwd
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@any false
            threadWorkspacePath == activeWorkspacePath
                && (thread.parentThreadId == threadId || thread.forkedFromThreadId == threadId)
        }
    }

    private fun AndrodexServiceState.clearScopedStateForThread(threadId: String): AndrodexServiceState {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return this
        return copy(
            threadTimelineStateByThread = threadTimelineStateByThread - normalizedThreadId,
            hydratedThreadIds = hydratedThreadIds - normalizedThreadId,
            hydratedThreadVersions = hydratedThreadVersions - normalizedThreadId,
            activeTurnIdByThread = activeTurnIdByThread - normalizedThreadId,
            runningThreadIds = runningThreadIds - normalizedThreadId,
            protectedRunningFallbackThreadIds = protectedRunningFallbackThreadIds - normalizedThreadId,
            readyThreadIds = readyThreadIds - normalizedThreadId,
            failedThreadIds = failedThreadIds - normalizedThreadId,
            latestTurnTerminalStateByThread = latestTurnTerminalStateByThread - normalizedThreadId,
            tokenUsageByThread = tokenUsageByThread - normalizedThreadId,
            pendingToolInputsByThread = pendingToolInputsByThread - normalizedThreadId,
        )
    }

    private fun AndrodexServiceState.clearWorkspaceScopedThreadState(): AndrodexServiceState {
        return copy(
            threadTimelineStateByThread = emptyMap(),
            hydratedThreadIds = emptySet(),
            hydratedThreadVersions = emptyMap(),
            activeTurnIdByThread = emptyMap(),
            runningThreadIds = emptySet(),
            protectedRunningFallbackThreadIds = emptySet(),
            readyThreadIds = emptySet(),
            failedThreadIds = emptySet(),
            latestTurnTerminalStateByThread = emptyMap(),
            tokenUsageByThread = emptyMap(),
            focusedTurnId = null,
            pendingToolInputsByThread = emptyMap(),
        )
    }

    private fun prepareThreadForDisplay(threadId: String, targetThread: ThreadSummary?) {
        stateFlow.update { current ->
            current.copy(
                selectedThreadId = threadId,
                selectedThreadTitle = targetThread?.title,
                focusedTurnId = null,
                readyThreadIds = current.readyThreadIds - threadId,
                failedThreadIds = current.failedThreadIds - threadId,
            )
        }
    }

    private suspend fun ensureThreadHydrated(
        threadId: String,
        forceRefresh: Boolean = false,
        keepStreamingRowsOverride: Boolean? = null,
    ) {
        val expectedContextRevision = threadSessionContextRevision.get()
        val requiredForceRevision = if (forceRefresh) {
            threadHydrationRequestMutex.withLock {
                val nextRevision = (threadHydrationForceRequestRevisions[threadId] ?: 0) + 1
                threadHydrationForceRequestRevisions[threadId] = nextRevision
                nextRevision
            }
        } else {
            null
        }
        while (true) {
            if (expectedContextRevision != threadSessionContextRevision.get()) {
                return
            }
            if (!forceRefresh && isThreadHydrated(threadId)) {
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "AndrodexService.ensureThreadHydrated.skip",
                    durationMs = 0L,
                    extra = "reason=already_hydrated",
                )
                return
            }

            var existingLoad: CompletableDeferred<Unit>? = null
            var newLoad: CompletableDeferred<Unit>? = null
            var alreadySatisfied = false
            threadHydrationRequestMutex.withLock {
                val existingLoadRevision = threadHydrationLoadContextRevisions[threadId]
                if (existingLoadRevision != null && existingLoadRevision != expectedContextRevision) {
                    threadHydrationLoads.remove(threadId)
                    threadHydrationLoadContextRevisions.remove(threadId)
                }
                if (requiredForceRevision != null
                    && (threadHydrationForceSatisfiedRevisions[threadId] ?: 0) >= requiredForceRevision
                ) {
                    alreadySatisfied = true
                } else {
                    existingLoad = threadHydrationLoads[threadId]
                    if (existingLoad == null) {
                        newLoad = CompletableDeferred()
                        threadHydrationLoads[threadId] = requireNotNull(newLoad)
                        threadHydrationLoadContextRevisions[threadId] = expectedContextRevision
                        if (forceRefresh && requiredForceRevision != null) {
                            threadHydrationForceLoadRevisions[threadId] = requiredForceRevision
                        } else {
                            threadHydrationForceLoadRevisions.remove(threadId)
                        }
                    }
                }
            }
            if (alreadySatisfied) {
                return
            }

            existingLoad?.let { inFlightLoad ->
                ThreadOpenPerfLogger.logStage(
                    threadId = threadId,
                    stage = "AndrodexService.ensureThreadHydrated.awaitInFlight",
                    durationMs = 0L,
                    extra = "force=$forceRefresh",
                )
                try {
                    inFlightLoad.await()
                } catch (error: Throwable) {
                    if (!forceRefresh) {
                        throw error
                    }
                }
                if (!forceRefresh) {
                    return
                }
                continue
            }

            val activeLoad = newLoad ?: return
            var failure: Throwable? = null
            try {
                if (!forceRefresh && isThreadHydrated(threadId)) {
                    ThreadOpenPerfLogger.logStage(
                        threadId = threadId,
                        stage = "AndrodexService.ensureThreadHydrated.skipAfterGate",
                        durationMs = 0L,
                        extra = "reason=already_hydrated",
                    )
                    return
                }
                loadThreadIntoState(
                    threadId = threadId,
                    keepStreamingRowsOverride = keepStreamingRowsOverride,
                    expectedContextRevision = expectedContextRevision,
                )
                return
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                threadHydrationRequestMutex.withLock {
                    if (threadHydrationLoads[threadId] === activeLoad) {
                        threadHydrationLoads.remove(threadId)
                        threadHydrationLoadContextRevisions.remove(threadId)
                    }
                    val completedForceRevision = threadHydrationForceLoadRevisions.remove(threadId)
                    if (completedForceRevision != null) {
                        threadHydrationForceSatisfiedRevisions[threadId] = maxOf(
                            threadHydrationForceSatisfiedRevisions[threadId] ?: 0,
                            completedForceRevision,
                        )
                    }
                }
                if (failure == null) {
                    activeLoad.complete(Unit)
                } else {
                    activeLoad.completeExceptionally(requireNotNull(failure))
                }
            }
        }
    }

    private suspend fun recoverVisibleThreadState() {
        val selectedThreadId = stateFlow.value.selectedThreadId
        if (selectedThreadId != null) {
            ensureThreadHydrated(selectedThreadId, forceRefresh = true)
        }

        val siblingRunningThreads = (stateFlow.value.runningThreadIds + stateFlow.value.protectedRunningFallbackThreadIds)
            .filterNot { it == selectedThreadId }
        siblingRunningThreads.forEach { threadId ->
            runCatching {
                syncThreadRunStateFromSnapshot(threadId, repository.readThreadRunSnapshot(threadId))
            }
        }
    }

    private fun isCurrentThreadOpenAttempt(attemptRevision: Long): Boolean {
        return threadOpenAttemptRevision.get() == attemptRevision
    }

    private suspend fun restoreWorkspaceSwitchFailure(
        previousState: AndrodexServiceState,
        previousWorkspacePath: String?,
    ) {
        val previousSelectedThreadId = previousState.selectedThreadId
        val normalizedPreviousWorkspacePath = previousWorkspacePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val workspaceRestored = if (normalizedPreviousWorkspacePath == null) {
            false
        } else if (normalizedPreviousWorkspacePath == stateFlow.value.activeWorkspacePath) {
            true
        } else {
            runCatching {
                val status = repository.activateWorkspace(normalizedPreviousWorkspacePath)
                stateFlow.update {
                    it.copy(activeWorkspacePath = status.currentCwd ?: normalizedPreviousWorkspacePath)
                }
            }.isSuccess
        }
        if (!workspaceRestored) {
            stateFlow.update {
                it.copy(
                    selectedThreadId = null,
                    selectedThreadTitle = null,
                    focusedTurnId = null,
                )
            }
            return
        }
        restoreThreadPresentationState(previousState)
        if (previousSelectedThreadId != null && previousSelectedThreadId !in previousState.hydratedThreadIds) {
            runCatching {
                ensureThreadHydrated(previousSelectedThreadId, forceRefresh = true)
            }
        }
    }

    private fun restoreThreadPresentationState(previousState: AndrodexServiceState) {
        stateFlow.update {
            it.copy(
                selectedThreadId = previousState.selectedThreadId,
                selectedThreadTitle = previousState.selectedThreadTitle,
                threadTimelineStateByThread = previousState.threadTimelineStateByThread,
                hydratedThreadIds = previousState.hydratedThreadIds,
                hydratedThreadVersions = previousState.hydratedThreadVersions,
                activeTurnIdByThread = previousState.activeTurnIdByThread,
                runningThreadIds = previousState.runningThreadIds,
                protectedRunningFallbackThreadIds = previousState.protectedRunningFallbackThreadIds,
                readyThreadIds = previousState.readyThreadIds,
                failedThreadIds = previousState.failedThreadIds,
                latestTurnTerminalStateByThread = previousState.latestTurnTerminalStateByThread,
                tokenUsageByThread = previousState.tokenUsageByThread,
                focusedTurnId = previousState.focusedTurnId,
                pendingToolInputsByThread = previousState.pendingToolInputsByThread,
            )
        }
    }

    private fun emitRunCompletionEvent(
        threadId: String,
        turnId: String?,
        terminalState: TurnTerminalState,
    ) {
        val threadTitle = stateFlow.value.threads.firstOrNull { it.id == threadId }?.title
            ?: stateFlow.value.selectedThreadTitle
            ?: "Conversation"
        runCompletionEventsFlow.tryEmit(
            RunCompletionEvent(
                threadId = threadId,
                turnId = turnId,
                terminalState = terminalState,
                threadTitle = threadTitle,
            )
        )
    }

    private fun clearPendingToolInputsForTurn(threadId: String, turnId: String?) {
        stateFlow.update { current ->
            current.copy(
                pendingToolInputsByThread = current.pendingToolInputsByThread.removePendingToolInputTurn(
                    threadId = threadId,
                    turnId = turnId,
                ),
            )
        }
    }

    private fun pendingToolInputTurnToInvalidateAfterThreadRecovery(snapshot: ThreadRunSnapshot): String? {
        return snapshot.latestTurnId?.takeIf {
            snapshot.latestTurnTerminalState != null
            && snapshot.interruptibleTurnId == null
            && !snapshot.hasInterruptibleTurnWithoutId
            && !snapshot.shouldAssumeRunningFromLatestTurn
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
                    val connected = repository.reconnectSaved()
                    if (connected) {
                        refreshThreadsInternal()
                        loadWorkspaceState()
                        break
                    }
                    delay(savedReconnectRetryDelayMs)
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

    private fun isMissingNotificationThreadError(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: return false
        return message.contains("thread not found")
            || message.contains("unknown thread")
            || Regex("""\bthread\b.*\bnot found\b""").containsMatchIn(message)
    }

    private fun mergeRecoveredNotificationThread(
        existing: List<ThreadSummary>,
        recovered: ThreadSummary?,
    ): List<ThreadSummary> {
        val normalized = recovered ?: return existing
        val mutable = existing.toMutableList()
        val index = mutable.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            mutable[index] = normalized
        } else {
            mutable.add(0, normalized)
        }
        return mutable
    }

    data class RunCompletionEvent(
        val threadId: String,
        val turnId: String?,
        val terminalState: TurnTerminalState,
        val threadTitle: String,
    )

    private enum class NotificationRouteResult {
        Opened,
        Missing,
        Unavailable,
    }

    private sealed interface NotificationThreadLookupResult {
        data class Found(val thread: ThreadSummary) : NotificationThreadLookupResult
        data object Missing : NotificationThreadLookupResult
        data object Unavailable : NotificationThreadLookupResult
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
            val nextTimeline = current.threadTimelineStateByThread.mapValues { (_, timelineState) ->
                val nextMessages = timelineState.rawMessages.map { message ->
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
                timelineState.withRawMessages(nextMessages)
            }
            current.copy(threadTimelineStateByThread = nextTimeline)
        }
    }
}

private fun AndrodexServiceState.threadMessages(threadId: String): List<ConversationMessage> {
    val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    return threadTimelineStateByThread[normalizedThreadId]?.rawMessages.orEmpty()
}

private fun Map<String, ThreadTimelineState>.withThreadMessages(
    threadId: String,
    rawMessages: List<ConversationMessage>,
): Map<String, ThreadTimelineState> {
    val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return this
    val existingState = this[normalizedThreadId]
    val nextState = existingState.withRawMessages(
        threadId = normalizedThreadId,
        rawMessages = rawMessages,
    )
    return this + (normalizedThreadId to nextState)
}

private fun ThreadTimelineState?.withRawMessages(
    threadId: String,
    rawMessages: List<ConversationMessage>,
): ThreadTimelineState {
    val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return this
        ?: ThreadTimelineState(threadId = threadId)
    val existingState = this
    if (existingState != null
        && existingState.threadId == normalizedThreadId
        && existingState.rawMessages == rawMessages
    ) {
        return existingState
    }
    val nextRevision = (existingState?.messageRevision ?: 0L) + 1L
    return ThreadTimelineState(
        threadId = normalizedThreadId,
        rawMessages = rawMessages,
        messageRevision = nextRevision,
        renderSnapshot = buildThreadTimelineRenderSnapshot(
            threadId = normalizedThreadId,
            messageRevision = nextRevision,
            messages = rawMessages,
        ),
    )
}

private fun ThreadTimelineState.withRawMessages(rawMessages: List<ConversationMessage>): ThreadTimelineState {
    return (this as ThreadTimelineState?).withRawMessages(
        threadId = threadId,
        rawMessages = rawMessages,
    )
}

private fun Map<String, Map<String, ToolUserInputRequest>>.removePendingToolInputRequest(
    threadId: String,
    requestId: String,
): Map<String, Map<String, ToolUserInputRequest>> {
    val requestsForThread = this[threadId].orEmpty() - requestId
    return if (requestsForThread.isEmpty()) {
        this - threadId
    } else {
        this + (threadId to requestsForThread)
    }
}

private fun Map<String, Map<String, ToolUserInputRequest>>.removePendingToolInputTurn(
    threadId: String,
    turnId: String?,
): Map<String, Map<String, ToolUserInputRequest>> {
    val normalizedTurnId = turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    val requestsForThread = this[threadId].orEmpty()
    if (requestsForThread.isEmpty()) {
        return this
    }

    val remaining = requestsForThread.filterValues { request ->
        request.turnId?.trim()?.takeIf { it.isNotEmpty() } != normalizedTurnId
    }
    return when {
        remaining.size == requestsForThread.size -> this
        remaining.isEmpty() -> this - threadId
        else -> this + (threadId to remaining)
    }
}

private sealed interface ActiveTurnResolution {
    data class Resolved(val turnId: String) : ActiveTurnResolution
    data object WaitingForTurnId : ActiveTurnResolution
    data object NoActiveTurn : ActiveTurnResolution
}

private sealed interface ToolInputThreadOwnership {
    data class Resolved(val threadId: String) : ToolInputThreadOwnership

    data class Unroutable(
        val userMessage: String,
        val hostMessage: String,
    ) : ToolInputThreadOwnership
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

private fun upsertExecutionMessage(
    messages: List<ConversationMessage>,
    threadId: String,
    turnId: String?,
    itemId: String?,
    kind: ConversationKind,
    command: String?,
    status: String?,
    text: String,
    execution: ExecutionContent?,
    isStreaming: Boolean,
): List<ConversationMessage> {
    val incoming = ConversationMessage(
        id = itemId ?: UUID.randomUUID().toString(),
        threadId = threadId,
        role = ConversationRole.SYSTEM,
        kind = kind,
        text = text,
        createdAtEpochMs = System.currentTimeMillis(),
        turnId = turnId,
        itemId = itemId,
        isStreaming = isStreaming,
        status = status,
        command = command,
        execution = execution,
    )
    val existingIndex = messages.indexOfLast { message ->
        message.role == ConversationRole.SYSTEM
            && message.kind == kind
            && executionMessagesRepresentSameItem(message, incoming)
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
        text = if (isStreaming && existing.isStreaming && incoming.text.length < existing.text.length) {
            existing.text
        } else {
            incoming.text.ifBlank { existing.text }
        },
        status = incoming.status ?: existing.status,
        command = incoming.command ?: existing.command,
        execution = incoming.execution ?: existing.execution,
        isStreaming = isStreaming || (existing.isStreaming && incoming.text.length <= existing.text.length),
    )
    return updated
}

private fun mergeThreadMessages(
    threadId: String? = null,
    existing: List<ConversationMessage>,
    incoming: List<ConversationMessage>,
    keepStreamingRows: Boolean,
): List<ConversationMessage> {
    return ThreadOpenPerfLogger.measure(
        threadId = threadId,
        stage = "AndrodexService.mergeThreadMessages",
        extra = {
            "existing=${existing.size} incoming=${incoming.size} keepStreaming=$keepStreamingRows"
        },
    ) {
        if (incoming.isEmpty()) {
            return@measure existing
        }

        val merged = if (keepStreamingRows) existing.toMutableList() else mutableListOf()
        val originalExistingCount = merged.size
        val consumedOriginalExistingIndexes = mutableSetOf<Int>()
        val mergeLookup = ThreadMessageMergeLookup(merged, originalExistingCount)
        incoming.forEach { incomingMessage ->
            val existingIndex = mergeLookup.findMatchingIndex(
                incoming = incomingMessage,
                merged = merged,
                consumedOriginalExistingIndexes = consumedOriginalExistingIndexes,
            )
            if (existingIndex >= 0) {
                if (existingIndex < originalExistingCount) {
                    consumedOriginalExistingIndexes += existingIndex
                }
                merged[existingIndex] = mergeMatchedMessage(
                    existing = merged[existingIndex],
                    incoming = incomingMessage,
                    keepStreamingRows = keepStreamingRows,
                )
            } else {
                merged += incomingMessage
                mergeLookup.recordAppendedIndex(merged.lastIndex, incomingMessage)
            }
        }
        merged.sortedBy { it.createdAtEpochMs }
    }
}

private class ThreadMessageMergeLookup(
    existing: List<ConversationMessage>,
    originalExistingCount: Int,
) {
    private val originalExistingCount = originalExistingCount
    private val originalItemIndexesById = linkedMapOf<String, ArrayDeque<Int>>()
    private val originalTurnIndexesByKey = linkedMapOf<FastTurnLookupKey, ArrayDeque<Int>>()
    private val originalChatIndexesByKey = linkedMapOf<FastChatLookupKey, ArrayDeque<Int>>()
    private val appendedChatIndexesByKey = linkedMapOf<FastChatLookupKey, ArrayDeque<Int>>()

    init {
        for (index in (originalExistingCount - 1) downTo 0) {
            recordOriginalIndex(index, existing[index])
        }
    }

    fun findMatchingIndex(
        incoming: ConversationMessage,
        merged: List<ConversationMessage>,
        consumedOriginalExistingIndexes: Set<Int>,
    ): Int {
        return matchOriginalByItemId(incoming, merged, consumedOriginalExistingIndexes)
            ?: matchOriginalByTurnKey(incoming, merged, consumedOriginalExistingIndexes)
            ?: matchOriginalByChatKey(incoming, merged, consumedOriginalExistingIndexes)
            ?: matchAppendedChat(incoming, merged)
            ?: findMatchingMessageIndexWithScan(
                incoming = incoming,
                merged = merged,
                originalExistingCount = originalExistingCount,
                consumedOriginalExistingIndexes = consumedOriginalExistingIndexes,
            )
    }

    fun recordAppendedIndex(index: Int, message: ConversationMessage) {
        val chatKey = fastChatLookupKey(message) ?: return
        appendedChatIndexesByKey.getOrPut(chatKey) { ArrayDeque() }.addFirst(index)
    }

    private fun recordOriginalIndex(index: Int, message: ConversationMessage) {
        normalizedItemId(message)?.let { itemId ->
            originalItemIndexesById.getOrPut(itemId) { ArrayDeque() }.addLast(index)
        }
        fastTurnLookupKey(message)?.let { key ->
            originalTurnIndexesByKey.getOrPut(key) { ArrayDeque() }.addLast(index)
        }
        fastChatLookupKey(message)?.let { key ->
            originalChatIndexesByKey.getOrPut(key) { ArrayDeque() }.addLast(index)
        }
    }

    private fun matchOriginalByItemId(
        incoming: ConversationMessage,
        merged: List<ConversationMessage>,
        consumedOriginalExistingIndexes: Set<Int>,
    ): Int? {
        val itemId = normalizedItemId(incoming) ?: return null
        return nextUsableIndex(
            indexes = originalItemIndexesById[itemId],
            merged = merged,
            consumedOriginalExistingIndexes = consumedOriginalExistingIndexes,
            matcher = { existing -> timelineMessagesRepresentSameItem(existing, incoming) },
        )
    }

    private fun matchOriginalByTurnKey(
        incoming: ConversationMessage,
        merged: List<ConversationMessage>,
        consumedOriginalExistingIndexes: Set<Int>,
    ): Int? {
        val key = fastTurnLookupKey(incoming) ?: return null
        return nextUsableIndex(
            indexes = originalTurnIndexesByKey[key],
            merged = merged,
            consumedOriginalExistingIndexes = consumedOriginalExistingIndexes,
            matcher = { existing -> timelineMessagesRepresentSameItem(existing, incoming) },
        )
    }

    private fun matchOriginalByChatKey(
        incoming: ConversationMessage,
        merged: List<ConversationMessage>,
        consumedOriginalExistingIndexes: Set<Int>,
    ): Int? {
        val key = fastChatLookupKey(incoming) ?: return null
        return nextUsableIndex(
            indexes = originalChatIndexesByKey[key],
            merged = merged,
            consumedOriginalExistingIndexes = consumedOriginalExistingIndexes,
            matcher = { existing -> chatTimelineMessagesRepresentSameItem(existing, incoming) },
        )
    }

    private fun matchAppendedChat(
        incoming: ConversationMessage,
        merged: List<ConversationMessage>,
    ): Int? {
        val key = fastChatLookupKey(incoming) ?: return null
        val indexes = appendedChatIndexesByKey[key] ?: return null
        return indexes.firstOrNull { index ->
            index in merged.indices && chatTimelineMessagesRepresentSameItem(merged[index], incoming)
        }
    }

    private fun nextUsableIndex(
        indexes: ArrayDeque<Int>?,
        merged: List<ConversationMessage>,
        consumedOriginalExistingIndexes: Set<Int>,
        matcher: (ConversationMessage) -> Boolean,
    ): Int? {
        val candidateIndexes = indexes ?: return null
        while (candidateIndexes.isNotEmpty()) {
            val candidateIndex = candidateIndexes.removeFirst()
            if (candidateIndex !in merged.indices || candidateIndex in consumedOriginalExistingIndexes) {
                continue
            }
            if (matcher(merged[candidateIndex])) {
                return candidateIndex
            }
        }
        return null
    }
}

private data class FastTurnLookupKey(
    val threadId: String,
    val role: ConversationRole,
    val kind: ConversationKind,
    val turnId: String,
)

private data class FastChatLookupKey(
    val threadId: String,
    val role: ConversationRole,
    val text: String,
    val attachmentsSignature: String,
)

private fun findMatchingMessageIndexWithScan(
    incoming: ConversationMessage,
    merged: List<ConversationMessage>,
    originalExistingCount: Int,
    consumedOriginalExistingIndexes: Set<Int>,
): Int {
    return ((minOf(originalExistingCount, merged.size) - 1) downTo 0).firstOrNull { index ->
        index !in consumedOriginalExistingIndexes
            && timelineMessagesRepresentSameItem(merged[index], incoming)
    } ?: -1
}

private fun normalizedItemId(message: ConversationMessage): String? {
    return message.itemId?.trim()?.takeIf { it.isNotEmpty() }
}

private fun fastTurnLookupKey(message: ConversationMessage): FastTurnLookupKey? {
    val turnId = message.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (message.kind == ConversationKind.COMMAND
        || message.kind == ConversationKind.EXECUTION
        || message.kind == ConversationKind.SUBAGENT_ACTION
    ) {
        return null
    }
    return FastTurnLookupKey(
        threadId = message.threadId,
        role = message.role,
        kind = message.kind,
        turnId = turnId,
    )
}

private fun fastChatLookupKey(message: ConversationMessage): FastChatLookupKey? {
    if (message.kind != ConversationKind.CHAT) {
        return null
    }
    return FastChatLookupKey(
        threadId = message.threadId,
        role = message.role,
        text = message.text,
        attachmentsSignature = attachmentSignature(message.attachments),
    )
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
        !keepStreamingRows -> incoming.text
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
        filePath = incoming.filePath ?: existing.filePath,
        status = incoming.status ?: existing.status,
        diffText = incoming.diffText ?: existing.diffText,
        command = incoming.command ?: existing.command,
        execution = incoming.execution ?: existing.execution,
        planExplanation = incoming.planExplanation ?: existing.planExplanation,
        planSteps = incoming.planSteps ?: existing.planSteps,
        subagentAction = incoming.subagentAction ?: existing.subagentAction,
    )
}

private fun timelineMessagesRepresentSameItem(
    existing: ConversationMessage,
    incoming: ConversationMessage,
): Boolean {
    return when {
        existing.kind in setOf(ConversationKind.COMMAND, ConversationKind.EXECUTION)
            && incoming.kind in setOf(ConversationKind.COMMAND, ConversationKind.EXECUTION) -> {
            executionMessagesRepresentSameItem(existing, incoming)
        }

        existing.kind == ConversationKind.SUBAGENT_ACTION && incoming.kind == ConversationKind.SUBAGENT_ACTION -> {
            subagentMessagesRepresentSameItem(existing, incoming)
        }

        else -> {
            messagesRepresentSameItem(existing, incoming)
                || chatTimelineMessagesRepresentSameItem(existing, incoming)
        }
    }
}

private fun chatTimelineMessagesRepresentSameItem(
    existing: ConversationMessage,
    incoming: ConversationMessage,
): Boolean {
    if (existing.threadId != incoming.threadId
        || existing.role != incoming.role
        || existing.kind != ConversationKind.CHAT
        || incoming.kind != ConversationKind.CHAT
        || existing.text != incoming.text
        || attachmentSignature(existing.attachments) != attachmentSignature(incoming.attachments)
    ) {
        return false
    }

    val existingTurnId = existing.turnId?.trim()?.takeIf { it.isNotEmpty() }
    val incomingTurnId = incoming.turnId?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        existingTurnId != null && incomingTurnId != null -> existingTurnId == incomingTurnId
        else -> kotlin.math.abs(existing.createdAtEpochMs - incoming.createdAtEpochMs) <= 60_000L
    }
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

    if (existing.threadId == incoming.threadId
        && existing.role == incoming.role
        && existing.kind == incoming.kind
        && ((existingTurnId == null && incomingTurnId != null) || (existingTurnId != null && incomingTurnId == null))
        && (existing.isStreaming || incoming.isStreaming)
    ) {
        return kotlin.math.abs(existing.createdAtEpochMs - incoming.createdAtEpochMs) <= 60_000L
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

private fun executionMessagesRepresentSameItem(
    existing: ConversationMessage,
    incoming: ConversationMessage,
): Boolean {
    val existingItemId = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
    val incomingItemId = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
    if (existingItemId != null || incomingItemId != null) {
        return existingItemId != null && existingItemId == incomingItemId
    }

    val existingTurnId = existing.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val incomingTurnId = incoming.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (existing.threadId != incoming.threadId
        || existingTurnId != incomingTurnId
        || existing.kind != incoming.kind
    ) {
        return false
    }

    val existingIdentity = executionIdentity(existing)
    val incomingIdentity = executionIdentity(incoming)
    if (existingIdentity != null && incomingIdentity != null) {
        return existingIdentity == incomingIdentity
            && (
                existing.shouldMergeAsInFlightExecution()
                    || shouldMergeReloadedExecutionSnapshot(existing, incoming)
                )
    }

    return existing.text == incoming.text
        && existing.status == incoming.status
        && (
            existing.shouldMergeAsInFlightExecution()
                || shouldMergeReloadedExecutionSnapshot(existing, incoming)
            )
}

private fun executionIdentity(message: ConversationMessage): String? {
    val normalizedCommand = message.command?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (normalizedCommand != null) {
        return "command:$normalizedCommand"
    }

    val execution = message.execution ?: return null
    val normalizedTitle = execution.title.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
    return "${execution.kind.name.lowercase()}:$normalizedTitle"
}

private fun shouldMergeReloadedExecutionSnapshot(
    existing: ConversationMessage,
    incoming: ConversationMessage,
): Boolean {
    if (existing.shouldMergeAsInFlightExecution() || incoming.shouldMergeAsInFlightExecution()) {
        return false
    }

    val existingStatus = normalizedExecutionMergeStatus(existing)
    val incomingStatus = normalizedExecutionMergeStatus(incoming)
    if (existingStatus != null && incomingStatus != null && existingStatus != incomingStatus) {
        return false
    }

    return abs(existing.createdAtEpochMs - incoming.createdAtEpochMs) <= executionReloadMergeWindowMs
}

private fun normalizedExecutionMergeStatus(message: ConversationMessage): String? {
    return message.status
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: message.execution?.status
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}

private fun ConversationMessage.shouldMergeAsInFlightExecution(): Boolean {
    return isStreaming || !isExecutionTerminalStatus(status ?: execution?.status)
}

private fun isExecutionTerminalStatus(status: String?): Boolean {
    return status?.trim()?.lowercase() in setOf(
        "completed",
        "complete",
        "done",
        "success",
        "succeeded",
        "failed",
        "error",
        "stopped",
        "cancelled",
        "canceled",
    )
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
