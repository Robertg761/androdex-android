package io.androdex.android

import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitChangedFile
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
class MainViewModelGitWorkflowTest {
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
    fun canRunGitAction_requiresBoundWorkingDirectory() {
        val viewModel = MainViewModel(GitTestRepository())

        assertFalse(
            viewModel.canRunGitAction(
                isConnected = true,
                isThreadRunning = false,
                hasGitWorkingDirectory = false,
            )
        )
        assertFalse(
            viewModel.canRunGitAction(
                isConnected = true,
                isThreadRunning = true,
                hasGitWorkingDirectory = true,
            )
        )
        assertTrue(
            viewModel.canRunGitAction(
                isConnected = true,
                isThreadRunning = false,
                hasGitWorkingDirectory = true,
            )
        )
    }

    @Test
    fun requestCreateGitBranch_warnsWhenDefaultBranchHasLocalCommits() = runTest(dispatcher) {
        val repository = GitTestRepository().apply {
            gitStatusResult = gitStatus(
                branch = "main",
                dirty = false,
                localOnlyCommitCount = 2,
            )
            gitBranchesWithStatusResult = gitBranchesResult(status = gitStatusResult)
        }
        val viewModel = prepareThreadViewModel(repository)

        viewModel.openGitBranchDialog()
        viewModel.updateGitBranchName("topic")
        viewModel.requestCreateGitBranch()
        dispatcher.scheduler.runCurrent()

        val alert = viewModel.uiState.value.gitAlert
        assertEquals("Local commits stay on main", alert?.title)
        assertTrue(alert?.message?.contains("2 local commits") == true)
        assertTrue(alert?.buttons?.any { it.label == "Create Anyway" } == true)
    }

    @Test
    fun requestSwitchGitBranch_dirtyTreePromptsCommitFirst() = runTest(dispatcher) {
        val repository = GitTestRepository().apply {
            gitStatusResult = gitStatus(
                branch = "main",
                dirty = true,
                files = listOf(GitChangedFile("android/app/src/main/java/io/androdex/android/MainViewModel.kt", "M")),
            )
            gitBranchesWithStatusResult = gitBranchesResult(
                branches = listOf("main", "feature/existing"),
                status = gitStatusResult,
            )
        }
        val viewModel = prepareThreadViewModel(repository)

        viewModel.requestSwitchGitBranch("feature/existing")
        dispatcher.scheduler.runCurrent()

        val alert = viewModel.uiState.value.gitAlert
        assertEquals("Commit changes before switching branch?", alert?.title)
        assertTrue(alert?.message?.contains("MainViewModel.kt") == true)
        assertTrue(alert?.buttons?.any { it.label == "Commit & Switch" } == true)
    }

    @Test
    fun requestCreateGitWorktree_blocksDirtyBaseMismatch() = runTest(dispatcher) {
        val repository = GitTestRepository().apply {
            gitStatusResult = gitStatus(
                branch = "main",
                dirty = true,
            )
            gitBranchesWithStatusResult = gitBranchesResult(
                branches = listOf("main", "develop"),
                status = gitStatusResult,
            )
        }
        val viewModel = prepareThreadViewModel(repository)

        viewModel.openGitWorktreeDialog()
        viewModel.updateGitWorktreeBranchName("mobile-git")
        viewModel.updateGitWorktreeBaseBranch("develop")
        viewModel.requestCreateGitWorktree()
        dispatcher.scheduler.runCurrent()

        val alert = viewModel.uiState.value.gitAlert
        assertTrue(alert?.title?.contains("local changes", ignoreCase = true) == true)
        assertTrue(alert?.message?.contains("Switch the base branch") == true)
        assertTrue(repository.createdWorktrees.isEmpty())
    }

    private fun prepareThreadViewModel(repository: GitTestRepository): MainViewModel {
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.recentState = WorkspaceRecentState(
            activeCwd = "C:\\Projects\\Androdex",
            recentWorkspaces = listOf(
                WorkspacePathSummary("C:\\Projects\\Androdex", "Androdex", true)
            ),
        )
        repository.tryEmit(
            ClientUpdate.Connection(io.androdex.android.model.ConnectionStatus.CONNECTED)
        )
        repository.tryEmit(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-1",
                        title = "Conversation",
                        preview = null,
                        cwd = "C:\\Projects\\Androdex",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    )
                )
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()
        return viewModel
    }

    private fun GitTestRepository.gitStatus(
        branch: String,
        dirty: Boolean,
        localOnlyCommitCount: Int = 0,
        files: List<GitChangedFile> = emptyList(),
    ): GitRepoSyncResult {
        return GitRepoSyncResult(
            repoRoot = "C:\\Projects\\Androdex",
            currentBranch = branch,
            trackingBranch = "origin/$branch",
            isDirty = dirty,
            aheadCount = localOnlyCommitCount,
            behindCount = 0,
            localOnlyCommitCount = localOnlyCommitCount,
            state = when {
                dirty -> "dirty"
                localOnlyCommitCount > 0 -> "ahead_only"
                else -> "up_to_date"
            },
            canPush = localOnlyCommitCount > 0,
            isPublishedToRemote = true,
            files = files,
            repoDiffTotals = null,
        )
    }

    private fun GitTestRepository.gitBranchesResult(
        branches: List<String> = listOf("main"),
        status: GitRepoSyncResult,
    ): GitBranchesWithStatusResult {
        return GitBranchesWithStatusResult(
            branches = branches,
            branchesCheckedOutElsewhere = emptySet(),
            worktreePathByBranch = emptyMap(),
            localCheckoutPath = "C:\\Projects\\Androdex",
            currentBranch = status.currentBranch,
            defaultBranch = "main",
            status = status,
        )
    }
}

private class GitTestRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>(extraBufferCapacity = 16)

    var recentState = WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    var gitStatusResult = GitRepoSyncResult(
        repoRoot = "C:\\Projects\\Androdex",
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
    var gitBranchesWithStatusResult = GitBranchesWithStatusResult(
        branches = listOf("main"),
        branchesCheckedOutElsewhere = emptySet(),
        worktreePathByBranch = emptyMap(),
        localCheckoutPath = "C:\\Projects\\Androdex",
        currentBranch = "main",
        defaultBranch = "main",
        status = gitStatusResult,
    )
    val createdWorktrees = mutableListOf<String>()

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    fun tryEmit(update: ClientUpdate) {
        updatesFlow.tryEmit(update)
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
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) = Unit

    override suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
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

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult = gitStatusResult

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult = GitRepoDiffResult("")

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult {
        return GitCommitResult("abc1234", "main", message)
    }

    override suspend fun gitPush(workingDirectory: String): GitPushResult {
        return GitPushResult("main", "origin", gitStatusResult)
    }

    override suspend fun gitPull(workingDirectory: String): GitPullResult {
        return GitPullResult(success = true, status = gitStatusResult)
    }

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        return gitBranchesWithStatusResult
    }

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult {
        return GitCheckoutResult(branch, "origin/$branch", gitStatusResult.copy(currentBranch = branch))
    }

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult {
        return GitCreateBranchResult("remodex/$name", gitStatusResult)
    }

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        createdWorktrees += "$name:$baseBranch:${changeTransfer.wireValue}"
        return GitCreateWorktreeResult("remodex/$name", "$workingDirectory\\worktree", false)
    }

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult {
        return GitRemoveWorktreeResult(success = true)
    }
}
