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
}
