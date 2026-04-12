package io.androdex.android.transport.macnative

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacNativeSnapshotMapperTest {
    @Test
    fun snapshotMapping_decodesThreadSummaryAndLoadResult() {
        val snapshot = sampleSnapshot()

        val threads = mapMacNativeSnapshotToThreadSummaries(snapshot)
        val loadResult = mapMacNativeSnapshotToThreadLoad(snapshot, "thread-1")

        assertEquals(1, threads.size)
        assertEquals("Conversation", threads.first().title)
        assertEquals("/workspace/demo", threads.first().cwd)
        assertTrue(threads.first().threadCapabilities?.backgroundTerminalCleanup?.supported == true)
        assertTrue(threads.first().threadCapabilities?.turnInterrupt?.supported == true)
        assertTrue(threads.first().threadCapabilities?.checkpointRollback?.supported == false)
        assertEquals(
            "This thread has no checkpoints available to roll back yet.",
            threads.first().threadCapabilities?.checkpointRollback?.reason,
        )
        assertEquals(2, loadResult.messages.size)
        assertEquals("turn-1", loadResult.runSnapshot.interruptibleTurnId)
        assertTrue(loadResult.messages.last().isStreaming)
    }

    @Test
    fun pendingState_usesActivitiesForApprovalsAndUserInputs() {
        val pending = deriveMacNativePendingState(sampleSnapshot())

        assertEquals(1, pending.approvals.size)
        assertEquals("approval-1", pending.approvals.first().idValue.toString())
        assertEquals(1, pending.toolInputsByThread["thread-1"]?.size)
        assertEquals(
            "question-1",
            pending.toolInputsByThread["thread-1"]?.first()?.questions?.first()?.id,
        )
    }

    @Test
    fun snapshotMapping_blocksMutationsForArchivedStoppedThreads() {
        val snapshot = sampleSnapshot(
            threadOverrides = """
                {
                  "archivedAt": "2026-04-12T10:00:02Z",
                  "latestTurn": {
                    "turnId": "turn-2",
                    "state": "completed"
                  },
                  "session": {
                    "threadId": "thread-1",
                    "status": "stopped",
                    "activeTurnId": null
                  },
                  "checkpoints": [
                    {
                      "turnId": "turn-1",
                      "checkpointTurnCount": 1,
                      "checkpointRef": "checkpoint-1",
                      "status": "ready",
                      "files": [],
                      "assistantMessageId": "msg-2",
                      "completedAt": "2026-04-12T10:00:01Z"
                    }
                  ]
                }
            """.trimIndent()
        )

        val thread = mapMacNativeSnapshotToThreadSummaries(snapshot).first()

        assertTrue(thread.threadCapabilities?.turnStart?.supported == false)
        assertEquals(
            "This thread is archived on the Mac server.",
            thread.threadCapabilities?.turnStart?.reason,
        )
        assertTrue(thread.threadCapabilities?.turnInterrupt?.supported == false)
        assertTrue(thread.threadCapabilities?.backgroundTerminalCleanup?.supported == false)
        assertTrue(thread.threadCapabilities?.checkpointRollback?.supported == false)
    }

    private fun sampleSnapshot(
        threadOverrides: String? = null,
    ): JSONObject {
        return JSONObject(
            """
                {
                  "snapshotSequence": 5,
                  "projects": [
                    {
                      "id": "project-1",
                      "title": "Demo",
                      "workspaceRoot": "/workspace/demo"
                    }
                  ],
                  "threads": [
                    {
                      "id": "thread-1",
                      "projectId": "project-1",
                      "title": "Conversation",
                      "modelSelection": {
                        "provider": "codex",
                        "model": "gpt-5.4"
                      },
                      "latestTurn": {
                        "turnId": "turn-1",
                        "state": "running"
                      },
                      "session": {
                        "threadId": "thread-1",
                        "status": "running",
                        "activeTurnId": "turn-1"
                      },
                      "archivedAt": null,
                      "deletedAt": null,
                      "checkpoints": [],
                      "messages": [
                        {
                          "id": "msg-1",
                          "role": "user",
                          "text": "Hello",
                          "turnId": "turn-1",
                          "streaming": false,
                          "createdAt": "2026-04-12T10:00:00Z"
                        },
                        {
                          "id": "msg-2",
                          "role": "assistant",
                          "text": "Hi there",
                          "turnId": "turn-1",
                          "streaming": true,
                          "createdAt": "2026-04-12T10:00:01Z"
                        }
                      ],
                      "activities": [
                        {
                          "id": "activity-approval",
                          "kind": "approval.requested",
                          "summary": "Command approval requested",
                          "turnId": "turn-1",
                          "payload": {
                            "requestId": "approval-1",
                            "requestType": "command_execution_approval",
                            "detail": "Run command?"
                          }
                        },
                        {
                          "id": "activity-user-input",
                          "kind": "user-input.requested",
                          "summary": "Need user input",
                          "turnId": "turn-1",
                          "payload": {
                            "requestId": "user-input-1",
                            "questions": [
                              {
                                "id": "question-1",
                                "header": "Choice",
                                "question": "Continue?",
                                "options": [
                                  {
                                    "label": "Yes",
                                    "description": "Proceed"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        ).also { snapshot ->
            val overrides = threadOverrides?.let(::JSONObject) ?: return@also
            val thread = snapshot
                .optJSONArray("threads")
                ?.optJSONObject(0)
                ?: return@also
            val keys = overrides.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                thread.put(key, overrides.get(key))
            }
        }
    }
}
