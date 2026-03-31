package io.androdex.android.notifications

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexNotificationSupportTest {
    @Test
    fun notificationAlertsEnabled_allowsPreTiramisuWhenNotificationsEnabled() {
        assertTrue(
            notificationAlertsEnabled(
                sdkInt = Build.VERSION_CODES.S,
                permissionGranted = false,
                notificationsEnabled = true,
            )
        )
    }

    @Test
    fun notificationAlertsEnabled_blocksTiramisuWithoutPermission() {
        assertFalse(
            notificationAlertsEnabled(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = false,
                notificationsEnabled = true,
            )
        )
    }

    @Test
    fun notificationAlertsEnabled_blocksWhenNotificationsDisabled() {
        assertFalse(
            notificationAlertsEnabled(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = true,
                notificationsEnabled = false,
            )
        )
    }

    @Test
    fun notificationAlertsEnabled_allowsTiramisuWhenPermissionGrantedAndNotificationsEnabled() {
        assertTrue(
            notificationAlertsEnabled(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = true,
                notificationsEnabled = true,
            )
        )
    }

    @Test
    fun postNotificationBestEffort_swallowsSecurityException() {
        assertFalse(
            postNotificationBestEffort {
                throw SecurityException("permission denied")
            }
        )
    }

    @Test
    fun postNotificationBestEffort_reportsSuccessWhenPostSucceeds() {
        assertTrue(postNotificationBestEffort { })
    }
}
