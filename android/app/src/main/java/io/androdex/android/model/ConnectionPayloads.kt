package io.androdex.android.model

import io.androdex.android.transport.macnative.normalizeMacNativeHttpBaseUrl
import org.json.JSONObject

internal const val macNativePairingPayloadVersion = 1
internal const val macNativePairingTransport = "mac-native"

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

internal data class MacNativePairingPayload(
    val version: Int,
    val httpBaseUrl: String,
    val credential: String,
    val expiresAt: Long,
    val label: String? = null,
    val fingerprint: String? = null,
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
            )
        }
    }
}

internal fun parseConnectPayloadDescriptor(rawPayload: String): ConnectPayloadDescriptor {
    val json = JSONObject(rawPayload)
    RecoveryPayload.fromJson(json)?.let { return ConnectPayloadDescriptor.Recovery(it) }
    MacNativePairingPayload.fromJson(json)?.let { return ConnectPayloadDescriptor.MacNative(it) }
    PairingPayload.fromJson(json)?.let { return ConnectPayloadDescriptor.Bridge(it) }
    throw IllegalArgumentException("The payload is missing required pairing or recovery fields.")
}
