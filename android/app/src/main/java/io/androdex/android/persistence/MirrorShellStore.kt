package io.androdex.android.persistence

import android.content.Context

data class MirrorShellSnapshot(
    val pairedOrigin: String?,
    val displayLabel: String?,
    val lastOpenedUrl: String?,
)

class MirrorShellStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSnapshot(): MirrorShellSnapshot {
        return MirrorShellSnapshot(
            pairedOrigin = prefs.getString(KEY_PAIRED_ORIGIN, null)?.trim()?.takeIf { it.isNotEmpty() },
            displayLabel = prefs.getString(KEY_DISPLAY_LABEL, null)?.trim()?.takeIf { it.isNotEmpty() },
            lastOpenedUrl = prefs.getString(KEY_LAST_OPENED_URL, null)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun savePairing(origin: String, displayLabel: String?) {
        prefs.edit()
            .putString(KEY_PAIRED_ORIGIN, origin.trim())
            .putString(KEY_DISPLAY_LABEL, displayLabel?.trim().takeUnless { it.isNullOrEmpty() })
            .apply()
    }

    fun saveLastOpenedUrl(url: String?) {
        val normalized = url?.trim().takeUnless { it.isNullOrEmpty() }
        prefs.edit().putString(KEY_LAST_OPENED_URL, normalized).apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_PAIRED_ORIGIN)
            .remove(KEY_DISPLAY_LABEL)
            .remove(KEY_LAST_OPENED_URL)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "androdex_web_shell"
        const val KEY_PAIRED_ORIGIN = "paired_origin"
        const val KEY_DISPLAY_LABEL = "display_label"
        const val KEY_LAST_OPENED_URL = "last_opened_url"
    }
}
