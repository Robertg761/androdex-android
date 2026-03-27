package io.androdex.android

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.data.AndrodexRepositoryContract
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
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceRecentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelComposerAutocompleteTest {
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
    fun fileAutocomplete_selectionAddsChipAndRewritesText() = runTest(dispatcher) {
        val repository = ComposerRepository().apply {
            fuzzyMatches = listOf(
                FuzzyFileMatch(
                    root = "C:\\Projects\\Androdex",
                    path = "android/app/src/main/java/io/androdex/android/MainViewModel.kt",
                    fileName = "MainViewModel.kt",
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

        viewModel.updateComposerText("Inspect @Ma")
        dispatcher.scheduler.advanceTimeBy(200)
        dispatcher.scheduler.runCurrent()

        val beforeSelect = viewModel.uiState.value
        assertTrue(beforeSelect.isFileAutocompleteVisible)
        assertEquals(1, beforeSelect.composerFileAutocompleteItems.size)

        viewModel.selectFileAutocomplete(beforeSelect.composerFileAutocompleteItems.single())

        val afterSelect = viewModel.uiState.value
        assertEquals("Inspect @MainViewModel.kt ", afterSelect.composerText)
        assertEquals(1, afterSelect.composerMentionedFilesByThread["thread-1"]?.size)
        assertFalse(afterSelect.isFileAutocompleteVisible)
    }

    @Test
    fun skillAutocomplete_andSlashCommandSelectionUpdateComposerState() = runTest(dispatcher) {
        val repository = ComposerRepository().apply {
            skills = listOf(
                SkillMetadata(
                    name = "frontend-design",
                    description = "Build polished UI",
                    path = "C:\\Users\\rober\\.codex\\skills\\frontend-design\\SKILL.md",
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, "C:\\Projects\\Androdex", null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("Use \$fr")
        dispatcher.scheduler.advanceTimeBy(200)
        dispatcher.scheduler.runCurrent()

        val skillState = viewModel.uiState.value
        assertTrue(skillState.isSkillAutocompleteVisible)
        viewModel.selectSkillAutocomplete(skillState.composerSkillAutocompleteItems.single())

        val selectedSkillState = viewModel.uiState.value
        assertEquals("Use \$frontend-design ", selectedSkillState.composerText)
        assertEquals(1, selectedSkillState.composerMentionedSkillsByThread["thread-1"]?.size)

        viewModel.updateComposerText("/sub")
        dispatcher.scheduler.runCurrent()

        val slashState = viewModel.uiState.value
        assertTrue(slashState.isSlashCommandAutocompleteVisible)
        assertEquals(listOf(ComposerSlashCommand.SUBAGENTS), slashState.composerSlashCommandItems)

        viewModel.selectSlashCommand(ComposerSlashCommand.SUBAGENTS)

        val selectedSlashState = viewModel.uiState.value
        assertTrue(selectedSlashState.isComposerSubagentsEnabled)
        assertEquals("", selectedSlashState.composerText)
        assertFalse(selectedSlashState.isSlashCommandAutocompleteVisible)
    }

    @Test
    fun sendMessage_buildsCanonicalFilePayloadAndStructuredSkillMentions() = runTest(dispatcher) {
        val repository = ComposerRepository().apply {
            fuzzyMatches = listOf(
                FuzzyFileMatch(
                    root = "C:\\Projects\\Androdex",
                    path = "android/app/src/main/java/io/androdex/android/MainViewModel.kt",
                    fileName = "MainViewModel.kt",
                )
            )
            skills = listOf(
                SkillMetadata(
                    name = "frontend-design",
                    description = "Build polished UI",
                    path = "C:\\Users\\rober\\.codex\\skills\\frontend-design\\SKILL.md",
                )
            )
        }
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, "C:\\Projects\\Androdex", null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("Inspect @Ma")
        dispatcher.scheduler.advanceTimeBy(200)
        dispatcher.scheduler.runCurrent()
        viewModel.selectFileAutocomplete(viewModel.uiState.value.composerFileAutocompleteItems.single())
        viewModel.updateComposerText(viewModel.uiState.value.composerText + "with \$fr")
        dispatcher.scheduler.advanceTimeBy(200)
        dispatcher.scheduler.runCurrent()
        viewModel.selectSkillAutocomplete(viewModel.uiState.value.composerSkillAutocompleteItems.single())

        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("thread-1:Inspect @android/app/src/main/java/io/androdex/android/MainViewModel.kt with \$frontend-design"),
            repository.startedTurns,
        )
        assertEquals(
            listOf(
                listOf(
                    TurnSkillMention(
                        id = "frontend-design",
                        name = "frontend-design",
                        path = "C:\\Users\\rober\\.codex\\skills\\frontend-design\\SKILL.md",
                    )
                )
            ),
            repository.startedTurnSkillMentions,
        )
    }

    @Test
    fun reviewSlashCommand_armsInlineReviewAndAllowsTargetSwitching() = runTest(dispatcher) {
        val repository = ComposerRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, "C:\\Projects\\Androdex", null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.updateComposerText("/review")
        viewModel.selectSlashCommand(ComposerSlashCommand.REVIEW)

        val armedState = viewModel.uiState.value
        assertEquals(
            ComposerReviewTarget.UNCOMMITTED_CHANGES,
            armedState.composerReviewSelectionByThread["thread-1"]?.target,
        )
        assertEquals("", armedState.composerText)

        viewModel.updateComposerReviewTarget(ComposerReviewTarget.BASE_BRANCH)
        val switchedState = viewModel.uiState.value
        assertEquals(ComposerReviewTarget.BASE_BRANCH, switchedState.composerReviewSelectionByThread["thread-1"]?.target)
        assertEquals("main", switchedState.composerReviewSelectionByThread["thread-1"]?.baseBranch)
    }

    @Test
    fun reviewMode_clearsWhenDraftTextBecomesNonReviewContent() = runTest(dispatcher) {
        val repository = ComposerRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, "C:\\Projects\\Androdex", null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.selectSlashCommand(ComposerSlashCommand.REVIEW)
        assertNotNull(viewModel.uiState.value.composerReviewSelectionByThread["thread-1"])

        viewModel.updateComposerText("Please review this")

        assertFalse(viewModel.uiState.value.composerReviewSelectionByThread.containsKey("thread-1"))
    }

    @Test
    fun sendMessage_routesReviewRequestsThroughReviewRpc() = runTest(dispatcher) {
        val repository = ComposerRepository()
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()
        repository.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, "C:\\Projects\\Androdex", null, null))
            )
        )
        dispatcher.scheduler.runCurrent()
        viewModel.openThread("thread-1")
        dispatcher.scheduler.runCurrent()

        viewModel.selectSlashCommand(ComposerSlashCommand.REVIEW)
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("thread-1:UNCOMMITTED_CHANGES:"),
            repository.startedReviews,
        )
        assertTrue(repository.startedTurns.isEmpty())
    }

    @Test
    fun slashCommandFiltering_keepsReviewCommandVisible() {
        assertTrue(ComposerSlashCommand.filtered("").contains(ComposerSlashCommand.REVIEW))
    }
}

private class ComposerRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    var fuzzyMatches: List<FuzzyFileMatch> = emptyList()
    var skills: List<SkillMetadata> = emptyList()
    val startedTurns = mutableListOf<String>()
    val startedTurnSkillMentions = mutableListOf<List<TurnSkillMention>>()
    val startedReviews = mutableListOf<String>()

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
    ): List<FuzzyFileMatch> = fuzzyMatches

    override suspend fun listSkills(cwds: List<String>?): List<SkillMetadata> = skills

    override suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment>,
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        startedTurns += "$threadId:$userInput"
        startedTurnSkillMentions += skillMentions
    }

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) {
        startedReviews += "$threadId:${target.name}:${baseBranch.orEmpty()}"
    }

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

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        return WorkspaceRecentState(activeCwd = "C:\\Projects\\Androdex", recentWorkspaces = emptyList())
    }

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult {
        return WorkspaceBrowseResult(
            requestedPath = path,
            parentPath = null,
            entries = emptyList(),
            rootEntries = emptyList(),
            activeCwd = "C:\\Projects\\Androdex",
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
