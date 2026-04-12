package io.androdex.android.data

import io.androdex.android.model.AccessMode
import io.androdex.android.model.BackendKind
import io.androdex.android.model.ServiceTier
import io.androdex.android.model.ThreadRuntimeOverride

internal interface MacNativeClientPreferences {
    fun loadSelectedModelId(): String?

    fun saveSelectedModelId(value: String?)

    fun loadSelectedReasoningEffort(): String?

    fun saveSelectedReasoningEffort(value: String?)

    fun loadSelectedAccessMode(): AccessMode

    fun saveSelectedAccessMode(value: AccessMode)

    fun loadSelectedServiceTier(): ServiceTier?

    fun saveSelectedServiceTier(value: ServiceTier?)

    fun loadThreadRuntimeOverrides(scopeKey: String?): Map<String, ThreadRuntimeOverride>

    fun saveThreadRuntimeOverrides(scopeKey: String?, value: Map<String, ThreadRuntimeOverride>)

    fun savePreferredBackendKind(kind: BackendKind?)
}

internal class PersistenceMacNativeClientPreferences(
    private val persistence: AndrodexPersistence,
) : MacNativeClientPreferences {
    override fun loadSelectedModelId(): String? = persistence.loadSelectedModelId()

    override fun saveSelectedModelId(value: String?) {
        persistence.saveSelectedModelId(value)
    }

    override fun loadSelectedReasoningEffort(): String? = persistence.loadSelectedReasoningEffort()

    override fun saveSelectedReasoningEffort(value: String?) {
        persistence.saveSelectedReasoningEffort(value)
    }

    override fun loadSelectedAccessMode(): AccessMode = persistence.loadSelectedAccessMode()

    override fun saveSelectedAccessMode(value: AccessMode) {
        persistence.saveSelectedAccessMode(value)
    }

    override fun loadSelectedServiceTier(): ServiceTier? = persistence.loadSelectedServiceTier()

    override fun saveSelectedServiceTier(value: ServiceTier?) {
        persistence.saveSelectedServiceTier(value)
    }

    override fun loadThreadRuntimeOverrides(scopeKey: String?): Map<String, ThreadRuntimeOverride> {
        return persistence.loadThreadRuntimeOverrides(scopeKey = scopeKey)
    }

    override fun saveThreadRuntimeOverrides(
        scopeKey: String?,
        value: Map<String, ThreadRuntimeOverride>,
    ) {
        persistence.saveThreadRuntimeOverrides(scopeKey = scopeKey, value = value)
    }

    override fun savePreferredBackendKind(kind: BackendKind?) {
        persistence.savePreferredBackendKind(kind)
    }
}
