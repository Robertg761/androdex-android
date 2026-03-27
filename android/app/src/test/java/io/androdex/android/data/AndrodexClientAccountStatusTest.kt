package io.androdex.android.data

import io.androdex.android.model.HostAccountSnapshot
import io.androdex.android.model.HostAccountSnapshotOrigin
import io.androdex.android.model.HostAccountStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexClientAccountStatusTest {
    @Test
    fun applyHostAccountUpdatePayload_preservesExistingAuthStateForRateLimitOnlyUpdates() {
        val currentSnapshot = HostAccountSnapshot(
            status = HostAccountStatus.AUTHENTICATED,
            email = "host@example.com",
            planType = "pro",
            origin = HostAccountSnapshotOrigin.BRIDGE_BOOTSTRAP,
        )

        val updatedSnapshot = applyHostAccountUpdatePayload(
            currentSnapshot = currentSnapshot,
            payload = JSONObject(
                """
                {
                  "rateLimits": [
                    {
                      "name": "gpt-5.4",
                      "remaining": 42,
                      "limit": 100
                    }
                  ]
                }
                """.trimIndent()
            ),
            origin = HostAccountSnapshotOrigin.NATIVE_LIVE,
        )

        assertNotNull(updatedSnapshot)
        assertEquals(HostAccountStatus.AUTHENTICATED, updatedSnapshot?.status)
        assertEquals("host@example.com", updatedSnapshot?.email)
        assertEquals("pro", updatedSnapshot?.planType)
        assertEquals(1, updatedSnapshot?.rateLimits?.size)
        assertEquals(HostAccountSnapshotOrigin.NATIVE_LIVE, updatedSnapshot?.origin)
    }

    @Test
    fun resolveLiveHostAccountSnapshot_keepsExistingDetailsWhenBridgeFallbackIsSparse() {
        val currentSnapshot = HostAccountSnapshot(
            status = HostAccountStatus.AUTHENTICATED,
            email = "host@example.com",
            planType = "pro",
            origin = HostAccountSnapshotOrigin.NATIVE_LIVE,
        )
        val bridgeFallback = HostAccountSnapshot(
            status = HostAccountStatus.AUTHENTICATED,
            bridgeVersion = "1.2.3",
        )

        val resolvedSnapshot = resolveLiveHostAccountSnapshot(
            currentSnapshot = currentSnapshot,
            bridgeSnapshot = bridgeFallback,
        )

        assertNotNull(resolvedSnapshot)
        assertEquals("host@example.com", resolvedSnapshot?.email)
        assertEquals("pro", resolvedSnapshot?.planType)
        assertEquals("1.2.3", resolvedSnapshot?.bridgeVersion)
        assertEquals(HostAccountSnapshotOrigin.BRIDGE_FALLBACK, resolvedSnapshot?.origin)
    }

    @Test
    fun resolveInitialHostAccountSnapshot_returnsUnavailableBootstrapWhenNoSnapshotExists() {
        val resolvedSnapshot = resolveInitialHostAccountSnapshot(
            currentSnapshot = null,
            bridgeSnapshot = null,
        )

        assertEquals(HostAccountStatus.UNAVAILABLE, resolvedSnapshot.status)
        assertEquals(HostAccountSnapshotOrigin.BRIDGE_BOOTSTRAP, resolvedSnapshot.origin)
        assertTrue(resolvedSnapshot.rateLimits.isEmpty())
    }
}
