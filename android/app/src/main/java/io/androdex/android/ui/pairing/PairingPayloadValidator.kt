package io.androdex.android.ui.pairing

import io.androdex.android.AppEnvironment
import io.androdex.android.crypto.pairingQrVersion
import io.androdex.android.crypto.secureClockSkewToleranceSeconds
import io.androdex.android.model.PairingPayload
import io.androdex.android.model.RecoveryPayload
import org.json.JSONObject

internal sealed interface ConnectPayloadValidationResult {
    data class Success(val payload: PairingPayload) : ConnectPayloadValidationResult

    data class RecoverySuccess(val payload: RecoveryPayload) : ConnectPayloadValidationResult

    data class Error(val message: String) : ConnectPayloadValidationResult

    data class UpdateRequired(val message: String) : ConnectPayloadValidationResult
}

internal fun validateConnectPayload(
    rawPayload: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): ConnectPayloadValidationResult {
    val json = runCatching { JSONObject(rawPayload) }.getOrNull()
        ?: return ConnectPayloadValidationResult.Error("The payload is not valid JSON.")
    val recoveryPayload = RecoveryPayload.fromJson(json)
    if (recoveryPayload != null) {
        return ConnectPayloadValidationResult.RecoverySuccess(recoveryPayload)
    }
    val payload = PairingPayload.fromJson(json)
        ?: return ConnectPayloadValidationResult.Error("The payload is missing required pairing or recovery fields.")
    if (payload.version != 2 && payload.version != pairingQrVersion) {
        return ConnectPayloadValidationResult.UpdateRequired(
            "This pairing QR came from a different Androdex bridge format. Update the host package with `${AppEnvironment.bridgeUpdateCommand}` and scan a new QR code.",
        )
    }
    if (payload.relay.isBlank()) {
        return ConnectPayloadValidationResult.Error("The pairing payload is missing the relay URL.")
    }
    if (payload.routingId.isBlank()) {
        return ConnectPayloadValidationResult.Error("The pairing payload is missing the host identity.")
    }
    if (payload.version >= pairingQrVersion && payload.bootstrapToken.isNullOrBlank()) {
        return ConnectPayloadValidationResult.Error("The pairing payload is missing the bootstrap token.")
    }
    if (payload.version < pairingQrVersion && payload.sessionId.isNullOrBlank()) {
        return ConnectPayloadValidationResult.Error("The pairing payload is missing the session ID.")
    }
    val expiryWithSkew = payload.expiresAt + (secureClockSkewToleranceSeconds * 1000)
    if (expiryWithSkew < nowEpochMs) {
        return ConnectPayloadValidationResult.Error(
            if (payload.version >= pairingQrVersion) {
                "The pairing QR code has expired. Generate a new QR code from the daemon."
            } else {
                "The pairing QR code has expired. Generate a new QR code from the bridge."
            },
        )
    }
    return ConnectPayloadValidationResult.Success(payload)
}
