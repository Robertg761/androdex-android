package io.androdex.android.transport.macnative

import org.json.JSONObject

internal object MacNativeHttpPaths {
    const val environmentDescriptor = "/.well-known/t3/environment"
    const val authBootstrapBearer = "/api/auth/bootstrap/bearer"
    const val authSession = "/api/auth/session"
    const val authWebSocketToken = "/api/auth/ws-token"
    const val orchestrationSnapshot = "/api/orchestration/snapshot"
    const val orchestrationDispatch = "/api/orchestration/dispatch"
}

internal object MacNativeWsMethods {
    const val subscribeOrchestrationDomainEvents = "subscribeOrchestrationDomainEvents"
    const val orchestrationReplayEvents = "orchestration.replayEvents"
}

internal data class MacNativeServerTarget(
    val httpBaseUrl: String,
    val wsBaseUrl: String,
)

internal data class MacNativeBearerSession(
    val serverTarget: MacNativeServerTarget,
    val sessionToken: String,
    val role: String?,
    val expiresAtEpochMs: Long?,
)

internal data class MacNativeSessionState(
    val authenticated: Boolean,
    val role: String?,
    val sessionMethod: String?,
    val expiresAtEpochMs: Long?,
    val payload: JSONObject,
)

internal data class MacNativeWebSocketToken(
    val token: String,
    val expiresAtEpochMs: Long?,
)

internal data class MacNativePersistedSession(
    val httpBaseUrl: String,
    val wsBaseUrl: String,
    val sessionToken: String,
    val role: String?,
    val expiresAtEpochMs: Long?,
    val environmentId: String? = null,
    val hostLabel: String? = null,
    val hostFingerprint: String? = null,
)

internal interface MacNativeSessionStore {
    fun loadBearerSession(): MacNativePersistedSession?

    fun saveBearerSession(session: MacNativePersistedSession)

    fun clearBearerSession()

    fun loadSnapshotSequence(): Long?

    fun saveSnapshotSequence(snapshotSequence: Long)

    fun clearSnapshotSequence()
}

internal interface MacNativeAuthHttpTransport {
    suspend fun fetchEnvironmentDescriptor(serverTarget: MacNativeServerTarget): JSONObject

    suspend fun bootstrapBearerSession(
        serverTarget: MacNativeServerTarget,
        credential: String,
    ): MacNativeBearerSession

    suspend fun readSession(
        serverTarget: MacNativeServerTarget,
        bearerSessionToken: String,
    ): MacNativeSessionState

    suspend fun issueWebSocketToken(session: MacNativeBearerSession): MacNativeWebSocketToken
}

internal interface MacNativeOrchestrationHttpTransport {
    suspend fun fetchSnapshot(session: MacNativeBearerSession): JSONObject

    suspend fun dispatchCommand(
        session: MacNativeBearerSession,
        command: JSONObject,
    ): JSONObject
}

internal fun interface MacNativeOrchestrationEventListener {
    fun onDomainEvent(event: JSONObject)
}

internal interface MacNativeWebSocketConnection {
    suspend fun close(code: Int = 1000, reason: String = "Normal Closure")
}

internal interface MacNativeOrchestrationWsTransport {
    suspend fun connect(
        session: MacNativeBearerSession,
        token: MacNativeWebSocketToken,
        eventListener: MacNativeOrchestrationEventListener,
    ): MacNativeWebSocketConnection

    suspend fun replayEvents(
        session: MacNativeBearerSession,
        token: MacNativeWebSocketToken,
        fromSequenceExclusive: Long,
    ): List<JSONObject>
}

internal data class MacNativeTransportStack(
    val authHttp: MacNativeAuthHttpTransport,
    val orchestrationHttp: MacNativeOrchestrationHttpTransport,
    val orchestrationWs: MacNativeOrchestrationWsTransport,
    val sessionStore: MacNativeSessionStore,
)
