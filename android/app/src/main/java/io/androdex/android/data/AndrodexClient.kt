package io.androdex.android.data

import android.util.Log
import io.androdex.android.crypto.aesGcmDecrypt
import io.androdex.android.crypto.aesGcmEncrypt
import io.androdex.android.crypto.buildClientAuthTranscript
import io.androdex.android.crypto.buildTranscriptBytes
import io.androdex.android.crypto.buildTrustedSessionResolveTranscript
import io.androdex.android.crypto.decodeBase64
import io.androdex.android.crypto.deriveSharedSecret
import io.androdex.android.crypto.encodeBase64
import io.androdex.android.crypto.fingerprint
import io.androdex.android.crypto.generatePhoneIdentityState
import io.androdex.android.crypto.generateX25519PrivateKey
import io.androdex.android.crypto.hkdfSha256
import io.androdex.android.crypto.pairingQrVersion
import io.androdex.android.crypto.randomNonce
import io.androdex.android.crypto.secureClockSkewToleranceSeconds
import io.androdex.android.crypto.secureNonce
import io.androdex.android.crypto.secureProtocolVersion
import io.androdex.android.crypto.sha256
import io.androdex.android.crypto.signEd25519
import io.androdex.android.crypto.verifyEd25519
import io.androdex.android.ComposerReviewTarget
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.AccessMode
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.FuzzyFileMatch
import io.androdex.android.model.GitBranchesWithStatusResult
import io.androdex.android.model.GitCheckoutResult
import io.androdex.android.model.GitCommitResult
import io.androdex.android.model.GitCreateBranchResult
import io.androdex.android.model.GitCreateWorktreeResult
import io.androdex.android.model.GitOperationException
import io.androdex.android.model.GitPullResult
import io.androdex.android.model.GitPushResult
import io.androdex.android.model.GitRemoveWorktreeResult
import io.androdex.android.model.GitRepoDiffResult
import io.androdex.android.model.GitRepoSyncResult
import io.androdex.android.model.GitWorktreeChangeTransferMode
import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountSnapshotOrigin
import io.androdex.android.model.HostAccountStatus
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.ModelOption
import io.androdex.android.model.PairingPayload
import io.androdex.android.model.PlanStep
import io.androdex.android.model.PhoneIdentityState
import io.androdex.android.model.SavedRelaySession
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SkillMetadata
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputOption
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.ToolUserInputResponse
import io.androdex.android.model.TrustedPairSnapshot
import io.androdex.android.model.TurnFileMention
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.model.TurnSkillMention
import io.androdex.android.model.TrustedMacRecord
import io.androdex.android.model.TrustedMacRegistry
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceRecentState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

private const val handshakeModeQrBootstrap = "qr_bootstrap"
private const val handshakeModeTrustedReconnect = "trusted_reconnect"
private const val relayOpenTimeoutMs = 12_000L
private const val secureHandshakeTimeoutMs = 20_000L
private const val trustedSessionResolveTimeoutMs = 8_000L
private const val defaultRpcTimeoutMs = 20_000L
private const val threadReadTimeoutMs = 45_000L
private const val threadListTimeoutMs = 30_000L
private const val logTag = "AndrodexClient"
private const val trustedReconnectFailureThreshold = 3

private class HostTransportInterruptedException(
    message: String,
) : CancellationException(message)

private enum class TrustedResolveFallbackPolicy {
    ALLOW_SAVED_SESSION,
    REQUIRE_FRESH_LIVE_SESSION,
}

private sealed interface TrustedSessionResolveResult {
    data class Resolved(
        val pairing: PairingPayload,
        val displayName: String?,
    ) : TrustedSessionResolveResult

    data class FallbackToSavedSession(
        val detail: String,
    ) : TrustedSessionResolveResult

    data class LiveSessionUnresolved(
        val detail: String,
    ) : TrustedSessionResolveResult

    data class RepairRequired(
        val detail: String,
    ) : TrustedSessionResolveResult

    data class UpdateRequired(
        val detail: String,
    ) : TrustedSessionResolveResult
}

internal fun resetMaintenanceActionCapabilityFlags(
    supportsThreadCompaction: Boolean,
    supportsThreadRollback: Boolean,
    supportsBackgroundTerminalCleanup: Boolean,
): Triple<Boolean, Boolean, Boolean> {
    return if (supportsThreadCompaction && supportsThreadRollback && supportsBackgroundTerminalCleanup) {
        Triple(supportsThreadCompaction, supportsThreadRollback, supportsBackgroundTerminalCleanup)
    } else {
        Triple(true, true, true)
    }
}

