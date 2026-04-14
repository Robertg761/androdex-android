package io.androdex.android

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.data.AndrodexRepositoryContract
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
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceRecentState
import io.androdex.android.onboarding.InMemoryFirstPairingOnboardingStore
import io.androdex.android.ui.state.AndrodexDestinationUiState
import io.androdex.android.ui.state.toAppUiState
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
class MainViewModelPairingOnboardingTest {
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
    fun existingPairedInstall_marksOnboardingSeenImmediately() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository(
            hasSavedPairing = true,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "host-123",
                relayUrl = "wss://relay.example.com/relay",
                fingerprint = "ABCD1234",
                lastPairedAtEpochMs = 1_000L,
                hasSavedRelaySession = true,
            ),
        )
        val onboardingStore = InMemoryFirstPairingOnboardingStore(initialSeen = false)

        val viewModel = MainViewModel(
            repository = repository,
            firstPairingOnboardingStore = onboardingStore,
        )
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.hasSeenFirstPairingOnboarding)
        assertFalse(viewModel.uiState.value.isFirstPairingOnboardingActive)
        assertTrue(onboardingStore.hasSeenFirstPairingOnboarding())
    }

    @Test
    fun externalPayloadConnect_connectsImmediatelyAndBypassesOnboarding() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository()
        val onboardingStore = InMemoryFirstPairingOnboardingStore(initialSeen = false)
        val viewModel = MainViewModel(
            repository = repository,
            firstPairingOnboardingStore = onboardingStore,
        )
        dispatcher.scheduler.runCurrent()

        viewModel.updatePairingInput(VALID_PAIRING_PAYLOAD)
        viewModel.connectWithCurrentPairingInput(fromExternalPayload = true)
        dispatcher.scheduler.runCurrent()

        assertEquals(VALID_PAIRING_PAYLOAD, repository.connectedPayload)
        assertTrue(viewModel.uiState.value.hasSeenFirstPairingOnboarding)
        assertFalse(viewModel.uiState.value.isFirstPairingOnboardingActive)
        assertTrue(onboardingStore.hasSeenFirstPairingOnboarding())
    }

    @Test
    fun httpsPairDeepLink_extractsMacPairUrlPayload() {
        val pairUrl = "https://mac.example.com/pair#token=pair_123"
        assertEquals(pairUrl, extractPairingPayloadFromUriString(pairUrl))
    }

    @Test
    fun freshScanSuccess_overridesSavedReconnectAndConnectsWithNewPayload() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository(
            hasSavedPairing = true,
            trustedPairSnapshot = TrustedPairSnapshot(
                deviceId = "old-host",
                relayUrl = "wss://relay.example.com/relay",
                fingerprint = "OLD1234",
                lastPairedAtEpochMs = 1_000L,
                hasSavedRelaySession = true,
            ),
        )
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.onAppForegrounded()
        dispatcher.scheduler.runCurrent()
        repository.reconnectSavedCalls = 0

        viewModel.beginFreshPairingScan()
        dispatcher.scheduler.runCurrent()
        viewModel.completeFreshPairingScan(VALID_PAIRING_PAYLOAD)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, repository.reconnectSavedCalls)
        assertEquals(2, repository.disconnectCalls)
        assertEquals(VALID_PAIRING_PAYLOAD, repository.connectedPayload)
        assertTrue(viewModel.uiState.value.freshPairingAttempt == null)
    }

    @Test
    fun canceledFreshScan_resumesSavedReconnectAfterNotice() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository(hasSavedPairing = true)
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.onAppForegrounded()
        dispatcher.scheduler.runCurrent()
        repository.reconnectSavedCalls = 0

        viewModel.beginFreshPairingScan()
        dispatcher.scheduler.runCurrent()
        viewModel.completeFreshPairingScan(null)
        dispatcher.scheduler.runCurrent()

        assertEquals(1, repository.reconnectSavedCalls)
        assertEquals("No QR code was captured. Reconnecting to the saved pair.", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.freshPairingAttempt == null)
    }

    @Test
    fun canceledFreshScan_fromFirstPairingFallsBackToManualPairing() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository(hasSavedPairing = false)
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.beginFreshPairingScan()
        dispatcher.scheduler.runCurrent()
        viewModel.completeFreshPairingScan(null)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, repository.reconnectSavedCalls)
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isFirstPairingOnboardingActive)
        assertTrue(viewModel.uiState.value.freshPairingAttempt == null)
        assertTrue(
            viewModel.uiState.value.toAppUiState(isSettingsVisible = false).destination
                is AndrodexDestinationUiState.Pairing
        )
    }

    @Test
    fun invalidFreshPayload_keepsManualPairingStateWithoutResumingReconnect() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository(hasSavedPairing = true)
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.onAppForegrounded()
        dispatcher.scheduler.runCurrent()
        repository.reconnectSavedCalls = 0

        viewModel.beginFreshPairingScan()
        dispatcher.scheduler.runCurrent()
        viewModel.completeFreshPairingScan("not-json")
        dispatcher.scheduler.runCurrent()

        assertEquals(0, repository.reconnectSavedCalls)
        assertEquals(FreshPairingStage.PAYLOAD_CAPTURED, viewModel.uiState.value.freshPairingAttempt?.stage)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("payload") == true)
    }

    @Test
    fun openManualPairingSetup_disconnectsWithoutClearingSavedPairingAndClosesThread() = runTest(dispatcher) {
        val repository = PairingOnboardingRepository(hasSavedPairing = true)
        val viewModel = MainViewModel(repository)
        dispatcher.scheduler.runCurrent()

        viewModel.createThread()
        dispatcher.scheduler.runCurrent()
        assertEquals("thread-created", viewModel.uiState.value.selectedThreadId)

        viewModel.openManualPairingSetup()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, repository.disconnectCalls)
        assertFalse(repository.lastDisconnectClearedSavedPairing)
        assertEquals(null, viewModel.uiState.value.selectedThreadId)
    }

    private companion object {
        val VALID_PAIRING_PAYLOAD = """
            {
              "v": 3,
              "relay": "wss://relay.example.com/socket",
              "hostId": "host-1",
              "macDeviceId": "mac-1",
              "macIdentityPublicKey": "pub",
              "bootstrapToken": "token",
              "expiresAt": 9999999999999
            }
        """.trimIndent()
    }
}

