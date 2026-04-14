package io.androdex.android.ui.pairing

import io.androdex.android.AppEnvironment
import io.androdex.android.crypto.pairingQrVersion
import io.androdex.android.crypto.secureClockSkewToleranceSeconds
import io.androdex.android.model.ConnectPayloadDescriptor
import io.androdex.android.model.MacNativePairingSource
import io.androdex.android.model.PairingPayload
import io.androdex.android.model.RecoveryPayload
import io.androdex.android.model.macNativePairingPayloadVersion
import io.androdex.android.model.parseConnectPayloadDescriptor

internal sealed interface ConnectPayloadValidationResult {
    data class Success(val payload: Any) : ConnectPayloadValidationResult

    data class RecoverySuccess(val payload: RecoveryPayload) : ConnectPayloadValidationResult

    data class Error(val message: String) : ConnectPayloadValidationResult

    data class UpdateRequired(val message: String) : ConnectPayloadValidationResult
}

internal fun validateConnectPayload(
    rawPayload: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): ConnectPayloadValidationResult {
    val payload = runCatching { parseConnectPayloadDescriptor(rawPayload) }.getOrElse { error ->
        return if (error is IllegalArgumentException) {
            ConnectPayloadValidationResult.Error(error.message ?: "The payload is not valid JSON.")
        } else {
            ConnectPayloadValidationResult.Error("The payload is not valid JSON.")
        }
    }

    return when (payload) {
        is ConnectPayloadDescriptor.Recovery -> ConnectPayloadValidationResult.RecoverySuccess(payload.payload)
        is ConnectPayloadDescriptor.MacNative -> validateMacNativePairingPayload(payload.payload, nowEpochMs)
        is ConnectPayloadDescriptor.Bridge -> validateBridgePairingPayload(payload.payload, nowEpochMs)
    }
}

private fun validateMacNativePairingPayload(
    payload: io.androdex.android.model.MacNativePairingPayload,
    nowEpochMs: Long,
): ConnectPayloadValidationResult {
    if (payload.version != macNativePairingPayloadVersion) {
        return ConnectPayloadValidationResult.UpdateRequired(
            "This Mac pairing payload came from an unsupported Android convergence format. Update Androdex on both devices and scan a new QR code.",
        )
    }
    if (payload.source == MacNativePairingSource.PAIR_URL) {
        return ConnectPayloadValidationResult.Success(payload)
    }
    val expiryWithSkew = payload.expiresAt + (secureClockSkewToleranceSeconds * 1000)
    if (expiryWithSkew < nowEpochMs) {
        return ConnectPayloadValidationResult.Error(
            "The Mac pairing payload has expired. Generate a new QR code from the Mac app.",
        )
    }
    return ConnectPayloadValidationResult.Success(payload)
}

private fun validateBridgePairingPayload(
    payload: PairingPayload,
    nowEpochMs: Long,
): ConnectPayloadValidationResult {
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
