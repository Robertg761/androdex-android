package io.androdex.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PairingPayload(
    val version: Int,
    val relay: String,
    val hostId: String?,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val bootstrapToken: String?,
    val expiresAt: Long,
    val sessionId: String? = null,
) {
    val routingId: String
        get() = hostId?.takeIf { it.isNotBlank() }
            ?: sessionId?.takeIf { it.isNotBlank() }
            ?: ""

    fun toSavedPairing(): PairingPayload = PairingPayload(
        version = if (version >= 3) version else 2,
        relay = relay,
        hostId = hostId ?: sessionId,
        macDeviceId = macDeviceId,
        macIdentityPublicKey = macIdentityPublicKey,
        bootstrapToken = null,
        expiresAt = 0L,
        sessionId = null,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("relay", relay)
        .put("hostId", hostId)
        .put("sessionId", sessionId)
        .put("macDeviceId", macDeviceId)
        .put("macIdentityPublicKey", macIdentityPublicKey)
        .put("bootstrapToken", bootstrapToken)
        .put("expiresAt", expiresAt)

    companion object {
        fun fromJson(json: JSONObject): PairingPayload? {
            val relay = json.optString("relay").trim()
            val hostId = json.optString("hostId").trim().ifEmpty { null }
            val sessionId = json.optString("sessionId").trim().ifEmpty { null }
            val macDeviceId = json.optString("macDeviceId").trim()
            val macIdentityPublicKey = json.optString("macIdentityPublicKey").trim()
            if (relay.isEmpty() || (hostId == null && sessionId == null) || macDeviceId.isEmpty() || macIdentityPublicKey.isEmpty()) {
                return null
            }

            return PairingPayload(
                version = json.optInt("v", 0),
                relay = relay,
                hostId = hostId,
                macDeviceId = macDeviceId,
                macIdentityPublicKey = macIdentityPublicKey,
                bootstrapToken = json.optString("bootstrapToken").trim().ifEmpty { null },
                expiresAt = json.optLong("expiresAt", 0L),
                sessionId = sessionId,
            )
        }
    }
}

data class PhoneIdentityState(
    val phoneDeviceId: String,
    val phoneIdentityPrivateKey: String,
    val phoneIdentityPublicKey: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("phoneDeviceId", phoneDeviceId)
        .put("phoneIdentityPrivateKey", phoneIdentityPrivateKey)
        .put("phoneIdentityPublicKey", phoneIdentityPublicKey)

    companion object {
        fun fromJson(json: JSONObject): PhoneIdentityState? {
            val deviceId = json.optString("phoneDeviceId").trim()
            val privateKey = json.optString("phoneIdentityPrivateKey").trim()
            val publicKey = json.optString("phoneIdentityPublicKey").trim()
            if (deviceId.isEmpty() || privateKey.isEmpty() || publicKey.isEmpty()) {
                return null
            }
            return PhoneIdentityState(deviceId, privateKey, publicKey)
        }
    }
}

data class TrustedMacRecord(
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val lastPairedAtEpochMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("macDeviceId", macDeviceId)
        .put("macIdentityPublicKey", macIdentityPublicKey)
        .put("lastPairedAtEpochMs", lastPairedAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): TrustedMacRecord? {
            val deviceId = json.optString("macDeviceId").trim()
            val publicKey = json.optString("macIdentityPublicKey").trim()
            if (deviceId.isEmpty() || publicKey.isEmpty()) {
                return null
            }
            return TrustedMacRecord(
                macDeviceId = deviceId,
                macIdentityPublicKey = publicKey,
                lastPairedAtEpochMs = json.optLong("lastPairedAtEpochMs", 0L),
            )
        }
    }
}

data class TrustedMacRegistry(
    val records: Map<String, TrustedMacRecord>,
) {
    fun toJson(): JSONObject {
        val recordObject = JSONObject()
        records.forEach { (key, value) ->
            recordObject.put(key, value.toJson())
        }
        return JSONObject().put("records", recordObject)
    }

    companion object {
        val empty = TrustedMacRegistry(emptyMap())

        fun fromJson(json: JSONObject): TrustedMacRegistry {
            val recordsJson = json.optJSONObject("records") ?: return empty
            val records = buildMap<String, TrustedMacRecord> {
                val keys = recordsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val record = recordsJson.optJSONObject(key)?.let(TrustedMacRecord::fromJson) ?: continue
                    put(key, record)
                }
            }
            return TrustedMacRegistry(records)
        }
    }
}

data class TrustedPairSnapshot(
    val deviceId: String,
    val relayUrl: String?,
    val fingerprint: String?,
    val lastPairedAtEpochMs: Long?,
)

