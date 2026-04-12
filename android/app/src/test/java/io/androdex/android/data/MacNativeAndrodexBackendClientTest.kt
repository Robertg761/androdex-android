package io.androdex.android.data

import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.BackendKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ToolUserInputAnswer
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.transport.macnative.MacNativeAuthHttpTransport
import io.androdex.android.transport.macnative.MacNativeBearerSession
import io.androdex.android.transport.macnative.MacNativeHttpException
import io.androdex.android.transport.macnative.MacNativeOrchestrationEventListener
import io.androdex.android.transport.macnative.MacNativeOrchestrationHttpTransport
import io.androdex.android.transport.macnative.MacNativeOrchestrationWsTransport
import io.androdex.android.transport.macnative.MacNativePersistedSession
import io.androdex.android.transport.macnative.MacNativeServerTarget
import io.androdex.android.transport.macnative.MacNativeSessionState
import io.androdex.android.transport.macnative.MacNativeSessionStore
import io.androdex.android.transport.macnative.MacNativeTransportStack
import io.androdex.android.transport.macnative.MacNativeWebSocketConnection
import io.androdex.android.transport.macnative.MacNativeWebSocketToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MacNativeAndrodexBackendClientTest {
    @Test
    fun connectWithPairingPayload_bootstrapsSnapshotAndSubscribes() = runTest {
        val auth = FakeMacNativeAuthHttpTransport()
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(listOf(sampleSnapshot(snapshotSequence = 3L))),
        )
        val orchestrationWs = FakeMacNativeOrchestrationWsTransport()
        val sessionStore = FakeMacNativeSessionStore()
        val preferences = FakeMacNativeClientPreferences()
        val client = MacNativeAndrodexBackendClient(
            preferences = preferences,
            transportStack = MacNativeTransportStack(
                authHttp = auth,
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = orchestrationWs,
                sessionStore = sessionStore,
            ),
        )

        client.connectWithPairingPayload(samplePairingPayload())

        assertNotNull(sessionStore.loadBearerSession())
        assertEquals(3L, sessionStore.loadSnapshotSequence())
        assertEquals(BackendKind.MAC_NATIVE, preferences.preferredBackendKind)
        assertTrue(orchestrationHttp.fetchSnapshotCalls >= 1)
    }

    @Test
    fun reconnectSaved_usesPersistedSessionAndRefreshesSnapshot() = runTest {
        val sessionStore = FakeMacNativeSessionStore(
            persistedSession = samplePersistedSession(),
            snapshotSequence = 1L,
        )
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(listOf(sampleSnapshot(snapshotSequence = 5L))),
        )
        val orchestrationWs = FakeMacNativeOrchestrationWsTransport()
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(),
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = orchestrationWs,
                sessionStore = sessionStore,
            ),
        )

        assertTrue(client.reconnectSaved())

        assertEquals(5L, sessionStore.loadSnapshotSequence())
    }

    @Test
    fun reconnectSaved_clearsExpiredAuthAndRequiresRepair() = runTest {
        val sessionStore = FakeMacNativeSessionStore(
            persistedSession = samplePersistedSession(),
            snapshotSequence = 2L,
        )
        val auth = FakeMacNativeAuthHttpTransport(
            readSessionError = MacNativeHttpException(401, "expired"),
        )
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = auth,
                orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(),
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = sessionStore,
            ),
        )

        assertFalse(client.reconnectSaved())

        assertNull(sessionStore.loadBearerSession())
        assertNull(sessionStore.loadSnapshotSequence())
        assertFalse(client.hasSavedPairing())
    }

    @Test
    fun reconnectSaved_propagatesCancellationWithoutReportingRetryFailure() = runTest {
        val sessionStore = FakeMacNativeSessionStore(
            persistedSession = samplePersistedSession(),
            snapshotSequence = 2L,
        )
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(
                    readSessionError = CancellationException("StandaloneCoroutine was cancelled"),
                ),
                orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(),
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = sessionStore,
            ),
        )

        val failure = runCatching {
            client.reconnectSaved()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertNotNull(sessionStore.loadBearerSession())
        assertEquals(2L, sessionStore.loadSnapshotSequence())
    }

    @Test
    fun canonicalActions_dispatchExpectedCommands() = runTest {
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(listOf(sampleSnapshot(snapshotSequence = 1L))),
        )
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(),
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = FakeMacNativeSessionStore(),
            ),
        )

        client.connectWithPairingPayload(samplePairingPayload())
        client.startTurn(threadId = "thread-1", userInput = "Hello", attachments = emptyList(), fileMentions = emptyList(), skillMentions = emptyList(), collaborationMode = null)
        client.interruptTurn(threadId = "thread-1", turnId = "turn-1")
        client.rollbackThread(threadId = "thread-1", numTurns = 1)
        client.cleanBackgroundTerminals(threadId = "thread-1")
        client.respondToApproval(
            ApprovalRequest(
                idValue = "approval-1",
                method = "tool/approval",
                command = null,
                reason = null,
                threadId = "thread-1",
                turnId = null,
            ),
            accept = true,
        )
        client.respondToToolUserInput(
            request = ToolUserInputRequest(
                idValue = "input-1",
                method = "tool/request_user_input",
                threadId = "thread-1",
                turnId = null,
                itemId = null,
                title = null,
                message = null,
                questions = emptyList(),
                rawPayload = "{}",
            ),
            response = ToolUserInputResponse(
                answers = mapOf(
                    "question-1" to ToolUserInputAnswer(
                        answers = listOf("Yes"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "thread.turn.start",
                "thread.turn.interrupt",
                "thread.checkpoint.revert",
                "thread.session.stop",
                "thread.approval.respond",
                "thread.user-input.respond",
            ),
            orchestrationHttp.dispatchedCommands.map { it.optString("type") },
        )
    }

    @Test
    fun startThread_waitsForCreatedThreadToAppearInSnapshot() = runTest {
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(
                listOf(
                    sampleSnapshot(snapshotSequence = 1L),
                    sampleSnapshot(snapshotSequence = 1L),
                    sampleSnapshot(snapshotSequence = 1L),
                )
            ),
            onDispatchCommand = { command, transport ->
                if (command.optString("type") == "thread.create") {
                    transport.enqueueSnapshot(
                        sampleSnapshot(
                            snapshotSequence = 2L,
                            threads = listOf(
                                sampleThread(
                                    threadId = command.optString("threadId"),
                                    projectId = command.optString("projectId"),
                                    title = command.optString("title"),
                                    workspacePath = "/workspace/demo",
                                    assistantText = "Hi there",
                                )
                            ),
                        )
                    )
                }
            }
        )
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(),
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = FakeMacNativeSessionStore(),
            ),
        )

        client.connectWithPairingPayload(samplePairingPayload())
        val thread = client.startThread("/workspace/demo")

        assertEquals(orchestrationHttp.dispatchedCommands.last().optString("threadId"), thread.id)
        assertEquals("New conversation", thread.title)
        assertTrue(orchestrationHttp.fetchSnapshotCalls >= 4)
    }

    @Test
    fun startThread_ignoresDeletedProjectsWhenChoosingCreationTarget() = runTest {
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(
                listOf(
                    sampleSnapshot(
                        snapshotSequence = 1L,
                        projects = listOf(
                            sampleProject(
                                projectId = "project-deleted",
                                title = "Deleted",
                                workspaceRoot = "/workspace/deleted",
                                deletedAt = "2026-04-12T10:00:00Z",
                            ),
                            sampleProject(
                                projectId = "project-live",
                                title = "Live",
                                workspaceRoot = "/workspace/live",
                            ),
                        ),
                    )
                )
            ),
            onDispatchCommand = { command, transport ->
                if (command.optString("type") == "thread.create") {
                    transport.enqueueSnapshot(
                        sampleSnapshot(
                            snapshotSequence = 2L,
                            projects = listOf(
                                sampleProject(
                                    projectId = "project-deleted",
                                    title = "Deleted",
                                    workspaceRoot = "/workspace/deleted",
                                    deletedAt = "2026-04-12T10:00:00Z",
                                ),
                                sampleProject(
                                    projectId = "project-live",
                                    title = "Live",
                                    workspaceRoot = "/workspace/live",
                                ),
                            ),
                            threads = listOf(
                                sampleThread(
                                    threadId = command.optString("threadId"),
                                    projectId = command.optString("projectId"),
                                    title = command.optString("title"),
                                    workspacePath = JSONObject.NULL.toString(),
                                    assistantText = "",
                                )
                            ),
                        )
                    )
                }
            },
        )
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(),
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = FakeMacNativeSessionStore(),
            ),
        )

        client.connectWithPairingPayload(samplePairingPayload())
        client.startThread(null)

        assertEquals("project-live", orchestrationHttp.dispatchedCommands.last().optString("projectId"))
    }

    @Test
    fun shouldEmitMacNativeTurnCompletion_handlesFastFinishedOptimisticTurn() = runTest {
        val previous = ThreadRunSnapshot(
            interruptibleTurnId = null,
            hasInterruptibleTurnWithoutId = false,
            latestTurnId = null,
            latestTurnTerminalState = null,
            shouldAssumeRunningFromLatestTurn = false,
        )
        val nextCompleted = ThreadRunSnapshot(
            interruptibleTurnId = null,
            hasInterruptibleTurnWithoutId = false,
            latestTurnId = "turn-1",
            latestTurnTerminalState = TurnTerminalState.COMPLETED,
            shouldAssumeRunningFromLatestTurn = false,
        )

        assertTrue(shouldEmitMacNativeTurnCompletion(previous, nextCompleted, hadOptimisticTurnStart = true))
        assertFalse(shouldEmitMacNativeTurnCompletion(previous, nextCompleted, hadOptimisticTurnStart = false))
    }

    @Test
    fun startTurn_pollsForCompletionWhenLiveUpdateIsMissed() = runTest {
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(
                listOf(
                    sampleSnapshot(snapshotSequence = 1L),
                    sampleSnapshot(
                        snapshotSequence = 2L,
                        threads = listOf(
                            sampleThread(
                                assistantText = "",
                                latestTurnState = null,
                                sessionStatus = "running",
                                activeTurnId = null,
                                assistantStreaming = false,
                            )
                        ),
                    ),
                    sampleSnapshot(
                        snapshotSequence = 3L,
                        threads = listOf(
                            sampleThread(
                                assistantText = "OK",
                                latestTurnState = "completed",
                                latestTurnCompletedAt = "2026-04-12T10:00:05Z",
                                latestTurnAssistantMessageId = "msg-2",
                                sessionStatus = "ready",
                                activeTurnId = null,
                                assistantStreaming = false,
                            )
                        ),
                    ),
                )
            ),
        )
        val updates = mutableListOf<ClientUpdate>()
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(),
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = FakeMacNativeSessionStore(),
            ),
            scope = backgroundScope,
            startedTurnRefreshPollMs = 100L,
        )
        backgroundScope.launch {
            client.updates.collect { updates += it }
        }

        client.connectWithPairingPayload(samplePairingPayload())
        advanceUntilIdle()

        client.startTurn(
            threadId = "thread-1",
            userInput = "Hello",
            attachments = emptyList(),
            fileMentions = emptyList(),
            skillMentions = emptyList(),
            collaborationMode = null,
        )
        advanceUntilIdle()
        advanceTimeBy(150L)
        advanceUntilIdle()

        assertTrue(
            updates.any {
                it is ClientUpdate.TurnCompleted
                    && it.threadId == "thread-1"
                    && it.terminalState == TurnTerminalState.COMPLETED
            }
        )
        assertTrue(
            updates.any {
                it is ClientUpdate.ThreadLoaded
                    && it.thread?.id == "thread-1"
                    && it.messages.any { message -> message.text == "OK" }
            }
        )
    }

    @Test
    fun completedSnapshotWithNullActiveTurnDoesNotReportInterruptibleTurn() = runTest {
        val completedThread = sampleThread(
            assistantText = "OK",
            latestTurnState = "completed",
            latestTurnCompletedAt = "2026-04-12T10:00:05Z",
            latestTurnAssistantMessageId = "msg-2",
            sessionStatus = "ready",
            activeTurnId = "turn-1",
            assistantStreaming = false,
        ).apply {
            optJSONObject("session")?.put("activeTurnId", JSONObject.NULL)
        }
        val runSnapshot = io.androdex.android.transport.macnative.mapMacNativeThreadRunSnapshot(completedThread)
        val threadLoad = io.androdex.android.transport.macnative.mapMacNativeSnapshotToThreadLoad(
            snapshot = sampleSnapshot(snapshotSequence = 3L, threads = listOf(completedThread)),
            threadId = "thread-1",
        )

        assertNull(runSnapshot.interruptibleTurnId)
        assertEquals(TurnTerminalState.COMPLETED, runSnapshot.latestTurnTerminalState)
        assertFalse(threadLoad.thread?.threadCapabilities?.turnInterrupt?.supported ?: true)
    }

    @Test
    fun listRecentWorkspaces_dropsDeletedActiveWorkspace() = runTest {
        val orchestrationHttp = FakeMacNativeOrchestrationHttpTransport(
            snapshots = ArrayDeque(
                listOf(
                    sampleSnapshot(
                        snapshotSequence = 1L,
                        projects = listOf(
                            sampleProject(
                                projectId = "project-deleted",
                                title = "Deleted",
                                workspaceRoot = "/workspace/deleted",
                                deletedAt = "2026-04-12T10:00:00Z",
                            ),
                            sampleProject(
                                projectId = "project-live",
                                title = "Live",
                                workspaceRoot = "/workspace/live",
                            ),
                        ),
                    )
                )
            ),
        )
        val client = MacNativeAndrodexBackendClient(
            preferences = FakeMacNativeClientPreferences(),
            transportStack = MacNativeTransportStack(
                authHttp = FakeMacNativeAuthHttpTransport(),
                orchestrationHttp = orchestrationHttp,
                orchestrationWs = FakeMacNativeOrchestrationWsTransport(),
                sessionStore = FakeMacNativeSessionStore(),
            ),
        )

        client.connectWithPairingPayload(samplePairingPayload())
        client.activateWorkspace("/workspace/deleted")
        val recent = client.listRecentWorkspaces()

        assertEquals("/workspace/live", recent.activeCwd)
        assertEquals(listOf("/workspace/live"), recent.recentWorkspaces.map { it.path })
    }

    private fun samplePairingPayload(): String {
        return JSONObject()
            .put("v", 1)
            .put("transport", "mac-native")
            .put("httpBaseUrl", "http://127.0.0.1:3000")
            .put("credential", "pair-credential")
            .put("expiresAt", "2026-04-12T12:00:00Z")
            .put("label", "Robert's Mac")
            .put("fingerprint", "mac-fingerprint")
            .toString()
    }

    private fun samplePersistedSession(): MacNativePersistedSession {
        return MacNativePersistedSession(
            httpBaseUrl = "http://127.0.0.1:3000",
            wsBaseUrl = "ws://127.0.0.1:3000",
            sessionToken = "session-token",
            role = "owner",
            expiresAtEpochMs = 1_800_000_000_000L,
            environmentId = "env-1",
            hostLabel = "Robert's Mac",
            hostFingerprint = "mac-fingerprint",
        )
    }

    private fun sampleSnapshot(
        snapshotSequence: Long,
        projects: List<JSONObject> = listOf(sampleProject()),
        threads: List<JSONObject> = listOf(sampleThread()),
    ): JSONObject {
        return JSONObject()
            .put("snapshotSequence", snapshotSequence)
            .put("projects", org.json.JSONArray(projects))
            .put("threads", org.json.JSONArray(threads))
            .put("updatedAt", "2026-04-12T10:00:01Z")
    }

    private fun sampleProject(
        projectId: String = "project-1",
        title: String = "Demo",
        workspaceRoot: String = "/workspace/demo",
        deletedAt: String? = null,
    ): JSONObject {
        return JSONObject()
            .put("id", projectId)
            .put("title", title)
            .put("workspaceRoot", workspaceRoot)
            .put("deletedAt", deletedAt)
    }

    private fun sampleThread(
        threadId: String = "thread-1",
        projectId: String = "project-1",
        title: String = "Conversation",
        workspacePath: String = "/workspace/demo",
        assistantText: String = "Hi there",
        latestTurnState: String? = "running",
        latestTurnCompletedAt: String? = null,
        latestTurnAssistantMessageId: String? = null,
        sessionStatus: String = "running",
        activeTurnId: String? = "turn-1",
        assistantStreaming: Boolean = true,
    ): JSONObject {
        return JSONObject()
            .put("id", threadId)
            .put("projectId", projectId)
            .put("title", title)
            .put(
                "modelSelection",
                JSONObject()
                    .put("provider", "codex")
                    .put("model", "gpt-5.4")
            )
            .put("runtimeMode", "full-access")
            .put("interactionMode", "default")
            .put("branch", JSONObject.NULL)
            .put("worktreePath", workspacePath)
            .put("createdAt", "2026-04-12T10:00:00Z")
            .put("updatedAt", "2026-04-12T10:00:01Z")
            .put("archivedAt", JSONObject.NULL)
            .put("deletedAt", JSONObject.NULL)
            .put(
                "latestTurn",
                latestTurnState?.let {
                    JSONObject()
                        .put("turnId", "turn-1")
                        .put("state", it)
                        .put("requestedAt", "2026-04-12T10:00:00Z")
                        .put("startedAt", "2026-04-12T10:00:00Z")
                        .put("completedAt", latestTurnCompletedAt)
                        .put("assistantMessageId", latestTurnAssistantMessageId)
                } ?: JSONObject.NULL
            )
            .put(
                "session",
                JSONObject()
                    .put("threadId", threadId)
                    .put("status", sessionStatus)
                    .put("providerName", "codex")
                    .put("runtimeMode", "full-access")
                    .put("activeTurnId", activeTurnId)
                    .put("lastError", JSONObject.NULL)
                    .put("updatedAt", "2026-04-12T10:00:01Z")
            )
            .put(
                "messages",
                org.json.JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", "msg-1")
                            .put("role", "user")
                            .put("text", "Hello")
                            .put("turnId", "turn-1")
                            .put("streaming", false)
                            .put("createdAt", "2026-04-12T10:00:00Z"),
                        JSONObject()
                            .put("id", "msg-2")
                            .put("role", "assistant")
                            .put("text", assistantText)
                            .put("turnId", "turn-1")
                            .put("streaming", assistantStreaming)
                            .put("createdAt", "2026-04-12T10:00:01Z"),
                    )
                )
            )
            .put("proposedPlans", org.json.JSONArray())
            .put("activities", org.json.JSONArray())
            .put("checkpoints", org.json.JSONArray())
    }
}