class AndrodexClient(
    private val persistence: AndrodexPersistence,
) {
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient()
    private val updatesFlow = MutableSharedFlow<ClientUpdate>(extraBufferCapacity = 64)
    private val requestMutex = Mutex()
    private val socketMutex = Mutex()
    private val connectionLifecycleMutex = Mutex()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingSecureControlWaiters = mutableMapOf<String, MutableList<CompletableDeferred<String>>>()
    private val bufferedSecureControlMessages = mutableMapOf<String, ArrayDeque<String>>()

    private var webSocket: WebSocket? = null
    private var openSocketDeferred: CompletableDeferred<Unit>? = null
    private var secureSession: SecureSession? = null
    private var pendingHandshake: PendingHandshake? = null
    private var pendingTerminalConnectionUpdate: ClientUpdate.Connection? = null
    private var lastSocketCloseDetail: String? = null
    private var lastSocketFailureDetail: String? = null
    private var phoneIdentityState: PhoneIdentityState = persistence.loadPhoneIdentity() ?: generatePhoneIdentityState().also {
        persistence.savePhoneIdentity(it)
    }
    private var trustedMacRegistry: TrustedMacRegistry = persistence.loadTrustedMacRegistry()
    private var lastTrustedMacDeviceId: String? = persistence.loadLastTrustedMacDeviceId()
    private var savedRelaySession: SavedRelaySession? = persistence.loadSavedRelaySession()
    private var lastAppliedBridgeOutboundSeq: Int = persistence.loadLastAppliedBridgeOutboundSeq()
    private var trustedReconnectFailureCount = 0
    private var availableModels: List<ModelOption> = emptyList()
    private var selectedModelId: String? = persistence.loadSelectedModelId()
    private var selectedReasoningEffort: String? = persistence.loadSelectedReasoningEffort()
    private var selectedAccessMode: AccessMode = persistence.loadSelectedAccessMode()
    private var selectedServiceTier: ServiceTier? = persistence.loadSelectedServiceTier()
    private var threadRuntimeOverridesByThread = persistence.loadThreadRuntimeOverrides()
    private var collaborationModes: Set<CollaborationModeKind> = emptySet()
    private var hostAccountSnapshot: HostAccountSnapshot? = null
    private var supportsServiceTier = true
    private var supportsThreadCompaction = true
    private var supportsThreadRollback = true
    private var supportsBackgroundTerminalCleanup = true
    private var supportsThreadFork = true

    val updates: SharedFlow<ClientUpdate> = updatesFlow.asSharedFlow()

    init {
        emitPairingAvailability()
        emitRuntimeConfig()
    }

    fun hasSavedPairing(): Boolean = savedRelaySession != null

    fun currentFingerprint(): String? {
        val trusted = currentTrustedMacRecord()
        val session = savedRelaySession
        val publicKey = trusted?.macIdentityPublicKey ?: session?.macIdentityPublicKey ?: return null
        return fingerprint(publicKey)
    }

    fun currentTrustedPairSnapshot(): TrustedPairSnapshot? {
        val trusted = currentTrustedMacRecord()
        val session = savedRelaySession
        val deviceId = trusted?.macDeviceId ?: session?.macDeviceId ?: return null
        return TrustedPairSnapshot(
            deviceId = deviceId,
            relayUrl = trusted?.relayUrl ?: session?.relayUrl,
            fingerprint = fingerprint(trusted?.macIdentityPublicKey ?: session?.macIdentityPublicKey ?: return null),
            lastPairedAtEpochMs = trusted?.lastPairedAtEpochMs,
            displayName = trusted?.displayName,
            hasSavedRelaySession = session?.macDeviceId == deviceId,
        )
    }

    private fun currentTrustedMacRecord(): TrustedMacRecord? {
        val preferredDeviceId = lastTrustedMacDeviceId?.trim()?.takeIf { it.isNotEmpty() }
        if (preferredDeviceId != null) {
            trustedMacRegistry.records[preferredDeviceId]?.let { return it }
        }
        val session = savedRelaySession
        if (session != null) {
            trustedMacRegistry.records[session.macDeviceId]?.let { return it }
        }
        return trustedMacRegistry.records.values.maxByOrNull { it.lastUsedAtEpochMs ?: it.lastResolvedAtEpochMs ?: it.lastPairedAtEpochMs }
    }

    private fun reconnectCandidate(): PairingPayload? {
        savedRelaySession?.let { return it.toPairingPayload() }
        val trusted = currentTrustedMacRecord() ?: return null
        val relayUrl = trusted.relayUrl ?: return null
        val hostId = trusted.lastResolvedHostId ?: trusted.macDeviceId
        return PairingPayload(
            version = pairingQrVersion,
            relay = relayUrl,
            hostId = hostId,
            sessionId = hostId,
            macDeviceId = trusted.macDeviceId,
            macIdentityPublicKey = trusted.macIdentityPublicKey,
            bootstrapToken = null,
            expiresAt = 0L,
        )
    }

    private fun rememberLastTrustedMacDeviceId(macDeviceId: String?) {
        val normalized = macDeviceId?.trim()?.takeIf { it.isNotEmpty() }
        lastTrustedMacDeviceId = normalized
        persistence.saveLastTrustedMacDeviceId(normalized)
    }

    private fun updateTrustedMacRecord(
        macDeviceId: String,
        transform: (TrustedMacRecord) -> TrustedMacRecord,
    ) {
        val record = trustedMacRegistry.records[macDeviceId] ?: return
        val updated = transform(record)
        trustedMacRegistry = trustedMacRegistry.copy(
            records = trustedMacRegistry.records + (macDeviceId to updated),
        )
        persistence.saveTrustedMacRegistry(trustedMacRegistry)
        rememberLastTrustedMacDeviceId(macDeviceId)
    }

    private suspend fun handleTrustedReconnectFailure(
        detail: String,
        clearSavedSession: Boolean,
    ) {
        trustedReconnectFailureCount += 1
        if (clearSavedSession && trustedReconnectFailureCount >= trustedReconnectFailureThreshold) {
            clearSavedRelaySession()
        }
        disconnectInternal(clearSavedPairing = false)
        updatesFlow.emit(
            ClientUpdate.Connection(
                ConnectionStatus.DISCONNECTED,
                if (savedRelaySession == null) {
                    detail
                } else {
                    "Trusted host is still known, but the last live session could not be refreshed yet."
                },
                fingerprint = currentFingerprint(),
            )
        )
    }

    private fun clearSavedRelaySession() {
        persistence.clearSavedRelaySession()
        savedRelaySession = null
        lastAppliedBridgeOutboundSeq = 0
        emitPairingAvailability()
    }

    suspend fun connectWithPairingPayload(rawPayload: String) {
        val pairing = parsePairingPayload(rawPayload)
        resetBridgeOutboundReplayCursor()
        connect(pairing)
        val savedSession = pairing.toSavedRelaySession(lastAppliedBridgeOutboundSeq)
            ?: throw IllegalStateException("Failed to save the paired relay session.")
        persistence.saveSavedRelaySession(savedSession)
        savedRelaySession = savedSession
        rememberLastTrustedMacDeviceId(pairing.macDeviceId)
        emitPairingAvailability()
    }

    suspend fun reconnectSaved() {
        val reconnectCandidate = reconnectCandidate()
            ?: throw IllegalStateException("No saved or trusted pairing is available.")
        when (val resolved = resolveTrustedPairing(reconnectCandidate)) {
            is TrustedSessionResolveResult.Resolved -> {
                connect(resolved.pairing)
                trustedReconnectFailureCount = 0
                resolved.displayName?.let { displayName ->
                    updateTrustedMacRecord(resolved.pairing.macDeviceId) { record ->
                        record.copy(displayName = displayName)
                    }
                }
            }
            is TrustedSessionResolveResult.FallbackToSavedSession -> {
                connect(reconnectCandidate)
                trustedReconnectFailureCount = 0
            }
            is TrustedSessionResolveResult.LiveSessionUnresolved -> {
                handleTrustedReconnectFailure(
                    detail = resolved.detail,
                    clearSavedSession = savedRelaySession != null,
                )
            }
            is TrustedSessionResolveResult.RepairRequired -> {
                trustedReconnectFailureCount = 0
                emitTerminalConnectionUpdate(
                    ClientUpdate.Connection(
                        ConnectionStatus.RECONNECT_REQUIRED,
                        resolved.detail,
                    )
                )
                disconnectInternal(clearSavedPairing = false)
                updatesFlow.emit(consumePendingTerminalConnectionUpdate() ?: ClientUpdate.Connection(ConnectionStatus.RECONNECT_REQUIRED, resolved.detail))
            }
            is TrustedSessionResolveResult.UpdateRequired -> {
                trustedReconnectFailureCount = 0
                emitTerminalConnectionUpdate(
                    ClientUpdate.Connection(
                        ConnectionStatus.UPDATE_REQUIRED,
                        resolved.detail,
                    )
                )
                disconnectInternal(clearSavedPairing = false)
                updatesFlow.emit(consumePendingTerminalConnectionUpdate() ?: ClientUpdate.Connection(ConnectionStatus.UPDATE_REQUIRED, resolved.detail))
            }
        }
    }

    suspend fun forgetTrustedHost() {
        val trusted = currentTrustedMacRecord() ?: return
        if (savedRelaySession?.macDeviceId == trusted.macDeviceId) {
            clearSavedRelaySession()
        }
        trustedMacRegistry = trustedMacRegistry.copy(
            records = trustedMacRegistry.records - trusted.macDeviceId,
        )
        persistence.saveTrustedMacRegistry(trustedMacRegistry)
        if (lastTrustedMacDeviceId == trusted.macDeviceId) {
            rememberLastTrustedMacDeviceId(null)
        }
        if (secureSession?.macDeviceId == trusted.macDeviceId) {
            disconnectInternal(clearSavedPairing = false)
            updatesFlow.emit(ClientUpdate.Connection(ConnectionStatus.DISCONNECTED, "Trusted host forgotten."))
        }
        emitPairingAvailability()
    }

    suspend fun disconnect(clearSavedPairing: Boolean = false) {
        connectionLifecycleMutex.withLock {
            disconnectInternal(
                clearSavedPairing = clearSavedPairing,
            )
            updatesFlow.emit(ClientUpdate.Connection(ConnectionStatus.DISCONNECTED))
        }
    }

    suspend fun listThreads(limit: Int = 40): List<ThreadSummary> {
        return try {
            val threads = mutableListOf<ThreadSummary>()
            var nextCursor: Any? = JSONObject.NULL
            do {
                val params = JSONObject()
                    .put("sourceKinds", JSONArray().apply {
                        put("cli")
                        put("vscode")
                        put("appServer")
                        put("exec")
                        put("unknown")
                    })
                    .put("limit", limit)
                    .put("cursor", nextCursor)
                val result = sendRequest("thread/list", params)
                val page = result.optJSONArray("data")
                    ?: result.optJSONArray("items")
                    ?: result.optJSONArray("threads")
                    ?: JSONArray()
                for (index in 0 until page.length()) {
                    val thread = page.optJSONObject(index)?.let(::decodeThreadSummary) ?: continue
                    threads += thread
                }
                nextCursor = result.opt("nextCursor").takeUnless { it == null }
                    ?: result.opt("next_cursor").takeUnless { it == null }
            } while (nextCursor != null && nextCursor != JSONObject.NULL && threads.size < limit)

            val sorted = threads.sortedByDescending { it.updatedAtEpochMs ?: it.createdAtEpochMs ?: 0L }
            updatesFlow.emit(ClientUpdate.ThreadsLoaded(sorted))
            sorted
        } catch (error: RpcException) {
            if (isNoActiveWorkspaceError(error)) {
                updatesFlow.emit(ClientUpdate.ThreadsLoaded(emptyList()))
                emptyList()
            } else {
                throw error
            }
        }
    }

    suspend fun startThread(preferredProjectPath: String? = null): ThreadSummary {
        var includesServiceTier = supportsServiceTier
        while (true) {
            val params = buildThreadStartParams(
                preferredProjectPath = preferredProjectPath,
                model = runtimeModelIdentifierForTurn(),
                serviceTier = if (includesServiceTier) runtimeServiceTierForThread() else null,
            )
            val result = try {
                sendRequestWithAccessModeFallback("thread/start", params)
            } catch (error: RpcException) {
                if (consumeUnsupportedServiceTier(error, includesServiceTier)) {
                    includesServiceTier = false
                    continue
                }
                throw error
            }
            val thread = decodeThreadSummary(result.optJSONObject("thread") ?: JSONObject())
                ?: throw IllegalStateException("thread/start response did not include a thread.")
            return thread
        }
    }

    suspend fun forkThread(
        threadId: String,
        preferredProjectPath: String? = null,
        preferredModel: String? = null,
    ): ThreadSummary {
        if (!supportsThreadFork) {
            throw IllegalStateException("This host bridge does not support native thread forks yet. Update Androdex on the host and retry.")
        }

        var includesServiceTier = supportsServiceTier
        var includesSandbox = true
        var usesMinimalForkParams = false
        while (true) {
            val params = buildThreadForkParams(
                sourceThreadId = threadId,
                preferredProjectPath = preferredProjectPath,
                model = preferredModel ?: runtimeModelIdentifierForTurn(),
                serviceTier = if (includesServiceTier) runtimeServiceTierForThread(threadId) else null,
                includeSandbox = includesSandbox,
                usesMinimalForkParams = usesMinimalForkParams,
                accessMode = selectedAccessMode,
            )
            try {
                val result = sendRequestWithApprovalPolicyFallback(
                    method = "thread/fork",
                    baseParams = params,
                    context = if (includesSandbox) "sandbox" else "minimal",
                )
                val threadObject = result.optJSONObject("thread") ?: JSONObject()
                val decoded = decodeThreadSummary(threadObject)
                    ?: throw IllegalStateException("thread/fork response did not include a thread.")
                val patched = decoded.copy(
                    cwd = decoded.cwd ?: preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() },
                    forkedFromThreadId = decoded.forkedFromThreadId ?: threadId,
                )
                inheritThreadRuntimeOverrides(fromThreadId = threadId, toThreadId = patched.id)
                return patched
            } catch (error: RpcException) {
                if (consumeUnsupportedThreadFork(error)) {
                    throw IllegalStateException("This host bridge does not support native thread forks yet. Update Androdex on the host and retry.")
                }
                if (consumeUnsupportedThreadForkOverrides(error, usesMinimalForkParams)) {
                    includesServiceTier = false
                    includesSandbox = false
                    usesMinimalForkParams = true
                    continue
                }
                if (consumeUnsupportedServiceTier(error, includesServiceTier)) {
                    includesServiceTier = false
                    continue
                }
                if (includesSandbox && shouldFallbackFromSandboxPolicy(error.message)) {
                    includesSandbox = false
                    continue
                }
                throw error
            }
        }
    }

    suspend fun compactThread(threadId: String) {
        if (!supportsThreadCompaction) {
            throw IllegalStateException("This host bridge does not support native thread compaction yet. Update Androdex on the host and retry.")
        }

        resumeThread(threadId)
        try {
            sendRequest(
                "thread/compact/start",
                JSONObject().put("threadId", threadId),
            )
        } catch (error: RpcException) {
            if (consumeUnsupportedThreadCompaction(error)) {
                throw IllegalStateException("This host bridge does not support native thread compaction yet. Update Androdex on the host and retry.")
            }
            throw error
        }
    }

    suspend fun rollbackThread(
        threadId: String,
        numTurns: Int = 1,
    ): ThreadLoadResult {
        require(numTurns >= 1) { "Rollback requires dropping at least one turn." }
        if (!supportsThreadRollback) {
            throw IllegalStateException("This host bridge does not support native thread rollback yet. Update Androdex on the host and retry.")
        }

        resumeThread(threadId)
        try {
            val result = sendRequest(
                "thread/rollback",
                JSONObject()
                    .put("threadId", threadId)
                    .put("numTurns", numTurns),
            )
            val threadObject = result.optJSONObject("thread") ?: JSONObject()
            return ThreadLoadResult(
                thread = decodeThreadSummary(threadObject),
                messages = decodeMessagesFromThreadRead(threadId, threadObject),
                runSnapshot = decodeThreadRunSnapshot(threadObject),
            )
        } catch (error: RpcException) {
            if (consumeUnsupportedThreadRollback(error)) {
                throw IllegalStateException("This host bridge does not support native thread rollback yet. Update Androdex on the host and retry.")
            }
            throw error
        }
    }

    suspend fun cleanBackgroundTerminals(threadId: String) {
        if (!supportsBackgroundTerminalCleanup) {
            throw IllegalStateException("This host bridge does not support native background terminal cleanup yet. Update Androdex on the host and retry.")
        }

        resumeThread(threadId)
        try {
            sendRequest(
                "thread/backgroundTerminals/clean",
                JSONObject().put("threadId", threadId),
            )
        } catch (error: RpcException) {
            if (consumeUnsupportedBackgroundTerminalCleanup(error)) {
                throw IllegalStateException("This host bridge does not support native background terminal cleanup yet. Update Androdex on the host and retry.")
            }
            throw error
        }
    }

    suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        val result = sendRequest("workspace/listRecent", JSONObject())
        return decodeWorkspaceRecentState(result)
    }

    suspend fun fuzzyFileSearch(
        query: String,
        roots: List<String>,
        cancellationToken: String? = null,
    ): List<FuzzyFileMatch> {
        val normalizedQuery = query.trim()
        val normalizedRoots = roots.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedQuery.isEmpty() || normalizedRoots.isEmpty()) {
            return emptyList()
        }

        val params = JSONObject()
            .put("query", normalizedQuery)
            .put("roots", JSONArray(normalizedRoots))
            .put("cancellationToken", cancellationToken?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        val result = sendRequest("fuzzyFileSearch", params)
        return decodeFuzzyFileMatches(result)
    }

    suspend fun listSkills(cwds: List<String>?): List<SkillMetadata> {
        val normalizedCwds = cwds.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val params = JSONObject()
        if (normalizedCwds.isNotEmpty()) {
            params.put("cwds", JSONArray(normalizedCwds))
        }

        return try {
            decodeSkillMetadata(sendRequest("skills/list", params))
        } catch (error: RpcException) {
            if (!shouldRetrySkillsListWithCwdFallback(error.message) || normalizedCwds.isEmpty()) {
                throw error
            }
            decodeSkillMetadata(
                sendRequest(
                    "skills/list",
                    JSONObject().put("cwd", normalizedCwds.first()),
                )
            )
        }
    }

    suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult {
        val params = JSONObject()
        path?.trim()?.takeIf { it.isNotEmpty() }?.let { params.put("path", it) }
        val result = sendRequest("workspace/listDirectory", params)
        return decodeWorkspaceBrowseResult(result)
    }

    suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus {
        val result = sendRequest(
            "workspace/activate",
            JSONObject().put("cwd", cwd.trim())
        )
        return decodeWorkspaceActivationStatus(result)
    }

    suspend fun gitStatus(workingDirectory: String): GitRepoSyncResult {
        return runGitRequest {
            decodeGitRepoSyncResult(
                sendRequest("git/status", gitParams(workingDirectory))
            )
        }
    }

    suspend fun gitDiff(workingDirectory: String): GitRepoDiffResult {
        return runGitRequest {
            decodeGitRepoDiffResult(
                sendRequest("git/diff", gitParams(workingDirectory))
            )
        }
    }

    suspend fun gitCommit(
        workingDirectory: String,
        message: String,
    ): GitCommitResult {
        return runGitRequest {
            decodeGitCommitResult(
                sendRequest(
                    "git/commit",
                    gitParams(workingDirectory).put("message", message.trim()),
                )
            )
        }
    }

    suspend fun gitPush(workingDirectory: String): GitPushResult {
        return runGitRequest {
            decodeGitPushResult(
                sendRequest("git/push", gitParams(workingDirectory))
            )
        }
    }

    suspend fun gitPull(workingDirectory: String): GitPullResult {
        return runGitRequest {
            decodeGitPullResult(
                sendRequest("git/pull", gitParams(workingDirectory))
            )
        }
    }

    suspend fun gitBranchesWithStatus(workingDirectory: String): GitBranchesWithStatusResult {
        return runGitRequest {
            decodeGitBranchesWithStatusResult(
                sendRequest("git/branchesWithStatus", gitParams(workingDirectory))
            )
        }
    }

    suspend fun gitCheckout(
        workingDirectory: String,
        branch: String,
    ): GitCheckoutResult {
        return runGitRequest {
            decodeGitCheckoutResult(
                sendRequest(
                    "git/checkout",
                    gitParams(workingDirectory).put("branch", branch.trim()),
                )
            )
        }
    }

    suspend fun gitCreateBranch(
        workingDirectory: String,
        name: String,
    ): GitCreateBranchResult {
        return runGitRequest {
            decodeGitCreateBranchResult(
                sendRequest(
                    "git/createBranch",
                    gitParams(workingDirectory).put("name", name.trim()),
                )
            )
        }
    }

    suspend fun gitCreateWorktree(
        workingDirectory: String,
        name: String,
        baseBranch: String,
        changeTransfer: GitWorktreeChangeTransferMode,
    ): GitCreateWorktreeResult {
        return runGitRequest {
            decodeGitCreateWorktreeResult(
                sendRequest(
                    "git/createWorktree",
                    gitParams(workingDirectory)
                        .put("name", name.trim())
                        .put("baseBranch", baseBranch.trim())
                        .put("changeTransfer", changeTransfer.wireValue),
                )
            )
        }
    }

    suspend fun gitRemoveWorktree(
        workingDirectory: String,
        branch: String,
    ): GitRemoveWorktreeResult {
        return runGitRequest {
            decodeGitRemoveWorktreeResult(
                sendRequest(
                    "git/removeWorktree",
                    gitParams(workingDirectory).put("branch", branch.trim()),
                )
            )
        }
    }

    suspend fun loadThread(threadId: String): ThreadLoadResult {
        resumeThread(threadId)
        return try {
            val result = sendRequest(
                "thread/read",
                JSONObject()
                    .put("threadId", threadId)
                    .put("includeTurns", true),
            )
            val threadObject = result.optJSONObject("thread") ?: JSONObject()
            val summary = decodeThreadSummary(threadObject)
            val messages = decodeMessagesFromThreadRead(threadId, threadObject)
            ThreadLoadResult(
                thread = summary,
                messages = messages,
                runSnapshot = decodeThreadRunSnapshot(threadObject),
            )
        } catch (error: RpcException) {
            val lowered = error.message.lowercase(Locale.US)
            if (
                lowered.contains("not materialized yet")
                || lowered.contains("includeturns is unavailable before first user message")
            ) {
                ThreadLoadResult(
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
            } else {
                throw error
            }
        }
    }

    suspend fun readThreadRunSnapshot(threadId: String): ThreadRunSnapshot {
        resumeThread(threadId)
        val result = sendRequest(
            "thread/read",
            JSONObject()
                .put("threadId", threadId)
                .put("includeTurns", true),
        )
        val threadObject = result.optJSONObject("thread") ?: JSONObject()
        return decodeThreadRunSnapshot(threadObject)
    }

    suspend fun startTurn(
        threadId: String,
        userInput: String,
        attachments: List<ImageAttachment> = emptyList(),
        fileMentions: List<TurnFileMention> = emptyList(),
        skillMentions: List<TurnSkillMention> = emptyList(),
        collaborationMode: CollaborationModeKind? = null,
    ) {
        resumeThread(threadId)
        var compatibility = TurnRequestCompatibilityState(
            includeStructuredFileItems = fileMentions.isNotEmpty(),
            includeStructuredSkillItems = skillMentions.isNotEmpty(),
            collaborationMode = collaborationMode?.takeIf { it in collaborationModes },
            includesServiceTier = supportsServiceTier,
        )
        while (true) {
            val params = buildTurnStartParams(
                threadId = threadId,
                userInput = userInput,
                attachments = attachments,
                fileMentions = fileMentions,
                skillMentions = skillMentions,
                imageUrlKey = compatibility.imageUrlKey,
                includeStructuredFileItems = compatibility.includeStructuredFileItems,
                includeStructuredSkillItems = compatibility.includeStructuredSkillItems,
                model = runtimeModelIdentifierForTurn(),
                reasoningEffort = selectedReasoningEffortForThread(threadId),
                serviceTier = if (compatibility.includesServiceTier) runtimeServiceTierForThread(threadId) else null,
                collaborationMode = compatibility.collaborationMode,
            )
            try {
                sendRequestWithAccessModeFallback("turn/start", params)
                return
            } catch (error: RpcException) {
                compatibility.nextRetryState(error.message)?.let { nextCompatibility ->
                    compatibility = nextCompatibility
                    continue
                }
                if (consumeUnsupportedServiceTier(error, compatibility.includesServiceTier)) {
                    compatibility = compatibility.copy(includesServiceTier = false)
                    continue
                }
                throw error
            }
        }
    }

    suspend fun startReview(
        threadId: String,
        target: ComposerReviewTarget,
        baseBranch: String? = null,
    ) {
        resumeThread(threadId)
        val params = buildReviewStartParams(
            threadId = threadId,
            target = target,
            baseBranch = baseBranch,
        )
        sendRequestWithAccessModeFallback("review/start", params)
    }

    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        userInput: String,
        attachments: List<ImageAttachment> = emptyList(),
        fileMentions: List<TurnFileMention> = emptyList(),
        skillMentions: List<TurnSkillMention> = emptyList(),
        collaborationMode: CollaborationModeKind? = null,
    ) {
        resumeThread(threadId)
        var compatibility = TurnRequestCompatibilityState(
            includeStructuredFileItems = fileMentions.isNotEmpty(),
            includeStructuredSkillItems = skillMentions.isNotEmpty(),
            collaborationMode = collaborationMode?.takeIf { it in collaborationModes },
            includesServiceTier = supportsServiceTier,
        )
        while (true) {
            val params = buildTurnSteerParams(
                threadId = threadId,
                expectedTurnId = expectedTurnId,
                userInput = userInput,
                attachments = attachments,
                fileMentions = fileMentions,
                skillMentions = skillMentions,
                imageUrlKey = compatibility.imageUrlKey,
                includeStructuredFileItems = compatibility.includeStructuredFileItems,
                includeStructuredSkillItems = compatibility.includeStructuredSkillItems,
                model = runtimeModelIdentifierForTurn(),
                reasoningEffort = selectedReasoningEffortForThread(threadId),
                serviceTier = if (compatibility.includesServiceTier) runtimeServiceTierForThread(threadId) else null,
                collaborationMode = compatibility.collaborationMode,
            )
            try {
                sendRequestWithAccessModeFallback("turn/steer", params)
                return
            } catch (error: RpcException) {
                compatibility.nextRetryState(error.message)?.let { nextCompatibility ->
                    compatibility = nextCompatibility
                    continue
                }
                if (consumeUnsupportedServiceTier(error, compatibility.includesServiceTier)) {
                    compatibility = compatibility.copy(includesServiceTier = false)
                    continue
                }
                throw error
            }
        }
    }

    suspend fun interruptTurn(threadId: String, turnId: String) {
        sendRequest(
            "turn/interrupt",
            JSONObject()
                .put("threadId", threadId)
                .put("turnId", turnId),
        )
    }

    suspend fun registerPushNotifications(
        deviceToken: String,
        alertsEnabled: Boolean,
        authorizationStatus: String,
        appEnvironment: String,
    ) {
        sendRequest(
            "notifications/push/register",
            JSONObject()
                .put("deviceToken", deviceToken.trim())
                .put("alertsEnabled", alertsEnabled)
                .put("authorizationStatus", authorizationStatus.trim())
                .put("devicePlatform", "android")
                .put("appEnvironment", appEnvironment.trim())
        )
    }

    suspend fun loadRuntimeConfig() {
        val result = sendRequest(
            "model/list",
            JSONObject()
                .put("cursor", JSONObject.NULL)
                .put("limit", 50)
                .put("includeHidden", false)
        )
        availableModels = decodeModelOptions(result)
        normalizeRuntimeSelectionsAfterModelsUpdate()
        persistence.saveSelectedModelId(selectedModelId)
        persistence.saveSelectedReasoningEffort(selectedReasoningEffortForSelectedModel())
        emitRuntimeConfig()
        val bridgeSnapshot = runCatching {
            decodeHostAccountSnapshot(sendRequest("account/status/read", JSONObject()))
        }.getOrNull()
        hostAccountSnapshot = resolveInitialHostAccountSnapshot(
            currentSnapshot = hostAccountSnapshot,
            bridgeSnapshot = bridgeSnapshot,
        )
        emitAccountStatus()
    }

    suspend fun setSelectedModelId(modelId: String?) {
        selectedModelId = modelId?.trim()?.takeIf { it.isNotEmpty() }
        normalizeRuntimeSelectionsAfterModelsUpdate()
        persistence.saveSelectedModelId(selectedModelId)
        persistence.saveSelectedReasoningEffort(selectedReasoningEffortForSelectedModel())
        emitRuntimeConfig()
    }

    suspend fun setSelectedReasoningEffort(effort: String?) {
        selectedReasoningEffort = effort?.trim()?.takeIf { it.isNotEmpty() }
        normalizeRuntimeSelectionsAfterModelsUpdate()
        persistence.saveSelectedReasoningEffort(selectedReasoningEffortForSelectedModel())
        emitRuntimeConfig()
    }

    suspend fun setSelectedAccessMode(accessMode: AccessMode) {
        selectedAccessMode = accessMode
        persistence.saveSelectedAccessMode(accessMode)
        emitRuntimeConfig()
    }

    suspend fun setSelectedServiceTier(serviceTier: ServiceTier?) {
        selectedServiceTier = serviceTier
        persistence.saveSelectedServiceTier(serviceTier)
        emitRuntimeConfig()
    }

    suspend fun setThreadRuntimeOverride(
        threadId: String,
        runtimeOverride: ThreadRuntimeOverride?,
    ) {
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedOverride = runtimeOverride?.normalized()
        if (normalizedOverride == null) {
            threadRuntimeOverridesByThread = threadRuntimeOverridesByThread - normalizedThreadId
        } else {
            threadRuntimeOverridesByThread = threadRuntimeOverridesByThread + (normalizedThreadId to normalizedOverride)
        }
        persistence.saveThreadRuntimeOverrides(threadRuntimeOverridesByThread)
        emitRuntimeConfig()
    }

    suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        sendResponse(request.idValue, if (accept) "accept" else "decline")
        updatesFlow.emit(ClientUpdate.ApprovalCleared)
    }

    suspend fun respondToToolUserInput(
        request: ToolUserInputRequest,
        response: ToolUserInputResponse,
    ) {
        sendResponse(request.idValue, response.toJson())
    }

    suspend fun rejectToolUserInput(
        request: ToolUserInputRequest,
        message: String,
    ) {
        sendErrorResponse(request.idValue, -32602, message)
    }

    private suspend fun connect(pairing: PairingPayload) {
        connectionLifecycleMutex.withLock {
            disconnectInternal(clearSavedPairing = false)
            resetMaintenanceActionCapabilityDowngrades()
            updatesFlow.emit(ClientUpdate.Connection(ConnectionStatus.CONNECTING, "Connecting to relay..."))

            val relayUrl = pairing.relay.trimEnd('/')
            val attemptId = System.currentTimeMillis()
            Log.i(logTag, "connect[$attemptId] start relay=$relayUrl host=${pairing.routingId.take(8)}")
            pendingTerminalConnectionUpdate = null
            lastSocketCloseDetail = null
            lastSocketFailureDetail = null
            val request = Request.Builder()
                .url("$relayUrl/${pairing.routingId}")
                .header("x-role", "android")
                .build()

            val openDeferred = CompletableDeferred<Unit>()
            openSocketDeferred = openDeferred
            webSocket = okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(
                            logTag,
                            "connect[$attemptId] websocket open code=${response.code} message=${response.message} url=${request.url}"
                        )
                        clientScope.launch {
                            if (!isCurrentSocket(webSocket)) {
                                return@launch
                            }
                            if (!openDeferred.isCompleted) {
                                openDeferred.complete(Unit)
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientScope.launch {
                            if (!isCurrentSocket(webSocket)) {
                                return@launch
                            }
                            try {
                                handleIncomingWireText(text)
                            } catch (error: Throwable) {
                                updatesFlow.emit(ClientUpdate.Error(error.message ?: "Failed to process relay message."))
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        lastSocketCloseDetail = "code=$code reason=${reason.ifBlank { "<empty>" }}"
                        Log.w(logTag, "connect[$attemptId] websocket closing code=$code reason=$reason")
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        lastSocketCloseDetail = "code=$code reason=${reason.ifBlank { "<empty>" }}"
                        Log.w(logTag, "connect[$attemptId] websocket closed code=$code reason=$reason")
                        clientScope.launch {
                            handleSocketClosed(webSocket, code)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        lastSocketFailureDetail =
                            "responseCode=${response?.code} responseMessage=${response?.message ?: "<none>"} error=${t.message ?: "<none>"}"
                        Log.e(
                            logTag,
                            "connect[$attemptId] websocket failure responseCode=${response?.code} responseMessage=${response?.message} error=${t.message}",
                            t
                        )
                        clientScope.launch {
                            handleSocketFailure(webSocket, t)
                        }
                        if (!openDeferred.isCompleted) {
                            openDeferred.completeExceptionally(t)
                        }
                    }
                }
            )

            withTimeout(relayOpenTimeoutMs) {
                openDeferred.await()
            }

            updatesFlow.emit(ClientUpdate.Connection(ConnectionStatus.HANDSHAKING, "Performing secure handshake..."))
            performSecureHandshake(pairing)
            initializeSession()
            runCatching { loadRuntimeConfig() }
            pairing.toSavedRelaySession(lastAppliedBridgeOutboundSeq)?.let { persistedSession ->
                persistence.saveSavedRelaySession(persistedSession)
                savedRelaySession = persistedSession
            }
            trustedReconnectFailureCount = 0
            updateTrustedMacRecord(pairing.macDeviceId) { record ->
                record.copy(
                    relayUrl = pairing.relay,
                    lastResolvedHostId = pairing.routingId,
                    lastUsedAtEpochMs = System.currentTimeMillis(),
                )
            }
            pendingTerminalConnectionUpdate = null
            updatesFlow.emit(
                ClientUpdate.Connection(
                    status = ConnectionStatus.CONNECTED,
                    detail = "Connected to ${pairing.routingId.take(8)}",
                    fingerprint = secureSession?.macIdentityPublicKey?.let(::fingerprint),
                )
            )
        }
    }

    private suspend fun resolveTrustedPairing(pairing: PairingPayload): TrustedSessionResolveResult {
        val trustedMac = trustedMacRegistry.records[pairing.macDeviceId]
            ?: return TrustedSessionResolveResult.FallbackToSavedSession("No trusted host metadata is available yet.")
        val resolveUrl = trustedSessionResolveUrl(pairing.relay)
            ?: return TrustedSessionResolveResult.FallbackToSavedSession("This relay does not support trusted-session resolve.")
        val fallbackPolicy = if (savedRelaySession?.macDeviceId == pairing.macDeviceId) {
            TrustedResolveFallbackPolicy.ALLOW_SAVED_SESSION
        } else {
            TrustedResolveFallbackPolicy.REQUIRE_FRESH_LIVE_SESSION
        }

        return runCatching {
            withTimeout(trustedSessionResolveTimeoutMs) {
                val timestamp = System.currentTimeMillis()
                val nonce = encodeBase64(randomNonce(16))
                val transcriptBytes = buildTrustedSessionResolveTranscript(
                    macDeviceId = pairing.macDeviceId,
                    phoneDeviceId = phoneIdentityState.phoneDeviceId,
                    phoneIdentityPublicKey = phoneIdentityState.phoneIdentityPublicKey,
                    nonce = nonce,
                    timestamp = timestamp,
                )
                val signature = encodeBase64(
                    signEd25519(
                        privateKeyBase64 = phoneIdentityState.phoneIdentityPrivateKey,
                        payload = transcriptBytes,
                    )
                )
                val requestBody = JSONObject()
                    .put("macDeviceId", pairing.macDeviceId)
                    .put("phoneDeviceId", phoneIdentityState.phoneDeviceId)
                    .put("phoneIdentityPublicKey", phoneIdentityState.phoneIdentityPublicKey)
                    .put("nonce", nonce)
                    .put("timestamp", timestamp)
                    .put("signature", signature)
                val request = Request.Builder()
                    .url(resolveUrl)
                    .post(
                        requestBody
                            .toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
                val responseJson = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        val bodyText = response.body.string()
                        val parsedBody = runCatching { JSONObject(bodyText) }.getOrNull()
                        if (!response.isSuccessful) {
                            return@use mapTrustedSessionResolveFailure(
                                responseCode = response.code,
                                responseBody = parsedBody,
                                fallbackPolicy = fallbackPolicy,
                            )
                        }
                        parsedBody ?: JSONObject()
                    }
                }
                if (responseJson is TrustedSessionResolveResult) {
                    return@withTimeout responseJson
                }
                responseJson as JSONObject
                val resolvedSessionId = responseJson.optString("sessionId").trim()
                if (resolvedSessionId.isEmpty()) {
                    return@withTimeout mapTrustedSessionResolveFailure(
                        responseCode = 502,
                        responseBody = null,
                        fallbackPolicy = fallbackPolicy,
                        fallbackDetail = "Trusted host is known, but the relay did not return a fresh live session.",
                    )
                }
                val displayName = responseJson.optString("displayName").trim().ifEmpty { null }
                rememberLastTrustedMacDeviceId(pairing.macDeviceId)
                updateTrustedMacRecord(pairing.macDeviceId) { record ->
                    record.copy(
                        relayUrl = pairing.relay,
                        displayName = displayName ?: record.displayName,
                        lastResolvedHostId = resolvedSessionId,
                        lastResolvedAtEpochMs = timestamp,
                        lastUsedAtEpochMs = timestamp,
                    )
                }
                TrustedSessionResolveResult.Resolved(
                    pairing = pairing.copy(
                        hostId = resolvedSessionId,
                        sessionId = resolvedSessionId,
                        macIdentityPublicKey = responseJson
                            .optString("macIdentityPublicKey")
                            .trim()
                            .ifEmpty { trustedMac.macIdentityPublicKey },
                        bootstrapToken = null,
                        expiresAt = 0L,
                    ),
                    displayName = displayName,
                )
            }
        }.onFailure { error ->
            Log.w(
                logTag,
                "trusted-session resolve fallback host=${pairing.macDeviceId.take(8)} relay=$resolveUrl error=${error.message}",
            )
        }.getOrElse {
            mapTrustedSessionResolveFailure(
                responseCode = 0,
                responseBody = null,
                fallbackPolicy = fallbackPolicy,
            )
        }
    }

    private fun mapTrustedSessionResolveFailure(
        responseCode: Int,
        responseBody: JSONObject?,
        fallbackPolicy: TrustedResolveFallbackPolicy,
        fallbackDetail: String? = null,
    ): TrustedSessionResolveResult {
        val errorCode = responseBody?.optString("code")?.trim()?.ifEmpty { null }
            ?: responseBody?.optString("error")?.trim()?.ifEmpty { null }
        val detail = responseBody?.optString("message")?.trim()?.ifEmpty { null }
            ?: responseBody?.optString("error_description")?.trim()?.ifEmpty { null }
            ?: fallbackDetail
            ?: when (responseCode) {
                0 -> "Trusted host is known, but the relay could not resolve a fresh live session."
                else -> "Trusted host is known, but the relay did not provide a fresh live session."
            }
        return when (errorCode) {
            "phone_not_trusted", "phone_identity_changed", "phone_replacement_required" -> {
                TrustedSessionResolveResult.RepairRequired(detail)
            }
            "update_required" -> TrustedSessionResolveResult.UpdateRequired(detail)
            else -> {
                if (fallbackPolicy == TrustedResolveFallbackPolicy.ALLOW_SAVED_SESSION) {
                    TrustedSessionResolveResult.FallbackToSavedSession(detail)
                } else {
                    TrustedSessionResolveResult.LiveSessionUnresolved(detail)
                }
            }
        }
    }

    private suspend fun disconnectInternal(clearSavedPairing: Boolean) {
        socketMutex.withLock {
            webSocket?.close(1000, null)
            webSocket = null
            openSocketDeferred = null
            secureSession = null
            pendingHandshake = null
            lastSocketCloseDetail = null
            lastSocketFailureDetail = null
            pendingTerminalConnectionUpdate = null
            clearPendingRequests()
            clearSecureWaiters(HostTransportInterruptedException("Disconnected"))
            if (clearSavedPairing) {
                clearSavedRelaySession()
            }
        }
    }

    private suspend fun initializeSession() {
        performInitializeSessionRequest { params -> sendRequest("initialize", params) }
        sendNotification("initialized", null)
        refreshCollaborationModes()
    }

    private fun resetMaintenanceActionCapabilityDowngrades() {
        val (nextSupportsThreadCompaction, nextSupportsThreadRollback, nextSupportsBackgroundTerminalCleanup) =
            resetMaintenanceActionCapabilityFlags(
                supportsThreadCompaction = supportsThreadCompaction,
                supportsThreadRollback = supportsThreadRollback,
                supportsBackgroundTerminalCleanup = supportsBackgroundTerminalCleanup,
            )
        val changed = nextSupportsThreadCompaction != supportsThreadCompaction
            || nextSupportsThreadRollback != supportsThreadRollback
            || nextSupportsBackgroundTerminalCleanup != supportsBackgroundTerminalCleanup
        supportsThreadCompaction = nextSupportsThreadCompaction
        supportsThreadRollback = nextSupportsThreadRollback
        supportsBackgroundTerminalCleanup = nextSupportsBackgroundTerminalCleanup
        if (changed) {
            emitRuntimeConfig()
        }
    }

    private suspend fun resumeThread(threadId: String) {
        val params = JSONObject().put("threadId", threadId)
        try {
            sendRequest("thread/resume", params)
        } catch (_: Throwable) {
        }
    }

    private suspend fun performSecureHandshake(pairing: PairingPayload) {
        val trustedMac = trustedMacRegistry.records[pairing.macDeviceId]
        val handshakeMode = if (trustedMac != null) handshakeModeTrustedReconnect else handshakeModeQrBootstrap
        val expectedMacIdentityPublicKey = trustedMac?.macIdentityPublicKey ?: pairing.macIdentityPublicKey
        val routingId = pairing.routingId

        val phoneEphemeralPrivateKey = generateX25519PrivateKey()
        val clientNonce = randomNonce()
        val phoneEphemeralPublicKey = encodeBase64(phoneEphemeralPrivateKey.generatePublicKey().encoded)

        val clientHello = JSONObject()
            .put("kind", "clientHello")
            .put("protocolVersion", secureProtocolVersion)
            .put("hostId", routingId)
            .put("sessionId", pairing.sessionId ?: routingId)
            .put("handshakeMode", handshakeMode)
            .put("phoneDeviceId", phoneIdentityState.phoneDeviceId)
            .put("phoneIdentityPublicKey", phoneIdentityState.phoneIdentityPublicKey)
            .put("phoneEphemeralPublicKey", phoneEphemeralPublicKey)
            .put("clientNonce", encodeBase64(clientNonce))
        if (handshakeMode == handshakeModeQrBootstrap && !pairing.bootstrapToken.isNullOrBlank()) {
            clientHello.put("bootstrapToken", pairing.bootstrapToken)
        }

        pendingHandshake = PendingHandshake(
            mode = handshakeMode,
            transcriptBytes = ByteArray(0),
            phoneEphemeralPrivateKey = phoneEphemeralPrivateKey,
            phoneDeviceId = phoneIdentityState.phoneDeviceId,
        )
        sendRawText(clientHello.toString())

        val serverHelloRaw = waitForMatchingServerHello(
            expectedSessionId = routingId,
            expectedMacDeviceId = pairing.macDeviceId,
            expectedMacIdentityPublicKey = expectedMacIdentityPublicKey,
            expectedClientNonce = clientHello.getString("clientNonce"),
            clientNonce = clientNonce,
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
        )
        val serverHello = JSONObject(serverHelloRaw)
        val protocolVersion = serverHello.optInt("protocolVersion", 0)
        if (protocolVersion != secureProtocolVersion) {
            emitTerminalConnectionUpdate(ClientUpdate.Connection(ConnectionStatus.UPDATE_REQUIRED, "Bridge version mismatch."))
            throw IllegalStateException("This bridge uses a different secure transport version.")
        }

        val transcriptBytes = buildTranscriptBytes(
            sessionId = routingId,
            protocolVersion = protocolVersion,
            handshakeMode = serverHello.optString("handshakeMode"),
            keyEpoch = serverHello.optInt("keyEpoch"),
            macDeviceId = pairing.macDeviceId,
            phoneDeviceId = phoneIdentityState.phoneDeviceId,
            macIdentityPublicKey = serverHello.optString("macIdentityPublicKey"),
            phoneIdentityPublicKey = phoneIdentityState.phoneIdentityPublicKey,
            macEphemeralPublicKey = serverHello.optString("macEphemeralPublicKey"),
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
            clientNonce = clientNonce,
            serverNonce = decodeBase64(serverHello.optString("serverNonce")),
            expiresAtForTranscript = serverHello.optLong("expiresAtForTranscript"),
        )

        val macSignature = decodeBase64(serverHello.optString("macSignature"))
        val signatureValid = verifyEd25519(
            publicKeyBase64 = serverHello.optString("macIdentityPublicKey"),
            payload = transcriptBytes,
            signature = macSignature,
        )
        if (!signatureValid) {
            emitTerminalConnectionUpdate(
                ClientUpdate.Connection(
                    ConnectionStatus.RECONNECT_REQUIRED,
                    "The secure host signature could not be verified.",
                )
            )
            throw IllegalStateException("The secure host signature could not be verified.")
        }

        pendingHandshake = PendingHandshake(
            mode = handshakeMode,
            transcriptBytes = transcriptBytes,
            phoneEphemeralPrivateKey = phoneEphemeralPrivateKey,
            phoneDeviceId = phoneIdentityState.phoneDeviceId,
        )

        val phoneSignature = signEd25519(
            privateKeyBase64 = phoneIdentityState.phoneIdentityPrivateKey,
            payload = buildClientAuthTranscript(transcriptBytes),
        )
        sendRawText(
            JSONObject()
                .put("kind", "clientAuth")
                .put("hostId", routingId)
                .put("sessionId", pairing.sessionId ?: routingId)
                .put("phoneDeviceId", phoneIdentityState.phoneDeviceId)
                .put("keyEpoch", serverHello.optInt("keyEpoch"))
                .put("phoneSignature", encodeBase64(phoneSignature))
                .toString()
        )

        waitForMatchingSecureReady(
            expectedSessionId = routingId,
            expectedKeyEpoch = serverHello.optInt("keyEpoch"),
            expectedMacDeviceId = pairing.macDeviceId,
        )

        val sharedSecret = deriveSharedSecret(
            privateKey = phoneEphemeralPrivateKey,
            publicKeyBase64 = serverHello.optString("macEphemeralPublicKey"),
        )
        val salt = sha256(transcriptBytes)
        val infoPrefix = "${io.androdex.android.crypto.secureHandshakeTag}|$routingId|${pairing.macDeviceId}|${phoneIdentityState.phoneDeviceId}|${serverHello.optInt("keyEpoch")}"
        val phoneToMacKey = hkdfSha256(sharedSecret, salt, "$infoPrefix|phoneToMac".toByteArray(), 32)
        val macToPhoneKey = hkdfSha256(sharedSecret, salt, "$infoPrefix|macToPhone".toByteArray(), 32)

        secureSession = SecureSession(
            sessionId = routingId,
            keyEpoch = serverHello.optInt("keyEpoch"),
            macDeviceId = pairing.macDeviceId,
            macIdentityPublicKey = serverHello.optString("macIdentityPublicKey"),
            phoneToMacKey = phoneToMacKey,
            macToPhoneKey = macToPhoneKey,
            lastInboundBridgeOutboundSeq = lastAppliedBridgeOutboundSeq,
        )
        pendingHandshake = null

        if (handshakeMode == handshakeModeQrBootstrap) {
            val pairedAt = System.currentTimeMillis()
            val nextRegistry = TrustedMacRegistry(
                records = trustedMacRegistry.records + (
                    pairing.macDeviceId to TrustedMacRecord(
                        macDeviceId = pairing.macDeviceId,
                        macIdentityPublicKey = serverHello.optString("macIdentityPublicKey"),
                        lastPairedAtEpochMs = pairedAt,
                        relayUrl = pairing.relay,
                        lastResolvedHostId = pairing.routingId,
                        lastResolvedAtEpochMs = pairedAt,
                        lastUsedAtEpochMs = pairedAt,
                    )
                )
            )
            trustedMacRegistry = nextRegistry
            persistence.saveTrustedMacRegistry(nextRegistry)
            rememberLastTrustedMacDeviceId(pairing.macDeviceId)
        } else {
            updateTrustedMacRecord(pairing.macDeviceId) { record ->
                record.copy(
                    relayUrl = pairing.relay,
                    lastResolvedHostId = pairing.routingId,
                    lastResolvedAtEpochMs = System.currentTimeMillis(),
                    lastUsedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }

        sendRawText(
            JSONObject()
                .put("kind", "resumeState")
                .put("hostId", routingId)
                .put("sessionId", pairing.sessionId ?: routingId)
                .put("keyEpoch", serverHello.optInt("keyEpoch"))
                .put("lastAppliedBridgeOutboundSeq", lastAppliedBridgeOutboundSeq)
                .toString()
        )
        emitPairingAvailability()
    }

    private suspend fun waitForMatchingServerHello(
        expectedSessionId: String,
        expectedMacDeviceId: String,
        expectedMacIdentityPublicKey: String,
        expectedClientNonce: String,
        clientNonce: ByteArray,
        phoneEphemeralPublicKey: String,
    ): String {
        while (true) {
            val raw = waitForSecureControlMessage("serverHello")
            val hello = JSONObject(raw)
            val echoedNonce = hello.optString("clientNonce")
            if (echoedNonce.isNotBlank() && echoedNonce != expectedClientNonce) {
                continue
            }
            val isLegacyMatch = if (echoedNonce.isBlank()) {
                val transcript = buildTranscriptBytes(
                    sessionId = expectedSessionId,
                    protocolVersion = hello.optInt("protocolVersion"),
                    handshakeMode = hello.optString("handshakeMode"),
                    keyEpoch = hello.optInt("keyEpoch"),
                    macDeviceId = hello.optString("macDeviceId"),
                    phoneDeviceId = phoneIdentityState.phoneDeviceId,
                    macIdentityPublicKey = hello.optString("macIdentityPublicKey"),
                    phoneIdentityPublicKey = phoneIdentityState.phoneIdentityPublicKey,
                    macEphemeralPublicKey = hello.optString("macEphemeralPublicKey"),
                    phoneEphemeralPublicKey = phoneEphemeralPublicKey,
                    clientNonce = clientNonce,
                    serverNonce = decodeBase64(hello.optString("serverNonce")),
                    expiresAtForTranscript = hello.optLong("expiresAtForTranscript"),
                )
                verifyEd25519(
                    publicKeyBase64 = hello.optString("macIdentityPublicKey"),
                    payload = transcript,
                    signature = decodeBase64(hello.optString("macSignature")),
                )
            } else {
                true
            }

            if (
                (hello.optString("hostId").ifBlank { hello.optString("sessionId") }) == expectedSessionId
                && hello.optString("macDeviceId") == expectedMacDeviceId
                && hello.optString("macIdentityPublicKey") == expectedMacIdentityPublicKey
                && isLegacyMatch
            ) {
                return raw
            }
        }
    }

    private suspend fun waitForSecureControlMessage(kind: String): String {
        val secureError = popBufferedSecureError()
        if (secureError != null) {
            throw IllegalStateException(secureError)
        }

        val buffered = bufferedSecureControlMessages[kind]
        if (buffered != null && buffered.isNotEmpty()) {
            return buffered.pollFirst() ?: error("Buffered secure control message missing.")
        }

        val deferred = CompletableDeferred<String>()
        requestMutex.withLock {
            pendingSecureControlWaiters.getOrPut(kind) { mutableListOf() }.add(deferred)
        }
        return withTimeout(secureHandshakeTimeoutMs) { deferred.await() }
    }

    private suspend fun handleIncomingWireText(text: String) {
        val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (payload.optString("kind")) {
            "serverHello", "secureReady", "secureError" -> bufferSecureControlMessage(payload.optString("kind"), text)
            "encryptedEnvelope" -> handleEncryptedEnvelope(payload)
            else -> {
                if (payload.has("method") || payload.has("id")) {
                    processIncomingApplicationText(text)
                }
            }
        }
    }

    private suspend fun handleEncryptedEnvelope(envelope: JSONObject) {
        val session = secureSession ?: return
        if (envelope.optString("sessionId") != session.sessionId) {
            return
        }
        if (envelope.optInt("keyEpoch") != session.keyEpoch) {
            return
        }
        if (envelope.optString("sender") != "mac") {
            return
        }

        val counter = envelope.optInt("counter")
        if (counter <= session.lastInboundCounter) {
            return
        }

        val plaintext = try {
            aesGcmDecrypt(
                key = session.macToPhoneKey,
                nonce = secureNonce("mac", counter),
                ciphertext = decodeBase64(envelope.optString("ciphertext")),
                tag = decodeBase64(envelope.optString("tag")),
            )
        } catch (_: Throwable) {
            updatesFlow.emit(
                ClientUpdate.Connection(
                    ConnectionStatus.RECONNECT_REQUIRED,
                    "The secure envelope could not be verified.",
                )
            )
            return
        }
        val payload = JSONObject(plaintext.toString(Charsets.UTF_8))
        session.lastInboundCounter = counter
        val bridgeOutboundSeq = payload.optInt("bridgeOutboundSeq", -1)
        if (!shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = bridgeOutboundSeq,
                lastAppliedBridgeOutboundSeq = lastAppliedBridgeOutboundSeq,
            )
        ) {
            return
        }
        if (bridgeOutboundSeq > 0) {
            lastAppliedBridgeOutboundSeq = bridgeOutboundSeq
            persistence.saveLastAppliedBridgeOutboundSeq(bridgeOutboundSeq)
        }
        processIncomingApplicationText(payload.optString("payloadText"))
    }

    private suspend fun processIncomingApplicationText(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.has("method")) {
            val method = message.optString("method")
            if (message.has("id")) {
                handleServerRequest(method, message.get("id"), message.optJSONObject("params"))
            } else {
                handleNotification(method, message.optJSONObject("params"))
            }
            return
        }

        if (message.has("id")) {
            val id = message.get("id").toString()
            val continuation = pendingResponses.remove(id) ?: return
            continuation.complete(message)
        }
    }

    private suspend fun handleServerRequest(method: String, idValue: Any, params: JSONObject?) {
        when {
            isApprovalRequestMethod(method) -> {
                updatesFlow.emit(
                    ClientUpdate.ApprovalRequested(
                        ApprovalRequest(
                            idValue = idValue,
                            method = method,
                            command = params?.stringOrNull("command"),
                            reason = params?.stringOrNull("reason"),
                            threadId = params?.stringOrNull("threadId", "thread_id"),
                            turnId = params?.stringOrNull("turnId", "turn_id"),
                        )
                    )
                )
            }

            isToolUserInputRequestMethod(method) -> {
                updatesFlow.emit(
                    ClientUpdate.ToolUserInputRequested(
                        decodeToolUserInputRequest(
                            method = method,
                            idValue = idValue,
                            params = params,
                        )
                    )
                )
            }

            else -> sendErrorResponse(idValue, -32601, "Unsupported request method: $method")
        }
    }

    private fun decodeToolUserInputRequest(
        method: String,
        idValue: Any,
        params: JSONObject?,
    ): ToolUserInputRequest {
        val candidateContainers = listOfNotNull(
            params,
            params?.objectOrNull("input"),
            params?.objectOrNull("schema"),
            params?.objectOrNull("request"),
            params?.objectOrNull("input")?.objectOrNull("schema"),
            params?.objectOrNull("msg", "event"),
        )
        val questions = candidateContainers.firstNotNullOfOrNull { container ->
            container.arrayOrNull("questions")
        }?.let(::decodeToolUserInputQuestions).orEmpty()

        return ToolUserInputRequest(
            idValue = idValue,
            method = method,
            threadId = extractThreadId(params),
            turnId = extractTurnId(params),
            itemId = extractItemId(params),
            title = candidateContainers.firstNotNullOfOrNull { container ->
                container.stringOrNull("title", "label", "prompt", "question")
            },
            message = candidateContainers.firstNotNullOfOrNull { container ->
                container.stringOrNull("message", "description", "reason", "text")
            },
            questions = questions,
            rawPayload = params?.toString() ?: "{}",
        )
    }

    private fun decodeToolUserInputQuestions(questions: JSONArray): List<ToolUserInputQuestion> {
        val decoded = mutableListOf<ToolUserInputQuestion>()
        for (index in 0 until questions.length()) {
            val candidate = questions.optJSONObject(index) ?: continue
            val questionId = candidate.stringOrNull("id", "key", "name") ?: continue
            val questionText = candidate.stringOrNull("question", "prompt", "label", "title") ?: continue
            decoded += ToolUserInputQuestion(
                id = questionId,
                header = candidate.stringOrNull("header"),
                question = questionText,
                options = decodeToolUserInputOptions(candidate.arrayOrNull("options")),
                isOther = candidate.booleanLikeOrFalse("isOther", "other", "allowOther"),
                isSecret = candidate.booleanLikeOrFalse("isSecret", "secret"),
            )
        }
        return decoded
    }

    private fun decodeToolUserInputOptions(options: JSONArray?): List<ToolUserInputOption> {
        if (options == null) {
            return emptyList()
        }

        val decoded = mutableListOf<ToolUserInputOption>()
        for (index in 0 until options.length()) {
            val optionObject = options.optJSONObject(index)
            if (optionObject != null) {
                val label = optionObject.stringOrNull("label") ?: continue
                decoded += ToolUserInputOption(
                    label = label,
                    description = optionObject.stringOrNull("description"),
                )
                continue
            }

            val label = options.optString(index).trim()
            if (label.isNotEmpty()) {
                decoded += ToolUserInputOption(label = label)
            }
        }
        return decoded
    }

    private suspend fun handleNotification(method: String, params: JSONObject?) {
        if (handleThreadLifecycleNotification(method, params)) {
            return
        }
        if (handleTurnLifecycleNotification(method, params)) {
            return
        }
        if (handleRuntimeStatusNotification(method, params)) {
            return
        }
        handleItemProtocolNotification(method, params)
    }

    private suspend fun handleThreadLifecycleNotification(method: String, params: JSONObject?): Boolean {
        return when (method) {
            "thread/started", "thread/name/updated", "thread/status/changed" -> {
                threadLifecycleUpdateForNotification(method, params)?.let { update ->
                    updatesFlow.emit(update)
                }
                try {
                    listThreads()
                } catch (_: Throwable) {
                }
                true
            }

            else -> false
        }
    }

    private suspend fun handleTurnLifecycleNotification(method: String, params: JSONObject?): Boolean {
        return when (method) {
            "turn/started" -> {
                updatesFlow.emit(
                    ClientUpdate.TurnStarted(
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                    )
                )
                true
            }

            "turn/completed", "turn/failed", "error" -> {
                val threadId = extractThreadId(params)
                val turnId = extractTurnId(params)
                if (method == "error" && threadId.isNullOrBlank() && turnId.isNullOrBlank()) {
                    extractTurnErrorMessage(params)?.let { updatesFlow.emit(ClientUpdate.Error(it)) }
                    return true
                }
                val terminalState = when (method) {
                    "turn/failed" -> TurnTerminalState.FAILED
                    "error" -> if (extractWillRetry(params)) null else TurnTerminalState.FAILED
                    else -> extractTurnTerminalState(params)
                } ?: return true
                updatesFlow.emit(
                    ClientUpdate.TurnCompleted(
                        threadId = threadId,
                        turnId = turnId,
                        terminalState = terminalState,
                        errorMessage = extractTurnErrorMessage(params),
                        willRetry = extractWillRetry(params),
                    )
                )
                true
            }

            "turn/plan/updated" -> {
                val steps = extractPlanSteps(params)
                if (steps.isNotEmpty()) {
                    updatesFlow.emit(
                        ClientUpdate.PlanUpdated(
                            threadId = extractThreadId(params),
                            turnId = extractTurnId(params),
                            explanation = extractPlanExplanation(params),
                            steps = steps,
                        )
                    )
                }
                true
            }

            else -> false
        }
    }

    private suspend fun handleRuntimeStatusNotification(method: String, params: JSONObject?): Boolean {
        return when (method) {
            "thread/tokenUsage/updated" -> {
                decodeThreadTokenUsage(params ?: JSONObject())?.let { usage ->
                    updatesFlow.emit(
                        ClientUpdate.TokenUsageUpdated(
                            threadId = extractThreadId(params),
                            usage = usage,
                        )
                    )
                }
                true
            }

            "account/updated", "account/login/completed", "account/rateLimits/updated" -> {
                applyLiveHostAccountUpdate(extractHostAccountSnapshotPayload(params))
                true
            }

            "skills/changed" -> {
                updatesFlow.emit(
                    ClientUpdate.SkillsChanged(
                        cwds = extractSkillChangeCwds(params),
                    )
                )
                true
            }

            else -> false
        }
    }

    private suspend fun handleItemProtocolNotification(method: String, params: JSONObject?) {
        if (emitSubagentActionUpdateIfPresent(method, params)) {
            return
        }
        extractExecutionProtocolUpdate(method, params)?.let { update ->
            updatesFlow.emit(update)
            return
        }
        when (method) {
            "item/agentMessage/delta",
            "codex/event/agent_message_content_delta",
            "codex/event/agent_message_delta" -> {
                val delta = extractAssistantDeltaText(params) ?: return
                updatesFlow.emit(
                    ClientUpdate.AssistantDelta(
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        delta = delta,
                    )
                )
            }

            "item/plan/delta" -> {
                val delta = extractPlanDeltaText(params) ?: return
                updatesFlow.emit(
                    ClientUpdate.PlanDelta(
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        delta = delta,
                    )
                )
            }

            "item/reasoning/textDelta" -> {
                val delta = extractReasoningDeltaText(params) ?: return
                updatesFlow.emit(
                    ClientUpdate.ReasoningDelta(
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        delta = delta,
                    )
                )
            }

            "item/completed", "codex/event/item_completed", "codex/event/agent_message" -> {
                extractReasoningCompletedText(params)?.let { text ->
                    updatesFlow.emit(
                        ClientUpdate.ReasoningCompleted(
                            threadId = extractThreadId(params),
                            turnId = extractTurnId(params),
                            itemId = extractItemId(params),
                            text = text,
                        )
                    )
                    return
                }
                extractPlanCompletedContent(params)?.let { content ->
                    updatesFlow.emit(
                        ClientUpdate.PlanCompleted(
                            threadId = extractThreadId(params),
                            turnId = extractTurnId(params),
                            itemId = extractItemId(params),
                            text = content.text,
                            explanation = content.explanation,
                            steps = content.steps,
                        )
                    )
                    return
                }
                val text = extractAssistantCompletedText(params) ?: return
                updatesFlow.emit(
                    ClientUpdate.AssistantCompleted(
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        text = text,
                    )
                )
            }
        }
    }

    private suspend fun emitSubagentActionUpdateIfPresent(method: String, params: JSONObject?): Boolean {
        val action = extractSubagentAction(params) ?: return false
        val normalizedMethod = normalizeItemType(method)
        val isStreaming = !(
            normalizedMethod.contains("completed")
                || normalizedMethod.contains("finished")
                || normalizedMethod.contains("done")
                || method == "codex/event/agent_message"
        )
        updatesFlow.emit(
            ClientUpdate.SubagentActionUpdate(
                threadId = extractThreadId(params),
                turnId = extractTurnId(params),
                itemId = extractItemId(params),
                action = action,
                isStreaming = isStreaming,
            )
        )
        return true
    }

    private suspend fun applyLiveHostAccountUpdate(payload: JSONObject?) {
        val nativeSnapshot = payload?.let { rawPayload ->
            applyHostAccountUpdatePayload(
                currentSnapshot = hostAccountSnapshot,
                payload = rawPayload,
                origin = HostAccountSnapshotOrigin.NATIVE_LIVE,
            )
        }
        val resolvedSnapshot = nativeSnapshot ?: run {
            val bridgePayload = runCatching {
                sendRequest("account/status/read", JSONObject())
            }.getOrNull()
            val bridgeSnapshot = bridgePayload?.let(::decodeHostAccountSnapshot)
            resolveLiveHostAccountSnapshot(
                currentSnapshot = hostAccountSnapshot,
                bridgePayload = bridgePayload,
                bridgeSnapshot = bridgeSnapshot,
            )
        }
        hostAccountSnapshot = resolvedSnapshot
        updatesFlow.emit(ClientUpdate.AccountStatusLoaded(snapshot = resolvedSnapshot))
    }

    private suspend fun sendRequest(method: String, params: JSONObject?): JSONObject {
        val requestId = UUID.randomUUID().toString()
        val request = JSONObject()
            .put("id", requestId)
            .put("method", method)
        if (params != null) {
            request.put("params", params)
        }

        val responseDeferred = CompletableDeferred<JSONObject>()
        pendingResponses[requestId] = responseDeferred
        sendMessage(request)
        val response = try {
            withTimeout(timeoutForMethod(method)) { responseDeferred.await() }
        } catch (error: TimeoutCancellationException) {
            pendingResponses.remove(requestId)
            throw HostTransportInterruptedException(
                "Timed out waiting for $method response from the host."
            )
        } catch (error: Throwable) {
            pendingResponses.remove(requestId)
            throw error
        }
        val error = response.optJSONObject("error")
        if (error != null) {
            throw RpcException(
                code = error.optInt("code"),
                message = error.optString("message"),
                data = error.optJSONObject("data"),
            )
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private suspend fun sendRequestWithApprovalPolicyFallback(
        method: String,
        baseParams: JSONObject,
        context: String,
    ): JSONObject {
        var lastError: RpcException? = null
        val policies = selectedAccessMode.approvalPolicyCandidates
        for ((index, policy) in policies.withIndex()) {
            val params = clonedJson(baseParams).put("approvalPolicy", policy)
            try {
                return sendRequest(method, params)
            } catch (error: RpcException) {
                lastError = error
                if (index < policies.lastIndex && shouldRetryWithApprovalPolicyFallback(error.message)) {
                    continue
                }
                throw error
            }
        }
        throw lastError ?: IllegalStateException("$method $context failed without a response.")
    }

    private suspend fun sendRequestWithAccessModeFallback(
        method: String,
        baseParams: JSONObject,
    ): JSONObject {
        var sandboxMode = AccessModeSandboxMode.SANDBOX_POLICY
        while (true) {
            val params = applyAccessModeParams(baseParams, selectedAccessMode, sandboxMode)
            try {
                return sendRequestWithApprovalPolicyFallback(
                    method = method,
                    baseParams = params,
                    context = sandboxMode.name.lowercase(Locale.US),
                )
            } catch (error: RpcException) {
                sandboxMode = when {
                    sandboxMode == AccessModeSandboxMode.SANDBOX_POLICY && shouldFallbackFromSandboxPolicy(error.message) -> {
                        AccessModeSandboxMode.LEGACY_SANDBOX
                    }

                    sandboxMode == AccessModeSandboxMode.LEGACY_SANDBOX && shouldFallbackFromSandboxPolicy(error.message) -> {
                        AccessModeSandboxMode.MINIMAL
                    }

                    else -> throw error
                }
            }
        }
    }

    private suspend fun sendNotification(method: String, params: JSONObject?) {
        val payload = JSONObject().put("method", method)
        if (params != null) {
            payload.put("params", params)
        }
        sendMessage(payload)
    }

    private suspend fun sendResponse(idValue: Any, resultValue: Any?) {
        sendMessage(
            JSONObject()
                .put("id", idValue)
                .put("result", resultValue.toJsonValue())
        )
    }

    private suspend fun sendErrorResponse(idValue: Any, code: Int, message: String) {
        sendMessage(
            JSONObject()
                .put("id", idValue)
                .put(
                    "error",
                    JSONObject()
                        .put("code", code)
                        .put("message", message)
                )
        )
    }

    private suspend fun sendMessage(message: JSONObject) {
        val secureText = secureWireText(message.toString())
        sendRawText(secureText)
    }

    private suspend fun sendRawText(text: String) {
        val socket = webSocket ?: throw IllegalStateException(buildNotConnectedDetail())
        val sent = withContext(Dispatchers.IO) { socket.send(text) }
        if (!sent) {
            Log.e(logTag, "socket send returned false payloadLength=${text.length}")
            throw IOException("Failed to write to relay socket.")
        }
        Log.d(logTag, "socket send ok payloadLength=${text.length}")
    }

    private fun secureWireText(plaintext: String): String {
        val session = secureSession ?: throw IllegalStateException("Secure session is not ready yet.")
        val payload = JSONObject()
            .put("bridgeOutboundSeq", JSONObject.NULL)
            .put("payloadText", plaintext)
        val nonce = secureNonce("android", session.nextOutboundCounter)
        val (ciphertext, tag) = aesGcmEncrypt(
            key = session.phoneToMacKey,
            nonce = nonce,
            plaintext = payload.toString().toByteArray(Charsets.UTF_8),
        )
        val envelope = JSONObject()
            .put("kind", "encryptedEnvelope")
            .put("v", secureProtocolVersion)
            .put("sessionId", session.sessionId)
            .put("keyEpoch", session.keyEpoch)
            .put("sender", "android")
            .put("counter", session.nextOutboundCounter)
            .put("ciphertext", encodeBase64(ciphertext))
            .put("tag", encodeBase64(tag))
        session.nextOutboundCounter += 1
        return envelope.toString()
    }

    private suspend fun handleSocketClosed(closedSocket: WebSocket, code: Int) {
        val isCurrent = socketMutex.withLock {
            if (webSocket !== closedSocket) {
                false
            } else {
                webSocket = null
                openSocketDeferred = null
                true
            }
        }
        if (!isCurrent) {
            return
        }

        secureSession = null
        pendingHandshake = null
        clearPendingRequests()
        clearSecureWaiters(HostTransportInterruptedException("Socket closed"))

        if (shouldClearSavedRelaySessionForSocketClose(code)) {
            clearSavedRelaySession()
        }

        val update = connectionUpdateForSocketClose(
            code = code,
            hasTrustedHost = currentTrustedMacRecord() != null,
            pendingTerminalUpdate = consumePendingTerminalConnectionUpdate(),
        )
        if (update != null) {
            updatesFlow.emit(update)
        }
    }

    private suspend fun handleSocketFailure(failedSocket: WebSocket, error: Throwable) {
        val isCurrent = socketMutex.withLock {
            if (webSocket !== failedSocket) {
                false
            } else {
                webSocket = null
                openSocketDeferred = null
                true
            }
        }
        if (!isCurrent) {
            return
        }

        secureSession = null
        pendingHandshake = null
        clearPendingRequests()
        clearSecureWaiters(HostTransportInterruptedException("Socket failure"))

        val update = connectionUpdateForSocketFailure(
            savedPairingAvailable = hasSavedPairing(),
            errorMessage = error.message,
            pendingTerminalUpdate = consumePendingTerminalConnectionUpdate(),
        )
        if (update != null) {
            updatesFlow.emit(update)
        }
    }

    private fun buildNotConnectedDetail(): String {
        val closeDetail = lastSocketCloseDetail
        if (!closeDetail.isNullOrBlank()) {
            return "Not connected. Last relay close: $closeDetail"
        }
        val failureDetail = lastSocketFailureDetail
        if (!failureDetail.isNullOrBlank()) {
            return "Not connected. Last relay failure: $failureDetail"
        }
        return "Not connected."
    }

    private suspend fun bufferSecureControlMessage(kind: String, rawText: String) {
        if (kind == "secureError") {
            val error = JSONObject(rawText)
            val code = error.optString("code")
            val message = error.optString("message").ifBlank { "Secure handshake failed." }
            emitTerminalConnectionUpdate(secureErrorConnectionUpdate(code, message))
        }

        val waiter = requestMutex.withLock {
            val waiters = pendingSecureControlWaiters[kind]
            if (waiters.isNullOrEmpty()) {
                bufferedSecureControlMessages.getOrPut(kind) { ArrayDeque() }.add(rawText)
                null
            } else {
                waiters.removeAt(0).also {
                    if (waiters.isEmpty()) {
                        pendingSecureControlWaiters.remove(kind)
                    }
                }
            }
        }
        waiter?.complete(rawText)
    }

    private fun emitPairingAvailability() {
        updatesFlow.tryEmit(ClientUpdate.PairingAvailability(hasSavedPairing(), currentFingerprint()))
    }

    private fun emitRuntimeConfig() {
        updatesFlow.tryEmit(
            ClientUpdate.RuntimeConfigLoaded(
                models = availableModels,
                selectedModelId = selectedModelOption()?.stableIdentifier ?: selectedModelId,
                selectedReasoningEffort = selectedReasoningEffortForSelectedModel(),
                selectedAccessMode = selectedAccessMode,
                selectedServiceTier = selectedServiceTier,
                supportsServiceTier = supportsServiceTier,
                supportsThreadCompaction = supportsThreadCompaction,
                supportsThreadRollback = supportsThreadRollback,
                supportsBackgroundTerminalCleanup = supportsBackgroundTerminalCleanup,
                supportsThreadFork = supportsThreadFork,
                collaborationModes = collaborationModes,
                threadRuntimeOverridesByThread = threadRuntimeOverridesByThread,
            )
        )
    }

    private suspend fun refreshCollaborationModes() {
        collaborationModes = try {
            decodeCollaborationModes(sendRequest("collaborationMode/list", JSONObject()))
        } catch (error: Throwable) {
            val resolvedModes = resolveCollaborationModesAfterProbeFailure(
                currentModes = collaborationModes,
                failure = error,
            )
            if (resolvedModes == collaborationModes) {
                Log.w(
                    logTag,
                    "collaborationMode/list probe failed; preserving ${collaborationModes.size} previously discovered mode(s)",
                    error,
                )
            } else {
                Log.i(logTag, "collaborationMode/list unsupported; clearing discovered collaboration modes")
            }
            resolvedModes
        }
        emitRuntimeConfig()
    }

    private fun emitAccountStatus() {
        updatesFlow.tryEmit(
            ClientUpdate.AccountStatusLoaded(
                snapshot = hostAccountSnapshot,
            )
        )
    }

    private fun parsePairingPayload(rawPayload: String): PairingPayload {
        val payload = PairingPayload.fromJson(JSONObject(rawPayload))
            ?: throw IllegalArgumentException("The QR payload is missing required pairing fields.")
        require(payload.version == 2 || payload.version == pairingQrVersion) {
            "Unsupported pairing format. Update the Android client or the bridge."
        }
        require(payload.relay.isNotBlank()) { "The pairing payload is missing the relay URL." }
        require(payload.routingId.isNotBlank()) { "The pairing payload is missing the host identity." }
        if (payload.version >= pairingQrVersion) {
            require(!payload.bootstrapToken.isNullOrBlank()) { "The pairing payload is missing the bootstrap token." }
            val expiryWithSkew = payload.expiresAt + (secureClockSkewToleranceSeconds * 1000)
            require(expiryWithSkew >= System.currentTimeMillis()) {
                "The pairing QR code has expired. Generate a new QR code from the daemon."
            }
        } else {
            require(!payload.sessionId.isNullOrBlank()) { "The pairing payload is missing the session ID." }
            val expiryWithSkew = payload.expiresAt + (secureClockSkewToleranceSeconds * 1000)
            require(expiryWithSkew >= System.currentTimeMillis()) {
                "The pairing QR code has expired. Generate a new QR code from the bridge."
            }
        }
        return payload
    }

    private fun extractThreadId(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("threadId", "thread_id", "conversationId", "conversation_id")
            ?: params.optJSONObject("thread")?.stringOrNull("id")
            ?: params.optJSONObject("turn")?.stringOrNull("threadId", "thread_id")
            ?: params.optJSONObject("item")?.stringOrNull("threadId", "thread_id")
            ?: params.objectOrNull("msg", "event")?.let(::extractThreadId)
    }

    private fun extractTurnId(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("turnId", "turn_id", "id")
            ?: params.optJSONObject("turn")?.stringOrNull("id", "turnId", "turn_id")
            ?: params.optJSONObject("item")?.stringOrNull("turnId", "turn_id")
            ?: params.objectOrNull("msg", "event")?.let(::extractTurnId)
    }

    private fun extractItemId(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("itemId", "item_id", "messageId", "message_id")
            ?: params.optJSONObject("item")?.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")
            ?: params.objectOrNull("msg", "event")?.let(::extractItemId)
    }

    private fun extractExecutionProtocolUpdate(method: String, params: JSONObject?): ClientUpdate? {
        return extractCommandExecutionUpdate(method, params)
            ?: extractExecutionStyleUpdate(method, params)
    }

    private fun extractCommandExecutionUpdate(method: String, params: JSONObject?): ClientUpdate.CommandExecutionUpdate? {
        val normalizedMethod = normalizeItemType(method)
        val item = extractProtocolItemCandidate(params)
        val methodLooksCommandLike = normalizedMethod.contains("commandexecution")
            || normalizedMethod.contains("commandexec")
            || normalizedMethod.contains("terminalcommand")
        val itemLooksCommandLike = item?.let { isCommandExecutionItemType(it.optString("type")) } == true
        if (!methodLooksCommandLike && !itemLooksCommandLike) {
            return null
        }

        val source = item
            ?: params?.objectOrNull("msg", "event")
            ?: params
            ?: return null
        val commandData = decodeCommandExecutionContent(source)
        val isStreaming = when {
            normalizedMethod.contains("completed")
                || normalizedMethod.contains("finished")
                || normalizedMethod.contains("done")
                || normalizedMethod == "itemcompleted"
                || normalizedMethod == "codexeventitemcompleted"
                || normalizedMethod == "codexeventagentmessage" -> false
            normalizedMethod.contains("delta") || normalizedMethod.contains("updated") -> true
            else -> !isTerminalCommandStatus(commandData.status)
        }
        return ClientUpdate.CommandExecutionUpdate(
            threadId = extractThreadId(params),
            turnId = extractTurnId(params),
            itemId = resolveExecutionUpdateItemId(params, item),
            command = commandData.command,
            status = commandData.status,
            text = commandData.text,
            isStreaming = isStreaming,
            execution = commandData.execution,
        )
    }

    private fun extractExecutionStyleUpdate(method: String, params: JSONObject?): ClientUpdate.ExecutionUpdate? {
        val item = extractProtocolItemCandidate(params) ?: return null
        if (!isExecutionStyleItemType(item.optString("type"))) {
            return null
        }

        val normalizedMethod = normalizeItemType(method)
        val executionData = decodeExecutionStyleContent(item)
        val isStreaming = when {
            normalizedMethod.contains("completed")
                || normalizedMethod.contains("finished")
                || normalizedMethod.contains("done")
                || normalizedMethod == "itemcompleted"
                || normalizedMethod == "codexeventitemcompleted"
                || normalizedMethod == "codexeventagentmessage" -> false
            normalizedMethod.contains("delta") || normalizedMethod.contains("updated") -> true
            else -> !isTerminalCommandStatus(executionData.status)
        }
        return ClientUpdate.ExecutionUpdate(
            threadId = extractThreadId(params),
            turnId = extractTurnId(params),
            itemId = resolveExecutionUpdateItemId(params, item),
            text = executionData.text,
            isStreaming = isStreaming,
            execution = executionData.execution,
        )
    }

    private fun extractAssistantDeltaText(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("delta")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("delta")
    }

    private fun extractPlanDeltaText(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("delta")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("delta")
    }

    private fun extractReasoningDeltaText(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("delta")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("delta")
    }

    private fun extractSubagentAction(params: JSONObject?): io.androdex.android.model.SubagentAction? {
        if (params == null) return null
        val candidateItems = listOfNotNull(
            extractProtocolItemCandidate(params),
            params.objectOrNull("msg", "event"),
            params.objectOrNull("event"),
            params,
        )
        candidateItems.forEach { candidate ->
            decodeSubagentActionItem(candidate.toRawMap())?.let { return it }
        }
        return null
    }

    private fun extractAssistantCompletedText(params: JSONObject?): String? {
        if (params == null) return null
        val item = params.objectOrNull("item")
            ?: params.objectOrNull("msg", "event")?.optJSONObject("item")
        if (item != null) {
            val type = normalizeItemType(item.optString("type"))
            val role = item.optString("role").lowercase(Locale.US)
            if (type == "agentmessage" || type == "assistantmessage" || (type == "message" && role != "user")) {
                return item.stringOrNull("text", "message")
                    ?: decodeThreadReadAssistantContent(item)
            }
        }
        return params.stringOrNull("message")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("message")
    }

    private fun extractReasoningCompletedText(params: JSONObject?): String? {
        if (params == null) return null
        val item = params.objectOrNull("item")
            ?: params.objectOrNull("msg", "event")?.optJSONObject("item")
            ?: return null
        if (normalizeItemType(item.optString("type")) != "reasoning") {
            return null
        }
        return decodeReasoningText(item)
    }

    private fun extractPlanCompletedContent(params: JSONObject?): DecodedPlanContent? {
        if (params == null) return null
        val item = params.objectOrNull("item")
            ?: params.objectOrNull("msg", "event")?.optJSONObject("item")
            ?: return null
        if (normalizeItemType(item.optString("type")) != "plan") {
            return null
        }
        return decodePlanContent(item)
    }

    private fun extractPlanExplanation(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("explanation")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("explanation")
    }

    private fun extractPlanSteps(params: JSONObject?): List<PlanStep> {
        if (params == null) return emptyList()
        val plan = params.optJSONArray("plan")
            ?: params.objectOrNull("msg", "event")?.optJSONArray("plan")
            ?: return emptyList()
        return decodePlanSteps(plan)
    }

    private fun extractThreadStatus(params: JSONObject?): String? {
        if (params == null) return null
        return params.objectOrNull("status")?.stringOrNull("type", "status")
            ?: params.stringOrNull("status")
            ?: params.objectOrNull("msg", "event")?.let(::extractThreadStatus)
    }

    private fun extractTurnErrorMessage(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("message", "error")
            ?: params.objectOrNull("msg", "event")?.let(::extractTurnErrorMessage)
    }

    private fun extractWillRetry(params: JSONObject?): Boolean {
        if (params == null) return false
        return params.optBoolean("willRetry", params.optBoolean("will_retry", false))
            || params.objectOrNull("msg", "event")?.let(::extractWillRetry) == true
    }

    private fun extractTurnTerminalState(params: JSONObject?): TurnTerminalState? {
        val normalizedStatus = normalizeTurnStatus(
            params?.objectOrNull("status")?.stringOrNull("type", "status")
                ?: params?.stringOrNull("status")
                ?: params?.objectOrNull("turn")?.stringOrNull("status")
        )
        return when {
            normalizedStatus == null -> TurnTerminalState.COMPLETED
            isStoppedTurnStatus(normalizedStatus) -> TurnTerminalState.STOPPED
            isFailedTurnStatus(normalizedStatus) -> TurnTerminalState.FAILED
            isCompletedTurnStatus(normalizedStatus) -> TurnTerminalState.COMPLETED
            else -> TurnTerminalState.COMPLETED
        }
    }

    private fun extractHostAccountSnapshotPayload(params: JSONObject?): JSONObject? {
        if (params == null) {
            return null
        }
        val event = params.objectOrNull("msg", "event")
        val candidates = listOfNotNull(
            params.objectOrNull("account", "snapshot", "result"),
            event?.objectOrNull("account", "snapshot", "result"),
            params.takeIf(::looksLikeHostAccountSnapshot),
            event?.takeIf(::looksLikeHostAccountSnapshot),
        )
        return candidates.firstOrNull()
    }

    private fun extractSkillChangeCwds(params: JSONObject?): List<String> {
        if (params == null) {
            return emptyList()
        }
        val event = params.objectOrNull("msg", "event")
        val roots = params.arrayOrNull("cwds", "roots", "workspaces")
            ?: event?.arrayOrNull("cwds", "roots", "workspaces")
        if (roots != null) {
            val decoded = mutableListOf<String>()
            for (index in 0 until roots.length()) {
                val value = roots.optString(index).trim()
                if (value.isNotEmpty() && value !in decoded) {
                    decoded += value
                }
            }
            if (decoded.isNotEmpty()) {
                return decoded
            }
        }
        return listOfNotNull(
            params.stringOrNull("cwd", "root", "workspace"),
            event?.stringOrNull("cwd", "root", "workspace"),
        ).distinct()
    }

    private fun isApprovalRequestMethod(method: String): Boolean {
        return method == "item/commandExecution/requestApproval"
            || method == "item/fileChange/requestApproval"
            || method.endsWith("requestApproval")
    }

    private fun isToolUserInputRequestMethod(method: String): Boolean {
        return normalizeItemType(method) == "itemtoolrequestuserinput"
    }

    private fun looksLikeHostAccountSnapshot(candidate: JSONObject): Boolean {
        return listOf(
            "status",
            "state",
            "email",
            "authMethod",
            "auth_method",
            "planType",
            "plan_type",
            "loginInFlight",
            "login_in_flight",
            "needsReauth",
            "needs_reauth",
            "tokenReady",
            "token_ready",
            "rateLimits",
            "rate_limits",
            "limits",
        ).any(candidate::has)
    }

    private fun isTerminalCommandStatus(status: String?): Boolean {
        val normalizedStatus = status?.trim()?.lowercase(Locale.US) ?: return false
        return isCompletedTurnStatus(normalizedStatus)
            || isFailedTurnStatus(normalizedStatus)
            || isStoppedTurnStatus(normalizedStatus)
    }

    private fun decodeThreadReadAssistantContent(item: JSONObject): String? {
        val content = item.optJSONArray("content") ?: return null
        val parts = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            block.stringOrNull("text", "delta")?.let(parts::add)
        }
        return parts.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun popBufferedSecureError(): String? {
        val raw = requestMutex.withLock {
            val buffered = bufferedSecureControlMessages["secureError"]
            if (buffered.isNullOrEmpty()) {
                null
            } else {
                buffered.pollFirst()
            }
        } ?: return null
        return JSONObject(raw).optString("message")
    }

    private suspend fun clearSecureWaiters(error: Throwable) {
        val waiters = requestMutex.withLock {
            val values = pendingSecureControlWaiters.values.flatten()
            pendingSecureControlWaiters.clear()
            bufferedSecureControlMessages.clear()
            values
        }
        waiters.forEach { it.completeExceptionally(error) }
    }

    private fun clearPendingRequests() {
        val outstanding = pendingResponses.values.toList()
        pendingResponses.clear()
        // Treat transport drops as cancellation so background refresh coroutines
        // unwind cleanly instead of surfacing as fatal app exceptions.
        outstanding.forEach {
            it.completeExceptionally(HostTransportInterruptedException("Disconnected"))
        }
    }

    private suspend fun emitTerminalConnectionUpdate(update: ClientUpdate.Connection) {
        pendingTerminalConnectionUpdate = update
        updatesFlow.emit(update)
    }

    private fun resetBridgeOutboundReplayCursor() {
        lastAppliedBridgeOutboundSeq = 0
        persistence.saveLastAppliedBridgeOutboundSeq(0)
    }

    private fun consumePendingTerminalConnectionUpdate(): ClientUpdate.Connection? {
        val update = pendingTerminalConnectionUpdate
        pendingTerminalConnectionUpdate = null
        return update
    }

    private fun normalizeRuntimeSelectionsAfterModelsUpdate() {
        if (availableModels.isEmpty()) {
            selectedReasoningEffort = null
            return
        }

        val resolvedModel = selectedModelOption() ?: fallbackModel()
        selectedModelId = resolvedModel?.stableIdentifier

        if (resolvedModel == null) {
            selectedReasoningEffort = null
            return
        }

        val supported = resolvedModel.supportedReasoningEfforts.map { it.reasoningEffort }.toSet()
        selectedReasoningEffort = when {
            supported.isEmpty() -> null
            selectedReasoningEffort != null && supported.contains(selectedReasoningEffort) -> selectedReasoningEffort
            resolvedModel.defaultReasoningEffort != null && supported.contains(resolvedModel.defaultReasoningEffort) -> resolvedModel.defaultReasoningEffort
            supported.contains("medium") -> "medium"
            else -> resolvedModel.supportedReasoningEfforts.firstOrNull()?.reasoningEffort
        }
    }

    private fun selectedModelOption(): ModelOption? {
        val current = selectedModelId ?: return null
        return availableModels.firstOrNull { it.id == current || it.model == current }
    }

    private fun fallbackModel(): ModelOption? {
        return availableModels.firstOrNull { it.isDefault } ?: availableModels.firstOrNull()
    }

    private fun runtimeModelIdentifierForTurn(): String? {
        return selectedModelOption()?.model ?: fallbackModel()?.model
    }

    private fun threadRuntimeOverrideFor(threadId: String?): ThreadRuntimeOverride? {
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return threadRuntimeOverridesByThread[normalizedThreadId]
    }

    private fun selectedReasoningEffortForThread(threadId: String?): String? {
        val model = selectedModelOption() ?: fallbackModel() ?: return null
        val supported = model.supportedReasoningEfforts.map { it.reasoningEffort }.toSet()
        if (supported.isEmpty()) {
            return null
        }

        val threadOverride = threadRuntimeOverrideFor(threadId)
        val overriddenReasoning = threadOverride?.reasoningEffort
            ?.trim()
            ?.takeIf { threadOverride.overridesReasoning && it.isNotEmpty() && supported.contains(it) }
        if (overriddenReasoning != null) {
            return overriddenReasoning
        }

        return selectedReasoningEffortForSelectedModel()
    }

    private fun selectedReasoningEffortForSelectedModel(): String? {
        val model = selectedModelOption() ?: fallbackModel() ?: return null
        val supported = model.supportedReasoningEfforts.map { it.reasoningEffort }.toSet()
        if (supported.isEmpty()) {
            return null
        }

        return when {
            selectedReasoningEffort != null && supported.contains(selectedReasoningEffort) -> selectedReasoningEffort
            model.defaultReasoningEffort != null && supported.contains(model.defaultReasoningEffort) -> model.defaultReasoningEffort
            supported.contains("medium") -> "medium"
            else -> model.supportedReasoningEfforts.firstOrNull()?.reasoningEffort
        }
    }

    private fun runtimeServiceTierForThread(threadId: String? = null): String? {
        if (!supportsServiceTier) {
            return null
        }

        val threadOverride = threadRuntimeOverrideFor(threadId)
        if (threadOverride?.overridesServiceTier == true) {
            return threadOverride.serviceTier?.wireValue
        }

        return selectedServiceTier?.wireValue
    }

    private fun inheritThreadRuntimeOverrides(
        fromThreadId: String,
        toThreadId: String,
    ) {
        val normalizedSourceThreadId = fromThreadId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedDestinationThreadId = toThreadId.trim().takeIf { it.isNotEmpty() } ?: return
        if (normalizedSourceThreadId == normalizedDestinationThreadId) {
            return
        }

        val sourceOverride = threadRuntimeOverridesByThread[normalizedSourceThreadId]?.normalized()
        threadRuntimeOverridesByThread = if (sourceOverride == null) {
            threadRuntimeOverridesByThread - normalizedDestinationThreadId
        } else {
            threadRuntimeOverridesByThread + (normalizedDestinationThreadId to sourceOverride)
        }
        persistence.saveThreadRuntimeOverrides(threadRuntimeOverridesByThread)
        emitRuntimeConfig()
    }

    private fun consumeUnsupportedServiceTier(
        error: RpcException,
        includesServiceTier: Boolean,
    ): Boolean {
        if (!includesServiceTier || !shouldRetryWithoutServiceTier(error.code, error.message)) {
            return false
        }
        supportsServiceTier = false
        emitRuntimeConfig()
        return true
    }

    private fun consumeUnsupportedThreadForkOverrides(
        error: RpcException,
        usesMinimalForkParams: Boolean,
    ): Boolean {
        return !usesMinimalForkParams && shouldRetryThreadForkWithoutOverrides(error.code, error.message)
    }

    private fun consumeUnsupportedThreadFork(error: RpcException): Boolean {
        if (!shouldTreatAsUnsupportedThreadFork(error.code, error.message)) {
            return false
        }
        supportsThreadFork = false
        emitRuntimeConfig()
        return true
    }

    private fun consumeUnsupportedThreadCompaction(error: RpcException): Boolean {
        if (!shouldTreatAsUnsupportedThreadCompaction(error.code, error.message)) {
            return false
        }
        supportsThreadCompaction = false
        emitRuntimeConfig()
        return true
    }

    private fun consumeUnsupportedThreadRollback(error: RpcException): Boolean {
        if (!shouldTreatAsUnsupportedThreadRollback(error.code, error.message)) {
            return false
        }
        supportsThreadRollback = false
        emitRuntimeConfig()
        return true
    }

    private fun consumeUnsupportedBackgroundTerminalCleanup(error: RpcException): Boolean {
        if (!shouldTreatAsUnsupportedBackgroundTerminalCleanup(error.code, error.message)) {
            return false
        }
        supportsBackgroundTerminalCleanup = false
        emitRuntimeConfig()
        return true
    }

    private suspend fun isCurrentSocket(candidate: WebSocket): Boolean {
        return socketMutex.withLock { webSocket === candidate }
    }

    private fun gitParams(workingDirectory: String): JSONObject {
        return JSONObject().put("cwd", workingDirectory.trim())
    }

    private suspend fun <T> runGitRequest(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: RpcException) {
            throw error.toGitOperationException()
        }
    }

    private fun timeoutForMethod(method: String): Long {
        return when (method) {
            "thread/read" -> threadReadTimeoutMs
            "thread/list" -> threadListTimeoutMs
            "workspace/listDirectory" -> threadListTimeoutMs
            else -> defaultRpcTimeoutMs
        }
    }

    private fun isNoActiveWorkspaceError(error: RpcException): Boolean {
        val message = error.message.lowercase(Locale.US)
        return message.contains("no active workspace on the host")
    }

    private suspend fun waitForMatchingSecureReady(
        expectedSessionId: String,
        expectedKeyEpoch: Int,
        expectedMacDeviceId: String,
    ) {
        while (true) {
            val raw = waitForSecureControlMessage("secureReady")
            val ready = JSONObject(raw)
            if (
                ready.optString("hostId").ifBlank { ready.optString("sessionId") } == expectedSessionId
                && ready.optInt("keyEpoch") == expectedKeyEpoch
                && ready.optString("macDeviceId") == expectedMacDeviceId
            ) {
                return
            }
        }
    }

    data class RpcException(
        val code: Int,
        override val message: String,
        val data: JSONObject?,
    ) : RuntimeException(message)

    private data class PendingHandshake(
        val mode: String,
        val transcriptBytes: ByteArray,
        val phoneEphemeralPrivateKey: X25519PrivateKeyParameters,
        val phoneDeviceId: String,
    )

    private data class SecureSession(
        val sessionId: String,
        val keyEpoch: Int,
        val macDeviceId: String,
        val macIdentityPublicKey: String,
        val phoneToMacKey: ByteArray,
        val macToPhoneKey: ByteArray,
        var lastInboundBridgeOutboundSeq: Int,
        var lastInboundCounter: Int = -1,
        var nextOutboundCounter: Int = 0,
    )
}

private fun AndrodexClient.RpcException.toGitOperationException(): GitOperationException {
    val errorCode = data?.optString("errorCode")?.trim()?.takeIf { it.isNotEmpty() }
        ?: data?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
        ?: message.substringBefore(':').trim().takeIf { it.matches(Regex("[a-z0-9_]+")) }
    return GitOperationException(
        code = errorCode,
        message = message.ifBlank { "Git operation failed." },
    )
}

internal fun secureErrorConnectionUpdate(code: String, message: String): ClientUpdate.Connection {
    val status = when (code) {
        "update_required" -> ConnectionStatus.UPDATE_REQUIRED
        "pairing_expired", "phone_not_trusted", "phone_identity_changed", "phone_replacement_required" -> ConnectionStatus.RECONNECT_REQUIRED
        else -> ConnectionStatus.DISCONNECTED
    }
    return ClientUpdate.Connection(status, message)
}

internal fun connectionUpdateForSocketClose(
    code: Int,
    hasTrustedHost: Boolean,
    pendingTerminalUpdate: ClientUpdate.Connection?,
): ClientUpdate.Connection? {
    if (pendingTerminalUpdate != null) {
        return null
    }
    return if (code in setOf(4000, 4001, 4002, 4003, 4004)) {
        ClientUpdate.Connection(
            status = when (code) {
                4002, 4004 -> ConnectionStatus.RETRYING_SAVED_PAIRING
                else -> ConnectionStatus.DISCONNECTED
            },
            detail = when (code) {
                4002 -> "Host offline, retrying saved pairing until the daemon reconnects."
                4004 -> "Host temporarily unavailable, retrying saved pairing."
                4003 -> {
                    if (hasTrustedHost) {
                        "The previous live session was replaced. Trusted host details were kept, so you can reconnect without rescanning."
                    } else {
                        "This device was replaced by a newer connection. You can reconnect from saved pairing."
                    }
                }
                else -> {
                    if (hasTrustedHost) {
                        "The previous live session closed (code $code), but the trusted host record was preserved."
                    } else {
                        "The relay connection closed (code $code). Reconnect from saved pairing."
                    }
                }
            }
        )
    } else {
        ClientUpdate.Connection(
            ConnectionStatus.DISCONNECTED,
            "Relay disconnected (code $code)."
        )
    }
}

internal fun shouldClearSavedRelaySessionForSocketClose(code: Int): Boolean {
    return code in setOf(4000, 4001, 4003)
}

internal fun trustedSessionResolveUrl(relayUrl: String): String? {
    val relayUri = runCatching { URI(relayUrl.trim()) }.getOrNull() ?: return null
    val normalizedScheme = when (relayUri.scheme?.lowercase(Locale.US)) {
        "ws" -> "http"
        "wss" -> "https"
        "http", "https" -> relayUri.scheme.lowercase(Locale.US)
        else -> return null
    }
    val host = relayUri.host ?: return null
    val baseSegments = relayUri.path
        .orEmpty()
        .split('/')
        .filter { it.isNotBlank() }
        .dropLastWhile { it == "relay" }
    val resolvePath = buildString {
        append('/')
        if (baseSegments.isNotEmpty()) {
            append(baseSegments.joinToString("/"))
            append('/')
        }
        append("v1/trusted/session/resolve")
    }
    return URI(
        normalizedScheme,
        relayUri.userInfo,
        host,
        relayUri.port,
        resolvePath,
        null,
        null,
    )
        .toString()
}

internal fun buildTurnInputPayloadSpec(
    userInput: String,
    attachments: List<ImageAttachment> = emptyList(),
    fileMentions: List<TurnFileMention> = emptyList(),
    skillMentions: List<TurnSkillMention> = emptyList(),
    imageUrlKey: String = "url",
    includeStructuredFileItems: Boolean = true,
    includeStructuredSkillItems: Boolean = true,
): List<Map<String, Any?>> {
    val payload = mutableListOf<Map<String, Any?>>()
    attachments.forEach { attachment ->
        val payloadDataUrl = attachment.payloadDataUrl?.trim()
        if (payloadDataUrl.isNullOrEmpty()) {
            return@forEach
        }
        payload += linkedMapOf(
            "type" to "image",
            imageUrlKey to payloadDataUrl,
        )
    }

    val trimmedInput = userInput.trim()
    if (trimmedInput.isNotEmpty()) {
        payload += mapOf(
            "type" to "text",
            "text" to trimmedInput,
        )
    }

    if (includeStructuredFileItems) {
        fileMentions.forEach { mention ->
            val normalizedPath = mention.path.trim()
            if (normalizedPath.isEmpty()) {
                return@forEach
            }
            val item = linkedMapOf<String, Any?>(
                "type" to "file",
                "path" to normalizedPath,
            )
            mention.name?.trim()?.takeIf { it.isNotEmpty() }?.let { item["name"] = it }
            payload += item
        }
    }

    if (includeStructuredSkillItems) {
        skillMentions.forEach { mention ->
            val normalizedSkillId = mention.id.trim()
            if (normalizedSkillId.isEmpty()) {
                return@forEach
            }
            val item = linkedMapOf<String, Any?>(
                "type" to "skill",
                "id" to normalizedSkillId,
            )
            mention.name?.trim()?.takeIf { it.isNotEmpty() }?.let { item["name"] = it }
            mention.path?.trim()?.takeIf { it.isNotEmpty() }?.let { item["path"] = it }
            payload += item
        }
    }
    return payload
}

internal fun buildTurnInputPayload(
    userInput: String,
    attachments: List<ImageAttachment> = emptyList(),
    fileMentions: List<TurnFileMention> = emptyList(),
    skillMentions: List<TurnSkillMention> = emptyList(),
    imageUrlKey: String = "url",
    includeStructuredFileItems: Boolean = true,
    includeStructuredSkillItems: Boolean = true,
): JSONArray {
    return buildTurnInputPayloadSpec(
        userInput = userInput,
        attachments = attachments,
        fileMentions = fileMentions,
        skillMentions = skillMentions,
        imageUrlKey = imageUrlKey,
        includeStructuredFileItems = includeStructuredFileItems,
        includeStructuredSkillItems = includeStructuredSkillItems,
    ).toJsonArray()
}

internal fun buildThreadStartParams(
    preferredProjectPath: String? = null,
    model: String? = null,
    serviceTier: String? = null,
): JSONObject {
    return buildThreadStartPayloadSpec(
        preferredProjectPath = preferredProjectPath,
        model = model,
        serviceTier = serviceTier,
    ).toJsonObject()
}

internal fun buildThreadStartPayloadSpec(
    preferredProjectPath: String? = null,
    model: String? = null,
    serviceTier: String? = null,
): Map<String, Any?> {
    val params = linkedMapOf<String, Any?>()
    preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() }?.let { params["cwd"] = it }
    model?.trim()?.takeIf { it.isNotEmpty() }?.let { params["model"] = it }
    serviceTier?.trim()?.takeIf { it.isNotEmpty() }?.let { params["serviceTier"] = it }
    return params
}

