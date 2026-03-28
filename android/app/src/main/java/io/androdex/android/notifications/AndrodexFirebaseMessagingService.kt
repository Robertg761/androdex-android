package io.androdex.android.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AndrodexFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val normalized = token.trim()
        if (normalized.isNotEmpty()) {
            AndrodexNotificationStore(applicationContext).savePushToken(normalized)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = decodeNotificationOpenPayload(message.data)
        if (payload == null || AndrodexAppProcessState.isForeground) {
            return
        }

        val title = message.data[AndrodexNotificationKeys.title]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Androdex"
        val body = message.data[AndrodexNotificationKeys.body]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Response ready"

        AndrodexNotificationPlatform.showRunCompletionNotification(
            context = applicationContext,
            notification = AndrodexRunCompletionNotification(
                threadId = payload.threadId,
                turnId = payload.turnId,
                title = title,
                body = body,
            ),
        )
    }
}