private class FakeMacNativeClientPreferences : MacNativeClientPreferences {
    var preferredBackendKind: BackendKind? = null
    private var selectedModelId: String? = null
    private var selectedReasoningEffort: String? = null
    private var selectedAccessMode: AccessMode = AccessMode.FULL_ACCESS
    private var selectedServiceTier: ServiceTier? = null
    private val runtimeOverridesByScope = linkedMapOf<String?, Map<String, ThreadRuntimeOverride>>()

    override fun loadSelectedModelId(): String? = selectedModelId

    override fun saveSelectedModelId(value: String?) {
        selectedModelId = value
    }

    override fun loadSelectedReasoningEffort(): String? = selectedReasoningEffort

    override fun saveSelectedReasoningEffort(value: String?) {
        selectedReasoningEffort = value
    }

    override fun loadSelectedAccessMode(): AccessMode = selectedAccessMode

    override fun saveSelectedAccessMode(value: AccessMode) {
        selectedAccessMode = value
    }

    override fun loadSelectedServiceTier(): ServiceTier? = selectedServiceTier

    override fun saveSelectedServiceTier(value: ServiceTier?) {
        selectedServiceTier = value
    }

    override fun loadThreadRuntimeOverrides(scopeKey: String?): Map<String, ThreadRuntimeOverride> {
        return runtimeOverridesByScope[scopeKey].orEmpty()
    }