internal fun buildThreadForkParams(
    sourceThreadId: String,
    preferredProjectPath: String? = null,
    model: String? = null,
    serviceTier: String? = null,
    includeSandbox: Boolean = true,
    usesMinimalForkParams: Boolean = false,
    accessMode: AccessMode = AccessMode.ON_REQUEST,
): JSONObject {
    return buildThreadForkPayloadSpec(
        sourceThreadId = sourceThreadId,
        preferredProjectPath = preferredProjectPath,
        model = model,
        serviceTier = serviceTier,
        includeSandbox = includeSandbox,
        usesMinimalForkParams = usesMinimalForkParams,
        accessMode = accessMode,
    ).toJsonObject()
}

internal fun buildThreadForkPayloadSpec(
    sourceThreadId: String,
    preferredProjectPath: String? = null,
    model: String? = null,
    serviceTier: String? = null,
    includeSandbox: Boolean = true,
    usesMinimalForkParams: Boolean = false,
    accessMode: AccessMode = AccessMode.ON_REQUEST,
): Map<String, Any?> {
    val params = linkedMapOf<String, Any?>(
        "threadId" to sourceThreadId,
    )
    if (usesMinimalForkParams) {
        return params
    }
    preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() }?.let { params["cwd"] = it }
    model?.trim()?.takeIf { it.isNotEmpty() }?.let { params["model"] = it }
    serviceTier?.trim()?.takeIf { it.isNotEmpty() }?.let { params["serviceTier"] = it }
    if (includeSandbox) {
        params["sandbox"] = accessMode.sandboxLegacyValue
    }
    return params
}

