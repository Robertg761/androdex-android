package io.androdex.android.data

import io.androdex.android.model.AccessMode
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.BackendKind
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ToolUserInputAnswer
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
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
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

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
        assistantText: String = "Hi there",
    ): JSONObject {
        return JSONObject(
            """
                {
                  "snapshotSequence": $snapshotSequence,
                  "projects": [
                    {
                      "id": "project-1",
                      "title": "Demo",
                      "workspaceRoot": "/workspace/demo"
                    }
                  ],
                  "threads": [
                    {
                      "id": "thread-1",
                      "projectId": "project-1",
                      "title": "Conversation",
                      "modelSelection": {
                        "provider": "codex",
                        "model": "gpt-5.4"
                      },
                      "runtimeMode": "full-access",
                      "interactionMode": "default",
                      "branch": null,
                      "worktreePath": "/workspace/demo",
                      "createdAt": "2026-04-12T10:00:00Z",
                      "updatedAt": "2026-04-12T10:00:01Z",
                      "archivedAt": null,
                      "deletedAt": null,
                      "latestTurn": {
                        "turnId": "turn-1",
                        "state": "running",
                        "requestedAt": "2026-04-12T10:00:00Z",
                        "startedAt": "2026-04-12T10:00:00Z",
                        "completedAt": null,
                        "assistantMessageId": null
                      },
                      "session": {
                        "threadId": "thread-1",
                        "status": "running",
                        "providerName": "codex",
                        "runtimeMode": "full-access",
                        "activeTurnId": "turn-1",
                        "lastError": null,
                        "updatedAt": "2026-04-12T10:00:01Z"
                      },
                      "messages": [
                        {
                          "id": "msg-1",
                          "role": "user",
                          "text": "Hello",
                          "turnId": "turn-1",
                          "streaming": false,
                          "createdAt": "2026-04-12T10:00:00Z"
                        },
                        {
                          "id": "msg-2",
                          "role": "assistant",
                          "text": "$assistantText",
                          "turnId": "turn-1",
                          "streaming": true,
                          "createdAt": "2026-04-12T10:00:01Z"
                        }
                      ],
                      "proposedPlans": [],
                      "activities": [],
                      "checkpoints": []
                    }
                  ],
                  "updatedAt": "2026-04-12T10:00:01Z"
                }
            """.trimIndent()
        )
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
) : MacNativeOrchestrationHttpTransport {
    var fetchSnapshotCalls = 0
    val dispatchedCommands = mutableListOf<JSONObject>()

    override suspend fun fetchSnapshot(session: MacNativeBearerSession): JSONObject {
        fetchSnapshotCalls += 1
        return when {
            snapshots.isEmpty() -> JSONObject().put("snapshotSequence", 0).put("projects", org.json.JSONArray()).put("threads", org.json.JSONArray()).put("updatedAt", "2026-04-12T10:00:01Z")
            snapshots.size == 1 -> JSONObject(snapshots.first().toString())
            else -> JSONObject(snapshots.removeFirst().toString())
        }
    }

    override suspend fun dispatchCommand(
        session: MacNativeBearerSession,
        command: JSONObject,
    ): JSONObject {
        dispatchedCommands += JSONObject(command.toString())
        return JSONObject().put("sequence", fetchSnapshotCalls + dispatchedCommands.size)
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
