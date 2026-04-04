package io.androdex.android.service

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.ExecutionKind
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
import io.androdex.android.model.PlanStep
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.SubagentAction
import io.androdex.android.model.SubagentState
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.WorkspaceRecentState
import io.androdex.android.model.requestId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AndrodexServiceTest {
    @Test
    fun service_restoresPersistedThreadTimelinesAtStartup() = runTest {
        val repository = FakeRepository().apply {
            persistedThreadTimelines = linkedMapOf(
                "thread-1" to listOf(
                    ConversationMessage(
                        id = "cached-user",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "Cached prompt",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "user-item",
                    ),
                    ConversationMessage(
                        id = "cached-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-1",
                        itemId = "assistant-item",
                    ),
                )
            )
        }

        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        assertEquals(
            listOf("Cached prompt", "Cached reply"),
            service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text },
        )
        assertEquals(2, service.state.value.timelineRenderSnapshotsByThread.getValue("thread-1").items.size)
    }

    @Test
    fun openThread_showsPersistedTimelineBeforeHostHydrationFinishes() = runTest {
        val cachedMessages = listOf(
            ConversationMessage(
                id = "cached-user",
                threadId = "thread-1",
                role = ConversationRole.USER,
                kind = ConversationKind.CHAT,
                text = "Cached prompt",
                createdAtEpochMs = 1_000L,
                turnId = "turn-1",
                itemId = "user-item",
            ),
            ConversationMessage(
                id = "cached-assistant",
                threadId = "thread-1",
                role = ConversationRole.ASSISTANT,
                kind = ConversationKind.CHAT,
                text = "Cached reply",
                createdAtEpochMs = 2_000L,
                turnId = "turn-1",
                itemId = "assistant-item",
            ),
        )
        val repository = FakeRepository().apply {
            persistedThreadTimelines = linkedMapOf("thread-1" to cachedMessages)
            loadThreadDelayMs = 5_000L
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, 5_000L),
                messages = cachedMessages + ConversationMessage(
                    id = "fresh-thinking",
                    threadId = "thread-1",
                    role = ConversationRole.SYSTEM,
                    kind = ConversationKind.THINKING,
                    text = "Fresh host state",
                    createdAtEpochMs = 3_000L,
                    turnId = "turn-1",
                    itemId = "thinking-item",
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        val openJob = backgroundScope.launch {
            service.openThread("thread-1")
        }
        runCurrent()

        assertTrue(openJob.isActive)
        assertEquals("thread-1", service.state.value.selectedThreadId)
        assertEquals(listOf("Cached prompt", "Cached reply"), service.state.value.messages.map { it.text })

        openJob.cancel()
    }

    @Test
    fun openThread_hostHydrationReconcilesPersistedTimelineWithoutDuplicates() = runTest {
        val cachedMessages = listOf(
            ConversationMessage(
                id = "cached-user",
                threadId = "thread-1",
                role = ConversationRole.USER,
                kind = ConversationKind.CHAT,
                text = "Cached prompt",
                createdAtEpochMs = 1_000L,
                turnId = "turn-1",
                itemId = "user-item",
            ),
            ConversationMessage(
                id = "cached-assistant",
                threadId = "thread-1",
                role = ConversationRole.ASSISTANT,
                kind = ConversationKind.CHAT,
                text = "Cached reply",
                createdAtEpochMs = 2_000L,
                turnId = "turn-1",
                itemId = "assistant-item",
            ),
        )
        val repository = FakeRepository().apply {
            persistedThreadTimelines = linkedMapOf("thread-1" to cachedMessages)
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, 5_000L),
                messages = cachedMessages + ConversationMessage(
                    id = "fresh-thinking",
                    threadId = "thread-1",
                    role = ConversationRole.SYSTEM,
                    kind = ConversationKind.THINKING,
                    text = "Fresh host state",
                    createdAtEpochMs = 3_000L,
                    turnId = "turn-1",
                    itemId = "thinking-item",
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(
            listOf("Cached prompt", "Cached reply", "Fresh host state"),
            service.state.value.messages.map { it.text },
        )
        assertEquals(1, service.state.value.messages.count { it.id == "cached-user" })
        assertEquals(1, service.state.value.messages.count { it.id == "cached-assistant" })
    }

    @Test
    fun respondToApproval_usesExplicitRequestInsteadOfCurrentPendingApproval() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        val renderedRequest = ApprovalRequest(
            idValue = "request-rendered",
            method = "item/commandExecution/requestApproval",
            command = "rm -rf /tmp/demo",
            reason = "Needs approval",
            threadId = "thread-1",
            turnId = "turn-1",
        )
        val replacementRequest = renderedRequest.copy(
            idValue = "request-replacement",
            command = "echo replacement",
        )

        repository.emit(ClientUpdate.ApprovalRequested(renderedRequest))
        advanceUntilIdle()
        repository.emit(ClientUpdate.ApprovalRequested(replacementRequest))
        advanceUntilIdle()

        service.respondToApproval(renderedRequest, accept = true)

        assertEquals(renderedRequest, repository.lastApprovalRequest)
        assertEquals(true, repository.lastApprovalAccepted)
    }

    @Test
    fun persistedTimelineWrites_keepOriginalHostScopeWhenFlushRunsLater() = runTest {
        val repository = FakeRepository().apply {
            currentThreadTimelineScopeKeyValue = "host-a"
        }
        val persistenceDispatcher = ManualCoroutineDispatcher()
        val service = AndrodexService(
            repository = repository,
            scope = backgroundScope,
            threadTimelinePersistenceDispatcher = persistenceDispatcher,
        )
        runCurrent()
        service.enqueueThreadTimelinePersistence(
            scopeKey = "host-a",
            changedMessagesByThread = mapOf(
                "thread-1" to listOf(
                    ConversationMessage(
                        id = "assistant-1",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                    )
                )
            ),
        )
        repository.currentThreadTimelineScopeKeyValue = "host-b"
        persistenceDispatcher.runAll()

        assertEquals(1, repository.persistedThreadTimelineWrites.size)
        assertEquals("host-a", repository.persistedThreadTimelineWrites.single().first)
    }

    @Test
    fun connectWithPairingPayload_reloadsPersistedTimelinesForNewHostScope() = runTest {
        val hostAMessages = listOf(
            ConversationMessage(
                id = "host-a-user",
                threadId = "thread-a",
                role = ConversationRole.USER,
                kind = ConversationKind.CHAT,
                text = "Host A cached prompt",
                createdAtEpochMs = 1_000L,
            )
        )
        val hostBMessages = listOf(
            ConversationMessage(
                id = "host-b-user",
                threadId = "thread-b",
                role = ConversationRole.USER,
                kind = ConversationKind.CHAT,
                text = "Host B cached prompt",
                createdAtEpochMs = 2_000L,
            )
        )
        val repository = FakeRepository().apply {
            currentThreadTimelineScopeKeyValue = "host-a"
            persistedThreadTimelinesByScope = mapOf(
                "host-a" to linkedMapOf("thread-a" to hostAMessages),
                "host-b" to linkedMapOf("thread-b" to hostBMessages),
            )
            connectWithPairingPayloadAction = {
                currentThreadTimelineScopeKeyValue = "host-b"
            }
        }

        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        assertEquals(listOf("Host A cached prompt"), service.state.value.timelineByThread["thread-a"]?.map { it.text })

        service.connectWithPairingPayload("payload", isFreshPairing = false)
        advanceUntilIdle()

        assertFalse(service.state.value.timelineByThread.containsKey("thread-a"))
        assertEquals(listOf("Host B cached prompt"), service.state.value.timelineByThread["thread-b"]?.map { it.text })
    }

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
    fun assistantStreamingDelta_reusesUnchangedRenderRowsForTextOnlyUpdates() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "user-1",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "Prompt",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "user-item",
                    ),
                    ConversationMessage(
                        id = "assistant-1",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Hel",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-1",
                        itemId = "assistant-item",
                        isStreaming = true,
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-1",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = true,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        val beforeSnapshot = service.state.value.timelineRenderSnapshotsByThread.getValue("thread-1")
        val beforeUserRow = beforeSnapshot.items.first()

        service.processClientUpdate(
            ClientUpdate.AssistantDelta("thread-1", "turn-1", "assistant-item", "lo")
        )

        val afterSnapshot = service.state.value.timelineRenderSnapshotsByThread.getValue("thread-1")
        assertSame(beforeUserRow, afterSnapshot.items.first())
        assertEquals("Hello", afterSnapshot.items.last().message.text)
        assertTrue(afterSnapshot.items.last().message.isStreaming)
    }

    @Test
    fun reasoningDelta_structuralChangeRebuildsRenderSnapshotRows() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "user-1",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "Prompt",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "user-item",
                    ),
                    ConversationMessage(
                        id = "assistant-1",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Answer",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-1",
                        itemId = "assistant-item",
                        isStreaming = true,
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-1",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = true,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        val beforeSnapshot = service.state.value.timelineRenderSnapshotsByThread.getValue("thread-1")
        val beforeUserRow = beforeSnapshot.items.first()

        service.processClientUpdate(
            ClientUpdate.ReasoningDelta("thread-1", "turn-1", "thinking-item", "Planning")
        )

        val afterSnapshot = service.state.value.timelineRenderSnapshotsByThread.getValue("thread-1")
        assertEquals(3, afterSnapshot.items.size)
        assertNotSame(beforeUserRow, afterSnapshot.items.first())
        assertEquals(ConversationKind.THINKING, afterSnapshot.items.last().message.kind)
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
    fun assistantEvents_mergeFirstReplyWhenTurnIdArrivesAfterStreamingRow() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", null))
        service.processClientUpdate(ClientUpdate.AssistantDelta("thread-1", null, null, "o"))
        service.processClientUpdate(ClientUpdate.AssistantCompleted("thread-1", "turn-1", null, "ok"))

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(1, messages.size)
        assertEquals("ok", messages.single().text)
        assertEquals("turn-1", messages.single().turnId)
        assertFalse(messages.single().isStreaming)
    }

    @Test
    fun openThread_whileRunning_mergesTurnlessAssistantReplyIntoLoadedHistory() = runTest {
        val now = System.currentTimeMillis()
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-user",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "reply%20only%20with%20alpha0330b",
                        createdAtEpochMs = now + 2_000L,
                        turnId = "turn-1",
                        itemId = "user-1",
                    ),
                    ConversationMessage(
                        id = "history-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "alpha0330b",
                        createdAtEpochMs = now + 3_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-1",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = true,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.sendMessage("reply%20only%20with%20alpha0330b", preferredThreadId = "thread-1")
        service.processClientUpdate(
            ClientUpdate.AssistantCompleted(
                threadId = "thread-1",
                turnId = null,
                itemId = null,
                text = "alpha0330b",
            )
        )

        service.openThread("thread-1")
        advanceUntilIdle()

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(2, messages.size)
        assertEquals(
            listOf("reply%20only%20with%20alpha0330b", "alpha0330b"),
            messages.map { it.text },
        )
        assertEquals(listOf("turn-1", "turn-1"), messages.map { it.turnId })
    }

    @Test
    fun openThread_replacesSettledLocalTimelineWithAuthoritativeHistory() = runTest {
        val now = System.currentTimeMillis()
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-user",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "reply%20only%20with%20alpha0330b",
                        createdAtEpochMs = now + 2_000L,
                        turnId = "turn-1",
                        itemId = "user-1",
                    ),
                    ConversationMessage(
                        id = "history-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "alpha0330b",
                        createdAtEpochMs = now + 3_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.sendMessage("reply%20only%20with%20alpha0330b", preferredThreadId = "thread-1")
        service.processClientUpdate(ClientUpdate.ReasoningDelta("thread-1", null, null, "[]"))
        service.processClientUpdate(
            ClientUpdate.AssistantCompleted(
                threadId = "thread-1",
                turnId = null,
                itemId = null,
                text = "alpha0330b",
            )
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = null,
                terminalState = io.androdex.android.model.TurnTerminalState.COMPLETED,
            )
        )
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(2, messages.size)
        assertEquals(
            listOf("reply%20only%20with%20alpha0330b", "alpha0330b"),
            messages.map { it.text },
        )
    }

    @Test
    fun pairingAvailabilityFalse_closesSelectedThreadAndClearsThreadList() = runTest {
        val repository = FakeRepository().apply {
            hasSavedPairing = true
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Conversation", null, null, null, null),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.refreshThreads()
        service.openThread("thread-1")
        advanceUntilIdle()
        assertEquals("thread-1", service.state.value.selectedThreadId)
        assertFalse(service.state.value.threads.isEmpty())

        service.processClientUpdate(ClientUpdate.PairingAvailability(hasSavedPairing = false))
        advanceUntilIdle()

        assertNull(service.state.value.selectedThreadId)
        assertTrue(service.state.value.threads.isEmpty())
        assertFalse(service.state.value.hasLoadedThreadList)
        assertTrue(service.state.value.timelineByThread.isEmpty())
        assertTrue(service.state.value.hydratedThreadIds.isEmpty())
    }

    @Test
    fun openThread_deduplicatesMirroredChatRowsWithinSingleHistorySnapshot() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-user-1",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "reply%20only%20with%20alpha0330b",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-1",
                        itemId = "user-1",
                    ),
                    ConversationMessage(
                        id = "history-user-2",
                        threadId = "thread-1",
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = "reply%20only%20with%20alpha0330b",
                        createdAtEpochMs = 2_001L,
                        turnId = "turn-1",
                        itemId = "user-2",
                    ),
                    ConversationMessage(
                        id = "history-assistant-1",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "alpha0330b",
                        createdAtEpochMs = 3_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    ),
                    ConversationMessage(
                        id = "history-assistant-2",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "alpha0330b",
                        createdAtEpochMs = 3_001L,
                        turnId = "turn-1",
                        itemId = "assistant-2",
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(2, messages.size)
        assertEquals(
            listOf("reply%20only%20with%20alpha0330b", "alpha0330b"),
            messages.map { it.text },
        )
    }

    @Test
    fun openThread_preservesDistinctHistoryItemsWithSharedTurnId() = runTest {
        val now = System.currentTimeMillis()
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-1",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "First reply",
                        createdAtEpochMs = now + 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    ),
                    ConversationMessage(
                        id = "history-assistant-2",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Second reply",
                        createdAtEpochMs = now + 2_000L,
                        turnId = "turn-1",
                        itemId = "assistant-2",
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.AssistantCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-1",
                text = "First reply",
            )
        )
        service.processClientUpdate(
            ClientUpdate.AssistantCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-2",
                text = "Second reply",
            )
        )

        service.openThread("thread-1")
        advanceUntilIdle()

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
        assertEquals(listOf("assistant-1", "assistant-2"), messages.map { it.itemId })
        assertEquals(listOf("First reply", "Second reply"), messages.map { it.text })
    }

    @Test
    fun openThread_reselectingVisibleHydratedThreadSkipsSecondHistoryLoad() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertTrue("thread-1" in service.state.value.hydratedThreadIds)
        assertEquals(listOf("Cached reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun refreshThreads_keepsHydratedThreadWhenSummaryVersionIsUnchanged() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        service.refreshThreads()
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertTrue("thread-1" in service.state.value.hydratedThreadIds)
        assertEquals(listOf("Cached reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_reloadsHydratedThreadAfterReconnect() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Conversation", null, null, null, null),
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-1",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
            messages = listOf(
                ConversationMessage(
                    id = "history-assistant-2",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Fresh reply",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-2",
                    itemId = "assistant-2",
                )
            ),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-2",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )

        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.RECONNECT_REQUIRED))
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_reselectingRunningHydratedThreadForcesFreshHistoryLoad() = runTest {
        val repository = FakeRepository().apply {
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                        isStreaming = true,
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-1",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = true,
                ),
            )
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-fresh",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Fresh reply",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-2",
                        itemId = "assistant-2",
                        isStreaming = true,
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-2",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-2",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = true,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(
            listOf("Stale reply", "Fresh reply"),
            service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text },
        )
        assertEquals("turn-2", service.state.value.activeTurnIdByThread["thread-1"])
        assertTrue("thread-1" in service.state.value.runningThreadIds)
    }

    @Test
    fun createThread_reopeningClosedEmptyThreadSkipsHostHydration() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.createThread()
        advanceUntilIdle()
        service.closeThread()

        service.openThread("thread-created")
        advanceUntilIdle()

        assertTrue(service.state.value.timelineByThread["thread-created"].orEmpty().isEmpty())
        assertTrue("thread-created" in service.state.value.hydratedThreadIds)
        assertEquals(emptyList<String>(), repository.loadedThreadIds)
    }

    @Test
    fun createThread_reselectingFreshEmptyThreadSkipsHostHydration() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.createThread()
        advanceUntilIdle()

        service.openThread("thread-created")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), repository.loadedThreadIds)
        assertTrue(service.state.value.timelineByThread["thread-created"].orEmpty().isEmpty())
        assertTrue("thread-created" in service.state.value.hydratedThreadIds)
    }

    @Test
    fun openThread_switchingBackToHydratedIdleThreadSkipsReload() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResultsByThreadId["thread-a"] = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Thread A", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-a",
                        threadId = "thread-a",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Reply A",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-a",
                        itemId = "assistant-a",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            loadThreadResultsByThreadId["thread-b"] = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Thread B", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-b",
                        threadId = "thread-b",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Reply B",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-b",
                        itemId = "assistant-b",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-b",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Thread A", null, null, 1_000L, 1_000L),
                    ThreadSummary("thread-b", "Thread B", null, null, 1_000L, 1_000L),
                )
            )
        )

        service.openThread("thread-a")
        advanceUntilIdle()

        service.openThread("thread-b")
        advanceUntilIdle()

        service.openThread("thread-a")
        advanceUntilIdle()

        assertEquals(listOf("thread-a", "thread-b"), repository.loadedThreadIds)
        assertEquals(listOf("Reply A"), service.state.value.timelineByThread["thread-a"].orEmpty().map { it.text })
        assertEquals("thread-a", service.state.value.selectedThreadId)
    }

    @Test
    fun activateWorkspace_clearsHydratedTimelineBeforeReopeningSameThreadId() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        repository.refreshedThreads = listOf(
            ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null),
        )
        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null),
            messages = listOf(
                ConversationMessage(
                    id = "history-assistant-fresh",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Fresh reply",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-2",
                    itemId = "assistant-2",
                )
            ),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-2",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )

        service.activateWorkspace("/tmp/project-b")
        advanceUntilIdle()

        assertTrue(service.state.value.timelineByThread.isEmpty())
        assertTrue(service.state.value.hydratedThreadIds.isEmpty())

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_workspaceSwitchClearsThreadScopedStateBeforeReusingThreadId() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-fresh",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Fresh reply",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-2",
                        itemId = "assistant-2",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-2",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadLoaded(
                thread = ThreadSummary("thread-1", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
            )
        )
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null))
            )
        )
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("/tmp/project-b"), repository.activatedWorkspaces)
        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
        assertEquals(emptyMap<String, Map<String, ToolUserInputRequest>>(), service.state.value.pendingToolInputsByThread)
    }

    @Test
    fun openThread_workspaceSwitchClearsRunningStateForReusedThreadId() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadDelayMs = 1_000L
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null))
            )
        )
        service.processClientUpdate(ClientUpdate.TurnStarted("thread-1", "turn-stale"))
        advanceUntilIdle()

        val openJob = backgroundScope.launch { service.openThread("thread-1") }
        runCurrent()

        val interruptFailure = runCatching { service.interruptThread("thread-1") }.exceptionOrNull()
        advanceUntilIdle()
        openJob.join()

        assertEquals("No active run is available to stop.", interruptFailure?.message)
        assertTrue(repository.interruptedTurns.isEmpty())
        assertEquals(listOf("thread-1"), repository.readThreadRunSnapshotIds)
    }

    @Test
    fun openThread_workspaceSwitchClearsPendingToolInputsForReusedThreadId() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Workspace B", null, "/tmp/project-b", null, null))
            )
        )
        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-1",
                    method = "item/tool/requestUserInput",
                    threadId = "thread-1",
                    turnId = "turn-a",
                    itemId = "item-a",
                    title = "Pick a branch",
                    message = "Select which branch should be inspected.",
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
            )
        )
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.respondToToolUserInput("thread-1", "request-1", mapOf("branch" to "main"))

        assertTrue(service.state.value.pendingToolInputsByThread["thread-1"].isNullOrEmpty())
        assertNull(repository.lastToolInputRequest)
        assertNull(repository.lastToolInputResponse)
    }

    @Test
    fun openThread_workspaceSwitchLoadFailureRestoresPreviousSelection() = runTest {
        val existingMessage = ConversationMessage(
            id = "history-assistant-existing",
            threadId = "thread-a",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Existing reply",
            createdAtEpochMs = 1_000L,
            turnId = "turn-a",
            itemId = "assistant-a",
        )
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(existingMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.openThread("thread-a")
        advanceUntilIdle()

        repository.loadThreadError = IllegalStateException("temporary load failure")

        val error = runCatching { service.openThread("thread-b") }.exceptionOrNull()
        advanceUntilIdle()

        assertEquals("temporary load failure", error?.message)
        assertEquals(listOf("/tmp/project-b", "/tmp/project-a"), repository.activatedWorkspaces)
        assertEquals("/tmp/project-a", service.state.value.activeWorkspacePath)
        assertEquals("thread-a", service.state.value.selectedThreadId)
        assertEquals(
            listOf("Existing reply"),
            service.state.value.timelineByThread["thread-a"].orEmpty().map { it.text },
        )
        assertTrue(service.state.value.timelineByThread["thread-b"].isNullOrEmpty())
    }

    @Test
    fun openThread_workspaceActivationFailureRestoresPreviousSelection() = runTest {
        val existingMessage = ConversationMessage(
            id = "history-assistant-existing",
            threadId = "thread-a",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Existing reply",
            createdAtEpochMs = 1_000L,
            turnId = "turn-a",
            itemId = "assistant-a",
        )
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(existingMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.openThread("thread-a")
        advanceUntilIdle()

        repository.activateWorkspaceErrorsByPath["/tmp/project-b"] = IllegalStateException("workspace offline")

        val error = runCatching { service.openThread("thread-b") }.exceptionOrNull()
        advanceUntilIdle()

        assertEquals("workspace offline", error?.message)
        assertEquals(listOf("/tmp/project-b"), repository.activatedWorkspaces)
        assertEquals("/tmp/project-a", service.state.value.activeWorkspacePath)
        assertEquals("thread-a", service.state.value.selectedThreadId)
        assertEquals(
            listOf("Existing reply"),
            service.state.value.timelineByThread["thread-a"].orEmpty().map { it.text },
        )
        assertEquals(listOf("thread-a"), repository.loadedThreadIds)
    }

    @Test
    fun openThread_workspaceSwitchRollbackFailureClearsSelectionInsteadOfRestoringOldWorkspaceState() = runTest {
        val existingMessage = ConversationMessage(
            id = "history-assistant-existing",
            threadId = "thread-a",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Existing reply",
            createdAtEpochMs = 1_000L,
            turnId = "turn-a",
            itemId = "assistant-a",
        )
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(existingMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.openThread("thread-a")
        advanceUntilIdle()

        repository.activateWorkspaceErrorsByPath["/tmp/project-a"] = IllegalStateException("rollback failed")
        repository.loadThreadError = IllegalStateException("temporary load failure")

        val error = runCatching { service.openThread("thread-b") }.exceptionOrNull()
        advanceUntilIdle()

        assertEquals("temporary load failure", error?.message)
        assertEquals(listOf("/tmp/project-b", "/tmp/project-a"), repository.activatedWorkspaces)
        assertEquals("/tmp/project-b", service.state.value.activeWorkspacePath)
        assertNull(service.state.value.selectedThreadId)
        assertTrue(service.state.value.timelineByThread.isEmpty())
    }

    @Test
    fun openThread_workspaceSwitchLoadFailureRehydratesPreviousThreadWhenLoadWasInterrupted() = runTest {
        val existingMessage = ConversationMessage(
            id = "history-assistant-existing",
            threadId = "thread-a",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Existing reply",
            createdAtEpochMs = 1_000L,
            turnId = "turn-a",
            itemId = "assistant-a",
        )
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadDelayMs = 1_000L
            loadThreadResultsByThreadId["thread-a"] = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(existingMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            loadThreadErrorsByThreadId["thread-b"] = IllegalStateException("temporary load failure")
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )

        val initialOpen = backgroundScope.launch { service.openThread("thread-a") }
        runCurrent()

        val error = runCatching { service.openThread("thread-b") }.exceptionOrNull()
        advanceUntilIdle()
        initialOpen.join()

        assertEquals("temporary load failure", error?.message)
        assertEquals(listOf("/tmp/project-b", "/tmp/project-a"), repository.activatedWorkspaces)
        assertEquals(listOf("thread-a", "thread-b", "thread-a"), repository.loadedThreadIds)
        assertEquals("thread-a", service.state.value.selectedThreadId)
        assertEquals(
            listOf("Existing reply"),
            service.state.value.timelineByThread["thread-a"].orEmpty().map { it.text },
        )
        assertTrue("thread-a" in service.state.value.hydratedThreadIds)
    }

    @Test
    fun openThread_workspaceSwitchFailureDoesNotRollbackSupersedingSelection() = runTest {
        val existingMessage = ConversationMessage(
            id = "history-assistant-existing",
            threadId = "thread-a",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Existing reply",
            createdAtEpochMs = 1_000L,
            turnId = "turn-a",
            itemId = "assistant-a",
        )
        val newestMessage = ConversationMessage(
            id = "history-assistant-newest",
            threadId = "thread-c",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Newest reply",
            createdAtEpochMs = 2_000L,
            turnId = "turn-c",
            itemId = "assistant-c",
        )
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                    WorkspacePathSummary("/tmp/project-c", "Project C", false),
                )
            )
            loadThreadDelayMs = 1_000L
            loadThreadResultsByThreadId["thread-a"] = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(existingMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            loadThreadErrorsByThreadId["thread-b"] = IllegalStateException("temporary load failure")
            loadThreadResultsByThreadId["thread-c"] = ThreadLoadResult(
                thread = ThreadSummary("thread-c", "Workspace C", null, "/tmp/project-c", null, null),
                messages = listOf(newestMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-c",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                    ThreadSummary("thread-c", "Workspace C", null, "/tmp/project-c", null, null),
                )
            )
        )
        service.openThread("thread-a")
        advanceUntilIdle()

        var failingOpenError: Throwable? = null
        val failingOpen = backgroundScope.launch {
            failingOpenError = runCatching { service.openThread("thread-b") }.exceptionOrNull()
        }
        runCurrent()

        var supersedingOpenError: Throwable? = null
        val supersedingOpen = backgroundScope.launch {
            supersedingOpenError = runCatching { service.openThread("thread-c") }.exceptionOrNull()
        }
        runCurrent()

        advanceUntilIdle()
        failingOpen.join()
        supersedingOpen.join()

        assertEquals("temporary load failure", failingOpenError?.message)
        assertNull(supersedingOpenError)
        assertEquals("/tmp/project-c", repository.activatedWorkspaces.last())
        assertEquals("/tmp/project-c", service.state.value.activeWorkspacePath)
        assertEquals("thread-c", service.state.value.selectedThreadId)
        assertEquals(
            listOf("Newest reply"),
            service.state.value.timelineByThread["thread-c"].orEmpty().map { it.text },
        )
        assertTrue(service.state.value.timelineByThread["thread-a"].isNullOrEmpty())
    }

    @Test
    fun openThread_workspaceSwitchClearsStaleSubagentIdentityCache() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-b",
                        title = "Workspace B",
                        preview = null,
                        cwd = "/tmp/project-b",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                    ),
                    ThreadSummary(
                        id = "thread-child",
                        title = "Scout",
                        preview = null,
                        cwd = "/tmp/project-a",
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                        parentThreadId = "thread-a",
                        agentNickname = "Scout",
                        agentRole = "explorer",
                    )
                )
            )
        )
        service.processClientUpdate(
            ClientUpdate.SubagentActionUpdate(
                threadId = "thread-a",
                turnId = "turn-a",
                itemId = null,
                action = SubagentAction(
                    tool = "spawnAgent",
                    status = "completed",
                    prompt = "Inspect Android tests",
                    model = "gpt-5.4-mini",
                    receiverThreadIds = listOf("thread-child"),
                ),
                isStreaming = false,
            )
        )
        advanceUntilIdle()

        assertEquals(
            "Scout",
            service.state.value.timelineByThread["thread-a"]
                .orEmpty()
                .single()
                .subagentAction
                ?.agentRows
                ?.single()
                ?.nickname,
        )

        service.openThread("thread-b")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.SubagentActionUpdate(
                threadId = "thread-b",
                turnId = "turn-b",
                itemId = null,
                action = SubagentAction(
                    tool = "spawnAgent",
                    status = "completed",
                    prompt = "Inspect Android tests",
                    model = "gpt-5.4-mini",
                    receiverThreadIds = listOf("thread-child"),
                ),
                isStreaming = false,
            )
        )
        advanceUntilIdle()

        val reusedAgent = service.state.value.timelineByThread["thread-b"]
            .orEmpty()
            .single { it.kind == ConversationKind.SUBAGENT_ACTION }
            .subagentAction
            ?.agentRows
            ?.single()
        assertNull(reusedAgent?.nickname)
        assertNull(reusedAgent?.role)
    }

    @Test
    fun openThread_workspaceSwitchClearsBackgroundRunFallbackForPreviousThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.processClientUpdate(ClientUpdate.TurnStarted("thread-a", null))
        advanceUntilIdle()

        service.openThread("thread-b")
        advanceUntilIdle()

        assertFalse("thread-a" in service.state.value.runningThreadIds)
        assertFalse("thread-a" in service.state.value.protectedRunningFallbackThreadIds)

        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = null,
                turnId = null,
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        advanceUntilIdle()

        assertFalse("thread-a" in service.state.value.runningThreadIds)
        assertFalse("thread-a" in service.state.value.protectedRunningFallbackThreadIds)
        assertFalse("thread-a" in service.state.value.readyThreadIds)
    }

    @Test
    fun openThread_workspaceSwitchClearsReadyAndFailedBadgesForReusedThreadIds() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                    ThreadSummary("thread-c", "Workspace A Failure", null, "/tmp/project-a", null, null),
                )
            )
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-a",
                turnId = "turn-a",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-c",
                turnId = "turn-c",
                terminalState = TurnTerminalState.FAILED,
            )
        )
        advanceUntilIdle()

        service.openThread("thread-b")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace B Ready", null, "/tmp/project-b", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                    ThreadSummary("thread-c", "Workspace B Failed", null, "/tmp/project-b", null, null),
                )
            )
        )
        advanceUntilIdle()

        assertFalse("thread-a" in service.state.value.readyThreadIds)
        assertFalse("thread-c" in service.state.value.failedThreadIds)
    }

    @Test
    fun openThread_workspaceSwitchIgnoresLateDeltaForPreviousWorkspaceThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-a",
                turnId = "turn-a",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        advanceUntilIdle()

        service.openThread("thread-b")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ReasoningDelta(
                threadId = "thread-a",
                turnId = "turn-a",
                itemId = "reasoning-a",
                delta = "Late reasoning chunk",
            )
        )

        val messages = service.state.value.timelineByThread["thread-a"].orEmpty()
        assertTrue(messages.none { it.kind == ConversationKind.THINKING })
        assertFalse("thread-a" in service.state.value.runningThreadIds)
        assertFalse("thread-a" in service.state.value.protectedRunningFallbackThreadIds)
    }

    @Test
    fun activeWorkspace_acceptsNewThreadUpdatesBeforeThreadListRefresh() = runTest {
        val existingThread = ThreadSummary("thread-existing", "Existing", null, "/tmp/project-a", null, null)
        val newThread = ThreadSummary("thread-new", "New", null, "/tmp/project-a", null, null)
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                )
            )
            refreshedThreads = listOf(existingThread, newThread)
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(ClientUpdate.ThreadsLoaded(listOf(existingThread)))
        advanceUntilIdle()

        val request = ToolUserInputRequest(
            idValue = "request-new",
            method = "item/tool/requestUserInput",
            threadId = "thread-new",
            turnId = "turn-new",
            itemId = "item-new",
            title = "Choose a branch",
            message = "Select a branch for the new thread.",
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

        service.processClientUpdate(ClientUpdate.ToolUserInputRequested(request))
        service.processClientUpdate(ClientUpdate.AssistantDelta("thread-new", "turn-new", "item-new", "Hello"))

        assertEquals(
            listOf("request-new"),
            service.state.value.pendingToolInputsByThread["thread-new"]?.keys?.toList(),
        )
        assertEquals(listOf("Hello"), service.state.value.timelineByThread["thread-new"].orEmpty().map { it.text })
        assertTrue("thread-new" in service.state.value.runningThreadIds)
        assertNull(repository.lastRejectedToolInputRequest)
    }

    @Test
    fun openThread_workspaceSwitchIgnoresLateTokenUsageForPreviousWorkspaceThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        advanceUntilIdle()

        service.openThread("thread-b")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.TokenUsageUpdated(
                threadId = "thread-a",
                usage = io.androdex.android.model.ThreadTokenUsage(
                    tokensUsed = 1200,
                    tokenLimit = 32000,
                ),
            )
        )
        service.processClientUpdate(
            ClientUpdate.AssistantDelta(
                threadId = "thread-a",
                turnId = "turn-a",
                itemId = "item-a",
                delta = "Late assistant chunk",
            )
        )

        assertNull(service.state.value.tokenUsageByThread["thread-a"])
        assertTrue(service.state.value.timelineByThread["thread-a"].isNullOrEmpty())
    }

    @Test
    fun activateWorkspace_ignoresLateExplicitUpdateForPreviousWorkspaceThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            refreshedThreads = listOf(
                ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.activateWorkspace("/tmp/project-b")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.AssistantDelta(
                threadId = "thread-a",
                turnId = "turn-a",
                itemId = "assistant-a",
                delta = "Late assistant chunk",
            )
        )

        assertTrue(service.state.value.timelineByThread["thread-a"].isNullOrEmpty())
        assertFalse("thread-a" in service.state.value.runningThreadIds)
        assertFalse("thread-a" in service.state.value.protectedRunningFallbackThreadIds)
    }

    @Test
    fun toolUserInputRequests_forPreviousWorkspaceAreRejectedAfterSwitch() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.openThread("thread-b")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-1",
                    method = "item/tool/requestUserInput",
                    threadId = "thread-a",
                    turnId = "turn-a",
                    itemId = "item-a",
                    title = "Pick a branch",
                    message = "Select which branch should be inspected.",
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
            )
        )
        advanceUntilIdle()

        assertTrue(service.state.value.pendingToolInputsByThread.isEmpty())
        assertEquals(
            "Structured tool input no longer matches the active workspace. Reopen the relevant thread and retry from the host.",
            service.state.value.errorMessage,
        )
        assertEquals("request-1", repository.lastRejectedToolInputRequest?.requestId)
        assertEquals(
            "Structured tool input thread id does not belong to the active workspace context.",
            repository.lastRejectedToolInputMessage,
        )
    }

    @Test
    fun connectWithPairingPayload_clearsHydratedTimelineBeforeReopeningSameThreadId() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Old host", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        repository.refreshedThreads = listOf(
            ThreadSummary("thread-1", "New host", null, null, null, null),
        )
        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-1", "New host", null, null, null, null),
            messages = listOf(
                ConversationMessage(
                    id = "history-assistant-fresh",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Fresh reply",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-2",
                    itemId = "assistant-2",
                )
            ),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-2",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )

        service.connectWithPairingPayload("fresh-payload")
        advanceUntilIdle()

        assertTrue(service.state.value.timelineByThread.isEmpty())
        assertTrue(service.state.value.hydratedThreadIds.isEmpty())

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun connectWithPairingPayload_preservesPendingNotificationTargetUntilReconnectCompletes() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-pending", "Pending thread", null, null, null, null),
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-pending", "Pending thread", null, null, null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.handleNotificationOpen("thread-pending", "turn-7")
        advanceUntilIdle()

        assertEquals("thread-pending", service.state.value.pendingNotificationOpenThreadId)

        service.connectWithPairingPayload("fresh-payload")
        advanceUntilIdle()

        assertNull(service.state.value.pendingNotificationOpenThreadId)
        assertEquals("thread-pending", service.state.value.selectedThreadId)
        assertEquals("turn-7", service.state.value.focusedTurnId)
        assertEquals(listOf("thread-pending"), repository.loadedThreadIds)

        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        assertNull(service.state.value.pendingNotificationOpenThreadId)
        assertEquals("thread-pending", service.state.value.selectedThreadId)
        assertEquals("turn-7", service.state.value.focusedTurnId)
        assertEquals(listOf("thread-pending"), repository.loadedThreadIds)
    }

    @Test
    fun openThread_reconnectWhileLoadInFlightDoesNotReuseStaleHydration() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
            )
            loadThreadDelayMs = 1_000L
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-fresh",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Fresh reply",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-2",
                        itemId = "assistant-2",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-2",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 1_000L))
            )
        )

        val initialOpen = backgroundScope.launch { service.openThread("thread-1") }
        runCurrent()
        service.closeThread()

        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.RECONNECT_REQUIRED))
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()
        initialOpen.join()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_reloadsHydratedRunningThreadToCatchUp() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Latest reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = "turn-1",
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = true,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertTrue("thread-1" in service.state.value.runningThreadIds)
    }

    @Test
    fun openThread_deduplicatesConcurrentHydrationForSameThread() = runTest {
        val repository = FakeRepository().apply {
            loadThreadDelayMs = 1_000L
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Hydrated once",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        val firstOpen = backgroundScope.launch { service.openThread("thread-1") }
        val secondOpen = backgroundScope.launch { service.openThread("thread-1") }
        runCurrent()
        advanceUntilIdle()
        firstOpen.join()
        secondOpen.join()

        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Hydrated once"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_forceRefreshWhileLoadInFlightPerformsFollowUpRead() = runTest {
        val repository = FakeRepository().apply {
            loadThreadDelayMs = 1_000L
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-fresh",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Fresh reply",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-2",
                        itemId = "assistant-2",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-2",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        val firstOpen = backgroundScope.launch { service.openThread("thread-1") }
        runCurrent()

        val forcedRefresh = backgroundScope.launch { service.openThread("thread-1", forceRefresh = true) }
        advanceUntilIdle()
        firstOpen.join()
        forcedRefresh.join()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_forceRefreshRetriesAfterInheritedLoadFailure() = runTest {
        val repository = FakeRepository().apply {
            loadThreadDelayMs = 1_000L
            queuedLoadThreadErrors += IllegalStateException("connection dropped")
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-fresh",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Fresh reply",
                        createdAtEpochMs = 2_000L,
                        turnId = "turn-2",
                        itemId = "assistant-2",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-2",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        var initialOpenError: Throwable? = null
        val initialOpen = backgroundScope.launch {
            initialOpenError = runCatching { service.openThread("thread-1") }.exceptionOrNull()
        }
        runCurrent()

        val refreshError = runCatching { service.openThread("thread-1", forceRefresh = true) }.exceptionOrNull()
        advanceUntilIdle()
        initialOpen.join()

        assertEquals("connection dropped", initialOpenError?.message)
        assertNull(refreshError)
        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun refreshThreads_invalidatesHydrationWhenSummaryUpdatedAtAdvances() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        repository.refreshedThreads = listOf(
            ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
        )
        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
            messages = listOf(
                ConversationMessage(
                    id = "history-assistant-fresh",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Fresh reply",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-2",
                    itemId = "assistant-2",
                )
            ),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-2",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )

        service.refreshThreads()
        advanceUntilIdle()

        assertFalse("thread-1" in service.state.value.hydratedThreadIds)

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun refreshThreads_invalidatesHydrationWhenSummaryHasNoVersion() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        repository.refreshedThreads = listOf(
            ThreadSummary("thread-1", "Conversation", null, null, null, null),
        )
        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
            messages = listOf(
                ConversationMessage(
                    id = "history-assistant-fresh",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Fresh reply",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-2",
                    itemId = "assistant-2",
                )
            ),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-2",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )

        service.refreshThreads()
        advanceUntilIdle()

        assertFalse("thread-1" in service.state.value.hydratedThreadIds)

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun refreshThreads_invalidatesHydrationWhenSummaryHasOnlyCreatedAt() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Cached reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()
        service.closeThread()

        repository.refreshedThreads = listOf(
            ThreadSummary("thread-1", "Conversation", null, null, 1_000L, null),
        )
        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, null),
            messages = listOf(
                ConversationMessage(
                    id = "history-assistant-fresh",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Fresh reply",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-2",
                    itemId = "assistant-2",
                )
            ),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = "turn-2",
                latestTurnTerminalState = TurnTerminalState.COMPLETED,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )

        service.refreshThreads()
        advanceUntilIdle()

        assertFalse("thread-1" in service.state.value.hydratedThreadIds)

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Fresh reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun openThread_secondForceRefreshWhileForceLoadInFlightPerformsAdditionalRead() = runTest {
        val repository = FakeRepository().apply {
            loadThreadDelayMs = 1_000L
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 1_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-stale",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Stale reply",
                        createdAtEpochMs = 1_000L,
                        turnId = "turn-1",
                        itemId = "assistant-1",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            queuedLoadThreadResults += ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, 1_000L, 2_000L),
                messages = listOf(
                    ConversationMessage(
                        id = "history-assistant-latest",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Latest reply",
                        createdAtEpochMs = 3_000L,
                        turnId = "turn-3",
                        itemId = "assistant-3",
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-3",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        val firstRefresh = backgroundScope.launch { service.openThread("thread-1", forceRefresh = true) }
        runCurrent()

        val secondRefresh = backgroundScope.launch { service.openThread("thread-1", forceRefresh = true) }
        advanceUntilIdle()
        firstRefresh.join()
        secondRefresh.join()

        assertEquals(listOf("thread-1", "thread-1"), repository.loadedThreadIds)
        assertEquals(listOf("Latest reply"), service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text })
    }

    @Test
    fun refreshThreads_marksListLoadedEvenWhenRepositoryReturnsEmptyWithoutUpdate() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.refreshThreads()
        advanceUntilIdle()

        assertTrue(service.state.value.hasLoadedThreadList)
        assertTrue(service.state.value.threads.isEmpty())
    }

    @Test
    fun refreshThreads_replacesExistingThreadsWhenRepositoryLaterReturnsEmpty() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Existing", null, "/tmp/project", null, null),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.refreshThreads()
        advanceUntilIdle()
        assertEquals(listOf("thread-1"), service.state.value.threads.map { it.id })

        repository.refreshedThreads = emptyList()
        service.refreshThreads()
        advanceUntilIdle()

        assertTrue(service.state.value.hasLoadedThreadList)
        assertTrue(service.state.value.threads.isEmpty())
    }

    @Test
    fun createThread_keepsSelectedThreadWhenBackgroundRefreshFails() = runTest {
        val repository = FakeRepository().apply {
            refreshThreadsError = IllegalStateException("Timed out waiting for 30000 ms")
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project",
                recentWorkspaces = listOf(WorkspacePathSummary("/tmp/project", "project", true))
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        advanceUntilIdle()
        service.createThread()
        advanceUntilIdle()

        assertEquals("thread-created", service.state.value.selectedThreadId)
        assertEquals("Conversation", service.state.value.selectedThreadTitle)
        assertTrue(service.state.value.timelineByThread.containsKey("thread-created"))
    }

    @Test
    fun createThread_activatesExplicitWorkspaceBeforeStartingThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "project-a", true),
                    WorkspacePathSummary("/tmp/project-b", "project-b", false),
                )
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        advanceUntilIdle()
        service.createThread("/tmp/project-b")
        advanceUntilIdle()

        assertEquals(listOf("/tmp/project-b"), repository.activatedWorkspaces)
        assertEquals(listOf("/tmp/project-b"), repository.startedThreadCwds)
        assertEquals("/tmp/project-b", service.state.value.activeWorkspacePath)
        assertEquals("thread-created", service.state.value.selectedThreadId)
    }

    @Test
    fun sendMessage_createsAndUsesThreadWhenBackgroundRefreshFails() = runTest {
        val repository = FakeRepository().apply {
            refreshThreadsError = IllegalStateException("Timed out waiting for 30000 ms")
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project",
                recentWorkspaces = listOf(WorkspacePathSummary("/tmp/project", "project", true))
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        advanceUntilIdle()
        service.sendMessage("Ship it", preferredThreadId = null)
        advanceUntilIdle()

        assertEquals(listOf("thread-created:Ship it"), repository.startedTurns)
        assertEquals("thread-created", service.state.value.selectedThreadId)
        assertEquals("Conversation", service.state.value.selectedThreadTitle)
    }

    @Test
    fun refreshThreads_marksExistingThreadListAsLoadingDuringRefresh() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Existing", null, "/tmp/project", null, null),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.refreshThreads()
        advanceUntilIdle()

        repository.refreshThreadsDelayMs = 10
        val refreshJob = backgroundScope.launch {
            service.refreshThreads()
        }
        runCurrent()

        assertTrue(service.state.value.hasLoadedThreadList)
        assertEquals(listOf("thread-1"), service.state.value.threads.map { it.id })
        assertTrue(service.state.value.isLoadingThreadList)

        advanceUntilIdle()
        refreshJob.join()

        assertFalse(service.state.value.isLoadingThreadList)
    }

    @Test
    fun backgroundThreadRefresh_waitsUntilSelectedThreadLoadFinishes() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Selected", null, null, null, null),
                ThreadSummary("thread-2", "Background", null, null, null, null),
            )
            loadThreadDelayMs = 1_000L
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Selected", null, null, null, null),
                messages = emptyList(),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-selected",
                    latestTurnTerminalState = null,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-1", "Selected", null, null, null, null),
                    ThreadSummary("thread-2", "Background", null, null, null, null),
                )
            )
        )
        advanceUntilIdle()

        val openJob = backgroundScope.launch {
            service.openThread("thread-1")
        }
        runCurrent()

        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-2",
                turnId = "turn-2",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        runCurrent()

        assertEquals(0, repository.refreshThreadsCalls)

        advanceUntilIdle()
        openJob.join()
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertEquals(1, repository.refreshThreadsCalls)
        assertFalse(repository.refreshThreadsObservedWhileLoadThreadInFlight)
    }

    @Test
    fun explicitRefresh_waitsForSelectedThreadLoadInsteadOfOverlappingHostCalls() = runTest {
        val repository = FakeRepository().apply {
            refreshedThreads = listOf(ThreadSummary("thread-1", "Selected", null, null, null, null))
            loadThreadDelayMs = 1_000L
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Selected", null, null, null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(listOf(ThreadSummary("thread-1", "Selected", null, null, null, null)))
        )
        advanceUntilIdle()

        val openJob = backgroundScope.launch {
            service.openThread("thread-1")
        }
        runCurrent()

        val refreshJob = backgroundScope.launch {
            service.refreshThreads()
        }
        runCurrent()

        assertEquals(0, repository.refreshThreadsCalls)

        advanceUntilIdle()
        openJob.join()
        refreshJob.join()
        advanceUntilIdle()

        assertEquals(1, repository.refreshThreadsCalls)
        assertFalse(repository.refreshThreadsObservedWhileLoadThreadInFlight)
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
    fun commandExecutionUpdates_keepDistinctCommandsWhenItemIdsAreMissing() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "npm test",
                status = "running",
                text = "Running: npm test",
                isStreaming = true,
            )
        )
        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "git status",
                status = "completed",
                text = "Completed: git status",
                isStreaming = false,
            )
        )

        val commands = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.COMMAND }
        assertEquals(2, commands.size)
        assertEquals(listOf("npm test", "git status"), commands.map { it.command })
    }

    @Test
    fun commandExecutionUpdates_mergeSameCommandAcrossStreamingAndCompletion() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "npm test",
                status = "running",
                text = "Running: npm test",
                isStreaming = true,
            )
        )
        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "npm test",
                status = "completed",
                text = "Completed: npm test",
                isStreaming = false,
            )
        )

        val command = service.state.value.timelineByThread["thread-1"].orEmpty()
            .single { it.kind == ConversationKind.COMMAND }
        assertEquals("Completed: npm test", command.text)
        assertEquals("completed", command.status)
        assertFalse(command.isStreaming)
    }

    @Test
    fun commandExecutionUpdates_keepDistinctSameCommandRerunsWhenItemIdsAreMissing() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "npm test",
                status = "completed",
                text = "Completed: npm test",
                isStreaming = false,
            )
        )
        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "npm test",
                status = "running",
                text = "Running: npm test",
                isStreaming = true,
            )
        )

        val commands = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.COMMAND }
        assertEquals(2, commands.size)
        assertEquals(listOf("Completed: npm test", "Running: npm test"), commands.map { it.text })
    }

    @Test
    fun executionUpdates_mergeStructuredRowsByItemId() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "review-1",
                text = "Running: Review current changes",
                isStreaming = true,
                execution = ExecutionContent(
                    kind = ExecutionKind.REVIEW,
                    title = "Review current changes",
                    status = "running",
                    summary = "Inspecting uncommitted changes",
                ),
            )
        )
        service.processClientUpdate(
            ClientUpdate.ExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "review-1",
                text = "Completed: Review current changes",
                isStreaming = false,
                execution = ExecutionContent(
                    kind = ExecutionKind.REVIEW,
                    title = "Review current changes",
                    status = "completed",
                    summary = "Found two issues",
                ),
            )
        )

        val executions = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.EXECUTION }
        assertEquals(1, executions.size)
        val review = executions.single()
        assertEquals("review-1", review.itemId)
        assertEquals("completed", review.status)
        assertEquals("Found two issues", review.execution?.summary)
        assertFalse(review.isStreaming)
    }

    @Test
    fun executionUpdates_mergeReloadedTerminalReviewRowsWithoutItemIds() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-review",
                        threadId = "thread-1",
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.EXECUTION,
                        text = "Completed: Review current changes",
                        createdAtEpochMs = System.currentTimeMillis() + 1_000L,
                        turnId = "turn-1",
                        itemId = null,
                        isStreaming = false,
                        status = "completed",
                        execution = ExecutionContent(
                            kind = ExecutionKind.REVIEW,
                            title = "Review current changes",
                            status = "completed",
                            summary = "Found one issue",
                        ),
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                text = "Completed: Review current changes",
                isStreaming = false,
                execution = ExecutionContent(
                    kind = ExecutionKind.REVIEW,
                    title = "Review current changes",
                    status = "completed",
                    summary = "Found one issue",
                ),
            )
        )
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        val executions = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.EXECUTION }
        assertEquals(1, executions.size)
        assertEquals("Found one issue", executions.single().execution?.summary)
    }

    @Test
    fun executionUpdates_keepDistinctReloadedTerminalRerunsWithoutItemIds() = runTest {
        val now = System.currentTimeMillis()
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-review-1",
                        threadId = "thread-1",
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.EXECUTION,
                        text = "Completed: Review current changes",
                        createdAtEpochMs = now + 1_000L,
                        turnId = "turn-1",
                        itemId = null,
                        isStreaming = false,
                        status = "completed",
                        execution = ExecutionContent(
                            kind = ExecutionKind.REVIEW,
                            title = "Review current changes",
                            status = "completed",
                            summary = "First pass",
                        ),
                    ),
                    ConversationMessage(
                        id = "history-review-2",
                        threadId = "thread-1",
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.EXECUTION,
                        text = "Completed: Review current changes",
                        createdAtEpochMs = now + 2_000L,
                        turnId = "turn-1",
                        itemId = null,
                        isStreaming = false,
                        status = "completed",
                        execution = ExecutionContent(
                            kind = ExecutionKind.REVIEW,
                            title = "Review current changes",
                            status = "completed",
                            summary = "Second pass",
                        ),
                    ),
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                text = "Completed: Review current changes",
                isStreaming = false,
                execution = ExecutionContent(
                    kind = ExecutionKind.REVIEW,
                    title = "Review current changes",
                    status = "completed",
                    summary = "First pass",
                ),
            )
        )
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        val executions = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.EXECUTION }
        assertEquals(2, executions.size)
        assertEquals(listOf("First pass", "Second pass"), executions.map { it.execution?.summary })
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
    fun notificationOpen_staysPendingUntilThreadCanBeLoaded() = runTest {
        val repository = FakeRepository().apply {
            loadThreadError = IllegalStateException("thread not found")
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.handleNotificationOpen("thread-pending", "turn-1")
        advanceUntilIdle()

        assertEquals("thread-pending", service.state.value.pendingNotificationOpenThreadId)
        assertNull(service.state.value.selectedThreadId)

        repository.loadThreadError = null
        repository.loadThreadResult = ThreadLoadResult(
            thread = ThreadSummary("thread-pending", "Pending thread", null, null, null, null),
            messages = emptyList(),
            runSnapshot = ThreadRunSnapshot(
                interruptibleTurnId = null,
                hasInterruptibleTurnWithoutId = false,
                latestTurnId = null,
                latestTurnTerminalState = null,
                shouldAssumeRunningFromLatestTurn = false,
            ),
        )
        repository.refreshedThreads = listOf(
            ThreadSummary("thread-pending", "Pending thread", null, null, null, null)
        )
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-pending", "Pending thread", null, null, null, null))
            )
        )
        advanceUntilIdle()
        service.routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
        advanceUntilIdle()

        assertEquals("thread-pending", service.state.value.pendingNotificationOpenThreadId)
        assertNull(service.state.value.selectedThreadId)
        assertNull(service.state.value.missingNotificationThreadPrompt)
    }

    @Test
    fun notificationOpen_activatesTargetWorkspaceBeforeLoadingThread() = runTest {
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "D:\\Client\\SiteB",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("D:\\Client\\SiteB", "SiteB", true),
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", false),
                )
            )
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Thread 1", null, "C:\\Projects\\AppA", null, null)
            )
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Thread 1", null, "C:\\Projects\\AppA", null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, "C:\\Projects\\AppA", null, null))
            )
        )
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        service.handleNotificationOpen("thread-1", "turn-7")
        advanceUntilIdle()
        service.routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
        advanceUntilIdle()

        assertEquals(listOf("C:\\Projects\\AppA"), repository.activatedWorkspaces)
        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
        assertEquals("turn-7", service.state.value.focusedTurnId)

        service.consumeFocusedTurnId("thread-1")

        assertNull(service.state.value.focusedTurnId)
    }

    @Test
    fun notificationOpen_showsMissingThreadPromptAndFallsBackToLiveThread() = runTest {
        val repository = FakeRepository().apply {
            loadThreadError = IllegalStateException("thread not found")
            refreshedThreads = listOf(
                ThreadSummary("thread-deleted", "Deleted", null, null, null, null),
                ThreadSummary("thread-live", "Live", null, null, null, null),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-deleted", "Deleted", null, null, null, null),
                    ThreadSummary("thread-live", "Live", null, null, null, null),
                )
            )
        )
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        service.handleNotificationOpen("thread-deleted", null)
        advanceUntilIdle()
        service.routePendingNotificationOpenIfPossible()
        advanceUntilIdle()

        assertNull(service.state.value.pendingNotificationOpenThreadId)
        assertEquals("thread-live", service.state.value.selectedThreadId)
        assertEquals("thread-deleted", service.state.value.missingNotificationThreadPrompt?.threadId)
    }

    @Test
    fun notificationOpen_genericNotFoundErrorStaysUnavailable() = runTest {
        val repository = FakeRepository().apply {
            loadThreadError = IllegalStateException("File not found in current workspace")
            recentState = WorkspaceRecentState(
                activeCwd = "D:\\Client\\SiteB",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("D:\\Client\\SiteB", "SiteB", true),
                    WorkspacePathSummary("C:\\Projects\\AppA", "AppA", false),
                )
            )
            refreshedThreads = listOf(
                ThreadSummary("thread-1", "Thread 1", null, "C:\\Projects\\AppA", null, null)
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Thread 1", null, "C:\\Projects\\AppA", null, null))
            )
        )
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        service.handleNotificationOpen("thread-1", null)
        advanceUntilIdle()
        service.routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
        advanceUntilIdle()

        assertEquals("thread-1", service.state.value.pendingNotificationOpenThreadId)
        assertNull(service.state.value.missingNotificationThreadPrompt)
        assertEquals(listOf("C:\\Projects\\AppA"), repository.activatedWorkspaces)
    }

    @Test
    fun notificationOpen_workspaceSwitchLoadFailureRestoresPreviousSelection() = runTest {
        val existingMessage = ConversationMessage(
            id = "history-assistant-existing",
            threadId = "thread-a",
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = "Existing reply",
            createdAtEpochMs = 1_000L,
            turnId = "turn-a",
            itemId = "assistant-a",
        )
        val repository = FakeRepository().apply {
            recentState = WorkspaceRecentState(
                activeCwd = "/tmp/project-a",
                recentWorkspaces = listOf(
                    WorkspacePathSummary("/tmp/project-a", "Project A", true),
                    WorkspacePathSummary("/tmp/project-b", "Project B", false),
                )
            )
            loadThreadResultsByThreadId["thread-a"] = ThreadLoadResult(
                thread = ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                messages = listOf(existingMessage),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-a",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
            loadThreadErrorsByThreadId["thread-b"] = IllegalStateException("temporary load failure")
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.loadWorkspaceState()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-a", "Workspace A", null, "/tmp/project-a", null, null),
                    ThreadSummary("thread-b", "Workspace B", null, "/tmp/project-b", null, null),
                )
            )
        )
        service.openThread("thread-a")
        advanceUntilIdle()

        service.handleNotificationOpen("thread-b", "turn-b")
        advanceUntilIdle()
        service.routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
        advanceUntilIdle()

        assertEquals(listOf("/tmp/project-b", "/tmp/project-a"), repository.activatedWorkspaces)
        assertEquals("/tmp/project-a", service.state.value.activeWorkspacePath)
        assertEquals("thread-a", service.state.value.selectedThreadId)
        assertEquals(
            listOf("Existing reply"),
            service.state.value.timelineByThread["thread-a"].orEmpty().map { it.text },
        )
        assertEquals("thread-b", service.state.value.pendingNotificationOpenThreadId)
        assertEquals("turn-b", service.state.value.pendingNotificationOpenTurnId)
        assertNull(service.state.value.missingNotificationThreadPrompt)
    }

    @Test
    fun notificationOpen_keepsPendingStateWhenRefreshFails() = runTest {
        val repository = FakeRepository().apply {
            loadThreadError = IllegalStateException("temporary refresh failure")
            refreshThreadsError = IllegalStateException("refresh failed")
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        service.handleNotificationOpen("thread-retry", null)
        advanceUntilIdle()

        assertEquals("thread-retry", service.state.value.pendingNotificationOpenThreadId)
        assertNull(service.state.value.missingNotificationThreadPrompt)
        assertNull(service.state.value.selectedThreadId)
    }

    @Test
    fun forkThread_activatesPreferredProjectBeforeLoadingFork() = runTest {
        val repository = FakeRepository().apply {
            forkThreadResult = ThreadSummary(
                id = "thread-fork",
                title = "Forked thread",
                preview = null,
                cwd = "C:\\Projects\\Forked",
                createdAtEpochMs = null,
                updatedAtEpochMs = null,
            )
            loadThreadResult = ThreadLoadResult(
                thread = forkThreadResult,
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-1", "Source", null, "D:\\Client\\Source", null, null, model = "gpt-5.4")
                )
            )
        )
        advanceUntilIdle()

        service.forkThread("thread-1", preferredProjectPath = "C:\\Projects\\Forked")
        advanceUntilIdle()

        assertEquals(listOf("C:\\Projects\\Forked"), repository.activatedWorkspaces)
        assertEquals(listOf("thread-fork"), repository.loadedThreadIds)
    }

    @Test
    fun tokenUsageUpdates_storeLatestUsageByThread() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.TokenUsageUpdated(
                threadId = "thread-1",
                usage = io.androdex.android.model.ThreadTokenUsage(
                    tokensUsed = 1200,
                    tokenLimit = 32000,
                ),
            )
        )

        val usage = service.state.value.tokenUsageByThread["thread-1"]
        assertEquals(1200, usage?.tokensUsed)
        assertEquals(32000, usage?.tokenLimit)
    }

    @Test
    fun skillsChanged_bumpsSkillInventoryVersion() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        val initialVersion = service.state.value.skillInventoryVersion
        service.processClientUpdate(ClientUpdate.SkillsChanged(cwds = listOf("C:\\Projects\\AppA")))

        assertEquals(initialVersion + 1L, service.state.value.skillInventoryVersion)
    }

    @Test
    fun toolUserInputRequests_storePendingPromptByThread() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
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
            )
        )

        assertEquals(
            listOf("request-1"),
            service.state.value.pendingToolInputsByThread["thread-1"]?.keys?.toList(),
        )
    }

    @Test
    fun toolUserInputRequests_rejectWhenMultipleCandidateThreadsExist() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-1", "First", null, null, null, null),
                    ThreadSummary("thread-2", "Second", null, null, null, null),
                )
            )
        )

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-1",
                    method = "item/tool/requestUserInput",
                    threadId = null,
                    turnId = null,
                    itemId = null,
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
            )
        )
        advanceUntilIdle()

        assertTrue(service.state.value.pendingToolInputsByThread.isEmpty())
        assertEquals("request-1", repository.lastRejectedToolInputRequest?.requestId)
        assertEquals(
            "Structured tool input could not be routed because multiple candidate threads are open.",
            repository.lastRejectedToolInputMessage,
        )
    }

    @Test
    fun toolUserInputRequests_routeToSelectedThreadWithoutExplicitThreadId() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-2", "Second", null, null, null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary("thread-1", "First", null, null, null, null),
                    ThreadSummary("thread-2", "Second", null, null, null, null),
                )
            )
        )
        advanceUntilIdle()

        service.openThread("thread-2")
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-1",
                    method = "item/tool/requestUserInput",
                    threadId = null,
                    turnId = null,
                    itemId = null,
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
            )
        )
        advanceUntilIdle()

        assertEquals(
            listOf("request-1"),
            service.state.value.pendingToolInputsByThread["thread-2"]?.keys?.toList(),
        )
        assertNull(repository.lastRejectedToolInputRequest)
    }

    @Test
    fun toolUserInputRequests_routeToSingleKnownThreadWithoutExplicitThreadId() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Recovered", null, null, null, null))
            )
        )

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-1",
                    method = "item/tool/requestUserInput",
                    threadId = null,
                    turnId = null,
                    itemId = null,
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
            )
        )
        advanceUntilIdle()

        assertEquals(
            listOf("request-1"),
            service.state.value.pendingToolInputsByThread["thread-1"]?.keys?.toList(),
        )
        assertNull(repository.lastRejectedToolInputRequest)
    }

    @Test
    fun toolUserInputRequests_persistAcrossReconnectRecovery() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Recovered", null, null, null, null),
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
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()
        repository.emit(ClientUpdate.ThreadsLoaded(listOf(ThreadSummary("thread-1", "Recovered", null, null, null, null))))
        advanceUntilIdle()
        service.openThread("thread-1")
        advanceUntilIdle()

        val request = ToolUserInputRequest(
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

        service.processClientUpdate(ClientUpdate.ToolUserInputRequested(request))
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.RECONNECT_REQUIRED))
        service.processClientUpdate(ClientUpdate.Connection(ConnectionStatus.CONNECTED))
        advanceUntilIdle()

        assertEquals(
            listOf("request-1"),
            service.state.value.pendingToolInputsByThread["thread-1"]?.keys?.toList(),
        )
    }

    @Test
    fun respondToToolUserInput_clearsOnlyAfterSuccessfulAcknowledgment() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        val request = ToolUserInputRequest(
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
        service.processClientUpdate(ClientUpdate.ToolUserInputRequested(request))

        service.respondToToolUserInput(
            threadId = "thread-1",
            requestId = "request-1",
            answers = mapOf("branch" to "main"),
        )

        assertEquals("request-1", repository.lastToolInputRequest?.requestId)
        assertEquals(listOf("main"), repository.lastToolInputResponse?.answers?.get("branch")?.answers)
        assertTrue(service.state.value.pendingToolInputsByThread["thread-1"].isNullOrEmpty())
    }

    @Test
    fun turnCompletion_clearsOnlyPendingToolInputForCompletedTurn() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
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
            )
        )
        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-2",
                    method = "item/tool/requestUserInput",
                    threadId = "thread-1",
                    turnId = "turn-2",
                    itemId = "item-2",
                    title = "Pick another branch",
                    message = "Select which branch to inspect next.",
                    questions = listOf(
                        ToolUserInputQuestion(
                            id = "branch",
                            header = "Branch",
                            question = "Which branch should we inspect next?",
                            options = emptyList(),
                        )
                    ),
                    rawPayload = "{}",
                )
            )
        )
        service.processClientUpdate(
            ClientUpdate.TurnCompleted(
                threadId = "thread-1",
                turnId = "turn-1",
                terminalState = TurnTerminalState.COMPLETED,
            )
        )
        advanceUntilIdle()

        assertEquals(
            listOf("request-2"),
            service.state.value.pendingToolInputsByThread["thread-1"]?.keys?.toList(),
        )
    }

    @Test
    fun completedThreadRecovery_clearsOnlyPendingToolInputForRecoveredTurn() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Recovered", null, null, null, null),
                messages = emptyList(),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Recovered", null, null, null, null))
            )
        )
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
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
            )
        )
        service.processClientUpdate(
            ClientUpdate.ToolUserInputRequested(
                ToolUserInputRequest(
                    idValue = "request-2",
                    method = "item/tool/requestUserInput",
                    threadId = "thread-1",
                    turnId = "turn-2",
                    itemId = "item-2",
                    title = "Pick another branch",
                    message = "Select which branch to inspect next.",
                    questions = listOf(
                        ToolUserInputQuestion(
                            id = "branch",
                            header = "Branch",
                            question = "Which branch should we inspect next?",
                            options = emptyList(),
                        )
                    ),
                    rawPayload = "{}",
                )
            )
        )
        advanceUntilIdle()

        service.openThread("thread-1")
        advanceUntilIdle()

        assertEquals(
            listOf("request-2"),
            service.state.value.pendingToolInputsByThread["thread-1"]?.keys?.toList(),
        )
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
    fun startReview_sendsReviewRpcAndAddsOptimisticTimelineRow() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()
        service.openThread("thread-1")
        advanceUntilIdle()

        service.startReview("thread-1", ComposerReviewTarget.UNCOMMITTED_CHANGES)
        advanceUntilIdle()

        assertEquals(listOf("thread-1:UNCOMMITTED_CHANGES:"), repository.startedTurns)
        assertTrue(service.state.value.runningThreadIds.contains("thread-1"))
        assertEquals(
            "Review current changes",
            service.state.value.timelineByThread["thread-1"].orEmpty().single().text,
        )
    }

    @Test
    fun compactThread_marksThreadRunningAndCallsRepository() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()

        service.compactThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.compactedThreadIds)
        assertTrue(service.state.value.runningThreadIds.contains("thread-1"))
    }

    @Test
    fun rollbackThread_replacesTimelineWithRepositoryResult() = runTest {
        val repository = FakeRepository().apply {
            rollbackThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", "After rollback", null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "msg-after",
                        threadId = "thread-1",
                        role = ConversationRole.ASSISTANT,
                        kind = ConversationKind.CHAT,
                        text = "Only the older turn remains",
                        createdAtEpochMs = 2L,
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-older",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", "Before rollback", null, null, null))
            )
        )
        advanceUntilIdle()
        service.openThread("thread-1")
        advanceUntilIdle()

        service.rollbackThread("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1:1"), repository.rollbackRequests)
        assertEquals(
            listOf("Only the older turn remains"),
            service.state.value.timelineByThread["thread-1"].orEmpty().map { it.text },
        )
        assertFalse(service.state.value.readyThreadIds.contains("thread-1"))
        assertFalse(service.state.value.runningThreadIds.contains("thread-1"))
    }

    @Test
    fun cleanBackgroundTerminals_refreshesThreadAfterCleanup() = runTest {
        val repository = FakeRepository()
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()

        service.cleanBackgroundTerminals("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), repository.cleanedBackgroundTerminalThreadIds)
        assertEquals(listOf("thread-1"), repository.loadedThreadIds)
    }

    @Test
    fun cleanBackgroundTerminals_dropsStaleStreamingExecutionRowsOnReload() = runTest {
        val repository = FakeRepository().apply {
            loadThreadResult = ThreadLoadResult(
                thread = ThreadSummary("thread-1", "Conversation", null, null, null, null),
                messages = listOf(
                    ConversationMessage(
                        id = "history-command",
                        threadId = "thread-1",
                        role = ConversationRole.SYSTEM,
                        kind = ConversationKind.COMMAND,
                        text = "Completed: clean background terminals",
                        createdAtEpochMs = System.currentTimeMillis() + 1_000L,
                        turnId = "turn-1",
                        itemId = null,
                        isStreaming = false,
                        status = "completed",
                        command = "clean background terminals",
                        execution = ExecutionContent(
                            kind = ExecutionKind.COMMAND,
                            title = "clean background terminals",
                            status = "completed",
                            summary = "Background terminals removed",
                        ),
                    )
                ),
                runSnapshot = ThreadRunSnapshot(
                    interruptibleTurnId = null,
                    hasInterruptibleTurnWithoutId = false,
                    latestTurnId = "turn-1",
                    latestTurnTerminalState = TurnTerminalState.COMPLETED,
                    shouldAssumeRunningFromLatestTurn = false,
                ),
            )
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.CommandExecutionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                command = "clean background terminals",
                status = "running",
                text = "Running: clean background terminals\nstale output that should not survive reload",
                isStreaming = true,
            )
        )
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-1", "Conversation", null, null, null, null))
            )
        )
        advanceUntilIdle()

        service.cleanBackgroundTerminals("thread-1")
        advanceUntilIdle()

        val commands = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.COMMAND }
        assertEquals(1, commands.size)
        assertEquals("Completed: clean background terminals", commands.single().text)
        assertFalse(commands.single().isStreaming)
    }

    @Test
    fun subagentUpdates_mergeAndAdoptThreadIdentityMetadata() = runTest {
        val service = AndrodexService(FakeRepository(), backgroundScope)
        advanceUntilIdle()

        service.processClientUpdate(
            ClientUpdate.SubagentActionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                action = SubagentAction(
                    tool = "spawnAgent",
                    status = "in_progress",
                    prompt = "Inspect Android tests",
                    model = "gpt-5.4-mini",
                    receiverThreadIds = listOf("thread-child"),
                    agentStates = mapOf(
                        "thread-child" to SubagentState(
                            threadId = "thread-child",
                            status = "in_progress",
                            message = "Inspecting tests",
                        )
                    ),
                ),
                isStreaming = true,
            )
        )
        service.processClientUpdate(
            ClientUpdate.ThreadsLoaded(
                listOf(
                    ThreadSummary(
                        id = "thread-child",
                        title = "Scout",
                        preview = null,
                        cwd = null,
                        createdAtEpochMs = null,
                        updatedAtEpochMs = null,
                        parentThreadId = "thread-1",
                        agentNickname = "Scout",
                        agentRole = "explorer",
                    )
                )
            )
        )
        service.processClientUpdate(
            ClientUpdate.SubagentActionUpdate(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                action = SubagentAction(
                    tool = "spawnAgent",
                    status = "completed",
                    prompt = "Inspect Android tests",
                    model = "gpt-5.4-mini",
                    receiverThreadIds = listOf("thread-child"),
                ),
                isStreaming = false,
            )
        )

        val messages = service.state.value.timelineByThread["thread-1"].orEmpty()
            .filter { it.kind == ConversationKind.SUBAGENT_ACTION }
        assertEquals(1, messages.size)
        val action = messages.single().subagentAction
        assertEquals("Scout", action?.agentRows?.single()?.nickname)
        assertEquals("explorer", action?.agentRows?.single()?.role)
        assertEquals("completed", action?.status)
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

    @Test
    fun reconnectSaved_skipsFollowUpLoadsWhenReconnectDoesNotConnect() = runTest {
        val repository = FakeRepository().apply {
            reconnectSavedResult = false
        }
        val service = AndrodexService(repository, backgroundScope)
        advanceUntilIdle()

        service.reconnectSaved()
        advanceUntilIdle()

        assertEquals(0, repository.refreshThreadsCalls)
        assertEquals(0, repository.listRecentWorkspacesCalls)
    }
}

