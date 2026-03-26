package io.androdex.android.data

import android.util.Log
import io.androdex.android.crypto.aesGcmDecrypt
import io.androdex.android.crypto.aesGcmEncrypt
import io.androdex.android.crypto.buildClientAuthTranscript
import io.androdex.android.crypto.buildTranscriptBytes
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
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ModelOption
import io.androdex.android.model.PairingPayload
import io.androdex.android.model.PhoneIdentityState
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.TurnTerminalState
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val handshakeModeQrBootstrap = "qr_bootstrap"
private const val handshakeModeTrustedReconnect = "trusted_reconnect"
private const val relayOpenTimeoutMs = 12_000L
private const val secureHandshakeTimeoutMs = 20_000L
private const val defaultRpcTimeoutMs = 20_000L
private const val threadReadTimeoutMs = 45_000L
private const val threadListTimeoutMs = 30_000L
private const val logTag = "AndrodexClient"

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
    private var lastAppliedBridgeOutboundSeq: Int = persistence.loadLastAppliedBridgeOutboundSeq()
    private var savedPairingPayload: PairingPayload? = persistence.loadPairing()
    private var availableModels: List<ModelOption> = emptyList()
    private var selectedModelId: String? = persistence.loadSelectedModelId()
    private var selectedReasoningEffort: String? = persistence.loadSelectedReasoningEffort()

    val updates: SharedFlow<ClientUpdate> = updatesFlow.asSharedFlow()

    init {
        emitPairingAvailability()
        emitRuntimeConfig()
    }

    fun hasSavedPairing(): Boolean = savedPairingPayload != null

    fun currentFingerprint(): String? {
        val pairing = savedPairingPayload ?: return null
        val trusted = trustedMacRegistry.records[pairing.macDeviceId]
        return fingerprint(trusted?.macIdentityPublicKey ?: pairing.macIdentityPublicKey)
    }

    suspend fun connectWithPairingPayload(rawPayload: String) {
        val pairing = parsePairingPayload(rawPayload)
        connect(pairing)
        val savedPairing = pairing.toSavedPairing()
        persistence.savePairing(savedPairing)
        savedPairingPayload = savedPairing
        lastAppliedBridgeOutboundSeq = persistence.loadLastAppliedBridgeOutboundSeq()
        emitPairingAvailability()
    }

    suspend fun reconnectSaved() {
        val pairing = savedPairingPayload ?: throw IllegalStateException("No saved pairing is available.")
        connect(pairing)
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
        val params = JSONObject()
        runtimeModelIdentifierForTurn()?.let { params.put("model", it) }
        preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() }?.let { params.put("cwd", it) }
        val result = sendRequest("thread/start", params)
        val thread = decodeThreadSummary(result.optJSONObject("thread") ?: JSONObject())
            ?: throw IllegalStateException("thread/start response did not include a thread.")
        return thread
    }

    suspend fun listRecentWorkspaces(): WorkspaceRecentState {
        val result = sendRequest("workspace/listRecent", JSONObject())
        return decodeWorkspaceRecentState(result)
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

    suspend fun startTurn(threadId: String, userInput: String) {
        resumeThread(threadId)
        val params = JSONObject()
            .put("threadId", threadId)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", userInput.trim())
                )
            )
        runtimeModelIdentifierForTurn()?.let { params.put("model", it) }
        selectedReasoningEffortForSelectedModel()?.let { params.put("effort", it) }
        sendRequest("turn/start", params)
    }

    suspend fun steerTurn(threadId: String, expectedTurnId: String, userInput: String) {
        resumeThread(threadId)
        val params = JSONObject()
            .put("threadId", threadId)
            .put("expectedTurnId", expectedTurnId)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", userInput.trim())
                )
            )
        selectedReasoningEffortForSelectedModel()?.let { params.put("effort", it) }
        sendRequest("turn/steer", params)
    }

    suspend fun interruptTurn(threadId: String, turnId: String) {
        sendRequest(
            "turn/interrupt",
            JSONObject()
                .put("threadId", threadId)
                .put("turnId", turnId),
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

    suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        sendResponse(request.idValue, if (accept) "accept" else "decline")
        updatesFlow.emit(ClientUpdate.ApprovalCleared)
    }

    private suspend fun connect(pairing: PairingPayload) {
        connectionLifecycleMutex.withLock {
            disconnectInternal(clearSavedPairing = false)
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
            clearSecureWaiters(IllegalStateException("Disconnected"))
            if (clearSavedPairing) {
                persistence.clearPairing()
                savedPairingPayload = null
                lastAppliedBridgeOutboundSeq = 0
                emitPairingAvailability()
            }
        }
    }

    private suspend fun initializeSession() {
        val clientInfo = JSONObject()
            .put("name", "androdex_android")
            .put("title", "Androdex Android")
            .put("version", "0.1.0")

        val modernParams = JSONObject()
            .put("clientInfo", clientInfo)
            .put(
                "capabilities",
                JSONObject().put("experimentalApi", true)
            )

        try {
            sendRequest("initialize", modernParams)
        } catch (error: RpcException) {
            val lowered = error.message.lowercase(Locale.US)
            if (
                !lowered.contains("capabilities")
                && !lowered.contains("experimentalapi")
            ) {
                throw error
            }
            sendRequest("initialize", JSONObject().put("clientInfo", clientInfo))
        }

        sendNotification("initialized", null)
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
            val nextRegistry = TrustedMacRegistry(
                records = trustedMacRegistry.records + (
                    pairing.macDeviceId to TrustedMacRecord(
                        macDeviceId = pairing.macDeviceId,
                        macIdentityPublicKey = serverHello.optString("macIdentityPublicKey"),
                        lastPairedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            )
            trustedMacRegistry = nextRegistry
            persistence.saveTrustedMacRegistry(nextRegistry)
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
        if (bridgeOutboundSeq > lastAppliedBridgeOutboundSeq) {
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
        if (
            method == "item/commandExecution/requestApproval"
            || method == "item/fileChange/requestApproval"
            || method.endsWith("requestApproval")
        ) {
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
            return
        }

        sendErrorResponse(idValue, -32601, "Unsupported request method: $method")
    }

    private suspend fun handleNotification(method: String, params: JSONObject?) {
        when (method) {
            "thread/started", "thread/name/updated", "thread/status/changed" -> {
                if (method == "thread/started") {
                    updatesFlow.emit(
                        ClientUpdate.TurnStarted(
                            threadId = extractThreadId(params),
                            turnId = extractTurnId(params),
                        )
                    )
                } else if (method == "thread/status/changed") {
                    updatesFlow.emit(
                        ClientUpdate.ThreadStatusChanged(
                            threadId = extractThreadId(params),
                            status = extractThreadStatus(params),
                        )
                    )
                }
                try {
                    listThreads()
                } catch (_: Throwable) {
                }
            }

            "turn/started" -> {
                updatesFlow.emit(
                    ClientUpdate.TurnStarted(
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                    )
                )
            }

            "turn/completed", "turn/failed", "error" -> {
                val threadId = extractThreadId(params)
                val turnId = extractTurnId(params)
                if (method == "error" && threadId.isNullOrBlank() && turnId.isNullOrBlank()) {
                    extractTurnErrorMessage(params)?.let { updatesFlow.emit(ClientUpdate.Error(it)) }
                    return
                }
                val terminalState = when (method) {
                    "turn/failed" -> TurnTerminalState.FAILED
                    "error" -> if (extractWillRetry(params)) {
                        null
                    } else {
                        TurnTerminalState.FAILED
                    }
                    else -> extractTurnTerminalState(params)
                } ?: return
                updatesFlow.emit(
                    ClientUpdate.TurnCompleted(
                        threadId = threadId,
                        turnId = turnId,
                        terminalState = terminalState,
                        errorMessage = extractTurnErrorMessage(params),
                        willRetry = extractWillRetry(params),
                    )
                )
            }

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
        val response = withTimeout(timeoutForMethod(method)) { responseDeferred.await() }
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

    private suspend fun sendNotification(method: String, params: JSONObject?) {
        val payload = JSONObject().put("method", method)
        if (params != null) {
            payload.put("params", params)
        }
        sendMessage(payload)
    }

    private suspend fun sendResponse(idValue: Any, resultValue: String) {
        sendMessage(
            JSONObject()
                .put("id", idValue)
                .put("result", resultValue)
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
        clearSecureWaiters(IllegalStateException("Socket closed"))

        val update = connectionUpdateForSocketClose(code, consumePendingTerminalConnectionUpdate())
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
        clearSecureWaiters(IllegalStateException("Socket failure"))

        val update = connectionUpdateForSocketFailure(
            savedPairingAvailable = savedPairingPayload != null,
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
        val pairing = savedPairingPayload
        val currentFingerprint = pairing?.let {
            val trusted = trustedMacRegistry.records[it.macDeviceId]
            fingerprint(trusted?.macIdentityPublicKey ?: it.macIdentityPublicKey)
        }
        updatesFlow.tryEmit(ClientUpdate.PairingAvailability(pairing != null, currentFingerprint))
    }

    private fun emitRuntimeConfig() {
        updatesFlow.tryEmit(
            ClientUpdate.RuntimeConfigLoaded(
                models = availableModels,
                selectedModelId = selectedModelOption()?.stableIdentifier ?: selectedModelId,
                selectedReasoningEffort = selectedReasoningEffortForSelectedModel(),
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

    private fun extractAssistantDeltaText(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("delta")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("delta")
    }

    private fun extractReasoningDeltaText(params: JSONObject?): String? {
        if (params == null) return null
        return params.stringOrNull("delta")
            ?: params.objectOrNull("msg", "event")?.stringOrNull("delta")
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
        outstanding.forEach { it.completeExceptionally(IllegalStateException("Disconnected")) }
    }

    private suspend fun emitTerminalConnectionUpdate(update: ClientUpdate.Connection) {
        pendingTerminalConnectionUpdate = update
        updatesFlow.emit(update)
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

    private suspend fun isCurrentSocket(candidate: WebSocket): Boolean {
        return socketMutex.withLock { webSocket === candidate }
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
    pendingTerminalUpdate: ClientUpdate.Connection?,
): ClientUpdate.Connection? {
    if (pendingTerminalUpdate != null) {
        return null
    }
    return if (code in setOf(4000, 4001, 4002, 4003)) {
        ClientUpdate.Connection(
            status = when (code) {
                4002 -> ConnectionStatus.RETRYING_SAVED_PAIRING
                else -> ConnectionStatus.RECONNECT_REQUIRED
            },
            detail = when (code) {
                4002 -> "Host offline, retrying saved pairing until the daemon reconnects."
                4003 -> "This device was replaced by a newer connection. You can reconnect from saved pairing."
                else -> "The relay connection closed (code $code). Reconnect from saved pairing."
            }
        )
    } else {
        ClientUpdate.Connection(
            ConnectionStatus.DISCONNECTED,
            "Relay disconnected (code $code)."
        )
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
