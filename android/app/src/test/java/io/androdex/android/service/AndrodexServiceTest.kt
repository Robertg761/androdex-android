package io.androdex.android.service

import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.PlanStep
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.WorkspaceRecentState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndrodexServiceTest {
    @Test
    fun sendMessage_keepsPerThreadRunningFallbackUntilTurnCompletion() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true))
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.sendMessage("Ship it")
        advanceUntilIdle()

        assertEquals(setOf("thread-created"), service.state.value.runningThreadIds)
        assertTrue(service.state.value.activeTurnIdByThread.isEmpty())

        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = null,
                turnId = null,
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        advanceUntilIdle()

        assertTrue(service.state.value.runningThreadIds.isEmpty())
        assertTrue(service.state.value.selectedThreadId == "thread-created")
    }

    @Test
    fun assistantEvents_mergeByItemAndTrackThreadTurnState() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.AssistantDelta("thread-1", "turn-1", "item-1", "Hello"))
        service.processClientUpdate(ClientUpdate.AssistantDelta("thread-1", "turn-1", "item-1", " world"))
        service.processClientUpdate(ClientUpdate.AssistantCompleted("thread-1", "turn-1", "item-1", "Hello world"))

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(1, messages.size)
        assertEquals("Hello world", messages.single().text)
        assertEquals("turn-1", service.state.value.activeTurnIdByThread["thread-1"])
        assertEquals(setOf("thread-1"), service.state.value.runningThreadIds)
    }

    @Test
    fun assistantEvents_keepDistinctItemsInSameTurn() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.AssistantDelta("thread-1", "turn-1", "item-1", "First"))
        service.processClientUpdate(ClientUpdate.AssistantDelta("thread-1", "turn-1", "item-2", "Second"))

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(listOf("item-1", "item-2"), messages.map { it.itemId })
        assertEquals(listOf("First", "Second"), messages.map { it.text })
    }

    @Test
    fun planUpdates_mergeStreamingAndStructuredStateIntoSingleRow() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.PlanUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Break the work into safe steps.",
                steps = listOf(
                    PlanStep("Inspect the send flow", "completed"),
                    PlanStep("Wire collaboration payloads", "in_progress"),
                ),
            )
        )
        service.processClientUpdate(
            ClientUpdate.PlanDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "plan-1",
                delta = "Planning...",
            )
        )
        service.processClientUpdate(
            ClientUpdate.PlanCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "plan-1",
                text = "Break the work into safe steps.",
                explanation = "Break the work into safe steps.",
                steps = listOf(
                    PlanStep("Inspect the send flow", "completed"),
                    PlanStep("Wire collaboration payloads", "completed"),
                ),
            )
        )

        val planMessages = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.PLAN }
        assertEquals(1, planMessages.size)
        val structuredPlan = planMessages.single()
        assertEquals("Break the work into safe steps.", structuredPlan.planExplanation)
        assertEquals(2, structuredPlan.planSteps?.size)
        assertEquals("completed", structuredPlan.planSteps?.last()?.status)
    }

    @Test
    fun lateReasoningDeltaAfterTurnCompletionDoesNotCreateNewThinkingRow() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.TurnStarted(threadId = "thread-1", turnId = "turn-1")
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        service.processClientUpdate(
            ClientUpdate.ReasoningDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "reasoning-1",
                delta = "Late reasoning chunk",
            )
        )

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertTrue(messages.none { it.kind == ConversationKind.THINKING })
    }

    @Test
    fun lateReasoningDeltaAfterTurnCompletionUpdatesExistingThinkingWithoutStreaming() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.TurnStarted(threadId = "thread-1", turnId = "turn-1")
        )
        service.processClientUpdate(
            ClientUpdate.ReasoningDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "reasoning-1",
                delta = "First",
            )
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        service.processClientUpdate(
            ClientUpdate.ReasoningDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "reasoning-1",
                delta = " second",
            )
        )

        val thinking = service.state.value.timelineByThread["thread-1"].orEmpty().single()
        assertEquals("First second", thinking.text)
        assertFalse(thinking.isStreaming)
    }

    @Test
    fun connectedRecoveryRehydratesSelectedThreadRunStateFromThreadRead() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Recovered", null, null, null, null),
                messages = emptyList(),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-recovered",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-recovered",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", null))
        service.openThread("thread-1")
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.RECONNECT_REQUIRED))
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        assertEquals("turn-recovered", service.state.value.activeTurnIdByThread["thread-1"])
        assertTrue(service.state.value.runningThreadIds.contains("thread-1"))
    }

    @Test
    fun interruptThread_usesThreadReadFallbackWhenActiveTurnIsMissing() = runTest {
        val repository = FakeRepository().apply {
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = "turn-live",
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-live",
                latestTurnTerminalState = null,
                shouldAssumeRunningFromLatestTurn = false,
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", null))
        service.interruptThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.readThreadRunSnapshotIds)
        assertEquals(listOf("thread-1:turn-live"), repository.interruptedTurns)
        assertEquals("turn-live", service.state.value.activeTurnIdByThread["thread-1"])
    }

    @Test
    fun interruptThread_usesKnownActiveTurnIdWithoutThreadRead() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", "turn-live"))
        service.interruptThread("thread-1")
        advanceUntilIdle()

        assertTrue(repository.readThreadRunSnapshotIds.isEmpty())
        assertEquals(listOf("thread-1:turn-live"), repository.interruptedTurns)
    }

    @Test
    fun interruptThread_keepsProtectedFallbackWhenThreadReadHasRunningTurnWithoutId() = runTest {
        val repository = FakeRepository().apply {
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = true,
                latestTurnId = "turn-completed",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", null))

        runCatching { service.interruptThread("thread-1") }

        assertEquals(listOf("thread-1"), repository.readThreadRunSnapshotIds)
        assertTrue(service.state.value.protectedRunningFallbackThreadIds.contains("thread-1"))
        assertFalse(service.state.value.runningThreadIds.contains("thread-1"))
        assertTrue(repository.interruptedTurns.isEmpty())
    }

    @Test
    fun sendMessage_steersActiveRunWhenTurnIdIsKnown() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = emptyList(),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-live",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-live",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", "turn-live"))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()
        service.openThread("thread-1")
        advanceUntilIdle()

        service.sendMessage("Keep going")
        advanceUntilIdle()

        assertTrue(repository.readThreadRunSnapshotIds.isEmpty())
        assertEquals(listOf("thread-1:turn-live:Keep going"), repository.steeredTurns)
        assertTrue(repository.startedTurns.isEmpty())
    }

    @Test
    fun sendMessage_usesThreadReadFallbackToSteerWhenTurnIdIsMissing() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = emptyList(),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = true,
                    latestTurnId = "turn-live",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = "turn-live",
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-live",
                latestTurnTerminalState = null,
                shouldAssumeRunningFromLatestTurn = false,
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", null))
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()
        service.openThread("thread-1")
        advanceUntilIdle()

        service.sendMessage("Keep going")
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.readThreadRunSnapshotIds)
        assertEquals(listOf("thread-1:turn-live:Keep going"), repository.steeredTurns)
        assertTrue(repository.startedTurns.isEmpty())
        assertEquals("turn-live", service.state.value.activeTurnIdByThread["thread-1"])
    }

    @Test
    fun sendMessage_passesPlanModeToRepository() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.sendMessage("Make a plan", collaborationMode = CollaborationModeKind.PLAN)
        advanceUntilIdle()

        assertEquals(listOf("thread-created:Make a plan"), repository.startedTurns)
        assertEquals(listOf(CollaborationModeKind.PLAN), repository.startedTurnModes)
    }

    @Test
    fun openThread_activatesDifferentWorkspaceBeforeLoadingThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "C:\\Projects\\AppA",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", true),
                    WorkspacePathSummary("D:\\Client\\SiteB", "SiteB", false),
                )
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
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

        service.loadWorkspaceState()
        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("D:\\Client\\SiteB"), repository.activatedWorkspaces)
        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
    }
}

