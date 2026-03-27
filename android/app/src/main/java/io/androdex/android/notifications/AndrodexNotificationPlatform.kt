package io.androdex.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.androdex.android.MainActivity
import io.androdex.android.R

internal object AndrodexNotificationPlatform {
    fun ensureRunCompletionChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(RUN_COMPLETION_NOTIFICATION_CHANNEL_ID)
        if (existing != null) {
            return
        }

        manager.createNotificationChannel(
            NotificationChannel(
                RUN_COMPLETION_NOTIFICATION_CHANNEL_ID,
                "Run completions",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Androdex completion alerts while the app is backgrounded."
            }
        )
    }

    fun showRunCompletionNotification(
        context: Context,
        notification: AndrodexRunCompletionNotification,
    ) {
        ensureRunCompletionChannel(context)

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AndrodexNotificationKeys.source, RUN_COMPLETION_NOTIFICATION_SOURCE)
            .putExtra(AndrodexNotificationKeys.threadId, notification.threadId)
            .putExtra(AndrodexNotificationKeys.turnId, notification.turnId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.threadId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(context, RUN_COMPLETION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notification.threadId.hashCode(), built)
    }
}
