package io.androdex.android.data

import android.content.Context
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConversationMessage
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
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceRecentState
import kotlinx.coroutines.flow.SharedFlow

interface AndrodexRepositoryContract {
    val updates: SharedFlow<ClientUpdate>
    fun hasSavedPairing(): Boolean
    fun currentFingerprint(): String?
    fun currentTrustedPairSnapshot(): TrustedPairSnapshot?
    fun currentThreadTimelineScopeKey(): String? = null
    fun startupNotice(): String?
    fun startupConnectionStatus(): io.androdex.android.model.ConnectionStatus? = null
    fun startupConnectionDetail(): String? = null
    fun loadPersistedThreadTimelines(scopeKey: String?): Map<String, List<ConversationMessage>> = emptyMap()
    fun savePersistedThreadTimeline(
        scopeKey: String?,
        threadId: String,
        messages: List<ConversationMessage>,
    ) = Unit
    suspend fun connectWithPairingPayload(rawPayload: String)
    suspend fun connectWithRecoveryPayload(rawPayload: String)
    suspend fun reconnectSaved(): Boolean
    suspend fun forgetTrustedHost() = Unit
    suspend fun disconnect(clearSavedPairing: Boolean = false)
    suspend fun refreshThreads(): List<ThreadSummary>
    suspend fun startThread(preferredProjectPath: String? = null): ThreadSummary
    suspend fun loadThread(threadId: String): ThreadLoadResult
    suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot
    suspend fun fuzzyFileSearch(
        query: String,
        roots: List<String>,
        cancellationToken: String? = null,
    ): List<FuzzyFileMatch>
    suspend fun listSkills(cwds: List<String>?): List<SkillMetadata>
    suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment> = emptyList(),
        fileMentions: List<TurnFileMention> = emptyList(),
        skillMentions: List<TurnSkillMention> = emptyList(),
        collaborationMode: CollaborationModeKind? = null,
    )
    suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String? = null,
    )
    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        userInput: String,
        attachments: List<ImageAttachment> = emptyList(),
        fileMentions: List<TurnFileMention> = emptyList(),
        skillMentions: List<TurnSkillMention> = emptyList(),
        collaborationMode: CollaborationModeKind? = null,
    )
    suspend fun interruptTurn(threadId: String, turnId: String)
    suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    )
    suspend fun loadRuntimeConfig()
    suspend fun setSelectedModelId(modelId: String?)
    suspend fun setSelectedReasoningEffort(effort: String?)
    suspend fun setSelectedAccessMode(accessMode: AccessMode) = Unit
    suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) = Unit
    suspend fun setThreadRuntimeOverride(threadId: String, runtimeOverride: ThreadRuntimeOverride?) = Unit
    suspend fun compactThread(threadId: String) {
        throw UnsupportedOperationException("Thread compaction is not available in this repository implementation.")
    }
    suspend fun rollbackThread(
        threadId: String,
        numTurns: Int = 1,
    ): ThreadLoadResult {
        throw UnsupportedOperationException("Thread rollback is not available in this repository implementation.")
    }
    suspend fun cleanBackgroundTerminals(threadId: String) {
        throw UnsupportedOperationException("Background terminal cleanup is not available in this repository implementation.")
    }
    suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String? = null,
        preferredModel: String? = null,
    ): ThreadSummary {
        throw UnsupportedOperationException("Thread fork is not available in this repository implementation.")
    }
    suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean)
    suspend fun respondToToolUserInput(request: ToolUserInputRequest, response: ToolUserInputResponse)
    suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String)
    suspend fun listRecentWorkspaces(): WorkspaceRecentState
    suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult
    suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus
    suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult
    suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult
    suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult
    suspend fun gitPush(workingDirectory: String): GitPushResult
    suspend fun gitPull(workingDirectory: String): GitPullResult
    suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult
    suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult
    suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult
    suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult
    suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult
}

class AndrodexRepository(context: Context) : AndrodexRepositoryContract {
    private val persistence = AndrodexPersistence(context.applicationContext)
    private val client = AndrodexClient(persistence)

    override val updates: SharedFlow<ClientUpdate> = client.updates

    override fun hasSavedPairing(): Boolean = client.hasSavedPairing()

    override fun currentFingerprint(): String? = client.currentFingerprint()

    override fun currentTrustedPairSnapshot(): TrustedPairSnapshot? = client.currentTrustedPairSnapshot()

    override fun currentThreadTimelineScopeKey(): String? = client.currentThreadTimelineScopeKey()

    override fun startupNotice(): String? = persistence.takeStartupNotice()

    override fun startupConnectionStatus(): io.androdex.android.model.ConnectionStatus? = client.startupConnectionStatus()

    override fun startupConnectionDetail(): String? = client.startupConnectionDetail()

