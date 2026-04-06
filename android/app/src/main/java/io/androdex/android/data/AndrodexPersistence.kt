package io.androdex.android.data

import android.content.Context
import android.content.SharedPreferences
import io.androdex.android.crypto.SecureStore
import io.androdex.android.model.AccessMode
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.model.ExecutionContent
import io.androdex.android.model.ExecutionDetail
import io.androdex.android.model.ExecutionKind
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.PairingPayload
import io.androdex.android.model.PlanStep
import io.androdex.android.model.PhoneIdentityState
import io.androdex.android.model.RecoveryPayload
import io.androdex.android.model.SavedRelaySession
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.SubagentAction
import io.androdex.android.model.SubagentRef
import io.androdex.android.model.SubagentState
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.TrustedMacRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

open class AndrodexPersistence internal constructor(
    private val secureStore: SecureStore,
    private val durableTrustPreferences: SharedPreferences? = null,
    private val timelineCachePreferences: SharedPreferences? = null,
) {
    constructor(context: Context) : this(
        secureStore = SecureStore(context),
        durableTrustPreferences = context.applicationContext.getSharedPreferences(
            DURABLE_TRUST_PREFS_NAME,
            Context.MODE_PRIVATE,
        ),
        timelineCachePreferences = context.applicationContext.getSharedPreferences(
            THREAD_TIMELINE_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    private var startupReport: SecureStateStartupReport

    init {
        startupReport = sanitizeUnreadableSecureState()
        migrateLegacySavedPairingIfNeeded()
    }

    fun hasSavedPairing(): Boolean = loadSavedRelaySession() != null

    fun takeStartupNotice(): String? {
        val notice = startupReport.notice
        startupReport = startupReport.copy(notice = null)
        return notice
    }

    fun startupBlockedTrustDetail(): String? = startupReport.blockedTrustDetail

    fun hasStartupBlockedTrust(): Boolean = !startupReport.blockedTrustDetail.isNullOrBlank()

    fun loadSavedRelaySession(): SavedRelaySession? {
        val current = secureStore.readString(KEY_SAVED_RELAY_SESSION)
            ?.let { runCatching { SavedRelaySession.fromJson(JSONObject(it)) }.getOrNull() }
        if (current != null) {
            return current
        }

        val legacyPairing = secureStore.readString(KEY_PAIRING)
            ?.let { runCatching { PairingPayload.fromJson(JSONObject(it)) }.getOrNull() }
            ?: return null
        val migrated = legacyPairing.toSavedRelaySession(
            lastAppliedBridgeOutboundSeq = secureStore.readString(KEY_LAST_APPLIED_SEQ)?.toIntOrNull() ?: 0,
        ) ?: return null
        saveSavedRelaySession(migrated)
        secureStore.remove(KEY_PAIRING)
        return migrated
    }

    fun saveSavedRelaySession(savedRelaySession: SavedRelaySession) {
        secureStore.writeString(
            KEY_SAVED_RELAY_SESSION,
            savedRelaySession.toJson().toString(),
            synchronously = true,
        )
        secureStore.writeString(
            KEY_LAST_APPLIED_SEQ,
            savedRelaySession.lastAppliedBridgeOutboundSeq.toString(),
            synchronously = true,
        )
    }

    fun clearSavedRelaySession() {
        secureStore.remove(KEY_SAVED_RELAY_SESSION, synchronously = true)
        secureStore.remove(KEY_PAIRING, synchronously = true)
        secureStore.remove(KEY_LAST_APPLIED_SEQ, synchronously = true)
    }

    fun loadPhoneIdentity(): PhoneIdentityState? {
        val raw = readDurableTrustString(KEY_PHONE_IDENTITY) ?: return null
        return runCatching { PhoneIdentityState.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun savePhoneIdentity(identityState: PhoneIdentityState) {
        writeDurableTrustString(KEY_PHONE_IDENTITY, identityState.toJson().toString())
    }

    fun clearPhoneIdentity() {
        removeDurableTrustString(KEY_PHONE_IDENTITY)
    }

    fun loadTrustedMacRegistry(): TrustedMacRegistry {
        val raw = readDurableTrustString(KEY_TRUSTED_MACS) ?: return TrustedMacRegistry.empty
        return runCatching { TrustedMacRegistry.fromJson(JSONObject(raw)) }.getOrDefault(TrustedMacRegistry.empty)
    }

    fun saveTrustedMacRegistry(registry: TrustedMacRegistry) {
        writeDurableTrustString(KEY_TRUSTED_MACS, registry.toJson().toString())
    }

    fun clearTrustedMacRegistry() {
        removeDurableTrustString(KEY_TRUSTED_MACS)
    }

    fun loadRecoveryPayloadRegistry(): Map<String, RecoveryPayload> {
        val raw = readDurableTrustString(KEY_TRUSTED_RECOVERY_PAYLOADS) ?: return emptyMap()
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val decoded = linkedMapOf<String, RecoveryPayload>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            if (key.isEmpty()) {
                continue
            }
            val recoveryPayload = payload.optJSONObject(key)?.let(RecoveryPayload::fromJson) ?: continue
            decoded[key] = recoveryPayload
        }
        return decoded
    }

    fun saveRecoveryPayloadRegistry(registry: Map<String, RecoveryPayload>) {
        if (registry.isEmpty()) {
            removeDurableTrustString(KEY_TRUSTED_RECOVERY_PAYLOADS)
            return
        }
        val payload = JSONObject()
        registry.forEach { (macDeviceId, recoveryPayload) ->
            val normalizedMacDeviceId = macDeviceId.trim()
            if (normalizedMacDeviceId.isNotEmpty()) {
                payload.put(normalizedMacDeviceId, recoveryPayload.toJson())
            }
        }
        writeDurableTrustString(KEY_TRUSTED_RECOVERY_PAYLOADS, payload.toString())
    }

    fun loadLastTrustedMacDeviceId(): String? {
        return readDurableTrustString(KEY_LAST_TRUSTED_MAC_DEVICE_ID)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveLastTrustedMacDeviceId(value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            removeDurableTrustString(KEY_LAST_TRUSTED_MAC_DEVICE_ID)
        } else {
            writeDurableTrustString(KEY_LAST_TRUSTED_MAC_DEVICE_ID, normalized)
        }
    }

    fun clearAllPairingState() {
        clearSavedRelaySession()
        clearPhoneIdentity()
        clearTrustedMacRegistry()
        saveRecoveryPayloadRegistry(emptyMap())
        saveLastTrustedMacDeviceId(null)
        clearAllPersistedThreadTimelines()
        startupReport = SecureStateStartupReport()
    }

    fun loadLastAppliedBridgeOutboundSeq(): Int {
        return secureStore.readString(KEY_LAST_APPLIED_SEQ)?.toIntOrNull()
            ?: loadSavedRelaySession()?.lastAppliedBridgeOutboundSeq
            ?: 0
    }

    fun saveLastAppliedBridgeOutboundSeq(value: Int) {
        secureStore.writeString(KEY_LAST_APPLIED_SEQ, value.coerceAtLeast(0).toString())
    }

    fun loadSelectedModelId(): String? {
        return secureStore.readString(KEY_SELECTED_MODEL_ID)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveSelectedModelId(value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            secureStore.remove(KEY_SELECTED_MODEL_ID)
        } else {
            secureStore.writeString(KEY_SELECTED_MODEL_ID, normalized)
        }
    }

    fun loadSelectedReasoningEffort(): String? {
        return secureStore.readString(KEY_SELECTED_REASONING_EFFORT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveSelectedReasoningEffort(value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            secureStore.remove(KEY_SELECTED_REASONING_EFFORT)
        } else {
            secureStore.writeString(KEY_SELECTED_REASONING_EFFORT, normalized)
        }
    }

    fun loadSelectedAccessMode(): AccessMode {
        return AccessMode.fromWireValue(secureStore.readString(KEY_SELECTED_ACCESS_MODE))
            ?: AccessMode.ON_REQUEST
    }

    fun saveSelectedAccessMode(value: AccessMode) {
        secureStore.writeString(KEY_SELECTED_ACCESS_MODE, value.wireValue)
    }

    fun loadSelectedServiceTier(): ServiceTier? {
        return ServiceTier.fromWireValue(secureStore.readString(KEY_SELECTED_SERVICE_TIER))
    }

    fun saveSelectedServiceTier(value: ServiceTier?) {
        val normalized = value?.wireValue
        if (normalized == null) {
            secureStore.remove(KEY_SELECTED_SERVICE_TIER)
        } else {
            secureStore.writeString(KEY_SELECTED_SERVICE_TIER, normalized)
        }
    }

    fun loadThreadRuntimeOverrides(): Map<String, ThreadRuntimeOverride> {
        return loadThreadRuntimeOverrides(scopeKey = null)
    }

    fun saveThreadRuntimeOverrides(value: Map<String, ThreadRuntimeOverride>) {
        saveThreadRuntimeOverrides(scopeKey = null, value = value)
    }

    fun loadThreadRuntimeOverrides(scopeKey: String?): Map<String, ThreadRuntimeOverride> {
        val bundle = decodeThreadRuntimeOverrideBundleSpec(secureStore.readString(KEY_THREAD_RUNTIME_OVERRIDES))
        val normalizedScopeKey = normalizeThreadRuntimeOverrideScopeKey(scopeKey) ?: return bundle.legacyOverrides
        return bundle.scopedOverridesByScopeKey[normalizedScopeKey].orEmpty()
    }

    fun saveThreadRuntimeOverrides(
        scopeKey: String?,
        value: Map<String, ThreadRuntimeOverride>,
    ) {
        val existingBundle = decodeThreadRuntimeOverrideBundleSpec(secureStore.readString(KEY_THREAD_RUNTIME_OVERRIDES))
        val normalizedScopeKey = normalizeThreadRuntimeOverrideScopeKey(scopeKey)
        val normalizedOverrides = normalizeThreadRuntimeOverrides(value)
        val nextBundle = if (normalizedScopeKey == null) {
            existingBundle.copy(legacyOverrides = normalizedOverrides)
        } else {
            existingBundle.copy(
                scopedOverridesByScopeKey = existingBundle.scopedOverridesByScopeKey.toMutableMap().apply {
                    if (normalizedOverrides.isEmpty()) {
                        remove(normalizedScopeKey)
                    } else {
                        put(normalizedScopeKey, normalizedOverrides)
                    }
                }
            )
        }
        val encoded = encodeThreadRuntimeOverrideBundleSpec(nextBundle)
        if (encoded == null) {
            secureStore.remove(KEY_THREAD_RUNTIME_OVERRIDES)
        } else {
            secureStore.writeString(KEY_THREAD_RUNTIME_OVERRIDES, encoded)
        }
    }

    fun loadPersistedThreadTimelines(scopeKey: String?): Map<String, List<ConversationMessage>> {
        val normalizedScopeKey = normalizeThreadTimelineScopeKey(scopeKey) ?: return emptyMap()
        val preferences = timelineCachePreferences ?: return emptyMap()
        val threadIds = decodePersistedStringListSpec(
            preferences.getString(threadTimelineScopeIndexKey(normalizedScopeKey), null)
        )
        if (threadIds.isEmpty()) {
            return emptyMap()
        }

        val decoded = linkedMapOf<String, List<ConversationMessage>>()
        threadIds.forEach { threadId ->
            val messages = decodePersistedThreadTimelineMessagesSpec(
                rawValue = preferences.getString(
                    threadTimelineEntryKey(normalizedScopeKey, threadId),
                    null,
                ),
                fallbackThreadId = threadId,
            ) ?: return@forEach
            decoded[threadId] = messages
        }
        return decoded
    }

    fun savePersistedThreadTimeline(
        scopeKey: String?,
        threadId: String,
        messages: List<ConversationMessage>,
    ) {
        val normalizedScopeKey = normalizeThreadTimelineScopeKey(scopeKey) ?: return
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return
        val preferences = timelineCachePreferences ?: return

        val existingThreadIds = decodePersistedStringListSpec(
            preferences.getString(threadTimelineScopeIndexKey(normalizedScopeKey), null)
        )
        val nextThreadIds = buildList {
            addAll(existingThreadIds)
            if (normalizedThreadId !in this) {
                add(normalizedThreadId)
            }
        }

        preferences.edit()
            .putString(
                KEY_THREAD_TIMELINE_CACHE_SCOPES,
                encodePersistedStringListSpec(
                    decodePersistedStringListSpec(
                        preferences.getString(KEY_THREAD_TIMELINE_CACHE_SCOPES, null)
                    ) + normalizedScopeKey
                ),
            )
            .putString(
                threadTimelineScopeIndexKey(normalizedScopeKey),
                encodePersistedStringListSpec(nextThreadIds),
            )
            .putString(
                threadTimelineEntryKey(normalizedScopeKey, normalizedThreadId),
                encodePersistedThreadTimelineMessagesSpec(messages),
            )
            .apply()
    }

    fun clearPersistedThreadTimelines(scopeKey: String?) {
        val normalizedScopeKey = normalizeThreadTimelineScopeKey(scopeKey) ?: return
        val preferences = timelineCachePreferences ?: return
        val threadIds = decodePersistedStringListSpec(
            preferences.getString(threadTimelineScopeIndexKey(normalizedScopeKey), null)
        )
        val remainingScopes = decodePersistedStringListSpec(
            preferences.getString(KEY_THREAD_TIMELINE_CACHE_SCOPES, null)
        ).filterNot { it == normalizedScopeKey }
        val editor = preferences.edit()
            .putString(KEY_THREAD_TIMELINE_CACHE_SCOPES, encodePersistedStringListSpec(remainingScopes))
            .remove(threadTimelineScopeIndexKey(normalizedScopeKey))
        threadIds.forEach { threadId ->
            editor.remove(threadTimelineEntryKey(normalizedScopeKey, threadId))
        }
        editor.apply()
    }

    private fun sanitizeUnreadableSecureState(): SecureStateStartupReport {
        val savedRelaySessionState = secureStore.readStringState(KEY_SAVED_RELAY_SESSION)
        val pairingState = secureStore.readStringState(KEY_PAIRING)
        val phoneIdentityState = secureStore.readStringState(KEY_PHONE_IDENTITY)
        val trustedMacState = secureStore.readStringState(KEY_TRUSTED_MACS)
        val trustedRecoveryPayloadsState = secureStore.readStringState(KEY_TRUSTED_RECOVERY_PAYLOADS)
        val lastTrustedMacDeviceIdState = secureStore.readStringState(KEY_LAST_TRUSTED_MAC_DEVICE_ID)
        val lastAppliedSeqState = secureStore.readStringState(KEY_LAST_APPLIED_SEQ)
        val selectedModelState = secureStore.readStringState(KEY_SELECTED_MODEL_ID)
        val selectedReasoningState = secureStore.readStringState(KEY_SELECTED_REASONING_EFFORT)
        val selectedAccessModeState = secureStore.readStringState(KEY_SELECTED_ACCESS_MODE)
        val selectedServiceTierState = secureStore.readStringState(KEY_SELECTED_SERVICE_TIER)
        val threadRuntimeOverridesState = secureStore.readStringState(KEY_THREAD_RUNTIME_OVERRIDES)

        val repairedPhoneIdentity = repairDurableTrustFromBackupIfNeeded(KEY_PHONE_IDENTITY, phoneIdentityState)
        val repairedTrustedMacRegistry = repairDurableTrustFromBackupIfNeeded(KEY_TRUSTED_MACS, trustedMacState)
        val repairedTrustedRecoveryPayloads =
            repairDurableTrustFromBackupIfNeeded(KEY_TRUSTED_RECOVERY_PAYLOADS, trustedRecoveryPayloadsState)
        val repairedLastTrustedMacDeviceId =
            repairDurableTrustFromBackupIfNeeded(KEY_LAST_TRUSTED_MAC_DEVICE_ID, lastTrustedMacDeviceIdState)

        val savedRelaySessionUnreadable = savedRelaySessionState.isUnreadable
            || (pairingState.isUnreadable && !savedRelaySessionState.wasPresent)
            || (lastAppliedSeqState.isUnreadable && (savedRelaySessionState.wasPresent || pairingState.wasPresent))
        val phoneIdentityUnreadable = phoneIdentityState.isUnreadable && !repairedPhoneIdentity
        val trustedMacUnreadable = (
            trustedMacState.isUnreadable && !repairedTrustedMacRegistry
        ) || (
            trustedRecoveryPayloadsState.isUnreadable && !repairedTrustedRecoveryPayloads
        )
        val lastTrustedMacUnreadable = lastTrustedMacDeviceIdState.isUnreadable && !repairedLastTrustedMacDeviceId

        if (savedRelaySessionUnreadable) {
            clearSavedRelaySession()
        }
        if (lastTrustedMacUnreadable) {
            secureStore.remove(KEY_LAST_TRUSTED_MAC_DEVICE_ID)
        }

        if (selectedModelState.isUnreadable) {
            secureStore.remove(KEY_SELECTED_MODEL_ID)
        }
        if (selectedReasoningState.isUnreadable) {
            secureStore.remove(KEY_SELECTED_REASONING_EFFORT)
        }
        if (selectedAccessModeState.isUnreadable) {
            secureStore.remove(KEY_SELECTED_ACCESS_MODE)
        }
        if (selectedServiceTierState.isUnreadable) {
            secureStore.remove(KEY_SELECTED_SERVICE_TIER)
        }
        if (threadRuntimeOverridesState.isUnreadable) {
            secureStore.remove(KEY_THREAD_RUNTIME_OVERRIDES)
        }

        return SecureStateStartupReport(
            notice = buildSecureStateRecoveryNotice(
                savedRelaySessionUnreadable = savedRelaySessionUnreadable,
                phoneIdentityUnreadable = phoneIdentityUnreadable,
                trustedMacUnreadable = trustedMacUnreadable || lastTrustedMacUnreadable,
            ),
            blockedTrustDetail = buildBlockedDurableTrustDetail(
                phoneIdentityUnreadable = phoneIdentityUnreadable,
                trustedMacUnreadable = trustedMacUnreadable,
            ),
        )
    }

    private fun readDurableTrustString(key: String): String? {
        val secureValue = secureStore.readString(key)?.trim()?.takeIf { it.isNotEmpty() }
        if (secureValue != null) {
            return secureValue
        }
        val backupValue = durableTrustPreferences?.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (backupValue != null) {
            secureStore.writeString(key, backupValue)
        }
        return backupValue
    }

    private fun writeDurableTrustString(key: String, value: String) {
        secureStore.writeString(key, value, synchronously = true)
        durableTrustPreferences?.edit()?.putString(key, value)?.apply()
    }

    private fun removeDurableTrustString(key: String) {
        secureStore.remove(key, synchronously = true)
        durableTrustPreferences?.edit()?.remove(key)?.apply()
    }

    private fun repairDurableTrustFromBackupIfNeeded(
        key: String,
        state: SecureStore.SecureReadState,
    ): Boolean {
        val backupValue = durableTrustPreferences?.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (state.isUnreadable) {
            secureStore.remove(key)
        }
        if (backupValue == null) {
            return false
        }
        if (state.value == null) {
            secureStore.writeString(key, backupValue)
        }
        return state.isUnreadable
    }

    private fun migrateLegacySavedPairingIfNeeded() {
        val currentSession = secureStore.readString(KEY_SAVED_RELAY_SESSION)
        if (!currentSession.isNullOrBlank()) {
            return
        }
        val legacyPairing = secureStore.readString(KEY_PAIRING)
            ?.let { runCatching { PairingPayload.fromJson(JSONObject(it)) }.getOrNull() }
            ?: return
        val migrated = legacyPairing.toSavedRelaySession(
            lastAppliedBridgeOutboundSeq = secureStore.readString(KEY_LAST_APPLIED_SEQ)?.toIntOrNull() ?: 0,
        ) ?: return
        saveSavedRelaySession(migrated)
        secureStore.remove(KEY_PAIRING)
    }

    private fun clearAllPersistedThreadTimelines() {
        val preferences = timelineCachePreferences ?: return
        val scopeKeys = decodePersistedStringListSpec(
            preferences.getString(KEY_THREAD_TIMELINE_CACHE_SCOPES, null)
        )
        if (scopeKeys.isEmpty()) {
            preferences.edit().remove(KEY_THREAD_TIMELINE_CACHE_SCOPES).apply()
            return
        }

        val editor = preferences.edit().remove(KEY_THREAD_TIMELINE_CACHE_SCOPES)
        scopeKeys.forEach { scopeKey ->
            val threadIds = decodePersistedStringListSpec(
                preferences.getString(threadTimelineScopeIndexKey(scopeKey), null)
            )
            editor.remove(threadTimelineScopeIndexKey(scopeKey))
            threadIds.forEach { threadId ->
                editor.remove(threadTimelineEntryKey(scopeKey, threadId))
            }
        }
        editor.apply()
    }

    private companion object {
        const val DURABLE_TRUST_PREFS_NAME = "androdex.durable_trust"
        const val THREAD_TIMELINE_CACHE_PREFS_NAME = "androdex.thread_timelines"
        const val KEY_THREAD_TIMELINE_CACHE_SCOPES = "thread_timeline_cache_scopes"
        const val KEY_PAIRING = "pairing_payload"
        const val KEY_SAVED_RELAY_SESSION = "saved_relay_session"
        const val KEY_PHONE_IDENTITY = "phone_identity"
        const val KEY_TRUSTED_MACS = "trusted_macs"
        const val KEY_TRUSTED_RECOVERY_PAYLOADS = "trusted_recovery_payloads"
        const val KEY_LAST_TRUSTED_MAC_DEVICE_ID = "last_trusted_mac_device_id"
        const val KEY_LAST_APPLIED_SEQ = "last_applied_bridge_outbound_seq"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_SELECTED_REASONING_EFFORT = "selected_reasoning_effort"
        const val KEY_SELECTED_ACCESS_MODE = "selected_access_mode"
        const val KEY_SELECTED_SERVICE_TIER = "selected_service_tier"
        const val KEY_THREAD_RUNTIME_OVERRIDES = "thread_runtime_overrides"
    }
}

internal data class SecureStateStartupReport(
    val notice: String? = null,
    val blockedTrustDetail: String? = null,
)

internal fun PairingPayload.toSavedRelaySession(
    lastAppliedBridgeOutboundSeq: Int = 0,
): SavedRelaySession? {
    val routingId = routingId.trim()
    if (relay.isBlank() || routingId.isEmpty() || macDeviceId.isBlank() || macIdentityPublicKey.isBlank()) {
        return null
    }
    return SavedRelaySession(
        relayUrl = relay,
        hostId = routingId,
        macDeviceId = macDeviceId,
        macIdentityPublicKey = macIdentityPublicKey,
        protocolVersion = version.coerceAtLeast(3),
        lastAppliedBridgeOutboundSeq = lastAppliedBridgeOutboundSeq.coerceAtLeast(0),
    )
}

internal fun buildSecureStateRecoveryNotice(
    savedRelaySessionUnreadable: Boolean,
    phoneIdentityUnreadable: Boolean,
    trustedMacUnreadable: Boolean,
): String? {
    return when {
        phoneIdentityUnreadable -> {
            "Androdex could not read this phone's saved trusted identity. Durable trust was preserved, but automatic reconnect is blocked until you repair with a fresh QR or forget the trusted host."
        }
        savedRelaySessionUnreadable -> {
            "The saved live relay session was unreadable, so Androdex cleared only that disposable reconnect target. Your trusted host details were kept when possible."
        }
        trustedMacUnreadable -> {
            "Androdex could not read the trusted host registry. Durable trust was preserved, but automatic reconnect is blocked until you repair with a fresh QR or forget the trusted host."
        }
        else -> null
    }
}

internal fun buildBlockedDurableTrustDetail(
    phoneIdentityUnreadable: Boolean,
    trustedMacUnreadable: Boolean,
): String? {
    return when {
        phoneIdentityUnreadable -> {
            "This Android device cannot read its saved trusted identity, so secure reconnect is blocked. Repair with a fresh QR code or forget the trusted host on this phone."
        }
        trustedMacUnreadable -> {
            "This Android device cannot read its trusted host registry, so secure reconnect is blocked. Repair with a fresh QR code or forget the trusted host on this phone."
        }
        else -> null
    }
}

internal fun decodeThreadRuntimeOverridesSpec(rawValue: String?): Map<String, ThreadRuntimeOverride> {
    val normalizedRawValue = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    val payload = runCatching { JSONObject(normalizedRawValue) }.getOrNull() ?: return emptyMap()
    val decoded = linkedMapOf<String, ThreadRuntimeOverride>()
    val keys = payload.keys()
    while (keys.hasNext()) {
        val threadId = keys.next().trim()
        if (threadId.isEmpty()) {
            continue
        }
        val runtimeOverride = payload.optJSONObject(threadId)
            ?.let(::decodeThreadRuntimeOverrideSpec)
            ?.normalized()
            ?: continue
        decoded[threadId] = runtimeOverride
    }
    return decoded
}

internal fun encodeThreadRuntimeOverridesSpec(value: Map<String, ThreadRuntimeOverride>): String? {
    val normalizedValue = normalizeThreadRuntimeOverrides(value)
    if (normalizedValue.isEmpty()) {
        return null
    }

    val payload = JSONObject()
    normalizedValue.forEach { (threadId, runtimeOverride) ->
        payload.put(threadId, encodeThreadRuntimeOverrideSpec(runtimeOverride))
    }

    return payload.takeIf { it.length() > 0 }?.toString()
}

internal data class ThreadRuntimeOverrideBundleSpec(
    val legacyOverrides: Map<String, ThreadRuntimeOverride> = emptyMap(),
    val scopedOverridesByScopeKey: Map<String, Map<String, ThreadRuntimeOverride>> = emptyMap(),
)

internal fun decodeThreadRuntimeOverrideBundleSpec(rawValue: String?): ThreadRuntimeOverrideBundleSpec {
    val normalizedRawValue = rawValue?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ThreadRuntimeOverrideBundleSpec()
    val payload = runCatching { JSONObject(normalizedRawValue) }.getOrNull()
        ?: return ThreadRuntimeOverrideBundleSpec()
    val hasScopedPayload = payload.has("scopes") || payload.has("legacy") || payload.optInt("v", 0) >= 2
    if (!hasScopedPayload) {
        return ThreadRuntimeOverrideBundleSpec(
            legacyOverrides = decodeThreadRuntimeOverridesSpec(normalizedRawValue),
        )
    }

    val legacyOverrides = payload.optJSONObject("legacy")
        ?.toString()
        ?.let(::decodeThreadRuntimeOverridesSpec)
        .orEmpty()
    val scopedOverridesByScopeKey = linkedMapOf<String, Map<String, ThreadRuntimeOverride>>()
    val scopesPayload = payload.optJSONObject("scopes") ?: JSONObject()
    val scopeKeys = scopesPayload.keys()
    while (scopeKeys.hasNext()) {
        val scopeKey = normalizeThreadRuntimeOverrideScopeKey(scopeKeys.next()) ?: continue
        val overrides = scopesPayload.optJSONObject(scopeKey)
            ?.toString()
            ?.let(::decodeThreadRuntimeOverridesSpec)
            .orEmpty()
        if (overrides.isNotEmpty()) {
            scopedOverridesByScopeKey[scopeKey] = overrides
        }
    }
    return ThreadRuntimeOverrideBundleSpec(
        legacyOverrides = legacyOverrides,
        scopedOverridesByScopeKey = scopedOverridesByScopeKey,
    )
}

internal fun encodeThreadRuntimeOverrideBundleSpec(value: ThreadRuntimeOverrideBundleSpec): String? {
    val normalizedLegacyOverrides = normalizeThreadRuntimeOverrides(value.legacyOverrides)
    val normalizedScopedOverrides = value.scopedOverridesByScopeKey.mapNotNull { (scopeKey, overrides) ->
        val normalizedScopeKey = normalizeThreadRuntimeOverrideScopeKey(scopeKey) ?: return@mapNotNull null
        val normalizedOverrides = normalizeThreadRuntimeOverrides(overrides)
        if (normalizedOverrides.isEmpty()) {
            null
        } else {
            normalizedScopeKey to normalizedOverrides
        }
    }.toMap(linkedMapOf())

    if (normalizedScopedOverrides.isEmpty()) {
        return encodeThreadRuntimeOverridesSpec(normalizedLegacyOverrides)
    }

    val payload = JSONObject()
        .put("v", 2)
        .put("legacy", encodeThreadRuntimeOverridesPayload(normalizedLegacyOverrides))
        .put("scopes", JSONObject().apply {
            normalizedScopedOverrides.forEach { (scopeKey, overrides) ->
                put(scopeKey, encodeThreadRuntimeOverridesPayload(overrides))
            }
        })

    return payload.toString()
}

private fun normalizeThreadRuntimeOverrides(value: Map<String, ThreadRuntimeOverride>): Map<String, ThreadRuntimeOverride> {
    if (value.isEmpty()) {
        return emptyMap()
    }

    val normalized = linkedMapOf<String, ThreadRuntimeOverride>()
    value.forEach { (threadId, runtimeOverride) ->
        val normalizedThreadId = threadId.trim()
        val normalizedOverride = runtimeOverride.normalized()
        if (normalizedThreadId.isEmpty() || normalizedOverride == null) {
            return@forEach
        }
        normalized[normalizedThreadId] = normalizedOverride
    }
    return normalized
}

private fun encodeThreadRuntimeOverridesPayload(value: Map<String, ThreadRuntimeOverride>): JSONObject {
    return JSONObject().apply {
        value.forEach { (threadId, runtimeOverride) ->
            put(threadId, encodeThreadRuntimeOverrideSpec(runtimeOverride))
        }
    }
}

private fun encodeThreadRuntimeOverrideSpec(value: ThreadRuntimeOverride): JSONObject {
    return JSONObject()
        .put("reasoningEffort", value.reasoningEffort)
        .put("serviceTier", value.serviceTierRawValue)
        .put("overridesReasoning", value.overridesReasoning)
        .put("overridesServiceTier", value.overridesServiceTier)
}

private fun decodeThreadRuntimeOverrideSpec(value: JSONObject): ThreadRuntimeOverride {
    return ThreadRuntimeOverride(
        reasoningEffort = value.optString("reasoningEffort").trim().ifEmpty { null },
        serviceTierRawValue = value.optString("serviceTier").trim().ifEmpty { null },
        overridesReasoning = value.optBoolean("overridesReasoning"),
        overridesServiceTier = value.optBoolean("overridesServiceTier"),
    )
}

internal fun decodePersistedThreadTimelineMessagesSpec(
    rawValue: String?,
    fallbackThreadId: String? = null,
): List<ConversationMessage>? {
    val normalizedRawValue = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val payload = runCatching { JSONObject(normalizedRawValue) }.getOrNull() ?: return null
    val items = payload.optJSONArray("messages") ?: return null
    val decoded = mutableListOf<ConversationMessage>()
    for (index in 0 until items.length()) {
        val messageObject = items.optJSONObject(index) ?: continue
        decodePersistedConversationMessageSpec(messageObject, fallbackThreadId)?.let(decoded::add)
    }
    return decoded.sortedBy { it.createdAtEpochMs }
}

internal fun encodePersistedThreadTimelineMessagesSpec(messages: List<ConversationMessage>): String {
    val items = JSONArray()
    messages.forEach { message ->
        items.put(encodePersistedConversationMessageSpec(message))
    }
    return JSONObject()
        .put("v", 1)
        .put("messages", items)
        .toString()
}

internal fun decodePersistedStringListSpec(rawValue: String?): List<String> {
    val normalizedRawValue = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val items = runCatching { JSONArray(normalizedRawValue) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until items.length()) {
            val value = items.optString(index).trim().takeIf { it.isNotEmpty() } ?: continue
            if (value !in this) {
                add(value)
            }
        }
    }
}

internal fun encodePersistedStringListSpec(values: List<String>): String? {
    if (values.isEmpty()) {
        return null
    }
    val items = JSONArray()
    val seen = linkedSetOf<String>()
    values.forEach { value ->
        val normalizedValue = value.trim()
        if (normalizedValue.isNotEmpty() && seen.add(normalizedValue)) {
            items.put(normalizedValue)
        }
    }
    return items.takeIf { it.length() > 0 }?.toString()
}

private fun normalizeThreadTimelineScopeKey(value: String?): String? {
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

private fun normalizeThreadRuntimeOverrideScopeKey(value: String?): String? {
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

private fun threadTimelineScopeIndexKey(scopeKey: String): String {
    return "thread_timeline_scope.${encodedTimelinePreferenceSegment(scopeKey)}.index"
}

private fun threadTimelineEntryKey(scopeKey: String, threadId: String): String {
    return "thread_timeline_scope.${encodedTimelinePreferenceSegment(scopeKey)}.thread.${encodedTimelinePreferenceSegment(threadId)}"
}

private fun encodedTimelinePreferenceSegment(value: String): String {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun encodePersistedConversationMessageSpec(message: ConversationMessage): JSONObject {
    return JSONObject()
        .put("id", message.id)
        .put("threadId", message.threadId)
        .put("role", message.role.name)
        .put("kind", message.kind.name)
        .put("text", message.text)
        .put("attachments", JSONArray().apply {
            message.attachments.forEach { attachment ->
                put(
                    JSONObject()
                        .put("id", attachment.id)
                        .put("thumbnailBase64Jpeg", attachment.thumbnailBase64Jpeg)
                        .put("payloadDataUrl", attachment.payloadDataUrl)
                        .put("sourceUrl", attachment.sourceUrl)
                )
            }
        })
        .put("createdAtEpochMs", message.createdAtEpochMs)
        .put("turnId", message.turnId)
        .put("itemId", message.itemId)
        .put("isStreaming", message.isStreaming)
        .put("filePath", message.filePath)
        .put("status", message.status)
        .put("diffText", message.diffText)
        .put("command", message.command)
        .put("execution", message.execution?.let(::encodePersistedExecutionContentSpec) ?: JSONObject.NULL)
        .put("planExplanation", message.planExplanation)
        .put("planSteps", message.planSteps?.let(::encodePersistedPlanStepsSpec) ?: JSONObject.NULL)
        .put("subagentAction", message.subagentAction?.let(::encodePersistedSubagentActionSpec) ?: JSONObject.NULL)
}

private fun decodePersistedConversationMessageSpec(
    value: JSONObject,
    fallbackThreadId: String?,
): ConversationMessage? {
    val id = value.optString("id").trim().ifEmpty { return null }
    val threadId = value.optString("threadId").trim().ifEmpty { fallbackThreadId ?: return null }
    val role = enumValueOfOrNull<ConversationRole>(value.optString("role").trim()) ?: return null
    val kind = enumValueOfOrNull<ConversationKind>(value.optString("kind").trim()) ?: return null
    return ConversationMessage(
        id = id,
        threadId = threadId,
        role = role,
        kind = kind,
        text = value.optString("text"),
        attachments = decodePersistedImageAttachmentsSpec(value.optJSONArray("attachments")),
        createdAtEpochMs = value.optLong("createdAtEpochMs", 0L),
        turnId = value.optString("turnId").trim().ifEmpty { null },
        itemId = value.optString("itemId").trim().ifEmpty { null },
        isStreaming = false,
        filePath = value.optString("filePath").trim().ifEmpty { null },
        status = value.optString("status").trim().ifEmpty { null },
        diffText = value.optString("diffText").trim().ifEmpty { null },
        command = value.optString("command").trim().ifEmpty { null },
        execution = value.optJSONObject("execution")?.let(::decodePersistedExecutionContentSpec),
        planExplanation = value.optString("planExplanation").trim().ifEmpty { null },
        planSteps = value.optJSONArray("planSteps")?.let(::decodePersistedPlanStepsSpec),
        subagentAction = value.optJSONObject("subagentAction")?.let(::decodePersistedSubagentActionSpec),
    )
}

private fun encodePersistedExecutionContentSpec(value: ExecutionContent): JSONObject {
    return JSONObject()
        .put("kind", value.kind.name)
        .put("title", value.title)
        .put("status", value.status)
        .put("summary", value.summary)
        .put("output", value.output)
        .put("details", JSONArray().apply {
            value.details.forEach { detail ->
                put(
                    JSONObject()
                        .put("label", detail.label)
                        .put("value", detail.value)
                        .put("isMonospace", detail.isMonospace)
                )
            }
        })
}

private fun decodePersistedExecutionContentSpec(value: JSONObject): ExecutionContent? {
    val kind = enumValueOfOrNull<ExecutionKind>(value.optString("kind").trim()) ?: return null
    val title = value.optString("title").trim().ifEmpty { return null }
    val status = value.optString("status").trim().ifEmpty { return null }
    return ExecutionContent(
        kind = kind,
        title = title,
        status = status,
        summary = value.optString("summary").trim().ifEmpty { null },
        output = value.optString("output").trim().ifEmpty { null },
        details = buildList {
            val details = value.optJSONArray("details") ?: return@buildList
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                val label = detail.optString("label").trim().ifEmpty { continue }
                val text = detail.optString("value")
                add(
                    ExecutionDetail(
                        label = label,
                        value = text,
                        isMonospace = detail.optBoolean("isMonospace"),
                    )
                )
            }
        },
    )
}

private fun encodePersistedPlanStepsSpec(value: List<PlanStep>): JSONArray {
    return JSONArray().apply {
        value.forEach { step ->
            put(
                JSONObject()
                    .put("text", step.text)
                    .put("status", step.status)
            )
        }
    }
}

private fun decodePersistedPlanStepsSpec(value: JSONArray): List<PlanStep> {
    return buildList {
        for (index in 0 until value.length()) {
            val step = value.optJSONObject(index) ?: continue
            val text = step.optString("text").trim().ifEmpty { continue }
            add(
                PlanStep(
                    text = text,
                    status = step.optString("status").trim().ifEmpty { null },
                )
            )
        }
    }
}

private fun encodePersistedSubagentActionSpec(value: SubagentAction): JSONObject {
    val agentStates = JSONObject()
    value.agentStates.forEach { (threadId, state) ->
        val normalizedThreadId = threadId.trim().takeIf { it.isNotEmpty() } ?: return@forEach
        agentStates.put(
            normalizedThreadId,
            JSONObject()
                .put("threadId", state.threadId)
                .put("status", state.status)
                .put("message", state.message)
        )
    }
    return JSONObject()
        .put("tool", value.tool)
        .put("status", value.status)
        .put("prompt", value.prompt)
        .put("model", value.model)
        .put("receiverThreadIds", JSONArray(value.receiverThreadIds))
        .put("receiverAgents", JSONArray().apply {
            value.receiverAgents.forEach { receiver ->
                put(
                    JSONObject()
                        .put("threadId", receiver.threadId)
                        .put("agentId", receiver.agentId)
                        .put("nickname", receiver.nickname)
                        .put("role", receiver.role)
                        .put("model", receiver.model)
                        .put("prompt", receiver.prompt)
                )
            }
        })
        .put("agentStates", agentStates)
}

private fun decodePersistedSubagentActionSpec(value: JSONObject): SubagentAction? {
    val tool = value.optString("tool").trim().ifEmpty { return null }
    val status = value.optString("status").trim().ifEmpty { return null }
    return SubagentAction(
        tool = tool,
        status = status,
        prompt = value.optString("prompt").trim().ifEmpty { null },
        model = value.optString("model").trim().ifEmpty { null },
        receiverThreadIds = buildList {
            val threadIds = value.optJSONArray("receiverThreadIds") ?: return@buildList
            for (index in 0 until threadIds.length()) {
                val threadId = threadIds.optString(index).trim().ifEmpty { continue }
                if (threadId !in this) {
                    add(threadId)
                }
            }
        },
        receiverAgents = buildList {
            val receivers = value.optJSONArray("receiverAgents") ?: return@buildList
            for (index in 0 until receivers.length()) {
                val receiver = receivers.optJSONObject(index) ?: continue
                val threadId = receiver.optString("threadId").trim().ifEmpty { continue }
                add(
                    SubagentRef(
                        threadId = threadId,
                        agentId = receiver.optString("agentId").trim().ifEmpty { null },
                        nickname = receiver.optString("nickname").trim().ifEmpty { null },
                        role = receiver.optString("role").trim().ifEmpty { null },
                        model = receiver.optString("model").trim().ifEmpty { null },
                        prompt = receiver.optString("prompt").trim().ifEmpty { null },
                    )
                )
            }
        },
        agentStates = buildMap {
            val agentStates = value.optJSONObject("agentStates") ?: return@buildMap
            val keys = agentStates.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val agentState = agentStates.optJSONObject(key) ?: continue
                val threadId = agentState.optString("threadId").trim().ifEmpty { key.trim() }.ifEmpty { continue }
                val state = agentState.optString("status").trim().ifEmpty { continue }
                put(
                    threadId,
                    SubagentState(
                        threadId = threadId,
                        status = state,
                        message = agentState.optString("message").trim().ifEmpty { null },
                    )
                )
            }
        },
    )
}

private fun decodePersistedImageAttachmentsSpec(value: JSONArray?): List<ImageAttachment> {
    return buildList {
        val items = value ?: return@buildList
        for (index in 0 until items.length()) {
            val attachment = items.optJSONObject(index) ?: continue
            val id = attachment.optString("id").trim().ifEmpty { continue }
            val thumbnail = attachment.optString("thumbnailBase64Jpeg").trim().ifEmpty { continue }
            add(
                ImageAttachment(
                    id = id,
                    thumbnailBase64Jpeg = thumbnail,
                    payloadDataUrl = attachment.optString("payloadDataUrl").trim().ifEmpty { null },
                    sourceUrl = attachment.optString("sourceUrl").trim().ifEmpty { null },
                )
            )
        }
    }
}

private inline fun <reified T : Enum<T>> enumValueOfOrNull(value: String?): T? {
    val normalizedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return enumValues<T>().firstOrNull { it.name.equals(normalizedValue, ignoreCase = true) }
}