private class FakeRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>(replay = 16, extraBufferCapacity = 16)

    var hasSavedPairing = false
    var recentState = WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    var startupNotice: String? = null
    val activatedWorkspaces = mutableListOf<String>()
    val loadedThreadIds = mutableListOf<String>()
    val startedThreadCwds = mutableListOf<String?>()

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    suspend fun emit(update: ClientUpdate) {
        updatesFlow.emit(update)
    }

    override fun hasSavedPairing(): Boolean = hasSavedPairing

    override fun currentFingerprint(): String? = null

    override fun startupNotice(): String? = startupNotice

    override suspend fun connectWithPairingPayload(rawPayload: String) = Unit

    override suspend fun reconnectSaved() = Unit

    override suspend fun disconnect(clearSavedPairing: Boolean) = Unit

    override suspend fun refreshThreads(): List<ThreadSummary> = emptyList()

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary {
        startedThreadCwds += preferredProjectPath
        return ThreadSummary("thread-created", "Conversation", null, preferredProjectPath, null, null)
    }

    var loadThreadResult = ThreadLoadResult(
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
    var runSnapshot = ThreadRunSnapshot(
        interruptibleTurnId = null,
        hasInterruptibleTurnWithoutId = false,
        latestTurnId = null,
        latestTurnTerminalState = null,
        shouldAssumeRunningFromLatestTurn = false,
    )
    val readThreadRunSnapshotIds = mutableListOf<String>()
    val startedTurns = mutableListOf<String>()
    val startedTurnModes = mutableListOf<CollaborationModeKind?>()
    val steeredTurns = mutableListOf<String>()
    val steeredTurnModes = mutableListOf<CollaborationModeKind?>()
    val interruptedTurns = mutableListOf<String>()

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        loadedThreadIds += threadId
        return loadThreadResult
    }

    override suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot {
        readThreadRunSnapshotIds += threadId
        return runSnapshot
    }

    override suspend fun startTurn(
        threadId: String,
        userInput: String,
        collaborationMode: CollaborationModeKind?,
    ) {
        startedTurns += "$threadId:$userInput"
        startedTurnModes += collaborationMode
    }

    override suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        userInput: String,
        collaborationMode: CollaborationModeKind?,
    ) {
        steeredTurns += "$threadId:$expectedTurnId:$userInput"
        steeredTurnModes += collaborationMode
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        interruptedTurns += "$threadId:$turnId"
    }

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
}