internal fun buildTurnStartParams(
    threadId: String,
    userInput: String,
    attachments: List<ImageAttachment> = emptyList(),
    fileMentions: List<TurnFileMention> = emptyList(),
    skillMentions: List<TurnSkillMention> = emptyList(),
    imageUrlKey: String = "url",
    includeStructuredFileItems: Boolean = true,
    includeStructuredSkillItems: Boolean = true,
    model: String?,
    reasoningEffort: String?,
    serviceTier: String?,
    collaborationMode: CollaborationModeKind?,
): JSONObject {
    return buildTurnStartPayloadSpec(
        threadId = threadId,
        userInput = userInput,
        attachments = attachments,
        fileMentions = fileMentions,
        skillMentions = skillMentions,
        imageUrlKey = imageUrlKey,
        includeStructuredFileItems = includeStructuredFileItems,
        includeStructuredSkillItems = includeStructuredSkillItems,
        model = model,
        reasoningEffort = reasoningEffort,
        serviceTier = serviceTier,
        collaborationMode = collaborationMode,
    ).toJsonObject()
}

internal fun buildTurnStartPayloadSpec(
    threadId: String,
    userInput: String,
    attachments: List<ImageAttachment> = emptyList(),
    fileMentions: List<TurnFileMention> = emptyList(),
    skillMentions: List<TurnSkillMention> = emptyList(),
    imageUrlKey: String = "url",
    includeStructuredFileItems: Boolean = true,
    includeStructuredSkillItems: Boolean = true,
    model: String?,
    reasoningEffort: String?,
    serviceTier: String?,
    collaborationMode: CollaborationModeKind?,
): Map<String, Any?> {
    val params = linkedMapOf<String, Any?>(
        "threadId" to threadId,
        "input" to buildTurnInputPayloadSpec(
            userInput = userInput,
            attachments = attachments,
            fileMentions = fileMentions,
            skillMentions = skillMentions,
            imageUrlKey = imageUrlKey,
            includeStructuredFileItems = includeStructuredFileItems,
            includeStructuredSkillItems = includeStructuredSkillItems,
        ),
    )
    model?.let { params["model"] = it }
    reasoningEffort?.let { params["effort"] = it }
    serviceTier?.let { params["serviceTier"] = it }
    buildCollaborationModePayloadSpec(
        collaborationMode = collaborationMode,
        model = model,
        reasoningEffort = reasoningEffort,
    )?.let { params["collaborationMode"] = it }
    return params
}