    override fun saveThreadRuntimeOverrides(
        scopeKey: String?,
        value: Map<String, ThreadRuntimeOverride>,
    ) {
        runtimeOverridesByScope[scopeKey] = value
    }

    override fun savePreferredBackendKind(kind: BackendKind?) {
        preferredBackendKind = kind
    }
}

private class FakeMacNativeSessionStore(
    private var persistedSession: MacNativePersistedSession? = null,
    private var snapshotSequence: Long? = null,
) : MacNativeSessionStore {
    override fun loadBearerSession(): MacNativePersistedSession? = persistedSession

    override fun saveBearerSession(session: MacNativePersistedSession) {
        persistedSession = session
    }

    override fun clearBearerSession() {
        persistedSession = null
    }

    override fun loadSnapshotSequence(): Long? = snapshotSequence

    override fun saveSnapshotSequence(snapshotSequence: Long) {
        this.snapshotSequence = snapshotSequence
    }

    override fun clearSnapshotSequence() {
        snapshotSequence = null
    }
}

private class FakeMacNativeAuthHttpTransport(
    private val readSessionError: Throwable? = null,
) : MacNativeAuthHttpTransport {
    override suspend fun fetchEnvironmentDescriptor(serverTarget: MacNativeServerTarget): JSONObject {
        return JSONObject()
            .put("environmentId", "env-1")
            .put("label", "Robert's Mac")
    }

    override suspend fun bootstrapBearerSession(
        serverTarget: MacNativeServerTarget,
        credential: String,
    ): MacNativeBearerSession {
        return MacNativeBearerSession(
            serverTarget = serverTarget,
            sessionToken = "session-token",
            role = "owner",
            expiresAtEpochMs = 1_800_000_000_000L,
        )
    }

    override suspend fun readSession(
        serverTarget: MacNativeServerTarget,
        bearerSessionToken: String,
    ): MacNativeSessionState {
        readSessionError?.let { throw it }
        return MacNativeSessionState(
            authenticated = true,
            role = "owner",
            sessionMethod = "bearer-session-token",
            expiresAtEpochMs = 1_800_000_000_000L,
            payload = JSONObject().put("sessionToken", bearerSessionToken),
        )
    }

    override suspend fun issueWebSocketToken(session: MacNativeBearerSession): MacNativeWebSocketToken {
        return MacNativeWebSocketToken(
            token = "ws-token-${UUID.randomUUID()}",
            expiresAtEpochMs = 1_800_000_000_000L,
        )
    }
}

