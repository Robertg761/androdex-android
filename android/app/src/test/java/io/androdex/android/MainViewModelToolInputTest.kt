package io.androdex.android

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
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
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceRecentState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelToolInputTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun submitToolInput_preventsDuplicateSubmissionAndClearsAfterAcknowledgment() = runTest(dispatcher) {
        val repository = ToolInputRepository().apply {
            toolInputResponseGate = CompletableDeferred()
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.RuntimeConfigLoaded(
                models = emptyList(),
                selectedModelId = null,
                selectedReasoningEffort = null,
                selectedAccessMode = AccessMode.ON_REQUEST,
                selectedServiceTier = null,
                supportsServiceTier = true,
                supportsThreadCompaction = true,
                supportsThreadRollback = true,
                supportsBackgroundTerminalCleanup = true,
                supportsThreadFork = true,
                collaborationModes = setOf(CollaborationModeKind.PLAN),
                threadRuntimeOverridesByThread = emptyMap(),
            )
        )
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.ToolUserInputRequested(
                toolInputRequest(),
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.updateToolInputAnswer("request-1", "branch", "main")
        viewModel.submitToolInput("request-1")
        dispatcher.scheduler.runCurrent()
        viewModel.submitToolInput("request-1")
        dispatcher.scheduler.runCurrent()

        assertEquals(1, repository.respondToToolUserInputCalls)
        assertTrue(viewModel.uiState.value.submittingToolInputRequestIds.contains("request-1"))
        assertEquals(1, viewModel.uiState.value.pendingToolInputsByThread["thread-1"]?.size)

        repository.toolInputResponseGate?.complete(Unit)
        dispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.submittingToolInputRequestIds.contains("request-1"))
        assertTrue(viewModel.uiState.value.pendingToolInputsByThread["thread-1"].isNullOrEmpty())
        assertTrue(viewModel.uiState.value.toolInputAnswersByRequest["request-1"].isNullOrEmpty())
    }

    @Test
    fun submitToolInput_failureKeepsPromptAndDraftAnswer() = runTest(dispatcher) {
        val repository = ToolInputRepository().apply {
            toolInputFailure = IllegalStateException("Host unavailable")
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.ToolUserInputRequested(toolInputRequest()))
        dispatcher.scheduler.runCurrent()

        viewModel.updateToolInputAnswer("request-1", "branch", "main")
        viewModel.submitToolInput("request-1")
        dispatcher.scheduler.runCurrent()

        assertEquals(1, repository.respondToToolUserInputCalls)
        assertEquals("Host unavailable", viewModel.uiState.value.errorMessage)
        assertEquals("main", viewModel.uiState.value.toolInputAnswersByRequest["request-1"]?.get("branch"))
        assertEquals(1, viewModel.uiState.value.pendingToolInputsByThread["thread-1"]?.size)
    }
}

private fun toolInputRequest(): ToolUserInputRequest {
    return ToolUserInputRequest(
        idValue = "request-1",
        method = "item/tool/requestUserInput",
        threadId = "thread-1",
        turnId = "turn-1",
        itemId = "item-1",
        title = "Pick a branch",
        message = "Select which branch to inspect.",
        questions = listOf(
            ToolUserInputQuestion(
                id = "branch",
                header = "Branch",
                question = "Which branch should we inspect?",
                options = emptyList(),
            )
        ),
        rawPayload = "{}",
    )
}

private class ToolInputRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    var toolInputFailure: Throwable? = null
    var toolInputResponseGate: CompletableDeferred<Unit>? = null
    var respondToToolUserInputCalls = 0

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    suspend fun emit(update: ClientUpdate) {
        updatesFlow.emit(update)
    }

    override fun hasSavedPairing(): Boolean = false

    override fun currentFingerprint(): String? = null

    override fun currentTrustedPairSnapshot() = null

    override fun startupNotice(): String? = null

    override suspend fun connectWithPairingPayload(rawPayload: String) = Unit

    override suspend fun reconnectSaved() = Unit

    override suspend fun disconnect(clearSavedPairing: Boolean) = Unit

    override suspend fun refreshThreads(): List<ThreadSummary> = emptyList()

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary {
        return ThreadSummary("thread-created", "Conversation", null, preferredProjectPath, null, null)
    }

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        return ThreadLoadResult(
            thread = null,
            messages = emptyList(),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = null,
                latestTurnTerminalState = null,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )
    }

    override suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot {
        return ThreadRunSnapshot(
            interruptibleTurnId = null,
            hasInterruptibleTurnWithoutId = false,
            latestTurnId = null,
            latestTurnTerminalState = null,
            shouldAssumeRunningFromLatestTurn = false,
        )
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
    ) = Unit

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) = Unit

    override suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        fileMentions: List<TurnFileMention>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) = Unit

    override suspend fun interruptTurn(threadId: String, turnId: String) = Unit

    override suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) = Unit

    override suspend fun loadRuntimeConfig() = Unit

    override suspend fun setSelectedModelId(modelId: String?) = Unit

    override suspend fun setSelectedReasoningEffort(effort: String?) = Unit

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) = Unit

    override suspend fun respondToToolUserInput(request: ToolUserInputRequest, response: ToolUserInputResponse) {
        respondToToolUserInputCalls += 1
        toolInputFailure?.let { throw it }
        toolInputResponseGate?.await()
    }

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) = Unit

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        return WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    }

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult {
        return WorkspaceBrowseResult(
            requestedPath = path,
            parentPath = null,
            entries = emptyList(),
            rootEntries = emptyList(),
            activeCwd = null,
            recentWorkspaces = emptyList(),
        )
    }

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus {
        return WorkspaceActivationStatus(
            hostId = null,
            macDeviceId = null,
            relayUrl = null,
            relayStatus = null,
            currentCwd = cwd,
            workspaceActive = true,
            hasTrustedPhone = true,
        )
    }

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult {
        return GitRepoSyncResult(
            repoRoot = workingDirectory,
            currentBranch = "main",
            trackingBranch = "origin/main",
            isDirty = false,
            aheadCount = 0,
            behindCount = 0,
            localOnlyCommitCount = 0,
            state = "clean",
            canPush = false,
            isPublishedToRemote = true,
            files = emptyList(),
            repoDiffTotals = null,
        )
    }

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult = GitRepoDiffResult("")

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult {
        return GitCommitResult("sha", "main", message)
    }

    override suspend fun gitPush(workingDirectory: String): GitPushResult {
        return GitPushResult("main", "origin", null)
    }

    override suspend fun gitPull(workingDirectory: String): GitPullResult {
        return GitPullResult(success = true, status = null)
    }

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        return GitBranchesWithStatusResult(
            branches = listOf("main"),
            branchesCheckedOutElsewhere = emptySet(),
            worktreePathByBranch = emptyMap(),
            localCheckoutPath = workingDirectory,
            currentBranch = "main",
            defaultBranch = "main",
            status = null,
        )
    }

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult {
        return GitCheckoutResult(branch, "origin/$branch", null)
    }

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult {
        return GitCreateBranchResult(name, null)
    }

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        return GitCreateWorktreeResult(
            branch = name,
            worktreePath = "$workingDirectory\\..\\$name",
            alreadyExisted = false,
        )
    }

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult {
        return GitRemoveWorktreeResult(success = true)
    }
}