internal fun buildTurnSteerParams(
    threadId: String,
    expectedTurnId: String,
    userInput: String,
    attachments: List<ImageAttachment> = emptyList(),
    fileMentions: List<TurnFileMention> = emptyList(),
    skillMentions: List<TurnSkillMention> = emptyList(),
    imageUrlKey: String = "url",
    includeStructuredFileItems: Boolean = true,
    includeStructuredSkillItems: Boolean = true,
    model: String?,
    reasoningEffort: String?,
    serviceTier: String?,
    collaborationMode: CollaborationModeKind?,
): JSONObject {
    return buildTurnSteerPayloadSpec(
        threadId = threadId,
        expectedTurnId = expectedTurnId,
        userInput = userInput,
        attachments = attachments,
        fileMentions = fileMentions,
        skillMentions = skillMentions,
        imageUrlKey = imageUrlKey,
        includeStructuredFileItems = includeStructuredFileItems,
        includeStructuredSkillItems = includeStructuredSkillItems,
        model = model,
        reasoningEffort = reasoningEffort,
        serviceTier = serviceTier,
        collaborationMode = collaborationMode,
    ).toJsonObject()
}

internal fun buildTurnSteerPayloadSpec(
    threadId: String,
    expectedTurnId: String,
    userInput: String,
    attachments: List<ImageAttachment> = emptyList(),
    fileMentions: List<TurnFileMention> = emptyList(),
    skillMentions: List<TurnSkillMention> = emptyList(),
    imageUrlKey: String = "url",
    includeStructuredFileItems: Boolean = true,
    includeStructuredSkillItems: Boolean = true,
    model: String?,
    reasoningEffort: String?,
    serviceTier: String?,
    collaborationMode: CollaborationModeKind?,
): Map<String, Any?> {
    val params = linkedMapOf<String, Any?>(
        "threadId" to threadId,
        "expectedTurnId" to expectedTurnId,
        "input" to buildTurnInputPayloadSpec(
            userInput = userInput,
            attachments = attachments,
            fileMentions = fileMentions,
            skillMentions = skillMentions,
            imageUrlKey = imageUrlKey,
            includeStructuredFileItems = includeStructuredFileItems,
            includeStructuredSkillItems = includeStructuredSkillItems,
        ),
    )
    reasoningEffort?.let { params["effort"] = it }
    serviceTier?.let { params["serviceTier"] = it }
    buildCollaborationModePayloadSpec(
        collaborationMode = collaborationMode,
        model = model,
        reasoningEffort = reasoningEffort,
    )?.let { params["collaborationMode"] = it }
    return params
}

