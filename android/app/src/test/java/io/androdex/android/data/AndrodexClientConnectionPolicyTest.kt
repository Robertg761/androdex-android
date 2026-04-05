package io.androdex.android.data

import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.SavedRelaySession
import io.androdex.android.model.TrustedMacRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexClientConnectionPolicyTest {
    @Test
    fun secureError_mapsSavedPairingAuthFailuresToReconnectRequired() {
        val update = secureErrorConnectionUpdate(
            code = "phone_not_trusted",
            message = "Phone is no longer trusted.",
        )

        assertEquals(ConnectionStatus.RECONNECT_REQUIRED, update.status)
        assertEquals("Phone is no longer trusted.", update.detail)
    }

    @Test
    fun secureError_mapsPhoneReplacementToReconnectRequired() {
        val update = secureErrorConnectionUpdate(
            code = "phone_replacement_required",
            message = "Reset pairing on the host before pairing a new device.",
        )

        assertEquals(ConnectionStatus.RECONNECT_REQUIRED, update.status)
        assertEquals("Reset pairing on the host before pairing a new device.", update.detail)
    }

    @Test
    fun socketFailure_doesNotOverridePendingTerminalHandshakeStatus() {
        val terminalUpdate = ClientUpdate.Connection(
            status = ConnectionStatus.RECONNECT_REQUIRED,
            detail = "The secure host signature could not be verified.",
        )

        val update = connectionUpdateForSocketFailure(
            savedPairingAvailable = true,
            errorMessage = "connection reset",
            pendingTerminalUpdate = terminalUpdate,
        )

        assertNull(update)
    }

    @Test
    fun socketClose_keepsRetryOnlyForHostOfflineClose() {
        val update = connectionUpdateForSocketClose(
            code = 4002,
            hasTrustedHost = true,
            pendingTerminalUpdate = null,
        )

        assertEquals(ConnectionStatus.RETRYING_SAVED_PAIRING, update?.status)
        assertEquals(
            "Host offline, retrying saved pairing until the daemon reconnects.",
            update?.detail,
        )
    }

    @Test
    fun socketClose_retriesSavedPairingWhenHostIsTemporarilyUnavailable() {
        val update = connectionUpdateForSocketClose(
            code = 4004,
            hasTrustedHost = true,
            pendingTerminalUpdate = null,
        )

        assertEquals(ConnectionStatus.RETRYING_SAVED_PAIRING, update?.status)
        assertEquals("Host temporarily unavailable, retrying saved pairing.", update?.detail)
    }

    @Test
    fun trustedSessionResolveUrl_rewritesRelaySocketPathToHttpsEndpoint() {
        val resolveUrl = trustedSessionResolveUrl("wss://relay.androdex.xyz/relay")

        assertEquals("https://relay.androdex.xyz/v1/trusted/session/resolve", resolveUrl)
    }

    @Test
    fun trustedSessionResolveUrl_preservesBasePathBeforeRelaySegment() {
        val resolveUrl = trustedSessionResolveUrl("ws://localhost:8787/custom/relay")

        assertEquals("http://localhost:8787/custom/v1/trusted/session/resolve", resolveUrl)
    }

    @Test
    fun trustedSessionResolveFailure_requiresFreshLiveSessionWhenRelaySaysHostIsOffline() {
        val result = mapTrustedSessionResolveFailure(
            responseCode = 404,
            responseBody = JSONObject()
                .put("code", "session_unavailable")
                .put("message", "The trusted host is offline right now."),
            fallbackPolicy = TrustedResolveFallbackPolicy.REQUIRE_FRESH_LIVE_SESSION,
        )

        assertTrue(result is TrustedSessionResolveResult.LiveSessionUnresolved)
        assertEquals("The trusted host is offline right now.", (result as TrustedSessionResolveResult.LiveSessionUnresolved).detail)
    }

    @Test
    fun trustedSessionResolveFailure_fallsBackToExistingReconnectCandidateWhenRelayCannotResolve() {
        val result = mapTrustedSessionResolveFailure(
            responseCode = 404,
            responseBody = JSONObject()
                .put("code", "session_unavailable")
                .put("message", "The trusted host is offline right now."),
            fallbackPolicy = TrustedResolveFallbackPolicy.ALLOW_EXISTING_RECONNECT_CANDIDATE,
        )

        assertTrue(result is TrustedSessionResolveResult.FallbackToSavedSession)
        assertEquals(
            "The trusted host is offline right now.",
            (result as TrustedSessionResolveResult.FallbackToSavedSession).detail,
        )
    }

    @Test
    fun trustedSessionResolveFailure_marksInvalidSignatureAsRepairRequired() {
        val result = mapTrustedSessionResolveFailure(
            responseCode = 403,
            responseBody = JSONObject()
                .put("code", "invalid_signature")
                .put("message", "The trusted-session resolve signature is invalid."),
            fallbackPolicy = TrustedResolveFallbackPolicy.ALLOW_EXISTING_RECONNECT_CANDIDATE,
        )

        assertTrue(result is TrustedSessionResolveResult.RepairRequired)
        assertEquals(
            "The trusted-session resolve signature is invalid.",
            (result as TrustedSessionResolveResult.RepairRequired).detail,
        )
    }

    @Test
    fun trustedSessionResolveFailure_onlyFallsBackToSavedSessionForTransportErrors() {
        val result = mapTrustedSessionResolveFailure(
            responseCode = 0,
            responseBody = null,
            fallbackPolicy = TrustedResolveFallbackPolicy.ALLOW_EXISTING_RECONNECT_CANDIDATE,
        )

        assertTrue(result is TrustedSessionResolveResult.FallbackToSavedSession)
        assertEquals(
            "Trusted host is known, but the relay could not resolve a fresh live session.",
            (result as TrustedSessionResolveResult.FallbackToSavedSession).detail,
        )
    }

    @Test
    fun legacyRelaySessionIdentifier_ignoresStableUuidDeviceIds() {
        assertEquals(
            false,
            looksLikeLegacyRelaySessionIdentifier("51b5f04f-b19d-4fa7-b0ea-df5b31adb240"),
        )
    }

    @Test
    fun legacyRelaySessionIdentifier_detectsWhenDeviceIdMatchesLiveRoutingId() {
        assertTrue(
            looksLikeLegacyRelaySessionIdentifier(
                macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
                routingId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            )
        )
    }

    @Test
    fun stableRelayHostId_wrapsMacDeviceIdWithoutLookingLegacy() {
        val macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240"
        val hostId = stableRelayHostId(macDeviceId)

        assertEquals("mac.51b5f04f-b19d-4fa7-b0ea-df5b31adb240", hostId)
        assertEquals(false, looksLikeLegacyRelaySessionIdentifier(macDeviceId, hostId))
        assertTrue(isStableRelayHostIdForMacDeviceId(hostId, macDeviceId))
    }

    @Test
    fun legacyRelaySessionIdentifier_ignoresRealDeviceIds() {
        assertEquals(false, looksLikeLegacyRelaySessionIdentifier("mac-5"))
    }

    @Test
    fun selectPreferredTrustedMacRecord_prefersStableTrustedRecordOverLegacySavedSession() {
        val legacySession = SavedRelaySession(
            relayUrl = "wss://relay.example/relay",
            hostId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            macIdentityPublicKey = "legacy-key",
            protocolVersion = 3,
        )
        val legacyTrusted = TrustedMacRecord(
            macDeviceId = legacySession.macDeviceId,
            macIdentityPublicKey = "legacy-key",
            lastPairedAtEpochMs = 10L,
        )
        val stableTrusted = TrustedMacRecord(
            macDeviceId = "9fedc6fd-e3b8-4da8-9b65-6b545ab3fa2f",
            macIdentityPublicKey = "stable-key",
            lastPairedAtEpochMs = 20L,
            relayUrl = "wss://relay.example/relay",
            lastResolvedHostId = "host-5",
            lastUsedAtEpochMs = 30L,
        )

        val selected = selectPreferredTrustedMacRecord(
            records = mapOf(
                legacyTrusted.macDeviceId to legacyTrusted,
                stableTrusted.macDeviceId to stableTrusted,
            ),
            preferredDeviceId = legacyTrusted.macDeviceId,
            savedRelaySession = legacySession,
        )

        assertEquals("9fedc6fd-e3b8-4da8-9b65-6b545ab3fa2f", selected?.macDeviceId)
    }

    @Test
    fun selectPreferredTrustedMacRecord_usesLegacyRecordWhenOnlyLegacyTrustExists() {
        val legacyTrusted = TrustedMacRecord(
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            macIdentityPublicKey = "legacy-key",
            lastPairedAtEpochMs = 10L,
            lastResolvedHostId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
        )

        val selected = selectPreferredTrustedMacRecord(
            records = mapOf(legacyTrusted.macDeviceId to legacyTrusted),
            preferredDeviceId = legacyTrusted.macDeviceId,
            savedRelaySession = null,
        )

        assertEquals(legacyTrusted.macDeviceId, selected?.macDeviceId)
    }

    @Test
    fun socketClose_clearsOnlyLiveSessionForPermanentCloseWhenTrustRemains() {
        val update = connectionUpdateForSocketClose(
            code = 4003,
            hasTrustedHost = true,
            pendingTerminalUpdate = null,
        )

        assertEquals(ConnectionStatus.DISCONNECTED, update?.status)
        assertEquals(
            "The previous live session was replaced. Trusted host details were kept, so you can reconnect without rescanning.",
            update?.detail,
        )
        assertEquals(true, shouldClearSavedRelaySessionForSocketClose(4003))
    }
}
