package io.androdex.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun decodeThreadSummary_readsBridgeCapabilityMetadata() {
        val summary = decodeThreadSummarySpec(
            mapOf(
                "id" to "thread-t3",
                "title" to "T3 thread",
                "backendProvider" to "claudeAgent",
                "threadCapabilities" to mapOf(
                    "readOnly" to true,
                    "backendProvider" to "claudeAgent",
                    "companionSupported" to false,
                    "companionSupportState" to "unsupported_provider",
                    "companionSupportReason" to "Unsupported provider for Androdex companion support.",
                    "workspacePath" to "/tmp/project",
                    "workspacePathSource" to "project_workspace_root",
                    "workspaceResolved" to true,
                    "workspaceAvailable" to true,
                    "workspaceFallbackUsed" to true,
                    "recordedWorktreePath" to "/tmp/project-worktree",
                    "recordedWorktreeAvailable" to false,
                    "projectWorkspaceRoot" to "/tmp/project",
                    "projectWorkspaceRootAvailable" to true,
                    "liveUpdates" to mapOf(
                        "supported" to false,
                        "reason" to "Only Codex-backed threads can attach live updates.",
                    ),
                    "turnStart" to mapOf(
                        "supported" to false,
                        "reason" to "Read-only milestone.",
                    ),
                    "toolInputResponses" to mapOf(
                        "supported" to false,
                        "reason" to "Read-only milestone.",
                    ),
                ),
            )
        )

        assertEquals("claudeAgent", summary?.backendProvider)
        assertEquals("unsupported_provider", summary?.threadCapabilities?.companionSupportState)
        assertEquals("project_workspace_root", summary?.threadCapabilities?.workspacePathSource)
        assertEquals(true, summary?.threadCapabilities?.workspaceFallbackUsed)
        assertEquals("/tmp/project-worktree", summary?.threadCapabilities?.recordedWorktreePath)
        assertEquals(false, summary?.threadCapabilities?.recordedWorktreeAvailable)
        assertEquals("/tmp/project", summary?.threadCapabilities?.projectWorkspaceRoot)
        assertEquals(true, summary?.threadCapabilities?.projectWorkspaceRootAvailable)
        assertEquals(false, summary?.threadCapabilities?.liveUpdates?.supported)
        assertEquals(
            "Only Codex-backed threads can attach live updates.",
            summary?.threadCapabilities?.liveUpdates?.reason,
        )
        assertEquals(false, summary?.threadCapabilities?.turnStart?.supported)
        assertEquals(false, summary?.threadCapabilities?.toolInputResponses?.supported)
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

    @Test
    fun decodeSubagentActionItem_readsNestedCollaborationPayload() {
        val action = decodeSubagentActionItem(
            mapOf(
                "type" to "message",
                "collaboration" to mapOf(
                    "receiverThreadId" to "thread-child",
                    "newAgentNickname" to "Scout",
                    "receiverAgentRole" to "explorer",
                    "prompt" to "Inspect the failing tests",
                ),
            )
        )

        assertNotNull(action)
        assertEquals("thread-child", action?.receiverThreadIds?.single())
        assertEquals("Scout", action?.receiverAgents?.single()?.nickname)
    }

    @Test
    fun decodeSubagentActionItem_ignoresGenericPayloadsWithoutCollaborationSignals() {
        val action = decodeSubagentActionItem(
            mapOf(
                "type" to "message",
                "threadId" to "thread-child",
                "message" to "Normal assistant update",
            )
        )

        assertNull(action)
    }

    @Test
    fun decodeMessagesFromThreadRead_rendersStructuredFileAndSkillMentions() {
        val messages = decodeMessagesFromThreadRead(
            threadId = "thread-1",
            threadObject = JSONObject(
                """
                {
                  "turns": [
                    {
                      "id": "turn-1",
                      "items": [
                        {
                          "id": "item-1",
                          "type": "user_message",
                          "content": [
                            { "type": "text", "text": "Inspect these inputs" },
                            { "type": "file", "path": "android/app/src/main/java/io/androdex/android/MainViewModel.kt" },
                            { "type": "skill", "name": "frontend-design" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            ),
        )

        assertEquals(1, messages.size)
        assertEquals(
            "Inspect these inputs\n@android/app/src/main/java/io/androdex/android/MainViewModel.kt\n\$frontend-design",
            messages.single().text,
        )
    }
}
