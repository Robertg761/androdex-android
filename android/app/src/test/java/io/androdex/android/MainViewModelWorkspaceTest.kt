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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun openThread_keepsUiResponsiveWhileGitRefreshContinuesInBackground() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(
            ClientUpdate.Connection(ConnectionStatus.CONNECTED)
        )
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = "C:\\Projects\\AppA",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        assertEquals("thread-1", viewModel.uiState.value.selectedThreadId)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("C:\\Projects\\AppA"), repository.gitBranchesWithStatusRequests)
        assertTrue(viewModel.uiState.value.gitStateByThread["thread-1"]?.isRefreshing == true)

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.gitStateByThread["thread-1"]?.isRefreshing == true)
    }

    @Test
    fun openThread_refreshesCachedGitStateOnReopen() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true),
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = "C:\\Projects\\AppA",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        advanceUntilIdle()
        assertEquals(listOf("C:\\Projects\\AppA"), repository.gitBranchesWithStatusRequests)
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitCurrentBranch = "release"
        repository.gitDefaultBranch = "release"
        repository.gitBranchesWithStatusGate = CompletableDeferred()
        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")

        dispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(
            listOf("C:\\Projects\\AppA", "C:\\Projects\\AppA"),
            repository.gitBranchesWithStatusRequests,
        )
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)
    }

    @Test
    fun requestGitPull_waitsForFreshBranchStatusWhenReopenUsesCachedState() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        advanceUntilIdle()
        assertEquals("up_to_date", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.state)

        repository.gitStatusState = "diverged"
        repository.gitBranchesWithStatusGate = CompletableDeferred()
        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals(null, viewModel.uiState.value.gitAlert)

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals("Branch diverged from remote", viewModel.uiState.value.gitAlert?.title)
    }

    @Test
    fun refreshSelectedThreadGitState_ignoresManualRefreshWhileOlderLoadIsInFlight() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        val olderRefreshGate = CompletableDeferred<Unit>()
        repository.enqueueGitBranchesWithStatus(
            workspace,
            QueuedGitBranchesWithStatusResponse(
                gate = olderRefreshGate,
                currentBranch = "main",
                defaultBranch = "main",
            ),
        )

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        viewModel.refreshSelectedThreadGitState()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            listOf(workspace, workspace),
            repository.gitBranchesWithStatusRequests,
        )

        olderRefreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)
    }

    @Test
    fun openThread_doesNotStartDuplicateGitRefreshWhileLoadInFlight() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = "C:\\Projects\\AppA",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("C:\\Projects\\AppA"), repository.gitBranchesWithStatusRequests)

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)
    }

    @Test
    fun requestGitPull_waitsForBranchStatusBeforeShowingDivergedWarning() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
            gitStatusState = "diverged"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = "C:\\Projects\\AppA",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals(null, viewModel.uiState.value.gitAlert)

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals("Branch diverged from remote", viewModel.uiState.value.gitAlert?.title)
    }

    @Test
    fun requestGitPull_doesNotExposeStaleStatusAfterWorkspaceSwitch() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitPullGateByWorkingDirectory[workspaceA] = CompletableDeferred()
        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()

        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspaceA), repository.gitPullRequests)

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        repository.gitPullGateByWorkingDirectory.getValue(workspaceA).complete(Unit)
        dispatcher.scheduler.runCurrent()

        assertEquals(null, viewModel.uiState.value.gitStateByThread["thread-1"]?.status)
        assertTrue(viewModel.uiState.value.gitStateByThread["thread-1"]?.isLoadingBranchTargets == true)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceB).complete(Unit)
        advanceUntilIdle()

        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
    }

    @Test
    fun requestGitPull_abortsIfThreadStartsRunningWhileBranchContextLoads() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals(null, viewModel.uiState.value.gitAlert)
    }

    @Test
    fun requestGitPull_abortsIfThreadIsClosedWhileBranchContextLoads() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals(null, viewModel.uiState.value.gitAlert)
    }

    @Test
    fun requestGitPull_retriesFailedBackgroundRefreshAndShowsError() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
            gitBranchesWithStatusError = IllegalStateException("Git status unavailable")
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(workspace, workspace), repository.gitBranchesWithStatusRequests)
        assertTrue(repository.gitPullRequests.isEmpty())
        assertEquals("Git status unavailable", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun requestGitPull_usesLoadedContextWhenBridgeReturnsCanonicalCheckoutPath() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val canonicalWorkspace = "\\\\?\\C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
            gitLocalCheckoutPathByWorkingDirectory[workspace] = canonicalWorkspace
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        advanceUntilIdle()

        viewModel.requestGitPull()
        advanceUntilIdle()

        assertEquals(listOf(workspace), repository.gitPullRequests)
    }

    @Test
    fun openThread_reloadsGitStateAfterSkippedPullFailure() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitPullGateByWorkingDirectory[workspace] = CompletableDeferred()
        repository.gitPullError = IllegalStateException("Pull failed")

        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspace), repository.gitPullRequests)

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf(workspace), repository.gitBranchesWithStatusRequests)

        repository.gitCurrentBranch = "release"
        repository.gitDefaultBranch = "release"
        repository.gitPullGateByWorkingDirectory.getValue(workspace).complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(workspace, workspace), repository.gitBranchesWithStatusRequests)
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)
        assertEquals("Pull failed", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun pushGitChanges_doesNotExposeStaleStatusAfterWorkspaceSwitch() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitPushGateByWorkingDirectory[workspaceA] = CompletableDeferred()
        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()

        viewModel.pushGitChanges()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspaceA), repository.gitPushRequests)

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        repository.gitPushGateByWorkingDirectory.getValue(workspaceA).complete(Unit)
        dispatcher.scheduler.runCurrent()

        assertEquals(null, viewModel.uiState.value.gitStateByThread["thread-1"]?.status)
        assertTrue(viewModel.uiState.value.gitStateByThread["thread-1"]?.isLoadingBranchTargets == true)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceB).complete(Unit)
        advanceUntilIdle()

        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
    }

    @Test
    fun openThread_reloadsFallbackWorkspaceWhenWorkspaceChangesMidRefresh() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitBranchesWithStatusGateByWorkingDirectory[workspaceA] = CompletableDeferred()
            gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        assertEquals(listOf(workspaceA), repository.gitBranchesWithStatusRequests)

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspaceA, workspaceB), repository.gitBranchesWithStatusRequests)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceA).complete(Unit)
        dispatcher.scheduler.runCurrent()

        assertEquals(null, viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertTrue(viewModel.uiState.value.gitStateByThread["thread-1"]?.isLoadingBranchTargets == true)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceB).complete(Unit)
        advanceUntilIdle()

        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)
    }

    @Test
    fun openThread_clearsStaleGitStateWhileLoadingDifferentFallbackWorkspace() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()
        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        assertEquals(null, viewModel.uiState.value.gitStateByThread["thread-1"]?.status)
        assertEquals(null, viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets)
        assertTrue(viewModel.uiState.value.gitStateByThread["thread-1"]?.isLoadingBranchTargets == true)
    }

    @Test
    fun openThread_doesNotExposeIdleStateWithStaleFallbackGitContext() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)

        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()
        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()

        val snapshots = mutableListOf<AndrodexUiState>()
        val collectJob = backgroundScope.launch {
            viewModel.uiState.collect { snapshots += it }
        }
        dispatcher.scheduler.runCurrent()
        snapshots.clear()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        assertFalse(
            snapshots.any { state ->
                state.activeWorkspacePath == workspaceB &&
                    state.selectedThreadId == "thread-1" &&
                    !state.isBusy &&
                    state.gitStateByThread["thread-1"]?.status?.currentBranch == "main"
            }
        )

        collectJob.cancel()
    }

    @Test
    fun openThread_refreshesNewFallbackWorkspaceWhilePreviousRepoActionFinishes() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        advanceUntilIdle()
        assertEquals(listOf(workspaceA), repository.gitBranchesWithStatusRequests)

        repository.gitPullGateByWorkingDirectory[workspaceA] = CompletableDeferred()
        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceA] = CompletableDeferred()
        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()

        viewModel.requestGitPull()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspaceA), repository.gitPullRequests)

        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspaceA, workspaceB), repository.gitBranchesWithStatusRequests)
        assertTrue(viewModel.uiState.value.gitStateByThread["thread-1"]?.isLoadingBranchTargets == true)

        repository.gitPullGateByWorkingDirectory.getValue(workspaceA).complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(workspaceA, workspaceB), repository.gitBranchesWithStatusRequests)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceB).complete(Unit)
        advanceUntilIdle()
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceA).complete(Unit)
        advanceUntilIdle()
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.status?.currentBranch)
        assertEquals("release", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)
    }

    @Test
    fun openGitWorktreeDialog_backfillsMatchingBaseBranchAfterAsyncRefresh() = runTest(dispatcher) {
        val workspaceA = "C:\\Projects\\AppA"
        val workspaceB = "D:\\Client\\SiteB"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspaceA,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspaceA, "AppA", true),
                    WorkspacePathSummary(workspaceB, "SiteB", false),
                )
            )
            gitCurrentBranchByWorkingDirectory[workspaceA] = "main"
            gitDefaultBranchByWorkingDirectory[workspaceA] = "main"
            gitCurrentBranchByWorkingDirectory[workspaceB] = "release"
            gitDefaultBranchByWorkingDirectory[workspaceB] = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = null,
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
        advanceUntilIdle()
        assertEquals("main", viewModel.uiState.value.gitStateByThread["thread-1"]?.branchTargets?.defaultBranch)

        repository.gitBranchesWithStatusGateByWorkingDirectory[workspaceB] = CompletableDeferred()
        viewModel.closeThread()
        dispatcher.scheduler.runCurrent()
        viewModel.activateWorkspace(workspaceB)
        advanceUntilIdle()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.openGitWorktreeDialog()
        dispatcher.scheduler.runCurrent()

        assertEquals("", viewModel.uiState.value.gitWorktreeDialog?.baseBranch)

        repository.gitBranchesWithStatusGateByWorkingDirectory.getValue(workspaceB).complete(Unit)
        advanceUntilIdle()

        assertEquals("release", viewModel.uiState.value.gitWorktreeDialog?.baseBranch)
    }

    @Test
    fun openGitWorktreeDialog_keepsTypedBaseBranchWhenAsyncRefreshCompletes() = runTest(dispatcher) {
        val workspace = "C:\\Projects\\AppA"
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = workspace,
                recentWorkspaces = listOf(
                    WorkspacePathSummary(workspace, "AppA", true),
                )
            )
            gitBranchesWithStatusGate = CompletableDeferred()
            gitCurrentBranch = "release"
            gitDefaultBranch = "release"
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Thread 1",
                        preview = null,
                        cwd = workspace,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.openGitWorktreeDialog()
        dispatcher.scheduler.runCurrent()
        viewModel.updateGitWorktreeBaseBranch("custom-base")
        dispatcher.scheduler.runCurrent()

        repository.gitBranchesWithStatusGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("custom-base", viewModel.uiState.value.gitWorktreeDialog?.baseBranch)
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
    fun createThread_keepsThreadOpenWhenGitSnapshotFails() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true))
            )
            gitBranchesWithStatusError = IllegalStateException("Timed out waiting for 20000 ms")
        }
        val viewModel = MainViewModel(repository)

        viewModel.loadRecentWorkspaces()
        dispatcher.scheduler.runCurrent()
        viewModel.createThread()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("C:\\Projects\\AppA", repository.startedThreadCwds.single())
        assertEquals("thread-created", viewModel.uiState.value.selectedThreadId)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertEquals(listOf("C:\\Projects\\AppA"), repository.gitBranchesWithStatusRequests)
    }

    @Test
    fun createThread_usesExplicitWorkspaceAndActivatesIt() = runTest(dispatcher) {
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

        viewModel.loadRecentWorkspaces()
        dispatcher.scheduler.runCurrent()
        viewModel.createThread("D:\\Client\\SiteB")
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("D:\\Client\\SiteB"), repository.activatedWorkspaces)
        assertEquals(listOf("D:\\Client\\SiteB"), repository.startedThreadCwds)
        assertEquals("thread-created", viewModel.uiState.value.selectedThreadId)
    }

    @Test
    fun connect_doesNotAutoOpenProjectPicker() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        dispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isProjectPickerOpen)
    }

    @Test
    fun openProjectPicker_loadsWorkspaceStateExplicitly() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.openProjectPicker()
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isProjectPickerOpen)
        assertEquals(1, repository.recentStateLoads)
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
    fun protectedFallbackSend_startsImmediatelyWhenSnapshotShowsNoActiveRun() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-old",
                latestTurnTerminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            )
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

        repository.emit(ClientUpdate.TurnStarted("thread-1", null))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("Send now")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("thread-1"), repository.readThreadRunSnapshotIds)
        assertEquals(listOf("thread-1:Send now"), repository.startedTurns)
        assertTrue(viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts.isNullOrEmpty())
    }

    @Test
    fun protectedFallbackSend_staysQueuedWhenSnapshotConfirmsActiveRun() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = true,
                latestTurnId = "turn-live",
                latestTurnTerminalState = null,
                shouldAssumeRunningFromLatestTurn = false,
            )
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

        repository.emit(ClientUpdate.TurnStarted("thread-1", null))
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("Queue me")
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("thread-1"), repository.readThreadRunSnapshotIds)
        assertTrue(repository.startedTurns.isEmpty())
        assertEquals(
            listOf("Queue me"),
            viewModel.uiState.value.queuedDraftStateByThread["thread-1"]?.drafts?.map { it.text },
        )
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
                supportsThreadCompaction = true,
                supportsThreadRollback = true,
                supportsBackgroundTerminalCleanup = true,
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
                supportsThreadCompaction = true,
                supportsThreadRollback = true,
                supportsBackgroundTerminalCleanup = true,
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
                supportsThreadCompaction = true,
                supportsThreadRollback = true,
                supportsBackgroundTerminalCleanup = true,
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