private class ManualCoroutineDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }
}

private class FakeRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>(replay = 16, extraBufferCapacity = 16)

    var hasSavedPairing = false
    var recentState = WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    var startupNotice: String? = null
    var reconnectSavedResult = true
    val activatedWorkspaces = mutableListOf<String>()
    val activateWorkspaceErrorsByPath = mutableMapOf<String, Throwable>()
    val loadedThreadIds = mutableListOf<String>()
    val startedThreadCwds = mutableListOf<String?>()
    var currentThreadTimelineScopeKeyValue: String? = null
    var currentTrustedPairSnapshotValue: TrustedPairSnapshot? = null
    var currentFingerprintValue: String? = null
    var persistedThreadTimelinesByScope: Map<String?, Map<String, List<ConversationMessage>>> = emptyMap()
    var persistedThreadTimelines: Map<String, List<ConversationMessage>>
        get() = persistedThreadTimelinesByScope[currentThreadTimelineScopeKeyValue].orEmpty()
        set(value) {
            persistedThreadTimelinesByScope = persistedThreadTimelinesByScope + (currentThreadTimelineScopeKeyValue to value)
        }
    val persistedThreadTimelineWrites = mutableListOf<Triple<String?, String, List<ConversationMessage>>>()
    var connectWithPairingPayloadAction: ((String) -> Unit)? = null
    var reconnectSavedAction: (() -> Unit)? = null
    var refreshThreadsCalls = 0
    var listRecentWorkspacesCalls = 0

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    suspend fun emit(update: ClientUpdate) {
        updatesFlow.emit(update)
    }

    override fun hasSavedPairing(): Boolean = hasSavedPairing

    override fun currentFingerprint(): String? = currentFingerprintValue

    override fun currentTrustedPairSnapshot(): TrustedPairSnapshot? = currentTrustedPairSnapshotValue

    override fun currentThreadTimelineScopeKey(): String? = currentThreadTimelineScopeKeyValue

    override fun startupNotice(): String? = startupNotice

    override fun loadPersistedThreadTimelines(scopeKey: String?): Map<String, List<ConversationMessage>> {
        return persistedThreadTimelinesByScope[scopeKey].orEmpty()
    }

    override fun savePersistedThreadTimeline(
        scopeKey: String?,
        threadId: String,
        messages: List<ConversationMessage>,
    ) {
        val existing = persistedThreadTimelinesByScope[scopeKey].orEmpty()
        persistedThreadTimelinesByScope = persistedThreadTimelinesByScope + (scopeKey to (existing + (threadId to messages)))
        persistedThreadTimelineWrites += Triple(scopeKey, threadId, messages)
    }

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        connectWithPairingPayloadAction?.invoke(rawPayload)
    }

    override suspend fun reconnectSaved(): Boolean {
        reconnectSavedAction?.invoke()
        return reconnectSavedResult
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) = Unit

    var refreshThreadsError: Throwable? = null
    var refreshedThreads: List<ThreadSummary> = emptyList()
    var refreshThreadsDelayMs: Long = 0L
    var loadThreadDelayMs: Long = 0L
    var refreshThreadsObservedWhileLoadThreadInFlight = false
    private var loadThreadInFlightCount = 0
    val queuedLoadThreadErrors = ArrayDeque<Throwable>()
    val queuedLoadThreadResults = ArrayDeque<ThreadLoadResult>()
    val loadThreadErrorsByThreadId = mutableMapOf<String, Throwable>()
    val loadThreadResultsByThreadId = mutableMapOf<String, ThreadLoadResult>()

    override suspend fun refreshThreads(): List<ThreadSummary> {
        if (loadThreadInFlightCount > 0) {
            refreshThreadsObservedWhileLoadThreadInFlight = true
        }
        refreshThreadsCalls += 1
        refreshThreadsError?.let { throw it }
        if (refreshThreadsDelayMs > 0) {
            delay(refreshThreadsDelayMs)
        }
        return refreshedThreads
    }

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
    val compactedThreadIds = mutableListOf<String>()
    val rollbackRequests = mutableListOf<String>()
    val cleanedBackgroundTerminalThreadIds = mutableListOf<String>()
    val forkRequests = mutableListOf<String>()
    var lastApprovalRequest: ApprovalRequest? = null
    var lastApprovalAccepted: Boolean? = null
    var lastToolInputRequest: ToolUserInputRequest? = null
    var lastToolInputResponse: ToolUserInputResponse? = null
    var lastRejectedToolInputRequest: ToolUserInputRequest? = null
    var lastRejectedToolInputMessage: String? = null
    var loadThreadError: Throwable? = null
    var rollbackThreadResult = ThreadLoadResult(
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
    var forkThreadResult = ThreadSummary("thread-fork", "Forked", null, null, null, null)

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        loadedThreadIds += threadId
        loadThreadInFlightCount += 1
        try {
            if (queuedLoadThreadErrors.isNotEmpty()) {
                throw queuedLoadThreadErrors.removeFirst()
            }
            loadThreadErrorsByThreadId.remove(threadId)?.let { throw it }
            loadThreadError?.let { throw it }
            if (loadThreadDelayMs > 0) {
                delay(loadThreadDelayMs)
            }
            return if (queuedLoadThreadResults.isNotEmpty()) {
                queuedLoadThreadResults.removeFirst()
            } else if (threadId in loadThreadResultsByThreadId) {
                requireNotNull(loadThreadResultsByThreadId[threadId])
            } else {
                loadThreadResult
            }
        } finally {
            loadThreadInFlightCount -= 1
        }
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
        startedTurns += "$threadId:$userInput"
        startedTurnModes += collaborationMode
    }

    override suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String?,
    ) {
        startedTurns += "$threadId:${target.name}:${baseBranch.orEmpty()}"
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
        steeredTurns += "$threadId:$expectedTurnId:$userInput"
        steeredTurnModes += collaborationMode
    }

    override suspend fun interruptTurn(threadId: String, turnId: String) {
        interruptedTurns += "$threadId:$turnId"
    }

    override suspend fun compactThread(threadId: String) {
        compactedThreadIds += threadId
    }

    override suspend fun rollbackThread(
        threadId: String,
        numTurns: Int,
    ): ThreadLoadResult {
        rollbackRequests += "$threadId:$numTurns"
        return rollbackThreadResult
    }

    override suspend fun cleanBackgroundTerminals(threadId: String) {
        cleanedBackgroundTerminalThreadIds += threadId
    }

    override suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String?,
        preferredModel: String?,
    ): ThreadSummary {
        forkRequests += listOfNotNull(threadId, preferredProjectPath, preferredModel).joinToString("|")
        return forkThreadResult
    }

    override suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) = Unit

    override suspend fun loadRuntimeConfig() = Unit

    override suspend fun setSelectedModelId(modelId: String?) = Unit

    override suspend fun setSelectedReasoningEffort(effort: String?) = Unit

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        lastApprovalRequest = request
        lastApprovalAccepted = accept
    }

    override suspend fun respondToToolUserInput(request: ToolUserInputRequest, response: ToolUserInputResponse) {
        lastToolInputRequest = request
        lastToolInputResponse = response
    }

    override suspend fun rejectToolUserInput(request: ToolUserInputRequest, message: String) {
        lastRejectedToolInputRequest = request
        lastRejectedToolInputMessage = message
    }

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        listRecentWorkspacesCalls += 1
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
        activateWorkspaceErrorsByPath.remove(cwd)?.let { throw it }
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
