package io.androdex.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentJsonHelpersTest {
    @Test
    fun decodeThreadSummary_readsSubagentMetadata() {
        val summary = decodeThreadSummarySpec(
            mapOf(
                "id" to "thread-child",
                "title" to "Scout",
                "agentNickname" to "Scout",
                "agentRole" to "explorer",
                "parentThreadId" to "thread-parent",
            )
        )

        assertEquals("thread-parent", summary?.parentThreadId)
        assertEquals("Scout", summary?.agentNickname)
        assertEquals("explorer", summary?.agentRole)
    }

    @Test
    fun decodeSubagentActionSpec_decodesNestedStateAndIdentity() {
        val action = decodeSubagentActionSpec(
            mapOf(
                "type" to "collabToolCall",
                "tool" to "spawnAgent",
                "status" to "in_progress",
                "prompt" to "Inspect the failing tests",
                "model" to "gpt-5.4-mini",
                "receiverThreadId" to "thread-child",
                "newAgentNickname" to "Scout",
                "receiverAgentRole" to "explorer",
                "statuses" to mapOf(
                    "thread-child" to mapOf(
                        "status" to "in_progress",
                        "message" to "Inspecting Android tests",
                    )
                ),
            )
        )

        assertEquals("Spawning 1 agent", action?.summaryText)
        assertEquals("thread-child", action?.receiverThreadIds?.single())
        assertEquals("Scout", action?.agentRows?.single()?.nickname)
        assertEquals("explorer", action?.agentRows?.single()?.role)
        assertEquals("Inspecting Android tests", action?.agentStates?.get("thread-child")?.message)
    }

    @Test
    fun decodeSubagentActionItem_supportsSingularReceiverFields() {
        val action = decodeSubagentActionSpec(
            mapOf(
                "type" to "collabAgentInteraction",
                "status" to "completed",
                "receiverThreadId" to "thread-child",
                "newAgentId" to "agent-1",
                "newAgentNickname" to "Scout",
                "receiverAgentRole" to "explorer",
            )
        )

        assertNotNull(action)
        assertTrue(action?.receiverAgents?.isNotEmpty() == true)
        assertEquals("Scout", action?.receiverAgents?.single()?.nickname)
    }
}
