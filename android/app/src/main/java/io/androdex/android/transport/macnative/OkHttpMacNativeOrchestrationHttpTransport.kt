package io.androdex.android.transport.macnative

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class OkHttpMacNativeOrchestrationHttpTransport(
    okHttpClient: OkHttpClient,
) : OkHttpMacNativeJsonTransport(okHttpClient), MacNativeOrchestrationHttpTransport {
    override suspend fun fetchSnapshot(session: MacNativeBearerSession): JSONObject {
        val request = Request.Builder()
            .url(resolveMacNativeHttpEndpointUrl(session.serverTarget.httpBaseUrl, MacNativeHttpPaths.orchestrationSnapshot))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .get()
            .build()
        return executeJsonRequest(request)
    }

    override suspend fun dispatchCommand(
        session: MacNativeBearerSession,
        command: JSONObject,
    ): JSONObject {
        val request = Request.Builder()
            .url(resolveMacNativeHttpEndpointUrl(session.serverTarget.httpBaseUrl, MacNativeHttpPaths.orchestrationDispatch))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .post(jsonPostBody(command))
            .build()
        return executeJsonRequest(request)
    }
}
