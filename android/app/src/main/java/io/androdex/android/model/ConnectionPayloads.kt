package io.androdex.android.model

import io.androdex.android.transport.macnative.normalizeMacNativeHttpBaseUrl
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal const val macNativePairingPayloadVersion = 1
internal const val macNativePairingTransport = "mac-native"
private const val macNativePairPathSegment = "pair"

internal enum class BackendKind(
    val persistenceValue: String,
) {
    BRIDGE("bridge"),
    MAC_NATIVE("mac-native");

    companion object {
        fun fromPersistenceValue(value: String?): BackendKind? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.persistenceValue == normalized }
        }
    }
}

internal sealed interface ConnectPayloadDescriptor {
    data class Bridge(val payload: PairingPayload) : ConnectPayloadDescriptor

    data class MacNative(val payload: MacNativePairingPayload) : ConnectPayloadDescriptor

    data class Recovery(val payload: RecoveryPayload) : ConnectPayloadDescriptor
}

internal enum class MacNativePairingSource {
    JSON_PAYLOAD,
    PAIR_URL,
}

internal data class MacNativePairingPayload(
    val version: Int,
    val httpBaseUrl: String,
    val credential: String,
    val expiresAt: Long,
    val label: String? = null,
    val fingerprint: String? = null,
    val source: MacNativePairingSource = MacNativePairingSource.JSON_PAYLOAD,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("transport", macNativePairingTransport)
        .put("httpBaseUrl", httpBaseUrl)
        .put("credential", credential)
        .put("expiresAt", expiresAt)
        .put("label", label)
        .put("fingerprint", fingerprint)

    companion object {
        fun fromJson(json: JSONObject): MacNativePairingPayload? {
            val transport = json.optString("transport").trim().lowercase()
            val kind = json.optString("kind").trim().lowercase()
            if (transport != macNativePairingTransport && kind != macNativePairingTransport) {
                return null
            }

            val baseUrl = json.optString("httpBaseUrl")
                .trim()
                .ifEmpty { json.optString("baseUrl").trim() }
                .ifEmpty { json.optString("url").trim() }
            val credential = json.optString("credential").trim()
            if (baseUrl.isEmpty() || credential.isEmpty()) {
                return null
            }

            return MacNativePairingPayload(
                version = json.optInt("v", 0),
                httpBaseUrl = normalizeMacNativeHttpBaseUrl(baseUrl),
                credential = credential,
                expiresAt = json.optLong("expiresAt", 0L),
                label = json.optString("label").trim().ifEmpty { null },
                fingerprint = json.optString("fingerprint").trim().ifEmpty { null },
                source = MacNativePairingSource.JSON_PAYLOAD,
            )
        }
    }
}

internal fun parseConnectPayloadDescriptor(rawPayload: String): ConnectPayloadDescriptor {
    parseMacNativePairUrl(rawPayload)?.let { return ConnectPayloadDescriptor.MacNative(it) }
    val json = JSONObject(rawPayload)
    RecoveryPayload.fromJson(json)?.let { return ConnectPayloadDescriptor.Recovery(it) }
    MacNativePairingPayload.fromJson(json)?.let { return ConnectPayloadDescriptor.MacNative(it) }
    PairingPayload.fromJson(json)?.let { return ConnectPayloadDescriptor.Bridge(it) }
    throw IllegalArgumentException("The payload is missing required pairing or recovery fields.")
}

private fun parseMacNativePairUrl(rawPayload: String): MacNativePairingPayload? {
    val trimmed = rawPayload.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true)
        && !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }

    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.trim()?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") {
        return null
    }

    val encodedAuthority = uri.rawAuthority?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val normalizedPath = uri.rawPath
        ?.trim()
        .orEmpty()
        .trimEnd('/')
    if (!normalizedPath.endsWith("/$macNativePairPathSegment")) {
        return null
    }

    val credential = resolveMacNativePairUrlToken(uri) ?: return null
    val basePath = normalizedPath.removeSuffix("/$macNativePairPathSegment")
    val baseUrl = buildString {
        append(scheme)
        append("://")
        append(encodedAuthority)
        if (basePath.isNotEmpty()) {
            append(basePath)
        }
    }

    return MacNativePairingPayload(
        version = macNativePairingPayloadVersion,
        httpBaseUrl = normalizeMacNativeHttpBaseUrl(baseUrl),
        credential = credential,
        expiresAt = 0L,
        source = MacNativePairingSource.PAIR_URL,
    )
}

private fun resolveMacNativePairUrlToken(uri: URI): String? {
    extractQueryParameter(uri.rawQuery, "token")?.let { return it }
    return extractQueryParameter(uri.rawFragment, "token")
}

private fun extractQueryParameter(rawQuery: String?, name: String): String? {
    val normalizedName = name.trim()
    val query = rawQuery?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return query.split('&')
        .asSequence()
        .mapNotNull { entry ->
            val separatorIndex = entry.indexOf('=')
            val rawKey = if (separatorIndex >= 0) entry.substring(0, separatorIndex) else entry
            val rawValue = if (separatorIndex >= 0) entry.substring(separatorIndex + 1) else ""
            val decodedKey = URLDecoder.decode(rawKey, Charsets.UTF_8).trim()
            if (decodedKey != normalizedName) {
                null
            } else {
                URLDecoder.decode(rawValue, Charsets.UTF_8)
                    .trim()
                    .ifEmpty { null }
            }
        }
        .firstOrNull()
}
