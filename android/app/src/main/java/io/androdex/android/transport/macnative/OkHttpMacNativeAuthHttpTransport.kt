package io.androdex.android.transport.macnative

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class OkHttpMacNativeAuthHttpTransport(
    okHttpClient: OkHttpClient,
) : OkHttpMacNativeJsonTransport(okHttpClient), MacNativeAuthHttpTransport {
    override suspend fun fetchEnvironmentDescriptor(serverTarget: MacNativeServerTarget): JSONObject {
        val request = Request.Builder()
            .url(resolveMacNativeHttpEndpointUrl(serverTarget.httpBaseUrl, MacNativeHttpPaths.environmentDescriptor))
            .get()
            .build()
        return executeJsonRequest(request)
    }

    override suspend fun bootstrapBearerSession(
        serverTarget: MacNativeServerTarget,
        credential: String,
    ): MacNativeBearerSession {
        val request = Request.Builder()
            .url(resolveMacNativeHttpEndpointUrl(serverTarget.httpBaseUrl, MacNativeHttpPaths.authBootstrapBearer))
            .post(jsonPostBody(JSONObject().put("credential", credential)))
            .build()
        val responseJson = executeJsonRequest(request)
        val sessionToken = responseJson.optString("sessionToken").trim()
        require(sessionToken.isNotEmpty()) { "Bootstrap response is missing sessionToken." }
        return MacNativeBearerSession(
            serverTarget = serverTarget,
            sessionToken = sessionToken,
            role = responseJson.optString("role").trim().ifEmpty { null },
            expiresAtEpochMs = parseMacNativeTimestamp(responseJson.opt("expiresAt")),
        )
    }

    override suspend fun readSession(
        serverTarget: MacNativeServerTarget,
        bearerSessionToken: String,
    ): MacNativeSessionState {
        val request = Request.Builder()
            .url(resolveMacNativeHttpEndpointUrl(serverTarget.httpBaseUrl, MacNativeHttpPaths.authSession))
            .header("Authorization", "Bearer $bearerSessionToken")
            .get()
            .build()
        val responseJson = executeJsonRequest(request)
        return MacNativeSessionState(
            authenticated = responseJson.optBoolean("authenticated"),
            role = responseJson.optString("role").trim().ifEmpty { null },
            sessionMethod = responseJson.optString("sessionMethod").trim().ifEmpty { null },
            expiresAtEpochMs = parseMacNativeTimestamp(responseJson.opt("expiresAt")),
            payload = responseJson,
        )
    }

    override suspend fun issueWebSocketToken(session: MacNativeBearerSession): MacNativeWebSocketToken {
        val request = Request.Builder()
            .url(resolveMacNativeHttpEndpointUrl(session.serverTarget.httpBaseUrl, MacNativeHttpPaths.authWebSocketToken))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .post(jsonPostBody(JSONObject()))
            .build()
        val responseJson = executeJsonRequest(request)
        val token = responseJson.optString("token").trim()
        require(token.isNotEmpty()) { "WebSocket token response is missing token." }
        return MacNativeWebSocketToken(
            token = token,
            expiresAtEpochMs = parseMacNativeTimestamp(responseJson.opt("expiresAt")),
        )
    }
}
