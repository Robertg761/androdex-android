package io.androdex.android.data

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.attachment.decodeDataUrlImageData
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.BackendKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitCheckoutResult
import io.androdex.android.model.GitCommitResult
import io.androdex.android.model.GitCreateBranchResult
import io.androdex.android.model.GitCreateWorktreeResult
import io.androdex.android.model.GitPullResult
import io.androdex.android.model.GitPushResult
import io.androdex.android.model.GitRemoveWorktreeResult
import io.androdex.android.model.GitRepoDiffResult
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.GitWorktreeChangeTransferMode
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountSnapshotOrigin
import io.androdex.android.model.HostAccountStatus
import io.androdex.android.model.HostRuntimeMetadata
import io.androdex.android.model.HostRuntimeTargetOption
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.MacNativePairingPayload
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ReasoningEffortOption
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceDirectoryEntry
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.WorkspaceRecentState
import io.androdex.android.model.requestId
import io.androdex.android.model.parseConnectPayloadDescriptor
import io.androdex.android.reviewRequestText
import io.androdex.android.transport.macnative.MacNativeBearerSession
import io.androdex.android.transport.macnative.MacNativeHttpException
import io.androdex.android.transport.macnative.MacNativePersistedSession
import io.androdex.android.transport.macnative.MacNativeServerTarget
import io.androdex.android.transport.macnative.MacNativeTransportStack
import io.androdex.android.transport.macnative.createMacNativeServerTarget
import io.androdex.android.transport.macnative.deriveMacNativePendingState
import io.androdex.android.transport.macnative.mapMacNativeSnapshotToThreadLoad
import io.androdex.android.transport.macnative.mapMacNativeSnapshotToThreadSummaries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class MacNativeAndrodexBackendClient(
    private val persistence: AndrodexPersistence,
    private val transportStack: MacNativeTransportStack,
) : AndrodexBackendClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updatesFlow = MutableSharedFlow<ClientUpdate>(extraBufferCapacity = 128)
    private val snapshotMutex = Mutex()
    private val commandMutex = Mutex()

    private var currentSession: MacNativeBearerSession? = loadPersistedBearerSession()
    private var currentEnvironmentId: String? = transportStack.sessionStore.loadBearerSession()?.environmentId
    private var currentHostLabel: String? = transportStack.sessionStore.loadBearerSession()?.hostLabel
    private var currentHostFingerprint: String? = transportStack.sessionStore.loadBearerSession()?.hostFingerprint
    private var currentSnapshot: JSONObject? = null
    private var currentSocket = null as io.androdex.android.transport.macnative.MacNativeWebSocketConnection?
    private var activeWorkspacePath: String? = null
    private var selectedModelId: String? = persistence.loadSelectedModelId()
    private var selectedReasoningEffort: String? = persistence.loadSelectedReasoningEffort()
    private var selectedAccessMode: AccessMode = persistence.loadSelectedAccessMode()
    private var selectedServiceTier: ServiceTier? = persistence.loadSelectedServiceTier()
    private var threadRuntimeOverridesByThread = persistence.loadThreadRuntimeOverrides(currentThreadTimelineScopeKey())
    private val observedThreadIds = linkedSetOf<String>()
    private var knownApprovalRequestId: String? = null
    private var knownToolInputsByThread: Map<String, Set<String>> = emptyMap()
    private var knownRunSnapshotsByThread: Map<String, ThreadRunSnapshot> = emptyMap()

    override val updates: SharedFlow<ClientUpdate> = updatesFlow.asSharedFlow()

    override fun hasSavedPairing(): Boolean = transportStack.sessionStore.loadBearerSession() != null

    override fun currentFingerprint(): String? = currentHostFingerprint

    override fun currentTrustedPairSnapshot(): TrustedPairSnapshot? {
        val persisted = transportStack.sessionStore.loadBearerSession() ?: return null
        return TrustedPairSnapshot(
            deviceId = currentDeviceId(persisted),
            relayUrl = persisted.httpBaseUrl,
            fingerprint = persisted.hostFingerprint,
            lastPairedAtEpochMs = null,
            displayName = persisted.hostLabel,
            hasSavedRelaySession = true,
        )
    }

    override fun currentThreadTimelineScopeKey(): String? {
        val deviceId = currentTrustedPairSnapshot()?.deviceId ?: return null
        return "$deviceId::mac-native"
    }

    override fun currentLegacyThreadTimelineScopeKey(): String? = currentTrustedPairSnapshot()?.deviceId

    override fun startupConnectionStatus(): ConnectionStatus? = null

    override fun startupConnectionDetail(): String? = null

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        val parsed = parseConnectPayloadDescriptor(rawPayload)
        require(parsed is io.androdex.android.model.ConnectPayloadDescriptor.MacNative) {
            "The provided payload is not a Mac-native pairing payload."
        }
        connectWithMacNativePayload(parsed.payload)
    }

    override suspend fun connectWithRecoveryPayload(rawPayload: String) {
        throw UnsupportedOperationException("Recovery payloads are only supported on the legacy bridge backend.")
    }

    override suspend fun reconnectSaved(): Boolean {
        val persisted = transportStack.sessionStore.loadBearerSession() ?: return false
        return runCatching {
            emitConnection(ConnectionStatus.CONNECTING, "Reconnecting to the paired Mac…")
            val session = MacNativeBearerSession(
                serverTarget = MacNativeServerTarget(
                    httpBaseUrl = persisted.httpBaseUrl,
                    wsBaseUrl = persisted.wsBaseUrl,
                ),
                sessionToken = persisted.sessionToken,
                role = persisted.role,
                expiresAtEpochMs = persisted.expiresAtEpochMs,
            )
            val state = transportStack.authHttp.readSession(
                serverTarget = session.serverTarget,
                bearerSessionToken = session.sessionToken,
            )
            require(state.authenticated) { "The saved Mac session is no longer authenticated." }
            finishAuthenticatedConnection(
                session = session.copy(
                    role = state.role ?: session.role,
                    expiresAtEpochMs = state.expiresAtEpochMs ?: session.expiresAtEpochMs,
                ),
                environmentId = persisted.environmentId,
                hostLabel = persisted.hostLabel,
                hostFingerprint = persisted.hostFingerprint,
            )
            true
        }.getOrElse { error ->
            handleReconnectFailure(error)
            false
        }
    }

    override suspend fun forgetTrustedHost() {
        disconnect(clearSavedPairing = true)
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) {
        currentSocket?.close()
        currentSocket = null
        currentSession = null
        currentSnapshot = null
        knownApprovalRequestId = null
        knownToolInputsByThread = emptyMap()
        knownRunSnapshotsByThread = emptyMap()
        if (clearSavedPairing) {
            transportStack.sessionStore.clearBearerSession()
            transportStack.sessionStore.clearSnapshotSequence()
            persistence.savePreferredBackendKind(null)
        }
        updatesFlow.emit(
            ClientUpdate.Connection(
                status = ConnectionStatus.DISCONNECTED,
                detail = "Disconnected from the Mac server.",
                fingerprint = currentHostFingerprint,
                runtimeMetadata = runtimeMetadata(subscriptionState = "disconnected"),
            )
        )
        updatesFlow.emit(
            ClientUpdate.PairingAvailability(
                hasSavedPairing = hasSavedPairing(),
                fingerprint = currentHostFingerprint,
            )
        )
    }

    override suspend fun listThreads(limit: Int): List<ThreadSummary> {
        val snapshot = ensureSnapshot(forceRefresh = true)
        return mapMacNativeSnapshotToThreadSummaries(snapshot).take(limit)
    }

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary {
        val snapshot = ensureSnapshot(forceRefresh = true)
        val project = resolveProject(snapshot, preferredProjectPath)
        val threadId = UUID.randomUUID().toString()
        dispatchCommand(
            JSONObject()
                .put("type", "thread.create")
                .put("commandId", UUID.randomUUID().toString())
                .put("threadId", threadId)
                .put("projectId", project.getString("id"))
                .put("title", "New conversation")
                .put("modelSelection", resolveModelSelection(project))
                .put("runtimeMode", runtimeModeWireValue(selectedAccessMode))
                .put("interactionMode", "default")
                .put("branch", JSONObject.NULL)
                .put("worktreePath", JSONObject.NULL)
                .put("createdAt", isoNow()),
        )
        val refreshed = ensureSnapshot(forceRefresh = true)
        return mapMacNativeSnapshotToThreadLoad(refreshed, threadId).thread
            ?: throw IllegalStateException("The Mac server did not return the created thread.")
    }

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        observedThreadIds += threadId
        val snapshot = ensureSnapshot(forceRefresh = true)
        return mapMacNativeSnapshotToThreadLoad(snapshot, threadId)
    }

    override suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot {
        val snapshot = ensureSnapshot(forceRefresh = true)
        return mapMacNativeSnapshotToThreadLoad(snapshot, threadId).runSnapshot
    }

    override suspend fun fuzzyFileSearch(
        query: String,
        roots: List<String>,
        cancellationToken: String?,
    ): List<FuzzyFileMatch> = emptyList()

    override suspend fun listSkills(cwds: List<String>?): List<SkillMetadata> = emptyList()

    override suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        observedThreadIds += threadId
        dispatchCommand(
            JSONObject()
                .put("type", "thread.turn.start")
                .put("commandId", UUID.randomUUID().toString())
                .put("threadId", threadId)
                .put(
                    "message",
                    JSONObject()
                        .put("messageId", UUID.randomUUID().toString())
                        .put("role", "user")
                        .put("text", buildUserTurnText(userInput, fileMentions, skillMentions))
                        .put("attachments", encodeMacNativeAttachments(attachments)),
                )
                .put("runtimeMode", runtimeModeWireValue(selectedAccessMode))
                .put("interactionMode", if (collaborationMode == CollaborationModeKind.PLAN) "plan" else "default")
                .put("createdAt", isoNow()),
        )
        updatesFlow.emit(ClientUpdate.TurnStarted(threadId = threadId, turnId = null))
        refreshSnapshotAndEmitUpdates(forceRefresh = true)
    }

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) {
        startTurn(
            threadId = threadId,
            userInput = reviewRequestText(target, baseBranch),
            attachments = emptyList(),
            fileMentions = emptyList(),
            skillMentions = emptyList(),
            collaborationMode = null,
        )
    }

    override suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        startTurn(
            threadId = threadId,
            userInput = userInput,
            attachments = attachments,
            fileMentions = fileMentions,
            skillMentions = skillMentions,
            collaborationMode = collaborationMode,
        )
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        dispatchCommand(
            JSONObject()
                .put("type", "thread.turn.interrupt")
                .put("commandId", UUID.randomUUID().toString())
                .put("threadId", threadId)
                .put("turnId", turnId)
                .put("createdAt", isoNow()),
        )
        refreshSnapshotAndEmitUpdates(forceRefresh = true)
    }

    override suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) = Unit

    override suspend fun loadRuntimeConfig() {
        updatesFlow.emit(
            ClientUpdate.RuntimeConfigLoaded(
                models = resolveModelOptions(currentSnapshot),
                selectedModelId = selectedModelId,
                selectedReasoningEffort = selectedReasoningEffort,
                selectedAccessMode = selectedAccessMode,
                selectedServiceTier = selectedServiceTier,
                supportsServiceTier = false,
                supportsThreadCompaction = false,
                supportsThreadRollback = true,
                supportsBackgroundTerminalCleanup = false,
                supportsThreadFork = false,
                collaborationModes = setOf(CollaborationModeKind.PLAN),
                threadRuntimeOverridesByThread = threadRuntimeOverridesByThread,
                runtimeMetadata = runtimeMetadata(subscriptionState = if (currentSocket == null) "disconnected" else "subscribed"),
            )
        )
    }

    override suspend fun setSelectedModelId(modelId: String?) {
        selectedModelId = modelId?.trim()?.takeIf { it.isNotEmpty() }
        persistence.saveSelectedModelId(selectedModelId)
        loadRuntimeConfig()
    }

    override suspend fun setSelectedReasoningEffort(effort: String?) {
        selectedReasoningEffort = effort?.trim()?.takeIf { it.isNotEmpty() }
        persistence.saveSelectedReasoningEffort(selectedReasoningEffort)
        loadRuntimeConfig()
    }

    override suspend fun setHostRuntimeTarget(targetKind: String) = Unit

    override suspend fun setSelectedAccessMode(accessMode: AccessMode) {
        selectedAccessMode = accessMode
        persistence.saveSelectedAccessMode(accessMode)
        loadRuntimeConfig()
    }

    override suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) {
        selectedServiceTier = serviceTier
        persistence.saveSelectedServiceTier(serviceTier)
        loadRuntimeConfig()
    }

    override suspend fun setThreadRuntimeOverride(
        threadId: String,
        runtimeOverride: ThreadRuntimeOverride?,
    ) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        threadRuntimeOverridesByThread = threadRuntimeOverridesByThread.toMutableMap().apply {
            val normalized = runtimeOverride?.normalized()
            if (normalized == null) {
                remove(normalizedThreadId)
            } else {
                put(normalizedThreadId, normalized)
            }
        }
        persistence.saveThreadRuntimeOverrides(
            scopeKey = currentThreadTimelineScopeKey(),
            value = threadRuntimeOverridesByThread,
        )
        loadRuntimeConfig()
    }

    override suspend fun compactThread(threadId: String) {
        throw UnsupportedOperationException("Thread compaction is not available on the Mac-native backend yet.")
    }

    override suspend fun rollbackThread(
        threadId: String,
        numTurns: Int,
    ): ThreadLoadResult {
        dispatchCommand(
            JSONObject()
                .put("type", "thread.checkpoint.revert")
                .put("commandId", UUID.randomUUID().toString())
                .put("threadId", threadId)
                .put("turnCount", numTurns.coerceAtLeast(1))
                .put("createdAt", isoNow()),
        )
        refreshSnapshotAndEmitUpdates(forceRefresh = true)
        return mapMacNativeSnapshotToThreadLoad(ensureSnapshot(forceRefresh = true), threadId)
    }

    override suspend fun cleanBackgroundTerminals(threadId: String) {
        throw UnsupportedOperationException("Background terminal cleanup is not available on the Mac-native backend yet.")
    }

    override suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String?,
        preferredModel: String?,
    ): ThreadSummary {
        throw UnsupportedOperationException("Thread fork is not available on the Mac-native backend yet.")
    }

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        dispatchCommand(
            JSONObject()
                .put("type", "thread.approval.respond")
                .put("commandId", UUID.randomUUID().toString())
                .put("threadId", request.threadId ?: error("Approval request is missing a thread id."))
                .put("requestId", request.idValue.toString())
                .put("decision", if (accept) "accept" else "decline")
                .put("createdAt", isoNow()),
        )
        refreshSnapshotAndEmitUpdates(forceRefresh = true)
    }

    override suspend fun respondToToolUserInput(
        request: ToolUserInputRequest,
        response: ToolUserInputResponse,
    ) {
        dispatchCommand(
            JSONObject()
                .put("type", "thread.user-input.respond")
                .put("commandId", UUID.randomUUID().toString())
                .put("threadId", request.threadId ?: error("Tool input request is missing a thread id."))
                .put("requestId", request.idValue.toString())
                .put("answers", response.toJson().optJSONObject("answers"))
                .put("createdAt", isoNow()),
        )
        refreshSnapshotAndEmitUpdates(forceRefresh = true)
    }

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) {
        throw UnsupportedOperationException("Rejecting user-input prompts is not available on the Mac-native backend.")
    }

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        val snapshot = ensureSnapshot(forceRefresh = false)
        val projects = snapshot.optJSONArray("projects") ?: JSONArray()
        val recent = buildList {
            for (index in 0 until projects.length()) {
                val project = projects.optJSONObject(index) ?: continue
                val path = project.optString("workspaceRoot").trim().ifEmpty { continue }
                add(
                    WorkspacePathSummary(
                        path = path,
                        name = project.optString("title").trim().ifEmpty { path.substringAfterLast('/') },
                        isActive = path == activeWorkspacePath,
                    )
                )
            }
        }
        val resolvedActive = activeWorkspacePath ?: recent.firstOrNull { it.isActive }?.path ?: recent.firstOrNull()?.path
        return WorkspaceRecentState(
            activeCwd = resolvedActive,
            recentWorkspaces = recent,
        )
    }

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult {
        val recent = listRecentWorkspaces()
        val entries = recent.recentWorkspaces.map {
            WorkspaceDirectoryEntry(
                path = it.path,
                name = it.name,
                isDirectory = true,
                isActive = it.isActive,
                source = "project",
            )
        }
        return WorkspaceBrowseResult(
            requestedPath = path,
            parentPath = null,
            entries = if (path == null) emptyList() else entries.filter { it.path == path },
            rootEntries = entries,
            activeCwd = recent.activeCwd,
            recentWorkspaces = recent.recentWorkspaces,
        )
    }

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus {
        activeWorkspacePath = cwd.trim().takeIf { it.isNotEmpty() }
        return WorkspaceActivationStatus(
            hostId = currentEnvironmentId,
            macDeviceId = currentTrustedPairSnapshot()?.deviceId,
            relayUrl = currentSession?.serverTarget?.httpBaseUrl,
            relayStatus = "connected",
            currentCwd = activeWorkspacePath,
            workspaceActive = activeWorkspacePath != null,
            hasTrustedPhone = hasSavedPairing(),
        )
    }

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult {
        throw UnsupportedOperationException("Git status is not available on the Mac-native backend.")
    }

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult {
        throw UnsupportedOperationException("Git diff is not available on the Mac-native backend.")
    }

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult {
        throw UnsupportedOperationException("Git commit is not available on the Mac-native backend.")
    }

    override suspend fun gitPush(workingDirectory: String): GitPushResult {
        throw UnsupportedOperationException("Git push is not available on the Mac-native backend.")
    }

    override suspend fun gitPull(workingDirectory: String): GitPullResult {
        throw UnsupportedOperationException("Git pull is not available on the Mac-native backend.")
    }

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        throw UnsupportedOperationException("Git branches are not available on the Mac-native backend.")
    }

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult {
        throw UnsupportedOperationException("Git checkout is not available on the Mac-native backend.")
    }

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult {
        throw UnsupportedOperationException("Git branch creation is not available on the Mac-native backend.")
    }

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        throw UnsupportedOperationException("Git worktrees are not available on the Mac-native backend.")
    }

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult {
        throw UnsupportedOperationException("Git worktree removal is not available on the Mac-native backend.")
    }

    private suspend fun connectWithMacNativePayload(payload: MacNativePairingPayload) {
        disconnect(clearSavedPairing = false)
        emitConnection(ConnectionStatus.CONNECTING, "Connecting to the Mac server…")
        val serverTarget = createMacNativeServerTarget(payload.httpBaseUrl)
        val environmentDescriptor = transportStack.authHttp.fetchEnvironmentDescriptor(serverTarget)
        emitConnection(ConnectionStatus.HANDSHAKING, "Bootstrapping Mac session…")
        val bearerSession = transportStack.authHttp.bootstrapBearerSession(serverTarget, payload.credential)
        val sessionState = transportStack.authHttp.readSession(serverTarget, bearerSession.sessionToken)
        require(sessionState.authenticated) { "The Mac pairing credential did not produce an authenticated session." }
        require((sessionState.role ?: bearerSession.role) == "owner") {
            "Only owner sessions can access Mac orchestration APIs from Android."
        }
        finishAuthenticatedConnection(
            session = bearerSession.copy(
                role = sessionState.role ?: bearerSession.role,
                expiresAtEpochMs = sessionState.expiresAtEpochMs ?: bearerSession.expiresAtEpochMs,
            ),
            environmentId = environmentDescriptor.optString("environmentId").trim().ifEmpty { null },
            hostLabel = payload.label ?: environmentDescriptor.optString("label").trim().ifEmpty { null },
            hostFingerprint = payload.fingerprint,
        )
    }

    private suspend fun finishAuthenticatedConnection(
        session: MacNativeBearerSession,
        environmentId: String?,
        hostLabel: String?,
        hostFingerprint: String?,
    ) {
        currentSession = session
        currentEnvironmentId = environmentId
        currentHostLabel = hostLabel
        currentHostFingerprint = hostFingerprint
        transportStack.sessionStore.saveBearerSession(
            MacNativePersistedSession(
                httpBaseUrl = session.serverTarget.httpBaseUrl,
                wsBaseUrl = session.serverTarget.wsBaseUrl,
                sessionToken = session.sessionToken,
                role = session.role,
                expiresAtEpochMs = session.expiresAtEpochMs,
                environmentId = environmentId,
                hostLabel = hostLabel,
                hostFingerprint = hostFingerprint,
            )
        )
        persistence.savePreferredBackendKind(BackendKind.MAC_NATIVE)
        updatesFlow.emit(
            ClientUpdate.PairingAvailability(
                hasSavedPairing = true,
                fingerprint = currentHostFingerprint,
            )
        )
        updatesFlow.emit(
            ClientUpdate.AccountStatusLoaded(
                HostAccountSnapshot(
                    status = HostAccountStatus.AUTHENTICATED,
                    authMethod = "bearer-session-token",
                    tokenReady = true,
                    expiresAtEpochMs = session.expiresAtEpochMs,
                    origin = HostAccountSnapshotOrigin.NATIVE_LIVE,
                )
            )
        )
        emitConnection(ConnectionStatus.CONNECTED, "Connected to the Mac server.", subscriptionState = "connecting")
        refreshSnapshotAndEmitUpdates(forceRefresh = true)
        establishLiveSubscription(session)
        loadRuntimeConfig()
    }

    private suspend fun ensureSnapshot(forceRefresh: Boolean): JSONObject {
        return snapshotMutex.withLock {
            val cached = currentSnapshot
            if (!forceRefresh && cached != null) {
                return cached
            }
            val session = currentSession ?: loadPersistedBearerSession()
            requireNotNull(session) { "No saved Mac-native session is available." }
            val snapshot = transportStack.orchestrationHttp.fetchSnapshot(session)
            currentSnapshot = snapshot
            val snapshotSequence = snapshot.optLong("snapshotSequence")
            if (snapshotSequence >= 0L) {
                transportStack.sessionStore.saveSnapshotSequence(snapshotSequence)
            }
            snapshot
        }
    }

    private suspend fun refreshSnapshotAndEmitUpdates(forceRefresh: Boolean) {
        val snapshot = ensureSnapshot(forceRefresh)
        val threads = mapMacNativeSnapshotToThreadSummaries(snapshot)
        updatesFlow.emit(ClientUpdate.ThreadsLoaded(threads))
        val nextRunSnapshots = threads.associate { thread ->
            val runSnapshot = mapMacNativeSnapshotToThreadLoad(snapshot, thread.id).runSnapshot
            thread.id to runSnapshot
        }
        nextRunSnapshots.forEach { (threadId, nextRunSnapshot) ->
            val previous = knownRunSnapshotsByThread[threadId]
            val nextInterruptibleTurnId = nextRunSnapshot.interruptibleTurnId
            if (nextInterruptibleTurnId != null && previous?.interruptibleTurnId != nextInterruptibleTurnId) {
                updatesFlow.emit(ClientUpdate.TurnStarted(threadId = threadId, turnId = nextInterruptibleTurnId))
            }
            val previousTurnId = previous?.interruptibleTurnId ?: previous?.latestTurnId
            if (previousTurnId != null && previous?.latestTurnTerminalState == null && nextRunSnapshot.latestTurnTerminalState != null) {
                updatesFlow.emit(
                    ClientUpdate.TurnCompleted(
                        threadId = threadId,
                        turnId = nextRunSnapshot.latestTurnId ?: previousTurnId,
                        terminalState = nextRunSnapshot.latestTurnTerminalState,
                    )
                )
            }
        }
        knownRunSnapshotsByThread = nextRunSnapshots
        observedThreadIds.forEach { threadId ->
            val loaded = mapMacNativeSnapshotToThreadLoad(snapshot, threadId)
            updatesFlow.emit(ClientUpdate.ThreadLoaded(thread = loaded.thread, messages = loaded.messages))
        }
        emitPendingStateUpdates(snapshot)
        emitConnection(ConnectionStatus.CONNECTED, "Connected to the Mac server.", subscriptionState = if (currentSocket == null) "disconnected" else "subscribed")
    }

    private suspend fun emitPendingStateUpdates(snapshot: JSONObject) {
        val pendingState = deriveMacNativePendingState(snapshot)
        val nextApprovalRequestId = pendingState.approvals.firstOrNull()?.idValue?.toString()
        if (nextApprovalRequestId != knownApprovalRequestId) {
            if (nextApprovalRequestId == null) {
                updatesFlow.emit(ClientUpdate.ApprovalCleared(knownApprovalRequestId))
            } else {
                updatesFlow.emit(ClientUpdate.ApprovalRequested(pendingState.approvals.first()))
            }
            knownApprovalRequestId = nextApprovalRequestId
        }

        val nextToolInputsByThread = pendingState.toolInputsByThread.mapValues { (_, requests) ->
            requests.mapTo(linkedSetOf()) { it.requestId }
        }
        val previousThreadIds = knownToolInputsByThread.keys + nextToolInputsByThread.keys
        previousThreadIds.forEach { threadId ->
            val previousIds = knownToolInputsByThread[threadId].orEmpty()
            val nextIds = nextToolInputsByThread[threadId].orEmpty()
            pendingState.toolInputsByThread[threadId].orEmpty().forEach { request ->
                if (request.requestId !in previousIds) {
                    updatesFlow.emit(ClientUpdate.ToolUserInputRequested(request))
                }
            }
            previousIds.filterNot { it in nextIds }.forEach { requestId ->
                updatesFlow.emit(
                    ClientUpdate.ToolUserInputCleared(
                        threadId = threadId,
                        requestId = requestId,
                    )
                )
            }
        }
        knownToolInputsByThread = nextToolInputsByThread
    }

    private fun establishLiveSubscription(session: MacNativeBearerSession) {
        scope.launch {
            runCatching {
                currentSocket?.close()
                val wsToken = transportStack.authHttp.issueWebSocketToken(session)
                currentSocket = transportStack.orchestrationWs.connect(
                    session = session,
                    token = wsToken,
                    eventListener = io.androdex.android.transport.macnative.MacNativeOrchestrationEventListener { event ->
                        scope.launch {
                            val latestSequence = transportStack.sessionStore.loadSnapshotSequence() ?: 0L
                            val eventSequence = event.optLong("sequence", latestSequence)
                            if (eventSequence > latestSequence + 1) {
                                val replayToken = transportStack.authHttp.issueWebSocketToken(session)
                                transportStack.orchestrationWs.replayEvents(
                                    session = session,
                                    token = replayToken,
                                    fromSequenceExclusive = latestSequence,
                                )
                            }
                            if (eventSequence > latestSequence) {
                                transportStack.sessionStore.saveSnapshotSequence(eventSequence)
                            }
                            refreshSnapshotAndEmitUpdates(forceRefresh = true)
                        }
                    },
                )
                emitConnection(ConnectionStatus.CONNECTED, "Connected to the Mac server.", subscriptionState = "subscribed")
            }.onFailure {
                emitConnection(
                    status = ConnectionStatus.CONNECTED,
                    detail = "Connected, but live updates are degraded.",
                    subscriptionState = "error",
                )
            }
        }
    }

    private suspend fun dispatchCommand(command: JSONObject) {
        commandMutex.withLock {
            val session = currentSession ?: loadPersistedBearerSession()
            requireNotNull(session) { "No Mac-native session is available." }
            transportStack.orchestrationHttp.dispatchCommand(session, command)
        }
    }

    private fun loadPersistedBearerSession(): MacNativeBearerSession? {
        val persisted = transportStack.sessionStore.loadBearerSession() ?: return null
        return MacNativeBearerSession(
            serverTarget = MacNativeServerTarget(
                httpBaseUrl = persisted.httpBaseUrl,
                wsBaseUrl = persisted.wsBaseUrl,
            ),
            sessionToken = persisted.sessionToken,
            role = persisted.role,
            expiresAtEpochMs = persisted.expiresAtEpochMs,
        )
    }

    private suspend fun handleReconnectFailure(error: Throwable) {
        when (error) {
            is MacNativeHttpException -> {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    transportStack.sessionStore.clearBearerSession()
                    transportStack.sessionStore.clearSnapshotSequence()
                    persistence.savePreferredBackendKind(null)
                    updatesFlow.emit(
                        ClientUpdate.PairingAvailability(
                            hasSavedPairing = false,
                            fingerprint = currentHostFingerprint,
                        )
                    )
                    emitConnection(
                        ConnectionStatus.RECONNECT_REQUIRED,
                        "The saved Mac session expired. Pair again with a fresh Mac QR code.",
                    )
                } else {
                    emitConnection(ConnectionStatus.RETRYING_SAVED_PAIRING, error.message)
                }
            }
            else -> emitConnection(ConnectionStatus.RETRYING_SAVED_PAIRING, error.message ?: "The paired Mac is unavailable.")
        }
    }

    private suspend fun emitConnection(
        status: ConnectionStatus,
        detail: String?,
        subscriptionState: String? = if (currentSocket == null) "disconnected" else "subscribed",
    ) {
        updatesFlow.emit(
            ClientUpdate.Connection(
                status = status,
                detail = detail,
                fingerprint = currentHostFingerprint,
                runtimeMetadata = runtimeMetadata(subscriptionState),
            )
        )
    }

    private fun runtimeMetadata(subscriptionState: String?): HostRuntimeMetadata {
        return HostRuntimeMetadata(
            runtimeTarget = "mac-native",
            runtimeTargetDisplayName = "Mac Native",
            backendProvider = "t3code",
            backendProviderDisplayName = "T3 Code",
            runtimeTargetOptions = listOf(
                HostRuntimeTargetOption(
                    value = "mac-native",
                    title = "Mac Native",
                    subtitle = "Direct Mac server orchestration",
                    selected = true,
                )
            ),
            runtimeAuthMode = "bearer-session-token",
            runtimeEndpointHost = currentSession?.serverTarget?.httpBaseUrl,
            runtimeSnapshotSequence = currentSnapshot?.optInt("snapshotSequence"),
            runtimeReplaySequence = transportStack.sessionStore.loadSnapshotSequence()?.toInt(),
            runtimeSubscriptionState = subscriptionState,
        )
    }

    private fun resolveProject(snapshot: JSONObject, preferredProjectPath: String?): JSONObject {
        val projects = snapshot.optJSONArray("projects") ?: JSONArray()
        val normalizedPreferredPath = preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() }
        for (index in 0 until projects.length()) {
            val project = projects.optJSONObject(index) ?: continue
            val workspaceRoot = project.optString("workspaceRoot").trim().ifEmpty { null }
            if (workspaceRoot != null && workspaceRoot == normalizedPreferredPath) {
                activeWorkspacePath = workspaceRoot
                return project
            }
        }
        val active = activeWorkspacePath?.let { path ->
            (0 until projects.length())
                .asSequence()
                .mapNotNull(projects::optJSONObject)
                .firstOrNull { it.optString("workspaceRoot").trim() == path }
        }
        if (active != null) {
            return active
        }
        return projects.optJSONObject(0)
            ?: throw IllegalStateException("No Mac projects are available. Create a project on the Mac first.")
    }

    private fun resolveModelSelection(project: JSONObject): JSONObject {
        val defaultSelection = project.optJSONObject("defaultModelSelection")
        if (defaultSelection != null) {
            return defaultSelection
        }
        return JSONObject()
            .put("provider", "codex")
            .put("model", selectedModelId ?: "gpt-5.4")
    }

    private fun resolveModelOptions(snapshot: JSONObject?): List<ModelOption> {
        val knownModels = linkedMapOf<String, ModelOption>()
        snapshot?.optJSONArray("threads")?.let { threads ->
            for (index in 0 until threads.length()) {
                val thread = threads.optJSONObject(index) ?: continue
                val modelSelection = thread.optJSONObject("modelSelection") ?: continue
                val modelId = modelSelection.optString("model").trim().ifEmpty { continue }
                if (modelId !in knownModels) {
                    knownModels[modelId] = ModelOption(
                        id = modelId,
                        model = modelId,
                        displayName = modelId,
                        description = "Observed from the connected Mac server",
                        isDefault = knownModels.isEmpty(),
                        supportedReasoningEfforts = listOf(
                            ReasoningEffortOption("minimal", "Fastest response"),
                            ReasoningEffortOption("medium", "Balanced reasoning"),
                            ReasoningEffortOption("high", "Deeper reasoning"),
                        ),
                        defaultReasoningEffort = selectedReasoningEffort ?: "medium",
                    )
                }
            }
        }
        return knownModels.values.toList()
    }

    private fun buildUserTurnText(
        userInput: String,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
    ): String {
        val trimmedInput = userInput.trim()
        val extras = buildList {
            fileMentions.forEach { mention ->
                val path = mention.path.trim().takeIf { it.isNotEmpty() } ?: return@forEach
                add("File: $path")
            }
            skillMentions.forEach { mention ->
                val label = mention.name?.trim()?.takeIf { it.isNotEmpty() } ?: mention.id.trim()
                if (label.isNotEmpty()) {
                    add("Skill: $label")
                }
            }
        }
        if (extras.isEmpty()) {
            return trimmedInput
        }
        return buildString {
            append(trimmedInput)
            if (trimmedInput.isNotEmpty()) {
                append("\n\n")
            }
            append(extras.joinToString(separator = "\n"))
        }
    }

    private fun encodeMacNativeAttachments(attachments: List<ImageAttachment>): JSONArray {
        val encoded = JSONArray()
        attachments.forEachIndexed { index, attachment ->
            val payloadDataUrl = attachment.payloadDataUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            val mimeType = payloadDataUrl.substringAfter("data:", "").substringBefore(";").trim().ifEmpty { "image/png" }
            val sizeBytes = decodeDataUrlImageData(payloadDataUrl)?.size ?: 0
            encoded.put(
                JSONObject()
                    .put("type", "image")
                    .put("name", "attachment-${index + 1}.${defaultImageExtensionForMimeType(mimeType)}")
                    .put("mimeType", mimeType)
                    .put("sizeBytes", sizeBytes)
                    .put("dataUrl", payloadDataUrl),
            )
        }
        return encoded
    }

    private fun currentDeviceId(persisted: MacNativePersistedSession): String {
        return persisted.environmentId
            ?: persisted.hostLabel
            ?: persisted.httpBaseUrl
    }
}

private fun runtimeModeWireValue(accessMode: AccessMode): String {
    return when (accessMode) {
        AccessMode.ON_REQUEST -> "approval-required"
        AccessMode.FULL_ACCESS -> "full-access"
    }
}

private fun defaultImageExtensionForMimeType(mimeType: String): String {
    return when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }
}

private fun isoNow(): String = java.time.Instant.now().toString()
