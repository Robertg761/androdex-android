package io.androdex.android.transport.macnative

import io.androdex.android.data.parseTimestamp
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ThreadCapabilities
import io.androdex.android.model.ThreadCapabilityFlag
import io.androdex.android.model.ThreadLoadResult
import io.androdex.android.model.ThreadRunSnapshot
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.ToolUserInputOption
import io.androdex.android.model.ToolUserInputQuestion
import io.androdex.android.model.ToolUserInputRequest
import io.androdex.android.model.TurnTerminalState
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class MacNativePendingState(
    val approvals: List<ApprovalRequest>,
    val toolInputsByThread: Map<String, List<ToolUserInputRequest>>,
)

internal fun mapMacNativeSnapshotToThreadSummaries(snapshot: JSONObject): List<ThreadSummary> {
    val projectsById = snapshot.projectsById()
    return snapshot.threadsArray()
        .mapNotNull { thread ->
            mapMacNativeThreadSummary(
                thread = thread,
                project = projectsById[thread.optString("projectId").trim()],
            )
        }
        .sortedByDescending { it.updatedAtEpochMs ?: 0L }
}

internal fun mapMacNativeSnapshotToThreadLoad(
    snapshot: JSONObject,
    threadId: String,
): ThreadLoadResult {
    val projectsById = snapshot.projectsById()
    val thread = snapshot.threadsArray().firstOrNull {
        it.optString("id").trim() == threadId
    }
    return ThreadLoadResult(
        thread = thread?.let {
            mapMacNativeThreadSummary(
                thread = it,
                project = projectsById[it.optString("projectId").trim()],
            )
        },
        messages = thread?.let(::mapMacNativeThreadMessages).orEmpty(),
        runSnapshot = thread?.let(::mapMacNativeThreadRunSnapshot) ?: emptyMacNativeThreadRunSnapshot(),
    )
}

internal fun deriveMacNativePendingState(snapshot: JSONObject): MacNativePendingState {
    val approvals = mutableListOf<ApprovalRequest>()
    val toolInputsByThread = linkedMapOf<String, MutableList<ToolUserInputRequest>>()
    snapshot.threadsArray().forEach { thread ->
        val threadId = thread.optString("id").trim().ifEmpty { return@forEach }
        val activities = thread.optJSONArray("activities") ?: JSONArray()
        val pendingApprovalsByRequestId = linkedMapOf<String, ApprovalRequest>()
        val pendingInputsByRequestId = linkedMapOf<String, ToolUserInputRequest>()
        for (index in 0 until activities.length()) {
            val activity = activities.optJSONObject(index) ?: continue
            val payload = activity.optJSONObject("payload") ?: continue
            val requestId = payload.optString("requestId").trim().ifEmpty { continue }
            when (activity.optString("kind").trim()) {
                "approval.requested" -> {
                    pendingApprovalsByRequestId[requestId] = ApprovalRequest(
                        idValue = requestId,
                        method = approvalMethodForRequestType(payload.optString("requestType")),
                        command = payload.optString("command").trim().ifEmpty { null },
                        reason = payload.optString("detail").trim().ifEmpty { activity.optString("summary").trim().ifEmpty { null } },
                        threadId = threadId,
                        turnId = activity.optString("turnId").trim().ifEmpty { null },
                    )
                }
                "approval.resolved" -> pendingApprovalsByRequestId.remove(requestId)
                "provider.approval.respond.failed" -> {
                    if (payload.optString("detail").contains("Stale pending approval request", ignoreCase = true)) {
                        pendingApprovalsByRequestId.remove(requestId)
                    }
                }
                "user-input.requested" -> {
                    val questions = payload.optJSONArray("questions")?.toToolUserInputQuestions().orEmpty()
                    if (questions.isNotEmpty()) {
                        pendingInputsByRequestId[requestId] = ToolUserInputRequest(
                            idValue = requestId,
                            method = "tool/request_user_input",
                            threadId = threadId,
                            turnId = activity.optString("turnId").trim().ifEmpty { null },
                            itemId = null,
                            title = activity.optString("summary").trim().ifEmpty { null },
                            message = payload.optString("detail").trim().ifEmpty { null },
                            questions = questions,
                            rawPayload = payload.toString(),
                        )
                    }
                }
                "user-input.resolved" -> pendingInputsByRequestId.remove(requestId)
                "provider.user-input.respond.failed" -> {
                    if (payload.optString("detail").contains("Stale pending user-input request", ignoreCase = true)) {
                        pendingInputsByRequestId.remove(requestId)
                    }
                }
            }
        }
        approvals += pendingApprovalsByRequestId.values
        if (pendingInputsByRequestId.isNotEmpty()) {
            toolInputsByThread[threadId] = pendingInputsByRequestId.values.toMutableList()
        }
    }
    return MacNativePendingState(
        approvals = approvals.sortedBy { it.idValue.toString() },
        toolInputsByThread = toolInputsByThread,
    )
}

