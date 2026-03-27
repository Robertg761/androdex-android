package io.androdex.android.notifications

import android.content.Context

internal class AndrodexNotificationStore(context: Context) {
    private val prefs = context.getSharedPreferences("androdex_notifications", Context.MODE_PRIVATE)

    fun loadPushToken(): String? {
        return prefs.getString(KEY_PUSH_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun savePushToken(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY_PUSH_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_PUSH_TOKEN, normalized).apply()
        }
    }

    fun hasPromptedForPermission(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS_PROMPTED, false)

    fun markPromptedForPermission() {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_PROMPTED, true).apply()
    }

    private companion object {
        const val KEY_PUSH_TOKEN = "push_token"
        const val KEY_NOTIFICATIONS_PROMPTED = "notifications_prompted"
    }
}
