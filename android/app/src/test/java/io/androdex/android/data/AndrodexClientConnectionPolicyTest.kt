package io.androdex.android.data

import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
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
            fallbackPolicy = TrustedResolveFallbackPolicy.ALLOW_SAVED_SESSION,
        )

        assertTrue(result is TrustedSessionResolveResult.LiveSessionUnresolved)
        assertEquals("The trusted host is offline right now.", (result as TrustedSessionResolveResult.LiveSessionUnresolved).detail)
    }

    @Test
    fun trustedSessionResolveFailure_marksInvalidSignatureAsRepairRequired() {
        val result = mapTrustedSessionResolveFailure(
            responseCode = 403,
            responseBody = JSONObject()
                .put("code", "invalid_signature")
                .put("message", "The trusted-session resolve signature is invalid."),
            fallbackPolicy = TrustedResolveFallbackPolicy.ALLOW_SAVED_SESSION,
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
            fallbackPolicy = TrustedResolveFallbackPolicy.ALLOW_SAVED_SESSION,
        )

        assertTrue(result is TrustedSessionResolveResult.FallbackToSavedSession)
        assertEquals(
            "Trusted host is known, but the relay could not resolve a fresh live session.",
            (result as TrustedSessionResolveResult.FallbackToSavedSession).detail,
        )
    }

    @Test
    fun legacyRelaySessionIdentifier_detectsUuidSessionIds() {
        assertTrue(looksLikeLegacyRelaySessionIdentifier("51b5f04f-b19d-4fa7-b0ea-df5b31adb240"))
    }

    @Test
    fun legacyRelaySessionIdentifier_ignoresRealDeviceIds() {
        assertEquals(false, looksLikeLegacyRelaySessionIdentifier("mac-5"))
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
