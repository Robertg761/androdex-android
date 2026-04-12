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

    private fun sampleSnapshot(): JSONObject {
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
        )
    }
}