private data class QueuedGitBranchesWithStatusResponse(
    val gate: CompletableDeferred<Unit>? = null,
    val currentBranch: String? = null,
    val defaultBranch: String? = null,
    val statusState: String? = null,
    val error: Throwable? = null,
)

private class FakeRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    var hasSavedPairing = false
    var recentState = WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    var startupNotice: String? = null
    var reconnectSavedCalls = 0
    var recentStateLoads = 0
    val activatedWorkspaces = mutableListOf<String>()
    val loadedThreadIds = mutableListOf<String>()
    val readThreadRunSnapshotIds = mutableListOf<String>()
    val startedThreadCwds = mutableListOf<String?>()
    val gitBranchesWithStatusRequests = mutableListOf<String>()
    val gitPushRequests = mutableListOf<String>()
    val gitPullRequests = mutableListOf<String>()
    val startedTurns = mutableListOf<String>()
    val startedTurnModes = mutableListOf<CollaborationModeKind?>()
    val startTurnFailures = mutableListOf<Throwable>()
    var gitBranchesWithStatusGate: CompletableDeferred<Unit>? = null
    val gitBranchesWithStatusGateByWorkingDirectory = mutableMapOf<String, CompletableDeferred<Unit>>()
    var gitPushGate: CompletableDeferred<Unit>? = null
    val gitPushGateByWorkingDirectory = mutableMapOf<String, CompletableDeferred<Unit>>()
    var gitPullGate: CompletableDeferred<Unit>? = null
    val gitPullGateByWorkingDirectory = mutableMapOf<String, CompletableDeferred<Unit>>()
    var gitBranchesWithStatusError: Throwable? = null
    var gitPushError: Throwable? = null
    var gitPullError: Throwable? = null
    var gitCurrentBranch: String = "main"
    var gitDefaultBranch: String = "main"
    var gitLocalCheckoutPath: String? = null
    var gitStatusState: String = "up_to_date"
    val gitCurrentBranchByWorkingDirectory = mutableMapOf<String, String>()
    val gitDefaultBranchByWorkingDirectory = mutableMapOf<String, String>()
    val gitLocalCheckoutPathByWorkingDirectory = mutableMapOf<String, String>()
    val gitStatusStateByWorkingDirectory = mutableMapOf<String, String>()
    val queuedGitBranchesWithStatusByWorkingDirectory =
        mutableMapOf<String, MutableList<QueuedGitBranchesWithStatusResponse>>()
    var runSnapshot = ThreadRunSnapshot(
        interruptibleTurnId = null,
        hasInterruptibleTurnWithoutId = false,
        latestTurnId = null,
        latestTurnTerminalState = null,
        shouldAssumeRunningFromLatestTurn = false,
    )

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    suspend fun emit(update: ClientUpdate) {
        updatesFlow.emit(update)
    }

    fun enqueueGitBranchesWithStatus(
        workingDirectory: String,
        response: QueuedGitBranchesWithStatusResponse,
    ) {
        queuedGitBranchesWithStatusByWorkingDirectory
            .getOrPut(workingDirectory) { mutableListOf() }
            .add(response)
    }

    override fun hasSavedPairing(): Boolean = hasSavedPairing

    override fun currentFingerprint(): String? = null

    override fun currentTrustedPairSnapshot() = null

    override fun startupNotice(): String? = startupNotice

    override suspend fun connectWithPairingPayload(rawPayload: String) = Unit
    override suspend fun connectWithRecoveryPayload(rawPayload: String) = Unit

    override suspend fun reconnectSaved(): Boolean {
        reconnectSavedCalls += 1
        return true
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
        readThreadRunSnapshotIds += threadId
        return runSnapshot
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

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) = Unit

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        recentStateLoads += 1
        return recentState
    }

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
        val currentBranch = gitCurrentBranchByWorkingDirectory[workingDirectory] ?: gitCurrentBranch
        return buildGitStatus(
            workingDirectory = workingDirectory,
            currentBranch = currentBranch,
            statusState = gitStatusStateByWorkingDirectory[workingDirectory] ?: gitStatusState,
        )
    }

    private fun buildGitStatus(
        workingDirectory: String,
        currentBranch: String,
        statusState: String,
    ): GitRepoSyncResult {
        return GitRepoSyncResult(
            repoRoot = workingDirectory,
            currentBranch = currentBranch,
            trackingBranch = "origin/$currentBranch",
            isDirty = false,
            aheadCount = 0,
            behindCount = 0,
            localOnlyCommitCount = 0,
            state = statusState,
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
        gitPushRequests += workingDirectory
        gitPushGateByWorkingDirectory[workingDirectory]?.await()
        gitPushGate?.await()
        gitPushError?.let { throw it }
        return GitPushResult("main", "origin", gitStatus(workingDirectory))
    }

    override suspend fun gitPull(workingDirectory: String): GitPullResult {
        gitPullRequests += workingDirectory
        gitPullGateByWorkingDirectory[workingDirectory]?.await()
        gitPullGate?.await()
        gitPullError?.let { throw it }
        return GitPullResult(success = true, status = gitStatus(workingDirectory))
    }

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        gitBranchesWithStatusRequests += workingDirectory
        val queuedResponse = queuedGitBranchesWithStatusByWorkingDirectory[workingDirectory]
            ?.takeIf { it.isNotEmpty() }
            ?.removeAt(0)
        queuedResponse?.gate?.await()
        if (queuedResponse == null) {
            gitBranchesWithStatusGateByWorkingDirectory[workingDirectory]?.await()
            gitBranchesWithStatusGate?.await()
        }
        queuedResponse?.error?.let { throw it }
        gitBranchesWithStatusError?.let { throw it }
        val currentBranch = queuedResponse?.currentBranch
            ?: gitCurrentBranchByWorkingDirectory[workingDirectory]
            ?: gitCurrentBranch
        val defaultBranch = queuedResponse?.defaultBranch
            ?: gitDefaultBranchByWorkingDirectory[workingDirectory]
            ?: gitDefaultBranch
        val localCheckoutPath = gitLocalCheckoutPathByWorkingDirectory[workingDirectory]
            ?: gitLocalCheckoutPath
            ?: workingDirectory
        val statusState = queuedResponse?.statusState
            ?: gitStatusStateByWorkingDirectory[workingDirectory]
            ?: gitStatusState
        return GitBranchesWithStatusResult(
            branches = listOf(currentBranch),
            branchesCheckedOutElsewhere = emptySet(),
            worktreePathByBranch = emptyMap(),
            localCheckoutPath = localCheckoutPath,
            currentBranch = currentBranch,
            defaultBranch = defaultBranch,
            status = buildGitStatus(
                workingDirectory = workingDirectory,
                currentBranch = currentBranch,
                statusState = statusState,
            ),
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