    override fun loadPersistedThreadTimelines(scopeKey: String?): Map<String, List<ConversationMessage>> {
        val persisted = persistence.loadPersistedThreadTimelines(scopeKey)
        if (persisted.isNotEmpty() || scopeKey == null) {
            return persisted
        }

        val legacyScopeKey = client.currentLegacyThreadTimelineScopeKey()
        if (legacyScopeKey.isNullOrEmpty() || legacyScopeKey == scopeKey) {
            return persisted
        }

        return persistence.loadPersistedThreadTimelines(legacyScopeKey)
    }

    override fun savePersistedThreadTimeline(
        scopeKey: String?,
        threadId: String,
        messages: List<ConversationMessage>,
    ) {
        persistence.savePersistedThreadTimeline(
            scopeKey = scopeKey,
            threadId = threadId,
            messages = messages,
        )
    }

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        client.connectWithPairingPayload(rawPayload)
    }

    override suspend fun connectWithRecoveryPayload(rawPayload: String) {
        client.connectWithRecoveryPayload(rawPayload)
    }

    override suspend fun reconnectSaved(): Boolean = client.reconnectSaved()

    override suspend fun forgetTrustedHost() {
        client.forgetTrustedHost()
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) {
        client.disconnect(clearSavedPairing)
    }

    override suspend fun refreshThreads(): List<ThreadSummary> = client.listThreads()

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary = client.startThread(preferredProjectPath)

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        return client.loadThread(threadId)
    }

    override suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot {
        return client.readThreadRunSnapshot(threadId)
    }

    override suspend fun fuzzyFileSearch(
        query: String,
        roots: List<String>,
        cancellationToken: String?,
    ): List<FuzzyFileMatch> {
        return client.fuzzyFileSearch(query, roots, cancellationToken)
    }

    override suspend fun listSkills(cwds: List<String>?): List<SkillMetadata> {
        return client.listSkills(cwds)
    }

    override suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        client.startTurn(threadId, userInput, attachments, fileMentions, skillMentions, collaborationMode)
    }

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) {
        client.startReview(threadId, target, baseBranch)
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
        client.steerTurn(threadId, expectedTurnId, userInput, attachments, fileMentions, skillMentions, collaborationMode)
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        client.interruptTurn(threadId, turnId)
    }

    override suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) {
        client.registerPushNotifications(
            deviceToken = deviceToken,
            alertsEnabled = alertsEnabled,
            authorizationStatus = authorizationStatus,
            appEnvironment = appEnvironment,
        )
    }

    override suspend fun loadRuntimeConfig() {
        client.loadRuntimeConfig()
    }

    override suspend fun setSelectedModelId(modelId: String?) {
        client.setSelectedModelId(modelId)
    }

    override suspend fun setSelectedReasoningEffort(effort: String?) {
        client.setSelectedReasoningEffort(effort)
    }

    override suspend fun setSelectedAccessMode(accessMode: AccessMode) {
        client.setSelectedAccessMode(accessMode)
    }

    override suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) {
        client.setSelectedServiceTier(serviceTier)
    }

    override suspend fun setThreadRuntimeOverride(
        threadId: String,
        runtimeOverride: ThreadRuntimeOverride?,
    ) {
        client.setThreadRuntimeOverride(threadId, runtimeOverride)
    }

    override suspend fun compactThread(threadId: String) {
        client.compactThread(threadId)
    }

    override suspend fun rollbackThread(
        threadId: String,
        numTurns: Int,
    ): ThreadLoadResult {
        return client.rollbackThread(threadId, numTurns)
    }

    override suspend fun cleanBackgroundTerminals(threadId: String) {
        client.cleanBackgroundTerminals(threadId)
    }

    override suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String?,
        preferredModel: String?,
    ): ThreadSummary {
        return client.forkThread(threadId, preferredProjectPath, preferredModel)
    }

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        client.respondToApproval(request, accept)
    }

    override suspend fun respondToToolUserInput(request: ToolUserInputRequest, response: ToolUserInputResponse) {
        client.respondToToolUserInput(request, response)
    }

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) {
        client.rejectToolUserInput(request, message)
    }

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState = client.listRecentWorkspaces()

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult = client.listWorkspaceDirectory(path)

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus = client.activateWorkspace(cwd)

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult = client.gitStatus(workingDirectory)

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult = client.gitDiff(workingDirectory)

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult {
        return client.gitCommit(workingDirectory, message)
    }

    override suspend fun gitPush(workingDirectory: String): GitPushResult = client.gitPush(workingDirectory)

    override suspend fun gitPull(workingDirectory: String): GitPullResult = client.gitPull(workingDirectory)

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        return client.gitBranchesWithStatus(workingDirectory)
    }

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult {
        return client.gitCheckout(workingDirectory, branch)
    }

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult {
        return client.gitCreateBranch(workingDirectory, name)
    }

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        return client.gitCreateWorktree(workingDirectory, name, baseBranch, changeTransfer)
    }

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult {
        return client.gitRemoveWorktree(workingDirectory, branch)
    }
}
