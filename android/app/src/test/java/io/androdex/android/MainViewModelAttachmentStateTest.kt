package io.androdex.android

import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ComposerImageAttachmentState
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
import io.androdex.android.model.MAX_COMPOSER_IMAGE_ATTACHMENTS
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelAttachmentStateTest {
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
    fun beginComposerAttachmentIntake_capsAtMaxAndCreatesLoadingTiles() = runTest(dispatcher) {
        val repository = AttachmentRepository()
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

        val reservation = viewModel.beginComposerAttachmentIntake(MAX_COMPOSER_IMAGE_ATTACHMENTS + 2)
        dispatcher.scheduler.runCurrent()

        assertNotNull(reservation)
        assertEquals(MAX_COMPOSER_IMAGE_ATTACHMENTS, reservation?.acceptedIds?.size)
        assertEquals(2, reservation?.droppedCount)
        val attachments = viewModel.uiState.value.composerAttachmentsByThread["thread-1"].orEmpty()
        assertEquals(MAX_COMPOSER_IMAGE_ATTACHMENTS, attachments.size)
        assertTrue(attachments.all { it.state == ComposerImageAttachmentState.Loading })
    }

    @Test
    fun sendMessage_doesNothingWhileAttachmentStateIsBlocking() = runTest(dispatcher) {
        val repository = AttachmentRepository()
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

        val reservation = requireNotNull(viewModel.beginComposerAttachmentIntake(1))
        viewModel.updateComposerAttachmentState(
            threadId = reservation.threadId,
            attachmentId = reservation.acceptedIds.single(),
            state = ComposerImageAttachmentState.Failed("Couldn't load"),
        )
        dispatcher.scheduler.runCurrent()

        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.startedTurnInputs.isEmpty())
        assertEquals(1, viewModel.uiState.value.composerAttachmentsByThread["thread-1"]?.size)
    }

    @Test
    fun queuedAttachmentDraft_restoresReadyTilesAndFlushesAttachmentPayload() = runTest(dispatcher) {
        val repository = AttachmentRepository()
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

        val reservation = requireNotNull(viewModel.beginComposerAttachmentIntake(1))
        val attachment = ImageAttachment(
            id = "image-1",
            thumbnailBase64Jpeg = "thumb",
            payloadDataUrl = "data:image/jpeg;base64,AAAA",
        )
        viewModel.updateComposerAttachmentState(
            threadId = reservation.threadId,
            attachmentId = reservation.acceptedIds.single(),
            state = ComposerImageAttachmentState.Ready(attachment),
        )
        dispatcher.scheduler.runCurrent()

        repository.emit(ClientUpdate.TurnStarted("thread-1", "turn-1"))
        dispatcher.scheduler.runCurrent()
        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()
        viewModel.pauseSelectedThreadQueue()
        dispatcher.scheduler.runCurrent()

        val queuedDraft = viewModel.uiState.value.queuedDraftStateByThread["thread-1"]
            ?.drafts
            ?.single()
        assertNotNull(queuedDraft)
        assertTrue(viewModel.uiState.value.composerAttachmentsByThread["thread-1"].isNullOrEmpty())
        assertEquals(listOf(attachment), queuedDraft?.attachments)

        repository.emit(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        dispatcher.scheduler.runCurrent()

        viewModel.restoreQueuedDraftToComposer(requireNotNull(queuedDraft).id)
        dispatcher.scheduler.runCurrent()

        val restoredAttachments = viewModel.uiState.value.composerAttachmentsByThread["thread-1"].orEmpty()
        assertEquals(1, restoredAttachments.size)
        assertEquals(
            ComposerImageAttachmentState.Ready(attachment),
            restoredAttachments.single().state,
        )

        viewModel.sendMessage()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("thread-1:"), repository.startedTurnInputs)
        assertEquals(listOf(listOf(attachment)), repository.startedTurnAttachments)
        assertTrue(viewModel.uiState.value.composerAttachmentsByThread["thread-1"].isNullOrEmpty())
    }
}

private class AttachmentRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    val startedTurnInputs = mutableListOf<String>()
    val startedTurnAttachments = mutableListOf<List<ImageAttachment>>()

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
        skillMentions: List<TurnSkillMention>,
        collaborationMode: CollaborationModeKind?,
    ) {
        startedTurnInputs += "$threadId:$userInput"
        startedTurnAttachments += attachments
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
            state = "clean",
            canPush = false,
            isPublishedToRemote = true,
            files = emptyList(),
            repoDiffTotals = null,
        )
    }

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult {
        return GitRepoDiffResult(patch = "")
    }

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