internal fun mapMacNativeThreadRunSnapshot(thread: JSONObject): ThreadRunSnapshot {
    val latestTurn = thread.optJSONObject("latestTurn")
    val latestTurnId = latestTurn?.optString("turnId")?.trim()?.ifEmpty { null }
    val latestTurnState = latestTurn?.optString("state")?.trim()
    val session = thread.optJSONObject("session")
    val activeTurnId = session?.optString("activeTurnId")?.trim()?.ifEmpty { null }
    return ThreadRunSnapshot(
        interruptibleTurnId = activeTurnId ?: latestTurnId?.takeIf { latestTurnState == "running" },
        hasInterruptibleTurnWithoutId = false,
        latestTurnId = latestTurnId,
        latestTurnTerminalState = when (latestTurnState) {
            "completed" -> TurnTerminalState.COMPLETED
            "interrupted", "stopped" -> TurnTerminalState.STOPPED
            "error" -> TurnTerminalState.FAILED
            else -> null
        },
        shouldAssumeRunningFromLatestTurn = latestTurnState == "running" && latestTurnId != null,
    )
}

internal fun emptyMacNativeThreadRunSnapshot(): ThreadRunSnapshot = ThreadRunSnapshot(
    interruptibleTurnId = null,
    hasInterruptibleTurnWithoutId = false,
    latestTurnId = null,
    latestTurnTerminalState = null,
    shouldAssumeRunningFromLatestTurn = false,
)

private fun mapMacNativeThreadSummary(
    thread: JSONObject,
    project: JSONObject?,
): ThreadSummary? {
    val id = thread.optString("id").trim().ifEmpty { return null }
    val modelSelection = thread.optJSONObject("modelSelection")
    val provider = modelSelection?.optString("provider")?.trim()?.ifEmpty { null }
    val model = modelSelection?.optString("model")?.trim()?.ifEmpty { null }
    val latestMessage = thread.optJSONArray("messages")
        ?.let { messages ->
            (0 until messages.length())
                .asSequence()
                .mapNotNull(messages::optJSONObject)
                .lastOrNull()
        }
    val preview = latestMessage?.optString("text")?.trim()?.ifEmpty { null }
    val cwd = thread.optString("worktreePath").trim().ifEmpty {
        project?.optString("workspaceRoot")?.trim().orEmpty()
    }.ifEmpty { null }
    return ThreadSummary(
        id = id,
        title = thread.optString("title").trim().ifEmpty { "Conversation" },
        preview = preview,
        cwd = cwd,
        createdAtEpochMs = parseTimestamp(thread.opt("createdAt")),
        updatedAtEpochMs = parseTimestamp(thread.opt("updatedAt")),
        model = model,
        backendProvider = provider,
        threadCapabilities = ThreadCapabilities(
            backendProvider = provider,
            workspacePath = cwd,
            workspaceResolved = cwd != null,
            workspaceAvailable = cwd != null,
            read = ThreadCapabilityFlag(supported = true),
            liveUpdates = ThreadCapabilityFlag(supported = true),
            turnStart = ThreadCapabilityFlag(supported = true),
            turnInterrupt = ThreadCapabilityFlag(supported = true),
            approvalResponses = ThreadCapabilityFlag(supported = true),
            userInputResponses = ThreadCapabilityFlag(supported = true),
            toolInputResponses = ThreadCapabilityFlag(supported = true),
            backgroundTerminalCleanup = ThreadCapabilityFlag(
                supported = false,
                reason = "Background terminal cleanup is not available on the Mac-native path yet.",
            ),
            checkpointRollback = ThreadCapabilityFlag(supported = true),
        ),
    )
}

