package io.androdex.android.data

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.BackendKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ConnectPayloadDescriptor
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
import io.androdex.android.model.ImageAttachment
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
import io.androdex.android.model.WorkspaceRecentState
import io.androdex.android.model.parseConnectPayloadDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class RoutingAndrodexBackendClient(
    private val persistence: AndrodexPersistence,
    private val bridgeClient: AndrodexBackendClient,
    private val macNativeClient: AndrodexBackendClient,
) : AndrodexBackendClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updatesFlow = MutableSharedFlow<ClientUpdate>(extraBufferCapacity = 128)
    private var activeBackendKind: BackendKind = resolveInitialBackendKind()

    override val updates: SharedFlow<ClientUpdate> = updatesFlow.asSharedFlow()

    init {
        scope.launch {
            bridgeClient.updates.collect { update ->
                if (activeBackendKind == BackendKind.BRIDGE) {
                    updatesFlow.emit(update)
                }
            }
        }
        scope.launch {
            macNativeClient.updates.collect { update ->
                if (activeBackendKind == BackendKind.MAC_NATIVE) {
                    updatesFlow.emit(update)
                }
            }
        }
    }

    override fun hasSavedPairing(): Boolean = bridgeClient.hasSavedPairing() || macNativeClient.hasSavedPairing()

    override fun currentFingerprint(): String? = activeClient().currentFingerprint()

    override fun currentTrustedPairSnapshot(): TrustedPairSnapshot? = activeClient().currentTrustedPairSnapshot()

    override fun currentThreadTimelineScopeKey(): String? = activeClient().currentThreadTimelineScopeKey()

    override fun currentLegacyThreadTimelineScopeKey(): String? = activeClient().currentLegacyThreadTimelineScopeKey()

    override fun startupConnectionStatus(): io.androdex.android.model.ConnectionStatus? = activeClient().startupConnectionStatus()

    override fun startupConnectionDetail(): String? = activeClient().startupConnectionDetail()

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        val backendKind = when (parseConnectPayloadDescriptor(rawPayload)) {
            is ConnectPayloadDescriptor.MacNative -> BackendKind.MAC_NATIVE
            is ConnectPayloadDescriptor.Bridge -> BackendKind.BRIDGE
            is ConnectPayloadDescriptor.Recovery -> BackendKind.BRIDGE
        }
        switchBackend(backendKind)
        activeClient().connectWithPairingPayload(rawPayload)
    }

    override suspend fun connectWithRecoveryPayload(rawPayload: String) {
        switchBackend(BackendKind.BRIDGE)
        activeClient().connectWithRecoveryPayload(rawPayload)
    }

    override suspend fun reconnectSaved(): Boolean {
        val preferred = resolvePreferredReconnectBackend()
        switchBackend(preferred)
        if (activeClient().reconnectSaved()) {
            return true
        }
        val fallback = if (preferred == BackendKind.MAC_NATIVE) BackendKind.BRIDGE else BackendKind.MAC_NATIVE
        if (fallbackClient(preferred).hasSavedPairing()) {
            switchBackend(fallback)
            return activeClient().reconnectSaved()
        }
        return false
    }

    override suspend fun forgetTrustedHost() {
        bridgeClient.forgetTrustedHost()
        macNativeClient.forgetTrustedHost()
        persistence.savePreferredBackendKind(null)
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) {
        activeClient().disconnect(clearSavedPairing)
        if (clearSavedPairing && !hasSavedPairing()) {
            persistence.savePreferredBackendKind(null)
        }
    }

    override suspend fun listThreads(limit: Int): List<ThreadSummary> = activeClient().listThreads(limit)

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary = activeClient().startThread(preferredProjectPath)

    override suspend fun loadThread(threadId: String): ThreadLoadResult = activeClient().loadThread(threadId)

    override suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot = activeClient().readThreadRunSnapshot(threadId)

    override suspend fun fuzzyFileSearch(
        query: String,
        roots: List<String>,
        cancellationToken: String?,
    ): List<FuzzyFileMatch> = activeClient().fuzzyFileSearch(query, roots, cancellationToken)

    override suspend fun listSkills(cwds: List<String>?): List<SkillMetadata> = activeClient().listSkills(cwds)

    override suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        activeClient().startTurn(threadId, userInput, attachments, fileMentions, skillMentions, collaborationMode)
    }

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) {
        activeClient().startReview(threadId, target, baseBranch)
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
        activeClient().steerTurn(threadId, expectedTurnId, userInput, attachments, fileMentions, skillMentions, collaborationMode)
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        activeClient().interruptTurn(threadId, turnId)
    }

    override suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) {
        activeClient().registerPushNotifications(deviceToken, alertsEnabled, authorizationStatus, appEnvironment)
    }

    override suspend fun loadRuntimeConfig() {
        activeClient().loadRuntimeConfig()
    }

    override suspend fun setSelectedModelId(modelId: String?) {
        activeClient().setSelectedModelId(modelId)
    }

    override suspend fun setSelectedReasoningEffort(effort: String?) {
        activeClient().setSelectedReasoningEffort(effort)
    }

    override suspend fun setHostRuntimeTarget(targetKind: String) {
        activeClient().setHostRuntimeTarget(targetKind)
    }

    override suspend fun setSelectedAccessMode(accessMode: AccessMode) {
        activeClient().setSelectedAccessMode(accessMode)
    }

    override suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) {
        activeClient().setSelectedServiceTier(serviceTier)
    }

    override suspend fun setThreadRuntimeOverride(threadId: String, runtimeOverride: ThreadRuntimeOverride?) {
        activeClient().setThreadRuntimeOverride(threadId, runtimeOverride)
    }

    override suspend fun compactThread(threadId: String) {
        activeClient().compactThread(threadId)
    }

    override suspend fun rollbackThread(threadId: String, numTurns: Int): ThreadLoadResult =
        activeClient().rollbackThread(threadId, numTurns)

    override suspend fun cleanBackgroundTerminals(threadId: String) {
        activeClient().cleanBackgroundTerminals(threadId)
    }

    override suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String?,
        preferredModel: String?,
    ): ThreadSummary = activeClient().forkThread(threadId, preferredProjectPath, preferredModel)

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        activeClient().respondToApproval(request, accept)
    }

    override suspend fun respondToToolUserInput(
        request: ToolUserInputRequest,
        response: ToolUserInputResponse,
    ) {
        activeClient().respondToToolUserInput(request, response)
    }

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) {
        activeClient().rejectToolUserInput(request, message)
    }

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState = activeClient().listRecentWorkspaces()

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult = activeClient().listWorkspaceDirectory(path)

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus = activeClient().activateWorkspace(cwd)

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult = activeClient().gitStatus(workingDirectory)

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult = activeClient().gitDiff(workingDirectory)

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult =
        activeClient().gitCommit(workingDirectory, message)

    override suspend fun gitPush(workingDirectory: String): GitPushResult = activeClient().gitPush(workingDirectory)

    override suspend fun gitPull(workingDirectory: String): GitPullResult = activeClient().gitPull(workingDirectory)

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult =
        activeClient().gitBranchesWithStatus(workingDirectory)

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult =
        activeClient().gitCheckout(workingDirectory, branch)

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult =
        activeClient().gitCreateBranch(workingDirectory, name)

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult = activeClient().gitCreateWorktree(workingDirectory, name, baseBranch, changeTransfer)

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult = activeClient().gitRemoveWorktree(workingDirectory, branch)

    private fun switchBackend(kind: BackendKind) {
        activeBackendKind = kind
        persistence.savePreferredBackendKind(kind)
    }

    private fun resolveInitialBackendKind(): BackendKind {
        val preferred = persistence.loadPreferredBackendKind()
        return when {
            preferred == BackendKind.MAC_NATIVE && macNativeClient.hasSavedPairing() -> BackendKind.MAC_NATIVE
            preferred == BackendKind.BRIDGE && bridgeClient.hasSavedPairing() -> BackendKind.BRIDGE
            macNativeClient.hasSavedPairing() -> BackendKind.MAC_NATIVE
            else -> BackendKind.BRIDGE
        }
    }

    private fun resolvePreferredReconnectBackend(): BackendKind {
        val preferred = persistence.loadPreferredBackendKind()
        return when {
            preferred == BackendKind.MAC_NATIVE && macNativeClient.hasSavedPairing() -> BackendKind.MAC_NATIVE
            preferred == BackendKind.BRIDGE && bridgeClient.hasSavedPairing() -> BackendKind.BRIDGE
            macNativeClient.hasSavedPairing() -> BackendKind.MAC_NATIVE
            else -> BackendKind.BRIDGE
        }
    }

    private fun fallbackClient(kind: BackendKind): AndrodexBackendClient {
        return if (kind == BackendKind.MAC_NATIVE) bridgeClient else macNativeClient
    }

    private fun activeClient(): AndrodexBackendClient {
        return if (activeBackendKind == BackendKind.MAC_NATIVE) {
            macNativeClient
        } else {
            bridgeClient
        }
    }
}