private class PairingOnboardingRepository(
    private val hasSavedPairing: Boolean = false,
    private val trustedPairSnapshot: TrustedPairSnapshot? = null,
) : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    var connectedPayload: String? = null
    var reconnectSavedCalls = 0
    var disconnectCalls = 0
    var lastDisconnectClearedSavedPairing = false
    var connectError: Throwable? = null

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    override fun hasSavedPairing(): Boolean = hasSavedPairing

    override fun currentFingerprint(): String? = null

    override fun currentTrustedPairSnapshot(): TrustedPairSnapshot? = trustedPairSnapshot

    override fun startupNotice(): String? = null

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        connectError?.let { throw it }
        connectedPayload = rawPayload
    }

    override suspend fun connectWithRecoveryPayload(rawPayload: String) = Unit

    override suspend fun reconnectSaved(): Boolean {
        reconnectSavedCalls += 1
        return true
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) {
        disconnectCalls += 1
        lastDisconnectClearedSavedPairing = clearSavedPairing
    }

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

    override suspend fun setSelectedAccessMode(accessMode: AccessMode) = Unit

    override suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) = Unit

    override suspend fun setThreadRuntimeOverride(threadId: String, runtimeOverride: ThreadRuntimeOverride?) = Unit

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) = Unit

    override suspend fun respondToToolUserInput(request: ToolUserInputRequest, response: ToolUserInputResponse) = Unit

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) = Unit

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState = WorkspaceRecentState(null, emptyList())

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
            hasTrustedPhone = false,
        )
    }

    override suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitCommit(workingDirectory: String, message: String): GitCommitResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitPush(workingDirectory: String): GitPushResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitPull(workingDirectory: String): GitPullResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitCheckout(workingDirectory: String, branch: String): GitCheckoutResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitCreateBranch(workingDirectory: String, name: String): GitCreateBranchResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        throw UnsupportedOperationException("Not used in this test.")
    }

    override suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult {
        throw UnsupportedOperationException("Not used in this test.")
    }
}
