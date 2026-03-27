package io.androdex.android

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationMessage
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
import io.androdex.android.model.QueuePauseState
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.WorkspaceRecentState
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
class MainViewModelWorkspaceTest {
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
    fun openThread_activatesDifferentWorkspaceBeforeLoadingThread() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true),
                    WorkspacePathSummary("D:\\Client\\SiteB", "SiteB", false),
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = "D:\\Client\\SiteB",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.loadRecentWorkspaces()
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("D:\\Client\\SiteB"), repository.activatedWorkspaces)
        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
    }

    @Test
    fun createThread_usesActiveWorkspace() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true))
            )
        }
        val viewModel = MainViewModel(repository)

        viewModel.loadRecentWorkspaces()
        dispatcher.scheduler.runCurrent()
        viewModel.createThread()
        dispatcher.scheduler.runCurrent()

        assertEquals("C:\\Projects\\AppA", repository.startedThreadCwds.single())
        assertTrue(viewModel.uiState.value.selectedThreadId == "thread-created")
    }

    @Test
    fun startupNotice_populatesInitialErrorMessage() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            startupNotice = "Pair again on this Android install."
        }

        val viewModel = MainViewModel(repository)

        assertEquals("Pair again on this Android install.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun savedReconnect_waitsUntilForeground() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            hasSavedPairing = true
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.RETRYING_SAVED_PAIRING, "Retrying"))
        dispatcher.scheduler.runCurrent()
        assertEquals(0, repository.reconnectSavedCalls)

        viewModel.onAppForegrounded()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, repository.reconnectSavedCalls)
    }

    @Test
    fun savedReconnect_cancelsPendingRetryWhenBackgrounded() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            hasSavedPairing = true
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.onAppForegrounded()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, repository.reconnectSavedCalls)

        repository.reconnectSavedCalls = 0
        repository.emit(ClientUpdate.Connection(ConnectionStatus.RETRYING_SAVED_PAIRING, "Retrying"))
        dispatcher.scheduler.runCurrent()

        viewModel.onAppBackgrounded()
        dispatcher.scheduler.advanceTimeBy(5_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, repository.reconnectSavedCalls)
    }

    @Test
    fun queuedFollowUps_flushInOrderWhenThreadBecomesIdle() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("First queued")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("Second queued")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.startedTurns.isEmpty())
        assertEquals(
            listOf("First queued", "Second queued"),
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.map { it.text },
        )

        repository.emit(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
            )
        )
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("thread-1:First queued"), repository.startedTurns)
        assertEquals(
            listOf("Second queued"),
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.map { it.text },
        )

        repository.emit(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = null,
                terminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
            )
        )
        dispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("thread-1:First queued", "thread-1:Second queued"),
            repository.startedTurns,
        )
        assertTrue(viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts.isNullOrEmpty())
    }

    @Test
    fun pausedQueue_staysQueuedUntilResumed() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("Wait for the next pass")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()
        viewModel.pauseSelectedThreadQueue()
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
            )
        )
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.startedTurns.isEmpty())
        assertEquals(
            QueuePauseState.PAUSED,
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.pauseState,
        )

        viewModel.resumeSelectedThreadQueue()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("thread-1:Wait for the next pass"), repository.startedTurns)
    }

    @Test
    fun planMode_queuesAndRestoresPerThreadMode() = runTest(dispatcher) {
        val repository = FakeRepository()
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
                supportsThreadFork = true,
                collaborationModes = setOf(CollaborationModeKind.PLAN),
                threadRuntimeOverridesByThread = emptyMap(),
            )
        )
        dispatcher.scheduler.runCurrent()
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerPlanMode(true)
        viewModel.updateComposerText("Plan the cleanup")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        val queuedDraft = viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.single()
        assertEquals(CollaborationModeKind.PLAN, queuedDraft?.collaborationMode)
        assertTrue(viewModel.uiState.value.isComposerPlanMode)

        viewModel.updateComposerPlanMode(false)
        viewModel.restoreQueuedDraftToComposer(requireNotNull(queuedDraft).id)

        assertEquals("Plan the cleanup", viewModel.uiState.value.composerText)
        assertTrue(viewModel.uiState.value.isComposerPlanMode)
    }

    @Test
    fun runtimeRefresh_clearsUnsupportedPlanSelectionsAndQueuedDraftModes() = runTest(dispatcher) {
        val repository = FakeRepository()
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
                supportsThreadFork = true,
                collaborationModes = setOf(CollaborationModeKind.PLAN),
                threadRuntimeOverridesByThread = emptyMap(),
            )
        )
        dispatcher.scheduler.runCurrent()
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerPlanMode(true)
        viewModel.updateComposerText("Plan the cleanup")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        val queuedDraftId = requireNotNull(
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.single()
        ).id
        assertTrue(viewModel.uiState.value.isComposerPlanMode)

        repository.emit(
            ClientUpdate.RuntimeConfigLoaded(
                models = emptyList(),
                selectedModelId = null,
                selectedReasoningEffort = null,
                selectedAccessMode = AccessMode.ON_REQUEST,
                selectedServiceTier = null,
                supportsServiceTier = true,
                supportsThreadFork = true,
                collaborationModes = emptySet(),
                threadRuntimeOverridesByThread = emptyMap(),
            )
        )
        dispatcher.scheduler.runCurrent()

        val normalizedDraft = viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.single()
        assertEquals(null, normalizedDraft?.collaborationMode)
        assertFalse(viewModel.uiState.value.isComposerPlanMode)

        viewModel.restoreQueuedDraftToComposer(queuedDraftId)

        assertEquals("Plan the cleanup", viewModel.uiState.value.composerText)
        assertFalse(viewModel.uiState.value.isComposerPlanMode)
    }

    @Test
    fun failedQueueFlush_pausesAndPreservesOrderingUntilResume() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            startTurnFailures += IllegalStateException("Host unavailable")
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, null, null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("First queued")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()
        viewModel.updateComposerText("Second queued")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
            )
        )
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.startedTurns.isEmpty())
        assertEquals(
            listOf("First queued", "Second queued"),
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.map { it.text },
        )
        assertEquals(
            QueuePauseState.PAUSED,
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.pauseState,
        )

        viewModel.resumeSelectedThreadQueue()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("thread-1:First queued"), repository.startedTurns)

        repository.emit(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = null,
                terminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
            )
        )
        dispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("thread-1:First queued", "thread-1:Second queued"),
            repository.startedTurns,
        )
        assertTrue(viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts.isNullOrEmpty())
    }
}

