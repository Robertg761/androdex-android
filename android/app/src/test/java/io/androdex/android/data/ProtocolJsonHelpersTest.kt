package io.androdex.android.data

import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ExecutionKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun decodeCommandExecutionContent_buildsStructuredExecutionPayload() {
        val content = decodeCommandExecutionContent(
            JSONObject(
                """
                {
                  "type": "commandExecution",
                  "status": "running",
                  "command": "npm test",
                  "summary": "Running unit tests",
                  "output": "PASS app.test.ts",
                  "cwd": "G:\\Projects\\Androdex",
                  "durationMs": 2100
                }
                """.trimIndent()
            )
        )

        assertEquals("running", content.status)
        assertEquals("npm test", content.command)
        assertEquals(ExecutionKind.COMMAND, content.execution.kind)
        assertEquals("npm test", content.execution.title)
        assertEquals("Running unit tests", content.execution.summary)
        assertEquals("PASS app.test.ts", content.execution.output)
        assertTrue(
            content.execution.details.any {
                it.label == "Working directory" && it.value == "G:\\Projects\\Androdex"
            }
        )
        assertTrue(
            content.execution.details.any {
                it.label == "Duration" && it.value == "2.1s"
            }
        )
    }

    @Test
    fun decodeExecutionStyleContent_recognizesReviewItems() {
        val content = decodeExecutionStyleContent(
            JSONObject(
                """
                {
                  "type": "reviewRequest",
                  "status": "completed",
                  "summary": "Reviewing current changes",
                  "reason": "manual",
                  "target": {
                    "type": "uncommittedChanges"
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals("completed", content.status)
        assertEquals(ExecutionKind.REVIEW, content.execution.kind)
        assertEquals("Review current changes", content.execution.title)
        assertEquals("Reviewing current changes", content.execution.summary)
        assertTrue(
            content.execution.details.any {
                it.label == "Reason" && it.value == "manual"
            }
        )
        assertTrue(
            content.execution.details.any {
                it.label == "Target" && it.value == "uncommittedChanges"
            }
        )
    }

    @Test
    fun extractProtocolItemCandidate_supportsDirectMsgEventExecutionPayloads() {
        val item = extractProtocolItemCandidate(
            JSONObject(
                """
                {
                  "msg": {
                    "event": {
                      "id": "review-1",
                      "type": "reviewRequest",
                      "status": "completed",
                      "summary": "Reviewing current changes"
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertNotNull(item)
        assertEquals("review-1", item?.getString("id"))
        assertEquals("reviewRequest", item?.getString("type"))
    }

    @Test
    fun resolveExecutionUpdateItemId_usesNestedDirectMsgEventId() {
        val params = JSONObject(
            """
            {
              "msg": {
                "event": {
                  "id": "review-1",
                  "type": "reviewRequest",
                  "status": "completed",
                  "summary": "Reviewing current changes"
                }
              }
            }
            """.trimIndent()
        )
        val item = extractProtocolItemCandidate(params)

        assertEquals("review-1", resolveExecutionUpdateItemId(params, item))
    }

    @Test
    fun decodedHostAccountStatusOrNull_returnsNullForObjectStatePayloads() {
        val decoded = JSONObject(
            """
            {
              "state": {
                "kind": "authenticated"
              }
            }
            """.trimIndent()
        ).decodedHostAccountStatusOrNull()

        assertEquals(null, decoded)
    }

    @Test
    fun decodeMessagesFromThreadRead_preservesExecutionStyleHistoryRows() {
        val messages = decodeMessagesFromThreadRead(
            threadId = "thread-1",
            threadObject = JSONObject(
                """
                {
                  "updatedAt": "2026-03-27T12:00:00Z",
                  "turns": [
                    {
                      "id": "turn-1",
                      "items": [
                        {
                          "id": "review-1",
                          "type": "reviewRequest",
                          "status": "completed",
                          "summary": "Reviewing current changes",
                          "target": {
                            "type": "uncommittedChanges"
                          }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertEquals(1, messages.size)
        val message = messages.single()
        assertEquals(ConversationKind.EXECUTION, message.kind)
        assertEquals("turn-1", message.turnId)
        assertEquals("review-1", message.itemId)
        assertEquals(ExecutionKind.REVIEW, message.execution?.kind)
        assertEquals("Review current changes", message.execution?.title)
    }
}
