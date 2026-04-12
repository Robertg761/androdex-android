package io.androdex.android.ui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadValidatorTest {
    @Test
    fun unsupportedVersion_returnsUpdateRequired() {
        val result = validateConnectPayload(
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

        assertTrue(result is ConnectPayloadValidationResult.UpdateRequired)
    }

    @Test
    fun expiredLegacyPayload_returnsBridgeRefreshMessage() {
        val result = validateConnectPayload(
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

        assertTrue(result is ConnectPayloadValidationResult.Error)
        assertEquals(
            "The pairing QR code has expired. Generate a new QR code from the bridge.",
            (result as ConnectPayloadValidationResult.Error).message,
        )
    }

    @Test
    fun currentPayload_returnsSuccess() {
        val result = validateConnectPayload(
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

        assertTrue(result is ConnectPayloadValidationResult.Success)
    }

    @Test
    fun macNativePayload_returnsSuccess() {
        val result = validateConnectPayload(
            rawPayload = """
                {
                  "v": 1,
                  "transport": "mac-native",
                  "httpBaseUrl": "https://mac.example.com",
                  "credential": "pair_123",
                  "label": "Robert's Mac",
                  "fingerprint": "AA:BB:CC",
                  "expiresAt": 9999999999999
                }
            """.trimIndent(),
            nowEpochMs = 0L,
        )

        assertTrue(result is ConnectPayloadValidationResult.Success)
    }
}