private fun mapMacNativeThreadMessages(thread: JSONObject): List<ConversationMessage> {
    val threadId = thread.optString("id").trim()
    val messages = thread.optJSONArray("messages") ?: return emptyList()
    return buildList {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = when (message.optString("role").trim()) {
                "assistant" -> ConversationRole.ASSISTANT
                "system" -> ConversationRole.SYSTEM
                else -> ConversationRole.USER
            }
            add(
                ConversationMessage(
                    id = message.optString("id").trim().ifEmpty { UUID.randomUUID().toString() },
                    threadId = threadId,
                    role = role,
                    kind = when (role) {
                        ConversationRole.SYSTEM -> ConversationKind.THINKING
                        else -> ConversationKind.CHAT
                    },
                    text = message.optString("text"),
                    createdAtEpochMs = parseTimestamp(message.opt("createdAt")) ?: 0L,
                    turnId = message.optString("turnId").trim().ifEmpty { null },
                    itemId = message.optString("id").trim().ifEmpty { null },
                    isStreaming = message.optBoolean("streaming"),
                )
            )
        }
    }
}

private fun JSONObject.projectsById(): Map<String, JSONObject> = (optJSONArray("projects") ?: JSONArray())
    .let { projects ->
        buildMap {
            for (index in 0 until projects.length()) {
                val project = projects.optJSONObject(index) ?: continue
                val id = project.optString("id").trim().ifEmpty { continue }
                put(id, project)
            }
        }
    }

private fun JSONObject.threadsArray(): List<JSONObject> {
    val threads = optJSONArray("threads") ?: return emptyList()
    return buildList {
        for (index in 0 until threads.length()) {
            val thread = threads.optJSONObject(index) ?: continue
            add(thread)
        }
    }
}

private fun JSONArray.toToolUserInputQuestions(): List<ToolUserInputQuestion> {
    return buildList {
        for (index in 0 until length()) {
            val question = optJSONObject(index) ?: continue
            val id = question.optString("id").trim().ifEmpty { continue }
            val prompt = question.optString("question").trim().ifEmpty { continue }
            add(
                ToolUserInputQuestion(
                    id = id,
                    header = question.optString("header").trim().ifEmpty { null },
                    question = prompt,
                    options = question.optJSONArray("options")?.toToolUserInputOptions().orEmpty(),
                )
            )
        }
    }
}

private fun JSONArray.toToolUserInputOptions(): List<ToolUserInputOption> {
    return buildList {
        for (index in 0 until length()) {
            val option = optJSONObject(index) ?: continue
            val label = option.optString("label").trim().ifEmpty { continue }
            add(
                ToolUserInputOption(
                    label = label,
                    description = option.optString("description").trim().ifEmpty { null },
                )
            )
        }
    }
}

private fun approvalMethodForRequestType(requestType: String?): String {
    return when (requestType?.trim()) {
        "command_execution_approval", "exec_command_approval" -> "item/commandExecution/requestApproval"
        "file_read_approval" -> "item/fileRead/requestApproval"
        "file_change_approval", "apply_patch_approval" -> "item/fileChange/requestApproval"
        else -> "item/requestApproval"
    }
}