data class ThreadSummary(
    val id: String,
    val title: String,
    val preview: String?,
    val cwd: String?,
    val createdAtEpochMs: Long?,
    val updatedAtEpochMs: Long?,
    val forkedFromThreadId: String? = null,
    val parentThreadId: String? = null,
    val agentId: String? = null,
    val agentNickname: String? = null,
    val agentRole: String? = null,
    val model: String? = null,
) {
    val projectName: String
        get() = cwd
            ?.trim()
            ?.trimEnd('/', '\\')
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: "No Project"

    val preferredSubagentLabel: String?
        get() {
            val nickname = sanitizedSubagentIdentity(agentNickname)
            val role = sanitizedSubagentIdentity(agentRole)
            return when {
                nickname != null && role != null -> "$nickname [$role]"
                nickname != null -> nickname
                role != null -> role.replaceFirstChar(Char::uppercaseChar)
                else -> null
            }
        }
}

data class WorkspacePathSummary(
    val path: String,
    val name: String,
    val isActive: Boolean,
)

data class WorkspaceDirectoryEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isActive: Boolean,
    val source: String,
)

data class WorkspaceRecentState(
    val activeCwd: String?,
    val recentWorkspaces: List<WorkspacePathSummary>,
)

data class WorkspaceBrowseResult(
    val requestedPath: String?,
    val parentPath: String?,
    val entries: List<WorkspaceDirectoryEntry>,
    val rootEntries: List<WorkspaceDirectoryEntry>,
    val activeCwd: String?,
    val recentWorkspaces: List<WorkspacePathSummary>,
)

data class WorkspaceActivationStatus(
    val hostId: String?,
    val macDeviceId: String?,
    val relayUrl: String?,
    val relayStatus: String?,
    val currentCwd: String?,
    val workspaceActive: Boolean,
    val hasTrustedPhone: Boolean,
)

data class FuzzyFileMatch(
    val root: String? = null,
    val path: String,
    val fileName: String,
    val score: Double? = null,
    val indices: List<Int> = emptyList(),
)

data class SkillMetadata(
    val name: String,
    val description: String? = null,
    val path: String? = null,
    val scope: String? = null,
    val enabled: Boolean = true,
) {
    val normalizedName: String
        get() = name.trim().lowercase()
}

data class TurnSkillMention(
    val id: String,
    val name: String? = null,
    val path: String? = null,
)

data class ComposerMentionedFile(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val path: String,
)

data class ComposerMentionedSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String? = null,
    val description: String? = null,
)

data class ReasoningEffortOption(
    val reasoningEffort: String,
    val description: String,
)

data class ModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val supportedReasoningEfforts: List<ReasoningEffortOption>,
    val defaultReasoningEffort: String?,
) {
    val stableIdentifier: String
        get() = id.ifBlank { model }
}