internal fun buildReviewStartParams(
    threadId: String,
    target: ComposerReviewTarget,
    baseBranch: String? = null,
): JSONObject {
    return buildReviewStartPayloadSpec(
        threadId = threadId,
        target = target,
        baseBranch = baseBranch,
    ).toJsonObject()
}

internal fun buildReviewStartPayloadSpec(
    threadId: String,
    target: ComposerReviewTarget,
    baseBranch: String? = null,
): Map<String, Any?> {
    val targetObject = linkedMapOf<String, Any?>(
        "type" to when (target) {
            ComposerReviewTarget.UNCOMMITTED_CHANGES -> "uncommittedChanges"
            ComposerReviewTarget.BASE_BRANCH -> "baseBranch"
        },
    )
    if (target == ComposerReviewTarget.BASE_BRANCH) {
        val normalizedBaseBranch = baseBranch?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("Choose a base branch before starting this review.")
        targetObject["branch"] = normalizedBaseBranch
    }
    return linkedMapOf(
        "threadId" to threadId,
        "delivery" to "inline",
        "target" to targetObject,
    )
}

internal fun buildCollaborationModePayload(
    collaborationMode: CollaborationModeKind?,
    model: String?,
    reasoningEffort: String?,
): JSONObject? {
    return buildCollaborationModePayloadSpec(
        collaborationMode = collaborationMode,
        model = model,
        reasoningEffort = reasoningEffort,
    )?.toJsonObject()
}