private class FakeRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    var hasSavedPairing = false
    var recentState = WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    var startupNotice: String? = null
    var reconnectSavedCalls = 0
    val activatedWorkspaces = mutableListOf<String>()
    val loadedThreadIds = mutableListOf<String>()
    val startedThreadCwds = mutableListOf<String?>()
    val startedTurns = mutableListOf<String>()
    val startedTurnModes = mutableListOf<CollaborationModeKind?>()
    val startTurnFailures = mutableListOf<Throwable>()

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    suspend fun emit(update: ClientUpdate) {
        updatesFlow.emit(update)
    }

    override fun hasSavedPairing(): Boolean = hasSavedPairing

    override fun currentFingerprint(): String? = null

    override fun currentTrustedPairSnapshot() = null

    override fun startupNotice(): String? = startupNotice

    override suspend fun connectWithPairingPayload(rawPayload: String) = Unit

    override suspend fun reconnectSaved() {
        reconnectSavedCalls += 1
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) = Unit

    override suspend fun refreshThreads(): List<ThreadSummary> = emptyList()

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary {
        startedThreadCwds += preferredProjectPath
        return ThreadSummary("thread-created", "Conversation", null, preferredProjectPath, null, null)
    }

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        loadedThreadIds += threadId
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
    ) {
        val failure = startTurnFailures.firstOrNull()
        if (failure != null) {
            startTurnFailures.removeAt(0)
            throw failure
        }
        startedTurns += "$threadId:$userInput"
        startedTurnModes += collaborationMode
    }

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

    override suspend fun respondToToolUserInput(request: ToolUserInputRequest, response: ToolUserInputResponse) = Unit

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState = recentState

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult {
        return WorkspaceBrowseResult(
            requestedPath = path,
            parentPath = null,
            entries = emptyList(),
            rootEntries = emptyList(),
            activeCwd = recentState.activeCwd,
            recentWorkspaces = recentState.recentWorkspaces,
        )
    }

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus {
        activatedWorkspaces += cwd
        recentState = recentState.copy(activeCwd = cwd)
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
            state = "up_to_date",
            canPush = false,
            isPublishedToRemote = true,
            files = emptyList(),
            repoDiffTotals = null,
        )
    }

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult = GitRepoDiffResult("")

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult {
        return GitCommitResult("abc1234", "main", message)
    }

    override suspend fun gitPush(workingDirectory: String): GitPushResult {
        return GitPushResult("main", "origin", gitStatus(workingDirectory))
    }

    override suspend fun gitPull(workingDirectory: String): GitPullResult {
        return GitPullResult(success = true, status = gitStatus(workingDirectory))
    }

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        return GitBranchesWithStatusResult(
            branches = listOf("main"),
            branchesCheckedOutElsewhere = emptySet(),
            worktreePathByBranch = emptyMap(),
            localCheckoutPath = workingDirectory,
            currentBranch = "main",
            defaultBranch = "main",
            status = gitStatus(workingDirectory),
        )
    }

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult {
        return GitCheckoutResult(branch, "origin/$branch", gitStatus(workingDirectory))
    }

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult {
        return GitCreateBranchResult("remodex/$name", gitStatus(workingDirectory))
    }

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        return GitCreateWorktreeResult(
            branch = "remodex/$name",
            worktreePath = "$workingDirectory\\worktree",
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
