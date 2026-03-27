package io.androdex.android.data

import io.androdex.android.model.CollaborationModeKind
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AndrodexClientCapabilityBootstrapTest {
    @Test
    fun performInitializeSessionRequest_retriesWithoutCapabilitiesForLegacyRuntimes() = runTest {
        val payloads = mutableListOf<JSONObject>()

        performInitializeSessionRequest { params ->
            payloads += JSONObject(params.toString())
            if (payloads.size == 1) {
                throw AndrodexClient.RpcException(
                    code = -32602,
                    message = "Unknown field capabilities.experimentalApi",
                    data = null,
                )
            }
        }

        assertEquals(2, payloads.size)
        assertTrue(payloads.first().has("capabilities"))
        assertFalse(payloads.last().has("capabilities"))
        assertEquals("androdex_android", payloads.last().getJSONObject("clientInfo").getString("name"))
    }

    @Test
    fun buildInitializePayload_omitsCapabilitiesWhenFallbackRequested() {
        val payload = buildInitializePayload(includeCapabilities = false)

        assertFalse(payload.has("capabilities"))
        assertEquals("Androdex Android", payload.getJSONObject("clientInfo").getString("title"))
    }

    @Test
    fun decodeCollaborationModes_readsKnownModesAndSkipsUnknownOnes() {
        val result = JSONObject(
            """
            {
              "items": [
                { "mode": "plan" },
                { "mode": "custom" },
                "plan"
              ]
            }
            """.trimIndent()
        )

        val decoded = decodeCollaborationModes(result)

        assertEquals(setOf(CollaborationModeKind.PLAN), decoded)
    }

    @Test
    fun collaborationModeKind_fromWireValue_handlesUnknownModes() {
        assertEquals(CollaborationModeKind.PLAN, CollaborationModeKind.fromWireValue("plan"))
        assertNull(CollaborationModeKind.fromWireValue("delegate"))
    }

    @Test
    fun resolveCollaborationModesAfterProbeFailure_preservesExistingModesForTransientErrors() {
        val resolved = resolveCollaborationModesAfterProbeFailure(
            currentModes = setOf(CollaborationModeKind.PLAN),
            failure = IOException("Timed out talking to relay"),
        )

        assertEquals(setOf(CollaborationModeKind.PLAN), resolved)
    }

    @Test
    fun resolveCollaborationModesAfterProbeFailure_clearsModesWhenMethodIsUnsupported() {
        val resolved = resolveCollaborationModesAfterProbeFailure(
            currentModes = setOf(CollaborationModeKind.PLAN),
            failure = AndrodexClient.RpcException(
                code = -32601,
                message = "Method not found",
                data = null,
            ),
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun shouldTreatAsUnsupportedCollaborationModeList_ignoresGenericRpcFailures() {
        assertFalse(
            shouldTreatAsUnsupportedCollaborationModeList(
                errorCode = -32000,
                errorMessage = "Temporary relay issue",
            )
        )
    }

    @Test
    fun shouldTreatAsUnsupportedThreadRollback_matchesMethodSpecificErrors() {
        assertTrue(
            shouldTreatAsUnsupportedThreadRollback(
                errorCode = -32000,
                errorMessage = "thread/rollback is not supported by this host",
            )
        )
    }

    @Test
    fun shouldTreatAsUnsupportedBackgroundTerminalCleanup_ignoresGenericRpcFailures() {
        assertFalse(
            shouldTreatAsUnsupportedBackgroundTerminalCleanup(
                errorCode = -32000,
                errorMessage = "Temporary relay issue",
            )
        )
    }

    @Test
    fun shouldTreatAsUnsupportedThreadCompaction_matchesMethodNotFound() {
        assertTrue(
            shouldTreatAsUnsupportedThreadCompaction(
                errorCode = -32601,
                errorMessage = "Method not found",
            )
        )
    }
}
