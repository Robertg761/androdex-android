package io.androdex.android.data

import io.androdex.android.model.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndrodexPairingPersistencePolicyTest {
    @Test
    fun legacyPairingPayload_migratesToSavedRelaySession() {
        val savedSession = PairingPayload(
            version = 3,
            relay = "wss://relay.example.com/relay",
            hostId = "host-123",
            sessionId = null,
            macDeviceId = "mac-123",
            macIdentityPublicKey = "mac-public-key",
            bootstrapToken = null,
            expiresAt = 0L,
        ).toSavedRelaySession(lastAppliedBridgeOutboundSeq = 19)

        requireNotNull(savedSession)
        assertEquals("wss://relay.example.com/relay", savedSession.relayUrl)
        assertEquals("host-123", savedSession.hostId)
        assertEquals(19, savedSession.lastAppliedBridgeOutboundSeq)
    }

    @Test
    fun invalidLegacyPairingPayload_doesNotMigrate() {
        val savedSession = PairingPayload(
            version = 3,
            relay = "",
            hostId = null,
            sessionId = null,
            macDeviceId = "mac-123",
            macIdentityPublicKey = "mac-public-key",
            bootstrapToken = null,
            expiresAt = 0L,
        ).toSavedRelaySession()

        assertNull(savedSession)
    }

    @Test
    fun secureStateNotice_prefersPhoneIdentityLossOverSessionLoss() {
        val notice = buildSecureStateRecoveryNotice(
            savedRelaySessionUnreadable = true,
            phoneIdentityUnreadable = true,
            trustedMacUnreadable = true,
        )

        assertEquals(
            "This Android device can no longer read its secure identity after an install or restore change. Trusted reconnect was reset, so scan a fresh QR code to pair again.",
            notice,
        )
    }

    @Test
    fun secureStateNotice_distinguishesSavedSessionLossFromTrustedRegistryLoss() {
        val savedSessionNotice = buildSecureStateRecoveryNotice(
            savedRelaySessionUnreadable = true,
            phoneIdentityUnreadable = false,
            trustedMacUnreadable = false,
        )
        val trustedRegistryNotice = buildSecureStateRecoveryNotice(
            savedRelaySessionUnreadable = false,
            phoneIdentityUnreadable = false,
            trustedMacUnreadable = true,
        )

        assertEquals(
            "The saved live relay session was unreadable, so Androdex cleared only that reconnect target. Your trusted host record was kept when possible.",
            savedSessionNotice,
        )
        assertEquals(
            "The trusted host registry was unreadable, so only the remembered trust records were cleared. A still-valid live relay session can keep working until it expires.",
            trustedRegistryNotice,
        )
    }
}
