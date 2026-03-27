package io.androdex.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProtocolJsonHelpersTest {
    @Test
    fun decodeHostAccountSnapshot_readsRateLimitBuckets() {
        val snapshot = decodeHostAccountSnapshot(
            JSONObject(
                """
                {
                  "status": "authenticated",
                  "email": "host@example.com",
                  "rateLimits": [
                    {
                      "name": "gpt-5.4",
                      "remaining": 42,
                      "limit": 100,
                      "used": 58,
                      "resetsAt": "2026-03-27T12:30:00Z"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertNotNull(snapshot)
        assertEquals("host@example.com", snapshot?.email)
        assertEquals(1, snapshot?.rateLimits?.size)
        assertEquals("gpt-5.4", snapshot?.rateLimits?.single()?.name)
        assertEquals(42, snapshot?.rateLimits?.single()?.remaining)
        assertEquals(100, snapshot?.rateLimits?.single()?.limit)
        assertEquals(58, snapshot?.rateLimits?.single()?.used)
        assertEquals(1_774_614_600_000L, snapshot?.rateLimits?.single()?.resetsAtEpochMs)
    }

    @Test
    fun decodeThreadTokenUsage_supportsSnakeCaseKeys() {
        val usage = decodeThreadTokenUsage(
            JSONObject(
                """
                {
                  "usage": {
                    "tokens_used": 1200,
                    "token_limit": 32000
                  }
                }
                """.trimIndent()
            )
        )

        assertNotNull(usage)
        assertEquals(1200, usage?.tokensUsed)
        assertEquals(32000, usage?.tokenLimit)
        assertEquals(30800, usage?.remainingTokens)
    }
}
