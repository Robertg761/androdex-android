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
            JSONObject()
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
    fun decodeHostRuntimeMetadata_readsBridgeManagedRuntimeIdentity() {
        val result = JSONObject(
            """
            {
              "runtimeTarget": "codex-native",
              "runtimeTargetDisplayName": "Codex Native",
              "backendProvider": "codex",
              "backendProviderDisplayName": "Codex",
              "runtimeAttachState": "ready",
              "runtimeAttachFailure": "Gap replay timed out",
              "runtimeProtocolVersion": "2026-04-01",
              "runtimeAuthMode": "bootstrap-token",
              "runtimeEndpointHost": "127.0.0.1",
              "runtimeSnapshotSequence": 7,
              "runtimeReplaySequence": 9,
              "runtimeSubscriptionState": "live",
              "runtimeDuplicateSuppressionCount": 2
            }
            """.trimIndent()
        )

        val decoded = decodeHostRuntimeMetadata(result)

        assertEquals("codex-native", decoded?.runtimeTarget)
        assertEquals("Codex Native", decoded?.runtimeTargetDisplayName)
        assertEquals("codex", decoded?.backendProvider)
        assertEquals("Codex", decoded?.backendProviderDisplayName)
        assertEquals("ready", decoded?.runtimeAttachState)
        assertEquals("Gap replay timed out", decoded?.runtimeAttachFailure)
        assertEquals("2026-04-01", decoded?.runtimeProtocolVersion)
        assertEquals("bootstrap-token", decoded?.runtimeAuthMode)
        assertEquals("127.0.0.1", decoded?.runtimeEndpointHost)
        assertEquals(7, decoded?.runtimeSnapshotSequence)
        assertEquals(9, decoded?.runtimeReplaySequence)
        assertEquals("live", decoded?.runtimeSubscriptionState)
        assertEquals(2, decoded?.runtimeDuplicateSuppressionCount)
    }

    @Test
    fun decodeHostRuntimeMetadata_readsSnakeCaseRuntimeSyncFields() {
        val result = JSONObject(
            """
            {
              "runtime_target": "t3-server",
              "runtime_target_display_name": "T3 Server",
              "runtime_attach_state": "ready",
              "runtime_snapshot_sequence": "11",
              "runtime_replay_sequence": 13,
              "runtime_subscription_state": "live",
              "runtime_duplicate_suppression_count": "5"
            }
            """.trimIndent()
        )

        val decoded = decodeHostRuntimeMetadata(result)

        assertEquals("t3-server", decoded?.runtimeTarget)
        assertEquals("T3 Server", decoded?.runtimeTargetDisplayName)
        assertEquals("ready", decoded?.runtimeAttachState)
        assertEquals(11, decoded?.runtimeSnapshotSequence)
        assertEquals(13, decoded?.runtimeReplaySequence)
        assertEquals("live", decoded?.runtimeSubscriptionState)
        assertEquals(5, decoded?.runtimeDuplicateSuppressionCount)
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
    fun shouldRetryInitializeWithoutCapabilities_ignoresGenericCapabilitiesErrors() {
        assertFalse(
            shouldRetryInitializeWithoutCapabilities("Temporary capabilities probe timeout")
        )
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
    fun shouldTreatAsUnsupportedCollaborationModeList_requiresMethodSpecificSignal() {
        assertFalse(
            shouldTreatAsUnsupportedCollaborationModeList(
                errorCode = -32000,
                errorMessage = "Host does not support this relay operation right now",
            )
        )
        assertTrue(
            shouldTreatAsUnsupportedCollaborationModeList(
                errorCode = -32000,
                errorMessage = "collaborationMode/list is not supported by this host",
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

    @Test
    fun resetMaintenanceActionCapabilityFlags_reEnablesDowngradedActionsOnReconnect() {
        val reset = resetMaintenanceActionCapabilityFlags(
            supportsThreadCompaction = false,
            supportsThreadRollback = false,
            supportsBackgroundTerminalCleanup = false,
        )

        assertEquals(Triple(true, true, true), reset)
    }
}
