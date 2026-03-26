package io.androdex.android.service

import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ThreadSummary
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

        service.processClientUpdate(ClientUpdate.TurnCompleted(threadId = null))
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

    override suspend fun loadThread(threadId: String): Pair<ThreadSummary?, List<ConversationMessage>> {
        loadedThreadIds += threadId
        return null to emptyList()
    }

    override suspend fun startTurn(threadId: String, userInput: String) = Unit

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
