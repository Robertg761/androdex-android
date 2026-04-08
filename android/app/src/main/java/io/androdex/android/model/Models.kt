package io.androdex.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
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

data class SavedRelaySession(
    val relayUrl: String,
    val hostId: String,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val protocolVersion: Int,
    val lastAppliedBridgeOutboundSeq: Int = 0,
) {
    fun toPairingPayload(): PairingPayload = PairingPayload(
        version = protocolVersion,
        relay = relayUrl,
        hostId = hostId,
        sessionId = hostId,
        macDeviceId = macDeviceId,
        macIdentityPublicKey = macIdentityPublicKey,
        bootstrapToken = null,
        expiresAt = 0L,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("relayUrl", relayUrl)
        .put("hostId", hostId)
        .put("macDeviceId", macDeviceId)
        .put("macIdentityPublicKey", macIdentityPublicKey)
        .put("protocolVersion", protocolVersion)
        .put("lastAppliedBridgeOutboundSeq", lastAppliedBridgeOutboundSeq)

    companion object {
        fun fromJson(json: JSONObject): SavedRelaySession? {
            val relayUrl = json.optString("relayUrl").trim()
            val hostId = json.optString("hostId").trim()
            val macDeviceId = json.optString("macDeviceId").trim()
            val macIdentityPublicKey = json.optString("macIdentityPublicKey").trim()
            if (relayUrl.isEmpty() || hostId.isEmpty() || macDeviceId.isEmpty() || macIdentityPublicKey.isEmpty()) {
                return null
            }
            return SavedRelaySession(
                relayUrl = relayUrl,
                hostId = hostId,
                macDeviceId = macDeviceId,
                macIdentityPublicKey = macIdentityPublicKey,
                protocolVersion = json.optInt("protocolVersion", json.optInt("v", 3).coerceAtLeast(3)),
                lastAppliedBridgeOutboundSeq = json.optInt("lastAppliedBridgeOutboundSeq", 0).coerceAtLeast(0),
            )
        }
    }
}

data class RecoveryPayload(
    val version: Int,
    val relay: String,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val phoneDeviceId: String,
    val recoveryIdentityPublicKey: String,
    val recoveryIdentityPrivateKey: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("relay", relay)
        .put("macDeviceId", macDeviceId)
        .put("macIdentityPublicKey", macIdentityPublicKey)
        .put("phoneDeviceId", phoneDeviceId)
        .put("recoveryIdentityPublicKey", recoveryIdentityPublicKey)
        .put("recoveryIdentityPrivateKey", recoveryIdentityPrivateKey)

    companion object {
        fun fromJson(json: JSONObject): RecoveryPayload? {
            val relay = json.optString("relay").trim()
            val macDeviceId = json.optString("macDeviceId").trim()
            val macIdentityPublicKey = json.optString("macIdentityPublicKey").trim()
            val phoneDeviceId = json.optString("phoneDeviceId").trim()
            val recoveryIdentityPublicKey = json.optString("recoveryIdentityPublicKey").trim()
            val recoveryIdentityPrivateKey = json.optString("recoveryIdentityPrivateKey").trim()
            if (
                relay.isEmpty()
                || macDeviceId.isEmpty()
                || macIdentityPublicKey.isEmpty()
                || phoneDeviceId.isEmpty()
                || recoveryIdentityPublicKey.isEmpty()
                || recoveryIdentityPrivateKey.isEmpty()
            ) {
                return null
            }
            return RecoveryPayload(
                version = json.optInt("v", 1),
                relay = relay,
                macDeviceId = macDeviceId,
                macIdentityPublicKey = macIdentityPublicKey,
                phoneDeviceId = phoneDeviceId,
                recoveryIdentityPublicKey = recoveryIdentityPublicKey,
                recoveryIdentityPrivateKey = recoveryIdentityPrivateKey,
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
    val relayUrl: String? = null,
    val displayName: String? = null,
    val lastResolvedHostId: String? = null,
    val lastResolvedAtEpochMs: Long? = null,
    val lastUsedAtEpochMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("macDeviceId", macDeviceId)
        .put("macIdentityPublicKey", macIdentityPublicKey)
        .put("lastPairedAtEpochMs", lastPairedAtEpochMs)
        .put("relayUrl", relayUrl)
        .put("displayName", displayName)
        .put("lastResolvedHostId", lastResolvedHostId)
        .put("lastResolvedAtEpochMs", lastResolvedAtEpochMs)
        .put("lastUsedAtEpochMs", lastUsedAtEpochMs)

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
                relayUrl = json.optString("relayUrl").trim().ifEmpty { null },
                displayName = json.optString("displayName").trim().ifEmpty { null },
                lastResolvedHostId = json.optString("lastResolvedHostId").trim().ifEmpty { null },
                lastResolvedAtEpochMs = json.optLong("lastResolvedAtEpochMs").takeIf { it > 0L },
                lastUsedAtEpochMs = json.optLong("lastUsedAtEpochMs").takeIf { it > 0L },
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
    val displayName: String? = null,
    val hasSavedRelaySession: Boolean = false,
)

enum class HostAccountStatus {
    UNKNOWN,
    UNAVAILABLE,
    NOT_LOGGED_IN,
    LOGIN_PENDING,
    AUTHENTICATED,
    EXPIRED,
}

enum class HostAccountSnapshotOrigin {
    BRIDGE_BOOTSTRAP,
    BRIDGE_FALLBACK,
    NATIVE_LIVE,
}

data class HostAccountSnapshot(
    val status: HostAccountStatus,
    val authMethod: String? = null,
    val email: String? = null,
    val planType: String? = null,
    val loginInFlight: Boolean = false,
    val needsReauth: Boolean = false,
    val tokenReady: Boolean? = null,
    val expiresAtEpochMs: Long? = null,
    val bridgeVersion: String? = null,
    val bridgeLatestVersion: String? = null,
    val rateLimits: List<HostRateLimitBucket> = emptyList(),
    val origin: HostAccountSnapshotOrigin = HostAccountSnapshotOrigin.BRIDGE_FALLBACK,
)

data class HostRuntimeMetadata(
    val runtimeTarget: String? = null,
    val runtimeTargetDisplayName: String? = null,
    val backendProvider: String? = null,
    val backendProviderDisplayName: String? = null,
    val runtimeAttachState: String? = null,
    val runtimeAttachFailure: String? = null,
    val runtimeProtocolVersion: String? = null,
    val runtimeAuthMode: String? = null,
    val runtimeEndpointHost: String? = null,
    val runtimeSnapshotSequence: Int? = null,
    val runtimeReplaySequence: Int? = null,
    val runtimeSubscriptionState: String? = null,
    val runtimeDuplicateSuppressionCount: Int? = null,
)

data class HostRateLimitBucket(
    val name: String,
    val remaining: Int? = null,
    val limit: Int? = null,
    val used: Int? = null,
    val resetsAtEpochMs: Long? = null,
)

data class ThreadTokenUsage(
    val tokensUsed: Int,
    val tokenLimit: Int,
) {
    val remainingTokens: Int
        get() = maxOf(0, tokenLimit - tokensUsed)
}

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
    val backendProvider: String? = null,
    val threadCapabilities: ThreadCapabilities? = null,
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

data class ThreadCapabilities(
    val readOnly: Boolean = false,
    val backendProvider: String? = null,
    val companionSupported: Boolean = false,
    val companionSupportState: String? = null,
    val companionSupportReason: String? = null,
    val workspacePath: String? = null,
    val workspacePathSource: String? = null,
    val workspaceResolved: Boolean = false,
    val workspaceAvailable: Boolean = false,
    val workspaceFallbackUsed: Boolean = false,
    val recordedWorktreePath: String? = null,
    val recordedWorktreeAvailable: Boolean = false,
    val projectWorkspaceRoot: String? = null,
    val projectWorkspaceRootAvailable: Boolean = false,
    val read: ThreadCapabilityFlag? = null,
    val liveUpdates: ThreadCapabilityFlag? = null,
    val turnStart: ThreadCapabilityFlag? = null,
    val turnInterrupt: ThreadCapabilityFlag? = null,
    val approvalResponses: ThreadCapabilityFlag? = null,
    val userInputResponses: ThreadCapabilityFlag? = null,
    val toolInputResponses: ThreadCapabilityFlag? = null,
    val checkpointRollback: ThreadCapabilityFlag? = null,
)

data class ThreadCapabilityFlag(
    val supported: Boolean,
    val reason: String? = null,
)

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

data class TurnFileMention(
    val path: String,
    val name: String? = null,
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
    val title: String,
) {
    PLAN("plan", "Plan mode"),
    ;

    companion object {
        fun fromWireValue(rawValue: String?): CollaborationModeKind? {
            val normalized = rawValue?.trim()?.lowercase(Locale.US) ?: return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
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
    EXECUTION,
    SUBAGENT_ACTION,
    PLAN,
}

enum class ExecutionKind {
    COMMAND,
    REVIEW,
    COMPACTION,
    ROLLBACK,
    CLEANUP,
    ACTIVITY,
}

data class ExecutionDetail(
    val label: String,
    val value: String,
    val isMonospace: Boolean = false,
)

data class ExecutionContent(
    val kind: ExecutionKind,
    val title: String,
    val status: String,
    val summary: String? = null,
    val output: String? = null,
    val details: List<ExecutionDetail> = emptyList(),
) {
    val label: String
        get() = when (kind) {
            ExecutionKind.COMMAND -> "Command"
            ExecutionKind.REVIEW -> "Review"
            ExecutionKind.COMPACTION -> "Compaction"
            ExecutionKind.ROLLBACK -> "Rollback"
            ExecutionKind.CLEANUP -> "Cleanup"
            ExecutionKind.ACTIVITY -> "Activity"
        }
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
    val execution: ExecutionContent? = null,
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

data class ToolUserInputRequest(
    val idValue: Any,
    val method: String,
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val title: String?,
    val message: String?,
    val questions: List<ToolUserInputQuestion>,
    val rawPayload: String,
)

data class ToolUserInputQuestion(
    val id: String,
    val header: String?,
    val question: String,
    val options: List<ToolUserInputOption> = emptyList(),
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
)

data class ToolUserInputOption(
    val label: String,
    val description: String? = null,
)

data class ToolUserInputAnswer(
    val answers: List<String>,
)

data class ToolUserInputResponse(
    val answers: Map<String, ToolUserInputAnswer>,
) {
    fun toJson(): JSONObject {
        val answersJson = JSONObject()
        answers.forEach { (questionId, answer) ->
            answersJson.put(
                questionId,
                JSONObject().put("answers", JSONArray(answer.answers)),
            )
        }
        return JSONObject().put("answers", answersJson)
    }
}

val ToolUserInputRequest.requestId: String
    get() = idValue.toString()

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKING,
    CONNECTED,
    RETRYING_SAVED_PAIRING,
    TRUST_BLOCKED,
    RECONNECT_REQUIRED,
    UPDATE_REQUIRED,
}

sealed interface ClientUpdate {
    data class Connection(
        val status: ConnectionStatus,
        val detail: String? = null,
        val fingerprint: String? = null,
        val runtimeMetadata: HostRuntimeMetadata? = null,
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
        val supportsThreadCompaction: Boolean,
        val supportsThreadRollback: Boolean,
        val supportsBackgroundTerminalCleanup: Boolean,
        val supportsThreadFork: Boolean,
        val collaborationModes: Set<CollaborationModeKind>,
        val threadRuntimeOverridesByThread: Map<String, ThreadRuntimeOverride>,
        val runtimeMetadata: HostRuntimeMetadata? = null,
    ) : ClientUpdate

    data class AccountStatusLoaded(
        val snapshot: HostAccountSnapshot?,
    ) : ClientUpdate

    data class TokenUsageUpdated(
        val threadId: String?,
        val usage: ThreadTokenUsage,
    ) : ClientUpdate

    data class SkillsChanged(
        val cwds: List<String> = emptyList(),
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

    data class CommandExecutionUpdate(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val command: String?,
        val status: String?,
        val text: String,
        val isStreaming: Boolean,
        val execution: ExecutionContent? = null,
    ) : ClientUpdate

    data class ExecutionUpdate(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val text: String,
        val isStreaming: Boolean,
        val execution: ExecutionContent,
    ) : ClientUpdate

    data class ApprovalRequested(val request: ApprovalRequest) : ClientUpdate

    data class ToolUserInputRequested(
        val request: ToolUserInputRequest,
    ) : ClientUpdate

    data class ApprovalCleared(val requestId: String? = null) : ClientUpdate

    data class ToolUserInputCleared(
        val threadId: String? = null,
        val requestId: String? = null,
    ) : ClientUpdate

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
