package io.androdex.android.data

import io.androdex.android.model.CollaborationModeKind
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
