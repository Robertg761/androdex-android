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
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspacePathSummary
import io.androdex.android.model.WorkspaceRecentState
import io.androdex.android.model.requestId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        repository.emit(
            ClientUpdate.ThreadsLoaded(
                listOf(ThreadSummary("thread-pending", "Pending thread", null, null, null, null))
            )
        )
        advanceUntilIdle()
        service.routePendingNotificationOpenIfPossible(refreshIfNeeded = false)
        advanceUntilIdle()

        assertNull(service.state.value.pendingNotificationOpenThreadId)
        assertEquals("thread-pending", service.state.value.selectedThreadId)
        assertNull(service.state.value.missingNotificationThreadPrompt)
    }

    @Test
    fun notificationOpen_showsMissingThreadPromptAndFallsBackToLiveThread() = runTest {
        val repository = FakeRepository().apply {
            loadThreadError = IllegalStateException("thread not found")
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

    override fun currentTrustedPairSnapshot() = null

    override fun startupNotice(): String? = startupNotice

    override suspend fun connectWithPairingPayload(rawPayload: String) = Unit

    override suspend fun reconnectSaved() = Unit

    override suspend fun disconnect(clearSavedPairing: Boolean) = Unit

    var refreshThreadsError: Throwable? = null

    override suspend fun refreshThreads(): List<ThreadSummary> {
        refreshThreadsError?.let { throw it }
        return emptyList()
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
    var lastToolInputRequest: ToolUserInputRequest? = null
    var lastToolInputResponse: ToolUserInputResponse? = null
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

    override suspend fun loadThread(threadId: String): ThreadLoadResult {
        loadedThreadIds += threadId
        loadThreadError?.let { throw it }
        return loadThreadResult
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
        lastToolInputRequest = request
        lastToolInputResponse = response
    }

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
