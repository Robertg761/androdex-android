package io.androdex.android.data

import io.androdex.android.model.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
            "Androdex could not read this phone's saved trusted identity. Durable trust was preserved, but automatic reconnect is blocked until you repair with a fresh QR or forget the trusted host.",
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
            "The saved live relay session was unreadable, so Androdex cleared only that disposable reconnect target. Your trusted host details were kept when possible.",
            savedSessionNotice,
        )
        assertEquals(
            "Androdex could not read the trusted host registry. Durable trust was preserved, but automatic reconnect is blocked until you repair with a fresh QR or forget the trusted host.",
            trustedRegistryNotice,
        )
    }

    @Test
    fun blockedDurableTrustDetail_isReturnedForUnreadablePhoneIdentity() {
        val detail = buildBlockedDurableTrustDetail(
            phoneIdentityUnreadable = true,
            trustedMacUnreadable = false,
        )

        assertNotNull(detail)
        assertEquals(
            "This Android device cannot read its saved trusted identity, so secure reconnect is blocked. Repair with a fresh QR code or forget the trusted host on this phone.",
            detail,
        )
    }
}
