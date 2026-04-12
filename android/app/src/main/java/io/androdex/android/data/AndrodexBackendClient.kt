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

internal interface AndrodexBackendClient {
    val updates: SharedFlow<ClientUpdate>

    fun hasSavedPairing(): Boolean

    fun currentFingerprint(): String?

    fun currentTrustedPairSnapshot(): TrustedPairSnapshot?

    fun currentThreadTimelineScopeKey(): String?

    fun currentLegacyThreadTimelineScopeKey(): String?

    fun startupConnectionStatus(): io.androdex.android.model.ConnectionStatus?

    fun startupConnectionDetail(): String?

    suspend fun connectWithPairingPayload(rawPayload: String)

    suspend fun connectWithRecoveryPayload(rawPayload: String)

    suspend fun reconnectSaved(): Boolean

    suspend fun forgetTrustedHost()

    suspend fun disconnect(clearSavedPairing: Boolean = false)

    suspend fun listThreads(limit: Int = 40): List<ThreadSummary>

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

    suspend fun setHostRuntimeTarget(targetKind: String)

    suspend fun setSelectedAccessMode(accessMode: AccessMode)

    suspend fun setSelectedServiceTier(serviceTier: ServiceTier?)

    suspend fun setThreadRuntimeOverride(
        threadId: String,
        runtimeOverride: ThreadRuntimeOverride?,
    )

    suspend fun compactThread(threadId: String)

    suspend fun rollbackThread(
        threadId: String,
        numTurns: Int = 1,
    ): ThreadLoadResult

    suspend fun cleanBackgroundTerminals(threadId: String)

    suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String? = null,
        preferredModel: String? = null,
    ): ThreadSummary

    suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean)

    suspend fun respondToToolUserInput(
        request: ToolUserInputRequest,
        response: ToolUserInputResponse,
    )

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
