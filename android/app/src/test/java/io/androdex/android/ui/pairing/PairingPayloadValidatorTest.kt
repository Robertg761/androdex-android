package io.androdex.android.ui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadValidatorTest {
    @Test
    fun unsupportedVersion_returnsUpdateRequired() {
        val result = validatePairingPayload(
            rawPayload = """
                {
                  "v": 99,
                  "relay": "wss://relay.example.com/socket",
                  "hostId": "host-1",
                  "macDeviceId": "mac-1",
                  "macIdentityPublicKey": "pub",
                  "bootstrapToken": "token",
                  "expiresAt": 9999999999999
                }
            """.trimIndent(),
            nowEpochMs = 0L,
        )

        assertTrue(result is PairingPayloadValidationResult.UpdateRequired)
    }

    @Test
    fun expiredLegacyPayload_returnsBridgeRefreshMessage() {
        val result = validatePairingPayload(
            rawPayload = """
                {
                  "v": 2,
                  "relay": "wss://relay.example.com/socket",
                  "sessionId": "session-1",
                  "macDeviceId": "mac-1",
                  "macIdentityPublicKey": "pub",
                  "expiresAt": 1000
                }
            """.trimIndent(),
            nowEpochMs = 999999999L,
        )

        assertTrue(result is PairingPayloadValidationResult.Error)
        assertEquals(
            "The pairing QR code has expired. Generate a new QR code from the bridge.",
            (result as PairingPayloadValidationResult.Error).message,
        )
    }

    @Test
    fun currentPayload_returnsSuccess() {
        val result = validatePairingPayload(
            rawPayload = """
                {
                  "v": 3,
                  "relay": "wss://relay.example.com/socket",
                  "hostId": "host-1",
                  "macDeviceId": "mac-1",
                  "macIdentityPublicKey": "pub",
                  "bootstrapToken": "token",
                  "expiresAt": 9999999999999
                }
            """.trimIndent(),
            nowEpochMs = 0L,
        )

        assertTrue(result is PairingPayloadValidationResult.Success)
    }
}