private class FakeMacNativeOrchestrationHttpTransport(
    private val snapshots: ArrayDeque<JSONObject> = ArrayDeque(),
    private val onDispatchCommand: ((JSONObject, FakeMacNativeOrchestrationHttpTransport) -> Unit)? = null,
) : MacNativeOrchestrationHttpTransport {
    var fetchSnapshotCalls = 0
    val dispatchedCommands = mutableListOf<JSONObject>()
    private var nextSequence = 1L

    fun enqueueSnapshot(snapshot: JSONObject) {
        snapshots.addLast(JSONObject(snapshot.toString()))
    }

    override suspend fun fetchSnapshot(session: MacNativeBearerSession): JSONObject {
        fetchSnapshotCalls += 1
        val snapshot = when {
            snapshots.isEmpty() -> JSONObject().put("snapshotSequence", 0).put("projects", org.json.JSONArray()).put("threads", org.json.JSONArray()).put("updatedAt", "2026-04-12T10:00:01Z")
            snapshots.size == 1 -> JSONObject(snapshots.first().toString())
            else -> JSONObject(snapshots.removeFirst().toString())
        }
        nextSequence = maxOf(nextSequence, snapshot.optLong("snapshotSequence", 0L) + 1L)
        return snapshot
    }

    override suspend fun dispatchCommand(
        session: MacNativeBearerSession,
        command: JSONObject,
    ): JSONObject {
        val copiedCommand = JSONObject(command.toString())
        dispatchedCommands += copiedCommand
        onDispatchCommand?.invoke(copiedCommand, this)
        val sequence = nextSequence
        nextSequence += 1L
        return JSONObject().put("sequence", sequence)
    }
}

private class FakeMacNativeOrchestrationWsTransport(
    private val replayEvents: List<JSONObject> = emptyList(),
) : MacNativeOrchestrationWsTransport {
    var connectCalls = 0
    var listener: MacNativeOrchestrationEventListener? = null
    val replayRequests = mutableListOf<Long>()

    override suspend fun connect(
        session: MacNativeBearerSession,
        token: MacNativeWebSocketToken,
        eventListener: MacNativeOrchestrationEventListener,
    ): MacNativeWebSocketConnection {
        connectCalls += 1
        listener = eventListener
        return object : MacNativeWebSocketConnection {
            override suspend fun close(code: Int, reason: String) = Unit
        }
    }

    override suspend fun replayEvents(
        session: MacNativeBearerSession,
        token: MacNativeWebSocketToken,
        fromSequenceExclusive: Long,
    ): List<JSONObject> {
        replayRequests += fromSequenceExclusive
        return replayEvents.map { JSONObject(it.toString()) }
    }

    fun emitDomainEvent(event: JSONObject) {
        listener?.onDomainEvent(JSONObject(event.toString()))
    }
}
