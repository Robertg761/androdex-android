package io.androdex.android.data

import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AndrodexPersistenceThreadTimelineCacheTest {
    @Test
    fun encodeDecodePersistedThreadTimeline_roundTripsAndClearsStreamingFlags() {
        val encoded = encodePersistedThreadTimelineMessagesSpec(
            listOf(
                ConversationMessage(
                    id = "assistant-1",
                    threadId = "thread-1",
                    role = ConversationRole.ASSISTANT,
                    kind = ConversationKind.CHAT,
                    text = "Partial response",
                    createdAtEpochMs = 2_000L,
                    turnId = "turn-1",
                    itemId = "assistant-item",
                    isStreaming = true,
                )
            )
        )

        val decoded = decodePersistedThreadTimelineMessagesSpec(encoded, fallbackThreadId = "thread-1")

        requireNotNull(decoded)
        assertEquals(1, decoded.size)
        assertEquals("Partial response", decoded.single().text)
        assertFalse(decoded.single().isStreaming)
    }

    @Test
    fun decodePersistedThreadTimeline_returnsNullForMalformedPayload() {
        val decoded = decodePersistedThreadTimelineMessagesSpec("{not-json", fallbackThreadId = "thread-1")

        assertNull(decoded)
    }

    @Test
    fun decodePersistedThreadTimeline_skipsMalformedItemsAndKeepsValidMessages() {
        val decoded = decodePersistedThreadTimelineMessagesSpec(
            """
            {
              "v": 1,
              "messages": [
                {
                  "id": "bad-message",
                  "threadId": "thread-1",
                  "role": "not-a-role",
                  "kind": "CHAT",
                  "text": "broken",
                  "createdAtEpochMs": 10
                },
                {
                  "id": "assistant-1",
                  "threadId": "thread-1",
                  "role": "ASSISTANT",
                  "kind": "CHAT",
                  "text": "Recovered",
                  "createdAtEpochMs": 20,
                  "isStreaming": true
                }
              ]
            }
            """.trimIndent(),
            fallbackThreadId = "thread-1",
        )

        requireNotNull(decoded)
        assertEquals(1, decoded.size)
        assertEquals("assistant-1", decoded.single().id)
        assertEquals("Recovered", decoded.single().text)
        assertFalse(decoded.single().isStreaming)
    }
}
