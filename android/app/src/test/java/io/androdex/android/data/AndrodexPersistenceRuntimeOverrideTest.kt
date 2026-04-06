package io.androdex.android.data

import io.androdex.android.model.ThreadRuntimeOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexPersistenceRuntimeOverrideTest {
    @Test
    fun encodeDecodeThreadRuntimeOverrides_roundTripsNormalizedValues() {
        val encoded = encodeThreadRuntimeOverridesSpec(
            mapOf(
                "thread-1" to ThreadRuntimeOverride(
                    reasoningEffort = "high",
                    serviceTierRawValue = "fast",
                    overridesReasoning = true,
                    overridesServiceTier = true,
                )
            )
        )

        val decoded = decodeThreadRuntimeOverridesSpec(encoded)

        assertEquals(
            mapOf(
                "thread-1" to ThreadRuntimeOverride(
                    reasoningEffort = "high",
                    serviceTierRawValue = "fast",
                    overridesReasoning = true,
                    overridesServiceTier = true,
                )
            ),
            decoded,
        )
    }

    @Test
    fun encodeThreadRuntimeOverrides_dropsEmptyEntries() {
        val encoded = encodeThreadRuntimeOverridesSpec(
            mapOf(
                "thread-1" to ThreadRuntimeOverride(
                    reasoningEffort = null,
                    serviceTierRawValue = null,
                    overridesReasoning = false,
                    overridesServiceTier = false,
                )
            )
        )

        assertNull(encoded)
    }

    @Test
    fun decodeThreadRuntimeOverrides_ignoresMalformedPayload() {
        val decoded = decodeThreadRuntimeOverridesSpec("{not-json")

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun encodeDecodeThreadRuntimeOverrideBundle_roundTripsScopedBuckets() {
        val encoded = encodeThreadRuntimeOverrideBundleSpec(
            ThreadRuntimeOverrideBundleSpec(
                legacyOverrides = mapOf(
                    "thread-legacy" to ThreadRuntimeOverride(
                        reasoningEffort = "medium",
                        serviceTierRawValue = null,
                        overridesReasoning = true,
                        overridesServiceTier = false,
                    )
                ),
                scopedOverridesByScopeKey = mapOf(
                    "host-1::codex-native" to mapOf(
                        "thread-scoped" to ThreadRuntimeOverride(
                            reasoningEffort = "high",
                            serviceTierRawValue = "fast",
                            overridesReasoning = true,
                            overridesServiceTier = true,
                        )
                    )
                ),
            )
        )

        val decoded = decodeThreadRuntimeOverrideBundleSpec(encoded)

        assertEquals(setOf("thread-legacy"), decoded.legacyOverrides.keys)
        assertEquals(
            setOf("thread-scoped"),
            decoded.scopedOverridesByScopeKey["host-1::codex-native"]?.keys,
        )
    }

    @Test
    fun decodeThreadRuntimeOverrideBundle_preservesLegacyPayloadCompatibility() {
        val encodedLegacy = encodeThreadRuntimeOverridesSpec(
            mapOf(
                "thread-1" to ThreadRuntimeOverride(
                    reasoningEffort = "high",
                    serviceTierRawValue = "fast",
                    overridesReasoning = true,
                    overridesServiceTier = true,
                )
            )
        )

        val decoded = decodeThreadRuntimeOverrideBundleSpec(encodedLegacy)

        assertEquals(setOf("thread-1"), decoded.legacyOverrides.keys)
        assertTrue(decoded.scopedOverridesByScopeKey.isEmpty())
    }
}
