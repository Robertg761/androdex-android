package io.androdex.android.notifications

import android.os.Build

internal fun notificationAlertsEnabled(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean {
    val hasRuntimePermission = sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted
    return hasRuntimePermission && notificationsEnabled
}

internal inline fun postNotificationBestEffort(post: () -> Unit): Boolean {
    return try {
        post()
        true
    } catch (_: SecurityException) {
        false
    }
}
