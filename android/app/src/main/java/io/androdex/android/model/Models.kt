package io.androdex.android.model

import org.json.JSONArray
import org.json.JSONObject

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

data class ThreadSummary(
    val id: String,
    val title: String,
    val preview: String?,
    val cwd: String?,
    val createdAtEpochMs: Long?,
    val updatedAtEpochMs: Long?,
) {
    val projectName: String
        get() = cwd
            ?.trim()
            ?.trimEnd('/', '\\')
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: "No Project"
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
    PLAN,
}

data class ConversationMessage(
    val id: String,
    val threadId: String,
    val role: ConversationRole,
    val kind: ConversationKind,
    val text: String,
    val createdAtEpochMs: Long,
    val turnId: String? = null,
    val itemId: String? = null,
    val isStreaming: Boolean = false,
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

    data class ApprovalRequested(val request: ApprovalRequest) : ClientUpdate

    data object ApprovalCleared : ClientUpdate

    data class TurnCompleted(val threadId: String?) : ClientUpdate

    data class Error(val message: String) : ClientUpdate
}

fun JSONArray.toStringList(): List<String> {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        values += optString(index)
    }
    return values
}
