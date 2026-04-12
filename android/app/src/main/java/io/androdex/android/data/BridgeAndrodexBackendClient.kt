package io.androdex.android.data

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.CollaborationModeKind
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
import kotlinx.coroutines.flow.SharedFlow

internal class BridgeAndrodexBackendClient(
    private val delegate: AndrodexClient,
) : AndrodexBackendClient {
    override val updates: SharedFlow<ClientUpdate> = delegate.updates

    override fun hasSavedPairing(): Boolean = delegate.hasSavedPairing()

    override fun currentFingerprint(): String? = delegate.currentFingerprint()

    override fun currentTrustedPairSnapshot(): TrustedPairSnapshot? = delegate.currentTrustedPairSnapshot()

    override fun currentThreadTimelineScopeKey(): String? = delegate.currentThreadTimelineScopeKey()

    override fun currentLegacyThreadTimelineScopeKey(): String? = delegate.currentLegacyThreadTimelineScopeKey()

    override fun startupConnectionStatus(): io.androdex.android.model.ConnectionStatus? = delegate.startupConnectionStatus()

    override fun startupConnectionDetail(): String? = delegate.startupConnectionDetail()

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        delegate.connectWithPairingPayload(rawPayload)
    }

    override suspend fun connectWithRecoveryPayload(rawPayload: String) {
        delegate.connectWithRecoveryPayload(rawPayload)
    }

    override suspend fun reconnectSaved(): Boolean = delegate.reconnectSaved()

    override suspend fun forgetTrustedHost() {
        delegate.forgetTrustedHost()
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) {
        delegate.disconnect(clearSavedPairing)
    }

    override suspend fun listThreads(limit: Int): List<ThreadSummary> = delegate.listThreads(limit)

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary = delegate.startThread(preferredProjectPath)

    override suspend fun loadThread(threadId: String): ThreadLoadResult = delegate.loadThread(threadId)

    override suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot =
        delegate.readThreadRunSnapshot(threadId)

    override suspend fun fuzzyFileSearch(
        query: String,
        roots: List<String>,
        cancellationToken: String?,
    ): List<FuzzyFileMatch> = delegate.fuzzyFileSearch(query, roots, cancellationToken)

    override suspend fun listSkills(cwds: List<String>?): List<SkillMetadata> = delegate.listSkills(cwds)

    override suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        delegate.startTurn(threadId, userInput, attachments, fileMentions, skillMentions, collaborationMode)
    }

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) {
        delegate.startReview(threadId, target, baseBranch)
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
        delegate.steerTurn(threadId, expectedTurnId, userInput, attachments, fileMentions, skillMentions, collaborationMode)
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        delegate.interruptTurn(threadId, turnId)
    }

    override suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) {
        delegate.registerPushNotifications(deviceToken, alertsEnabled, authorizationStatus, appEnvironment)
    }

    override suspend fun loadRuntimeConfig() {
        delegate.loadRuntimeConfig()
    }

    override suspend fun setSelectedModelId(modelId: String?) {
        delegate.setSelectedModelId(modelId)
    }

    override suspend fun setSelectedReasoningEffort(effort: String?) {
        delegate.setSelectedReasoningEffort(effort)
    }

    override suspend fun setHostRuntimeTarget(targetKind: String) {
        delegate.setHostRuntimeTarget(targetKind)
    }

    override suspend fun setSelectedAccessMode(accessMode: AccessMode) {
        delegate.setSelectedAccessMode(accessMode)
    }

    override suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) {
        delegate.setSelectedServiceTier(serviceTier)
    }

    override suspend fun setThreadRuntimeOverride(
        threadId: String,
        runtimeOverride: ThreadRuntimeOverride?,
    ) {
        delegate.setThreadRuntimeOverride(threadId, runtimeOverride)
    }

    override suspend fun compactThread(threadId: String) {
        delegate.compactThread(threadId)
    }

    override suspend fun rollbackThread(
        threadId: String,
        numTurns: Int,
    ): ThreadLoadResult = delegate.rollbackThread(threadId, numTurns)

    override suspend fun cleanBackgroundTerminals(threadId: String) {
        delegate.cleanBackgroundTerminals(threadId)
    }

    override suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String?,
        preferredModel: String?,
    ): ThreadSummary = delegate.forkThread(threadId, preferredProjectPath, preferredModel)

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        delegate.respondToApproval(request, accept)
    }

    override suspend fun respondToToolUserInput(
        request: ToolUserInputRequest,
        response: ToolUserInputResponse,
    ) {
        delegate.respondToToolUserInput(request, response)
    }

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) {
        delegate.rejectToolUserInput(request, message)
    }

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState = delegate.listRecentWorkspaces()

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult =
        delegate.listWorkspaceDirectory(path)

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus = delegate.activateWorkspace(cwd)

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult = delegate.gitStatus(workingDirectory)

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult = delegate.gitDiff(workingDirectory)

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult =
        delegate.gitCommit(workingDirectory, message)

    override suspend fun gitPush(workingDirectory: String): GitPushResult = delegate.gitPush(workingDirectory)

    override suspend fun gitPull(workingDirectory: String): GitPullResult = delegate.gitPull(workingDirectory)

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult =
        delegate.gitBranchesWithStatus(workingDirectory)

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult =
        delegate.gitCheckout(workingDirectory, branch)

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult =
        delegate.gitCreateBranch(workingDirectory, name)

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult = delegate.gitCreateWorktree(workingDirectory, name, baseBranch, changeTransfer)

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult = delegate.gitRemoveWorktree(workingDirectory, branch)
}
