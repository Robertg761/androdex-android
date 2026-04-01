package io.androdex.android.data

import io.androdex.android.model.PairingPayload
import io.androdex.android.model.TrustedMacRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexClientSecureTransportTest {
    @Test
    fun shouldApplyBridgeOutboundSeq_acceptsUnsequencedAndNewerPayloads() {
        assertTrue(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = -1,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
        assertTrue(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = 8,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
    }

    @Test
    fun shouldApplyBridgeOutboundSeq_rejectsReplayedPayloads() {
        assertFalse(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = 7,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
        assertFalse(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = 6,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
    }

    @Test
    fun resolveSecureHandshakePlan_prefersFreshQrBootstrapOverExistingTrustedRecord() {
        val plan = resolveSecureHandshakePlan(
            pairing = PairingPayload(
                version = 3,
                relay = "wss://relay.example.com/relay",
                hostId = "host-123",
                sessionId = null,
                macDeviceId = "mac-123",
                macIdentityPublicKey = "fresh-qr-key",
                bootstrapToken = "bootstrap-token",
                expiresAt = 1_999_999_999_999L,
            ),
            trustedMac = TrustedMacRecord(
                macDeviceId = "mac-123",
                macIdentityPublicKey = "stale-trusted-key",
                lastPairedAtEpochMs = 1_000L,
            ),
        )

        assertEquals("qr_bootstrap", plan.handshakeMode)
        assertEquals("fresh-qr-key", plan.expectedMacIdentityPublicKey)
    }

    @Test
    fun resolveSecureHandshakePlan_keepsTrustedReconnectForSavedSessionPayloads() {
        val plan = resolveSecureHandshakePlan(
            pairing = PairingPayload(
                version = 3,
                relay = "wss://relay.example.com/relay",
                hostId = "host-123",
                sessionId = null,
                macDeviceId = "mac-123",
                macIdentityPublicKey = "fresh-qr-key",
                bootstrapToken = null,
                expiresAt = 0L,
            ),
            trustedMac = TrustedMacRecord(
                macDeviceId = "mac-123",
                macIdentityPublicKey = "trusted-key",
                lastPairedAtEpochMs = 1_000L,
            ),
        )

        assertEquals("trusted_reconnect", plan.handshakeMode)
        assertEquals("trusted-key", plan.expectedMacIdentityPublicKey)
    }
}