enum class AccessMode(
    val wireValue: String,
) {
    ON_REQUEST("on-request"),
    FULL_ACCESS("full-access");

    val displayName: String
        get() = when (this) {
            ON_REQUEST -> "Ask"
            FULL_ACCESS -> "Full"
        }

    val menuTitle: String
        get() = when (this) {
            ON_REQUEST -> "On-Request"
            FULL_ACCESS -> "Full Access"
        }

    val approvalPolicyCandidates: List<String>
        get() = when (this) {
            ON_REQUEST -> listOf("on-request", "onRequest")
            FULL_ACCESS -> listOf("never")
        }

    val sandboxLegacyValue: String
        get() = when (this) {
            ON_REQUEST -> "workspace-write"
            FULL_ACCESS -> "danger-full-access"
        }

    companion object {
        fun fromWireValue(value: String?): AccessMode? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

enum class ServiceTier(
    val wireValue: String,
) {
    FAST("fast");

    val displayName: String
        get() = when (this) {
            FAST -> "Fast"
        }

    val description: String
        get() = when (this) {
            FAST -> "Lower latency using the bridge/runtime fast tier."
        }

    companion object {
        fun fromWireValue(value: String?): ServiceTier? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

data class ThreadRuntimeOverride(
    val reasoningEffort: String? = null,
    val serviceTierRawValue: String? = null,
    val overridesReasoning: Boolean = false,
    val overridesServiceTier: Boolean = false,
) {
    val serviceTier: ServiceTier?
        get() = ServiceTier.fromWireValue(serviceTierRawValue)

    val isEmpty: Boolean
        get() = !overridesReasoning && !overridesServiceTier

    fun normalized(): ThreadRuntimeOverride? {
        val normalizedReasoning = reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedServiceTier = serviceTierRawValue?.trim()?.takeIf { it.isNotEmpty() }
        val normalized = copy(
            reasoningEffort = normalizedReasoning,
            serviceTierRawValue = normalizedServiceTier,
        )
        return normalized.takeUnless { it.isEmpty }
    }
}

enum class CollaborationModeKind(
    val wireValue: String,
) {
    PLAN("plan"),
}

enum class ConversationRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

enum class ConversationKind {
    CHAT,
    THINKING,
    FILE_CHANGE,
    COMMAND,
    SUBAGENT_ACTION,
    PLAN,
}

data class SubagentRef(
    val threadId: String,
    val agentId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val model: String? = null,
    val prompt: String? = null,
)

data class SubagentState(
    val threadId: String,
    val status: String,
    val message: String? = null,
)

data class SubagentThreadPresentation(
    val threadId: String,
    val agentId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val model: String? = null,
    val modelIsRequestedHint: Boolean = false,
    val prompt: String? = null,
    val fallbackStatus: String? = null,
    val fallbackMessage: String? = null,
) {
    val displayLabel: String
        get() {
            val trimmedNickname = sanitizedSubagentIdentity(nickname)
            val trimmedRole = sanitizedSubagentIdentity(role)
            return when {
                trimmedNickname != null && trimmedRole != null -> "$trimmedNickname [$trimmedRole]"
                trimmedNickname != null -> trimmedNickname
                trimmedRole != null -> trimmedRole.replaceFirstChar(Char::uppercaseChar)
                threadId.length > 14 -> "Agent ${threadId.takeLast(8)}"
                threadId.isBlank() -> "Agent"
                else -> threadId
            }
        }
}

data class SubagentAction(
    val tool: String,
    val status: String,
    val prompt: String? = null,
    val model: String? = null,
    val receiverThreadIds: List<String> = emptyList(),
    val receiverAgents: List<SubagentRef> = emptyList(),
    val agentStates: Map<String, SubagentState> = emptyMap(),
) {
    val agentRows: List<SubagentThreadPresentation>
        get() {
            val orderedThreadIds = buildList {
                receiverThreadIds.forEach { threadId ->
                    if (threadId !in this) {
                        add(threadId)
                    }
                }
                receiverAgents.forEach { agent ->
                    if (agent.threadId !in this) {
                        add(agent.threadId)
                    }
                }
                agentStates.keys.sorted().forEach { threadId ->
                    if (threadId !in this) {
                        add(threadId)
                    }
                }
            }

            return orderedThreadIds.map { threadId ->
                val matchingAgent = receiverAgents.firstOrNull { it.threadId == threadId }
                val matchingState = agentStates[threadId]
                SubagentThreadPresentation(
                    threadId = threadId,
                    agentId = matchingAgent?.agentId,
                    nickname = matchingAgent?.nickname,
                    role = matchingAgent?.role,
                    model = matchingAgent?.model ?: model,
                    modelIsRequestedHint = matchingAgent?.model == null && model != null,
                    prompt = matchingAgent?.prompt,
                    fallbackStatus = matchingState?.status,
                    fallbackMessage = matchingState?.message,
                )
            }
        }

    val normalizedTool: String
        get() = tool.trim().lowercase()
            .replace("_", "")
            .replace("-", "")

    val normalizedStatus: String
        get() = status.trim().lowercase()
            .replace("_", "")
            .replace("-", "")

    val summaryText: String
        get() {
            val count = maxOf(1, agentRows.size, receiverThreadIds.size, receiverAgents.size)
            val noun = if (count == 1) "agent" else "agents"
            return when (normalizedTool) {
                "spawnagent" -> "Spawning $count $noun"
                "wait", "waitagent" -> "Waiting on $count $noun"
                "closeagent" -> "Closing $count $noun"
                "resumeagent" -> "Resuming $count $noun"
                "sendinput" -> if (count == 1) "Updating agent" else "Updating agents"
                else -> if (count == 1) "Agent activity" else "Agent activity ($count)"
            }
        }
}

enum class TurnTerminalState {
    COMPLETED,
    FAILED,
    STOPPED,
}

data class ThreadRunSnapshot(
    val interruptibleTurnId: String?,
    val hasInterruptibleTurnWithoutId: Boolean,
    val latestTurnId: String?,
    val latestTurnTerminalState: TurnTerminalState?,
    val shouldAssumeRunningFromLatestTurn: Boolean,
)

data class ConversationMessage(
    val id: String,
    val threadId: String,
    val role: ConversationRole,
    val kind: ConversationKind,
    val text: String,
    val attachments: List<ImageAttachment> = emptyList(),
    val createdAtEpochMs: Long,
    val turnId: String? = null,
    val itemId: String? = null,
    val isStreaming: Boolean = false,
    val filePath: String? = null,
    val status: String? = null,
    val diffText: String? = null,
    val command: String? = null,
    val planExplanation: String? = null,
    val planSteps: List<PlanStep>? = null,
    val subagentAction: SubagentAction? = null,
)

data class QueuedTurnDraft(
    val id: String,
    val text: String,
    val attachments: List<ImageAttachment> = emptyList(),
    val createdAtEpochMs: Long,
    val collaborationMode: CollaborationModeKind? = null,
    val subagentsSelectionEnabled: Boolean = false,
    val mentionedFiles: List<ComposerMentionedFile> = emptyList(),
    val mentionedSkills: List<ComposerMentionedSkill> = emptyList(),
)

enum class QueuePauseState {
    ACTIVE,
    PAUSED,
}

data class ThreadQueuedDraftState(
    val drafts: List<QueuedTurnDraft> = emptyList(),
    val pauseState: QueuePauseState = QueuePauseState.ACTIVE,
    val pauseMessage: String? = null,
) {
    val isPaused: Boolean
        get() = pauseState == QueuePauseState.PAUSED
}

data class ThreadLoadResult(
    val thread: ThreadSummary?,
    val messages: List<ConversationMessage>,
    val runSnapshot: ThreadRunSnapshot,
)

data class MissingNotificationThreadPrompt(
    val threadId: String,
)

data class PlanStep(
    val text: String,
    val status: String?,
)

data class ApprovalRequest(
    val idValue: Any,
    val method: String,
    val command: String?,
    val reason: String?,
    val threadId: String?,
    val turnId: String?,
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKING,
    CONNECTED,
    RETRYING_SAVED_PAIRING,
    RECONNECT_REQUIRED,
    UPDATE_REQUIRED,
}

sealed interface ClientUpdate {
    data class Connection(
        val status: ConnectionStatus,
        val detail: String? = null,
        val fingerprint: String? = null,
    ) : ClientUpdate

    data class PairingAvailability(
        val hasSavedPairing: Boolean,
        val fingerprint: String? = null,
    ) : ClientUpdate

    data class ThreadsLoaded(val threads: List<ThreadSummary>) : ClientUpdate

    data class ThreadLoaded(
        val thread: ThreadSummary?,
        val messages: List<ConversationMessage>,
    ) : ClientUpdate

    data class RuntimeConfigLoaded(
        val models: List<ModelOption>,
        val selectedModelId: String?,
        val selectedReasoningEffort: String?,
        val selectedAccessMode: AccessMode,
        val selectedServiceTier: ServiceTier?,
        val supportsServiceTier: Boolean,
        val supportsThreadFork: Boolean,
        val threadRuntimeOverridesByThread: Map<String, ThreadRuntimeOverride>,
    ) : ClientUpdate

    data class PlanUpdated(
        val threadId: String?,
        val turnId: String?,
        val explanation: String?,
        val steps: List<PlanStep>,
    ) : ClientUpdate

    data class PlanDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val delta: String,
    ) : ClientUpdate

    data class PlanCompleted(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val text: String,
        val explanation: String?,
        val steps: List<PlanStep>?,
    ) : ClientUpdate

    data class SubagentActionUpdate(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val action: SubagentAction,
        val isStreaming: Boolean,
    ) : ClientUpdate

    data class AssistantDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val delta: String,
    ) : ClientUpdate

    data class AssistantCompleted(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val text: String,
    ) : ClientUpdate

    data class TurnStarted(
        val threadId: String?,
        val turnId: String?,
    ) : ClientUpdate

    data class ReasoningDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val delta: String,
    ) : ClientUpdate

    data class ReasoningCompleted(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val text: String,
    ) : ClientUpdate

    data class ApprovalRequested(val request: ApprovalRequest) : ClientUpdate

    data object ApprovalCleared : ClientUpdate

    data class TurnCompleted(
        val threadId: String?,
        val turnId: String?,
        val terminalState: TurnTerminalState,
        val errorMessage: String? = null,
        val willRetry: Boolean = false,
    ) : ClientUpdate

    data class ThreadStatusChanged(
        val threadId: String?,
        val status: String?,
    ) : ClientUpdate

    data class Error(val message: String) : ClientUpdate
}

fun JSONArray.toStringList(): List<String> {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        values += optString(index)
    }
    return values
}

private fun sanitizedSubagentIdentity(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lowered = trimmed.lowercase()
    return if (lowered == "collabagenttoolcall" || lowered == "collabtoolcall") {
        null
    } else {
        trimmed
    }
}
