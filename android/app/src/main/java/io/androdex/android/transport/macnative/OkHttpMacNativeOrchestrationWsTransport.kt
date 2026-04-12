package io.androdex.android.transport.macnative

import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class OkHttpMacNativeOrchestrationWsTransport(
    private val okHttpClient: OkHttpClient,
) : MacNativeOrchestrationWsTransport {
    override suspend fun connect(
        session: MacNativeBearerSession,
        token: MacNativeWebSocketToken,
        eventListener: MacNativeOrchestrationEventListener,
    ): MacNativeWebSocketConnection {
        val connection = ManagedMacNativeRpcWebSocket(
            okHttpClient = okHttpClient,
            socketUrl = macNativeSocketUrl(session.serverTarget.wsBaseUrl, token.token),
        )
        connection.open()
        connection.subscribe(MacNativeWsMethods.subscribeOrchestrationDomainEvents, JSONObject()) { values ->
            for (index in 0 until values.length()) {
                val event = values.optJSONObject(index) ?: continue
                eventListener.onDomainEvent(event)
            }
        }
        return object : MacNativeWebSocketConnection {
            override suspend fun close(code: Int, reason: String) {
                connection.close(code, reason)
            }
        }
    }

    override suspend fun replayEvents(
        session: MacNativeBearerSession,
        token: MacNativeWebSocketToken,
        fromSequenceExclusive: Long,
    ): List<JSONObject> {
        val webSocket = ManagedMacNativeRpcWebSocket(
            okHttpClient = okHttpClient,
            socketUrl = macNativeSocketUrl(
                wsBaseUrl = session.serverTarget.wsBaseUrl,
                token = token.token,
            ),
        )
        return try {
            webSocket.open()
            val response = webSocket.request(
                tag = MacNativeWsMethods.orchestrationReplayEvents,
                payload = JSONObject().put("fromSequenceExclusive", fromSequenceExclusive.coerceAtLeast(0L)),
            )
            when (val replayValue = response.opt("value")) {
                is JSONArray -> replayValue.toJsonObjectList()
                else -> emptyList()
            }
        } finally {
            webSocket.close()
        }
    }
}

private class ManagedMacNativeRpcWebSocket(
    okHttpClient: OkHttpClient,
    socketUrl: String,
) {
    private val nextRequestId = AtomicLong(1L)
    private val openDeferred = CompletableDeferred<Unit>()
    private val closeDeferred = CompletableDeferred<Throwable?>()
    private val pendingExits = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val chunkListeners = ConcurrentHashMap<String, (JSONArray) -> Unit>()
    private val webSocket: WebSocket

    init {
        webSocket = okHttpClient.newWebSocket(
            Request.Builder().url(socketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openDeferred.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failOutstanding(t)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closeDeferred.isCompleted) {
                        closeDeferred.complete(null)
                    }
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closeDeferred.isCompleted) {
                        closeDeferred.complete(null)
                    }
                }
            },
        )
    }

    suspend fun open() {
        openDeferred.await()
    }

    suspend fun request(tag: String, payload: JSONObject): JSONObject {
        open()
        val requestId = nextRequestId.getAndIncrement().toString()
        val response = CompletableDeferred<JSONObject>()
        pendingExits[requestId] = response
        sendJson(
            JSONObject()
                .put("_tag", "Request")
                .put("id", requestId)
                .put("tag", tag)
                .put("payload", payload),
        )
        return response.await().parseSuccessExit()
    }

    suspend fun subscribe(
        tag: String,
        payload: JSONObject,
        onChunk: (JSONArray) -> Unit,
    ) {
        open()
        val requestId = nextRequestId.getAndIncrement().toString()
        chunkListeners[requestId] = onChunk
        sendJson(
            JSONObject()
                .put("_tag", "Request")
                .put("id", requestId)
                .put("tag", tag)
                .put("payload", payload),
        )
    }

    suspend fun close(code: Int = 1000, reason: String = "Normal Closure") {
        webSocket.close(code, reason)
        closeDeferred.await()
    }

    private fun handleMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("_tag")) {
            "Ping" -> sendJson(JSONObject().put("_tag", "Pong"))
            "Chunk" -> {
                val requestId = message.optString("requestId").trim()
                val values = message.optJSONArray("values") ?: JSONArray()
                val listener = chunkListeners[requestId] ?: return
                runCatching { listener(values) }
            }
            "Exit" -> {
                val requestId = message.optString("requestId").trim()
                pendingExits.remove(requestId)?.complete(message.optJSONObject("exit") ?: JSONObject())
            }
            "ClientProtocolError", "Defect" -> {
                failOutstanding(IllegalStateException(message.toString()))
            }
        }
    }

    private fun failOutstanding(error: Throwable) {
        if (!openDeferred.isCompleted) {
            openDeferred.completeExceptionally(error)
        }
        if (!closeDeferred.isCompleted) {
            closeDeferred.complete(error)
        }
        pendingExits.values.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
        pendingExits.clear()
        chunkListeners.clear()
    }

    private fun sendJson(payload: JSONObject) {
        val sent = webSocket.send(payload.toString())
        if (!sent) {
            throw IOException("Failed to send websocket payload.")
        }
    }
}

private fun macNativeSocketUrl(
    wsBaseUrl: String,
    token: String,
    queryParameterName: String = "wsToken",
): String {
    val url = java.net.URI("$wsBaseUrl/ws").toURL().toURI()
    val withQuery = java.net.URI(
        url.scheme,
        url.userInfo,
        url.host,
        url.port,
        url.path,
        "$queryParameterName=$token",
        null,
    )
    return withQuery.toASCIIString()
}

private fun JSONObject.parseSuccessExit(): JSONObject {
    if (optString("_tag") != "Success") {
        val errorPayload = opt("failure")
        throw IOException(
            if (errorPayload is JSONObject) {
                errorPayload.optString("message").ifBlank { errorPayload.toString() }
            } else {
                "WebSocket RPC request failed."
            },
        )
    }
    return optJSONObject("value") ?: JSONObject().put("value", opt("value"))
}

private fun JSONArray.toJsonObjectList(): List<JSONObject> {
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(item)
        }
    }
}