internal fun buildInitializePayload(includeCapabilities: Boolean): JSONObject {
    val clientInfo = JSONObject()
        .put("name", "androdex_android")
        .put("title", "Androdex Android")
        .put("version", "0.1.0")
    return JSONObject().put("clientInfo", clientInfo).apply {
        if (includeCapabilities) {
            put(
                "capabilities",
                JSONObject().put("experimentalApi", true)
            )
        }
    }
}

internal fun threadLifecycleUpdateForNotification(
    method: String,
    params: JSONObject?,
): ClientUpdate? {
    return when (method.trim()) {
        "thread/status/changed" -> ClientUpdate.ThreadStatusChanged(
            threadId = params?.stringOrNull("threadId", "thread_id", "id"),
            status = params?.stringOrNull("status", "state"),
        )

        else -> null
    }
}

internal suspend fun performInitializeSessionRequest(
    sendInitializeRequest: suspend (JSONObject) -> Unit,
) {
    try {
        sendInitializeRequest(buildInitializePayload(includeCapabilities = true))
    } catch (error: AndrodexClient.RpcException) {
        if (!shouldRetryInitializeWithoutCapabilities(error.message)) {
            throw error
        }
        sendInitializeRequest(buildInitializePayload(includeCapabilities = false))
    }
}

internal fun buildCollaborationModePayloadSpec(
    collaborationMode: CollaborationModeKind?,
    model: String?,
    reasoningEffort: String?,
): Map<String, Any?>? {
    if (collaborationMode == null) {
        return null
    }
    val resolvedModel = model?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("Plan mode requires an available model before starting a plan turn.")
    return linkedMapOf(
        "mode" to collaborationMode.wireValue,
        "settings" to linkedMapOf(
            "model" to resolvedModel,
            "reasoning_effort" to reasoningEffort,
            "developer_instructions" to null,
        )
    )
}

internal fun applyAccessModeParams(
    baseParams: JSONObject,
    accessMode: AccessMode,
    sandboxMode: AccessModeSandboxMode,
): JSONObject {
    val params = clonedJson(baseParams)
    when (sandboxMode) {
        AccessModeSandboxMode.SANDBOX_POLICY -> {
            params.put("sandboxPolicy", buildSandboxPolicyPayloadSpec(accessMode).toJsonObject())
        }

        AccessModeSandboxMode.LEGACY_SANDBOX -> {
            params.put("sandbox", accessMode.sandboxLegacyValue)
        }

        AccessModeSandboxMode.MINIMAL -> Unit
    }
    return params
}

internal fun buildSandboxPolicyPayloadSpec(accessMode: AccessMode): Map<String, Any?> {
    return when (accessMode) {
        AccessMode.ON_REQUEST -> linkedMapOf(
            "type" to "workspaceWrite",
            "networkAccess" to true,
        )

        AccessMode.FULL_ACCESS -> linkedMapOf(
            "type" to "dangerFullAccess",
        )
    }
}

internal enum class AccessModeSandboxMode {
    SANDBOX_POLICY,
    LEGACY_SANDBOX,
    MINIMAL,
}

internal fun shouldRetryInitializeWithoutCapabilities(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    val mentionsCapabilitiesField = normalizedMessage.contains("capabilities")
        || normalizedMessage.contains("experimentalapi")
    if (!mentionsCapabilitiesField) {
        return false
    }
    return normalizedMessage.contains("unknown field")
        || normalizedMessage.contains("unexpected field")
        || normalizedMessage.contains("unrecognized field")
        || normalizedMessage.contains("invalid param")
        || normalizedMessage.contains("invalid params")
        || normalizedMessage.contains("failed to parse")
        || normalizedMessage.contains("unsupported")
}

internal fun resolveCollaborationModesAfterProbeFailure(
    currentModes: Set<CollaborationModeKind>,
    failure: Throwable,
): Set<CollaborationModeKind> {
    if (failure is AndrodexClient.RpcException &&
        shouldTreatAsUnsupportedCollaborationModeList(failure.code, failure.message)
    ) {
        return emptySet()
    }
    return currentModes
}

internal fun shouldTreatAsUnsupportedCollaborationModeList(errorCode: Int, errorMessage: String?): Boolean {
    if (errorCode == -32601) {
        return true
    }

    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    val mentionsCollaborationModeList = normalizedMessage.contains("collaborationmode/list")
        || normalizedMessage.contains("collaborationmode list")
        || normalizedMessage.contains("collaboration mode list")
        || normalizedMessage.contains("collaborationmode")
        || normalizedMessage.contains("collaboration mode")
    if (!mentionsCollaborationModeList) {
        return false
    }

    val mentionsUnsupportedMethod = normalizedMessage.contains("method not found")
        || normalizedMessage.contains("unknown method")
        || normalizedMessage.contains("not implemented")
        || normalizedMessage.contains("does not support")
    val mentionsUnsupportedField = normalizedMessage.contains("unknown field")
        || normalizedMessage.contains("unexpected field")
        || normalizedMessage.contains("unrecognized field")
        || normalizedMessage.contains("invalid param")
        || normalizedMessage.contains("invalid params")
        || normalizedMessage.contains("failed to parse")
        || normalizedMessage.contains("unsupported")
        || normalizedMessage.contains("not supported")

    return mentionsUnsupportedMethod || mentionsUnsupportedField
}

internal fun shouldRetryWithApprovalPolicyFallback(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    return normalizedMessage.contains("approval")
        || normalizedMessage.contains("unknown variant")
        || normalizedMessage.contains("expected one of")
        || normalizedMessage.contains("onrequest")
        || normalizedMessage.contains("on-request")
}

internal fun shouldFallbackFromSandboxPolicy(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    if (normalizedMessage.contains("thread not found") || normalizedMessage.contains("unknown thread")) {
        return false
    }
    if (!(normalizedMessage.contains("sandbox") || normalizedMessage.contains("sandboxpolicy"))) {
        return false
    }
    return normalizedMessage.contains("invalid params")
        || normalizedMessage.contains("invalid param")
        || normalizedMessage.contains("unknown field")
        || normalizedMessage.contains("unexpected field")
        || normalizedMessage.contains("unrecognized field")
        || normalizedMessage.contains("failed to parse")
        || normalizedMessage.contains("unsupported")
}

