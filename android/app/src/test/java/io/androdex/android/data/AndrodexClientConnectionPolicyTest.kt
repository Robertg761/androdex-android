package io.androdex.android.data

import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
