package io.androdex.android

import io.androdex.android.data.AndrodexRepositoryContract
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ThreadSummary
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
}

private class FakeRepository : AndrodexRepositoryContract {
    private val updatesFlow = MutableSharedFlow<ClientUpdate>()

    var recentState = WorkspaceRecentState(activeCwd = null, recentWorkspaces = emptyList())
    val activatedWorkspaces = mutableListOf<String>()
    val loadedThreadIds = mutableListOf<String>()
    val startedThreadCwds = mutableListOf<String?>()

    override val updates: SharedFlow<ClientUpdate> = updatesFlow

    suspend fun emit(update: ClientUpdate) {
        updatesFlow.emit(update)
    }

    override fun hasSavedPairing(): Boolean = false

    override fun currentFingerprint(): String? = null

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
