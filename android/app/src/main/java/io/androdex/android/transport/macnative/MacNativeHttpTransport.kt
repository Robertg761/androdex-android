package io.androdex.android.transport.macnative

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.Locale

internal class MacNativeHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

internal abstract class OkHttpMacNativeJsonTransport(
    private val okHttpClient: OkHttpClient,
) {
    protected fun executeJsonRequest(request: Request): JSONObject {
        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw MacNativeHttpException(
                    statusCode = response.code,
                    message = decodeMacNativeErrorMessage(responseBody, response.code),
                )
            }
            if (responseBody.isBlank()) {
                return JSONObject()
            }
            return JSONObject(responseBody)
        }
    }

    protected fun jsonPostBody(payload: JSONObject): okhttp3.RequestBody {
        return payload
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
    }
}

internal fun resolveMacNativeHttpEndpointUrl(
    httpBaseUrl: String,
    endpointPath: String,
): String {
    require(endpointPath.startsWith("/")) { "Endpoint paths must start with '/'." }
    val normalizedHttpBaseUrl = normalizeMacNativeHttpBaseUrl(httpBaseUrl)
    val uri = URI(normalizedHttpBaseUrl)
    return URI(
        uri.scheme,
        uri.userInfo,
        uri.host,
        uri.port,
        endpointPath,
        null,
        null,
    ).toASCIIString()
}

internal fun decodeMacNativeErrorMessage(
    responseBody: String,
    statusCode: Int,
): String {
    if (responseBody.isBlank()) {
        return "Mac-native HTTP request failed ($statusCode)."
    }

    return runCatching { JSONObject(responseBody) }
        .getOrNull()
        ?.optString("error")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: responseBody
}

internal fun parseMacNativeTimestamp(rawValue: Any?): Long? {
    return when (rawValue) {
        is Number -> {
            val value = rawValue.toDouble()
            if (value > 10_000_000_000) value.toLong() else (value * 1000).toLong()
        }

        is String -> {
            val trimmed = rawValue.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                trimmed.toLongOrNull()?.let { numeric ->
                    if (numeric > 10_000_000_000) numeric else numeric * 1000
                } ?: parseMacNativeIsoTimestamp(trimmed)
            }
        }

        else -> null
    }
}

private fun parseMacNativeIsoTimestamp(value: String): Long? {
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
