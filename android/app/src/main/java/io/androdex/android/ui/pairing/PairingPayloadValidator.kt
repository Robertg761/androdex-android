package io.androdex.android.ui.pairing

import io.androdex.android.AppEnvironment
import io.androdex.android.crypto.pairingQrVersion
import io.androdex.android.crypto.secureClockSkewToleranceSeconds
import io.androdex.android.model.PairingPayload
import org.json.JSONObject

internal sealed interface PairingPayloadValidationResult {
    data class Success(val payload: PairingPayload) : PairingPayloadValidationResult

    data class Error(val message: String) : PairingPayloadValidationResult

    data class UpdateRequired(val message: String) : PairingPayloadValidationResult
}

internal fun validatePairingPayload(
    rawPayload: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): PairingPayloadValidationResult {
    val payload = runCatching { PairingPayload.fromJson(JSONObject(rawPayload)) }.getOrNull()
        ?: return PairingPayloadValidationResult.Error("The QR payload is missing required pairing fields.")
    if (payload.version != 2 && payload.version != pairingQrVersion) {
        return PairingPayloadValidationResult.UpdateRequired(
            "This pairing QR came from a different Androdex bridge format. Update the host package with `${AppEnvironment.bridgeUpdateCommand}` and scan a new QR code.",
        )
    }
    if (payload.relay.isBlank()) {
        return PairingPayloadValidationResult.Error("The pairing payload is missing the relay URL.")
    }
    if (payload.routingId.isBlank()) {
        return PairingPayloadValidationResult.Error("The pairing payload is missing the host identity.")
    }
    if (payload.version >= pairingQrVersion && payload.bootstrapToken.isNullOrBlank()) {
        return PairingPayloadValidationResult.Error("The pairing payload is missing the bootstrap token.")
    }
    if (payload.version < pairingQrVersion && payload.sessionId.isNullOrBlank()) {
        return PairingPayloadValidationResult.Error("The pairing payload is missing the session ID.")
    }
    val expiryWithSkew = payload.expiresAt + (secureClockSkewToleranceSeconds * 1000)
    if (expiryWithSkew < nowEpochMs) {
        return PairingPayloadValidationResult.Error(
            if (payload.version >= pairingQrVersion) {
                "The pairing QR code has expired. Generate a new QR code from the daemon."
            } else {
                "The pairing QR code has expired. Generate a new QR code from the bridge."
            },
        )
    }
    return PairingPayloadValidationResult.Success(payload)
}
