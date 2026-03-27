package io.androdex.android.data

import android.content.Context
import io.androdex.android.crypto.SecureStore
import io.androdex.android.model.AccessMode
import io.androdex.android.model.PairingPayload
import io.androdex.android.model.PhoneIdentityState
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.ThreadRuntimeOverride
import io.androdex.android.model.TrustedMacRegistry
import org.json.JSONObject

class AndrodexPersistence(context: Context) {
    private val secureStore = SecureStore(context)
    private var startupNotice: String? = sanitizeUnreadableSecureState()

    fun hasSavedPairing(): Boolean = loadPairing() != null

    fun takeStartupNotice(): String? {
        val notice = startupNotice
        startupNotice = null
        return notice
    }

    fun loadPairing(): PairingPayload? {
        val raw = secureStore.readString(KEY_PAIRING) ?: return null
        return runCatching { PairingPayload.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun savePairing(pairingPayload: PairingPayload) {
        secureStore.writeString(KEY_PAIRING, pairingPayload.toJson().toString())
    }

    fun clearPairing() {
        secureStore.remove(KEY_PAIRING)
        secureStore.remove(KEY_LAST_APPLIED_SEQ)
    }

    fun loadPhoneIdentity(): PhoneIdentityState? {
        val raw = secureStore.readString(KEY_PHONE_IDENTITY) ?: return null
        return runCatching { PhoneIdentityState.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun savePhoneIdentity(identityState: PhoneIdentityState) {
        secureStore.writeString(KEY_PHONE_IDENTITY, identityState.toJson().toString())
    }

    fun loadTrustedMacRegistry(): TrustedMacRegistry {
        val raw = secureStore.readString(KEY_TRUSTED_MACS) ?: return TrustedMacRegistry.empty
        return runCatching { TrustedMacRegistry.fromJson(JSONObject(raw)) }.getOrDefault(TrustedMacRegistry.empty)
    }

    fun saveTrustedMacRegistry(registry: TrustedMacRegistry) {
        secureStore.writeString(KEY_TRUSTED_MACS, registry.toJson().toString())
    }

    fun loadLastAppliedBridgeOutboundSeq(): Int {
        return secureStore.readString(KEY_LAST_APPLIED_SEQ)?.toIntOrNull() ?: 0
    }

    fun saveLastAppliedBridgeOutboundSeq(value: Int) {
        secureStore.writeString(KEY_LAST_APPLIED_SEQ, value.toString())
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
        return decodeThreadRuntimeOverridesSpec(secureStore.readString(KEY_THREAD_RUNTIME_OVERRIDES))
    }

    fun saveThreadRuntimeOverrides(value: Map<String, ThreadRuntimeOverride>) {
        val encoded = encodeThreadRuntimeOverridesSpec(value)
        if (encoded == null) {
            secureStore.remove(KEY_THREAD_RUNTIME_OVERRIDES)
        } else {
            secureStore.writeString(KEY_THREAD_RUNTIME_OVERRIDES, encoded)
        }
    }

    private fun sanitizeUnreadableSecureState(): String? {
        val pairingState = secureStore.readStringState(KEY_PAIRING)
        val phoneIdentityState = secureStore.readStringState(KEY_PHONE_IDENTITY)
        val trustedMacState = secureStore.readStringState(KEY_TRUSTED_MACS)
        val lastAppliedSeqState = secureStore.readStringState(KEY_LAST_APPLIED_SEQ)
        val selectedModelState = secureStore.readStringState(KEY_SELECTED_MODEL_ID)
        val selectedReasoningState = secureStore.readStringState(KEY_SELECTED_REASONING_EFFORT)
        val selectedAccessModeState = secureStore.readStringState(KEY_SELECTED_ACCESS_MODE)
        val selectedServiceTierState = secureStore.readStringState(KEY_SELECTED_SERVICE_TIER)
        val threadRuntimeOverridesState = secureStore.readStringState(KEY_THREAD_RUNTIME_OVERRIDES)

        val lostSecurePairingState = pairingState.isUnreadable
            || phoneIdentityState.isUnreadable
            || trustedMacState.isUnreadable
            || lastAppliedSeqState.isUnreadable

        if (lostSecurePairingState) {
            clearPairing()
            secureStore.remove(KEY_PHONE_IDENTITY)
            secureStore.remove(KEY_TRUSTED_MACS)
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

        return if (lostSecurePairingState) {
            "Saved pairing from a previous Android install or device restore is no longer readable on this device. Scan a fresh QR code to pair again."
        } else {
            null
        }
    }

    private companion object {
        const val KEY_PAIRING = "pairing_payload"
        const val KEY_PHONE_IDENTITY = "phone_identity"
        const val KEY_TRUSTED_MACS = "trusted_macs"
        const val KEY_LAST_APPLIED_SEQ = "last_applied_bridge_outbound_seq"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_SELECTED_REASONING_EFFORT = "selected_reasoning_effort"
        const val KEY_SELECTED_ACCESS_MODE = "selected_access_mode"
        const val KEY_SELECTED_SERVICE_TIER = "selected_service_tier"
        const val KEY_THREAD_RUNTIME_OVERRIDES = "thread_runtime_overrides"
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
    if (value.isEmpty()) {
        return null
    }

    val payload = JSONObject()
    value.forEach { (threadId, runtimeOverride) ->
        val normalizedThreadId = threadId.trim()
        val normalizedOverride = runtimeOverride.normalized()
        if (normalizedThreadId.isEmpty() || normalizedOverride == null) {
            return@forEach
        }
        payload.put(normalizedThreadId, encodeThreadRuntimeOverrideSpec(normalizedOverride))
    }

    return payload.takeIf { it.length() > 0 }?.toString()
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