private fun shouldRetrySkillsListWithCwdFallback(errorMessage: String?): Boolean {
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    return normalizedMessage.contains("cwds")
        && (normalizedMessage.contains("unknown")
        || normalizedMessage.contains("unsupported")
        || normalizedMessage.contains("invalid")
        || normalizedMessage.contains("field")
        || normalizedMessage.contains("param"))
}

internal fun shouldRetryWithoutServiceTier(errorCode: Int, errorMessage: String?): Boolean {
    if (errorCode != -32600 && errorCode != -32602) {
        return false
    }
    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    return normalizedMessage.contains("servicetier")
        || normalizedMessage.contains("service tier")
        || normalizedMessage.contains("unknown field")
        || normalizedMessage.contains("unexpected field")
        || normalizedMessage.contains("unrecognized field")
        || normalizedMessage.contains("invalid param")
        || normalizedMessage.contains("invalid params")
}

internal fun shouldRetryThreadForkWithoutOverrides(errorCode: Int, errorMessage: String?): Boolean {
    if (errorCode != -32600 && errorCode != -32602 && errorCode != -32000) {
        return false
    }

    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    val mentionsUnknownField = normalizedMessage.contains("unknown field")
        || normalizedMessage.contains("unexpected field")
        || normalizedMessage.contains("unrecognized field")
    val mentionsInvalidNamedField = (normalizedMessage.contains("invalid param") || normalizedMessage.contains("invalid params"))
        && (normalizedMessage.contains("field") || normalizedMessage.contains("parameter") || normalizedMessage.contains("param"))
    val mentionsForkOverride = normalizedMessage.contains("cwd")
        || normalizedMessage.contains("modelprovider")
        || normalizedMessage.contains("model provider")
        || normalizedMessage.contains("model")
        || normalizedMessage.contains("sandbox")
        || normalizedMessage.contains("servicetier")
        || normalizedMessage.contains("service tier")

    return (mentionsUnknownField || mentionsInvalidNamedField) && mentionsForkOverride
}

internal fun shouldTreatAsUnsupportedThreadFork(errorCode: Int, errorMessage: String?): Boolean {
    return shouldTreatAsUnsupportedThreadMethod(
        errorCode = errorCode,
        errorMessage = errorMessage,
        methodHints = listOf("thread/fork", "thread fork"),
    )
}

internal fun shouldTreatAsUnsupportedThreadCompaction(errorCode: Int, errorMessage: String?): Boolean {
    return shouldTreatAsUnsupportedThreadMethod(
        errorCode = errorCode,
        errorMessage = errorMessage,
        methodHints = listOf("thread/compact/start", "thread compact", "context compaction", "compaction"),
    )
}

internal fun shouldTreatAsUnsupportedThreadRollback(errorCode: Int, errorMessage: String?): Boolean {
    return shouldTreatAsUnsupportedThreadMethod(
        errorCode = errorCode,
        errorMessage = errorMessage,
        methodHints = listOf("thread/rollback", "thread rollback", "rollback"),
    )
}

internal fun shouldTreatAsUnsupportedBackgroundTerminalCleanup(errorCode: Int, errorMessage: String?): Boolean {
    return shouldTreatAsUnsupportedThreadMethod(
        errorCode = errorCode,
        errorMessage = errorMessage,
        methodHints = listOf(
            "thread/backgroundterminals/clean",
            "background terminals",
            "background terminal",
            "terminal cleanup",
            "terminal clean",
        ),
    )
}

private fun shouldTreatAsUnsupportedThreadMethod(
    errorCode: Int,
    errorMessage: String?,
    methodHints: List<String>,
): Boolean {
    if (errorCode == -32601) {
        return true
    }

    val normalizedMessage = errorMessage?.lowercase(Locale.US).orEmpty()
    val mentionsUnsupportedMethod = normalizedMessage.contains("method not found")
        || normalizedMessage.contains("unknown method")
        || normalizedMessage.contains("not implemented")
        || normalizedMessage.contains("does not support")
    val mentionsSpecificUnsupported = methodHints.any { normalizedMessage.contains(it) }
        && (normalizedMessage.contains("unsupported") || normalizedMessage.contains("not supported"))

    if (errorCode != -32600 && errorCode != -32602 && errorCode != -32000) {
        return mentionsUnsupportedMethod || mentionsSpecificUnsupported
    }

    return mentionsUnsupportedMethod || mentionsSpecificUnsupported
}

private fun Map<String, Any?>.toJsonObject(): JSONObject {
    val jsonObject = JSONObject()
    entries.forEach { (key, value) ->
        jsonObject.put(key, value.toJsonValue())
    }
    return jsonObject
}

private fun clonedJson(value: JSONObject): JSONObject {
    return JSONObject(value.toString())
}

private fun List<*>.toJsonArray(): JSONArray {
    val jsonArray = JSONArray()
    forEach { item ->
        jsonArray.put(item.toJsonValue())
    }
    return jsonArray
}

private fun Any?.toJsonValue(): Any {
    return when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val normalizedMap = linkedMapOf<String, Any?>()
            entries.forEach { (key, value) ->
                if (key is String) {
                    normalizedMap[key] = value
                }
            }
            normalizedMap.toJsonObject()
        }

        is List<*> -> toJsonArray()
        else -> this
    }
}

internal fun connectionUpdateForSocketFailure(
    savedPairingAvailable: Boolean,
    errorMessage: String?,
    pendingTerminalUpdate: ClientUpdate.Connection?,
): ClientUpdate.Connection? {
    if (pendingTerminalUpdate != null) {
        return null
    }
    return ClientUpdate.Connection(
        status = if (savedPairingAvailable) {
            ConnectionStatus.RETRYING_SAVED_PAIRING
        } else {
            ConnectionStatus.DISCONNECTED
        },
        detail = if (savedPairingAvailable) {
            "Relay unavailable, retrying saved pairing."
        } else {
            errorMessage ?: "Relay connection failed."
        },
    )
}

private fun decodeThreadRunSnapshot(threadObject: JSONObject): ThreadRunSnapshot {
    val turns = threadObject.optJSONArray("turns") ?: JSONArray()
    var interruptibleTurnId: String? = null
    var hasInterruptibleTurnWithoutId = false
    var latestTurnId: String? = null
    var latestTurnTerminalState: TurnTerminalState? = null
    var shouldAssumeRunningFromLatestTurn = false

    for (index in turns.length() - 1 downTo 0) {
        val turnObject = turns.optJSONObject(index) ?: continue
        val turnId = turnObject.stringOrNull("id", "turnId", "turn_id")
        val normalizedStatus = normalizeTurnStatus(
            turnObject.stringOrNull("status")
                ?: turnObject.optJSONObject("status")?.stringOrNull("type", "status")
        )

        if (latestTurnId == null && !turnId.isNullOrBlank()) {
            latestTurnId = turnId
            latestTurnTerminalState = terminalStateForStatus(normalizedStatus)
            shouldAssumeRunningFromLatestTurn = normalizedStatus == null
        }

        if (normalizedStatus == null) {
            continue
        }
        if (!isInterruptibleTurnStatus(normalizedStatus)) {
            continue
        }

        if (!turnId.isNullOrBlank()) {
            interruptibleTurnId = turnId
            break
        }
        hasInterruptibleTurnWithoutId = true
    }

    return ThreadRunSnapshot(
        interruptibleTurnId = interruptibleTurnId,
        hasInterruptibleTurnWithoutId = hasInterruptibleTurnWithoutId,
        latestTurnId = latestTurnId,
        latestTurnTerminalState = latestTurnTerminalState,
        shouldAssumeRunningFromLatestTurn = shouldAssumeRunningFromLatestTurn,
    )
}

internal fun resolveExecutionUpdateItemId(
    params: JSONObject?,
    item: JSONObject?,
): String? {
    val directItemId = params?.stringOrNull("itemId", "item_id", "messageId", "message_id")
        ?: params?.optJSONObject("item")?.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")
        ?: params?.objectOrNull("msg", "event")?.let { nested ->
            nested.stringOrNull("itemId", "item_id", "messageId", "message_id")
                ?: nested.optJSONObject("item")?.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")
        }
    return directItemId
        ?: item?.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")
}

internal fun resolveInitialHostAccountSnapshot(
    currentSnapshot: HostAccountSnapshot?,
    bridgeSnapshot: HostAccountSnapshot?,
): HostAccountSnapshot {
    return when {
        bridgeSnapshot != null -> bridgeSnapshot.copy(origin = HostAccountSnapshotOrigin.BRIDGE_BOOTSTRAP)
        currentSnapshot != null -> currentSnapshot
        else -> HostAccountSnapshot(
            status = HostAccountStatus.UNAVAILABLE,
            origin = HostAccountSnapshotOrigin.BRIDGE_BOOTSTRAP,
        )
    }
}

internal fun resolveLiveHostAccountSnapshot(
    currentSnapshot: HostAccountSnapshot?,
    bridgePayload: JSONObject?,
    bridgeSnapshot: HostAccountSnapshot?,
): HostAccountSnapshot? {
    return when {
        bridgeSnapshot != null -> mergeHostAccountSnapshot(
            base = bridgeSnapshot.copy(origin = HostAccountSnapshotOrigin.BRIDGE_FALLBACK),
            fallback = currentSnapshot,
            sparseBridgePayload = bridgePayload,
        )
        else -> currentSnapshot
    }
}

internal fun applyHostAccountUpdatePayload(
    currentSnapshot: HostAccountSnapshot?,
    payload: JSONObject,
    origin: HostAccountSnapshotOrigin,
): HostAccountSnapshot? {
    val decodedSnapshot = decodeHostAccountSnapshot(payload)
    val decodedStatus = payload.decodedHostAccountStatusOrNull()
    val canSeedFromPayload = currentSnapshot != null || payload.hasAnyKey(
        "status",
        "state",
        "email",
        "authMethod",
        "auth_method",
        "planType",
        "plan_type",
        "loginInFlight",
        "login_in_flight",
        "needsReauth",
        "needs_reauth",
        "tokenReady",
        "token_ready",
    )
    val canSeedDecodedSnapshot = canSeedFromPayload && (!payload.hasAnyKey("status", "state") || decodedStatus != null)
    val seed = currentSnapshot ?: decodedSnapshot?.takeIf { canSeedDecodedSnapshot } ?: return null
    val resolvedStatus = if (payload.hasAnyKey("status", "state")) {
        decodedStatus ?: seed.status
    } else {
        seed.status
    }

    return seed.copy(
        status = resolvedStatus,
        authMethod = if (payload.hasAnyKey("authMethod", "auth_method")) {
            payload.stringOrNull("authMethod", "auth_method")
        } else {
            seed.authMethod
        },
        email = if (payload.has("email")) {
            payload.stringOrNull("email")
        } else {
            seed.email
        },
        planType = if (payload.hasAnyKey("planType", "plan_type")) {
            payload.stringOrNull("planType", "plan_type")
        } else {
            seed.planType
        },
        loginInFlight = if (payload.hasAnyKey("loginInFlight", "login_in_flight")) {
            payload.booleanValueOrDefault(
                defaultValue = seed.loginInFlight,
                names = arrayOf("loginInFlight", "login_in_flight"),
            )
        } else {
            seed.loginInFlight
        },
        needsReauth = if (payload.hasAnyKey("needsReauth", "needs_reauth")) {
            payload.booleanValueOrDefault(
                defaultValue = seed.needsReauth,
                names = arrayOf("needsReauth", "needs_reauth"),
            )
        } else {
            seed.needsReauth
        },
        tokenReady = if (payload.hasAnyKey("tokenReady", "token_ready")) {
            payload.optionalBooleanValue("tokenReady", "token_ready")
        } else {
            seed.tokenReady
        },
        expiresAtEpochMs = if (payload.hasAnyKey("expiresAt", "expires_at")) {
            decodedSnapshot?.expiresAtEpochMs
        } else {
            seed.expiresAtEpochMs
        },
        bridgeVersion = if (
            payload.hasAnyKey("bridgeVersion", "bridge_version", "bridgePackageVersion", "bridge_package_version")
        ) {
            payload.stringOrNull("bridgeVersion", "bridge_version", "bridgePackageVersion", "bridge_package_version")
        } else {
            seed.bridgeVersion
        },
        bridgeLatestVersion = if (
            payload.hasAnyKey(
                "bridgeLatestVersion",
                "bridge_latest_version",
                "bridgePublishedVersion",
                "bridge_published_version",
            )
        ) {
            payload.stringOrNull(
                "bridgeLatestVersion",
                "bridge_latest_version",
                "bridgePublishedVersion",
                "bridge_published_version",
            )
        } else {
            seed.bridgeLatestVersion
        },
        rateLimits = if (payload.hasAnyKey("rateLimits", "rate_limits", "limits", "buckets")) {
            decodeHostRateLimitBuckets(payload)
        } else {
            seed.rateLimits
        },
        origin = origin,
    )
}

internal fun mergeHostAccountSnapshot(
    base: HostAccountSnapshot?,
    fallback: HostAccountSnapshot?,
    sparseBridgePayload: JSONObject? = null,
): HostAccountSnapshot? {
    if (base == null) {
        return fallback
    }
    if (fallback == null) {
        return base
    }

    val preserveLoginInFlight = sparseBridgePayload?.hasAnyKey("loginInFlight", "login_in_flight") != true
    val preserveNeedsReauth = sparseBridgePayload?.hasAnyKey("needsReauth", "needs_reauth") != true
    val preserveTokenReady = sparseBridgePayload?.hasAnyKey("tokenReady", "token_ready") != true

    return base.copy(
        status = if (base.status == HostAccountStatus.UNKNOWN) fallback.status else base.status,
        authMethod = base.authMethod ?: fallback.authMethod,
        email = base.email ?: fallback.email,
        planType = base.planType ?: fallback.planType,
        loginInFlight = if (preserveLoginInFlight) fallback.loginInFlight else base.loginInFlight,
        needsReauth = if (preserveNeedsReauth) fallback.needsReauth else base.needsReauth,
        tokenReady = if (preserveTokenReady) fallback.tokenReady else base.tokenReady,
        expiresAtEpochMs = base.expiresAtEpochMs ?: fallback.expiresAtEpochMs,
        bridgeVersion = base.bridgeVersion ?: fallback.bridgeVersion,
        bridgeLatestVersion = base.bridgeLatestVersion ?: fallback.bridgeLatestVersion,
        rateLimits = if (base.rateLimits.isNotEmpty()) base.rateLimits else fallback.rateLimits,
    )
}

private fun normalizeTurnStatus(rawStatus: String?): String? {
    return rawStatus?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
}

private fun isInterruptibleTurnStatus(normalizedStatus: String): Boolean {
    return normalizedStatus in setOf("in_progress", "running", "active", "queued")
        || normalizedStatus.contains("progress")
        || normalizedStatus.contains("running")
}

private fun isCompletedTurnStatus(normalizedStatus: String): Boolean {
    return normalizedStatus in setOf("completed", "complete", "done", "success", "succeeded")
        || normalizedStatus.contains("complete")
        || normalizedStatus.contains("success")
}

private fun isFailedTurnStatus(normalizedStatus: String): Boolean {
    return normalizedStatus in setOf("failed", "error")
        || normalizedStatus.contains("fail")
        || normalizedStatus.contains("error")
    }

private fun isStoppedTurnStatus(normalizedStatus: String): Boolean {
    return normalizedStatus in setOf("stopped", "interrupted", "cancelled", "canceled")
        || normalizedStatus.contains("stop")
        || normalizedStatus.contains("interrupt")
        || normalizedStatus.contains("cancel")
}

private fun terminalStateForStatus(normalizedStatus: String?): TurnTerminalState? {
    return when {
        normalizedStatus == null -> null
        isStoppedTurnStatus(normalizedStatus) -> TurnTerminalState.STOPPED
        isFailedTurnStatus(normalizedStatus) -> TurnTerminalState.FAILED
        isCompletedTurnStatus(normalizedStatus) -> TurnTerminalState.COMPLETED
        else -> null
    }
}

internal fun shouldApplyBridgeOutboundSeq(
    incomingBridgeOutboundSeq: Int,
    lastAppliedBridgeOutboundSeq: Int,
): Boolean {
    if (incomingBridgeOutboundSeq <= 0) {
        return true
    }
    return incomingBridgeOutboundSeq > lastAppliedBridgeOutboundSeq
}

private fun JSONObject.hasAnyKey(vararg names: String): Boolean {
    return names.any(::has)
}

private fun JSONObject.booleanValueOrDefault(
    defaultValue: Boolean,
    names: Array<String>,
): Boolean {
    val firstKey = names.firstOrNull { has(it) } ?: return defaultValue
    return optBoolean(firstKey, defaultValue)
}

private fun JSONObject.optionalBooleanValue(vararg names: String): Boolean? {
    val firstKey = names.firstOrNull { has(it) } ?: return null
    val rawValue = opt(firstKey)
    return when (rawValue) {
        null,
        JSONObject.NULL -> null
        is Boolean -> rawValue
        is String -> rawValue.equals("true", ignoreCase = true)
        is Number -> rawValue.toInt() != 0
        else -> null
    }
}
